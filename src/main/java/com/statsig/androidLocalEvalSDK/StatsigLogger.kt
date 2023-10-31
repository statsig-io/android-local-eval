package com.statsig.androidLocalEvalSDK

import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val CONFIG_EXPOSURE_EVENT = "statsig::config_exposure"
internal const val LAYER_EXPOSURE_EVENT = "statsig::layer_exposure"
internal const val GATE_EXPOSURE_EVENT = "statsig::gate_exposure"
private const val EXPOSURE_DEDUPE_INTERVAL: Long = 10 * 60 * 1000
internal const val MAX_EVENTS: Int = 50
internal const val FLUSH_TIMER_MS: Long = 60000
internal const val SHUTDOWN_WAIT_S: Long = 3
internal val MAX_LOG_PERIOD = TimeUnit.DAYS.toMillis(3)

internal data class StatsigOfflineRequest(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("requestBody") val requestBody: String,
)

internal class StatsigLogger(
    private val coroutineScope: CoroutineScope,
    private val network: StatsigNetwork,
    private val statsigMetadata: StatsigMetadata,
) {
    private var events = ConcurrentLinkedQueue<LogEvent>()
    private val loggedExposures = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newSingleThreadExecutor()
    private val singleThreadDispatcher = executor.asCoroutineDispatcher()
    private val timer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }

    internal suspend fun flush() {
        withContext(singleThreadDispatcher) {
            if (events.size == 0) {
                return@withContext
            }
            val postEvents = ArrayList(events)
            events = ConcurrentLinkedQueue<LogEvent>()
            network.postLogs(postEvents, statsigMetadata)
        }
    }

    internal suspend fun log(event: LogEvent) {
        withContext(singleThreadDispatcher) {
            events.add(event)
            if (events.size >= MAX_EVENTS) {
                flush()
            }
        }
    }

    fun logGateExposure(
        user: StatsigUser,
        gateName: String,
        value: Boolean,
        ruleID: String,
        secondaryExposures: ArrayList<Map<String, String>>,
        isManualExposure: Boolean = false,
        evaluationDetails: EvaluationDetails?,
    ) {
        val dedupeKey = "$gateName:$ruleID:${evaluationDetails?.reason}"
        if (!shouldLogExposure(user, dedupeKey)) {
            return
        }
        coroutineScope.launch(singleThreadDispatcher) {
            val metadata = mutableMapOf(
                "gate" to gateName,
                "gateValue" to value.toString(),
                "ruleID" to ruleID,
                "isManualExposure" to isManualExposure.toString(),
            )
            if (evaluationDetails != null) {
                metadata["reason"] = evaluationDetails.reason.toString()
                metadata["time"] = evaluationDetails.configSyncTime.toString()
            }
            val event = LogEvent(
                GATE_EXPOSURE_EVENT,
                eventValue = null,
                metadata,
                user,
                statsigMetadata,
                secondaryExposures,
            )
            log(event)
        }
    }

    fun logConfigExposure(
        user: StatsigUser,
        configName: String,
        ruleID: String,
        secondaryExposures: ArrayList<Map<String, String>>,
        isManualExposure: Boolean,
        evaluationDetails: EvaluationDetails?,
    ) {
        val dedupeKey = "$configName:$ruleID:${evaluationDetails?.reason}"
        if (!shouldLogExposure(user, dedupeKey)) {
            return
        }
        coroutineScope.launch(singleThreadDispatcher) {
            val metadata =
                mutableMapOf("config" to configName, "ruleID" to ruleID, "isManualExposure" to isManualExposure.toString())
            if (evaluationDetails != null) {
                metadata["reason"] = evaluationDetails.reason.toString()
                metadata["time"] = evaluationDetails.configSyncTime.toString()
            }
            val event = LogEvent(
                CONFIG_EXPOSURE_EVENT,
                eventValue = null,
                metadata,
                user,
                statsigMetadata,
                secondaryExposures,
            )
            log(event)
        }
    }

    fun logLayerExposure(
        user: StatsigUser,
        layerExposureMetadata: LayerExposureMetadata,
        isManualExposure: Boolean = false,
    ) {
        val dedupeKey = "${layerExposureMetadata.config}:${layerExposureMetadata.ruleID}:${layerExposureMetadata.allocatedExperiment}:${layerExposureMetadata.parameterName}:${layerExposureMetadata.isExplicitParameter}:${layerExposureMetadata.evaluationDetails?.reason}"
        if (!shouldLogExposure(user, dedupeKey)) {
            return
        }
        coroutineScope.launch(singleThreadDispatcher) {
            if (isManualExposure) {
                layerExposureMetadata.isManualExposure = "true"
            }
            val metadata = layerExposureMetadata.toStatsigEventMetadataMap()
            val evaluationDetails = layerExposureMetadata.evaluationDetails
            if (evaluationDetails != null) {
                metadata["reason"] = evaluationDetails.reason.toString()
                metadata["time"] = evaluationDetails.configSyncTime.toString()
            }
            val event = LogEvent(
                LAYER_EXPOSURE_EVENT,
                eventValue = null,
                metadata,
                user,
                statsigMetadata,
                layerExposureMetadata.secondaryExposures,
            )
            log(event)
        }
    }

    fun retryFailedLog(sharedPrefs: SharedPreferences) {
        coroutineScope.launch (CoroutineDispatcherProvider().io){
            val savedLogs = StatsigUtils.getSavedLogs(sharedPrefs)
            if(savedLogs.isEmpty()) {
               return@launch
            }
            StatsigUtils.removeFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY)
            savedLogs.map {
                network.postLogs(it.requestBody)
            }
        }
    }

    suspend fun shutdown() {
        timer.cancel()
        flush()

        executor.shutdown()
        runCatching {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_S, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }.onFailure {
            executor.shutdownNow()
        }
    }

    private fun shouldLogExposure(user: StatsigUser, key: String): Boolean {
        val customKey = user.getCacheKey()
        val dedupeKey = "$customKey:$key"
        val now = System.currentTimeMillis()
        val lastTime = loggedExposures[dedupeKey] ?: 0
        if (lastTime >= now - EXPOSURE_DEDUPE_INTERVAL) {
            return false
        }
        loggedExposures[key] = now
        return true
    }
}
