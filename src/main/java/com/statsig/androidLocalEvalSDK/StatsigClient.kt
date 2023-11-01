package com.statsig.androidLocalEvalSDK

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"

class StatsigClient {
    internal var errorBoundary: ErrorBoundary = ErrorBoundary()
    internal lateinit var options: StatsigOptions
    internal lateinit var statsigNetwork: StatsigNetwork
    private lateinit var evaluator: Evaluator
    private lateinit var application: Application
    private lateinit var sdkKey: String
    private lateinit var statsigMetadata: StatsigMetadata
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private lateinit var statsigScope: CoroutineScope
    private lateinit var specStore: Store
    private lateinit var sharedPrefs: SharedPreferences

    private var pollingJob: Job? = null
    private var statsigJob = SupervisorJob()
    private var dispatcherProvider = CoroutineDispatcherProvider()
    private var initialized = AtomicBoolean(false)
    private var isBootstrapped = AtomicBoolean(false)

    suspend fun initialize(
        application: Application,
        sdkKey: String,
        options: StatsigOptions = StatsigOptions(),
    ): InitializationDetails? {
        if (this@StatsigClient.isInitialized()) {
            return null
        }
        return errorBoundary.captureAsync {
            setup(application, sdkKey, options)
            return@captureAsync setupAsync()
        }
    }

    fun initializeAsync(
        application: Application,
        sdkKey: String,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions(),
    ) {
        errorBoundary.capture({
            setup(application, sdkKey, options)
            statsigScope.launch {
                val initDetails = setupAsync()
                try {
                    callback?.onStatsigInitialize(initDetails)
                } catch (e: Exception) {
                    throw ExternalException(e.message)
                }
            }
        })
    }

    fun checkGate(user: StatsigUser, gateName: String): Boolean {
        enforceInitialized("checkGate")
        var result = false
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            result = evaluator.checkGate(normalizedUser, gateName).booleanValue
        }, tag = "checkGate")
        return result
    }

    fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        enforceInitialized("getExperiment")
        var result = DynamicConfig.empty()
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, experimentName)
            result = getDynamicConfigFromEvalResult(evaluation, normalizedUser, experimentName)
        }, tag = "getExperiment")
        return result
    }

    fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        enforceInitialized("getConfig")
        var result = DynamicConfig.empty()
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, dynamicConfigName)
            result = getDynamicConfigFromEvalResult(evaluation, normalizedUser, dynamicConfigName)
        }, tag = "getConfig")
        return result
    }

    fun getLayer(user: StatsigUser, layerName: String): Layer {
        enforceInitialized("getLayer")
        var result = Layer.empty(layerName)
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getLayer(normalizedUser, layerName)
            result = Layer(layerName, evaluation.ruleID, evaluation.groupName, evaluation.jsonValue as? Map<String, Any> ?: mapOf(), evaluation.secondaryExposures, evaluation.configDelegate ?: "")
        }, tag = "getConfig")
        return result
    }

    private fun setup(
        application: Application,
        sdkKey: String,
        options: StatsigOptions = StatsigOptions(),
    ) {
        if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
            throw IllegalArgumentException("Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console")
        }
        errorBoundary.setKey(sdkKey)
        this.application = application
        this.sdkKey = sdkKey
        this.options = options
        sharedPrefs = application.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        statsigNetwork = StatsigNetwork(sdkKey)
        statsigMetadata = StatsigMetadata()
        errorBoundary.setMetadata(statsigMetadata)
        exceptionHandler = errorBoundary.getExceptionHandler()
        populateStatsigMetadata()
        statsigScope = CoroutineScope(statsigJob + dispatcherProvider.main + exceptionHandler)
        specStore = Store(sdkKey, statsigNetwork, options, statsigMetadata, statsigScope, sharedPrefs, errorBoundary)
        evaluator = Evaluator(specStore, statsigNetwork, options, statsigMetadata, statsigScope, errorBoundary)
        // load cache
        if (!options.loadCacheAsync) {
            specStore.syncLoadFromLocalStorage()
        }
        // load from initialized values if available
        val initializeValues = options.initializeValues
        if (initializeValues != null) {
            specStore.bootstrap(initializeValues)
            isBootstrapped.set(true)
        }
        this.initialized.set(true)
    }

    private suspend fun setupAsync(): InitializationDetails {
        return withContext(dispatcherProvider.io) {
            val initStartTime = StatsigUtils.getTimeInMillis()
            return@withContext errorBoundary.captureAsync({
                if (this@StatsigClient.isBootstrapped.get()) {
                    return@captureAsync InitializationDetails(System.currentTimeMillis() - initStartTime, true, null)
                }
                // load cache
                if (options.loadCacheAsync) {
                    specStore.syncLoadFromLocalStorage()
                }
                val initializeDetail = specStore.fetchAndSave()
                initializeDetail.duration = StatsigUtils.getTimeInMillis() - initStartTime
                return@captureAsync initializeDetail
            }, { e: Exception ->
                return@captureAsync InitializationDetails(StatsigUtils.getTimeInMillis() - initStartTime, false, InitializeResponse.FailedInitializeResponse(InitializeFailReason.InternalError, e))
            })
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        var normalizedUser = StatsigUser(null)
        if (user != null) {
            normalizedUser = user.getCopyForEvaluation()
        }
        normalizedUser.statsigEnvironment = options.getEnvironment()
        return normalizedUser
    }

    private fun populateStatsigMetadata() {
        statsigMetadata.overrideStableID(options.overrideStableID)

        val stringID: Int? = application.applicationInfo?.labelRes
        if (stringID != null) {
            if (stringID == 0) {
                application.applicationInfo.nonLocalizedLabel.toString()
            } else {
                application.getString(stringID)
            }
        }

        try {
            if (application.packageManager != null) {
                val pInfo: PackageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
                statsigMetadata.appVersion = pInfo.versionName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // noop
        }
    }

    internal fun isInitialized(): Boolean {
        return this.initialized.get()
    }

    internal fun enforceInitialized(functionName: String) {
        if (!this.initialized.get()) {
            throw IllegalStateException("The SDK must be initialized prior to invoking $functionName")
        }
    }

    suspend fun shutdownSuspend() {
        enforceInitialized("shutdownSuspend")
        errorBoundary.captureAsync {
            shutdownImpl()
        }
    }

    private suspend fun shutdownImpl() {
        pollingJob?.cancel()
        initialized = AtomicBoolean()
        isBootstrapped = AtomicBoolean()
        errorBoundary = ErrorBoundary()
        statsigJob = SupervisorJob()
    }

    private fun getDynamicConfigFromEvalResult(result: ConfigEvaluation, user: StatsigUser, configName: String): DynamicConfig {
        return DynamicConfig(configName, result.jsonValue as? Map<String, Any> ?: mapOf(), result.ruleID, result.groupName, result.secondaryExposures)
    }
}
