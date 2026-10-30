package com.tj.mycardbinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
                // Store only set data and short search results, not full photographed text.
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
        return readCards(arr)
    }

    // Search for the title actually printed on the card, not a generic word
    // from its instructions. Broad guesses made "Poké Pad" look like "Item Finder".
    suspend fun findScannedCandidates(recognizedText: String): List<TradingCard> {
        val titles = ScanIdentity.titles(recognizedText)
        if (titles.isEmpty()) return emptyList()
        for (title in titles.take(2)) {
            val found = linkedMapOf<String, TradingCard>()
            val exactUrl = "cards?name=" +
                URLEncoder.encode("eq:" + title, "UTF-8") +
                "&pagination:itemsPerPage=100"
            try {
                readCards(JSONArray(json(exactUrl))).forEach { found[it.id] = it }
            } catch (_: Exception) {}
            if (found.isEmpty()) {
                val query = "cards?name=" +
                    URLEncoder.encode(title, "UTF-8") +
                    "&pagination:itemsPerPage=150"
                try {
                    readCards(JSONArray(json(query))).forEach { found[it.id] = it }
                } catch (_: Exception) {}
            }
            if (found.isEmpty()) {
                // If OCR left off an accent, search by a distinctive title word.
                val parts = ScanIdentity.key(title).split(' ')
                val fallback = parts.filter {
                    it.length >= 3 && it !in setOf("basic", "energy", "item", "trainer",
                        "pokemon", "the", "and", "card")
                }.lastOrNull()
                if (fallback != null) {
                    try {
                        val query = "cards?name=" + URLEncoder.encode(fallback, "UTF-8") +
                            "&pagination:itemsPerPage=200"
                        readCards(JSONArray(json(query))).forEach { found[it.id] = it }
                    } catch (_: Exception) {}
                }
            }
            val matches = ScanIdentity.match(recognizedText, found.values.toList())
            if (matches.isNotEmpty()) return matches
        }
        // An unidentifiable Energy card or incomplete OCR is safer to retry
        // than an unrelated printing silently added to the binder.
        return emptyList()
    }

    private fun readCards(arr: JSONArray): List<TradingCard> =
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            TradingCard(id, obj.optString("localId", "?"),
                obj.optString("name", "Unknown card"),
                obj.optString("image").takeIf { it.isNotBlank() })
        }
}