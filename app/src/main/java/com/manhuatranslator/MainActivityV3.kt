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

data class V3Block(val text: String, val box: Rect, val confidence: Float, val script: V3Script)
enum class V3Script { CHINESE, JAPANESE, KOREAN, LATIN }
data class V3Region(val box: Rect, val text: String, val script: V3Script)

class MainActivityV3 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { V3App() }
    }
}

private class V3Ocr : AutoCloseable {
    private data class Client(val script: V3Script, val recognizer: TextRecognizer)
    private val clients = listOf(
        Client(V3Script.CHINESE, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        Client(V3Script.JAPANESE, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        Client(V3Script.KOREAN, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        Client(V3Script.LATIN, TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun recognize(bitmap: Bitmap): List<V3Block> {
        val all = mutableListOf<V3Block>()
        for (angle in intArrayOf(0, 90, 270)) {
            val rotated = if (angle == 0) bitmap else Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(angle.toFloat()) }, true
            )
            try {
                for (client in clients) {
                    val result = client.recognizer.process(InputImage.fromBitmap(rotated, 0)).await()
                    result.textBlocks.flatMap { it.lines }.forEach { line ->
                        val text = line.text.trim()
                        val box = line.boundingBox ?: return@forEach
                        if (text.isEmpty()) return@forEach
                        val confidence = line.elements.mapNotNull { it.confidence }
                            .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.5f
                        all += V3Block(text, mapBox(box, angle, bitmap.width, bitmap.height), confidence, client.script)
                    }
                }
            } finally {
                if (rotated !== bitmap) rotated.recycle()
            }
        }
        return selectBest(all)
    }

    private fun selectBest(all: List<V3Block>): List<V3Block> {
        val ordered = all.sortedByDescending { blockScore(it) }
        val kept = mutableListOf<V3Block>()
        for (candidate in ordered) {
            val duplicate = kept.any {
                overlap(it.box, candidate.box) > 0.55f &&
                    (normalize(it.text) == normalize(candidate.text) || overlap(it.box, candidate.box) > 0.82f)
            }
            if (!duplicate) kept += candidate
        }
        return kept.sortedWith(compareBy<V3Block> { it.box.top }.thenBy { it.box.left })
    }

    private fun blockScore(b: V3Block): Double {
        val chars = b.text.count { !it.isWhitespace() }
        val cjk = b.text.count(::isCjk)
        val latin = b.text.count { it.isLetter() && it.code < 256 }
        return b.confidence * 100.0 + cjk * 16.0 + chars * 1.5 - latin * if (cjk == 0) 0.15 else 0.0
    }

    private fun isCjk(c: Char) = c.code in 0x3040..0x30FF || c.code in 0x3400..0x9FFF || c.code in 0xAC00..0xD7AF
    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), "")

    private fun overlap(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bot = min(a.bottom, b.bottom)
        if (r <= l || bot <= t) return 0f
        val inter = (r - l).toLong() * (bot - t).toLong()
        val area = min(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (area <= 0L) 0f else inter.toFloat() / area
    }

    private fun mapBox(box: Rect, angle: Int, width: Int, height: Int): Rect {
        if (angle == 0) return Rect(box)
        val points = listOf(
            box.left to box.top, box.right to box.top,
            box.left to box.bottom, box.right to box.bottom
        ).map { (x, y) ->
            if (angle == 90) y to height - x else width - y to x
        }
        return Rect(
            points.minOf { it.first }.coerceIn(0, width),
            points.minOf { it.second }.coerceIn(0, height),
            points.maxOf { it.first }.coerceIn(0, width),
            points.maxOf { it.second }.coerceIn(0, height)
        )
    }

    override fun close() = clients.forEach { it.recognizer.close() }
}

private fun v3Group(blocks: List<V3Block>): List<V3Region> {
    val result = mutableListOf<V3Region>()
    for (b in blocks) {
        val index = result.indexOfFirst { r ->
            val verticalGap = max(0, max(r.box.top, b.box.top) - min(r.box.bottom, b.box.bottom))
            val horizontalGap = max(0, max(r.box.left, b.box.left) - min(r.box.right, b.box.right))
            val closeX = abs(r.box.centerX() - b.box.centerX()) < max(r.box.width(), b.box.width()) * 0.8f
            val closeY = abs(r.box.centerY() - b.box.centerY()) < max(r.box.height(), b.box.height()) * 1.0f
            verticalGap <= max(34, max(r.box.height(), b.box.height())) &&
                horizontalGap <= max(42, max(r.box.width(), b.box.width()) / 2) &&
                (closeX || closeY)
        }
        if (index < 0) {
            result += V3Region(Rect(b.box), b.text, b.script)
        } else {
            val old = result[index]
            val script = dominantScript(old.script, b.script, old.text + " " + b.text)
            result[index] = V3Region(
                Rect(old.box).apply { union(b.box) },
                "${old.text} ${b.text}".trim(),
                script
            )
        }
    }
    return result
}

private fun dominantScript(a: V3Script, b: V3Script, text: String): V3Script {
    val hangul = text.count { it.code in 0xAC00..0xD7AF }
    val kana = text.count { it.code in 0x3040..0x30FF }
    val han = text.count { it.code in 0x3400..0x9FFF }
    return when {
        hangul > 0 -> V3Script.KOREAN
        kana > 0 -> V3Script.JAPANESE
        han > 0 -> V3Script.CHINESE
        a == b -> a
        else -> b
    }
}

private class V3TranslatorPool : AutoCloseable {
    private val translators = mutableMapOf<String, Translator>()

    suspend fun translate(text: String, script: V3Script): String {
        val source = when (script) {
            V3Script.CHINESE -> "zh"
            V3Script.JAPANESE -> "ja"
            V3Script.KOREAN -> "ko"
            V3Script.LATIN -> "en"
        }
        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage("pt")
                    .build()
            )
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return translator.translate(text).await().trim()
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}

private data class V3Area(val path: Path, val bounds: RectF)

private fun v3Render(original: Bitmap, translated: List<Pair<V3Region, String>>): Bitmap {
    val output = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    for ((region, text) in translated) {
        if (text.isBlank()) continue
        val background = v3Background(original, region.box)
        val area = v3SafeArea(original, region.box, background)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }

        // Primeiro limpa somente a região onde o OCR encontrou o texto original.
        // Isso evita deixar caracteres antigos por baixo da tradução.
        val erase = RectF(region.box).apply {
            inset(-max(10f, region.box.width() * 0.08f), -max(10f, region.box.height() * 0.15f))
        }
        canvas.save()
        canvas.clipPath(area.path)
        canvas.drawRect(erase, fill)
        canvas.restore()

        // Depois limpa o restante da área segura e desenha a tradução dentro dela.
        canvas.save()
        canvas.clipPath(area.path)
        canvas.drawRect(area.bounds, fill)
        canvas.restore()
        v3DrawText(canvas, area, text, background)
    }
    return output
}

private fun v3Background(bitmap: Bitmap, box: Rect): Int {
    val points = mutableListOf<Pair<Int, Int>>()
    val px = max(12, box.width() / 3)
    val py = max(12, box.height() / 2)
    points += box.centerX() to box.centerY()
    points += box.left + px to box.top + py
    points += box.right - px to box.top + py
    points += box.left + px to box.bottom - py
    points += box.right - px to box.bottom - py
    points += box.centerX() to (box.top - py).coerceAtLeast(0)
    points += box.centerX() to (box.bottom + py).coerceAtMost(bitmap.height - 1)
    val samples = points.filter { it.first in 0 until bitmap.width && it.second in 0 until bitmap.height }
        .map { bitmap.getPixel(it.first, it.second) }
    val light = samples.filter { luma(it) >= 180.0 }
    val source = if (light.isNotEmpty()) light else samples
    return source.groupBy { quantize(it) }.maxByOrNull { it.value.size }?.value?.firstOrNull() ?: Color.WHITE
}

private fun v3SafeArea(bitmap: Bitmap, source: Rect, background: Int): V3Area {
    val cx = source.centerX().toFloat()
    val cy = source.centerY().toFloat()
    val width = min(bitmap.width.toFloat(), max(150f, source.width() * 1.65f))
    val height = min(bitmap.height.toFloat(), max(150f, source.height() * 2.7f))
    val bounds = RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    bounds.left = bounds.left.coerceAtLeast(8f)
    bounds.top = bounds.top.coerceAtLeast(8f)
    bounds.right = bounds.right.coerceAtMost(bitmap.width - 8f)
    bounds.bottom = bounds.bottom.coerceAtMost(bitmap.height - 8f)

    val path = Path()
    val inset = min(10f, min(bounds.width(), bounds.height()) * 0.04f)
    val safe = RectF(bounds).apply { inset(inset, inset) }
    // O oval mantém a tradução dentro do interior mesmo em balões irregulares.
    path.addOval(safe, Path.Direction.CW)
    return V3Area(path, safe)
}

private fun v3DrawText(canvas: Canvas, area: V3Area, text: String, background: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (luma(background) > 150.0) Color.BLACK else Color.WHITE
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    val maxWidth = area.bounds.width() * 0.72f
    val maxHeight = area.bounds.height() * 0.70f
    var size = (area.bounds.height() * 0.19f).coerceIn(18f, 58f)
    var lines = emptyList<String>()
    while (size >= 11f) {
        paint.textSize = size
        lines = v3Wrap(text, paint, maxWidth)
        val height = lines.size * (paint.fontMetrics.bottom - paint.fontMetrics.top)
        if (height <= maxHeight) break
        size -= 1f
    }
    val lineHeight = paint.fontMetrics.bottom - paint.fontMetrics.top
    val centerX = area.bounds.centerX()
    val centerY = area.bounds.centerY()
    var y = centerY - lines.size * lineHeight / 2f - paint.fontMetrics.top

    canvas.save()
    canvas.clipPath(area.path)
    for (line in lines) {
        canvas.drawText(line, centerX - paint.measureText(line) / 2f, y, paint)
        y += lineHeight
    }
    canvas.restore()
}

private fun v3Wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
    val tokens = if (text.any(Char::isWhitespace)) text.trim().split(Regex("\\s+")) else text.map(Char::toString)
    val lines = mutableListOf<String>()
    var current = ""
    for (token in tokens) {
        val candidate = if (current.isEmpty()) token else "$current $token"
        if (paint.measureText(candidate) <= maxWidth) {
            current = candidate
        } else {
            if (current.isNotEmpty()) lines += current
            if (paint.measureText(token) <= maxWidth) {
                current = token
            } else {
                var chunk = ""
                for (c in token) {
                    if (paint.measureText(chunk + c) <= maxWidth) chunk += c
                    else {
                        if (chunk.isNotEmpty()) lines += chunk
                        chunk = c.toString()
                    }
                }
                current = chunk
            }
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

private fun quantize(c: Int) = (Color.red(c) / 16 shl 8) or (Color.green(c) / 16 shl 4) or (Color.blue(c) / 16)
private fun luma(c: Int) = .299 * Color.red(c) + .587 * Color.green(c) + .114 * Color.blue(c)

@Composable private fun V3App() {
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) { pages = it; open = true }
    }
    if (open && pages.isNotEmpty()) V3Reader(pages) { open = false }
    else V3Home { picker.launch(arrayOf("image/*")) }
}

@Composable private fun V3Home(onOpen: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Manhua Translator") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Leitor de Manhua", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("OCR + tradução automática para português")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpen) { Text("Abrir páginas") }
        }
    }
}

@Composable private fun V3Reader(pages: List<Uri>, onBack: () -> Unit) {
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
                    context.contentResolver.openInputStream(pages[page]).use {
                        BitmapFactory.decodeStream(it)
                    } ?: error("Imagem inválida")
                }
                status = "OCR em andamento..."
                val blocks = V3Ocr().use { it.recognize(source) }
                if (blocks.isEmpty()) {
                    status = "Nenhum texto detectado."
                    return@launch
                }
                val regions = v3Group(blocks)
                status = "Traduzindo ${regions.size} região(ões)..."
                val pool = V3TranslatorPool()
                val translated = try {
                    regions.mapNotNull { region ->
                        runCatching { region to pool.translate(region.text, region.script) }.getOrNull()
                    }
                } finally {
                    pool.close()
                }
                if (translated.isEmpty()) {
                    status = "Falha ao traduzir."
                    return@launch
                }
                status = "Renderizando..."
                bitmap = withContext(Dispatchers.Default) { v3Render(source, translated) }
                status = "${translated.size} região(ões) traduzida(s)."
            } catch (e: Exception) {
                status = "Falha: ${e.message ?: "erro desconhecido"}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Página ${page + 1} / ${pages.size}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } },
                actions = { Button(onClick = ::translatePage, enabled = !busy) { Text(if (busy) "Traduzindo..." else "Traduzir") } }
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
            if (bitmap != null) V3ZoomBitmap(bitmap!!) else V3ZoomUri(pages[page])
            status?.let {
                Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), tonalElevation = 4.dp) {
                    Text(it, Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable private fun V3ZoomUri(uri: Uri) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(uri) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan } },
        Alignment.Center
    ) {
        coil3.compose.AsyncImage(
            model = uri,
            contentDescription = "Página",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}

@Composable private fun V3ZoomBitmap(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(bitmap) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset += pan } },
        Alignment.Center
    ) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Página traduzida",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}
