package com.tj.mycardbinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CardSet(val id: String, val name: String, val count: Int)
data class TradingCard(val id: String, val localId: String, val name: String, val imageBase: String?)

class Catalog(context: Context) {
    private val base = "https://api.tcgdex.net/v2/en"
    private val cache = context.getSharedPreferences("catalog_cache", Context.MODE_PRIVATE)
    private suspend fun json(path: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(base + "/" + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            try {
                if (conn.responseCode !in 200..299) error("API error " + conn.responseCode)
                val value = conn.inputStream.bufferedReader().use { it.readText() }
                cache.edit().putString(path, value).apply()
                value
            } finally { conn.disconnect() }
        } catch (ex: Exception) { cache.getString(path, null) ?: throw ex }
    }
    suspend fun sets(): List<CardSet> {
        val arr = JSONArray(json("sets"))
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CardSet(obj.getString("id"), obj.getString("name"),
                obj.optJSONObject("cardCount")?.optInt("total") ?: 0)
        }
    }
    suspend fun cardsInSet(id: String): List<TradingCard> {
        val arr = JSONObject(json("sets/" + id)).optJSONArray("cards") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            TradingCard(obj.getString("id"), obj.optString("localId", "?"),
                obj.getString("name"), obj.optString("image").takeIf { it.isNotBlank() })
        }
    }
}
