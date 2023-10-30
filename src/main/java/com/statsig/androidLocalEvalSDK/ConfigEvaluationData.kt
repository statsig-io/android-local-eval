package com.statsig.androidLocalEvalSDK

internal class ConfigEvaluation(
    val fetchFromServer: Boolean = false,
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
