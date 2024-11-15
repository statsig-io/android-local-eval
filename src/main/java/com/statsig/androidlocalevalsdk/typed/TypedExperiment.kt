package com.statsig.androidlocalevalsdk.typed

interface TypedGroupName {}

abstract class TypedExperiment<T>(
    val name: String,
    val groups: Array<T>,
    val isMemoizable: Boolean = false,
    val memoUnitIdType: String = "userID"
) where T : Enum<T>, T : TypedGroupName {
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

