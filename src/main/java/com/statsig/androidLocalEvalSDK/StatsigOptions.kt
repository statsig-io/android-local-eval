package com.statsig.androidLocalEvalSDK

import com.google.gson.annotations.SerializedName

enum class Tier {
    PRODUCTION,
    STAGING,
    DEVELOPMENT,
}

private const val TIER_KEY: String = "tier"
internal const val DEFAULT_CONFIG_SPEC_API = "https://api.statsigcdn.com/v1/download_config_specs/"

/**
 * An object of properties for initializing the sdk with advanced options
 * @property api the api endpoint to use for initialization and logging
 * @property disableCurrentActivityLogging prevents the SDK from auto logging the current, top-level
 * activity for event logs
 * @property initTimeoutMs the amount of time to wait for an initialize() response from the server
 * NOTE: gates/configs will still be fetched in the background if this time is exceeded, but the
 * callback to initialize will fire after, at most, the time specified
 */
class StatsigOptions(
    /**
     The endpoint to use for downloading config spec network requests. You should not need to override this
     (unless you have another API that implements the Statsig API endpoints)
     */
    @SerializedName("configSpecAPI") var configSpecApi: String = DEFAULT_CONFIG_SPEC_API,
    /**
     The endpoint to use for downloading config spec network requests. You should not need to override this
     (unless you have another API that implements the Statsig API endpoints)
     */
    @SerializedName("eventLoggingAPI") var eventLoggingAPI: String = "https://events.statsigapi.net/v1/rgstr",
    /**
     * Used to decide how long the Statsig client waits for the initial network request to respond
     * before calling the completion block. The Statsig client will return either cached values
     * (if any) or default values if checkGate/getConfig/getExperiment is called before the initial
     * network request completes
     * if you always want to wait for the latest values fetched from Statsig server, you should set this to 0 so we do not timeout the network request.
     */
    @SerializedName("initTimeoutMs") var initTimeoutMs: Int = 3000,
    /**
     * overrides the stableID in the SDK that is set for the user
     */
    @SerializedName("overrideStableID") var overrideStableID: String? = null,
    /**
     * Whether or not the SDK should block on loading saved values from disk. By default, we block on
     * loading from disk, so we guarantee cache will be loaded when sdk is being used.
     */
    @SerializedName("loadCacheAsync") var loadCacheAsync: Boolean = false,
    /**
     * Provide a Dictionary representing the "initiailize response" required  to synchronously initialize the SDK.
     * This value can be obtained from a Statsig server SDK.
     */
    @SerializedName("initializeValues") var initializeValues: String? = null,
    /**
     By default, initialize send Network request and use network response for evaluation.
     * Setting this value to true will initialize without sending network request, and initialize
     * SDK from cache values for user.
     * */
) {

    private var environment: MutableMap<String, String>? = null

    fun setTier(tier: Tier) {
        setEnvironmentParameter(TIER_KEY, tier.toString().lowercase())
    }

    fun setEnvironmentParameter(key: String, value: String) {
        val env = environment
        if (env == null) {
            environment = mutableMapOf(key to value)
            return
        }

        env[key] = value
    }

    fun getEnvironment(): MutableMap<String, String>? {
        return environment
    }

    internal fun toMap(): Map<String, Any?> {
        return mapOf(
            "configSpecAPI" to configSpecApi,
            "eventLoggingAPI" to eventLoggingAPI,
            "initTimeoutMs" to initTimeoutMs,
            "overrideStableID" to overrideStableID,
            "loadCacheAsync" to loadCacheAsync,
            "initializeValues" to initializeValues,
            "environment" to environment,
        )
    }
}
