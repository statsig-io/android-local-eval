package com.statsig.androidlocalevalsdk.typed

abstract class TypedExperiment<T>(
    val name: String,
    private val groups: Array<T>,
    val isMemoizable: Boolean = false,
    val memoUnitIdType: String = "userID"
) where T : Enum<T> {
    val group: T?
        get() = _group

    private var _group: T? = null

    fun trySetGroupFromString(input: String?) {
        if (input == null) {
            return
        }

        return try {
            _group = groups.find { it.name.equals(input, ignoreCase = true) }
        } catch (e: Exception) {
            println("Error: Failed to parse group name. $e")
        }
    }


}

