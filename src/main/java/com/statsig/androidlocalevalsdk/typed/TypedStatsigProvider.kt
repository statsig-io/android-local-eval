package com.statsig.androidlocalevalsdk.typed

import TypedGateName
import android.util.Log
import com.statsig.androidlocalevalsdk.CheckGateOptions
import com.statsig.androidlocalevalsdk.EvaluatorUtils
import com.statsig.androidlocalevalsdk.FeatureGate
import com.statsig.androidlocalevalsdk.GetExperimentOptions
import com.statsig.androidlocalevalsdk.StatsigClient
import com.statsig.androidlocalevalsdk.StatsigOptions
import com.statsig.androidlocalevalsdk.StatsigUser
import com.statsig.androidlocalevalsdk.StatsigUtils

typealias AnyTypedExperiment = TypedExperiment<*, *>

internal class MemoStore {
    var experiments = mutableMapOf<String, AnyTypedExperiment>()
    var gates = mutableMapOf<String, FeatureGate>()

    fun reset() {
        experiments = mutableMapOf()
        gates = mutableMapOf()
    }
}

open class TypedStatsigProvider {
    companion object {
        private const val TAG = "TypedStatsigProvider"
    }

    var client: StatsigClient? = null
    internal var memo = MemoStore()
    internal var gson = StatsigUtils.getGson(serializeNulls = true)

    open fun checkGate(
        name: TypedGateName,
        user: StatsigUser? = null,
        options: CheckGateOptions? = null
    ): Boolean {
        return getFeatureGate(name, user, options).value
    }

    open fun getFeatureGate(
        name: TypedGateName,
        user: StatsigUser? = null,
        options: CheckGateOptions? = null
    ): FeatureGate {
        val (client, validatedUser) = validate(user) ?: return FeatureGate.empty(name.value)

        val found = tryGetMemoFeatureGate(name, validatedUser)
        if (found != null) {
            return found
        }

        val gate = client.getFeatureGate(validatedUser, name.value, options)
        return tryMemoizeFeatureGate(name, gate, validatedUser)
    }

    open fun <T> getExperiment(
        experiment: T,
        user: StatsigUser? = null,
        options: GetExperimentOptions? = null
    ): T where T : AnyTypedExperiment {
        val name = experiment.name
        val result: T = experiment.new() ?: run {
            Log.e(TAG, "Failed to create a new instance of experiment $name")
            return experiment
        }

        val (client, validatedUser) = validate(user) ?: return result

        val found = tryGetMemoExperiment(result, validatedUser)
        if (found != null) {
            return found.clone() ?: run {
                Log.e(TAG, "Failed to clone memoized instance of experiment $name")
                result
            }
        }

        val rawExperiment = client.getExperiment(validatedUser, result.name, options)
        val rawGroup = rawExperiment.groupName
        result.trySetGroupFromString(rawGroup)
        result.trySetValueFromString(gson, rawExperiment.rawValue)

        if (result.group == null && rawGroup != null) {
            Log.e(TAG, "Failed to convert group name: $rawGroup to enum for experiment $name")
        }

        if (result.value == null && rawGroup != null && experiment.valueClass != null) {
            Log.e(TAG, "Failed to convert group name: $rawGroup to enum for experiment $name")
        }

        return tryMemoizeExperiment(result, validatedUser)
    }

    open fun bind(client: StatsigClient, options: StatsigOptions) {
        this.client = client
    }


    /**
     * Private
     */

    private fun validate(optUser: StatsigUser?): Pair<StatsigClient, StatsigUser>? {
        val client = this.client ?: run {
            Log.e(TAG, "Error: Must initialize Statsig first")
            return null
        }

        val potentialUser = optUser ?: client.globalUser
        val user = potentialUser ?: run {
            Log.e(TAG, "Error: No user given when calling Statsig")
            return null
        }

        return Pair(client, user)
    }

    private fun <T : AnyTypedExperiment> tryGetMemoExperiment(
        experiment: T,
        user: StatsigUser
    ): T? {
        if (!experiment.isMemoizable) {
            return null
        }

        val key = getMemoKey(user, experiment.memoUnitIdType, experiment.name)
        val found = memo.experiments[key] ?: return null
        if (found::class != experiment::class) {
            Log.e(TAG, "Error: Memoized experiment was found but is of a different type")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return found as? T
    }

    private fun <T : AnyTypedExperiment> tryMemoizeExperiment(experiment: T, user: StatsigUser): T {
        if (!experiment.isMemoizable) {
            return experiment
        }

        val key = getMemoKey(user, experiment.memoUnitIdType, experiment.name)
        memo.experiments[key] = experiment
        return experiment
    }

    private fun tryGetMemoFeatureGate(
        name: TypedGateName,
        user: StatsigUser
    ): FeatureGate? {
        if (!name.isMemoizable) {
            return null
        }

        val key = getMemoKey(user, name.memoUnitIdType, name.value)
        return memo.gates[key]
    }

    private fun tryMemoizeFeatureGate(
        name: TypedGateName,
        gate: FeatureGate,
        user: StatsigUser
    ): FeatureGate {
        if (!name.isMemoizable) {
            return gate
        }

        val key = getMemoKey(user, name.memoUnitIdType, name.value)
        memo.gates[key] = gate
        return gate
    }

    private fun getMemoKey(user: StatsigUser, idType: String, name: String): String {
        val idValue = EvaluatorUtils.getUnitID(user, idType) ?: "<NONE>"
        return "$idType:$idValue:$name"
    }
}

