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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ManhuaTranslatorApp() }
    }
}

data class OcrBlock(val text: String, val box: Rect, val confidence: Float, val script: Script)
enum class Script { CHINESE, JAPANESE, KOREAN, LATIN, UNKNOWN }
data class Region(val box: Rect, val text: String, val confidence: Float, val script: Script)
data class Candidate(val script: Script, val blocks: List<OcrBlock>, val score: Double)

private class OcrEngine : AutoCloseable {
    private data class R(val script: Script, val client: TextRecognizer)
    private val clients = listOf(
        R(Script.CHINESE, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        R(Script.JAPANESE, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        R(Script.KOREAN, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        R(Script.LATIN, TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val candidates = mutableListOf<Candidate>()
        for (angle in listOf(0, 90, 270)) {
            val rotated = if (angle == 0) bitmap else rotate(bitmap, angle)
            try {
                for (r in clients) {
                    val result = r.client.process(InputImage.fromBitmap(rotated, 0)).await()
                    val blocks = result.textBlocks.flatMap { b ->
                        b.lines.mapNotNull { line ->
                            val text = line.text.trim()
                            val bb = line.boundingBox ?: return@mapNotNull null
                            if (text.isEmpty()) return@mapNotNull null
                            val box = mapBox(bb, angle, bitmap.width, bitmap.height)
                            val confidence = line.elements.mapNotNull { element -> element.confidence }.average()
                            val conf = confidence.toFloat().coerceIn(0f, 1f)
                            OcrBlock(text, box, conf, r.script)
                        }
                    }
                    if (blocks.isNotEmpty()) candidates += Candidate(r.script, blocks, score(r.script, angle, blocks))
                }
            } finally {
                if (rotated !== bitmap) rotated.recycle()
            }
        }
        return candidates.maxByOrNull { it.score }?.blocks?.let { dedup(it) } ?: emptyList()
    }

    private fun score(script: Script, angle: Int, blocks: List<OcrBlock>): Double {
        val chars = blocks.sumOf { block -> block.text.count { c -> !c.isWhitespace() } }
        val conf = blocks.map { it.confidence.toDouble() }.average()
        val cjk = blocks.sumOf { block ->
            block.text.count { c ->
                c.code in 0x3040..0x30ff || c.code in 0x3400..0x9fff || c.code in 0xac00..0xd7af
            }
        }
        return chars.toDouble() + conf * 90.0 + min(blocks.size, 30).toDouble() * 3.0 +
            if (script != Script.LATIN) cjk.toDouble() * 1.8 else 0.0 +
            if (angle != 0 && script != Script.LATIN && cjk >= 2) 10.0 else 0.0
    }

    private fun dedup(blocks: List<OcrBlock>): List<OcrBlock> =
        blocks.sortedByDescending { it.confidence * 100f + it.text.length.toFloat() }
            .fold(mutableListOf<OcrBlock>()) { out, b ->
                if (out.none { existing -> overlap(existing.box, b.box) > 0.72f && norm(existing.text) == norm(b.text) }) {
                    out += b
                }
                out
            }
            .sortedWith(compareBy<OcrBlock> { it.box.top }.thenBy { it.box.left })

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), "")

    private fun overlap(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val d = min(a.bottom, b.bottom)
        if (r <= l || d <= t) return 0f
        val intersection = (r - l).toLong() * (d - t).toLong()
        val minimumArea = min(a.width().toLong() * a.height().toLong(), b.width().toLong() * b.height().toLong())
        return if (minimumArea == 0L) 0f else intersection.toFloat() / minimumArea.toFloat()
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun mapBox(b: Rect, angle: Int, w: Int, h: Int): Rect {
        if (angle == 0) return Rect(b)
        val points = listOf(
            b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom
        ).map { (x, y) -> if (angle == 90) y to h - x else w - y to x }
        return Rect(
            points.minOf { it.first }.coerceIn(0, w),
            points.minOf { it.second }.coerceIn(0, h),
            points.maxOf { it.first }.coerceIn(0, w),
            points.maxOf { it.second }.coerceIn(0, h)
        )
    }

    override fun close() { clients.forEach { it.client.close() } }
}

private fun group(blocks: List<OcrBlock>): List<Region> {
    val result = mutableListOf<Region>()
    for (b in blocks.sortedWith(compareBy<OcrBlock> { it.box.top }.thenBy { it.box.left })) {
        val match = result.indexOfFirst { r ->
            val gapX = max(0, max(r.box.left, b.box.left) - min(r.box.right, b.box.right))
            val gapY = max(0, max(r.box.top, b.box.top) - min(r.box.bottom, b.box.bottom))
            val alignedX = abs(r.box.centerX() - b.box.centerX()) < max(r.box.width(), b.box.width()) * 0.65f
            val alignedY = abs(r.box.centerY() - b.box.centerY()) < max(r.box.height(), b.box.height()) * 0.65f
            gapX < max(24, (max(r.box.width(), b.box.width()) * 0.35f).toInt()) &&
                gapY < max(32, (max(r.box.height(), b.box.height()) * 0.8f).toInt()) &&
                (alignedX || alignedY)
        }
        if (match < 0) {
            result += Region(Rect(b.box), b.text, b.confidence, b.script)
        } else {
            val old = result[match]
            val union = Rect(old.box)
            union.union(b.box)
            result[match] = Region(union, old.text + " " + b.text, min(old.confidence, b.confidence), old.script)
        }
    }
    return result
}

private class TranslatorPool : AutoCloseable {
    private val cache = mutableMapOf<String, Translator>()

    suspend fun translate(text: String, source: String): String {
        val key = "$source>pt"
        val translator = cache.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build()
            )
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
        return translator.translate(text).await()
    }

    override fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}

private fun render(original: Bitmap, regions: List<Pair<Region, String>>): Bitmap {
    val out = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    for ((r, translation) in regions) {
        if (translation.isBlank()) continue
        val box = RectF(r.box)
        val pad = max(8f, min(box.width(), box.height()) * 0.12f)
        box.inset(-pad, -pad)
        box.left = max(0f, box.left)
        box.top = max(0f, box.top)
        box.right = min(out.width.toFloat(), box.right)
        box.bottom = min(out.height.toFloat(), box.bottom)
        val sample = sampleBackground(original, r.box)
        val path = Path().apply { addOval(box, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        paint.color = sample
        canvas.drawOval(box, paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (luma(sample) > 145) Color.BLACK else Color.WHITE
            typeface = Typeface.create("sans", Typeface.NORMAL)
        }
        var size = (r.box.height() * 0.32f).coerceIn(14f, 64f)
        val maxW = box.width() * 0.82f
        val maxH = box.height() * 0.82f
        var lines: List<String>
        while (true) {
            textPaint.textSize = size
            lines = wrap(translation, textPaint, maxW)
            val lineHeight = textPaint.fontMetrics.run { bottom - top }
            if (lines.size * lineHeight <= maxH || size <= 12f) break
            size -= 1f
        }
        val lineHeight = textPaint.fontMetrics.run { bottom - top }
        var y = box.centerY() - (lines.size * lineHeight) / 2f - textPaint.fontMetrics.top
        for (line in lines) {
            canvas.drawText(line, box.centerX() - textPaint.measureText(line) / 2f, y, textPaint)
            y += lineHeight
        }
        canvas.restore()
    }
    return out
}

private fun sampleBackground(b: Bitmap, r: Rect): Int {
    val points = listOf(
        r.left + r.width() / 4 to r.top + r.height() / 4,
        r.right - r.width() / 4 - 1 to r.top + r.height() / 4,
        r.centerX() to r.centerY()
    )
    val colors = points.filter { it.first in 0 until b.width && it.second in 0 until b.height }
        .map { b.getPixel(it.first, it.second) }
    return colors.groupBy { quant(it) }.maxByOrNull { it.value.size }?.value?.firstOrNull() ?: Color.WHITE
}

private fun quant(c: Int) = (Color.red(c) / 16 shl 8) or (Color.green(c) / 16 shl 4) or (Color.blue(c) / 16)
private fun luma(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
    val tokens = if (text.any { it.isWhitespace() }) text.split(Regex("\\s+")) else text.map { it.toString() }
    val lines = mutableListOf<String>()
    var current = ""
    for (token in tokens) {
        val candidate = if (current.isEmpty()) token else "$current $token"
        if (p.measureText(candidate) <= maxW) {
            current = candidate
        } else {
            if (current.isNotEmpty()) lines += current
            if (p.measureText(token) <= maxW) {
                current = token
            } else {
                var chunk = ""
                for (ch in token) {
                    if (p.measureText(chunk + ch) <= maxW) {
                        chunk += ch
                    } else {
                        if (chunk.isNotEmpty()) lines += chunk
                        chunk = ch.toString()
                    }
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
    if (open && pages.isNotEmpty()) Reader(pages) { open = false }
    else Home { picker.launch(arrayOf("image/*")) }
}

@Composable
private fun Home(onOpen: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Manhua Translator") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
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

    fun run() {
        if (busy) return
        scope.launch {
            busy = true
            status = "Abrindo imagem..."
            try {
                val src = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(pages[page]).use {
                        BitmapFactory.decodeStream(it)
                    } ?: error("Imagem inválida")
                }
                status = "OCR em andamento..."
                val blocks = OcrEngine().use { it.recognize(src) }
                if (blocks.isEmpty()) { status = "Nenhum texto detectado."; return@launch }
                val regions = group(blocks)
                val script = blocks.groupingBy { it.script }.eachCount().maxByOrNull { it.value }?.key
                val lang = when (script) {
                    Script.CHINESE -> "zh"
                    Script.JAPANESE -> "ja"
                    Script.KOREAN -> "ko"
                    Script.LATIN -> "en"
                    else -> null
                }
                if (lang == null) { status = "Idioma não identificado."; return@launch }
                status = "Traduzindo ${regions.size} região(ões)..."
                val tr = TranslatorPool()
                val translated = try {
                    regions.mapNotNull { r ->
                        try { r to tr.translate(r.text, lang) } catch (_: Exception) { null }
                    }
                } finally { tr.close() }
                if (translated.isEmpty()) { status = "Falha ao traduzir."; return@launch }
                status = "Renderizando..."
                bitmap = withContext(Dispatchers.Default) { render(src, translated) }
                status = "${translated.size} região(ões) traduzida(s)."
            } catch (e: Exception) {
                status = "Falha: ${e.message ?: "erro desconhecido"}"
            } finally { busy = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Página ${page + 1} / ${pages.size}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } },
                actions = { Button(onClick = ::run, enabled = !busy) { Text(if (busy) "Traduzindo..." else "Traduzir") } }
            )
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
            status?.let {
                Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), tonalElevation = 4.dp) {
                    Text(it, Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ZoomUri(uri: Uri) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var off by remember(uri) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(uri) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); off += pan } },
        Alignment.Center
    ) {
        coil3.compose.AsyncImage(
            model = uri,
            contentDescription = "Página",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y)
        )
    }
}

@Composable
private fun ZoomBitmap(b: Bitmap) {
    var scale by remember(b) { mutableFloatStateOf(1f) }
    var off by remember(b) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(b) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); off += pan } },
        Alignment.Center
    ) {
        Image(
            b.asImageBitmap(),
            contentDescription = "Página traduzida",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y)
        )
    }
}
