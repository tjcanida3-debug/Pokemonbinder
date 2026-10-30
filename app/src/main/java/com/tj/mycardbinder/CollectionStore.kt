package com.tj.mycardbinder

import android.content.Context
import org.json.JSONObject

enum class Finish(val title: String) {
    REGULAR("Regular"), HOLO("Holo"), REVERSE("Reverse holo"), FIRST_EDITION("1st edition")
}
fun inventoryKey(id: String, finish: Finish) = id + "|" + finish.name
fun totalOwned(id: String, inventory: Map<String, Int>) =
    Finish.entries.sumOf { inventory[inventoryKey(id, it)] ?: 0 }

class CollectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("my_collection", Context.MODE_PRIVATE)
    fun load(): Map<String, Int> = try {
        val obj = JSONObject(prefs.getString("counts", "{}") ?: "{}")
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val n = obj.optInt(key)
                if (n > 0) put(key, n)
            }
        }
    } catch (_: Exception) { emptyMap() }
    fun save(values: Map<String, Int>) {
        val obj = JSONObject()
        values.forEach { (key, value) -> if (value > 0) obj.put(key, value) }
        prefs.edit().putString("counts", obj.toString()).apply()
    }
}
