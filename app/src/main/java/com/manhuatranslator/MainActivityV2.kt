@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.manhuatranslator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
import com.google.mlkit.translate.Translation
import com.google.mlkit.translate.Translator
import com.google.mlkit.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class V2OcrBlock(val text: String, val box: Rect, val confidence: Float, val script: V2Script)
enum class V2Script { CHINESE, JAPANESE, KOREAN, LATIN }
data class V2Region(val box: Rect, val text: String, val script: V2Script)

class MainActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { V2App() }
    }
}

private class V2OcrEngine : AutoCloseable {
    private data class Client(val script: V2Script, val recognizer: TextRecognizer)
    private val clients = listOf(
        Client(V2Script.CHINESE, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        Client(V2Script.JAPANESE, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        Client(V2Script.KOREAN, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        Client(V2Script.LATIN, TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun recognize(bitmap: Bitmap): List<V2OcrBlock> {
        val candidates = mutableListOf<List<V2OcrBlock>>()
        for (angle in intArrayOf(0, 90, 270)) {
            val rotated = if (angle == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(angle.toFloat()) }, true)
            try {
                for (client in clients) {
                    val result = client.recognizer.process(InputImage.fromBitmap(rotated, 0)).await()
                    val blocks = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val text = line.text.trim()
                            val box = line.boundingBox ?: return@mapNotNull null
                            if (text.isEmpty()) return@mapNotNull null
                            val confidence = line.elements.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.5f
                            V2OcrBlock(text, mapBox(box, angle, bitmap.width, bitmap.height), confidence.coerceIn(0f, 1f), client.script)
                        }
                    }
                    if (blocks.isNotEmpty()) candidates += blocks
                }
            } finally { if (rotated !== bitmap) rotated.recycle() }
        }
        return candidates.maxByOrNull { score(it) }?.let(::dedup) ?: emptyList()
    }

    private fun score(blocks: List<V2OcrBlock>): Double {
        val chars = blocks.sumOf { it.text.count { c -> !c.isWhitespace() } }.toDouble()
        val cjk = blocks.sumOf { it.text.count(::isCjk) }.toDouble()
        val confidence = blocks.map { it.confidence }.average()
        val ratio = if (chars == 0.0) 0.0 else cjk / chars
        return chars + cjk * 11.0 + confidence * 50.0 + min(blocks.size, 20) * 2.0 + ratio * 35.0
    }

    private fun isCjk(c: Char) = c.code in 0x3040..0x30FF || c.code in 0x3400..0x9FFF || c.code in 0xAC00..0xD7AF

    private fun dedup(blocks: List<V2OcrBlock>) = blocks.sortedByDescending { it.confidence * 100f + it.text.length }
        .fold(mutableListOf<V2OcrBlock>()) { out, b ->
            if (out.none { normalize(it.text) == normalize(b.text) && overlap(it.box, b.box) > 0.7f }) out += b
            out
        }.sortedWith(compareBy<V2OcrBlock> { it.box.top }.thenBy { it.box.left })

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), "")
    private fun overlap(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top); val r = min(a.right, b.right); val bot = min(a.bottom, b.bottom)
        if (r <= l || bot <= t) return 0f
        val inter = (r - l).toLong() * (bot - t).toLong()
        val area = min(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (area <= 0L) 0f else inter.toFloat() / area
    }

    private fun mapBox(box: Rect, angle: Int, width: Int, height: Int): Rect {
        if (angle == 0) return Rect(box)
        val points = listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)
            .map { (x, y) -> if (angle == 90) y to height - x else width - y to x }
        return Rect(points.minOf { it.first }.coerceIn(0, width), points.minOf { it.second }.coerceIn(0, height), points.maxOf { it.first }.coerceIn(0, width), points.maxOf { it.second }.coerceIn(0, height))
    }

    override fun close() = clients.forEach { it.recognizer.close() }
}

private fun v2Group(blocks: List<V2OcrBlock>): List<V2Region> {
    val result = mutableListOf<V2Region>()
    for (b in blocks) {
        val i = result.indexOfFirst { r ->
            val gapX = max(0, max(r.box.left, b.box.left) - min(r.box.right, b.box.right)).toFloat()
            val gapY = max(0, max(r.box.top, b.box.top) - min(r.box.bottom, b.box.bottom)).toFloat()
            val ax = abs(r.box.centerX() - b.box.centerX()) < max(r.box.width(), b.box.width()) * .7f
            val ay = abs(r.box.centerY() - b.box.centerY()) < max(r.box.height(), b.box.height()) * .8f
            gapX < max(30f, max(r.box.width(), b.box.width()) * .4f) && gapY < max(38f, max(r.box.height(), b.box.height()) * .9f) && (ax || ay)
        }
        if (i < 0) result += V2Region(Rect(b.box), b.text, b.script)
        else {
            val old = result[i]
            result[i] = V2Region(Rect(old.box).apply { union(b.box) }, "${old.text} ${b.text}".trim(), old.script)
        }
    }
    return result
}

private class V2TranslatorPool : AutoCloseable {
    private val translators = mutableMapOf<String, Translator>()
    suspend fun translate(text: String, source: String): String {
        val translator = translators.getOrPut(source) { Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build()) }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return translator.translate(text).await().trim()
    }
    override fun close() { translators.values.forEach { it.close() }; translators.clear() }
}

private data class V2Interior(val path: Path, val bounds: RectF)

private fun v2Render(original: Bitmap, translated: List<Pair<V2Region, String>>): Bitmap {
    val output = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    for ((region, text) in translated) {
        if (text.isBlank()) continue
        val background = v2SampleBackground(original, region.box)
        val interior = v2DetectInterior(original, region.box, background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.save()
        canvas.clipPath(interior.path)
        canvas.drawRect(interior.bounds, paint)
        canvas.restore()
        v2DrawText(canvas, interior, text, background)
    }
    return output
}

private fun v2SampleBackground(bitmap: Bitmap, box: Rect): Int {
    val pad = max(8, min(box.width(), box.height()) / 2)
    val points = listOf(
        box.left - pad to box.centerY(), box.right + pad to box.centerY(),
        box.centerX() to box.top - pad, box.centerX() to box.bottom + pad,
        box.left - pad to box.top - pad, box.right + pad to box.top - pad,
        box.left - pad to box.bottom + pad, box.right + pad to box.bottom + pad
    ).filter { it.first in 0 until bitmap.width && it.second in 0 until bitmap.height }
    val samples = points.map { bitmap.getPixel(it.first, it.second) }
    if (samples.isEmpty()) return Color.WHITE
    val light = samples.filter { luma(it) > 170.0 }
    return (if (light.isNotEmpty()) light else samples).groupBy(::v2Quantize).maxByOrNull { it.value.size }?.value?.firstOrNull() ?: Color.WHITE
}

private fun v2DetectInterior(bitmap: Bitmap, source: Rect, background: Int): V2Interior {
    val padX = max(28, source.width() * 2)
    val padY = max(36, source.height() * 2)
    val left = (source.left - padX).coerceAtLeast(0)
    val right = (source.right + padX).coerceAtMost(bitmap.width - 1)
    val top = (source.top - padY).coerceAtLeast(0)
    val bottom = (source.bottom + padY).coerceAtMost(bitmap.height - 1)
    val cx = source.centerX().coerceIn(left, right)
    val rows = mutableListOf<Triple<Int, Int, Int>>()
    for (y in top..bottom step 2) {
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var x = left
        while (x <= right) {
            val good = v2NearBackground(bitmap, x, y, background)
            if (good && start < 0) start = x
            if ((!good || x == right) && start >= 0) {
                val end = if (good && x == right) x else x - 2
                if (end - start >= 12) runs += start to end
                start = -1
            }
            x += 2
        }
        val merged = v2MergeRuns(runs, 16)
        val chosen = merged.firstOrNull { cx in it.first..it.second }
            ?: merged.minByOrNull { abs(((it.first + it.second) / 2) - cx) }
        if (chosen != null && chosen.second - chosen.first >= max(20, source.width() / 3)) rows += Triple(y, chosen.first, chosen.second)
    }
    if (rows.size < 6) {
        val fallback = RectF(source).apply { inset(-source.width() * .75f, -source.height() * 1.5f) }
        return V2Interior(Path().apply { addOval(fallback, Path.Direction.CW) }, fallback)
    }
    val smooth = v2SmoothRows(rows)
    val path = Path()
    path.moveTo(smooth.first().second.toFloat(), smooth.first().first.toFloat())
    for ((y, l, _) in smooth.drop(1)) path.lineTo(l.toFloat(), y.toFloat())
    for ((y, _, r) in smooth.asReversed()) path.lineTo(r.toFloat(), y.toFloat())
    path.close()
    val bounds = RectF(smooth.minOf { it.second }.toFloat(), smooth.minOf { it.first }.toFloat(), smooth.maxOf { it.third }.toFloat(), smooth.maxOf { it.first }.toFloat())
    return V2Interior(path, bounds)
}

private fun v2NearBackground(bitmap: Bitmap, x: Int, y: Int, bg: Int): Boolean {
    var best = Int.MAX_VALUE
    for (dy in -1..1) for (dx in -1..1) {
        val px = (x + dx).coerceIn(0, bitmap.width - 1)
        val py = (y + dy).coerceIn(0, bitmap.height - 1)
        best = min(best, v2ColorDistance(bitmap.getPixel(px, py), bg))
    }
    return best <= 72
}

private fun v2MergeRuns(runs: List<Pair<Int, Int>>, gap: Int): List<Pair<Int, Int>> {
    if (runs.isEmpty()) return emptyList()
    val out = mutableListOf<Pair<Int, Int>>(); var cur = runs.first()
    for (r in runs.drop(1)) {
        if (r.first - cur.second <= gap) cur = cur.first to r.second else { out += cur; cur = r }
    }
    out += cur; return out
}

private fun v2SmoothRows(rows: List<Triple<Int, Int, Int>>): List<Triple<Int, Int, Int>> {
    if (rows.size < 5) return rows
    return rows.mapIndexed { i, row ->
        val from = max(0, i - 2); val to = min(rows.lastIndex, i + 2)
        val window = rows.subList(from, to + 1)
        Triple(row.first, window.map { it.second }.sorted()[window.size / 2], window.map { it.third }.sorted()[window.size / 2])
    }
}

private fun v2DrawText(canvas: Canvas, interior: V2Interior, text: String, background: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (luma(background) > 150.0) Color.BLACK else Color.WHITE
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    val maxWidth = interior.bounds.width() * .78f
    val maxHeight = interior.bounds.height() * .72f
    var size = (interior.bounds.height() * .22f).coerceIn(20f, 64f)
    var lines = emptyList<String>()
    while (size >= 12f) {
        paint.textSize = size
        lines = v2Wrap(text, paint, maxWidth)
        if (lines.size * (paint.fontMetrics.bottom - paint.fontMetrics.top) <= maxHeight) break
        size -= 1f
    }
    val lineHeight = paint.fontMetrics.bottom - paint.fontMetrics.top
    val centerX = interior.bounds.centerX()
    val centerY = interior.bounds.centerY()
    var y = centerY - lines.size * lineHeight / 2f - paint.fontMetrics.top
    canvas.save(); canvas.clipPath(interior.path)
    for (line in lines) {
        canvas.drawText(line, centerX - paint.measureText(line) / 2f, y, paint)
        y += lineHeight
    }
    canvas.restore()
}

private fun v2Wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
    val tokens = if (text.any(Char::isWhitespace)) text.trim().split(Regex("\\s+")) else text.map(Char::toString)
    val lines = mutableListOf<String>(); var current = ""
    for (token in tokens) {
        val candidate = if (current.isEmpty()) token else "$current $token"
        if (paint.measureText(candidate) <= maxWidth) current = candidate
        else {
            if (current.isNotEmpty()) lines += current
            if (paint.measureText(token) <= maxWidth) current = token
            else {
                var chunk = ""
                for (c in token) { if (paint.measureText(chunk + c) <= maxWidth) chunk += c else { if (chunk.isNotEmpty()) lines += chunk; chunk = c.toString() } }
                current = chunk
            }
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

private fun v2ColorDistance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
private fun v2Quantize(c: Int) = (Color.red(c) / 16 shl 8) or (Color.green(c) / 16 shl 4) or (Color.blue(c) / 16)
private fun luma(c: Int) = .299 * Color.red(c) + .587 * Color.green(c) + .114 * Color.blue(c)

@Composable private fun V2App() {
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) { pages = it; open = true } }
    if (open && pages.isNotEmpty()) V2Reader(pages) { open = false } else V2Home { picker.launch(arrayOf("image/*")) }
}

@Composable private fun V2Home(onOpen: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Manhua Translator") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Leitor de Manhua", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp)); Text("OCR + tradução automática para português")
            Spacer(Modifier.height(24.dp)); Button(onClick = onOpen) { Text("Abrir páginas") }
        }
    }
}

@Composable private fun V2Reader(pages: List<Uri>, onBack: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    fun translatePage() {
        if (busy) return
        scope.launch {
            busy = true; status = "Abrindo imagem..."
            try {
                val source = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(pages[page]).use { BitmapFactory.decodeStream(it) } ?: error("Imagem inválida") }
                status = "OCR em andamento..."
                val blocks = V2OcrEngine().use { it.recognize(source) }
                if (blocks.isEmpty()) { status = "Nenhum texto detectado."; return@launch }
                val regions = v2Group(blocks)
                val script = blocks.groupingBy { it.script }.eachCount().maxByOrNull { it.value }?.key
                val language = when (script) { V2Script.CHINESE -> "zh"; V2Script.JAPANESE -> "ja"; V2Script.KOREAN -> "ko"; V2Script.LATIN -> "en"; null -> null }
                if (language == null) { status = "Idioma não identificado."; return@launch }
                status = "Traduzindo ${regions.size} região(ões)..."
                val pool = V2TranslatorPool()
                val translated = try { regions.mapNotNull { r -> runCatching { r to pool.translate(r.text, language) }.getOrNull() } } finally { pool.close() }
                if (translated.isEmpty()) { status = "Falha ao traduzir."; return@launch }
                status = "Renderizando..."
                bitmap = withContext(Dispatchers.Default) { v2Render(source, translated) }
                status = "${translated.size} região(ões) traduzida(s)."
            } catch (e: Exception) { status = "Falha: ${e.message ?: "erro desconhecido"}" }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Página ${page + 1} / ${pages.size}") }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }, actions = { Button(onClick = ::translatePage, enabled = !busy) { Text(if (busy) "Traduzindo..." else "Traduzir") } }) }, bottomBar = { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { OutlinedButton(onClick = { if (page > 0) { page--; bitmap = null; status = null } }, enabled = page > 0 && !busy) { Text("Anterior") }; OutlinedButton(onClick = { if (page < pages.lastIndex) { page++; bitmap = null; status = null } }, enabled = page < pages.lastIndex && !busy) { Text("Próxima") } } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { if (bitmap != null) V2ZoomBitmap(bitmap!!) else V2ZoomUri(pages[page]); status?.let { Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), tonalElevation = 4.dp) { Text(it, Modifier.padding(16.dp), fontWeight = FontWeight.Medium) } } }
    }
}

@Composable private fun V2ZoomUri(uri: Uri) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }; var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(uri) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan } }, Alignment.Center) {
        coil3.compose.AsyncImage(model = uri, contentDescription = "Página", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@Composable private fun V2ZoomBitmap(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }; var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(bitmap) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan } }, Alignment.Center) {
        Image(bitmap.asImageBitmap(), contentDescription = "Página traduzida", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}
