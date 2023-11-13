package com.statsig.androidLocalEvalSDK

const val NANO_IN_MS = 1_000_000.0
const val MAX_SAMPLING_RATE = 10_000
internal class Diagnostics(private var isDisabled: Boolean) {
    var diagnosticsContext: ContextType = ContextType.INITIALIZE
    var markers: DiagnosticsMarkers = mutableMapOf()
    private val samplingRates: MutableMap<String, Int> = mutableMapOf("initialize" to 0)

    fun setSamplingRate(rates: Map<String, Int>) {
        for ((key, value) in rates) {
            if (samplingRates.containsKey(key)) {
                val samplingRate = if (value in 0..MAX_SAMPLING_RATE) {
                    value
                } else {
                    if (value < 0) 0 else MAX_SAMPLING_RATE
                }
                samplingRates[key] = samplingRate
            }
        }
    }

    fun markStart(key: KeyType, step: StepType? = null, additionalMarker: Marker? = null) {
        if (isDisabled) {
            return
        }
        val marker = Marker(key = key, action = ActionType.START, timestamp = System.nanoTime() / NANO_IN_MS, step = step)
        when (step) {
            StepType.NETWORK_REQUEST -> {
                marker.attempt = additionalMarker?.attempt
                marker.isDelta = additionalMarker?.isDelta
            }
        }
        this.addMarker(marker)
    }

    fun markEnd(key: KeyType, success: Boolean, step: StepType? = null, additionalMarker: Marker? = null) {
        if (isDisabled) {
            return
        }
        val marker = Marker(key = key, action = ActionType.END, success = success, timestamp = System.nanoTime() / NANO_IN_MS, step = step)
        when (key) {
            KeyType.DOWNLOAD_CONFIG_SPECS -> {
                if (step == StepType.NETWORK_REQUEST) {
                    marker.sdkRegion = additionalMarker?.sdkRegion
                    marker.statusCode = additionalMarker?.statusCode
                }
            }
            KeyType.OVERALL -> {
                marker.reason = additionalMarker?.reason
                marker.evaluationDetails = additionalMarker?.evaluationDetails
            }
        }
        when (this.diagnosticsContext) {
            ContextType.API_CALL -> {
                marker.markerID = additionalMarker?.markerID
                marker.configName = additionalMarker?.configName
            }
        }
        when (step) {
            StepType.NETWORK_REQUEST -> {
                marker.attempt = additionalMarker?.attempt
                marker.isDelta = additionalMarker?.isDelta
            }
        }
        this.addMarker(marker)
    }

    fun shouldLogDiagnostics(context: ContextType): Boolean {
        val samplingKey: String =
            when (context) {
                ContextType.INITIALIZE -> "initialize"
                ContextType.API_CALL -> return true
            }
        val rand = Math.random() * MAX_SAMPLING_RATE
        return samplingRates[samplingKey] ?: 0 > rand
    }

    private fun addMarker(marker: Marker) {
        if (this.markers[diagnosticsContext] == null) {
            this.markers[diagnosticsContext] = mutableListOf()
        }
        this.markers[diagnosticsContext]?.add(marker)
        this.markers.values
    }
}
