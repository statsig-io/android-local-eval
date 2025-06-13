package com.statsig.androidlocalevalsdk

import android.app.Application
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InitializationRetryFailedLogsTest {
    private lateinit var mockWebServer: MockWebServer
    private var logEventHits = 0
    private val gson = Gson()
    private val app: Application = mockk()
    private val latch = CountDownLatch(2)

    @Before
    fun setup() {
        logEventHits = 0
        mockWebServer = MockWebServer()
        
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path!!.contains("download_config_specs") -> {
                        MockResponse()
                            .setBody(gson.toJson(TestUtil.getAPIDownloadConfigSpec("/download_config_specs.json")))
                            .setResponseCode(200)
                    }
                    request.path!!.contains("log_event") -> {
                        Thread.sleep(100)
                        latch
                        logEventHits++
                        MockResponse().setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()
        mockkStatic(Base64::class)

        val arraySlot = slot<ByteArray>()
        every {
            Base64.encodeToString(capture(arraySlot), Base64.NO_WRAP)
        } answers {
            java.util.Base64.getEncoder().encodeToString(arraySlot.captured)
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    @Test
    fun testStoredFailedLogsDoNotBlockInitialization() = runBlocking {
        val sharedPrefs = TestUtil.stubAppFunctions(app)

        sharedPrefs.edit().clear().commit()

        val failedLogsJson = this::class.java.classLoader!!
            .getResource("sample_failed_logs.json")!!
            .readText()

        val gson: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        val pendingRequestType = object : TypeToken<List<StatsigOfflineRequest>>() {}.type
        val pendingRequests: List<StatsigOfflineRequest> = gson.fromJson(failedLogsJson, pendingRequestType)

        val updatedRequests = pendingRequests.map {
            it.copy(timestamp = System.currentTimeMillis())
        }

        val updatedJson = gson.toJson(updatedRequests)

        sharedPrefs.edit()
            .putString("StatsigNetwork.OFFLINE_LOGS", updatedJson)
            .commit()

        val options = StatsigOptions(
            configSpecApi = mockWebServer.url("/v1/download_config_specs").toString(),
            eventLoggingAPI = mockWebServer.url("/v1/log_event").toString()
        )

        Statsig.client = StatsigClient()
        val initializationDetails = Statsig.client.initialize(app, "client-key", options)
        assert(logEventHits == 0)
        assert(initializationDetails!!.success)

        val gateResult = Statsig.client.checkGate(StatsigUser("123"), "test_gate")
        assert(!gateResult)
        Statsig.client.shutdown()

        latch.await(1, TimeUnit.SECONDS)

        assert(logEventHits > 1)
    }
}
