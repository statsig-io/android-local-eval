package com.statsig.androidlocalevalsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StatsigInitializationTimeoutTest {

    private var app: Application = mockk()
    private lateinit var client: StatsigClient
    private lateinit var network: StatsigNetwork
    private lateinit var errorBoundary: ErrorBoundary
    private lateinit var mockWebServer: MockWebServer
    private val hitErrorBoundary = CountDownLatch(1)

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("sdk_exception")) {
                    hitErrorBoundary.countDown()
                    runBlocking {
                        delay(1000)
                    }
                    MockResponse()
                        .setBody("{\"result\":\"error logged\"}")
                        .setResponseCode(200)
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        client = spyk(StatsigClient(), recordPrivateCalls = true)
        client.errorBoundary = spyk(client.errorBoundary)
        errorBoundary = client.errorBoundary
        network = TestUtil.mockNetwork()

        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)

        every {
            errorBoundary.getUrl()
        } returns mockWebServer.url("/v1/sdk_exception").toString()

        client.statsigNetwork = network
        client.errorBoundary = errorBoundary
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    @Test
    fun testInitializeAsyncWithSlowErrorBoundary() = runBlocking {
        // Lets get a successful network response, and then trigger error boundary
        // so we can test that eb does not block the initialization beyond the init timeout
        mockkConstructor(Store::class)
        coEvery {
            anyConstructed<Store>().fetchAndSave()
        } throws(Exception("trigger the error boundary"))
        var initializationDetails: InitializationDetails? = null
        var initTimeout = 500
        runBlocking {
            initializationDetails = client.initialize(app, "client-key", StatsigOptions(initTimeoutMs = initTimeout))
        }
        // initialize timeout was hit, we got a value back and we are considered initialized
        assert(initializationDetails != null)
        assert(client.isInitialized())

        // error boundary was hit, but has not completed at this point, so the initialization timeout worked
        assertTrue(hitErrorBoundary.await(1, TimeUnit.SECONDS))
        assertTrue(
            "initialization time ${initializationDetails!!.duration} not less than initTimeout $initTimeout",
            initializationDetails!!.duration < initTimeout + 100L,
        )

        // error boundary was hit, but has not completed at this point, so the initialization timeout worked
        assert(hitErrorBoundary.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun testInitializationTimeout() = runBlocking {
        val timeout = 300
        mockWebServer.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == null) {
                        return MockResponse().setResponseCode(404)
                    }
                    if ("/v1/download_config_specs" in request.path!!) {
                        Thread.sleep(timeout.toLong() + 1000)
                        return MockResponse().setResponseCode(200)
                    }
                    if ("/v1/log_event" in request.path!!) {
                        Thread.sleep(timeout.toLong() + 1000)
                        return MockResponse().setResponseCode(200)
                    }
                    return MockResponse().setResponseCode(400)
                }
            }
        }
        val option = StatsigOptions(initTimeoutMs = timeout, configSpecApi = mockWebServer.url("/v1/download_config_specs").toString(), eventLoggingAPI = mockWebServer.url("/v1/log_event").toString())
        val initializationDetails = Statsig.client.initialize(app, "client-key", option)
        assert((initializationDetails?.duration?.toInt() ?: timeout < timeout + 100)) // 100 is buffer time for other setup job
    }
}
