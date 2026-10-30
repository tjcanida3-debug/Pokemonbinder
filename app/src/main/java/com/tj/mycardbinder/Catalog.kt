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

    // Search across all sets by the card title and, where available, printed card number.
    // The scanner only offers suggestions: the user chooses the final match.
    suspend fun findScannedCandidates(recognizedText: String): List<TradingCard> {
        val titleLines = recognizedText.lineSequence()
            .map { it.trim() }
            .filter { it.length in 3..38 && it.any { character -> character.isLetter() } }
            .take(15)
            .map { it.replace(Regex("(?i)^(basic|stage\\s*[0-2]|special)\\s+"), "")
                .replace(Regex("(?i)\\bHP\\s*\\d+.*"), "")
                .replace(Regex("[^A-Za-z0-9 '’-]"), " ")
                .replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 4..32 && it.any { character -> character.isLetter() } &&
                !it.matches(Regex("(?i)^(ENERGY|TRAINER|POK[EÉ]MON|BASIC|STAGE).*")) &&
                it.split(' ').size <= 5 }
            .toList()
        val queries = linkedSetOf<String>()
        for (line in titleLines) {
            queries.add(line)
            // Include the first title word when OCR mixes a title with a rarity label.
            if (line.contains(' ') && line.split(' ').first().length >= 5) {
                queries.add(line.split(' ').first())
            }
            if (queries.size >= 4) break
        }
        val cards = linkedMapOf<String, TradingCard>()
        for (name in queries.take(4)) {
            try {
                val encoded = URLEncoder.encode(name, "UTF-8")
                val arr = JSONArray(json("cards?name=" + encoded + "&pagination:itemsPerPage=90"))
                readCards(arr).forEach { cards[it.id] = it }
            } catch (_: Exception) { /* try remaining titles */ }
        }
        if (cards.isEmpty()) {
            val number = Regex("(?<!\\d)([0-9]{1,3})\\s*[/|]\\s*[0-9]{2,3}")
                .find(recognizedText)?.groupValues?.get(1)
            if (number != null) {
                try {
                    readCards(JSONArray(json("cards?localId=eq:" + number + "&pagination:itemsPerPage=90")))
                        .forEach { cards[it.id] = it }
                } catch (_: Exception) {}
            }
        }
        return matchScannedCard(recognizedText, cards.values.toList())
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