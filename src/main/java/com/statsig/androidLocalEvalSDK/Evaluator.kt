package com.statsig.androidLocalEvalSDK

import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date

internal class Evaluator(private val specStore: Store, private val network: StatsigNetwork, private val options: StatsigOptions, private val statsigMetadata: StatsigMetadata, private val statsigScope: CoroutineScope, private val errorBoundary: ErrorBoundary) {
    private val calendarOne = Calendar.getInstance()
    private val calendarTwo = Calendar.getInstance()
    private var hashLookupTable: MutableMap<String, ULong> = HashMap()

    fun checkGate(user: StatsigUser, name: String): ConfigEvaluation {
        // TODO(xinli) Override logic
        if (specStore.initReason == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED),
            )
        }

        val evalGate = specStore.getGate(name)
        return this.evaluateConfig(user, evalGate)
    }

    fun getConfig(user: StatsigUser, configName: String): ConfigEvaluation {
        // TODO(xinli): implement override
        if (specStore.initReason == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED),
            )
        }
        return evaluateConfig(user, specStore.getConfig(configName))
    }

    fun getLayer(user: StatsigUser, layerName: String): ConfigEvaluation {
        // TODO(xinli): implement override

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
                    fetchFromServer = false,
                    booleanValue = false,
                    mapOf<String, Any>(),
                    evaluationDetails = createEvaluationDetails(EvaluationReason.UNRECOGNIZED),
                )
        return this.evaluate(user, unwrappedConfig)
    }

    private fun evaluate(user: StatsigUser, config: APIConfig): ConfigEvaluation {
        val evaluationDetails = createEvaluationDetails(specStore.initReason)
        if (!config.enabled) {
            return ConfigEvaluation(
                fetchFromServer = false,
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

            if (result.fetchFromServer) {
                return result
            }

            secondaryExposures.addAll(result.secondaryExposures)
            if (result.booleanValue) {
                val delegatedEval = this.evaluateDelegate(user, rule, secondaryExposures)
                if(delegatedEval != null) {
                    return delegatedEval
                }
                val pass = evaluatePassPercent(user, config, rule)
                return ConfigEvaluation(
                    false,
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
            fetchFromServer = false,
            booleanValue = false,
            config.defaultValue,
            "default",
            "",
            secondaryExposures,
            evaluationDetails = evaluationDetails,
        )
    }

    private fun evaluateRule(user: StatsigUser, rule: APIRule): ConfigEvaluation {
        val secondaryExposures = arrayListOf<Map<String, String>>()
        var pass = true
        for (condition in rule.conditions) {
            val result = this.evaluateCondition(user, condition)
            if (result.fetchFromServer) {
                return result
            }
            if (!result.booleanValue) {
                pass = false
            }
            secondaryExposures.addAll(result.secondaryExposures)
        }

        return ConfigEvaluation(
            fetchFromServer = false,
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
            fetchFromServer = delegatedResult.fetchFromServer,
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
                errorBoundary.logException(e, tag = "evaluateCondition:condition")
                null
            }

            when (conditionEnum) {
                ConfigCondition.PUBLIC ->
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = true)

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
                        result.fetchFromServer,
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
                    val salt = EvaluatorUtils.getValueAsString(condition.additionalValues?.let { it["salt"] })
                    val unitID = EvaluatorUtils.getUnitID(user, condition.idType) ?: ""
                    value = computeUserHash("$salt.$unitID").mod(1000UL)
                }

                ConfigCondition.UNIT_ID -> {
                    value = EvaluatorUtils.getUnitID(user, condition.idType)
                }

                else -> {
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }

            when (condition.operator) {
                "gt" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue > doubleTargetValue,
                    )
                }

                "gte" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue >= doubleTargetValue,
                    )
                }

                "lt" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue < doubleTargetValue,
                    )
                }

                "lte" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue <= doubleTargetValue,
                    )
                }

                "version_gt" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) > 0
                        },
                    )
                }

                "version_gte" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) >= 0
                        },
                    )
                }

                "version_lt" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) < 0
                        },
                    )
                }

                "version_lte" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) <= 0
                        },
                    )
                }

                "version_eq" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) == 0
                        },
                    )
                }

                "version_neq" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) != 0
                        },
                    )
                }

                "any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }

                "none" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }

                "any_case_sensitive" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }

                "none_case_sensitive" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }

                "str_starts_with_any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.startsWith(b, true)
                        },
                    )
                }

                "str_ends_with_any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.endsWith(b, true)
                        },
                    )
                }

                "str_contains_any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }

                "str_contains_none" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }

                "str_matches" -> {
                    val targetValue = EvaluatorUtils.getValueAsString(condition.targetValue)
                        ?: return ConfigEvaluation(
                            fetchFromServer = false,
                            booleanValue = false,
                        )

                    val strValue =
                        EvaluatorUtils.getValueAsString(value)
                            ?: return ConfigEvaluation(
                                fetchFromServer = false,
                                booleanValue = false,
                            )

                    return ConfigEvaluation(
                        fetchFromServer = false,
                        booleanValue = Regex(targetValue).containsMatchIn(strValue),
                    )
                }

                "eq" -> {
                    return ConfigEvaluation(fetchFromServer = false, value == condition.targetValue)
                }

                "neq" -> {
                    return ConfigEvaluation(fetchFromServer = false, value != condition.targetValue)
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
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }
        } catch (e: IllegalArgumentException) {
            errorBoundary.logException(e, tag = "evaluateCondition:all")
            return ConfigEvaluation(true)
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
