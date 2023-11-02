package com.statsig.androidLocalEvalSDK

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class ExternalException(message: String? = null) : Exception(message)

internal class ErrorBoundary() {
    internal var urlString = "https://statsigapi.net/v1/sdk_exception"

    private var apiKey: String? = null
    private var seen = HashSet<String>()
    private var statsigMetadata: StatsigMetadata? = null

    fun setKey(apiKey: String) {
        this.apiKey = apiKey
    }

    private fun handleException(exception: Throwable, tag: String? = null) {
        if (exception !is ExternalException) {
            this.logException(exception, tag = tag)
        }
    }

    fun getExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, exception ->
            this.handleException(exception)
        }
    }

    fun capture(task: () -> Unit, tag: String? = null, recover: (() -> Unit)? = null, configName: String? = null) {
        try {
            task()
        } catch (e: Exception) {
            handleException(e, tag)
            recover?.let { it() }
        }
    }

    fun setMetadata(statsigMetadata: StatsigMetadata) {
        this.statsigMetadata = statsigMetadata
    }

    suspend fun <T> captureAsync(task: suspend () -> T): T? {
        return try {
            task()
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    suspend fun <T> captureAsync(task: suspend () -> T, recover: (suspend (e: Exception) -> T)): T {
        return try {
            task()
        } catch (e: Exception) {
            handleException(e)
            recover(e)
        }
    }

    internal fun logException(exception: Throwable, message: String? = null, tag: String? = null) {
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
            val body = mapOf(
                "exception" to name,
                "info" to RuntimeException(exception).stackTraceToString(),
                "statsigMetadata" to metadata,
                "functionName" to tag,

            )
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
}
