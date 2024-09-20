package com.statsig.androidlocalevalsdk

import android.app.Application
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class LocalOverrideTest {
    private lateinit var app: Application
    private lateinit var client: StatsigClient

    private val overrides = LocalOverrideAdapter("employeeID")
    private val user = StatsigUser("testUser")

    @Before
    fun setup() {
        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()

        client = StatsigClient()
        client.setup(
            app,
            "secret-key",
            StatsigOptions(overrideAdapter = overrides),
        )
    }

    @Test
    fun testGateOverride() {
        val gateName = "overridden_gate"

        overrides.setGate(user, gateName, true)
        assert(client.checkGate(user, gateName))

        overrides.removeGate(user, gateName)
        assert(!client.checkGate(user, gateName))
    }

    @Test
    fun testConfigOverride() {
        val configName = "overridden_config"
        overrides.setConfig(user, DynamicConfig(configName, mapOf("key" to "val")))

        var config = client.getConfig(user, configName)
        assert(config.value["key"] === "val")

        overrides.removeConfig(user, configName)

        config = client.getConfig(user, configName)
        assert(config.value["key"] === null)
    }

    @Test
    fun testExperimentOverride() {
        val experimentName = "overridden_exp"
        overrides.setExperiment(user, DynamicConfig(experimentName, mapOf("key" to "val")))

        var experiment = client.getExperiment(user, experimentName)
        assert(experiment.value["key"] === "val")

        overrides.removeExperiment(user, experimentName)

        experiment = client.getExperiment(user, experimentName)
        assert(experiment.value["key"] === null)
    }

    @Test
    fun testLayerOverride() {
        val layerName = "overridden_layer"

        overrides.setLayer(user, Layer(layerName, value = mapOf("key" to "val")))

        var layer = client.getLayer(user, layerName)
        assert(layer.value["key"] === "val")

        overrides.removeLayer(user, layerName)

        layer = client.getLayer(user, layerName)
        assert(layer.value["key"] === null)
    }

    @Test
    fun testDifferingUsers() {
        val userA = StatsigUser("")
        userA.customIDs = mapOf("employeeID" to "employee-a")

        overrides.setGate(userA, "overridden_gate", true)

        val userB = StatsigUser("")
        userB.customIDs = mapOf("employeeID" to "employee-b")

        assert(client.checkGate(userA, "overridden_gate"))
        assert(!client.checkGate(userB, "overridden_gate"))
    }
}
