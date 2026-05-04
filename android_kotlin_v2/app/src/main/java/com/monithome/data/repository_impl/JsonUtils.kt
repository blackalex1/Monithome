package com.monithome.data.repository_impl

import org.json.JSONArray
import org.json.JSONObject

object JsonUtils {
    fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            if (value is JSONObject) map[key] = jsonToMap(value)
            else if (value is JSONArray) map[key] = jsonToList(value)
            else map[key] = value
        }
        return map
    }

    fun jsonToList(array: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            if (value is JSONObject) list.add(jsonToMap(value))
            else if (value is JSONArray) list.add(jsonToList(value))
            else list.add(value)
        }
        return list
    }
}
