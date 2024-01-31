package com.statsig.androidlocalevalsdk

/**
 * Callback interface for Statsig calls. All callbacks will be run on the main thread.
 */
@FunctionalInterface
interface IStatsigCallback {
    fun onStatsigInitialize() {}

    fun onStatsigInitialize(initDetails: InitializationDetails) {
        return this.onStatsigInitialize()
    }
}

/**
 * A singleton class for interfacing with gates, configs, and logging in the Statsig console
 */
object Statsig {
    var client: StatsigClient = StatsigClient()
}
