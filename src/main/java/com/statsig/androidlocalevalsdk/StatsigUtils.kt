package com.statsig.androidlocalevalsdk

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.withContext

internal const val OFFLINE_LOGS_KEY: String = "StatsigNetwork.OFFLINE_LOGS"

internal object StatsigUtils {
    private val dispatcherProvider = CoroutineDispatcherProvider()

    fun getTimeInMillis(): Long {
        return System.currentTimeMillis()
    }

    fun toStringOrEmpty(value: Any?): String {
        return value?.toString() ?: ""
    }

    internal fun getGson(serializeNulls: Boolean = false): Gson {
        val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        if (serializeNulls) {
            return gson.serializeNulls().create()
        }
        return gson.create()
    }

    internal fun syncGetFromSharedPrefs(sharedPrefs: SharedPreferences?, key: String): String? {
        if (sharedPrefs == null) {
            return null
        }
        return try {
            sharedPrefs.getString(key, null)
        } catch (e: ClassCastException) {
            null
        }
    }

    internal suspend fun saveStringToSharedPrefs(sharedPrefs: SharedPreferences?, key: String, value: String) {
        if (sharedPrefs == null) {
            return
        }
        withContext(dispatcherProvider.io) {
            val editor = sharedPrefs.edit()
            editor.putString(key, value)
            editor.apply()
        }
    }

    internal suspend fun removeFromSharedPrefs(sharedPrefs: SharedPreferences?, key: String) {
        if (sharedPrefs == null) {
            return
        }
        withContext(dispatcherProvider.io) {
            val editor = sharedPrefs.edit()
            editor.remove(key)
            editor.apply()
        }
    }

    internal fun getFromSharedPrefs(sharedPrefs: SharedPreferences, key: String): String? {
        return try {
            sharedPrefs.getString(key, null)
        } catch (e: Exception) {
            null
        }
    }

    internal suspend fun getSavedLogs(sharedPrefs: SharedPreferences): List<StatsigOfflineRequest> {
        return withContext(dispatcherProvider.io) {
            val json: String = getFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY) ?: return@withContext arrayListOf()

            return@withContext try {
                val pendingRequestType = object : TypeToken<List<StatsigOfflineRequest>>() {}.type
                val pendingRequests: List<StatsigOfflineRequest> = getGson().fromJson(json, pendingRequestType)

                val currentTime = System.currentTimeMillis()
                pendingRequests.filter {
                    it.timestamp > currentTime - MAX_LOG_PERIOD
                }
            } catch (_: Exception) {
                return@withContext arrayListOf()
            }
        }
    }
}
