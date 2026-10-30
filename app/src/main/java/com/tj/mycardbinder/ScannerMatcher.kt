package com.tj.mycardbinder

fun matchScannedCard(text: String, cards: List<TradingCard>): List<TradingCard> {
    val normalized = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    val numbers = Regex("(?<!\\d)([0-9]{1,3})\\s*[/|]\\s*[0-9]{2,3}")
        .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
    return cards.mapNotNull { card ->
        val name = card.name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val number = card.localId.toIntOrNull()
        var score = 0
        if (number != null && number in numbers) score += 100
        if (name.isNotBlank() && normalized.contains(name)) score += 75
        score += name.split(' ').filter { it.length >= 4 }.count { normalized.contains(it) } * 10
        if (score > 0) card to score else null
    }.sortedByDescending { it.second }.take(8).map { it.first }
}
