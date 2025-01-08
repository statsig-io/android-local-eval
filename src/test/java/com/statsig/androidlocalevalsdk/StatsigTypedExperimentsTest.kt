package com.statsig.androidlocalevalsdk

import android.app.Application
import com.google.gson.annotations.SerializedName
import com.statsig.androidlocalevalsdk.typed.TypedExperiment
import com.statsig.androidlocalevalsdk.typed.TypedExperimentWithoutValue
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@Suppress("EnumEntryName")
enum class TestExpGroup {
    Control, Test, `Test #2`
}

class TestExperiment :
    TypedExperimentWithoutValue<TestExpGroup>(
        "experiment_with_many_params", TestExpGroup.values()
    )

class TestMemoExperiment : TypedExperimentWithoutValue<TestMemoExperiment.MemoGroup>(
    "test_exp_5050_targeting",
    MemoGroup.values(),
    isMemoizable = true
) {
    enum class MemoGroup {
        Control, Test
    }
}

class SameTestMemoExperiment : TypedExperimentWithoutValue<TestMemoExperiment.MemoGroup>(
    "test_exp_5050_targeting",
    TestMemoExperiment.MemoGroup.values(),
    isMemoizable = true
)

class TestBadGroupExperiment : TypedExperimentWithoutValue<TestBadGroupExperiment.BadGroup>(
    "experiment_with_many_params",
    BadGroup.values()
) {
    enum class BadGroup {
        Error, Bad
    }
}

data class TestExpValue(
    @SerializedName("a_string") val aString: String,
    @SerializedName("another_string") val anotherString: String,
    @SerializedName("a_number") val aNumber: Double,
    @SerializedName("a_bool") val aBool: Boolean,
    @SerializedName("an_object") val anObject: Map<String, Any>,
    @SerializedName("an_array") val anArray: List<Any>,
    @SerializedName("another_bool") val anotherBool: Boolean,
    @SerializedName("another_number") val anotherNumber: Double,
)

class TestExperimentWithValue: TypedExperiment<TestExpGroup, TestExpValue>(
    "experiment_with_many_params", TestExpGroup.values(), valueClass = TestExpValue::class.java
)

data class TestExpBadValue(
    @SerializedName("invalid_entry") val invalidEntry: String,
)

class TestExperimentWithBadValue: TypedExperiment<TestExpGroup, TestExpBadValue>(
    "experiment_with_many_params", TestExpGroup.values(), valueClass = TestExpBadValue::class.java
)

val myExperimentDefinition = TypedExperiment(
    "experiment_with_many_params", TestExpGroup.values(), valueClass = TestExpValue::class.java
)

class StatsigTypedExperimentsTest {
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
        }, configSpecs = TestUtil.getAPIDownloadConfigSpec("/empty_dcs.json"))

        val dcs =
            File("src/test/resources/eval_proj_dcs.json").readText()

        client = StatsigClient()
        client.statsigNetwork = network
        client.initializeSync(app, "client-key", dcs)

        user = StatsigUser("user-in-test")
    }

    private fun resetEvents () = runBlocking {
        client.flushEvents()
        events = mutableListOf()
    }

    @Test
    fun testGettingExperimentGroups() {
        val exp = client.typed.getExperiment(TestExperiment(), user)

        // also test its switchable
        when (exp.group) {
            TestExpGroup.Control -> {
                assertTrue(false)
            }

            TestExpGroup.Test -> {
                assertTrue(true)
            }

            TestExpGroup.`Test #2` -> {
                assertTrue(false)
            }

            else -> assertTrue(false)
        }
    }

    @Test
    fun testComplicatedGroupName() {
        val exp = client.typed.getExperiment(TestExperiment(), StatsigUser("user-in-test-2"))
        assertEquals(TestExpGroup.`Test #2`, exp.group)
    }

    @Test
    fun testMemoizedExperiments() = runBlocking {
        client.typed.memo.reset()

        val first = TestMemoExperiment()
        val exp1 = client.typed.getExperiment(first, user)
        assertEquals(TestMemoExperiment.MemoGroup.Test, exp1.group)

        client.updateAsync()

        val second = TestMemoExperiment()
        val exp2 = client.typed.getExperiment(second, user)
        assertEquals(TestMemoExperiment.MemoGroup.Test, exp2.group)
    }

    @Test
    fun testBadMemoConfiguration() {
        client.typed.memo.reset()

        val first = TestMemoExperiment()
        val exp1 = client.typed.getExperiment(first, user)

        val second = SameTestMemoExperiment()
        val exp2 = client.typed.getExperiment(second, user)

        assertNotNull(exp1)
        assertNotNull(exp2)
    }

    @Test
    fun testBadGroupEnumConfiguration() {
        val exp = client.typed.getExperiment(TestBadGroupExperiment(), user)
        assertNotNull(exp)
        assertNull(exp.group)
    }

    @Test
    fun testGlobalUser() {
        client.typed.memo.reset()

        client.setGlobalUser(StatsigUser("global-user-in-control"))
        val exp = client.typed.getExperiment(TestMemoExperiment())

        assertEquals(TestMemoExperiment.MemoGroup.Control, exp.group)
    }

    @Test
    fun testExperimentExposures() = runBlocking {
        resetEvents()

        client.typed.getExperiment(TestExperiment(), user)
        client.flushEvents()

        assertEquals(1, events.size)
        assertEquals("statsig::config_exposure", events[0].eventName)
    }

    @Test
    fun testDisablingExperimentExposures() = runBlocking {
        resetEvents()

        val disabledExposure = GetExperimentOptions(disableExposureLogging = true)
        client.typed.getExperiment(TestExperiment(), user, disabledExposure)
        client.flushEvents()

        assertEquals(0, events.size)
    }

    @Test
    fun testValueDeserialization() = runBlocking {
        val exp = client.typed.getExperiment(TestExperimentWithValue(), user)
        assertEquals("test_1", exp.value?.aString)
        assertEquals("layer_default", exp.value?.anotherString)

        assertEquals(false, exp.value?.aBool)
        assertEquals(false, exp.value?.anotherBool)

        assertEquals(2.0, exp.value?.aNumber)
        assertEquals(2.0, exp.value?.anotherNumber)

        assertEquals(1, exp.value?.anArray?.size)
        assertEquals("test_1", exp.value?.anArray?.first())

        assertEquals(1, exp.value?.anObject?.size)
        assertEquals("layer_default", exp.value?.anObject?.get("value"))
    }

    @Test
    fun testValueInvalidDeserialization() = runBlocking {
        val exp = client.typed.getExperiment(TestExperimentWithBadValue(), user)
        assertNull(exp.value?.invalidEntry)
    }

    @Test
    fun testReusingExperimentDefinitions() = runBlocking {
        val one = client.typed.getExperiment(myExperimentDefinition, user)
        assertEquals("test_1", one.value?.aString)

        val two = client.typed.getExperiment(myExperimentDefinition, StatsigUser("user-in-test-2"))

        assertEquals("test_1", one.value?.aString)
        assertEquals("test_2", two.value?.aString)
    }

    @Test
    fun testReturnsClonedInstances() = runBlocking {
        val one = client.typed.getExperiment(myExperimentDefinition, user)
        val two = client.typed.getExperiment(myExperimentDefinition, user)

        println(one.value?.aString)
        assert(one !== myExperimentDefinition)
        assert(one !== two)
    }

    @Test
    fun testMemoReturnsClonedInstances() = runBlocking {
        client.typed.memo.reset()

        val def = TestMemoExperiment()
        val one = client.typed.getExperiment(def, user)
        val two = client.typed.getExperiment(def, user)

        assert(one !== def)
        assert(one !== two)

        assertEquals(TestMemoExperiment.MemoGroup.Test, one.group)
        assertEquals(TestMemoExperiment.MemoGroup.Test, two.group)
    }
}
