package com.statsig.androidLocalEvalSDK

import kotlinx.coroutines.CoroutineScope

class StatsigUserPersistenStorageHelper(private val provider: UserPersistentStorageInterface, private val coroutineScope: CoroutineScope) {
    suspend fun load(user: StatsigUser, idType: String): PersistedValues {
        val key = getStorageKey(user, idType)
        return provider.load(key)
    }

    fun save(user: StatsigUser, idType: String, experimentName: String, data: String) {
        val key = getStorageKey(user, idType)
        provider.save(key, experimentName, data)
    }

    fun delete(user: StatsigUser, idType: String, experiment: String) {
        val key = getStorageKey(user, idType)
        provider.delete(key, experiment)
    }

    fun loadAsync(user: StatsigUser, idType: String, callback: IPersistentStorageCallback) {
        val key = getStorageKey(user, idType)
        return provider.loadAsync(key, callback)
    }

    companion object {
        fun getStorageKey(user: StatsigUser, idType: String): String {
            return "${user.getID(idType)}:$idType"
        }
    }
}

typealias PersistedValues = Map<String, String> // Experiment name map to Evaluation JSON
