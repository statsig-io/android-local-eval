package com.statsig.androidlocalevalsdk

import android.app.Application
import com.google.gson.reflect.TypeToken
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class DiagnosticsTest {
    lateinit var client: StatsigClient
    private lateinit var app: Application
    private lateinit var network: StatsigNetwork
    private val user1 = StatsigUser("testUser")
    private var logEvents = mutableListOf<List<LogEvent>>()

    @Before
    fun setup() {
        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()
        client = StatsigClient()
        network = TestUtil.mockNetwork(onLog = {
            logEvents.add(it)
        })
        client.statsigNetwork = network
    }

    @After
    fun afterEach() {
        logEvents = mutableListOf()
    }

    @Test
    fun testConcurrency() = runBlocking {
        client.initialize(app, "client-key")
        forceDiagnosticsOnErrorBoundary()
        val threads = arrayListOf<Thread>()
        val threadSize = 3
        val iterations = 4
        for (i in 1..threadSize) {
            val t =
                Thread {
                    for (j in 1..iterations) {
                        runBlocking {
                            client.checkGate(user1, "always_on_gate")
                        }
                    }
                }
            threads.add(t)
        }
        for (t in threads) {
            t.start()
        }
        for (t in threads) {
            t.join()
        }
        client.shutdown()
        val markers = parseMarkers(ContextType.API_CALL)
        assert(markers.size == threadSize * iterations * 2)
    }

    @Test
    fun testMaxMarkers() = runBlocking {
        client.initialize(app, "client-key")
        forceDiagnosticsOnErrorBoundary()
        for (i in 1..MAX_MARKERS + 10) {
            client.checkGate(user1, "always_on_gate")
        }
        client.shutdown()
        val apiMarkers = parseMarkers(ContextType.API_CALL)
        assert(apiMarkers.size == MAX_MARKERS)
    }

    private fun forceDiagnosticsOnErrorBoundary() {
        val diagField = client.javaClass.getDeclaredField("diagnostics")
        diagField.isAccessible = true
        val diagnostics = diagField.get(client) as Diagnostics
        val ebDiagField = client.errorBoundary.javaClass.getDeclaredField("diagnostics")
        ebDiagField.isAccessible = true
        ebDiagField.set(client.errorBoundary, diagnostics)
    }

    private fun parseMarkers(context: ContextType): List<Marker> {
        val gson = StatsigUtils.getGson()
        val markers = mutableListOf<Marker>()
        logEvents.forEach {
            it.filter {
                    event ->
                event.eventName == "statsig::diagnostics"
            }.forEach {
                    event ->
                if (event.eventMetadata?.get("context") == context.toString().lowercase()) {
                    val markersJson = event.eventMetadata?.get("markers")
                    if (markersJson != null) {
                        val type = object : TypeToken<List<Marker>>() {}.type
                        markers.addAll(gson.fromJson(markersJson, type))
                    }
                }
            }
        }
        return markers
    }
}
