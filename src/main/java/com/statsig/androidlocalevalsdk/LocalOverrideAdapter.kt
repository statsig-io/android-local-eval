package com.statsig.androidlocalevalsdk

class LocalOverrideAdapter(
    private val idType: String = "userID",
) : IOverrideAdapter {
    private val store: MutableMap<String, OverrideStore> = mutableMapOf()

    class OverrideStore {
        val gates: MutableMap<String, Boolean> = mutableMapOf()
        val configs: MutableMap<String, DynamicConfig> = mutableMapOf()
        val experiments: MutableMap<String, DynamicConfig> = mutableMapOf()
        val layers: MutableMap<String, Layer> = mutableMapOf()
    }

    /// Feature Gates

    override fun checkGate(user: StatsigUser, name: String, options: CheckGateOptions?): Boolean? {
        return this.store[this.getUserKey(user)]?.gates?.get(name)
    }

    fun setGate(user: StatsigUser, name: String, value: Boolean) {
        val userKey = this.getUserKey(user)
        if (!this.store.containsKey(userKey)) {
            this.store[userKey] = OverrideStore()
        }
        this.store[userKey]?.gates?.put(name, value)
    }

    fun removeGate(user: StatsigUser, name: String) {
        this.store[this.getUserKey(user)]?.gates?.remove(name)
    }

    /// Dynamic Configs

    override fun getConfig(user: StatsigUser, name: String, options: GetConfigOptions?): DynamicConfig? {
        return this.store[this.getUserKey(user)]?.configs?.get(name)
    }

    fun setConfig(user: StatsigUser, config: DynamicConfig) {
        val userKey = this.getUserKey(user)
        if (!this.store.containsKey(userKey)) {
            this.store[userKey] = OverrideStore()
        }
        this.store[userKey]?.configs?.put(config.name, config)
    }

    fun removeConfig(user: StatsigUser, name: String) {
        this.store[this.getUserKey(user)]?.configs?.remove(name)
    }

    /// Experiments

    override fun getExperiment(user: StatsigUser, name: String, options: GetExperimentOptions?): DynamicConfig? {
        return this.store[this.getUserKey(user)]?.experiments?.get(name)
    }

    fun setExperiment(user: StatsigUser, experiment: DynamicConfig) {
        val userKey = this.getUserKey(user)
        if (!this.store.containsKey(userKey)) {
            this.store[userKey] = OverrideStore()
        }
        this.store[userKey]?.experiments?.put(experiment.name, experiment)
    }

    fun removeExperiment(user: StatsigUser, name: String) {
        this.store[this.getUserKey(user)]?.experiments?.remove(name)
    }

    /// Layers

    override fun getLayer(user: StatsigUser, name: String, options: GetLayerOptions?): Layer? {
        return this.store[this.getUserKey(user)]?.layers?.get(name)
    }

    fun setLayer(user: StatsigUser, layer: Layer) {
        val userKey = this.getUserKey(user)
        if (!this.store.containsKey(userKey)) {
            this.store[userKey] = OverrideStore()
        }
        this.store[userKey]?.layers?.put(layer.name, layer)
    }

    fun removeLayer(user: StatsigUser, name: String) {
        this.store[this.getUserKey(user)]?.layers?.remove(name)
    }

    /// Private

    private fun getUserKey(user: StatsigUser): String {
        return user.getID(idType) ?: ""
    }
}
