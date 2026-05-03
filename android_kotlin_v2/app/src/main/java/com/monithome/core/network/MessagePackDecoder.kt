package com.monithome.core.network

import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType

/**
 * Декодер MessagePack для потоковой бинарной статистики.
 */
object MessagePackDecoder {

    fun decode(bytes: ByteArray): Map<String, Any> {
        val unpacker = MessagePack.newDefaultUnpacker(bytes)
        if (!unpacker.hasNext()) return emptyMap()
        
        val value = unpacker.unpackValue()
        return if (value.valueType == ValueType.MAP) {
            val map = value.asMapValue().map()
            val result = mutableMapOf<String, Any>()
            
            map.forEach { (k, v) ->
                val key = if (k.isStringValue) k.asStringValue().asString() else k.toString()
                result[key] = convertValue(v)
            }
            result
        } else {
            emptyMap()
        }
    }

    private fun convertValue(value: org.msgpack.value.Value): Any {
        return when (value.valueType) {
            ValueType.BOOLEAN -> value.asBooleanValue().boolean
            ValueType.INTEGER -> value.asIntegerValue().asLong()
            ValueType.FLOAT -> value.asFloatValue().toDouble()
            ValueType.STRING -> value.asStringValue().asString()
            ValueType.MAP -> {
                val m = value.asMapValue().map()
                val r = mutableMapOf<String, Any>()
                m.forEach { (k, v) -> 
                    val key = if (k.isStringValue) k.asStringValue().asString() else k.toString()
                    r[key] = convertValue(v) 
                }
                r
            }
            ValueType.ARRAY -> {
                val a = value.asArrayValue()
                val r = mutableListOf<Any>()
                a.forEach { r.add(convertValue(it)) }
                r
            }
            else -> value.toString()
        }
    }
}
