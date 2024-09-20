package com.statsig.androidlocalevalsdk

interface IOverrideAdapter {
    fun checkGate(user: StatsigUser, name: String, options: CheckGateOptions?): Boolean?
    fun getConfig(user: StatsigUser, name: String, options: GetConfigOptions?): DynamicConfig?
    fun getExperiment(user: StatsigUser, name: String, options: GetExperimentOptions?): DynamicConfig?
    fun getLayer(user: StatsigUser, name: String, options: GetLayerOptions?): Layer?
}
