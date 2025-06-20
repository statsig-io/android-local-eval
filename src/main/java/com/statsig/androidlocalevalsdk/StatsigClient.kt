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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.statsig.androidlocalevalsdk.typed.TypedStatsigProvider

const val MIN_BG_SYNC_INTERVAL_SECONDS = 60
const val DEFAULT_BG_SYNC_INTERVAL_SECONDS = 3600

class StatsigClient {
    val typed: TypedStatsigProvider
        get() = typedProvider

    internal var globalUser: StatsigUser? = null
    internal var errorBoundary: ErrorBoundary = ErrorBoundary()

    @VisibleForTesting
    internal lateinit var statsigNetwork: StatsigNetwork

    private lateinit var options: StatsigOptions
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

    private var minBackgroundSyncIntervalSeconds = MIN_BG_SYNC_INTERVAL_SECONDS
    private var pollingJob: Job? = null
    private var statsigJob = SupervisorJob()
    private var dispatcherProvider = CoroutineDispatcherProvider()
    private var persistentStorage: StatsigUserPersistenStorageHelper? = null
    private var initialized = AtomicBoolean(false)
    private var isBootstrapped = AtomicBoolean(false)
    private var typedProvider = TypedStatsigProvider()
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    @JvmOverloads
    fun initializeSync(
        application: Application,
        sdkKey: String,
        initialSpecs: String,
        options: StatsigOptions = StatsigOptions(),
    ): InitializationDetails? {
        if (this@StatsigClient.isInitialized()) {
            return InitializationDetails(0, true)
        }

        val initStartTime = System.currentTimeMillis()
        return errorBoundary.capture({
                options.initializeValues = initialSpecs
                setup(application, sdkKey, options)
                retryScope.launch {
                    statsigLogger.retryFailedLog(sharedPrefs)
                }
                diagnostics.markEnd(KeyType.OVERALL, true)
                InitializationDetails(System.currentTimeMillis() - initStartTime, true, null)
            },
            tag = "initializeSync",
        )
    }

    suspend fun updateAsync(): InitializationDetails {
        val initStartTime = System.currentTimeMillis()
        return errorBoundary.captureAsync({
            val details = specStore.fetchAndSave()
            InitializationDetails(System.currentTimeMillis() - initStartTime, details.success, details.failureDetails)
        }, { e: Exception ->
            return@captureAsync InitializationDetails(StatsigUtils.getTimeInMillis() - initStartTime, false, InitializeResponse.FailedInitializeResponse(InitializeFailReason.InternalError, e))
        })
    }

    suspend fun flushEvents() {
        errorBoundary.captureAsync({
                statsigLogger.flush()
            },
            "flushEvents"
        )
    }

    fun scheduleBackgroundUpdates(intervalSeconds: Int = DEFAULT_BG_SYNC_INTERVAL_SECONDS): Job? {
        return errorBoundary.capture({
            if (pollingJob != null) {
                pollingJob?.cancel()
            }

            var interval = intervalSeconds
            if (interval < minBackgroundSyncIntervalSeconds) {
                interval = minBackgroundSyncIntervalSeconds
                Log.e("STATSIG", "Background sync interval cannot be less than $MIN_BG_SYNC_INTERVAL_SECONDS seconds.  Defaulting to $MIN_BG_SYNC_INTERVAL_SECONDS seconds")
            }

            pollingJob = statsigScope.launch {
                while (statsigScope.isActive) {
                    specStore.fetchAndSave()
                    delay(interval * 1000L)
                }
            }

            return@capture pollingJob
        }, tag = "checkGate")
    }


    fun setGlobalUser(user: StatsigUser) {
        globalUser = user
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
    fun checkGate(user: StatsigUser?, gateName: String, option: CheckGateOptions? = null): Boolean {
        if (!isInitialized("checkGate")) {
            return false
        }
        var result = false
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.checkGate(normalizedUser, gateName, option)
            result = evaluation.booleanValue
            if (option?.disableExposureLogging !== true) {
                logGateExposureImpl(normalizedUser, gateName, evaluation)
            }
        }, tag = "checkGate", configName = gateName)
        return result
    }

    /**
     * Get the result of a gate, evaluated against a given user.
     * An exposure event will automatically be logged for the gate.
     *
     * @param user A StatsigUser object used for evaluation
     * @param gateName The name of the gate being evaluated
     * @param options advanced setup for checkGate, for example disable exposure logging
     */
    @JvmOverloads
    fun getFeatureGate(user: StatsigUser?, gateName: String, options: CheckGateOptions? = null): FeatureGate {
        var result = FeatureGate.empty(gateName)
        if (!isInitialized("getFeatureGate")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.checkGate(normalizedUser, gateName, options)
            result = getFeatureGateFromEvalResult(evaluation, gateName)
            if (options?.disableExposureLogging !== true) {
                logGateExposureImpl(normalizedUser, gateName, evaluation)
            }
        }, tag = "getFeatureGate", configName = gateName)
        return result
    }


    /**
     * Log an exposure for a given gate
     * @param user A StatsigUser object used for logging
     * @param gateName the name of the gate to log an exposure for
     */
    fun logGateExposure(user: StatsigUser?, gateName: String) {
        if (!isInitialized("logGateExposure")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.checkGate(normalizedUser, gateName, null)
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
    fun getExperiment(user: StatsigUser?, experimentName: String, option: GetExperimentOptions? = null): DynamicConfig {
        var result = DynamicConfig.empty()

        if (!isInitialized("getExperiment")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }

        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getExperiment(normalizedUser, experimentName, option)
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
    fun logExperimentExposure(user: StatsigUser?, experimentName: String) {
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, experimentName, null)
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
    fun getConfig(user: StatsigUser?, dynamicConfigName: String, option: GetConfigOptions? = null): DynamicConfig {
        var result = DynamicConfig.empty()
        if (!isInitialized("getExperiment")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, dynamicConfigName, option)
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
    fun logConfigExposure(user: StatsigUser?, dynamicConfigName: String) {
        if (!isInitialized("logConfigExposure")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getConfig(normalizedUser, dynamicConfigName, null)
            logConfigExposureImpl(normalizedUser, dynamicConfigName, evaluation, true)
        }, tag = "logConfigExposure", configName = dynamicConfigName)
    }

    /**
     * @param user A StatsigUser object used for the evaluation
     * @param layerName the name of the Experiment to check
     * @return the current layer values as a Layer object
     */
    @JvmOverloads
    fun getLayer(user: StatsigUser?, layerName: String, option: GetLayerOptions? = null): Layer {
        var result = Layer.empty(layerName)
        if (!isInitialized("getExperiment")) {
            result.evaluationDetails = EvaluationDetails(0, EvaluationReason.UNINITIALIZED)
            return result
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getLayer(normalizedUser, layerName, option)
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
    fun logLayerParameterExposure(user: StatsigUser?, layerName: String, paramName: String) {
        if (!isInitialized("logLayerParameterExposure")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val evaluation = evaluator.getLayer(normalizedUser, layerName, null)
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
    fun loadUserPersistedValuesAsync(user: StatsigUser?, idType: String, callback: IPersistentStorageCallback) {
        errorBoundary.capture(
            task = {
                val normalizedUser = normalizeUser(user)
                return@capture this.persistentStorage?.loadAsync(normalizedUser, idType, callback)
            },
            tag = "loadUserPersistedValuesAsync",
        )
    }

    /*
     * Load persisted values for given user and idType used for evaluation
     * */
    suspend fun loadUserPersistedValues(user: StatsigUser?, idType: String): PersistedValues {
        return errorBoundary.captureAsync(
            task = {
                val normalizedUser = normalizeUser(user)
                return@captureAsync this.persistentStorage?.load(normalizedUser, idType) ?: mapOf()
            },
        ) ?: mapOf()
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event, for aggregations/analysis
     * @param metadata an optional map of metadata associated with the event
     */
    fun logEvent(user: StatsigUser?, eventName: String, value: String?, metadata: Map<String, String>?) {
        if (!isInitialized("logEvent")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val event = LogEvent(eventName, value, metadata, normalizedUser, statsigMetadata)
            statsigScope.launch { statsigLogger.log(event) }
        }, tag = "logEvent")
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event
     * @param metadata an optional map of metadata associated with the event
     */
    fun logEvent(user: StatsigUser?, eventName: String, value: Double?, metadata: Map<String, String>?) {
        if (!isInitialized("logEvent")) {
            return
        }
        errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val event = LogEvent(eventName, value, metadata, normalizedUser, statsigMetadata)
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

    @VisibleForTesting
    internal fun setup(
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
        evaluator = Evaluator(specStore, errorBoundary, persistentStorage, options.overrideAdapter)
        typedProvider.bind(this, options)

        // load cache
        if (!options.loadCacheAsync || options.useNewerCacheValuesOverProvidedValues) {
            specStore.syncLoadFromLocalStorage()
        }
        // load from initialized values if available
        val initializeValues = options.initializeValues
        if (initializeValues != null) {
            specStore.bootstrap(initializeValues, options)
            isBootstrapped.set(true)
        }
        this.initialized.set(true)
    }

    private suspend fun setupAsync(): InitializationDetails {
        return withContext(dispatcherProvider.io) {
            val initStartTime = StatsigUtils.getTimeInMillis()
            return@withContext errorBoundary.captureAsync({
                diagnostics.markStart(KeyType.OVERALL)
                retryScope.launch {
                    statsigLogger.retryFailedLog(sharedPrefs)
                }

                if (this@StatsigClient.isBootstrapped.get()) {
                    diagnostics.markEnd(KeyType.OVERALL, true)
                    return@captureAsync InitializationDetails(System.currentTimeMillis() - initStartTime, true, null)
                }

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
        var internalUser = user ?: globalUser
        var normalizedUser = StatsigUser(null)
        if (internalUser != null) {
            normalizedUser = internalUser.getCopyForEvaluation()
        }
        if (options.getEnvironment() != null) {
            normalizedUser.statsigEnvironment = options.getEnvironment()
        }
        if (normalizedUser.statsigEnvironment == null) {
            val defaultEnv = specStore.getDefaultEnvironment()
            if (defaultEnv != null) {
                normalizedUser.statsigEnvironment = mutableMapOf("tier" to defaultEnv)
            }
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
        statsigLogger.logConfigExposure(user, configName, evaluation, isManualExposure)
    }

    private fun logGateExposureImpl(user: StatsigUser, configName: String, evaluation: ConfigEvaluation, isManualExposure: Boolean = false) {
        statsigLogger.logGateExposure(user, configName, evaluation, isManualExposure)
    }

    private fun getDynamicConfigFromEvalResult(result: ConfigEvaluation, configName: String): DynamicConfig {
        return DynamicConfig(
            configName,
            result.jsonValue as? Map<String, Any> ?: mapOf(),
            result.ruleID,
            result.groupName,
            result.secondaryExposures,
            result.evaluationDetails,
            result.returnableValue?.rawJson ?: "{}",
        )
    }

    private fun getFeatureGateFromEvalResult(result: ConfigEvaluation, gateName: String): FeatureGate {
        return FeatureGate(gateName, result.booleanValue, result.ruleID, result.secondaryExposures, result.evaluationDetails)
    }
}
