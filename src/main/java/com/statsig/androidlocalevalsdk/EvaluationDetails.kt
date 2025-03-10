package com.statsig.androidlocalevalsdk

class EvaluationDetails(
    val configSyncTime: Long,
    val reason: EvaluationReason,
) {
    val serverTime: Long = StatsigUtils.getTimeInMillis()

    fun toMap(): Map<String, String> {
        return mapOf(
            "reason" to this.reason.reason,
            "configSyncTime" to this.configSyncTime.toString(),
            "serverTime" to this.serverTime.toString(),
        )
    }
}

enum class EvaluationReason(val reason: String) {
    NETWORK("Network"),
    UNINITIALIZED("Uninitialized"),
    UNRECOGNIZED("Unrecognized"),
    BOOTSTRAP("Bootstrap"),
    INVALID_BOOTSTRAP("InvalidBootstrap"),
    CACHE("Cache"),
    UNSUPPORTED("Unsupported"),
    PERSISTED("Persisted"),
    LOCAL_OVERRIDE("LocalOverride"),
}
