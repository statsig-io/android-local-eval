package com.statsig.androidLocalEvalSDK

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException

internal class Store(
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
) {
    internal var initReason: EvaluationReason = EvaluationReason.UNINITIALIZED
    internal var lcut: Long = 0

    private var gson = StatsigUtils.getGson()

    private var dynamicConfigs: Map<String, APIConfig> = emptyMap()
    private var gates: Map<String, APIConfig> = emptyMap()
    private var layerConfigs: Map<String, APIConfig> = emptyMap()
    private var experimentToLayer: Map<String, String> = emptyMap()

    fun syncLoadFromLocalStorage() {
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
