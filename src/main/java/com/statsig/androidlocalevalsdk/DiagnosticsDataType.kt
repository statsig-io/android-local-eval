package com.statsig.androidlocalevalsdk

import com.google.gson.annotations.SerializedName

data class Marker(
    @SerializedName("markerID") var markerID: String? = null,
    @SerializedName("key") val key: KeyType? = null,
    @SerializedName("action") val action: ActionType? = null,
    @SerializedName("timestamp") val timestamp: Double? = null,
    @SerializedName("step") var step: StepType? = null,
    @SerializedName("statusCode") var statusCode: Int? = null,
    @SerializedName("success") var success: Boolean? = null,
    @SerializedName("url") var url: String? = null,
    @SerializedName("reason") var reason: String? = null,
    @SerializedName("sdkRegion") var sdkRegion: String? = null,
    @SerializedName("error") var error: ErrorMessage? = null,
    @SerializedName("configName") var configName: String? = null,
    @SerializedName("attempt") var attempt: Int? = null,
    @SerializedName("isDelta") var isDelta: Boolean? = null,
    @SerializedName("hasNetwork") var hasNetwork: Boolean? = null,
    @SerializedName("evaluationDetails") var evaluationDetails: EvaluationDetails? = null,
) {
    data class ErrorMessage(
        @SerializedName("message") val message: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("code") val code: String? = null,
    )

    data class EvaluationDetails(
        @SerializedName("configSyncTime") val configSyncTime: Long? = null,
        @SerializedName("initTime") val initTime: Long? = null,
        @SerializedName("reason") val reason: String? = null,
        @SerializedName("serverTime") val serverTime: Long? = null,
    )
}

enum class ContextType {
    @SerializedName("initialize")
    INITIALIZE,

    @SerializedName("api_call")
    API_CALL,
}

enum class KeyType {
    @SerializedName("download_config_specs")
    DOWNLOAD_CONFIG_SPECS,

    @SerializedName("bootstrap")
    BOOTSTRAP,

    @SerializedName("overall")
    OVERALL,

    @SerializedName("check_gate")
    CHECK_GATE,

    @SerializedName("get_config")
    GET_CONFIG,

    @SerializedName("get_experiment")
    GET_EXPERIMENT,

    @SerializedName("get_layer")
    GET_LAYER, ;

    companion object {
        fun convertFromString(value: String): KeyType? {
            return when (value) {
                in "checkGate" ->
                    KeyType.CHECK_GATE
                in "getExperiment" ->
                    KeyType.GET_EXPERIMENT
                in "getConfig" ->
                    KeyType.GET_CONFIG
                in "getLayer" ->
                    KeyType.GET_LAYER
                else ->
                    null
            }
        }
    }
}

enum class StepType {
    @SerializedName("process")
    PROCESS,

    @SerializedName("network_request")
    NETWORK_REQUEST,
}

enum class ActionType {
    @SerializedName("start")
    START,

    @SerializedName("end")
    END,
}

typealias DiagnosticsMarkers = MutableMap<ContextType, MutableList<Marker>>
