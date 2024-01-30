package com.statsig.androidLocalEvalSDK

import com.google.gson.annotations.SerializedName

internal class ConfigEvaluation(
    val booleanValue: Boolean = false,
    val jsonValue: Any? = null,
    val ruleID: String = "",
    val groupName: String? = null,
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    val explicitParameters: Array<String> = arrayOf(),
    val configDelegate: String? = null,
    var evaluationDetails: EvaluationDetails? = null,
    var isExperimentGroup: Boolean = false,
) {
    var undelegatedSecondaryExposures: ArrayList<Map<String, String>> = secondaryExposures

    // Used to save to PersistentStorage
    fun toPersistedValueConfig(): PersistedValueConfig {
        return PersistedValueConfig(
            value = this.booleanValue,
            jsonValue = this.jsonValue,
            ruleID = this.ruleID,
            groupName = this.groupName,
            secondaryExposures = this.secondaryExposures,
            isExperimentGroup = this.isExperimentGroup,
            time = this.evaluationDetails?.configSyncTime,
        )
    }
}

internal class PersistedValueConfig(
    @SerializedName("value") val value: Boolean = false,
    @SerializedName("json_value") val jsonValue: Any? = null,
    @SerializedName("rule_id") val ruleID: String = "",
    @SerializedName("group_name") val groupName: String? = null,
    @SerializedName("secondary_exposures") val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("time") var time: Long? = null,
    @SerializedName("is_experiment_group") var isExperimentGroup: Boolean = false,
) {
    fun toConfigEvaluationData(): ConfigEvaluation {
        val evalDetail = EvaluationDetails(this.time ?: StatsigUtils.getTimeInMillis(), EvaluationReason.PERSISTED)
        return ConfigEvaluation(
            jsonValue = this.jsonValue,
            booleanValue = this.value,
            ruleID = this.ruleID,
            groupName = this.groupName,
            secondaryExposures = this.secondaryExposures,
            isExperimentGroup = this.isExperimentGroup,
            evaluationDetails = evalDetail,
        )
    }
}

internal enum class ConfigCondition {
    PUBLIC,
    FAIL_GATE,
    PASS_GATE,
    IP_BASED,
    UA_BASED,
    USER_FIELD,
    CURRENT_TIME,
    ENVIRONMENT_FIELD,
    USER_BUCKET,
    UNIT_ID,
}
