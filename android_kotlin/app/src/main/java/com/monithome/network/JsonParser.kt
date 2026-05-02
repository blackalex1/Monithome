package com.monithome.network

import org.json.JSONArray
import org.json.JSONObject

object JsonParser {
    fun safeParseJson(args: Array<Any>, eventName: String? = null): Any? {
        try {
            if (args.isEmpty()) {
                android.util.Log.i("JsonParser", "Args are empty!")
                return null
            }
            val dataIndex = if (eventName != null && args[0] is String && args[0] == eventName && args.size > 1) 1 else 0
            val raw = args[dataIndex]
            
            if (raw is JSONObject || raw is JSONArray) return raw
            
            val str = raw.toString()
            if (str.isEmpty()) return null
            
            return try {
                if (str.startsWith("{")) JSONObject(str)
                else if (str.startsWith("[")) JSONArray(str)
                else null
            } catch (e: Exception) { null }
        } catch (e: Exception) {
            return null
        }
    }
}
