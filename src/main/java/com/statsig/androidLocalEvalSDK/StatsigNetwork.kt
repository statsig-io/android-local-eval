package com.statsig.androidLocalEvalSDK

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow

// HTTP
internal const val POST = "POST"
internal const val GET = "GET"
private const val CONTENT_TYPE_HEADER_KEY = "Content-Type"
private const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=UTF-8"
private const val STATSIG_API_HEADER_KEY = "STATSIG-API-KEY"
private const val STATSIG_CLIENT_TIME_HEADER_KEY = "STATSIG-CLIENT-TIME"
private const val STATSIG_SDK_TYPE_KEY = "STATSIG-SDK-TYPE"
private const val STATSIG_SDK_VERSION_KEY = "STATSIG-SDK-VERSION"
private const val ACCEPT_HEADER_KEY = "Accept"
private const val ACCEPT_HEADER_VALUE = "application/json"

private val RETRY_CODES: IntArray = intArrayOf(
    HttpURLConnection.HTTP_CLIENT_TIMEOUT,
    HttpURLConnection.HTTP_INTERNAL_ERROR,
    HttpURLConnection.HTTP_BAD_GATEWAY,
    HttpURLConnection.HTTP_UNAVAILABLE,
    HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
    522,
    524,
    599,
)
internal class StatsigNetwork(private val sdkKey: String) {
    private val dispatcherProvider = CoroutineDispatcherProvider()
    private val gson = StatsigUtils.getGson()

    suspend inline fun <reified T : Any> getRequest(
        api: String,
        retries: Int,
        timeout: Int? = null,
        crossinline callback: ((statusCode: Int?) -> Unit) = { _: Int? -> },
    ): T? {
        return withContext(dispatcherProvider.io) { // Perform network calls in IO thread
            var retryAttempt = 1
            var connection: HttpURLConnection? = null
            try {
                while (isActive) {
                    connection = URL(api).openConnection() as HttpURLConnection
                    connection.requestMethod = GET
                    if (timeout != null) {
                        connection.connectTimeout = timeout
                        connection.readTimeout = timeout
                    }
                    val code = connection.responseCode

                    val inputStream = if (code < HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    when (code) {
                        in 200..299 -> {
                            if (code == 204) {
                                return@withContext gson.fromJson("{has_updates: false}", T::class.java)
                            }
                            return@withContext inputStream.bufferedReader(Charsets.UTF_8)
                                .use { gson.fromJson(it, T::class.java) }
                        }
                        in RETRY_CODES -> {
                            if (retries > 0 && retryAttempt++ < retries) {
                                // Don't return, just allow the loop to happen
                                delay(100.0.pow(retryAttempt + 1).toLong())
                            } else {
                                callback(code)
                                return@withContext null
                            }
                        }
                        else -> {
                            callback(code)
                            return@withContext null
                        }
                    }
                }
            } finally {
                connection?.disconnect()
            }

            return@withContext null
        }
    }

    internal fun getURLForDownloadConfigSpec(api: String): String {
        if (api == DEFAULT_CONFIG_SPEC_API) {
            return "${api}$sdkKey.json"
        }
        return api
    }
}
