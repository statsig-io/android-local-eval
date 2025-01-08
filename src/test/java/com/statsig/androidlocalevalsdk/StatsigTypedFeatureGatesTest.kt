package com.statsig.androidlocalevalsdk

import TypedGateName
import android.app.Application
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.BeforeEach
import java.io.File

object MyGates {
    val simple = TypedGateName("test_public")
    val memoized = TypedGateName(
        "test_50_50",
        isMemoizable = true,
        memoUnitIdType = "employeeID"
    )
}

class StatsigTypedFeatureGatesTest {
    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var user: StatsigUser
    private lateinit var network: StatsigNetwork
    private var events = mutableListOf<LogEvent>()

    @Before
    fun setup() {
        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()

        network = TestUtil.mockNetwork(onLog = {
            events.addAll(it)
        })

        val dcs = File("src/test/resources/eval_proj_dcs.json").readText()

        client = StatsigClient()
        client.statsigNetwork = network
        client.initializeSync(app, "client-key", dcs)


        user = StatsigUser("user-passing")
        user.customIDs = mapOf("employeeID" to "1")
    }

    private fun resetEvents () = runBlocking {
        client.flushEvents()
        events = mutableListOf()
    }

    @Test
    fun testGettingGateValue() {
        val result = client.typed.checkGate(MyGates.simple, user)
        assertTrue(result)
    }

    @Test
    fun testMemoizedGates() {
        val gate1 = client.typed.getFeatureGate(MyGates.memoized, user)
        val gate2 = client.typed.getFeatureGate(MyGates.memoized, user)

        assertTrue(gate1 === gate2)

        val otherUser = StatsigUser("user-that-fails")
        otherUser.customIDs = mapOf("employeeID" to "2")

        val gate3 = client.typed.getFeatureGate(MyGates.memoized, otherUser)
        gate3.evaluationDetails?.reason

        assertTrue(gate1 !== gate3)
        assertFalse(gate3.value)
    }

    @Test
    fun testNullUser() {
        val result = client.typed.checkGate(MyGates.simple, null)
        assertFalse(result)
    }

    @Test
    fun testGlobalUser() {
        client.setGlobalUser(StatsigUser("my-global-user"))
        val result = client.typed.checkGate(MyGates.simple)
        assertTrue(result)
    }

    @Test
    fun testGateExposures() = runBlocking {
        resetEvents()

        client.typed.checkGate(MyGates.simple, user)
        client.typed.getFeatureGate(MyGates.simple, user)
        client.flushEvents()
        assertEquals(2, events.size)
        assertEquals("statsig::gate_exposure", events[0].eventName)
        assertEquals("statsig::gate_exposure", events[1].eventName)
    }

    @Test
    fun testDisablingGateExposures() = runBlocking {
        resetEvents()

        val disabledExposure = CheckGateOptions(disableExposureLogging = true)
        client.typed.checkGate(MyGates.simple, user, disabledExposure)
        client.typed.getFeatureGate(MyGates.simple, user, disabledExposure)
        client.flushEvents()
        assertEquals(0, events.size)
    }
}