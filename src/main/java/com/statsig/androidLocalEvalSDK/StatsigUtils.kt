package com.statsig.androidLocalEvalSDK

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.withContext

internal object StatsigUtils {
    private val dispatcherProvider = CoroutineDispatcherProvider()

    fun getTimeInMillis(): Long {
        return System.currentTimeMillis()
    }

    fun toStringOrEmpty(value: Any?): String {
        return value?.toString() ?: ""
    }

    internal fun getGson(): Gson {
        return GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
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
}
