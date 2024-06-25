package com.statsig.androidlocalevalsdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class StatsigClient {
    internal var errorBoundary: ErrorBoundary = ErrorBoundary()
    private lateinit var options: StatsigOptions

    @VisibleForTesting
    internal lateinit var statsigNetwork: StatsigNetwork
    private lateinit var evaluator: Evaluator
    private lateinit var statsigLogger: StatsigLogger
    private lateinit var application: Application
    private lateinit var sdkKey: String
    private lateinit var statsigMetadata: StatsigMetadata
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private lateinit var statsigScope: CoroutineScope
    private lateinit var specStore: Store
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var diagnostics: Diagnostics

    private var pollingJob: Job? = null
    private var statsigJob = SupervisorJob()
    private var dispatcherProvider = CoroutineDispatcherProvider()
    private var persistentStorage: StatsigUserPersistenStorageHelper? = null
    private var initialized = AtomicBoolean(false)
    private var isBootstrapped = AtomicBoolean(false)

    /**
     * Initializes the SDK for the given user
     * @param application - the Android application Statsig is operating in
     * @param sdkKey - a client or test SDK Key from the Statsig console
     * @param options - advanced SDK setup
     * @return data class containing initialization details (e.g. duration, success), null otherwise
     * @throws IllegalArgumentException if and Invalid SDK Key provided
     * Checking Gates/Configs before initialization calls back will return default values
     * Logging Events before initialization will drop those events
     * Susequent calls to initialize will be ignored.  To switch the user or update user values,
     * use updateUser()
     */
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

    /**
     * Initializes the SDK for the given user.  Initialization is complete when the callback
     * is invoked
     * @param application - the Android application Statsig is operating in
     * @param sdkKey - a client or test SDK Key from the Statsig console
     * @param callback - a callback to execute when initialization is complete
     * @param options - advanced SDK setup
     * Checking Gates/Configs before initialization calls back will return default values
     * Logging Events before initialization will drop those events
     * Susequent calls to initialize will be ignored.  To switch the user or update user values,
     * use updateUser()
     */
    @JvmOverloads
    fun initializeAsync(
        application: Application,
        sdkKey: String,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions(),
    ) {
        if (this@StatsigClient.isInitialized()) {
            return
        }
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

    /**
     * Get the boolean result of a gate, evaluated against a given user.
     * An exposure event will automatically be logged for the gate.
     *
     * @param user A StatsigUser object used for evaluation
     * @param gateName The name of the gate being evaluated
     * @param option advanced setup for checkGate, for example disable exposure logging
     */
    @JvmOverloads
    fun checkGate(user: StatsigUser, gateName: String, option: CheckGateOptions? = null): Boolean {
        if (!isInitialized("checkGate")) {
            return false
        }
        var result = false
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.checkGate(normalizedUser, gateName)
            result = evaluation.booleanValue
            if (option?.disableExposureLogging !== true) {
                logGateExposureImpl(normalizedUser, gateName, evaluation)
            }
        }, tag = "checkGate", configName = gateName)
        return result
    }

    /**
     * Log an exposure for a given gate
     * @param user A StatsigUser object used for logging
     * @param gateName the name of the gate to log an exposure for
     */
    fun logGateExposure(user: StatsigUser, gateName: String) {
        if (!isInitialized("logGateExposure")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.checkGate(normalizedUser, gateName)
            logGateExposureImpl(normalizedUser, gateName, evaluation, true)
        }, tag = "logGateExposure", configName = gateName)
    }

    /**
     * Check the value of an Experiment configured in the Statsig console
     * @param user A StatsigUser object used for the evaluation
     * @param experimentName the name of the Experiment to check
     * @param option advanced setup for getExperiment, for example disable exposure logging
     * @return the Dynamic Config backing the experiment
     */
    @JvmOverloads
    fun getExperiment(user: StatsigUser, experimentName: String, option: GetExperimentOptions? = null): DynamicConfig {
        var result = DynamicConfig.empty()
        if (!isInitialized("getExperiment")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, experimentName, option?.userPersistedValues)
            result = getDynamicConfigFromEvalResult(evaluation, experimentName)
            if (option?.disableExposureLogging !== true) {
                logConfigExposureImpl(normalizedUser, experimentName, evaluation)
            }
        }, tag = "getExperiment", configName = experimentName)
        return result
    }

    /**
     * Log an exposure for a given experiment
     * @param user A StatsigUser object used for logging
     * @param experimentName the name of the experiment to log an exposure for
     */
    fun logExperimentExposure(user: StatsigUser, experimentName: String) {
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, experimentName)
            logConfigExposureImpl(normalizedUser, experimentName, evaluation, true)
        }, tag = "logExperimentExposure", configName = experimentName)
    }

    /**
     * Get the values of a DynamicConfig, evaluated against the given user.
     * An exposure event will automatically be logged for the DynamicConfig.
     *
     * @param user A StatsigUser object used for evaluation
     * @param dynamicConfigName The name of the DynamicConfig
     * @param option advanced setup for getConfig, for example disable exposure logging
     * @return DynamicConfig object evaluated for the selected StatsigUser
     */
    @JvmOverloads
    fun getConfig(user: StatsigUser, dynamicConfigName: String, option: GetConfigOptions? = null): DynamicConfig {
        var result = DynamicConfig.empty()
        if (!isInitialized("getExperiment")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, dynamicConfigName)
            result = getDynamicConfigFromEvalResult(evaluation, dynamicConfigName)
            if (option?.disableExposureLogging !== true) {
                logConfigExposureImpl(normalizedUser, dynamicConfigName, evaluation)
            }
        }, tag = "getConfig", configName = dynamicConfigName)
        return result
    }

    /**
     * Log an exposure for a given config
     * @param user A StatsigUser object used for logging
     * @param configName the name of the experiment to log an exposure for
     */
    fun logConfigExposure(user: StatsigUser, dynamicConfigName: String) {
        if (!isInitialized("logConfigExposure")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, dynamicConfigName)
            logConfigExposureImpl(normalizedUser, dynamicConfigName, evaluation, true)
        }, tag = "logConfigExposure", configName = dynamicConfigName)
    }

    /**
     * @param user A StatsigUser object used for the evaluation
     * @param layerName the name of the Experiment to check
     * @return the current layer values as a Layer object
     */
    @JvmOverloads
    fun getLayer(user: StatsigUser, layerName: String, option: GetLayerOptions? = null): Layer {
        var result = Layer.empty(layerName)
        if (!isInitialized("getExperiment")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getLayer(normalizedUser, layerName, option?.userPersistedValues)
            result = Layer(
                layerName,
                evaluation.ruleID,
                evaluation.groupName,
                evaluation.jsonValue as? Map<String, Any> ?: mapOf(),
                evaluation.secondaryExposures,
                evaluation.configDelegate ?: "",
                evaluation.evaluationDetails,
                onExposure = getExposureFun(option?.disableExposureLogging == true, evaluation, normalizedUser),
            )
        }, tag = "getLayer", configName = layerName)
        return result
    }

    /**
     * Log an exposure for a given parameter within a layer
     * @param user A StatsigUser object used for logging
     * @param configName the name of the experiment to log an exposure for
     */
    fun logLayerParameterExposure(user: StatsigUser, layerName: String, paramName: String) {
        if (!isInitialized("logLayerParameterExposure")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getLayer(normalizedUser, layerName)
            val layer = Layer(
                layerName,
                evaluation.ruleID,
                evaluation.groupName,
                evaluation.jsonValue as? Map<String, Any> ?: mapOf(),
                evaluation.secondaryExposures,
                evaluation.configDelegate ?: "",
            )
            val layerExposureMetadata = createLayerExposureMetadata(layer, paramName, evaluation)
            statsigLogger.logLayerExposure(normalizedUser, layerExposureMetadata)
        }, tag = "logLayerExposure", configName = layerName)
    }

    /*
     * Load persisted values for given user and idType used for evaluation asynchronously,
     * callback will be called when value is ready
     * */
    fun loadUserPersistedValuesAsync(user: StatsigUser, idType: String, callback: IPersistentStorageCallback) {
        errorBoundary.capture(
            task = {
                return@capture this.persistentStorage?.loadAsync(user, idType, callback)
            },
            tag = "loadUserPersistedValuesAsync",
        )
    }

    /*
     * Load persisted values for given user and idType used for evaluation
     * */
    suspend fun loadUserPersistedValues(user: StatsigUser, idType: String): PersistedValues {
        return errorBoundary.captureAsync(
            task = {
                return@captureAsync this.persistentStorage?.load(user, idType) ?: mapOf()
            },
        ) ?: mapOf()
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event, for aggregations/analysis
     * @param metadata an optional map of metadata associated with the event
     */
    fun logEvent(user: StatsigUser, eventName: String, value: String?, metadata: Map<String, String>?) {
        if (!isInitialized("logEvent")) {
            return
        }
        errorBoundary.capture({
            val event = LogEvent(eventName, value, metadata, user, statsigMetadata)
            statsigScope.launch { statsigLogger.log(event) }
        }, tag = "logEvent")
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event
     * @param metadata an optional map of metadata associated with the event
     */
    fun logEvent(user: StatsigUser, eventName: String, value: Double?, metadata: Map<String, String>?) {
        if (!isInitialized("logEvent")) {
            return
        }
        errorBoundary.capture({
            val event = LogEvent(eventName, value, metadata, user, statsigMetadata)
            statsigScope.launch { statsigLogger.log(event) }
        }, tag = "logEvent")
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     */
    fun shutdown() {
        if (!isInitialized("shutdown")) {
            return
        }
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                shutdownSuspend()
                Statsig.client = StatsigClient()
            }
        }
    }

    private fun setup(
        application: Application,
        sdkKey: String,
        options: StatsigOptions = StatsigOptions(),
    ) {
        errorBoundary.setKey(sdkKey)
        exceptionHandler = errorBoundary.getExceptionHandler()
        this.application = application
        this.sdkKey = sdkKey
        this.options = options
        statsigScope = CoroutineScope(statsigJob + dispatcherProvider.main + exceptionHandler)
        sharedPrefs = application.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        diagnostics = Diagnostics(options.disableDiagnosticsLogging)
        if (!this::statsigNetwork.isInitialized) {
            // For testing purpose, prevent mocked network be overwritten
            statsigNetwork = StatsigNetwork(application, sdkKey, options, sharedPrefs, diagnostics, statsigScope)
        }
        statsigMetadata = StatsigMetadata()
        populateStatsigMetadata()
        val persistentStorageProvider = options.userPersistentStorage
        persistentStorage = if (persistentStorageProvider != null) StatsigUserPersistenStorageHelper(persistentStorageProvider, statsigScope) else null
        statsigLogger = StatsigLogger(statsigScope, statsigNetwork, diagnostics, statsigMetadata)
        errorBoundary.setDiagnostics(diagnostics)
        errorBoundary.setMetadata(statsigMetadata)
        specStore = Store(sdkKey, statsigNetwork, statsigScope, sharedPrefs, errorBoundary, diagnostics)
        evaluator = Evaluator(specStore, errorBoundary, persistentStorage)

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
                diagnostics.markStart(KeyType.OVERALL)
                statsigScope.launch {
                    statsigLogger.retryFailedLog(sharedPrefs)
                }
                if (this@StatsigClient.isBootstrapped.get()) {
                    diagnostics.markEnd(KeyType.OVERALL, true)
                    return@captureAsync InitializationDetails(System.currentTimeMillis() - initStartTime, true, null)
                }
                // load cache
                if (options.loadCacheAsync) {
                    specStore.syncLoadFromLocalStorage()
                }
                val initializeDetail = specStore.fetchAndSave()
                initializeDetail.duration = StatsigUtils.getTimeInMillis() - initStartTime
                diagnostics.markEnd(KeyType.OVERALL, initializeDetail.success, additionalMarker = Marker(evaluationDetails = specStore.getEvaluationDetailsForLogging()))
                statsigLogger.logDiagnostics(ContextType.INITIALIZE)
                return@captureAsync initializeDetail
            }, { e: Exception ->
                statsigLogger.logDiagnostics(ContextType.INITIALIZE)
                return@captureAsync InitializationDetails(StatsigUtils.getTimeInMillis() - initStartTime, false, InitializeResponse.FailedInitializeResponse(InitializeFailReason.InternalError, e))
            })
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        var normalizedUser = StatsigUser(null)
        if (user != null) {
            normalizedUser = user.getCopyForEvaluation()
        }
        if (options.getEnvironment() != null) {
            normalizedUser.statsigEnvironment = options.getEnvironment()
        }
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

    private fun getExposureFun(exposureDisabled: Boolean, configEvaluation: ConfigEvaluation, user: StatsigUser): OnLayerExposureInternal? {
        return if (exposureDisabled) {
            null
        } else {
            {
                    layer, parameterName ->
                val exposureMetadata = createLayerExposureMetadata(layer, parameterName, configEvaluation)
                statsigLogger.logLayerExposure(user, exposureMetadata)
            }
        }
    }

    internal fun isInitialized(): Boolean {
        return this.initialized.get()
    }

    internal fun isInitialized(functionName: String): Boolean {
        if (!this.initialized.get()) {
            Log.e("STATSIG", "The SDK must be initialized prior to invoking $functionName")
            return false
        }
        return true
    }

    suspend fun shutdownSuspend() {
        if (!isInitialized("shutdownSuspend")) {
            return
        }
        errorBoundary.captureAsync {
            shutdownImpl()
        }
    }

    private suspend fun shutdownImpl() {
        statsigLogger.shutdown()
        pollingJob?.cancel()
        initialized = AtomicBoolean()
        isBootstrapped = AtomicBoolean()
        errorBoundary = ErrorBoundary()
        statsigJob = SupervisorJob()
    }

    private fun logConfigExposureImpl(user: StatsigUser, configName: String, evaluation: ConfigEvaluation, isManualExposure: Boolean = false) {
        statsigLogger.logConfigExposure(user, configName, evaluation.ruleID, evaluation.secondaryExposures, isManualExposure, evaluation.evaluationDetails)
    }

    private fun logGateExposureImpl(user: StatsigUser, configName: String, evaluation: ConfigEvaluation, isManualExposure: Boolean = false) {
        statsigLogger.logGateExposure(user, configName, evaluation.booleanValue, evaluation.ruleID, evaluation.secondaryExposures, isManualExposure, evaluation.evaluationDetails)
    }

    private fun getDynamicConfigFromEvalResult(result: ConfigEvaluation, configName: String): DynamicConfig {
        return DynamicConfig(configName, result.jsonValue as? Map<String, Any> ?: mapOf(), result.ruleID, result.groupName, result.secondaryExposures, result.evaluationDetails)
    }
}
