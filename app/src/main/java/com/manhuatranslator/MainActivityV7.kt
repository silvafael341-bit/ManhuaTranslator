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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class V7Script { CHINESE, JAPANESE, KOREAN, LATIN }
data class V7Block(val text:String,val box:Rect,val confidence:Float,val script:V7Script)
data class V7Region(val box:Rect,val text:String,val script:V7Script)
data class V7Area(val path:Path,val bounds:RectF)

class MainActivityV7:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{V7App()}}
}

private class V7Ocr:AutoCloseable{
 private data class R(val s:V7Script,val r:TextRecognizer)
 private val recognizers=listOf(
  R(V7Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
  R(V7Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
  R(V7Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
  R(V7Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
 )
 suspend fun run(src:Bitmap):List<V7Block>{
  val all=mutableListOf<V7Block>()
  for(rot in intArrayOf(0,90,270)){
   val b=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true)
   try{for(r in recognizers){val z=r.r.process(InputImage.fromBitmap(b,0)).await();for(block in z.textBlocks)for(line in block.lines){val t=line.text.trim();val box=line.boundingBox?:continue;if(t.isEmpty())continue;val conf=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:0.5f;all+=V7Block(t,map(box,rot,src.width,src.height),conf,r.s)}}}finally{if(b!==src)b.recycle()}
  }
  return choose(all)
 }
 private fun choose(a:List<V7Block>):List<V7Block>{
  val cjk=a.filter{it.script!=V7Script.LATIN&&it.text.count(::cjkChar)>=2}
  val sorted=a.sortedByDescending{score(it)}
  val out=mutableListOf<V7Block>()
  for(x in sorted){
   if(x.text.count{it.isLetter()}<2)continue
   if(x.script==V7Script.LATIN&&cjk.any{overlap(it.box,x.box)>=0.18f&&it.confidence>=x.confidence*0.40f})continue
   if(out.none{overlap(it.box,x.box)>=0.52f})out+=x
  }
  return out.sortedWith(compareBy<V7Block>{it.box.top}.thenBy{it.box.left})
 }
 private fun score(x:V7Block):Double{
  val k=x.text.count(::cjkChar);val n=x.text.count{!it.isWhitespace()};val letters=x.text.count{it.isLetter()};val q=if(x.script==V7Script.LATIN)latinQuality(x.text)else 1.0
  return x.confidence*120.0+k*38.0+n*1.0+letters*1.5+q*20.0+if(x.script==V7Script.LATIN)0.0 else 30.0
 }
 private fun latinQuality(t:String):Double{
  val s=t.lowercase();val letters=s.count{it in 'a'..'z'};if(letters<3)return 0.0
  val vowels=s.count{it in "aeiou"};val words=s.split(Regex("\\s+")).filter{it.length>=2};val bad=s.count{it in "0123456789"}
  val ratio=vowels.toDouble()/letters.coerceAtLeast(1);return letters.toDouble()/s.count{!it.isWhitespace()}.coerceAtLeast(1)+words.size*.08+ratio.coerceIn(.2,.6)*.2-bad*.12
 }
 private fun cjkChar(c:Char)=c.code in 0x3040..0x30ff||c.code in 0x3400..0x9fff||c.code in 0xac00..0xd7af
 private fun overlap(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val inter=(r-l).toLong()*(d-t);val area=min(a.width().toLong()*a.height(),b.width().toLong()*b.height());return if(area<=0L)0f else inter.toFloat()/area}
 private fun map(q:Rect,rot:Int,w:Int,h:Int):Rect{if(rot==0)return Rect(q);val pts=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x};return Rect(pts.minOf{it.first}.coerceIn(0,w),pts.minOf{it.second}.coerceIn(0,h),pts.maxOf{it.first}.coerceIn(0,w),pts.maxOf{it.second}.coerceIn(0,h))}
 override fun close(){recognizers.forEach{it.r.close()}}
}

private fun group7(a:List<V7Block>):List<V7Region>{
 val out=mutableListOf<V7Region>()
 for(b in a){
  val idx=out.indexOfFirst{r->
   val gapY=max(0,max(r.box.top,b.box.top)-min(r.box.bottom,b.box.bottom))
   val gapX=max(0,max(r.box.left,b.box.left)-min(r.box.right,b.box.right))
   val closeY=gapY<=max(70,max(r.box.height(),b.box.height())*2.2f)
   val closeX=gapX<=max(35,min(r.box.width(),b.box.width())/2)
   (closeY&&abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.4f)||(closeX&&abs(r.box.centerY()-b.box.centerY())<=max(100,max(r.box.height(),b.box.height())*3.5f))
  }
  if(idx<0)out+=V7Region(Rect(b.box),b.text,b.script) else {val r=out[idx];out[idx]=V7Region(Rect(r.box).apply{union(b.box)},clean7(r.text+" "+b.text),dominant7(r.script,b.script,r.text+" "+b.text))}
 }
 return out
}
private fun clean7(s:String)=s.replace(Regex("\\s+")," ").trim()
private fun dominant7(a:V7Script,b:V7Script,t:String)=when{t.any{it.code in 0xac00..0xd7af}->V7Script.KOREAN;t.any{it.code in 0x3040..0x30ff}->V7Script.JAPANESE;t.any{it.code in 0x3400..0x9fff}->V7Script.CHINESE;a==b->a;else->b}

private class V7Translator:AutoCloseable{
 private val map=mutableMapOf<String,Translator>()
 suspend fun translate(text:String,script:V7Script):String{
  val src=when(script){V7Script.CHINESE->"zh";V7Script.JAPANESE->"ja";V7Script.KOREAN->"ko";V7Script.LATIN->"en"}
  val tr=map.getOrPut(src){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage("pt").build())}
  tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
  return tr.translate(text).await().trim()
 }
 override fun close(){map.values.forEach{it.close()};map.clear()}
}

private fun lum7(c:Int)=0.299*Color.red(c)+0.587*Color.green(c)+0.114*Color.blue(c)
private fun background7(b:Bitmap,q:Rect):Int{
 val l=max(0,q.left-q.width());val t=max(0,q.top-q.height());val r=min(b.width-1,q.right+q.width());val d=min(b.height-1,q.bottom+q.height());val vals=mutableListOf<Int>();val sx=max(3,(r-l)/18);val sy=max(3,(d-t)/18);var y=t
 while(y<=d){var x=l;while(x<=r){val c=b.getPixel(x,y);if(lum7(c)>=225)vals+=c;x+=sx};y+=sy}
 if(vals.isEmpty())return Color.WHITE
 val rr=vals.map{Color.red(it)}.sorted()[vals.size/2];val gg=vals.map{Color.green(it)}.sorted()[vals.size/2];val bb=vals.map{Color.blue(it)}.sorted()[vals.size/2]
 return if(rr>=235&&gg>=235&&bb>=235)Color.WHITE else Color.rgb(rr,gg,bb)
}

private fun radialArea7(b:Bitmap,q:Rect):V7Area{
 val cx=q.centerX().coerceIn(1,b.width-2);val cy=q.centerY().coerceIn(1,b.height-2)
 val maxR=min(b.width,b.height)*0.48f
 val points=mutableListOf<Pair<Float,Float>>()
 for(i in 0 until 180){
  val a=(i*2.0*Math.PI/180.0);var lastBright=0;var x=cx.toFloat();var y=cy.toFloat();var hit=false
  for(step in 2..maxR.toInt() step 2){x=cx+cos(a).toFloat()*step;y=cy+sin(a).toFloat()*step;if(x<2||x>=b.width-2||y<2||y>=b.height-2)break
   val dark=lum7(b.getPixel(x.toInt(),y.toInt()))<175
   if(dark){
    var darkCount=0;var brightAfter=0
    for(k in 0..12){val xx=(x+cos(a).toFloat()*k).toInt().coerceIn(0,b.width-1);val yy=(y+sin(a).toFloat()*k).toInt().coerceIn(0,b.height-1);if(lum7(b.getPixel(xx,yy))<175)darkCount++ else brightAfter++}
    if(darkCount>=5&&brightAfter<=5){hit=true;break}
   } else lastBright=step
  }
  if(hit)points+=x to y
 }
 if(points.size<80){val rr=RectF(q.left.toFloat()-q.width(),q.top.toFloat()-q.height(),q.right.toFloat()+q.width(),q.bottom.toFloat()+q.height());val p=Path();p.addOval(rr,Path.Direction.CW);return V7Area(p,rr)}
 val p=Path();p.moveTo(points[0].first,points[0].second);for(z in points.drop(1))p.lineTo(z.first,z.second);p.close()
 val left=points.minOf{it.first};val top=points.minOf{it.second};val right=points.maxOf{it.first};val bottom=points.maxOf{it.second};return V7Area(p,RectF(left,top,right,bottom))
}

private fun render7(src:Bitmap,pairs:List<Pair<V7Region,String>>):Bitmap{
 val out=src.copy(Bitmap.Config.ARGB_8888,true);val canvas=Canvas(out)
 for((region,text) in pairs){if(text.isBlank())continue;val area=radialArea7(src,region.box);val bg=background7(src,region.box);val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=bg};canvas.save();canvas.clipPath(area.path);canvas.drawPath(area.path,fill);canvas.restore();draw7(canvas,area,text,bg)}
 return out
}
private fun draw7(c:Canvas,a:V7Area,text:String,bg:Int){val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(lum7(bg)>150)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)};var size=(a.bounds.height()*0.095f).coerceIn(16f,46f);var lines=listOf<String>();while(size>=10f){p.textSize=size;lines=wrap7(text,p,a.bounds.width()*0.58f);if(lines.size*(p.fontMetrics.bottom-p.fontMetrics.top)<=a.bounds.height()*0.55f)break;size-=1f};val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=a.bounds.centerY()-lines.size*lh/2f-p.fontMetrics.top;c.save();c.clipPath(a.path);for(line in lines){c.drawText(line,a.bounds.centerX()-p.measureText(line)/2f,y,p);y+=lh};c.restore()}
private fun wrap7(text:String,p:Paint,width:Float):List<String>{val tokens=if(text.any(Char::isWhitespace))text.trim().split(Regex("\\s+"))else text.map{it.toString()};val out=mutableListOf<String>();var cur="";for(tok in tokens){val next=if(cur.isEmpty())tok else "$cur $tok";if(p.measureText(next)<=width)cur=next else{if(cur.isNotEmpty())out+=cur;if(p.measureText(tok)<=width)cur=tok else{var part="";for(ch in tok){if(p.measureText(part+ch)<=width)part+=ch else{if(part.isNotEmpty())out+=part;part=ch.toString()}};cur=part}}};if(cur.isNotEmpty())out+=cur;return out}

@Composable private fun V7App(){
 var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)}
 val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}}
 if(open&&pages.isNotEmpty())V7Reader(pages){open=false}else V7Home{pick.launch(arrayOf("image/*"))}
}
@Composable private fun V7Home(onOpen:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}}}}

@Composable private fun V7Reader(uris:List<Uri>,onBack:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var ox by remember{mutableStateOf(0f)};var oy by remember{mutableStateOf(0f)}
 LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;ox=0f;oy=0f;status=""}
 DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}}
 val shown=translated?:bitmap
 Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={scope.launch{status="Processando…";val src=bitmap;withContext(Dispatchers.Default){if(src!=null){val ocr=V7Ocr();val tr=V7Translator();try{val blocks=ocr.run(src);val regions=group7(blocks);val pairs=regions.mapNotNull{r->try{val t=tr.translate(r.text,r.script);if(t.isBlank())null else r to t}catch(_:Throwable){null}};val out=render7(src,pairs);withContext(Dispatchers.Main){translated=out;status="${pairs.size} região(ões) traduzida(s)."}}finally{ocr.close();tr.close()}}}}}){Text("Traduzir")}}}){pad->Box(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.surface)){if(shown!=null){Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);ox+=pan.x;oy+=pan.y}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=ox;translationY=oy}}};if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=72.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal=24.dp,vertical=12.dp));Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=index>0,onClick={index--;}){Text("Anterior")};Button(enabled=index<uris.lastIndex,onClick={index++;}){Text("Próxima")}}}}}
