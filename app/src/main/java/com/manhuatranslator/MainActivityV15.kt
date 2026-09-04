@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.manhuatranslator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.*

private enum class S15 { ZH, JA, KO, EN }
private data class B15(val text: String, val rect: Rect, val confidence: Float, val script: S15)
private data class Mask15(val bits: BooleanArray, val left: Int, val top: Int, val width: Int, val height: Int, val bounds: Rect)

class MainActivityV15 : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { App15() }
    }
}

private fun cjk15(s: String): Int = s.count {
    it.code in 0x3040..0x30FF || it.code in 0x3400..0x9FFF || it.code in 0xAC00..0xD7AF
}

private fun map15(r: Rect, rotation: Int, w: Int, h: Int): Rect {
    if (rotation == 0) return Rect(r)
    val pts = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)
    val mapped = pts.map { (x, y) -> if (rotation == 90) y to (h - x) else (w - y) to x }
    return Rect(
        mapped.minOf { it.first }.coerceIn(0, w),
        mapped.minOf { it.second }.coerceIn(0, h),
        mapped.maxOf { it.first }.coerceIn(0, w),
        mapped.maxOf { it.second }.coerceIn(0, h)
    )
}

private class Ocr15 : AutoCloseable {
    private data class Engine(val script: S15, val recognizer: TextRecognizer)
    private val engines = listOf(
        Engine(S15.ZH, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        Engine(S15.JA, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        Engine(S15.KO, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        Engine(S15.EN, TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun run(src: Bitmap): List<B15> {
        val all = mutableListOf<B15>()
        for (rotation in intArrayOf(0, 90, 270)) {
            val image = if (rotation == 0) src else Bitmap.createBitmap(
                src, 0, 0, src.width, src.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
            try {
                for (engine in engines) {
                    val result = engine.recognizer.process(InputImage.fromBitmap(image, 0)).await()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val text = line.text.trim()
                            val box = line.boundingBox ?: continue
                            if (text.length < 2) continue
                            val confidence = line.elements.mapNotNull { it.confidence }
                                .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0.5f
                            all += B15(text, map15(box, rotation, src.width, src.height), confidence, engine.script)
                        }
                    }
                }
            } finally {
                if (image !== src) image.recycle()
            }
        }

        val cjk = all.filter { it.script != S15.EN && cjk15(it.text) > 0 }
        val candidates = if (cjk.isNotEmpty()) cjk else all
        val selected = mutableListOf<B15>()
        for (item in candidates.sortedByDescending { it.confidence * 100f + cjk15(it.text) * 200f + min(30, it.text.length) }) {
            if (selected.none { overlap15(it.rect, item.rect) >= 0.45f }) selected += item
        }
        return selected.sortedWith(compareBy<B15> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun overlap15(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        if (r <= l || bot <= t) return 0f
        val intersection = (r - l).toLong() * (bot - t)
        val smaller = min(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (smaller <= 0) 0f else intersection.toFloat() / smaller
    }

    override fun close() = engines.forEach { it.recognizer.close() }
}

private class Translator15 : AutoCloseable {
    private val cache = mutableMapOf<String, Translator>()

    suspend fun translate(text: String, script: S15): String {
        val source = when (script) {
            S15.ZH -> "zh"
            S15.JA -> "ja"
            S15.KO -> "ko"
            S15.EN -> "en"
        }
        val translator = cache.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build()
            )
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return translator.translate(text).await().trim()
    }

    override fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}

private fun lum15(color: Int): Float =
    0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)

private fun colorDistance15(a: Int, b: Int): Int =
    abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

private fun median15(values: List<Int>): Int {
    if (values.isEmpty()) return 255
    return values.sorted()[values.size / 2]
}

private fun estimateBackground15(bitmap: Bitmap, rect: Rect): Int {
    val samples = mutableListOf<Int>()
    val l = max(0, rect.left - max(12, rect.width() / 2))
    val t = max(0, rect.top - max(12, rect.height() / 2))
    val r = min(bitmap.width - 1, rect.right + max(12, rect.width() / 2))
    val b = min(bitmap.height - 1, rect.bottom + max(12, rect.height() / 2))
    val sx = max(3, (r - l) / 12)
    val sy = max(3, (b - t) / 12)
    var y = t
    while (y <= b) {
        var x = l
        while (x <= r) {
            if (x < rect.left - 8 || x > rect.right + 8 || y < rect.top - 8 || y > rect.bottom + 8) {
                samples += bitmap.getPixel(x, y)
            }
            x += sx
        }
        y += sy
    }
    if (samples.isEmpty()) return Color.WHITE
    return Color.rgb(
        median15(samples.map(Color::red)),
        median15(samples.map(Color::green)),
        median15(samples.map(Color::blue))
    )
}

private fun boundaryMask15(src: Bitmap, rect: Rect): Mask15? {
    val cx = rect.centerX()
    val cy = rect.centerY()
    val bg = estimateBackground15(src, rect)
    val rays = 360
    val startRadius = max(12, max(rect.width(), rect.height()) / 2 + 8)
    val maxRadius = min(650, max(startRadius + 40, max(rect.width(), rect.height()) * 3))
    val radii = IntArray(rays) { -1 }

    fun isLikelyOutside(color: Int): Boolean {
        val distance = colorDistance15(color, bg)
        val brightnessDelta = abs(lum15(color) - lum15(bg))
        return distance > 105 || brightnessDelta > 58
    }

    for (i in 0 until rays) {
        val angle = 2.0 * PI * i / rays
        var radius = startRadius
        var hit = -1
        while (radius < maxRadius) {
            val x = (cx + cos(angle) * radius).roundToInt()
            val y = (cy + sin(angle) * radius).roundToInt()
            if (x < 1 || x >= src.width - 1 || y < 1 || y >= src.height - 1) break
            val here = src.getPixel(x, y)
            val before = src.getPixel(
                (cx + cos(angle) * max(1, radius - 3)).roundToInt().coerceIn(0, src.width - 1),
                (cy + sin(angle) * max(1, radius - 3)).roundToInt().coerceIn(0, src.height - 1)
            )
            val edge = colorDistance15(here, before) > 55
            if (edge || isLikelyOutside(here)) {
                var outside = 0
                for (k in 1..8) {
                    val q = radius + k
                    val xx = (cx + cos(angle) * q).roundToInt()
                    val yy = (cy + sin(angle) * q).roundToInt()
                    if (xx in 1 until src.width - 1 && yy in 1 until src.height - 1 && isLikelyOutside(src.getPixel(xx, yy))) {
                        outside++
                    }
                }
                if (outside >= 6) {
                    hit = radius - 2
                    break
                }
            }
            radius += 2
        }
        radii[i] = hit
    }

    val good = radii.count { it > 0 }
    if (good < rays * 0.50) return null
    val valid = radii.filter { it > 0 }.sorted()
    val median = valid[valid.size / 2]
    val cap = min(maxRadius - 2, max(startRadius + 20, max(rect.width(), rect.height()) * 2 + 30))

    for (i in radii.indices) if (radii[i] <= 0) radii[i] = median
    for (i in radii.indices) {
        val p = radii[(i + rays - 1) % rays]
        val n = radii[(i + 1) % rays]
        if (abs(radii[i] - p) > median * 0.55f && abs(radii[i] - n) > median * 0.55f) {
            radii[i] = (p + n) / 2
        }
        radii[i] = radii[i].coerceIn(startRadius, cap)
    }

    val left = max(0, cx - cap - 3)
    val top = max(0, cy - cap - 3)
    val right = min(src.width, cx + cap + 4)
    val bottom = min(src.height, cy + cap + 4)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null

    val bits = BooleanArray(width * height)
    var minX = width
    var maxX = 0
    var minY = height
    var maxY = 0
    var count = 0

    for (yy in 0 until height) {
        for (xx in 0 until width) {
            val dx = (left + xx) - cx
            val dy = (top + yy) - cy
            val distance = hypot(dx.toDouble(), dy.toDouble())
            var angleIndex = ((atan2(dy.toDouble(), dx.toDouble()) / (2.0 * PI)) * rays).roundToInt() % rays
            if (angleIndex < 0) angleIndex += rays
            if (distance <= radii[angleIndex] - 2) {
                bits[yy * width + xx] = true
                count++
                minX = min(minX, xx)
                maxX = max(maxX, xx)
                minY = min(minY, yy)
                maxY = max(maxY, yy)
            }
        }
    }

    val minArea = max(80, rect.width() * rect.height())
    if (count < minArea || minX >= maxX || minY >= maxY) return null
    return Mask15(bits, left, top, width, height, Rect(left + minX, top + minY, left + maxX + 1, top + maxY + 1))
}

private fun applyMask15(dst: Bitmap, mask: Mask15, color: Int) {
    val pixels = IntArray(mask.width * mask.height)
    dst.getPixels(pixels, 0, mask.width, mask.left, mask.top, mask.width, mask.height)
    for (i in pixels.indices) if (mask.bits[i]) pixels[i] = color
    dst.setPixels(pixels, 0, mask.width, mask.left, mask.top, mask.width, mask.height)
}

private fun wrap15(text: String, paint: Paint, maxWidth: Float): List<String> {
    val tokens = if (text.any(Char::isWhitespace)) text.trim().split(Regex("\\s+")) else text.map { it.toString() }
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
                var piece = ""
                for (ch in token) {
                    if (paint.measureText(piece + ch) <= maxWidth) piece += ch
                    else {
                        if (piece.isNotEmpty()) lines += piece
                        piece = ch.toString()
                    }
                }
                current = piece
            }
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

private fun drawTranslation15(canvas: Canvas, mask: Mask15, text: String, background: Int) {
    val box = RectF(mask.bounds)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (lum15(background) >= 150f) Color.BLACK else Color.WHITE
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    var size = min(box.width(), box.height()) * 0.115f
    var lines: List<String>
    while (true) {
        paint.textSize = size
        lines = wrap15(text, paint, box.width() * 0.66f)
        val totalHeight = lines.size * (paint.fontMetrics.bottom - paint.fontMetrics.top)
        if (totalHeight <= box.height() * 0.48f || size <= 12f) break
        size -= 1f
    }
    val lineHeight = paint.fontMetrics.bottom - paint.fontMetrics.top
    var y = box.centerY() - lines.size * lineHeight / 2f - paint.fontMetrics.top
    for (line in lines) {
        canvas.drawText(line, box.centerX() - paint.measureText(line) / 2f, y, paint)
        y += lineHeight
    }
}

private suspend fun translatePage15(src: Bitmap): Pair<Bitmap, Int> = withContext(Dispatchers.Default) {
    val ocr = Ocr15()
    val translator = Translator15()
    try {
        val blocks = ocr.run(src)
        val pairs = mutableListOf<Pair<B15, String>>()
        for (block in blocks) {
            val translated = translator.translate(block.text, block.script)
            if (translated.isNotBlank()) pairs += block to translated
        }
        val output = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        var translatedCount = 0
        for ((block, text) in pairs) {
            val mask = boundaryMask15(src, block.rect) ?: continue
            val background = estimateBackground15(src, block.rect)
            applyMask15(output, mask, background)
            drawTranslation15(canvas, mask, text, background)
            translatedCount++
        }
        output to translatedCount
    } finally {
        ocr.close()
        translator.close()
    }
}

@Composable
private fun App15() {
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var reader by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) {
            pages = it
            reader = true
        }
    }
    if (reader) Reader15(pages) { reader = false }
    else Home15 { picker.launch(arrayOf("image/*")) }
}

@Composable
private fun Home15(openPicker: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Manhua Translator") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Tradutor de manhua para português")
            Spacer(Modifier.height(20.dp))
            Button(onClick = openPicker) { Text("Selecionar imagens") }
        }
    }
}

@Composable
private fun Reader15(pages: List<Uri>, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(index) {
        bitmap = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(pages[index])?.use { BitmapFactory.decodeStream(it) }
        }
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        status = ""
    }

    val current = bitmap
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Página ${index + 1} / ${pages.size}") },
                navigationIcon = { TextButton(onClick = back) { Text("Voltar") } },
                actions = {
                    Button(
                        enabled = !busy && current != null,
                        onClick = {
                            val source = current ?: return@Button
                            scope.launch {
                                busy = true
                                status = "Traduzindo..."
                                val result = translatePage15(source)
                                bitmap?.recycle()
                                bitmap = result.first
                                status = "${result.second} região(ões) traduzida(s)."
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "Traduzindo..." else "Traduzir") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(index) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                )
            }
            if (status.isNotBlank()) {
                Text(status, Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(enabled = index > 0 && !busy, onClick = { index-- }) { Text("Anterior") }
                Button(enabled = index < pages.lastIndex && !busy, onClick = { index++ }) { Text("Próxima") }
            }
        }
    }
}
