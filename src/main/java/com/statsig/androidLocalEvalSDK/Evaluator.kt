package com.statsig.androidLocalEvalSDK

import android.util.Log
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date

internal class Evaluator(private val specStore: Store, private val errorBoundary: ErrorBoundary, private val persistentStorage: StatsigUserPersistenStorageHelper?) {
    private val calendarOne = Calendar.getInstance()
    private val calendarTwo = Calendar.getInstance()
    private var hashLookupTable: MutableMap<String, ULong> = HashMap()
    private val gson = StatsigUtils.getGson()
    fun checkGate(user: StatsigUser, name: String): ConfigEvaluation {
        if (specStore.initReason == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED),
            )
        }

        val evalGate = specStore.getGate(name)
        return this.evaluateConfig(user, evalGate)
    }

    fun getConfig(user: StatsigUser, configName: String, persistedValues: PersistedValues? = null): ConfigEvaluation {
        if (specStore.initReason == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED),
            )
        }
        val config = specStore.getConfig(configName)
        if (config == null) {
            // Delegate to evaluateConfig to construct the evaluation
            return evaluateConfig(user, config)
        }
        if (config.isActive && persistedValues != null) {
            val stickyValue = persistedValues?.get(configName)
            if (stickyValue != null) {
                // return sticky value
                return gson.fromJson(stickyValue, PersistedValueConfig::class.java).toConfigEvaluationData()
            }
            val evaluation = evaluateConfig(user, config)
            persistentStorage?.save(user, config.idType, configName, gson.toJson(evaluation.toPersistedValueConfig()))
            return evaluation
        } else {
            persistentStorage?.delete(user, config.idType, configName)
            return evaluateConfig(user, config)
        }
    }

    fun getLayer(user: StatsigUser, layerName: String): ConfigEvaluation {
        if (specStore.initReason == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED),
            )
        }

        return this.evaluateConfig(user, specStore.getLayerConfig(layerName))
    }

    private fun evaluateConfig(user: StatsigUser, config: APIConfig?): ConfigEvaluation {
        val unwrappedConfig =
            config
                ?: return ConfigEvaluation(
                    booleanValue = false,
                    mapOf<String, Any>(),
                    evaluationDetails = createEvaluationDetails(EvaluationReason.UNRECOGNIZED),
                )
        return this.evaluate(user, unwrappedConfig)
    }

    private fun evaluate(user: StatsigUser, config: APIConfig): ConfigEvaluation {
        try {
            val evaluationDetails = createEvaluationDetails(specStore.initReason)
            if (!config.enabled) {
                return ConfigEvaluation(
                    booleanValue = false,
                    config.defaultValue,
                    "disabled",
                    evaluationDetails = evaluationDetails,
                )
            }
            val secondaryExposures = arrayListOf<Map<String, String>>()
            for (rule in config.rules) {
                val result = this.evaluateRule(user, rule)
                result.evaluationDetails = evaluationDetails
                secondaryExposures.addAll(result.secondaryExposures)
                if (result.booleanValue) {
                    val delegatedEval = this.evaluateDelegate(user, rule, secondaryExposures)
                    if (delegatedEval != null) {
                        return delegatedEval
                    }
                    val pass = evaluatePassPercent(user, config, rule)
                    return ConfigEvaluation(
                        pass,
                        if (pass) result.jsonValue else config.defaultValue,
                        result.ruleID,
                        result.groupName,
                        secondaryExposures,
                        evaluationDetails = evaluationDetails,
                        isExperimentGroup = rule.isExperimentGroup ?: false,
                    )
                }
            }
            return ConfigEvaluation(
                booleanValue = false,
                config.defaultValue,
                "default",
                "",
                secondaryExposures,
                evaluationDetails = evaluationDetails,
            )
        } catch (e: UnsupportedEvaluationException) {
            // Return default value for unsupported evaluation
            errorBoundary.logException(e, e.message)
            return ConfigEvaluation(
                booleanValue = false,
                jsonValue = config.defaultValue,
                ruleID = "default",
                explicitParameters = config.explicitParameters ?: arrayOf(),
                evaluationDetails = EvaluationDetails(configSyncTime = specStore.lcut, reason = EvaluationReason.UNSUPPORTED),
            )
        }
    }

    private fun evaluateRule(user: StatsigUser, rule: APIRule): ConfigEvaluation {
        val secondaryExposures = arrayListOf<Map<String, String>>()
        var pass = true
        for (condition in rule.conditions) {
            val result = this.evaluateCondition(user, condition)
            if (!result.booleanValue) {
                pass = false
            }
            secondaryExposures.addAll(result.secondaryExposures)
        }

        return ConfigEvaluation(
            booleanValue = pass,
            rule.returnValue,
            rule.id,
            rule.groupName,
            secondaryExposures,
            isExperimentGroup = rule.isExperimentGroup == true,
        )
    }

    private fun evaluateDelegate(user: StatsigUser, rule: APIRule, secondaryExposures: ArrayList<Map<String, String>>): ConfigEvaluation? {
        val configDelegate = rule.configDelegate ?: return null
        val config = specStore.getConfig(configDelegate) ?: return null

        val delegatedResult = this.evaluate(user, config)
        val undelegatedSecondaryExposures = arrayListOf<Map<String, String>>()
        undelegatedSecondaryExposures.addAll(secondaryExposures)
        secondaryExposures.addAll(delegatedResult.secondaryExposures)

        val evaluation = ConfigEvaluation(
            booleanValue = delegatedResult.booleanValue,
            jsonValue = delegatedResult.jsonValue,
            ruleID = delegatedResult.ruleID,
            groupName = delegatedResult.groupName,
            secondaryExposures = secondaryExposures,
            configDelegate = rule.configDelegate,
            explicitParameters = config.explicitParameters ?: arrayOf(),
            evaluationDetails = this.createEvaluationDetails(this.specStore.initReason),
        )
        evaluation.undelegatedSecondaryExposures = undelegatedSecondaryExposures
        return evaluation
    }

    private fun evaluateCondition(user: StatsigUser, condition: APICondition): ConfigEvaluation {
        try {
            var value: Any?
            val field: String = StatsigUtils.toStringOrEmpty(condition.field)
            val conditionEnum: ConfigCondition? = try {
                ConfigCondition.valueOf(condition.type.uppercase())
            } catch (e: java.lang.IllegalArgumentException) {
                throw UnsupportedEvaluationException("Unsupported condition: ${condition.type}")
            }

            when (conditionEnum) {
                ConfigCondition.PUBLIC ->
                    return ConfigEvaluation(booleanValue = true)

                ConfigCondition.FAIL_GATE, ConfigCondition.PASS_GATE -> {
                    val name = StatsigUtils.toStringOrEmpty(condition.targetValue)
                    val result = this.checkGate(user, name)
                    val newExposure =
                        mapOf(
                            "gate" to name,
                            "gateValue" to result.booleanValue.toString(),
                            "ruleID" to result.ruleID,
                        )
                    val secondaryExposures = arrayListOf<Map<String, String>>()
                    secondaryExposures.addAll(result.secondaryExposures)
                    secondaryExposures.add(newExposure)
                    return ConfigEvaluation(
                        if (conditionEnum == ConfigCondition.PASS_GATE) {
                            result.booleanValue
                        } else {
                            !result.booleanValue
                        },
                        result.jsonValue,
                        "",
                        "",
                        secondaryExposures,
                    )
                }

                ConfigCondition.IP_BASED -> {
                    value = EvaluatorUtils.getFromUser(user, field)
                }

                ConfigCondition.USER_FIELD -> {
                    value = EvaluatorUtils.getFromUser(user, field)
                }

                ConfigCondition.CURRENT_TIME -> {
                    value = System.currentTimeMillis().toString()
                }

                ConfigCondition.ENVIRONMENT_FIELD -> {
                    value = EvaluatorUtils.getFromEnvironment(user, field)
                }

                ConfigCondition.USER_BUCKET -> {
                    val salt =
                        EvaluatorUtils.getValueAsString(condition.additionalValues?.let { it["salt"] })
                    val unitID = EvaluatorUtils.getUnitID(user, condition.idType) ?: ""
                    value = computeUserHash("$salt.$unitID").mod(1000UL)
                }

                ConfigCondition.UNIT_ID -> {
                    value = EvaluatorUtils.getUnitID(user, condition.idType)
                }

                else -> {
                    Log.d("STATSIG", "Unsupported evaluation conditon: $conditionEnum")
                    throw UnsupportedEvaluationException("Unsupported evaluation conditon: $conditionEnum")
                }
            }

            when (condition.operator) {
                "gt" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(
                        doubleValue > doubleTargetValue,
                    )
                }

                "gte" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(
                        doubleValue >= doubleTargetValue,
                    )
                }

                "lt" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(

                        doubleValue < doubleTargetValue,
                    )
                }

                "lte" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(

                        doubleValue <= doubleTargetValue,
                    )
                }

                "version_gt" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) > 0
                        },
                    )
                }

                "version_gte" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) >= 0
                        },
                    )
                }

                "version_lt" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) < 0
                        },
                    )
                }

                "version_lte" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) <= 0
                        },
                    )
                }

                "version_eq" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) == 0
                        },
                    )
                }

                "version_neq" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) != 0
                        },
                    )
                }

                "any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }

                "none" -> {
                    return ConfigEvaluation(
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }

                "any_case_sensitive" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }

                "none_case_sensitive" -> {
                    return ConfigEvaluation(
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }

                "str_starts_with_any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.startsWith(b, true)
                        },
                    )
                }

                "str_ends_with_any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.endsWith(b, true)
                        },
                    )
                }

                "str_contains_any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }

                "str_contains_none" -> {
                    return ConfigEvaluation(

                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }

                "str_matches" -> {
                    val targetValue = EvaluatorUtils.getValueAsString(condition.targetValue)
                        ?: return ConfigEvaluation(
                            booleanValue = false,
                        )

                    val strValue =
                        EvaluatorUtils.getValueAsString(value)
                            ?: return ConfigEvaluation(
                                booleanValue = false,
                            )

                    return ConfigEvaluation(
                        booleanValue = Regex(targetValue).containsMatchIn(strValue),
                    )
                }

                "eq" -> {
                    return ConfigEvaluation(value == condition.targetValue)
                }

                "neq" -> {
                    return ConfigEvaluation(value != condition.targetValue)
                }

                "before" -> {
                    return EvaluatorUtils.compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.before(b)
                        },
                        value,
                        condition.targetValue,
                    )
                }

                "after" -> {
                    return EvaluatorUtils.compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.after(b)
                        },
                        value,
                        condition.targetValue,
                    )
                }

                "on" -> {
                    return EvaluatorUtils.compareDates(
                        { a: Date, b: Date ->
                            calendarOne.time = a
                            calendarTwo.time = b
                            return@compareDates calendarOne[Calendar.YEAR] ==
                                calendarTwo[Calendar.YEAR] &&
                                calendarOne[Calendar.DAY_OF_YEAR] ==
                                calendarTwo[Calendar.DAY_OF_YEAR]
                        },
                        value,
                        condition.targetValue,
                    )
                }

                else -> {
                    throw UnsupportedEvaluationException("Unsupported evaluation conditon operator: ${condition.operator}")
                }
            }
        } catch (e: IllegalArgumentException) {
            throw UnsupportedEvaluationException("IllegalArgumentException when evaluate conditions")
        }
    }

    fun versionCompareHelper(
        version1: Any?,
        version2: Any?,
        compare: (v1: String, v2: String) -> Boolean,
    ): Boolean {
        var version1Str = EvaluatorUtils.getValueAsString(version1)
        var version2Str = EvaluatorUtils.getValueAsString(version2)

        if (version1Str == null || version2Str == null) {
            return false
        }

        val dashIndex1 = version1Str.indexOf('-')
        if (dashIndex1 > 0) {
            version1Str = version1Str.substring(0, dashIndex1)
        }

        val dashIndex2 = version2Str.indexOf('-')
        if (dashIndex2 > 0) {
            version2Str = version2Str.substring(0, dashIndex2)
        }

        return try {
            compare(version1Str, version2Str)
        } catch (e: NumberFormatException) {
            false
        } catch (e: Exception) {
            errorBoundary.logException(e, tag = "versionCompareHelper")
            false
        }
    }

    private fun evaluatePassPercent(user: StatsigUser, config: APIConfig, rule: APIRule): Boolean {
        return computeUserHash(
            config.salt +
                '.' +
                (rule.salt ?: rule.id) +
                '.' +
                (EvaluatorUtils.getUnitID(user, rule.idType) ?: ""),
        )
            .mod(10000UL) < (rule.passPercentage.times(100.0)).toULong()
    }

    private fun computeUserHash(input: String): ULong {
        hashLookupTable[input]?.let {
            return it
        }

        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        val hash = ByteBuffer.wrap(bytes).long.toULong()

        if (hashLookupTable.size > 1000) {
            hashLookupTable.clear()
        }

        hashLookupTable[input] = hash
        return hash
    }

    private fun createEvaluationDetails(reason: EvaluationReason): EvaluationDetails {
        if (reason == EvaluationReason.UNINITIALIZED) {
            return EvaluationDetails(0, reason)
        }
        return EvaluationDetails(specStore.lcut, reason)
    }
}

class UnsupportedEvaluationException(message: String) : Exception(message)
