package com.statsig.androidLocalEvalSDK

import com.google.gson.annotations.SerializedName

enum class InitializeFailReason {
    CoroutineTimeout,
    NetworkTimeout,
    NetworkError,
    InternalError,
}

sealed class InitializeResponse {
    data class FailedInitializeResponse(
        @SerializedName("reason") val reason: InitializeFailReason,
        @SerializedName("exception") val exception: Exception? = null,
        @SerializedName("statusCode") val statusCode: Int? = null,
    ) : InitializeResponse()
}
