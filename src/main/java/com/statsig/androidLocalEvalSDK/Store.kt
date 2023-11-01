package com.statsig.androidLocalEvalSDK

import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException

private const val CACHE_BY_SDK_KEY: String = "Statsig.CACHE_BY_KEY"

internal class Store(
    private val sdkKey: String,
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
    private val sharedPrefs: SharedPreferences,
    private val errorBoundary: ErrorBoundary,
) {
    internal var initReason: EvaluationReason = EvaluationReason.UNINITIALIZED
    internal var lcut: Long = 0

    private var gson = StatsigUtils.getGson()
    private val dispatcherProvider = CoroutineDispatcherProvider()

    private var dynamicConfigs: Map<String, APIConfig> = emptyMap()
    private var gates: Map<String, APIConfig> = emptyMap()
    private var layerConfigs: Map<String, APIConfig> = emptyMap()
    private var experimentToLayer: Map<String, String> = emptyMap()
    private var cacheByKey: MutableMap<String, String> = mutableMapOf()

    fun getGate(name: String): APIConfig? {
        return this.gates[name]
    }

    fun getConfig(name: String): APIConfig? {
        return this.dynamicConfigs[name]
    }

    fun getLayerConfig(name: String): APIConfig? {
        return this.layerConfigs[name]
    }

    fun syncLoadFromLocalStorage() {
        val cachedConfigSpecs = StatsigUtils.syncGetFromSharedPrefs(sharedPrefs, CACHE_BY_SDK_KEY)
        if (cachedConfigSpecs != null) {
            try {
                val cacheByKeyType = object : TypeToken<MutableMap<String, String>>() {}.type
                cacheByKey = gson.fromJson(cachedConfigSpecs, cacheByKeyType)
                val currentCache = gson.fromJson(cacheByKey[sdkKey], APIDownloadedConfigs::class.java)
                setConfigSpecs(currentCache)
            } catch (e: Exception) {
                statsigScope.launch(dispatcherProvider.io) {
                    StatsigUtils.removeFromSharedPrefs(sharedPrefs, CACHE_BY_SDK_KEY)
                }
            }
        }
    }

    fun bootstrap(initializeValues: String) {
        errorBoundary.capture(
            {
                var parsedConfigSpecs: APIDownloadedConfigs? = null
                initReason = EvaluationReason.INVALID_BOOTSTRAP
                parsedConfigSpecs = gson.fromJson(initializeValues, APIDownloadedConfigs::class.java)
                if (parsedConfigSpecs != null) {
                    setConfigSpecs(parsedConfigSpecs)
                    initReason = EvaluationReason.BOOTSTRAP
                }
            },
            tag = "bootstrap",
            recover = { initReason = EvaluationReason.INVALID_BOOTSTRAP },
        )
    }

    suspend fun fetchAndSave(): InitializationDetails {
        // network fetch
        var statusCode: Int? = null
        return errorBoundary.captureAsync({
            val endpoint = network.getURLForDownloadConfigSpec(options.configSpecApi)
            val downloadedConfigs = if (options.initTimeoutMs == 0) {
                network.getRequest<APIDownloadedConfigs>(
                    endpoint,
                    2,
                    null,
                    { code: Int? -> statusCode = code },
                )
            } else {
                withTimeout(options.initTimeoutMs.toLong()) {
                    network.getRequest<APIDownloadedConfigs>(
                        endpoint,
                        2,
                        options.initTimeoutMs,
                        { code: Int? -> statusCode = code },
                    )
                }
            }
            if (downloadedConfigs != null) {
                setConfigSpecs(downloadedConfigs)
                cacheByKey[sdkKey] = gson.toJson(downloadedConfigs)
                StatsigUtils.saveStringToSharedPrefs(sharedPrefs, CACHE_BY_SDK_KEY, gson.toJson(cacheByKey))
                initReason = EvaluationReason.NETWORK
                return@captureAsync InitializationDetails(0L, true, null)
            }
            return@captureAsync InitializationDetails(0L, false, InitializeResponse.FailedInitializeResponse(InitializeFailReason.NetworkError, null, statusCode))
        }, {
            val failedResponse = when (it) {
                is SocketTimeoutException, is ConnectException -> {
                    InitializeResponse.FailedInitializeResponse(InitializeFailReason.NetworkTimeout, it)
                }

                is TimeoutCancellationException -> {
                    InitializeResponse.FailedInitializeResponse(InitializeFailReason.CoroutineTimeout, it)
                }

                else -> {
                    InitializeResponse.FailedInitializeResponse(InitializeFailReason.InternalError, it)
                }
            }
            return@captureAsync InitializationDetails(0L, false, failedResponse)
        })
    }

    private fun setConfigSpecs(configSpecs: APIDownloadedConfigs) {
        if (!configSpecs.hasUpdates) {
            return
        }
        val newGates = getParsedSpecs(configSpecs.featureGates)
        val newDynamicConfigs = getParsedSpecs(configSpecs.dynamicConfigs)
        val newLayerConfigs = getParsedSpecs(configSpecs.layerConfigs)
        val newExperimentToLayer = emptyMap<String, String>().toMutableMap()
        val layersMap = configSpecs.layers
        if (layersMap != null) {
            for (layerName in layersMap.keys) {
                val experiments = layersMap[layerName] ?: continue
                for (experimentName in experiments) {
                    newExperimentToLayer[experimentName] = layerName
                }
            }
        }
        this.gates = newGates
        this.dynamicConfigs = newDynamicConfigs
        this.layerConfigs = newLayerConfigs
        this.experimentToLayer = newExperimentToLayer
        this.lcut = configSpecs.time
    }

    private fun getParsedSpecs(values: Array<APIConfig>): Map<String, APIConfig> {
        val parsed: MutableMap<String, APIConfig> = mutableMapOf()
        var specName: String?
        for (value in values) {
            specName = value.name
            parsed[specName] = value
        }
        return parsed
    }
}
