package com.statsig.androidLocalEvalSDK

import kotlinx.coroutines.CoroutineScope

internal class Evaluator(private val specStore: Store, private val network: StatsigNetwork, private val options: StatsigOptions, private val statsigMetadata: StatsigMetadata, private val statsigScope: CoroutineScope, private val errorBoundary: ErrorBoundary) {
    private var gson = StatsigUtils.getGson()
}
