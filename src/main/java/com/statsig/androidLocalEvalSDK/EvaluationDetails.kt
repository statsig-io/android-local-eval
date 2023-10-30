package com.statsig.androidLocalEvalSDK

internal class EvaluationDetails(
    var configSyncTime: Long,
    var initTime: Long,
    var reason: EvaluationReason,
) {
    var serverTime: Long = StatsigUtils.getTimeInMillis()

    fun toMap(): Map<String, String> {
        return mapOf(
            "reason" to this.reason.toString(),
            "configSyncTime" to this.configSyncTime.toString(),
            "initTime" to this.initTime.toString(),
            "serverTime" to this.serverTime.toString(),
        )
    }
}

enum class EvaluationReason(val reason: String) {
    NETWORK("Network"),
    UNINITIALIZED("Uninitialized"),
    BOOTSTRAP("Bootstrap"),
    INVALID_BOOTSTRAP("InvalidBootstrap"),
}
