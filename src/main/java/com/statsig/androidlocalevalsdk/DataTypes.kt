package com.statsig.androidlocalevalsdk

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

internal data class APIDownloadedConfigs(
    @SerializedName("dynamic_configs") val dynamicConfigs: Array<APIConfig>,
    @SerializedName("feature_gates") val featureGates: Array<APIConfig>,
    @SerializedName("layer_configs") val layerConfigs: Array<APIConfig>,
    @SerializedName("layers") val layers: Map<String, Array<String>>?,
    @SerializedName("time") val time: Long = 0,
    @SerializedName("has_updates") val hasUpdates: Boolean,
    @SerializedName("diagnostics") val diagnostics: Map<String, Int>? = null,
    @SerializedName("default_environment") val defaultEnvironment: String? = null,
)


@JsonAdapter(ReturnableValue.CustomSerializer::class)
internal data class ReturnableValue(
    val booleanValue: Boolean? = null,
    val rawJson: String = "null",
    val mapValue: Map<String, Any>? = null
) {
    fun getValue(): Any? {
        if (booleanValue != null) {
            return booleanValue
        }

        if (mapValue != null) {
            return mapValue
        }

        return null
    }

    internal class CustomSerializer : JsonDeserializer<ReturnableValue>, JsonSerializer<ReturnableValue> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): ReturnableValue {
            if (json == null) {
                return ReturnableValue()
            }

            if (json.isJsonPrimitive && json.asJsonPrimitive.isBoolean) {
                val booleanValue = json.asJsonPrimitive.asBoolean
                return ReturnableValue(booleanValue, json.toString(), null)
            }

            if (json.isJsonObject) {
                val jsonObject = json.asJsonObject
                val mapValue = context?.deserialize<Map<String, Any>>(jsonObject, Map::class.java) ?: emptyMap()
                return ReturnableValue(null, json.toString(), mapValue)
            }

            return ReturnableValue()
        }

        override fun serialize(
            src: ReturnableValue?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement {
            if (src == null) {
                return JsonNull.INSTANCE
            }

            return JsonParser.parseString(src.rawJson)

        }

    }
}


internal data class APIConfig(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("salt") val salt: String,
    @SerializedName("defaultValue") val defaultValue: ReturnableValue,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rules") val rules: Array<APIRule>,
    @SerializedName("idType") val idType: String,
    @SerializedName("entity") val entity: String,
    @SerializedName("explicitParameters") val explicitParameters: Array<String>?,
    @SerializedName("hasSharedParams") val hasSharedParams: Boolean?,
    @SerializedName("targetAppIDs") val targetAppIDs: Array<String>? = null,
    @SerializedName("version") val version: Int? = null,
)


internal data class APIRule(
    @SerializedName("name") val name: String,
    @SerializedName("passPercentage") val passPercentage: Double,
    @SerializedName("returnValue") val returnValue: ReturnableValue,
    @SerializedName("id") val id: String,
    @SerializedName("salt") val salt: String?,
    @SerializedName("conditions") val conditions: Array<APICondition>,
    @SerializedName("idType") val idType: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("configDelegate") val configDelegate: String?,
    @SerializedName("isExperimentGroup") val isExperimentGroup: Boolean?,
)

internal data class APICondition(
    @SerializedName("type") val type: String,
    @SerializedName("targetValue") val targetValue: Any?,
    @SerializedName("operator") val operator: String?,
    @SerializedName("field") val field: String?,
    @SerializedName("additionalValues") val additionalValues: Map<String, Any>?,
    @SerializedName("idType") val idType: String,
)

internal data class LayerExposureMetadata(
    @SerializedName("config") val config: String,
    @SerializedName("ruleID") val ruleID: String,
    @SerializedName("allocatedExperiment") val allocatedExperiment: String,
    @SerializedName("parameterName") val parameterName: String,
    @SerializedName("isExplicitParameter") val isExplicitParameter: String,
    @SerializedName("secondaryExposures") val secondaryExposures: ArrayList<Map<String, String>>,
    @SerializedName("isManualExposure") var isManualExposure: String = "false",
    @SerializedName("evaluationDetails") val evaluationDetails: EvaluationDetails?,
    @SerializedName("configVersion") val configVersion: Int? = null,
) {
    fun toStatsigEventMetadataMap(): MutableMap<String, String> {
        return mutableMapOf(
            "config" to config,
            "ruleID" to ruleID,
            "allocatedExperiment" to allocatedExperiment,
            "parameterName" to parameterName,
            "isExplicitParameter" to isExplicitParameter,
            "isManualExposure" to isManualExposure,
            "configVersion" to configVersion.toString(),
            // secondaryExposures excluded -- StatsigEvent adds secondaryExposures explicitly as a top level key
        )
    }
}
