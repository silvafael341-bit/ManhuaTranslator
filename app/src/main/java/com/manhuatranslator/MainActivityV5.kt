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

enum class V5Script { CHINESE, JAPANESE, KOREAN, LATIN }
data class V5Block(val text: String, val box: Rect, val confidence: Float, val script: V5Script)
data class V5Region(val box: Rect, val text: String, val script: V5Script)
data class V5Area(val path: Path, val bounds: RectF)

class MainActivityV5 : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { V5App() } }
}

private class V5Ocr : AutoCloseable {
    private data class C(val s: V5Script, val r: TextRecognizer)
    private val cs = listOf(
        C(V5Script.CHINESE, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        C(V5Script.JAPANESE, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        C(V5Script.KOREAN, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        C(V5Script.LATIN, TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun run(b: Bitmap): List<V5Block> {
        val all = mutableListOf<V5Block>()
        val enhanced = enhance(b)
        try {
            for (ang in intArrayOf(0, 90, 270)) {
                val rb = if (ang == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(ang.toFloat()) }, true)
                try { all += recognize(rb, ang, b.width, b.height, false) } finally { if (rb !== b) rb.recycle() }

                val re = if (ang == 0) enhanced else Bitmap.createBitmap(enhanced, 0, 0, enhanced.width, enhanced.height, Matrix().apply { postRotate(ang.toFloat()) }, true)
                try { all += recognize(re, ang, b.width, b.height, true) } finally { if (re !== enhanced) re.recycle() }
            }
        } finally { enhanced.recycle() }
        return choose(all)
    }

    private suspend fun recognize(b: Bitmap, ang: Int, w: Int, h: Int, enhanced: Boolean): List<V5Block> {
        val out = mutableListOf<V5Block>()
        for (c in cs) {
            if (enhanced && c.s != V5Script.LATIN) continue
            val z = c.r.process(InputImage.fromBitmap(b, 0)).await()
            z.textBlocks.flatMap { it.lines }.forEach { l ->
                val t = l.text.trim(); val q = l.boundingBox ?: return@forEach
                if (t.isEmpty()) return@forEach
                val conf = l.elements.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: .5f
                out += V5Block(t, map(q, ang, w, h), conf, c.s)
            }
        }
        return out
    }

    private fun choose(a: List<V5Block>): List<V5Block> {
        val cjk = a.filter { it.script != V5Script.LATIN && it.text.count(::isCjk) >= 1 }
        val ordered = a.sortedByDescending { score(it) }
        val out = mutableListOf<V5Block>()
        for (x in ordered) {
            if (x.script == V5Script.LATIN && x.text.count { it.isLetter() } < 2) continue
            if (x.script == V5Script.LATIN && cjk.any { overlap(it.box, x.box) > .18f && it.confidence >= x.confidence * .45f }) continue
            if (out.none { overlap(it.box, x.box) > .58f }) out += x
        }
        return out.sortedWith(compareBy<V5Block> { it.box.top }.thenBy { it.box.left })
    }

    private fun score(x: V5Block): Double {
        val n = x.text.count { !it.isWhitespace() }
        val k = x.text.count(::isCjk)
        val letters = x.text.count { it.isLetter() }
        val weird = x.text.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in ".,!?'-" }
        val quality = if (x.script == V5Script.LATIN) latinQuality(x.text) else 1.0
        return x.confidence * 115.0 + k * 30.0 + n * 1.2 + letters * 1.5 + quality * 22.0 - weird * 5.0 + if (x.script == V5Script.LATIN) 0.0 else 20.0
    }

    private fun latinQuality(t: String): Double {
        val s = t.lowercase()
        val letters = s.count { it in 'a'..'z' }
        if (letters < 2) return 0.0
        val vowels = s.count { it in "aeiou" }
        val words = s.split(Regex("\\s+")).count { it.length >= 2 }
        val vowelRatio = vowels.toDouble() / letters.coerceAtLeast(1)
        val repeated = Regex("(.)\\1\\1+").containsMatchIn(s)
        return (letters.toDouble() / s.count { !it.isWhitespace() }.coerceAtLeast(1)) + words * .08 + vowelRatio.coerceIn(.15, .55) * .15 - if (repeated) .15 else 0.0
    }

    private fun isCjk(c: Char) = c.code in 0x3040..0x30ff || c.code in 0x3400..0x9fff || c.code in 0xac00..0xd7af
    private fun overlap(a: Rect, b: Rect): Float { val l=max(a.left,b.left); val t=max(a.top,b.top); val r=min(a.right,b.right); val d=min(a.bottom,b.bottom); if(r<=l||d<=t)return 0f; val i=(r-l).toLong()*(d-t); val ar=min(a.width().toLong()*a.height(),b.width().toLong()*b.height()); return if(ar==0L)0f else i.toFloat()/ar }
    private fun map(q: Rect, a: Int, w: Int, h: Int): Rect { if(a==0)return Rect(q); val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(a==90)y to h-x else w-y to x}; return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h)) }
    private fun enhance(src: Bitmap): Bitmap {
        val out=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888); val c=Canvas(out); val cm=ColorMatrix(); cm.setSaturation(0f); val contrast=1.65f; val shift=-128f*(contrast-1f); cm.postConcat(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,shift,0f,contrast,0f,0f,shift,0f,0f,contrast,0f,shift,0f,0f,0f,1f,0f))); val p=Paint(Paint.ANTI_ALIAS_FLAG); p.colorFilter=ColorMatrixColorFilter(cm); c.drawBitmap(src,0f,0f,p); return out
    }
    override fun close(){cs.forEach{it.r.close()}}
}

private fun group(a: List<V5Block>): List<V5Region> {
    val r=mutableListOf<V5Region>()
    for(b in a){ val i=r.indexOfFirst{x-> val gx=max(0,max(x.box.left,b.box.left)-min(x.box.right,b.box.right)); val gy=max(0,max(x.box.top,b.box.top)-min(x.box.bottom,b.box.bottom)); val cx=abs(x.box.centerX()-b.box.centerX())<max(x.box.width(),b.box.width())*.8f; val cy=abs(x.box.centerY()-b.box.centerY())<max(x.box.height(),b.box.height()); gx<=45&&gy<=max(38,max(x.box.height(),b.box.height()))&&(cx||cy) }; if(i<0)r+=V5Region(Rect(b.box),b.text,b.script) else{val o=r[i];r[i]=V5Region(Rect(o.box).apply{union(b.box)},o.text+" "+b.text,dominant(o.script,b.script,o.text+" "+b.text))} }
    return r
}
private fun dominant(a:V5Script,b:V5Script,t:String)=when{t.any{it.code in 0xac00..0xd7af}->V5Script.KOREAN;t.any{it.code in 0x3040..0x30ff}->V5Script.JAPANESE;t.any{it.code in 0x3400..0x9fff}->V5Script.CHINESE;a==b->a;else->b}

private class V5Trans:AutoCloseable{
    private val m=mutableMapOf<String,Translator>()
    suspend fun go(t:String,s:V5Script):String{val src=when(s){V5Script.CHINESE->"zh";V5Script.JAPANESE->"ja";V5Script.KOREAN->"ko";V5Script.LATIN->"en"};val tr=m.getOrPut(src){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage("pt").build())};tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await();return tr.translate(t).await().trim()}
    override fun close(){m.values.forEach{it.close()};m.clear()}
}

private fun lum(c:Int)=.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c)
private fun bg(b:Bitmap,q:Rect):Int{
    val l=(q.left-q.width()).coerceAtLeast(0);val t=(q.top-q.height()).coerceAtLeast(0);val r=(q.right+q.width()).coerceAtMost(b.width-1);val d=(q.bottom+q.height()).coerceAtMost(b.height-1);val v=mutableListOf<Int>();var y=t;val sy=max(3,(d-t)/18);val sx=max(3,(r-l)/18)
    while(y<=d){var x=l;while(x<=r){val c=b.getPixel(x,y);if(lum(c)>=220)v+=c;x+=sx};y+=sy}
    if(v.size>=12){val rr=v.sumOf{Color.red(it)}/v.size;val gg=v.sumOf{Color.green(it)}/v.size;val bb=v.sumOf{Color.blue(it)}/v.size;return if(rr>235&&gg>235&&bb>235)Color.WHITE else Color.rgb(rr,gg,bb)}
    return Color.WHITE
}

private fun detectArea(b:Bitmap,q:Rect):V5Area{
    val cx=q.centerX();val cy=q.centerY();val w=max(q.width()*2.2f,220f).coerceAtMost(b.width-12f);val h=max(q.height()*3.4f,180f).coerceAtMost(b.height-12f);val l=(cx-w/2).coerceAtLeast(6f).toInt();val r=(cx+w/2).coerceAtMost(b.width-7f).toInt();val top=(cy-h/2).coerceAtLeast(6f).toInt();val bot=(cy+h/2).coerceAtMost(b.height-7f).toInt();val rows=mutableListOf<Triple<Int,Int,Int>>();val bright=220.0
    for(y in top..bot step max(2,(bot-top)/80)){var bestL=cx;var bestR=cx;var run=-1;var x=l;while(x<=r+1){val ok=x<=r&&lum(b.getPixel(x,y))>=bright;if(ok&&run<0)run=x;if(!ok&&run>=0){if(x-run>5&&cx in run..(x-1)){bestL=run;bestR=x-1;break};run=-1};x++};if(bestR>bestL)rows+=Triple(y,bestL,bestR)}
    if(rows.size<8){val p=Path();val rr=RectF(l.toFloat(),top.toFloat(),r.toFloat(),bot.toFloat());p.addOval(rr,Path.Direction.CW);return V5Area(p,rr)}
    val s=rows.toMutableList();for(i in 1 until s.lastIndex){val a=s[i-1];val z=s[i+1];s[i]=Triple(s[i].first,(a.second+s[i].second+z.second)/3,(a.third+s[i].third+z.third)/3)}
    val p=Path();p.moveTo(s.first().second.toFloat(),s.first().first.toFloat());for(z in s.drop(1))p.lineTo(z.second.toFloat(),z.first.toFloat());for(z in s.asReversed())p.lineTo(z.third.toFloat(),z.first.toFloat());p.close();return V5Area(p,RectF(s.minOf{it.second}.toFloat(),s.first().first.toFloat(),s.maxOf{it.third}.toFloat(),s.last().first.toFloat()))
}

private fun render(src:Bitmap,pairs:List<Pair<V5Region,String>>):Bitmap{
    val out=src.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(out)
    for((q,text)in pairs){if(text.isBlank())continue;val area=detectArea(src,q.box);val color=bg(src,q.box);val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=color;c.save();c.clipPath(area.path);c.drawRect(area.bounds,p);c.restore();draw(c,area,text,color)}
    return out
}
private fun draw(c:Canvas,a:V5Area,text:String,color:Int){val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=if(lum(color)>150)Color.BLACK else Color.WHITE;p.typeface=Typeface.create("sans",Typeface.NORMAL);var sz=(a.bounds.height()*.14f).coerceIn(18f,52f);var lines=listOf<String>();while(sz>=11){p.textSize=sz;lines=wrap(text,p,a.bounds.width()*.64f);if(lines.size*(p.fontMetrics.bottom-p.fontMetrics.top)<=a.bounds.height()*.58f)break;sz-=1};val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=a.bounds.centerY()-lines.size*lh/2-p.fontMetrics.top;c.save();c.clipPath(a.path);for(s in lines){c.drawText(s,a.bounds.centerX()-p.measureText(s)/2,y,p);y+=lh};c.restore()}
private fun wrap(t:String,p:Paint,w:Float):List<String>{val ts=if(t.any(Char::isWhitespace))t.trim().split(Regex("\\s+"))else t.map(Char::toString);val r=mutableListOf<String>();var cur="";for(x in ts){val z=if(cur.isEmpty())x else "$cur $x";if(p.measureText(z)<=w)cur=z else{if(cur.isNotEmpty())r+=cur;if(p.measureText(x)<=w)cur=x else{var ch="";for(k in x){if(p.measureText(ch+k)<=w)ch+=k else{if(ch.isNotEmpty())r+=ch;ch=k.toString()}};cur=ch}}};if(cur.isNotEmpty())r+=cur;return r}

@Composable private fun V5App(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open&&pages.isNotEmpty())Reader(pages){open=false}else Home{pick.launch(arrayOf("image/*"))}}
@Composable private fun Home(open:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=open){Text("Abrir páginas")}}}}
@Composable private fun Reader(pages:List<Uri>,back:()->Unit){var n by remember{mutableIntStateOf(0)};var bmp by remember{mutableStateOf<Bitmap?>(null)};var busy by remember{mutableStateOf(false)};var status by remember{mutableStateOf<String?>(null)};val ctx=LocalContext.current;val scope=rememberCoroutineScope();fun go(){if(busy)return;scope.launch{busy=true;try{val src=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(pages[n]).use{BitmapFactory.decodeStream(it)}?:error("Imagem inválida")};status="OCR em andamento...";val blocks=V5Ocr().use{it.run(src)};if(blocks.isEmpty()){status="Nenhum texto detectado.";return@launch};val regs=group(blocks);status="Traduzindo ${regs.size} região(ões)...";val tr=V5Trans();val done=try{regs.mapNotNull{q->runCatching{q to tr.go(q.text,q.script)}.getOrNull()}}finally{tr.close()};bmp=withContext(Dispatchers.Default){render(src,done)};status="${done.size} região(ões) traduzida(s)."}catch(e:Exception){status="Falha: ${e.message?:"erro desconhecido"}"}finally{busy=false}}}
 Scaffold(topBar={TopAppBar(title={Text("Página ${n+1} / ${pages.size}")},navigationIcon={TextButton(onClick=back){Text("Voltar")}},actions={Button(onClick=::go,enabled=!busy){Text(if(busy)"Traduzindo..." else "Traduzir")}})},bottomBar={Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){OutlinedButton(onClick={if(n>0){n--;bmp=null;status=null}},enabled=n>0&&!busy){Text("Anterior")};OutlinedButton(onClick={if(n<pages.lastIndex){n++;bmp=null;status=null}},enabled=n<pages.lastIndex&&!busy){Text("Próxima")}}}){p->Box(Modifier.fillMaxSize().padding(p)){if(bmp!=null)Zoom(bmp!!)else UriImage(pages[n]);status?.let{Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp),tonalElevation=4.dp){Text(it,Modifier.padding(16.dp),fontWeight=FontWeight.Medium)}}}}}
@Composable private fun UriImage(u:Uri){var s by remember(u){mutableFloatStateOf(1f)};var o by remember(u){mutableStateOf(Offset.Zero)};Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(u){detectTransformGestures{_,pan,z,_->s=(s*z).coerceIn(1f,5f);o+=pan}},Alignment.Center){coil3.compose.AsyncImage(model=u,contentDescription="Página",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=s,scaleY=s,translationX=o.x,translationY=o.y))}}
@Composable private fun Zoom(b:Bitmap){var s by remember(b){mutableFloatStateOf(1f)};var o by remember(b){mutableStateOf(Offset.Zero)};Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(b){detectTransformGestures{_,pan,z,_->s=(s*z).coerceIn(1f,5f);o+=pan}},Alignment.Center){Image(b.asImageBitmap(),contentDescription="Página traduzida",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=s,scaleY=s,translationX=o.x,translationY=o.y))}}
