package com.statsig.androidlocalevalsdk

import TypedGateName
import android.app.Application
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class SimpleGateName : TypedGateName("test_public")

class MemoizableGateName : TypedGateName(
    "test_50_50",
    isMemoizable = true,
    memoUnitIdType = "employeeID"
)

class StatsigTypedFeatureGatesTest {
    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var user: StatsigUser

    @Before
    fun setup() {
        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()

        val dcs = File("src/test/java/com/statsig/androidlocalevalsdk/eval_proj_dcs.json").readText()

        client = StatsigClient()
        client.initializeSync(app, "client-key", dcs)

        user = StatsigUser("user-passing")
        user.customIDs = mapOf("employeeID" to "1")
    }

    @Test
    fun testGettingGateValue() {
        val result = client.typed.checkGate(SimpleGateName(), user)
        assertTrue(result)
    }

    @Test
    fun testMemoizedGates() {
        val name = MemoizableGateName()
        val gate1 = client.typed.getFeatureGate(name, user)
        val gate2 = client.typed.getFeatureGate(name, user)

        assertTrue(gate1 === gate2)

        val otherUser = StatsigUser("user-that-fails")
        otherUser.customIDs = mapOf("employeeID" to "2")

        val gate3 = client.typed.getFeatureGate(name, otherUser)

        assertTrue(gate1 !== gate3)
        assertFalse(gate3.value)
    }

    @Test
    fun testNullUser() {
        val result = client.typed.checkGate(SimpleGateName(), null)
        assertFalse(result)
    }

    @Test
    fun testGlobalUser() {
        client.setGlobalUser(StatsigUser("my-global-user"))
        val result = client.typed.checkGate(SimpleGateName())
        assertTrue(result)
    }
}