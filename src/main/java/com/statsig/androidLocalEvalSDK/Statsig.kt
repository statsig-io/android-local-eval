package com.statsig.androidLocalEvalSDK

import android.app.Application
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Callback interface for Statsig calls. All callbacks will be run on the main thread.
 */
@FunctionalInterface
interface IStatsigCallback {
    fun onStatsigInitialize() {}

    fun onStatsigInitialize(initDetails: InitializationDetails) {
        return this.onStatsigInitialize()
    }

    fun onStatsigUpdateUser()
}

/**
 * A singleton class for interfacing with gates, configs, and logging in the Statsig console
 */
object Statsig {

    @VisibleForTesting
    internal var client: StatsigClient = StatsigClient()

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
    @JvmStatic
    fun initializeAsync(
        application: Application,
        sdkKey: String,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions(),
    ) {
        client.initializeAsync(application, sdkKey, callback, options)
    }

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
    @JvmSynthetic
    suspend fun initialize(
        application: Application,
        sdkKey: String,
        options: StatsigOptions = StatsigOptions(),
    ): InitializationDetails? {
        return client.initialize(application, sdkKey, options)
    }

    /**
     * Get the boolean result of a gate, evaluated against a given user.
     * An exposure event will automatically be logged for the gate.
     *
     * @param user A StatsigUser object used for evaluation
     * @param gateName The name of the gate being evaluated
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmOverloads
    @JvmStatic
    fun checkGate(user: StatsigUser, gateName: String): Boolean {
        return client.checkGate(user, gateName)
    }

    /**
     * Check the value of an Experiment configured in the Statsig console
     * @param user A StatsigUser object used for the evaluation
     * @param experimentName the name of the Experiment to check
     * @return the Dynamic Config backing the experiment
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmOverloads
    @JvmStatic
    fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        return client.getExperiment(user, experimentName)
    }

    /**
     * Get the values of a DynamicConfig, evaluated against the given user.
     * An exposure event will automatically be logged for the DynamicConfig.
     *
     * @param user A StatsigUser object used for evaluation
     * @param dynamicConfigName The name of the DynamicConfig
     * @return DynamicConfig object evaluated for the selected StatsigUser
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmOverloads
    @JvmStatic
    fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        return client.getConfig(user, dynamicConfigName)
    }

    /**
     * @param user A StatsigUser object used for the evaluation
     * @param layerName the name of the Experiment to check
     * @return the current layer values as a Layer object
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmOverloads
    @JvmStatic
    fun getLayer(user: StatsigUser, layerName: String): Layer {
        return client.getLayer(user, layerName)
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmStatic
    fun shutdown() {
        client.enforceInitialized("shutdown")
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                client.shutdownSuspend()
                client = StatsigClient()
            }
        }
    }

    @JvmSynthetic
    suspend fun shutdownSuspend() {
        client.shutdownSuspend()
        client = StatsigClient()
    }
}
