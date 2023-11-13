[![](https://jitpack.io/v/statsig-io/android-local-eval.svg)](https://jitpack.io/#statsig-io/android-local-eval)
# Statsig

## Statsig Android with Local Evaluation SDK

A slimmed version of the Android that operates on the rule definitions to evaluate any gate/experiment locally (more like a Statsig server SDK).  This means when you change the user object or set of properties to check against, you do not need to make a network request to statsig - the SDK already has everything it needs to evaluate an arbitrary user object against an experiment or feature gate.  You can choose to host the rulesets for your project on your own CDN, or to inline them in your server page response.

Statsig helps you move faster with feature gates (feature flags), and/or dynamic configs. It also allows you to run A/B/n tests to validate your new features and understand their impact on your KPIs. If you're new to Statsig, check out our product and create an account at [statsig.com](https://www.statsig.com).

## Getting Started
Check out our [SDK docs](https://docs.statsig.com/client/jsLocalEvaluationSDK) to get started.

## Supported Features
- Gate Checks
- Dynamic Configs
- Layers/Experiments
- Custom Event Logging
- Synchronous and Asynchronous initialization

## Unsupported Features - relative to statsig-kotlin
- Local Overrides
- Extended Browser Support
- Big ID List based segments (>1k IDs)
- IP/UA Based Checks, inferred country from IP
