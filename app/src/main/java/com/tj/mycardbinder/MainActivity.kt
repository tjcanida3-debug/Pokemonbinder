package com.tj.mycardbinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val midnight = Color(0xFF0C1120)
private val cardSurface = Color(0xFF18233B)
private val brightGold = Color(0xFFFFCD50)
private val aqua = Color(0xFF63DCDC)
private val paleText = Color(0xFFEDF1FF)
private val mutedText = Color(0xFFADB9D3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = brightGold, secondary = aqua, background = midnight,
                surface = cardSurface, onSurface = paleText, onBackground = paleText
            )) { BinderScreen() }
        }
    }
}

@Composable
private fun BinderScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var scannerOpen by remember { mutableStateOf(false) }
    var scanMatching by remember { mutableStateOf(false) }
    var scanHint by remember { mutableStateOf("Point your camera at one card and hold steady") }
    var lastObservedTitle by remember { mutableStateOf("") }
    var stableFrames by remember { mutableIntStateOf(0) }
    var lastQueriedTitle by remember { mutableStateOf("") }
    var lastScanAt by remember { mutableLongStateOf(0L) }
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
        catch (ex: Exception) { message = "Could not load sets: " + (ex.localizedMessage ?: "Check your internet") }
        finally { setsLoading = false }
    }
    LaunchedEffect(currentSet?.id) {
        cards = emptyList()
        selectedCard = null
        currentSet?.let { set ->
            cardsLoading = true
            try { cards = catalog.cardsInSet(set.id); message = null }
            catch (ex: Exception) { message = "Could not load cards: " + (ex.localizedMessage ?: "Check your internet") }
            finally { cardsLoading = false }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanHint = "Point your camera at one card and hold steady"
            scanMatching = false
            scannerOpen = true
        } else {
            message = "Allow camera permission to use live scanning. Manual checklists still work."
        }
    }

    fun startScan() {
        message = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scanHint = "Point your camera at one card and hold steady"
            scanMatching = false
            scannerOpen = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(selectedCard?.id) {
        val card = selectedCard ?: return@LaunchedEffect
        marketPrices[card.id] = priceCatalog.get(card.id)
    }

    if (scannerOpen) {
        LiveCardScannerScreen(
            isMatching = scanMatching,
            status = scanHint,
            onClose = { scannerOpen = false; scanMatching = false },
            onError = { scanHint = it },
            onTextDetected = { recognized ->
                val title = ScanIdentity.titles(recognized).firstOrNull()
                if (title == null) {
                    scanHint = if (recognized.contains("Basic Energy", true))
                        "Basic Energy cards need a readable title or collector number. Move closer to the bottom edge."
                    else "Hold the card steady and center the printed name."
                    stableFrames = 0
                    lastObservedTitle = ""
                } else {
                    val identity = ScanIdentity.key(title)
                    if (identity == lastObservedTitle) stableFrames += 1
                    else { lastObservedTitle = identity; stableFrames = 1 }
                    val now = SystemClock.elapsedRealtime()
                    scanHint = "Reading " + title + "… hold steady"
                    if (stableFrames >= 2 && !scanMatching &&
                        (identity != lastQueriedTitle || now - lastScanAt > 10000L)) {
                        lastQueriedTitle = identity
                        lastScanAt = now
                        stableFrames = 0
                        scanMatching = true
                        scanHint = "Looking up " + title + "…"
                        scanText = recognized
                        scope.launch {
                            try {
                                val matches = if (currentSet != null && cards.isNotEmpty()) {
                                    matchScannedCard(recognized, cards)
                                } else {
                                    catalog.findScannedCandidates(recognized)
                                }
                                if (matches.isNotEmpty()) {
                                    suggestions = matches
                                    scannerOpen = false
                                } else {
                                    scanHint = "No exact match for " + title +
                                        ". Try better light or select its set manually."
                                }
                            } catch (ex: Exception) {
                                scanHint = "Search unavailable. Check internet and try again."
                            } finally {
                                scanMatching = false
                            }
                        }
                    }
                }
            }
        )
    } else Scaffold(containerColor = midnight) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(9.dp))
            if (currentSet == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MY CARD BINDER", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black, color = paleText)
                        Text("Your collection. Your treasure.", color = mutedText,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Surface(color = Color(0xFF283958), shape = RoundedCornerShape(50.dp)) {
                        Text("TCG", Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            color = brightGold, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(13.dp))
                // Cropped and straightened from your own photo: Water Energy, Silvally, Nitro Fire Energy.
                Box(Modifier.fillMaxWidth().height(153.dp).clip(RoundedCornerShape(22.dp))) {
                    Image(painterResource(R.drawable.binder_showcase),
                        contentDescription = "Your photographed Pokémon cards",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { startScan() },
                    modifier = Modifier.fillMaxWidth().height(57.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = brightGold, contentColor = Color(0xFF16171E))) {
                    Text("📷  LIVE SCAN A CARD",
                        fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                }
                Text("Automatic scanning • no shutter button needed",
                    color = mutedText, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 5.dp))
                Spacer(Modifier.height(10.dp))
                val unique = owned.filterValues { it > 0 }.keys.map { it.substringBefore('|') }.toSet().size
                val estimate = collectionEstimate(owned, marketPrices)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile("OWNED CARDS", unique.toString(), "unique", Modifier.weight(1f))
                    SummaryTile("KNOWN VALUE", formatMarket(estimate.first),
                        if (estimate.second > 0) "${estimate.second} priced copies" else "prices load as you browse",
                        Modifier.weight(1f))
                }
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(value = setsSearch, onValueChange = { setsSearch = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("🔎  Search all Pokémon sets") },
                    shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EXPLORE SETS", style = MaterialTheme.typography.labelLarge,
                        color = brightGold, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("${sets.size} sets", color = mutedText,
                        style = MaterialTheme.typography.labelMedium)
                }
                if (setsLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = aqua)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val filtered = sets.filter { set ->
                    set.name.contains(setsSearch, ignoreCase = true) ||
                    set.id.contains(setsSearch, ignoreCase = true)
                }
                LazyColumn(contentPadding = PaddingValues(top = 7.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(filtered, key = { it.id }) { set ->
                        val ownedCount = ownedInSet(set.id, owned)
                        ElevatedCard(onClick = {
                            currentSet = set
                            cardsSearch = ""
                            missingOnly = false
                        }, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = cardSurface),
                            shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(45.dp).clip(RoundedCornerShape(13.dp))
                                    .background(Color(0xFF344367)), contentAlignment = Alignment.Center) {
                                    Text(set.name.firstOrNull()?.uppercase() ?: "?",
                                        fontWeight = FontWeight.Black, color = brightGold,
                                        style = MaterialTheme.typography.titleLarge)
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(set.name, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$ownedCount / ${set.count} cards", color = mutedText,
                                        style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { if (set.count > 0) (ownedCount.toFloat() / set.count).coerceIn(0f, 1f) else 0f },
                                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(10.dp)),
                                        color = aqua, trackColor = Color(0xFF35435E))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("›", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, color = brightGold)
                            }
                        }
                    }
                }
            } else {
                val set = currentSet!!
                TextButton(onClick = { currentSet = null; message = null }) {
                    Text("‹  ALL SETS", fontWeight = FontWeight.Bold)
                }
                Text(set.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val count = if (cards.isNotEmpty()) cards.count { totalOwned(it.id, owned) > 0 }
                    else ownedInSet(set.id, owned)
                val total = if (cards.isNotEmpty()) cards.size else set.count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$count / $total collected", color = mutedText,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val est = collectionEstimate(owned, marketPrices, set.id)
                    if (est.second > 0) Text("Known: " + formatMarket(est.first), color = brightGold,
                        style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(progress = { if (total > 0) (count.toFloat() / total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(10.dp)),
                    color = aqua, trackColor = Color(0xFF35435E))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { startScan() },
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = brightGold, contentColor = Color(0xFF16171E))) {
                    Text("📷  LIVE SCAN A CARD",
                        fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = cardsSearch, onValueChange = { cardsSearch = it },
                    label = { Text("Search cards or number") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = missingOnly, onClick = { missingOnly = !missingOnly },
                        label = { Text("Missing only") })
                    Spacer(Modifier.width(9.dp))
                    Text("Tap cards for variants + duplicates", color = mutedText,
                        style = MaterialTheme.typography.labelSmall)
                }
                if (cardsLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = aqua)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val filtered = cards.filter { card ->
                    (card.name.contains(cardsSearch, true) || card.localId.contains(cardsSearch, true)) &&
                        (!missingOnly || totalOwned(card.id, owned) == 0)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { card ->
                        LaunchedEffect(card.id) { marketPrices[card.id] = priceCatalog.get(card.id) }
                        val num = totalOwned(card.id, owned)
                        val preferred = Finish.entries.firstOrNull { (owned[inventoryKey(card.id, it)] ?: 0) > 0 } ?: Finish.REGULAR
                        val quote = marketPrices[card.id]
                        val quoteValue = quote?.forFinish(preferred)
                            ?: if (num == 0) quote?.byFinish?.values?.minOrNull() else null
                        ElevatedCard(onClick = { selectedCard = card }, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = cardSurface),
                            shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                CardArtwork(card.imageBase, Modifier.width(57.dp).height(81.dp))
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold)
                                    Text("#${card.localId}  •  $num owned", color = mutedText,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(70.dp)) {
                                    Text(quoteValue?.let { formatMarket(it) } ?: if (quote != null) "—" else "…",
                                        color = brightGold, style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold)
                                    Text("market USD", color = mutedText,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Checkbox(checked = num > 0, onCheckedChange = { toggle(card.id, it) })
                            }
                        }
                    }
                }
            }
        }
    }

    selectedCard?.let { card ->
        AlertDialog(onDismissRequest = { selectedCard = null }, title = { Text(card.name) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CardArtwork(card.imageBase, Modifier.fillMaxWidth().height(215.dp))
                    Text("Card #${card.localId}")
                    Text("Estimated TCGplayer market prices (USD)",
                        style = MaterialTheme.typography.bodySmall)
                    Text(marketPrices[card.id]?.updatedAt?.let { "Updated: " + it.take(10) } ?: "Prices may be unavailable",
                        color = mutedText, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Finish.entries.forEach { finish ->
                        val n = owned[inventoryKey(card.id, finish)] ?: 0
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(finish.title, modifier = Modifier.weight(1f))
                            Text(marketPrices[card.id]?.forFinish(finish)?.let { formatMarket(it) } ?: "—",
                                style = MaterialTheme.typography.labelSmall, color = brightGold)
                            TextButton(onClick = { setCount(card.id, finish, n - 1) }, enabled = n > 0) { Text("−") }
                            Text("$n")
                            TextButton(onClick = { setCount(card.id, finish, n + 1) }) { Text("+") }
                        }
                    }
                    Text("Market values are estimates; card condition changes value.",
                        style = MaterialTheme.typography.bodySmall, color = mutedText)
                }
            }, confirmButton = { TextButton(onClick = { selectedCard = null }) { Text("Done") } })
    }

    suggestions?.let { matches ->
        AlertDialog(
            onDismissRequest = { suggestions = null },
            title = { Text("Check your card", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    Text("The camera read: " +
                        (ScanIdentity.titles(scanText).firstOrNull() ?: "Card title uncertain"),
                        color = brightGold, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Compare the picture and number. Tap only the exact edition you own.",
                        color = mutedText, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    if (matches.isEmpty()) {
                        Text("No reliable match. Scan again or add it from the set checklist.")
                    } else {
                        matches.take(10).forEach { card ->
                            val setId = card.id.substringBeforeLast('-')
                            val setName = sets.find { it.id == setId }?.name ?: setId
                            ElevatedCard(
                                onClick = {
                                    val n = owned[inventoryKey(card.id, Finish.REGULAR)] ?: 0
                                    setCount(card.id, Finish.REGULAR, n + 1)
                                    suggestions = null
                                    selectedCard = card
                                },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF26344B))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    CardArtwork(card.imageBase,
                                        Modifier.width(57.dp).height(79.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(card.name, color = paleText,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold)
                                        Text(setName, color = mutedText,
                                            style = MaterialTheme.typography.bodySmall)
                                        Text("#" + card.localId, color = brightGold,
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                    Text("+", color = brightGold, fontWeight = FontWeight.Black,
                                        style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                    Text("Nothing is saved until you choose a card.",
                        color = mutedText, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    suggestions = null
                    lastScanAt = 0L
                    lastQueriedTitle = ""
                    lastObservedTitle = ""
                    stableFrames = 0
                    startScan()
                }) { Text("Scan again") }
            },
            dismissButton = {
                TextButton(onClick = { suggestions = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SummaryTile(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = cardSurface)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(label, color = mutedText, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black, color = brightGold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, color = mutedText, style = MaterialTheme.typography.labelSmall,
                maxLines = 2, minLines = 2)
        }
    }
}

private fun formatMarket(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale.US).format(value)

private fun collectionEstimate(
    inventory: Map<String, Int>, prices: Map<String, MarketPrices>, setId: String? = null
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