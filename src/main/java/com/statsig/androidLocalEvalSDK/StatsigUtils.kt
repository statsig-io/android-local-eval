package com.statsig.androidLocalEvalSDK

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy

internal class StatsigUtils {
    companion object {
        fun getTimeInMillis(): Long {
            return System.currentTimeMillis()
        }

        internal fun getGson(): Gson {
            return GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
        }
    }
}
