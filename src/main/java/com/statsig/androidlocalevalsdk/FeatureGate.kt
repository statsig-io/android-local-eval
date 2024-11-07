package com.statsig.androidlocalevalsdk

class FeatureGate(
        val name: String,
        val value: Boolean,
        val ruleID: String? = null,
        val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
        var evaluationDetails: EvaluationDetails? = null,
) {
    companion object {
        fun empty(name: String = ""): FeatureGate {
            return FeatureGate(name, false)
        }
    }

    init { }
}