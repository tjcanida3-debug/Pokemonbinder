package com.tj.mycardbinder

import java.text.Normalizer
import java.util.Locale

/**
 * Identify a PRINTED card title near the top of an OCR frame.
 * Never infer a card from rules text alone (e.g. "Item" -> "Item Finder").
 * An indistinguishable generic Basic Energy gets no automatic guesses.
 */
object ScanIdentity {
    fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private val ignoredHeaders = setOf(
        "trainer", "item", "supporter", "stadium", "tool", "pokemon tool",
        "pokemon", "basic", "stage 1", "stage 2", "stage1", "stage2",
        "basic energy", "special energy", "energy", "basic pokemon",
        "basic energy card", "special energy card"
    )
    private val rulesBeginnings = listOf(
        "search ", "shuffle ", "you may ", "once ", "during ", "if ",
        "when ", "this pokemon ", "your ", "the defending ",
        "draw ", "put ", "attach ", "discard ", "as long as ",
        "prevent ", "flip ", "choose ", "each player "
    )
    private val collectibleNumber = Regex("(?<!\\d)(\\d{1,3})\\s*[/|]\\s*(\\d{1,3})(?!\\d)")

    fun number(text: String): String? =
        collectibleNumber.findAll(text).firstOrNull()?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }

    /** Returns up to two viable title readings in priority order, never rules text or category labels. */
    fun titles(ocr: String): List<String> {
        val lines = ocr.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .take(24)
            .toList()
        if (lines.isEmpty()) return emptyList()
        val results = linkedSetOf<String>()
        // Cards print their title in the top header. Avoid the lower-half rules text.
        for (line in lines.take(10)) {
            val cleaned = line
                .replace(Regex("(?i)^(?:stage\\s*[12]|basic\\s+pokemon)\\s*[: -]*"), "")
                .replace(Regex("(?i)\\s+HP\\s*\\d+.*$"), "")
                .replace(Regex("(?i)\\s+\\d{2,3}\\s*HP.*$"), "")
                .replace(Regex("\\s+"), " ").trim()
            val normalized = key(cleaned)
            if (normalized.isEmpty() || normalized in ignoredHeaders) continue
            if (rulesBeginnings.any { normalized.startsWith(it) }) break
            if (cleaned.contains('.') || cleaned.contains(':')) continue
            if (cleaned.matches(Regex(".*\\b\\d{1,3}\\s*[/|]\\s*\\d{1,3}\\b.*"))) continue
            if (normalized.split(' ').size > 5 || normalized.length > 32 ||
                normalized.length < 3 || normalized.all { it.isDigit() }) continue
            if (normalized.contains("energy") &&
                (normalized == "basic energy" || normalized == "special energy")) continue
            // Ignore isolated type markers like "WATER", but retain multi-word Energy names.
            if (normalized in setOf("water", "fire", "grass", "psychic",
                    "darkness", "metal", "lightning", "fighting", "dragon")) continue
            results.add(cleaned)
            if (results.size >= 2) break
        }
        return results.toList()
    }

    fun match(text: String, cards: List<TradingCard>): List<TradingCard> {
        val identities = titles(text).map(::key).toSet()
        if (identities.isEmpty()) return emptyList()
        val exactTitle = cards.filter { key(it.name) in identities }
        val printedNumber = number(text)
        val sameNumber = if (printedNumber != null)
            exactTitle.filter { it.localId.trimStart('0').ifEmpty { "0" } == printedNumber }
        else emptyList()
        val ordered = if (sameNumber.isNotEmpty()) sameNumber else exactTitle
        return ordered.distinctBy { it.id }.take(10)
    }
}

fun matchScannedCard(text: String, cards: List<TradingCard>): List<TradingCard> =
    ScanIdentity.match(text, cards)
