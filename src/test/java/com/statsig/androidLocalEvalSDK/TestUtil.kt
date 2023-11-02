package com.statsig.androidLocalEvalSDK

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import io.mockk.*
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import java.lang.Exception

private const val TEST_TIMEOUT = 10L

class TestUtil {
    companion object {
        fun getConfigTestValues(): Map<String, Any> {
            val string = """
            {
                "name": "",
                "groupName": "",
                "passPercentage": 100,
                "conditions": [],
                "id": "",
                "salt": "",
                "returnValue": {
                    "testString": "test",
                    "testBoolean": true,
                    "testInt": 12,
                    "testDouble": 42.3,
                    "testLong": 9223372036854775806,
                    "testArray": [ "one", "two" ],
                    "testIntArray": [ 3, 2 ],
                    "testDoubleArray": [ 3.1, 2.1 ],
                    "testBooleanArray": [ true, false ],
                    "testNested": {
                        "nestedString": "nested",
                        "nestedBoolean": true,
                        "nestedDouble": 13.74,
                        "nestedLong": 13
                    }
                }
            }
            """.trimIndent()

            val obj = GsonBuilder()
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                .create()
                .fromJson(string, APIRule::class.java)

            return obj.returnValue as Map<String, Any>
        }

        fun getMockApp(): Application {
            return mockk()
        }

        fun stubAppFunctions(app: Application): TestSharedPreferences {
            val sharedPrefs = TestSharedPreferences()

            every {
                app.getSharedPreferences(any(), any())
            } returns sharedPrefs
            every {
                app.applicationInfo
            } returns null
            every {
                app.packageManager
            } returns null
            every {
                app.registerActivityLifecycleCallbacks(any())
            } returns Unit

            // Mock Log
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } answers {
                println(firstArg<String>() + ": " + secondArg())
                0
            }
            every { Log.d(any(), any()) } answers {
                println(firstArg<String>() + ": " + secondArg())
                0
            }
            every { Log.i(any(), any()) } answers {
                println(firstArg<String>() + ": " + secondArg())
                0
            }
            every { Log.e(any(), any()) } answers {
                println(firstArg<String>() + ": " + secondArg())
                0
            }
            return sharedPrefs
        }

        fun mockStatsigUtils() {
            mockkObject(StatsigUtils)
            coEvery {
                StatsigUtils.getFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().getString(secondArg<String>(), null)
            }

            every {
                StatsigUtils.syncGetFromSharedPrefs(any(), any())
            } answers {
                firstArg<SharedPreferences>().getString(secondArg<String>(), null)
            }

            coEvery {
                StatsigUtils.saveStringToSharedPrefs(any(), any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit().putString(secondArg<String>(), thirdArg<String>()).apply()
            }

            coEvery {
                StatsigUtils.removeFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit().remove(secondArg<String>())
            }
        }

        fun mockDispatchers() {
            val dispatcher = TestCoroutineDispatcher()
            Dispatchers.setMain(dispatcher)
            mockkConstructor(CoroutineDispatcherProvider::class)
            mockkConstructor(MainCoroutineDispatcher::class)
            every {
                anyConstructed<CoroutineDispatcherProvider>().io
            } returns dispatcher
            every {
                anyConstructed<CoroutineDispatcherProvider>().main
            } returns dispatcher
            every {
                anyConstructed<CoroutineDispatcherProvider>().default
            } returns dispatcher
        }

        @JvmName("mockBrokenNetwork")
        internal fun mockBrokenNetwork(): StatsigNetwork {
            val statsigNetwork = mockk<StatsigNetwork>()
            coEvery {
                statsigNetwork.getDownloadConfigSpec(any())
            } answers {
                throw Exception()
            }

            coEvery {
                statsigNetwork.postLogs(any(), any())
            } coAnswers {
                throw Exception()
            }

            return statsigNetwork
        }

        @JvmOverloads
        @JvmName("mockNetwork")
        internal fun mockNetwork(
            configSpecs: APIDownloadedConfigs? = null,
        ): StatsigNetwork {
            val statsigNetwork = mockk<StatsigNetwork>()
            var dcs: APIDownloadedConfigs? = configSpecs
            if (dcs == null) {
                dcs = getAPIDownloadConfigSpec("/download_config_specs.json")
            }
            coEvery {
                statsigNetwork.getDownloadConfigSpec(any())
            } answers {
                dcs
            }

            coEvery {
                statsigNetwork.postLogs(any(), any())
            } returns Unit

            return statsigNetwork
        }

        internal fun getAPIDownloadConfigSpec(path: String): APIDownloadedConfigs? {
            val text = CacheTest::class.java.getResource(path)?.readText()
            return StatsigUtils.getGson().fromJson(text, APIDownloadedConfigs::class.java)
        }
    }
}