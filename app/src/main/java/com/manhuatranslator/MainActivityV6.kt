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

enum class V6Script { CHINESE, JAPANESE, KOREAN, LATIN }
data class V6Block(val text:String,val box:Rect,val confidence:Float,val script:V6Script)
data class V6Region(val box:Rect,val text:String,val script:V6Script)
data class V6Area(val path:Path,val bounds:RectF)

class MainActivityV6:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{V6App()}}}

private class V6Ocr:AutoCloseable{
 private data class C(val s:V6Script,val r:TextRecognizer)
 private val cs=listOf(
  C(V6Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
  C(V6Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
  C(V6Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
  C(V6Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
 )
 suspend fun run(src:Bitmap):List<V6Block>{
  val all=mutableListOf<V6Block>();val hi=enhance(src)
  try{for(a in intArrayOf(0,90,270)){
   val b=if(a==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(a.toFloat())},true)
   try{all+=read(b,a,src.width,src.height,false)}finally{if(b!==src)b.recycle()}
   val e=if(a==0)hi else Bitmap.createBitmap(hi,0,0,hi.width,hi.height,Matrix().apply{postRotate(a.toFloat())},true)
   try{all+=read(e,a,src.width,src.height,true)}finally{if(e!==hi)e.recycle()}
  }}finally{hi.recycle()}
  return choose(all)
 }
 private suspend fun read(b:Bitmap,a:Int,w:Int,h:Int,enhanced:Boolean):List<V6Block>{
  val out=mutableListOf<V6Block>()
  for(c in cs){if(enhanced&&c.s!=V6Script.LATIN)continue;val z=c.r.process(InputImage.fromBitmap(b,0)).await();z.textBlocks.flatMap{it.lines}.forEach{l->val t=l.text.trim();val q=l.boundingBox?:return@forEach;if(t.isEmpty())return@forEach;val conf=l.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:.5f;out+=V6Block(t,map(q,a,w,h),conf,c.s)}}
  return out
 }
 private fun choose(a:List<V6Block>):List<V6Block>{
  val cjk=a.filter{it.script!=V6Script.LATIN&&it.text.count(::isCjk)>=1};val out=mutableListOf<V6Block>()
  for(x in a.sortedByDescending{score(it)}){
   if(x.text.count{it.isLetter()}<2)continue
   if(x.script==V6Script.LATIN&&cjk.any{overlap(it.box,x.box)>.20f&&it.confidence>=x.confidence*.45f})continue
   if(out.none{overlap(it.box,x.box)>.55f})out+=x
  }
  return out.sortedWith(compareBy<V6Block>{it.box.top}.thenBy{it.box.left})
 }
 private fun score(x:V6Block):Double{val n=x.text.count{!it.isWhitespace()};val k=x.text.count(::isCjk);val letters=x.text.count{it.isLetter()};val weird=x.text.count{!it.isLetterOrDigit()&&!it.isWhitespace()&&it!in ".,!?'-"};val q=if(x.script==V6Script.LATIN)latinQuality(x.text)else 1.0;return x.confidence*120+k*32+n*1.1+letters*1.8+q*30-weird*6+if(x.script==V6Script.LATIN)0.0 else 24.0}
 private fun latinQuality(t:String):Double{val s=t.lowercase();val letters=s.count{it in 'a'..'z'};if(letters<2)return 0.0;val vowels=s.count{it in "aeiou"};val words=s.split(Regex("\\s+")).filter{it.length>=2};val common=listOf("the","and","to","of","in","is","it","for","this","that","one","more","now","zone","defeating","pushing","killing","monsters","body","over").count{w->words.any{it.trim(".,!?'-")==w}};val ratio=vowels.toDouble()/letters;return letters.toDouble()/s.count{!it.isWhitespace()}.coerceAtLeast(1)+words.size*.10+common*.35+ratio.coerceIn(.18,.58)*.18}
 private fun isCjk(c:Char)=c.code in 0x3040..0x30ff||c.code in 0x3400..0x9fff||c.code in 0xac00..0xd7af
 private fun overlap(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val i=(r-l).toLong()*(d-t);val ar=min(a.width().toLong()*a.height(),b.width().toLong()*b.height());return if(ar==0L)0f else i.toFloat()/ar}
 private fun map(q:Rect,a:Int,w:Int,h:Int):Rect{if(a==0)return Rect(q);val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(a==90)y to h-x else w-y to x};return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))}
 private fun enhance(src:Bitmap):Bitmap{val out=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888);val c=Canvas(out);val cm=ColorMatrix();cm.setSaturation(0f);val k=1.8f;val sh=-128f*(k-1f);cm.postConcat(ColorMatrix(floatArrayOf(k,0f,0f,0f,sh,0f,k,0f,0f,sh,0f,0f,k,0f,sh,0f,0f,0f,1f,0f)));val p=Paint(Paint.ANTI_ALIAS_FLAG);p.colorFilter=ColorMatrixColorFilter(cm);c.drawBitmap(src,0f,0f,p);return out}
 override fun close(){cs.forEach{it.r.close()}}
}

private fun group(a:List<V6Block>):List<V6Region>{
 val r=mutableListOf<V6Region>()
 for(b in a){val i=r.indexOfFirst{x->val gx=max(0,max(x.box.left,b.box.left)-min(x.box.right,b.box.right));val gap=max(0,max(x.box.top,b.box.top)-min(x.box.bottom,b.box.bottom));val cy=abs(x.box.centerY()-b.box.centerY());val cx=abs(x.box.centerX()-b.box.centerX());val horizontal=gap<=max(95,max(x.box.height(),b.box.height())*2.6f)&&cx<=max(x.box.width(),b.box.width())*1.15f;val overlapX=gx<=max(25,min(x.box.width(),b.box.width())/3);horizontal||overlapX&&cy<=max(110,max(x.box.height(),b.box.height())*4f)};if(i<0)r+=V6Region(Rect(b.box),b.text,b.script)else{val o=r[i];r[i]=V6Region(Rect(o.box).apply{union(b.box)},cleanText(o.text+" "+b.text),dominant(o.script,b.script,o.text+" "+b.text))}}
 return r
}
private fun cleanText(s:String)=s.replace(Regex("\\s+")," ").replace(Regex("[\\u0000-\\u001F]"),"").trim()
private fun dominant(a:V6Script,b:V6Script,t:String)=when{t.any{it.code in 0xac00..0xd7af}->V6Script.KOREAN;t.any{it.code in 0x3040..0x30ff}->V6Script.JAPANESE;t.any{it.code in 0x3400..0x9fff}->V6Script.CHINESE;a==b->a;else->b}

private class V6Trans:AutoCloseable{private val m=mutableMapOf<String,Translator>();suspend fun go(t:String,s:V6Script):String{val src=when(s){V6Script.CHINESE->"zh";V6Script.JAPANESE->"ja";V6Script.KOREAN->"ko";V6Script.LATIN->"en"};val tr=m.getOrPut(src){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage("pt").build())};tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await();return tr.translate(t).await().trim()};override fun close(){m.values.forEach{it.close()};m.clear()}}

private fun lum(c:Int)=.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c)
private fun bg(b:Bitmap,q:Rect):Int{val l=(q.left-q.width()).coerceAtLeast(0);val t=(q.top-q.height()).coerceAtLeast(0);val r=(q.right+q.width()).coerceAtMost(b.width-1);val d=(q.bottom+q.height()).coerceAtMost(b.height-1);val v=mutableListOf<Int>();var y=t;val sy=max(3,(d-t)/20);val sx=max(3,(r-l)/20);while(y<=d){var x=l;while(x<=r){val c=b.getPixel(x,y);if(lum(c)>=225)v+=c;x+=sx};y+=sy};if(v.size>=12){val rr=v.sumOf{Color.red(it)}/v.size;val gg=v.sumOf{Color.green(it)}/v.size;val bb=v.sumOf{Color.blue(it)}/v.size;return if(rr>235&&gg>235&&bb>235)Color.WHITE else Color.rgb(rr,gg,bb)};return Color.WHITE}

private fun edge(b:Bitmap,start:Int,dir:Int,y:Int,limit:Int):Int{var x=start;var dark=0;var bright=0;while(x>=0&&x<b.width&&abs(x-start)<limit){val light=lum(b.getPixel(x,y))>=218;if(light){bright++;dark=0}else{dark++;if(dark>=18&&bright>=8)break};x+=dir};return x-dir}
private fun detectArea(b:Bitmap,q:Rect):V6Area{
 val cx=q.centerX().coerceIn(0,b.width-1);val cy=q.centerY().coerceIn(0,b.height-1);val maxW=max(260f,q.width()*2.8f).coerceAtMost(b.width-10f);val maxH=max(240f,q.height()*5.0f).coerceAtMost(b.height-10f);val l=max(5,cx-(maxW/2).toInt());val r=min(b.width-6,cx+(maxW/2).toInt());val top=max(5,cy-(maxH/2).toInt());val bot=min(b.height-6,cy+(maxH/2).toInt());val rows=mutableListOf<Triple<Int,Int,Int>>();val step=max(2,(bot-top)/90)
 for(y in top..bot step step){val left=edge(b,cx,-1,y,cx-l+1);val right=edge(b,cx,1,y,r-cx+1);if(right-left>40)rows+=Triple(y,left,right)}
 if(rows.size<10){val rr=RectF(l.toFloat(),top.toFloat(),r.toFloat(),bot.toFloat());val p=Path();p.addOval(rr,Path.Direction.CW);return V6Area(p,rr)}
 val s=rows.toMutableList();for(i in 1 until s.lastIndex){val a=s[i-1];val z=s[i+1];s[i]=Triple(s[i].first,(a.second+s[i].second+z.second)/3,(a.third+s[i].third+z.third)/3)}
 val p=Path();p.moveTo(s.first().second.toFloat(),s.first().first.toFloat());for(z in s.drop(1))p.lineTo(z.second.toFloat(),z.first.toFloat());for(z in s.asReversed())p.lineTo(z.third.toFloat(),z.first.toFloat());p.close();return V6Area(p,RectF(s.minOf{it.second}.toFloat(),s.first().first.toFloat(),s.maxOf{it.third}.toFloat(),s.last().first.toFloat()))
}

private fun render(src:Bitmap,pairs:List<Pair<V6Region,String>>):Bitmap{val out=src.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(out);for((q,text)in pairs){if(text.isBlank())continue;val area=detectArea(src,q.box);val color=bg(src,q.box);val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=color;c.save();c.clipPath(area.path);c.drawRect(area.bounds,p);c.restore();draw(c,area,text,color)};return out}
private fun draw(c:Canvas,a:V6Area,text:String,color:Int){val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=if(lum(color)>150)Color.BLACK else Color.WHITE;p.typeface=Typeface.create("sans",Typeface.NORMAL);var sz=(a.bounds.height()*.105f).coerceIn(17f,48f);var lines=listOf<String>();while(sz>=10){p.textSize=sz;lines=wrap(text,p,a.bounds.width()*.62f);if(lines.size*(p.fontMetrics.bottom-p.fontMetrics.top)<=a.bounds.height()*.50f)break;sz-=1};val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=a.bounds.centerY()-lines.size*lh/2-p.fontMetrics.top;c.save();c.clipPath(a.path);for(s in lines){c.drawText(s,a.bounds.centerX()-p.measureText(s)/2,y,p);y+=lh};c.restore()}
private fun wrap(t:String,p:Paint,w:Float):List<String>{val ts=if(t.any(Char::isWhitespace))t.trim().split(Regex("\\s+"))else t.map(Char::toString);val r=mutableListOf<String>();var cur="";for(x in ts){val z=if(cur.isEmpty())x else "$cur $x";if(p.measureText(z)<=w)cur=z else{if(cur.isNotEmpty())r+=cur;if(p.measureText(x)<=w)cur=x else{var ch="";for(k in x){if(p.measureText(ch+k)<=w)ch+=k else{if(ch.isNotEmpty())r+=ch;ch=k.toString()}};cur=ch}}};if(cur.isNotEmpty())r+=cur;return r}

@Composable private fun V6App(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open&&pages.isNotEmpty())Reader(pages){open=false}else Home{pick.launch(arrayOf("image/*"))}}
@Composable private fun Home(open:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=open){Text("Abrir páginas")}}}}
@Composable private fun Reader(pages:List<Uri>,back:()->Unit){var n by remember{mutableIntStateOf(0)};var bmp by remember{mutableStateOf<Bitmap?>(null)};var busy by remember{mutableStateOf(false)};var status by remember{mutableStateOf<String?>(null)};val ctx=LocalContext.current;val scope=rememberCoroutineScope();fun go(){if(busy)return;scope.launch{busy=true;try{val src=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(pages[n]).use{BitmapFactory.decodeStream(it)}?:error("Imagem inválida")};status="OCR em andamento...";val blocks=V6Ocr().use{it.run(src)};if(blocks.isEmpty()){status="Nenhum texto detectado.";return@launch};val regs=group(blocks);status="Traduzindo ${regs.size} região(ões)...";val tr=V6Trans();val done=try{regs.mapNotNull{q->runCatching{q to tr.go(q.text,q.script)}.getOrNull()}}finally{tr.close()};bmp=withContext(Dispatchers.Default){render(src,done)};status="${done.size} região(ões) traduzida(s)."}catch(e:Exception){status="Falha: ${e.message?:"erro desconhecido"}"}finally{busy=false}}}
 Scaffold(topBar={TopAppBar(title={Text("Página ${n+1} / ${pages.size}")},navigationIcon={TextButton(onClick=back){Text("Voltar")}},actions={Button(onClick=::go,enabled=!busy){Text(if(busy)"Traduzindo..." else "Traduzir")}})},bottomBar={Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){OutlinedButton(onClick={if(n>0){n--;bmp=null;status=null}},enabled=n>0&&!busy){Text("Anterior")};OutlinedButton(onClick={if(n<pages.lastIndex){n++;bmp=null;status=null}},enabled=n<pages.lastIndex&&!busy){Text("Próxima")}}}){p->Box(Modifier.fillMaxSize().padding(p)){if(bmp!=null)Zoom(bmp!!)else UriImage(pages[n]);status?.let{Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp),tonalElevation=4.dp){Text(it,Modifier.padding(16.dp),fontWeight=FontWeight.Medium)}}}}}
@Composable private fun UriImage(u:Uri){val ctx=LocalContext.current;var b by remember(u){mutableStateOf<Bitmap?>(null)};LaunchedEffect(u){b=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(u).use{BitmapFactory.decodeStream(it)}}};if(b!=null)Zoom(b!!)else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
@Composable private fun Zoom(b:Bitmap){var s by remember(b){mutableFloatStateOf(1f)};var o by remember(b){mutableStateOf(Offset.Zero)};Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).pointerInput(b){detectTransformGestures{_,pan,z,_->s=(s*z).coerceIn(1f,5f);o+=pan}}){Image(bitmap=b.asImageBitmap(),contentDescription=null,modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=s;scaleY=s;translationX=o.x;translationY=o.y},contentScale=ContentScale.Fit)}}
