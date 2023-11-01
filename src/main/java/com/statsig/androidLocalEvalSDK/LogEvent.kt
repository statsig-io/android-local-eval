package com.statsig.androidLocalEvalSDK

import com.google.gson.annotations.SerializedName
import kotlin.collections.ArrayList

internal data class LogEvent(
    @SerializedName("eventName") val eventName: String,
    @SerializedName("value") val eventValue: Any? = null,
    @SerializedName("metadata") var eventMetadata: Map<String, String>? = null,
    @SerializedName("user") var user: StatsigUser? = null,
    @SerializedName("statsigMetadata") val statsigMetadata: StatsigMetadata? = null,
    @SerializedName("secondaryExposures") val secondaryExposures: ArrayList<Map<String, String>>? = arrayListOf(),
    @SerializedName("time") val time: Long? = StatsigUtils.getTimeInMillis(),
) {
    init {
        // We need to use a special copy of the user object that strips out private attributes for logging purposes
        user = user?.getCopyForLogging()
    }
}
