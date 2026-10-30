package com.tj.mycardbinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MarketPrices(
    val byFinish: Map<Finish, Double>,
    val updatedAt: String?
) {
    fun forFinish(finish: Finish): Double? = byFinish[finish]
}

class PriceCatalog(context: Context) {
    private val preferences = context.getSharedPreferences("market_price_cache", Context.MODE_PRIVATE)
    private val refreshPeriodMs = 12L * 60L * 60L * 1000L

    private data class Snapshot(val result: MarketPrices, val storedAt: Long)

    private fun readCached(id: String): Snapshot? = try {
        val data = JSONObject(preferences.getString("card:" + id, null) ?: return null)
        Snapshot(parse(data.getJSONObject("body")), data.optLong("savedAt", 0))
    } catch (_: Exception) {
        null
    }

    fun cachedAll(): Map<String, MarketPrices> = buildMap {
        preferences.all.keys.filter { it.startsWith("card:") }.forEach { key ->
            readCached(key.removePrefix("card:"))?.let {
                put(key.removePrefix("card:"), it.result)
            }
        }
    }

    suspend fun get(id: String): MarketPrices {
        val old = readCached(id)
        if (old != null && System.currentTimeMillis() - old.storedAt < refreshPeriodMs) return old.result
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.tcgdex.net/v2/en/cards/" + id)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 16000
                val body = try {
                    if (connection.responseCode !in 200..299) error("Pricing request failed")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
                preferences.edit().putString(
                    "card:" + id,
                    JSONObject().put("savedAt", System.currentTimeMillis()).put("body", body).toString()
                ).apply()
                parse(body)
            } catch (_: Exception) {
                old?.result ?: MarketPrices(emptyMap(), null)
            }
        }
    }

    private fun parse(card: JSONObject): MarketPrices {
        val source = card.optJSONObject("pricing")?.optJSONObject("tcgplayer")
            ?: return MarketPrices(emptyMap(), null)
        val prices = mutableMapOf<Finish, Double>()
        val iterator = source.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val row = source.optJSONObject(key) ?: continue
            val amount = row.optDouble("marketPrice", Double.NaN)
            if (!amount.isFinite() || amount <= 0.0) continue
            val normalized = key.lowercase().replace('_', '-')
            val finish = when {
                "1st" in normalized || "first" in normalized -> Finish.FIRST_EDITION
                "reverse" in normalized -> Finish.REVERSE
                "holo" in normalized -> Finish.HOLO
                "normal" in normalized || "regular" in normalized -> Finish.REGULAR
                else -> continue
            }
            prices.putIfAbsent(finish, amount)
        }
        return MarketPrices(prices, source.optString("updated").takeIf { it.isNotBlank() })
    }
}
