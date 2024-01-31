package com.statsig.androidlocalevalsdk

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.DataOutputStream
import java.lang.Math.floor
import java.net.HttpURLConnection
import java.net.URL

internal class ExternalException(message: String? = null) : Exception(message)
const val SAMPLING_RATE = 10_000 // Sample rate 0.01%

internal class ErrorBoundary() {
    internal var urlString = "https://statsigapi.net/v1/sdk_exception"

    private var apiKey: String? = null
    private var seen = HashSet<String>()
    private var statsigMetadata: StatsigMetadata? = null
    private var diagnostics: Diagnostics? = null

    fun setDiagnostics(diagnostics: Diagnostics) {
        val sampled = floor(Math.random() * SAMPLING_RATE) == 0.0
        if (sampled) {
            this.diagnostics = diagnostics
        }
    }

    fun setMetadata(statsigMetadata: StatsigMetadata) {
        this.statsigMetadata = statsigMetadata
    }

    fun setKey(apiKey: String) {
        this.apiKey = apiKey
    }

    private fun handleException(exception: Throwable, tag: String? = null, configName: String? = null) {
        if (exception !is ExternalException) {
            this.logException(exception, tag = tag, configName = configName)
        }
    }

    fun getExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, exception ->
            this.handleException(exception)
        }
    }

    fun <T> capture(task: () -> T, tag: String? = null, recover: (() -> T)? = null, configName: String? = null): T? {
        var markerID = ""
        return try {
            val markerID = startMarker(tag, configName)
            val result = task()
            endMarker(tag, markerID, true, configName)
            result
        } catch (e: Exception) {
            handleException(e, tag)
            endMarker(tag, markerID, true, configName)
            recover?.let { it() }
        }
    }

    suspend fun <T> captureAsync(task: suspend () -> T): T? {
        return try {
            task()
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    suspend fun <T> captureAsync(task: suspend () -> T, recover: (suspend (e: Exception) -> T), tag: String? = null): T {
        return try {
            task()
        } catch (e: Exception) {
            handleException(e, tag = tag)
            recover(e)
        }
    }

    internal fun logException(exception: Throwable, message: String? = null, tag: String? = null, configName: String? = null) {
        try {
            Log.e("STATSIG", "An unexpected exception occured: " + exception)
            if (message != null) {
                Log.e("STATSIG", message)
            }
            val name = exception.javaClass.canonicalName ?: exception.javaClass.name
            if (seen.contains(name)) {
                return
            }
            seen.add(name)

            val metadata = statsigMetadata ?: StatsigMetadata("")
            val body = mutableMapOf(
                "exception" to name,
                "info" to RuntimeException(exception).stackTraceToString(),
                "statsigMetadata" to metadata,
            )
            if (tag != null) {
                body["tag"] = tag
            }
            if (configName != null) {
                body["configName"] = configName
            }
            val postData = StatsigUtils.getGson().toJson(body)

            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("STATSIG-API-KEY", apiKey)
            conn.useCaches = false

            DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }
            conn.responseCode // triggers request
        } catch (e: Exception) {
        }
    }

    private fun startMarker(tag: String?, configName: String?): String? {
        val diagnostics = this.diagnostics
        val markerKey = KeyType.convertFromString(tag ?: "")
        if (tag == null || diagnostics == null || markerKey == null) {
            return null
        }
        val markerID = tag + "_" + (diagnostics.markers[ContextType.API_CALL]?.count() ?: 0)
        diagnostics.diagnosticsContext = ContextType.API_CALL
        diagnostics.markStart(markerKey, step = null, Marker(markerID = markerID, configName = configName))
        return markerID
    }

    private fun endMarker(tag: String?, markerID: String?, success: Boolean, configName: String?) {
        val diagnostics = this.diagnostics
        val markerKey = KeyType.convertFromString(tag ?: "")
        if (tag == null || diagnostics == null || markerKey == null) {
            return
        }
        diagnostics.markEnd(markerKey, success, step = null, Marker(markerID = markerID, configName = configName))
    }
}
