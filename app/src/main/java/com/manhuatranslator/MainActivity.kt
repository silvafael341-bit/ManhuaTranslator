@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.manhuatranslator

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrBlock(val text: String, val box: Rect, val confidence: Float, val script: Script)
enum class Script { CHINESE, JAPANESE, KOREAN, LATIN, UNKNOWN }
data class Region(val box: Rect, val text: String, val confidence: Float, val script: Script)
data class OcrCandidate(val script: Script, val angle: Int, val blocks: List<OcrBlock>, val score: Double)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ManhuaTranslatorApp() }
    }
}

private class OcrEngine : AutoCloseable {
    private data class Client(val script: Script, val recognizer: TextRecognizer)
    private val clients = listOf(
        Client(Script.CHINESE, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        Client(Script.JAPANESE, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        Client(Script.KOREAN, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        Client(Script.LATIN, TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val candidates = mutableListOf<OcrCandidate>()
        for (angle in intArrayOf(0, 90, 270)) {
            val rotated = if (angle == 0) bitmap else rotate(bitmap, angle)
            try {
                for (client in clients) {
                    val result = client.recognizer.process(InputImage.fromBitmap(rotated, 0)).await()
                    val blocks = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val text = line.text.trim()
                            val bounds = line.boundingBox ?: return@mapNotNull null
                            if (text.isEmpty()) return@mapNotNull null
                            val confidence = line.elements.mapNotNull { it.confidence }
                                .takeIf { it.isNotEmpty() }?.average()?.toFloat()?.coerceIn(0f, 1f) ?: 0.5f
                            OcrBlock(text, mapBox(bounds, angle, bitmap.width, bitmap.height), confidence, client.script)
                        }
                    }
                    if (blocks.isNotEmpty()) {
                        candidates += OcrCandidate(client.script, angle, blocks, score(client.script, angle, blocks))
                    }
                }
            } finally {
                if (rotated !== bitmap) rotated.recycle()
            }
        }
        val best = candidates.maxByOrNull { it.score } ?: return emptyList()
        return dedup(best.blocks)
    }

    private fun score(script: Script, angle: Int, blocks: List<OcrBlock>): Double {
        val chars = blocks.sumOf { it.text.count { c -> !c.isWhitespace() } }.toDouble()
        val cjk = blocks.sumOf { block -> block.text.count(::isCjk) }.toDouble()
        val conf = blocks.map { it.confidence.toDouble() }.average()
        val cjkRatio = if (chars == 0.0) 0.0 else cjk / chars
        val scriptBonus = if (script != Script.LATIN && cjk > 0) 35.0 else 0.0
        val verticalBonus = if (angle != 0 && script != Script.LATIN && cjk >= 2) 15.0 else 0.0
        val latinPenalty = if (script == Script.LATIN && cjk == 0.0) 12.0 else 0.0
        return chars + cjk * 10.0 + conf * 50.0 + min(blocks.size, 20) * 2.0 +
            cjkRatio * 30.0 + scriptBonus + verticalBonus - latinPenalty
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x3040..0x30FF || c.code in 0x3400..0x9FFF || c.code in 0xAC00..0xD7AF

    private fun dedup(blocks: List<OcrBlock>): List<OcrBlock> =
        blocks.sortedByDescending { it.confidence * 100f + it.text.length }
            .fold(mutableListOf<OcrBlock>()) { out, block ->
                if (out.none { existing -> overlap(existing.box, block.box) > 0.72f && normalize(existing.text) == normalize(block.text) }) {
                    out += block
                }
                out
            }
            .sortedWith(compareBy<OcrBlock> { it.box.top }.thenBy { it.box.left })

    private fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), "")

    private fun overlap(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top).toLong()
        val area = min(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (area <= 0L) 0f else intersection.toFloat() / area.toFloat()
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun mapBox(box: Rect, angle: Int, width: Int, height: Int): Rect {
        if (angle == 0) return Rect(box)
        val points = listOf(
            box.left to box.top, box.right to box.top,
            box.left to box.bottom, box.right to box.bottom
        ).map { (x, y) -> if (angle == 90) y to height - x else width - y to x }
        return Rect(
            points.minOf { it.first }.coerceIn(0, width),
            points.minOf { it.second }.coerceIn(0, height),
            points.maxOf { it.first }.coerceIn(0, width),
            points.maxOf { it.second }.coerceIn(0, height)
        )
    }

    override fun close() = clients.forEach { it.recognizer.close() }
}

private fun group(blocks: List<OcrBlock>): List<Region> {
    val regions = mutableListOf<Region>()
    for (block in blocks.sortedWith(compareBy<OcrBlock> { it.box.top }.thenBy { it.box.left })) {
        val index = regions.indexOfFirst { region ->
            val gapX = max(0, max(region.box.left, block.box.left) - min(region.box.right, block.box.right)).toFloat()
            val gapY = max(0, max(region.box.top, block.box.top) - min(region.box.bottom, block.box.bottom)).toFloat()
            val alignedX = abs(region.box.centerX() - block.box.centerX()) < max(region.box.width(), block.box.width()).toFloat() * 0.65f
            val alignedY = abs(region.box.centerY() - block.box.centerY()) < max(region.box.height(), block.box.height()).toFloat() * 0.65f
            gapX < max(24f, max(region.box.width(), block.box.width()).toFloat() * 0.35f) &&
                gapY < max(32f, max(region.box.height(), block.box.height()).toFloat() * 0.8f) &&
                (alignedX || alignedY)
        }
        if (index < 0) {
            regions += Region(Rect(block.box), block.text, block.confidence, block.script)
        } else {
            val old = regions[index]
            val union = Rect(old.box).apply { union(block.box) }
            regions[index] = Region(union, "${old.text} ${block.text}".trim(), min(old.confidence, block.confidence), old.script)
        }
    }
    return regions
}

private class TranslatorPool : AutoCloseable {
    private val translators = mutableMapOf<String, Translator>()

    suspend fun translate(text: String, source: String): String {
        val key = "$source>pt"
        val translator = translators.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build())
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return translator.translate(text).await().trim()
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}

private fun render(original: Bitmap, translated: List<Pair<Region, String>>): Bitmap {
    val output = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    for ((region, text) in translated) {
        if (text.isBlank()) continue
        val sourceBox = RectF(region.box)
        val expansionX = max(18f, sourceBox.width() * 0.55f)
        val expansionY = max(28f, sourceBox.height() * 1.35f)
        val balloon = RectF(
            max(0f, sourceBox.left - expansionX),
            max(0f, sourceBox.top - expansionY),
            min(output.width.toFloat(), sourceBox.right + expansionX),
            min(output.height.toFloat(), sourceBox.bottom + expansionY)
        )
        val background = sampleBackground(original, region.box)
        eraseOriginalText(canvas, original, region.box, background)
        fillBackground(canvas, original, balloon, background)
        drawTranslation(canvas, balloon, text, background)
    }
    return output
}

private fun eraseOriginalText(canvas: Canvas, original: Bitmap, box: Rect, background: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val threshold = 85
    val left = box.left.coerceIn(0, original.width)
    val top = box.top.coerceIn(0, original.height)
    val right = box.right.coerceIn(0, original.width)
    val bottom = box.bottom.coerceIn(0, original.height)
    for (y in top until bottom) {
        for (x in left until right) {
            val color = original.getPixel(x, y)
            if (colorDistance(color, background) > threshold) {
                paint.color = background
                canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
            }
        }
    }
}

private fun fillBackground(canvas: Canvas, original: Bitmap, box: RectF, background: Int) {
    val path = Path().apply { addOval(box, Path.Direction.CW) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    canvas.save()
    canvas.clipPath(path)
    val left = box.left.toInt().coerceAtLeast(0)
    val top = box.top.toInt().coerceAtLeast(0)
    val right = box.right.toInt().coerceAtMost(original.width)
    val bottom = box.bottom.toInt().coerceAtMost(original.height)
    for (y in top until bottom step 2) {
        for (x in left until right step 2) {
            if (colorDistance(original.getPixel(x, y), background) < 48) {
                canvas.drawRect(x.toFloat(), y.toFloat(), x + 2f, y + 2f, paint)
            }
        }
    }
    canvas.restore()
}

private fun drawTranslation(canvas: Canvas, box: RectF, text: String, background: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (luma(background) > 150.0) Color.BLACK else Color.WHITE
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    val maxWidth = box.width() * 0.76f
    val maxHeight = box.height() * 0.62f
    var size = (box.height() * 0.30f).coerceIn(18f, 72f)
    var lines: List<String>
    while (true) {
        paint.textSize = size
        lines = wrapText(text, paint, maxWidth)
        val lineHeight = paint.fontMetrics.bottom - paint.fontMetrics.top
        if (lines.size * lineHeight <= maxHeight || size <= 12f) break
        size -= 1f
    }
    val lineHeight = paint.fontMetrics.bottom - paint.fontMetrics.top
    var y = box.centerY() - (lines.size * lineHeight) / 2f - paint.fontMetrics.top
    for (line in lines) {
        canvas.drawText(line, box.centerX() - paint.measureText(line) / 2f, y, paint)
        y += lineHeight
    }
}

private fun sampleBackground(bitmap: Bitmap, box: Rect): Int {
    val insetX = max(1, box.width() / 4)
    val insetY = max(1, box.height() / 4)
    val points = listOf(
        box.left + insetX to box.top + insetY,
        box.right - insetX - 1 to box.top + insetY,
        box.centerX() to box.centerY()
    )
    val valid = points.filter { it.first in 0 until bitmap.width && it.second in 0 until bitmap.height }
    if (valid.isEmpty()) return Color.WHITE
    return valid.map { bitmap.getPixel(it.first, it.second) }
        .groupBy { quantize(it) }.maxByOrNull { it.value.size }?.value?.firstOrNull() ?: Color.WHITE
}

private fun colorDistance(a: Int, b: Int): Int {
    val dr = Color.red(a) - Color.red(b)
    val dg = Color.green(a) - Color.green(b)
    val db = Color.blue(a) - Color.blue(b)
    return abs(dr) + abs(dg) + abs(db)
}

private fun quantize(color: Int) = (Color.red(color) / 16 shl 8) or (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
private fun luma(color: Int) = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val tokens = if (text.any { it.isWhitespace() }) text.trim().split(Regex("\\s+")) else text.map { it.toString() }
    val lines = mutableListOf<String>()
    var current = ""
    for (token in tokens) {
        val candidate = if (current.isEmpty()) token else "$current $token"
        if (paint.measureText(candidate) <= maxWidth) current = candidate
        else {
            if (current.isNotEmpty()) lines += current
            if (paint.measureText(token) <= maxWidth) current = token
            else {
                var chunk = ""
                for (char in token) {
                    if (paint.measureText(chunk + char) <= maxWidth) chunk += char
                    else { if (chunk.isNotEmpty()) lines += chunk; chunk = char.toString() }
                }
                current = chunk
            }
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

@Composable
private fun ManhuaTranslatorApp() {
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) { pages = it; open = true }
    }
    if (open && pages.isNotEmpty()) Reader(pages) { open = false } else Home { picker.launch(arrayOf("image/*")) }
}

@Composable
private fun Home(onOpen: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Manhua Translator") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Leitor de Manhua", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("OCR + tradução automática para português", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpen) { Text("Abrir páginas") }
        }
    }
}

@Composable
private fun Reader(pages: List<Uri>, onBack: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun translatePage() {
        if (busy) return
        scope.launch {
            busy = true
            status = "Abrindo imagem..."
            try {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(pages[page]).use { BitmapFactory.decodeStream(it) }
                        ?: error("Imagem inválida")
                }
                status = "OCR em andamento..."
                val blocks = OcrEngine().use { it.recognize(source) }
                if (blocks.isEmpty()) { status = "Nenhum texto detectado."; return@launch }
                val regions = group(blocks)
                val script = blocks.groupingBy { it.script }.eachCount().maxByOrNull { it.value }?.key
                val language = when (script) {
                    Script.CHINESE -> "zh"
                    Script.JAPANESE -> "ja"
                    Script.KOREAN -> "ko"
                    Script.LATIN -> "en"
                    else -> null
                }
                if (language == null) { status = "Idioma não identificado."; return@launch }
                status = "Traduzindo ${regions.size} região(ões)..."
                val pool = TranslatorPool()
                val translated = try {
                    regions.mapNotNull { region -> runCatching { region to pool.translate(region.text, language) }.getOrNull() }
                } finally { pool.close() }
                if (translated.isEmpty()) { status = "Falha ao traduzir."; return@launch }
                status = "Renderizando..."
                bitmap = withContext(Dispatchers.Default) { render(source, translated) }
                status = "${translated.size} região(ões) traduzida(s)."
            } catch (error: Exception) {
                status = "Falha: ${error.message ?: "erro desconhecido"}"
            } finally { busy = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Página ${page + 1} / ${pages.size}") }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }, actions = { Button(onClick = ::translatePage, enabled = !busy) { Text(if (busy) "Traduzindo..." else "Traduzir") } })
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { if (page > 0) { page--; bitmap = null; status = null } }, enabled = page > 0 && !busy) { Text("Anterior") }
                OutlinedButton(onClick = { if (page < pages.lastIndex) { page++; bitmap = null; status = null } }, enabled = page < pages.lastIndex && !busy) { Text("Próxima") }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (bitmap != null) ZoomBitmap(bitmap!!) else ZoomUri(pages[page])
            status?.let { message -> Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), tonalElevation = 4.dp) { Text(message, Modifier.padding(16.dp), fontWeight = FontWeight.Medium) } }
        }
    }
}

@Composable
private fun ZoomUri(uri: Uri) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(uri) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan } }, Alignment.Center) {
        coil3.compose.AsyncImage(model = uri, contentDescription = "Página", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@Composable
private fun ZoomBitmap(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(bitmap) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan } }, Alignment.Center) {
        Image(bitmap.asImageBitmap(), contentDescription = "Página traduzida", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}
