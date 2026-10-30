package com.tj.mycardbinder

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFCA41),
                    secondary = Color(0xFFE87158),
                    background = Color(0xFF121620),
                    surface = Color(0xFF1D2432)
                )
            ) { BinderScreen() }
        }
    }
}

@Composable
private fun BinderScreen() {
    val context = LocalContext.current
    val catalog = remember { Catalog(context.applicationContext) }
    val store = remember { CollectionStore(context.applicationContext) }
    val priceCatalog = remember { PriceCatalog(context.applicationContext) }
    val marketPrices = remember { mutableStateMapOf<String, MarketPrices>().apply { putAll(priceCatalog.cachedAll()) } }
    val owned = remember { mutableStateMapOf<String, Int>().apply { putAll(store.load()) } }
    var sets by remember { mutableStateOf<List<CardSet>>(emptyList()) }
    var currentSet by remember { mutableStateOf<CardSet?>(null) }
    var cards by remember { mutableStateOf<List<TradingCard>>(emptyList()) }
    var setsLoading by remember { mutableStateOf(true) }
    var cardsLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var setsSearch by remember { mutableStateOf("") }
    var cardsSearch by remember { mutableStateOf("") }
    var missingOnly by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<TradingCard?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<TradingCard>?>(null) }
    var scanText by remember { mutableStateOf("") }

    fun setCount(cardId: String, finish: Finish, value: Int) {
        val key = inventoryKey(cardId, finish)
        if (value <= 0) owned.remove(key) else owned[key] = value
        store.save(owned)
    }
    fun toggle(cardId: String, nowOwned: Boolean) {
        if (nowOwned) {
            if (totalOwned(cardId, owned) == 0) setCount(cardId, Finish.REGULAR, 1)
        } else {
            Finish.entries.forEach { owned.remove(inventoryKey(cardId, it)) }
            store.save(owned)
        }
    }

    LaunchedEffect(Unit) {
        try { sets = catalog.sets(); message = null }
        catch (ex: Exception) { message = "Could not load sets: " + (ex.localizedMessage ?: "Check internet") }
        finally { setsLoading = false }
    }
    LaunchedEffect(currentSet?.id) {
        cards = emptyList()
        selectedCard = null
        val set = currentSet
        if (set != null) {
            cardsLoading = true
            try { cards = catalog.cardsInSet(set.id); message = null }
            catch (ex: Exception) { message = "Could not load cards: " + (ex.localizedMessage ?: "Check internet") }
            finally { cardsLoading = false }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = photoUri
        if (!captured || uri == null) {
            scanning = false
        } else {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        scanText = result.text
                        suggestions = matchScannedCard(result.text, cards)
                        scanning = false
                    }
                    .addOnFailureListener { ex ->
                        message = "Could not read card: " + (ex.localizedMessage ?: "Unknown error")
                        scanning = false
                    }
                    .addOnCompleteListener { recognizer.close() }
            } catch (ex: Exception) {
                message = "Could not open photo: " + (ex.localizedMessage ?: "Unknown error")
                scanning = false
            }
        }
    }

    fun startScan() {
        try {
            val folder = File(context.cacheDir, "scans").apply { mkdirs() }
            val file = File(folder, "card_" + System.currentTimeMillis() + ".jpg")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            photoUri = uri
            scanning = true
            message = null
            camera.launch(uri)
        } catch (ex: Exception) {
            scanning = false
            message = "Camera unavailable: " + (ex.localizedMessage ?: "Unknown error")
        }
    }

    LaunchedEffect(selectedCard?.id) {
        val card = selectedCard ?: return@LaunchedEffect
        marketPrices[card.id] = priceCatalog.get(card.id)
    }

    Scaffold { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            if (currentSet == null) {
                Text("MY CARD BINDER", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Track all English Pokémon TCG sets", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                val unique = owned.filterValues { it > 0 }.keys.map { it.substringBefore('|') }.toSet().size
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Unique cards owned")
                        Text(unique.toString(), style = MaterialTheme.typography.headlineLarge)
                        Text(sets.size.toString() + " sets available", style = MaterialTheme.typography.bodySmall)
                        val estimate = collectionEstimate(owned, marketPrices)
                        if (estimate.second > 0) {
                            Text("Known collection value: " + formatMarket(estimate.first), fontWeight = FontWeight.SemiBold)
                            Text(estimate.second.toString() + " priced copies; unpriced cards excluded", style = MaterialTheme.typography.bodySmall)
                        } else Text("Open a set to load estimated card values", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = setsSearch, onValueChange = { setsSearch = it },
                    label = { Text("Search sets") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (setsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val filtered = sets.filter {
                    it.name.contains(setsSearch, ignoreCase = true) ||
                        it.id.contains(setsSearch, ignoreCase = true)
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { set ->
                        val count = ownedInSet(set.id, owned)
                        ElevatedCard(
                            onClick = { currentSet = set; cardsSearch = ""; missingOnly = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(set.name, fontWeight = FontWeight.SemiBold)
                                        Text(set.count.toString() + " cards", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(count.toString() + "/" + set.count, style = MaterialTheme.typography.labelLarge)
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (set.count > 0) count.toFloat() / set.count else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            } else {
                val set = currentSet!!
                TextButton(onClick = { currentSet = null; message = null }) { Text("← All sets") }
                Text(set.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                val count = if (cards.isNotEmpty()) cards.count { totalOwned(it.id, owned) > 0 }
                    else ownedInSet(set.id, owned)
                val total = if (cards.isNotEmpty()) cards.size else set.count
                Text(count.toString() + " / " + total + " cards owned")
                val setEstimate = collectionEstimate(owned, marketPrices, set.id)
                if (setEstimate.second > 0) Text("Known set value: " + formatMarket(setEstimate.first), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) count.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { startScan() },
                    enabled = cards.isNotEmpty() && !scanning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (scanning) "Reading photo..." else "📷 Scan a card from this set") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cardsSearch, onValueChange = { cardsSearch = it },
                    label = { Text("Search cards or card number") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = missingOnly, onClick = { missingOnly = !missingOnly },
                        label = { Text("Missing only") })
                    Spacer(Modifier.width(8.dp))
                    Text("Tap card for duplicates", style = MaterialTheme.typography.bodySmall)
                }
                if (cardsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val filtered = cards.filter {
                    (it.name.contains(cardsSearch, true) || it.localId.contains(cardsSearch, true)) &&
                        (!missingOnly || totalOwned(it.id, owned) == 0)
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(filtered, key = { it.id }) { card ->
                        LaunchedEffect(card.id) { marketPrices[card.id] = priceCatalog.get(card.id) }
                        val num = totalOwned(card.id, owned)
                        val preferred = Finish.entries.firstOrNull { (owned[inventoryKey(card.id, it)] ?: 0) > 0 } ?: Finish.REGULAR
                        val quote = marketPrices[card.id]
                        val quoteValue = quote?.forFinish(preferred)
                            ?: if (num == 0) quote?.byFinish?.values?.minOrNull() else null
                        ElevatedCard(
                            onClick = { selectedCard = card },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CardArtwork(card.imageBase, Modifier.width(55.dp).height(77.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold)
                                    Text("#" + card.localId + " · " + num + " owned",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(77.dp)) {
                                    Text(quoteValue?.let { formatMarket(it) } ?: if (quote != null) "N/A" else "…", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text("market · USD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(
                                    checked = num > 0,
                                    onCheckedChange = { toggle(card.id, it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedCard?.let { card ->
        AlertDialog(
            onDismissRequest = { selectedCard = null },
            title = { Text(card.name) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CardArtwork(card.imageBase, Modifier.fillMaxWidth().height(215.dp))
                    Text("Card #" + card.localId)
                    Text("TCGplayer market estimates in USD", style = MaterialTheme.typography.bodySmall)
                    Text(marketPrices[card.id]?.updatedAt?.let { "Updated: " + it.take(10) } ?: "Prices may be unavailable", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Finish.entries.forEach { finish ->
                        val count = owned[inventoryKey(card.id, finish)] ?: 0
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(finish.title, modifier = Modifier.weight(1f))
                            Text(marketPrices[card.id]?.forFinish(finish)?.let { formatMarket(it) } ?: "—", style = MaterialTheme.typography.labelSmall)
                            TextButton(
                                onClick = { setCount(card.id, finish, count - 1) },
                                enabled = count > 0
                            ) { Text("−") }
                            Text(count.toString())
                            TextButton(onClick = { setCount(card.id, finish, count + 1) }) { Text("+") }
                        }
                    }
                    Text("Prices are estimates for ungraded cards; condition can change the value.", style = MaterialTheme.typography.bodySmall)
                    Text("Choose finishes that actually exist for your card.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { selectedCard = null }) { Text("Done") } }
        )
    }

    suggestions?.let { matches ->
        AlertDialog(
            onDismissRequest = { suggestions = null },
            title = { Text("Confirm scanned card") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (matches.isEmpty()) {
                        Text("No match found in this set. Try a clearer photo or add it manually.")
                    } else {
                        Text("Select the right card. Nothing is saved until you choose.")
                        Spacer(Modifier.height(12.dp))
                        matches.forEach { card ->
                            OutlinedButton(
                                onClick = {
                                    val count = owned[inventoryKey(card.id, Finish.REGULAR)] ?: 0
                                    setCount(card.id, Finish.REGULAR, count + 1)
                                    suggestions = null
                                    selectedCard = card
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(card.name + " · #" + card.localId) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Text recognized:", fontWeight = FontWeight.SemiBold)
                    Text(scanText.take(350).ifBlank { "No text found" },
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { suggestions = null }) { Text("Cancel") } }
        )
    }
}


private fun formatMarket(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale.US).format(value)

private fun collectionEstimate(
    inventory: Map<String, Int>,
    prices: Map<String, MarketPrices>,
    setId: String? = null
): Pair<Double, Int> {
    var total = 0.0
    var pricedCopies = 0
    inventory.forEach { (key, quantity) ->
        if (quantity <= 0) return@forEach
        val id = key.substringBefore('|')
        if (setId != null && !id.startsWith(setId + "-")) return@forEach
        val finish = Finish.entries.firstOrNull { it.name == key.substringAfter('|', "") } ?: return@forEach
        val quote = prices[id]?.forFinish(finish) ?: return@forEach
        total += quantity * quote
        pricedCopies += quantity
    }
    return total to pricedCopies
}

private fun ownedInSet(setId: String, inventory: Map<String, Int>): Int =
    inventory.filterValues { it > 0 }.keys.map { it.substringBefore('|') }
        .filter { it.startsWith(setId + "-") }.toSet().size

@Composable
private fun CardArtwork(imageBase: String?, modifier: Modifier = Modifier) {
    if (imageBase != null) {
        AsyncImage(model = imageBase + "/low.webp", contentDescription = "Card image",
            modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No image", style = MaterialTheme.typography.labelSmall)
        }
    }
}
