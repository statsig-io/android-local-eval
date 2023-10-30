package com.statsig.androidLocalEvalSDK

import kotlinx.coroutines.CoroutineExceptionHandler

internal class ExternalException(message: String? = null) : Exception(message)

internal class ErrorBoundary() {
    internal var urlString = "https://statsigapi.net/v1/sdk_exception"

    private var apiKey: String? = null
    private var seen = HashSet<String>()
    private var statsigMetadata: StatsigMetadata? = null

    fun setKey(apiKey: String) {
        this.apiKey = apiKey
    }

    private fun handleException(exception: Throwable) {
        if (exception !is ExternalException) {
            this.logException(null, exception)
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
            handleException(e)
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

    internal fun logException(message: String? = "", exception: Throwable) {
        // TODO: Implement
        println("catching exception!!")
        println(exception)
    }
}
