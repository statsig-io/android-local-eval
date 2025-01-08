package com.statsig.androidlocalevalsdk.typed

import com.google.gson.Gson

open class TypedExperiment<G, V>(
    name: String,
    groups: Array<G>?,
    isMemoizable: Boolean = false,
    memoUnitIdType: String = "userID",
    valueClass: Class<V>? = null
) where G : Enum<G> {
    companion object {
        const val INVALID_SUBCLASS = "InvalidTypedExperimentSubclass"
    }

    val name: String
        get() = _name

    val isMemoizable: Boolean
        get() = _isMemoizable

    val memoUnitIdType: String
        get() = _memoUnitIdType

    val valueClass: Class<V>?
        get() = _valueClass

    val group: G?
        get() = _group

    val value: V?
        get() = _value

    private var _name: String
    private var _groups: Array<G>?
    private var _isMemoizable: Boolean
    private var _memoUnitIdType: String
    private var _valueClass: Class<V>?
    private var _group: G? = null
    private var _value: V? = null

    init {
        _name = name
        _groups = groups
        _isMemoizable = isMemoizable
        _memoUnitIdType = memoUnitIdType
        _valueClass = valueClass
    }

    internal constructor() : this(INVALID_SUBCLASS, null)

    fun <T : TypedExperiment<*, *>> new(): T? {
        return try {
            val inst = this.javaClass.getDeclaredConstructor().newInstance()
            if (inst::class != this::class) {
                return null
            }

            inst._name = _name
            inst._groups = _groups
            inst._isMemoizable = _isMemoizable
            inst._memoUnitIdType = _memoUnitIdType
            inst._valueClass = _valueClass
            @Suppress("UNCHECKED_CAST")
            inst as? T
        } catch (e: ClassCastException) {
            null
        }
    }

    fun <T : TypedExperiment<*, *>> clone(): T? {
        val inst = this.new() as? TypedExperiment<G, V> ?: return null
        if (inst::class != this::class) {
            return null
        }

        return try {
            inst._group = group
            inst._value = value
            @Suppress("UNCHECKED_CAST")
            inst as? T
        } catch (e: ClassCastException) {
            null
        }
    }

    fun trySetGroupFromString(input: String?) {
        val groups = _groups
        if (input == null || groups == null) {
            return
        }

        try {
            _group = groups.find { it.name.equals(input, ignoreCase = true) }
        } catch (e: Exception) {
            println("Error: Failed to parse group name. $e")
        }
    }

    fun trySetValueFromString(gson: Gson, input: String?) {
        val json = input ?: return

        try {
            _value = gson.fromJson(json, _valueClass)
        } catch (e: Exception) {
            println("Error: Failed to deserialize value. $e")
        }
    }
}

class NoValue
open class TypedExperimentWithoutValue<G>(
    name: String,
    groups: Array<G>,
    isMemoizable: Boolean = false,
    memoUnitIdType: String = "userID",
) : TypedExperiment<G, NoValue>(
    name, groups, isMemoizable, memoUnitIdType
) where G : Enum<G>

