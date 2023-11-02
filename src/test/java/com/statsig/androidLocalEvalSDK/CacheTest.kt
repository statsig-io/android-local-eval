package com.statsig.androidLocalEvalSDK

import android.app.Application
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class CacheTest {
    private lateinit var app: Application
    private lateinit var network: StatsigNetwork
    private lateinit var brokenNetowrk: StatsigNetwork
    private lateinit var client: StatsigClient
    private val user1 = StatsigUser("testUser")
    private val user2 = StatsigUser("testUser2")

    @Before
    fun setup() {
        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()
        client = StatsigClient()
        network = TestUtil.mockNetwork()
        brokenNetowrk = TestUtil.mockBrokenNetwork()
        client.statsigNetwork = network
    }

    @Test
    fun loadFromCache() = runBlocking {
        client.initialize(app, "client-key")
        assert(client.isInitialized())
        client.shutdown()
        client.statsigNetwork = brokenNetowrk
        client.initialize(app, "client-key")

        // Different user use the same cache value
        val checkGate = client.checkGate(user1, "always_on_gate")
        assert(checkGate)
        val checkGate2 = client.checkGate(user2, "always_on_gate")
        assert(checkGate2)

        val getConfig = client.getConfig(user1, "test_config")
        assert(getConfig?.getInt("number", 0) == 4)
        assert(getConfig?.evaluationDetails?.reason == EvaluationReason.CACHE)
    }

    @Test
    fun cacheMiss() = runBlocking {
        client.initialize(app, "client-key")
        assert(client.isInitialized())
        client.shutdown()
        client.statsigNetwork = brokenNetowrk
        client.initialize(app, "client-key1")
        val checkGate = client.checkGate(user1, "always_on_gate")
        assert(client.isInitialized())
        assert(!checkGate)
        val config = client.getConfig(user1, "test_config")
        assert(config?.getInt("number", 0) == 0)
        assert(config?.evaluationDetails?.reason == EvaluationReason.UNINITIALIZED)
    }

    @Test
    fun cachingForMultiClient() = runBlocking {
        // Populate cache with successful network request
        client.initialize(app, "client-key")
        assert(client.isInitialized())
        val otherClient = StatsigClient()
        val otherNetwork = TestUtil.mockNetwork(TestUtil.getAPIDownloadConfigSpec("/download_config_specs_v2.json"))
        otherClient.statsigNetwork = otherNetwork
        otherClient.initialize(app, "client-key2")
        client.shutdown()
        otherClient.shutdown()

        // Shutdown and reinitialize by loading from cache
        client.statsigNetwork = brokenNetowrk
        otherClient.statsigNetwork = brokenNetowrk
        client.initialize(app, "client-key")
        otherClient.initialize(app, "client-key2")
        assert(otherClient.isInitialized())
        assert(client.isInitialized())

        assert(client.checkGate(user1, "always_on_gate"))
        assert(!otherClient.checkGate(user1, "always_on_gate"))
        assert(!client.checkGate(user1, "always_on_gate_v2"))
        assert(otherClient.checkGate(user1, "always_on_gate_v2"))

        val getConfig1 = client.getConfig(user1, "test_config")
        assert(getConfig1?.getInt("number", 0) == 4)
        assert(getConfig1?.evaluationDetails?.reason == EvaluationReason.CACHE)
        val notValidConfig = otherClient.getConfig(user1, "testConfig")
        assert(notValidConfig?.getInt("number", 0) == 0) // return default value
        assert(notValidConfig?.evaluationDetails?.reason == EvaluationReason.UNRECOGNIZED)
    }
}
