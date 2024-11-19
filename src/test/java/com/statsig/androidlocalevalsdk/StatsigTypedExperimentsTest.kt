package com.statsig.androidlocalevalsdk

import android.app.Application
import com.statsig.androidlocalevalsdk.typed.TypedExperiment
import io.mockk.mockk
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
    TypedExperiment<TestExpGroup>("experiment_with_many_params", TestExpGroup.values()) {

}

class TestMemoExperiment : TypedExperiment<TestMemoExperiment.MemoGroup>(
    "test_exp_5050_targeting",
    MemoGroup.values(),
    isMemoizable = true
) {
    enum class MemoGroup {
        Control, Test
    }
}

class SameTestMemoExperiment : TypedExperiment<TestMemoExperiment.MemoGroup>(
    "test_exp_5050_targeting",
    TestMemoExperiment.MemoGroup.values(),
    isMemoizable = true
)

class TestBadGroupExperiment : TypedExperiment<TestBadGroupExperiment.BadGroup>(
    "experiment_with_many_params",
    BadGroup.values()
) {
    enum class BadGroup {
        Error, Bad
    }
}

class StatsigTypedTest {
    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var user: StatsigUser

    @Before
    fun setup() {
        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtils()

        val dcs =
            File("src/test/java/com/statsig/androidlocalevalsdk/eval_proj_dcs.json").readText()

        client = StatsigClient()
        client.initializeSync(app, "client-key", dcs)

        user = StatsigUser("user-in-test")
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
    fun testMemoizedExperiments() {
        client.typed.memo.reset()

        val first = TestMemoExperiment()
        val exp1 = client.typed.getExperiment(first, user)

        val second = TestMemoExperiment()
        val exp2 = client.typed.getExperiment(second, user)

        assertTrue(exp1 === exp2)
        assertTrue(first !== second)
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
}
