package com.statsig.androidlocalevalsdk

import com.google.gson.annotations.SerializedName

internal class ConfigEvaluation(
    val booleanValue: Boolean = false,
    val jsonValue: Any? = null,
    val returnableValue: ReturnableValue? = null,
    val ruleID: String = "",
    val groupName: String? = null,
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    val explicitParameters: Array<String>? = null,
    val configDelegate: String? = null,
    var evaluationDetails: EvaluationDetails? = null,
    var isExperimentGroup: Boolean = false,
    var configVersion: Int? = null,
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
            explicitParameters = this.explicitParameters,
            configDelegate = this.configDelegate,
            undelegatedSecondaryExposures = this.undelegatedSecondaryExposures,
            time = this.evaluationDetails?.configSyncTime,
        )
    }

    companion object {
        fun fromGateOverride(override: Boolean, configSyncTime: Long): ConfigEvaluation {
            return ConfigEvaluation(
                booleanValue = override,
                evaluationDetails = EvaluationDetails(configSyncTime, EvaluationReason.LOCAL_OVERRIDE),
            )
        }

        fun fromConfigOverride(override: DynamicConfig, configSyncTime: Long): ConfigEvaluation {
            return ConfigEvaluation(
                jsonValue = override.value,
                ruleID = override.ruleID ?: "",
                groupName = override.groupName,
                secondaryExposures = override.secondaryExposures,
                evaluationDetails = EvaluationDetails(configSyncTime, EvaluationReason.LOCAL_OVERRIDE),
            )
        }

        fun fromLayerOverride(override: Layer, configSyncTime: Long): ConfigEvaluation {
            return ConfigEvaluation(
                jsonValue = override.value,
                ruleID = override.ruleID ?: "",
                groupName = override.groupName,
                secondaryExposures = override.secondaryExposures,
                evaluationDetails = EvaluationDetails(configSyncTime, EvaluationReason.LOCAL_OVERRIDE),
            )
        }
    }
}

internal class PersistedValueConfig(
    @SerializedName("value") val value: Boolean = false,
    @SerializedName("json_value") val jsonValue: Any? = null,
    @SerializedName("rule_id") val ruleID: String = "",
    @SerializedName("group_name") val groupName: String? = null,
    @SerializedName("secondary_exposures") val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("explicit_parameters") val explicitParameters: Array<String>? = null,
    @SerializedName("config_delegate") val configDelegate: String? = null,
    @SerializedName("undelegated_secondary_exposures") val undelegatedSecondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("time") var time: Long? = null,
) {
    fun toConfigEvaluationData(): ConfigEvaluation {
        val evalDetail = EvaluationDetails(this.time ?: StatsigUtils.getTimeInMillis(), EvaluationReason.PERSISTED)
        val evaluation = ConfigEvaluation(
            jsonValue = this.jsonValue,
            booleanValue = this.value,
            ruleID = this.ruleID,
            groupName = this.groupName,
            secondaryExposures = this.secondaryExposures,
            explicitParameters = this.explicitParameters,
            configDelegate = this.configDelegate,
            isExperimentGroup = true,
            evaluationDetails = evalDetail,
        )
        evaluation.undelegatedSecondaryExposures = this.undelegatedSecondaryExposures
        return evaluation
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
