package com.statsig.androidlocalevalsdk.typed

import TypedGateName
import android.util.Log
import com.statsig.androidlocalevalsdk.EvaluatorUtils
import com.statsig.androidlocalevalsdk.FeatureGate
import com.statsig.androidlocalevalsdk.StatsigClient
import com.statsig.androidlocalevalsdk.StatsigOptions
import com.statsig.androidlocalevalsdk.StatsigUser

typealias AnyExperiment = TypedExperiment<*>

internal class MemoStore {
    var experiments = mutableMapOf<String, AnyExperiment>()
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

    open fun checkGate(
        name: TypedGateName,
        user: StatsigUser? = null
    ): Boolean {
        return getFeatureGate(name, user).value
    }

    open fun getFeatureGate(
        name: TypedGateName,
        user: StatsigUser? = null
    ): FeatureGate {
        val (client, validatedUser) = validate(user) ?: return FeatureGate.empty(name.value)

        val found = tryGetMemoFeatureGate(name, validatedUser)
        if (found != null) {
            return found
        }

        val gate = client.getFeatureGate(validatedUser, name.value)
        return tryMemoizeFeatureGate(name, gate, validatedUser)
    }

    open fun <T> getExperiment(
        experiment: T,
        user: StatsigUser? = null
    ): T where T : AnyExperiment {
        val (client, validatedUser) = validate(user) ?: return experiment

        val found = tryGetMemoExperiment(experiment, validatedUser)
        if (found != null) {
            return found
        }

        val rawExperiment = client.getExperiment(validatedUser, experiment.name)

        experiment.trySetGroupFromString(rawExperiment.groupName)

        if (experiment.group == null && rawExperiment.groupName != null) {
            Log.e(
                TAG,
                "Error: Failed to convert group name: ${rawExperiment.groupName} to enum for experiment ${experiment.name}"
            )
            return experiment
        }

        return tryMemoizeExperiment(experiment, validatedUser)
    }

    open fun bind(client: StatsigClient, options: StatsigOptions) {
        this.client = client
    }

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

    private fun <T : AnyExperiment> tryGetMemoExperiment(experiment: T, user: StatsigUser): T? {
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

    private fun <T : AnyExperiment> tryMemoizeExperiment(experiment: T, user: StatsigUser): T {
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

    private fun tryMemoizeFeatureGate(name: TypedGateName, gate: FeatureGate, user: StatsigUser): FeatureGate {
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

