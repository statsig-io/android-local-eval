package com.statsig.androidLocalEvalSDK

import java.util.Collections

const val NANO_IN_MS = 1_000_000.0
const val MAX_SAMPLING_RATE = 10_000
const val MAX_MARKERS = 30
internal class Diagnostics(private var isDisabled: Boolean) {
    var diagnosticsContext: ContextType = ContextType.INITIALIZE
    var markers: DiagnosticsMarkers = Collections.synchronizedMap(mutableMapOf())

    fun markStart(key: KeyType, step: StepType? = null, additionalMarker: Marker? = null) {
        if (isDisabled && diagnosticsContext == ContextType.API_CALL) {
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
        if (isDisabled && diagnosticsContext == ContextType.API_CALL) {
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

    fun clearContext(context: ContextType) {
        this.markers[context] = Collections.synchronizedList(mutableListOf())
    }

    private fun addMarker(marker: Marker) {
        if (this.markers[diagnosticsContext] == null) {
            this.markers[diagnosticsContext] = Collections.synchronizedList(mutableListOf())
        }
        if (this.markers[diagnosticsContext]?.size ?: 0 >= MAX_MARKERS) {
            return
        }
        this.markers[diagnosticsContext]?.add(marker)
        this.markers.values
    }
}
