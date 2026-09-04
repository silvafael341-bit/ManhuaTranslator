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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.*
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class S12 { ZH, JA, KO, EN }
private data class B12(val text:String,val box:Rect,val conf:Float,val script:S12)
private data class R12(val box:Rect,val text:String,val script:S12)

class MainActivityV12:ComponentActivity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App12()}}
}

private class Ocr12:AutoCloseable{
 private val rs=listOf(
  S12.ZH to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
  S12.JA to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
  S12.KO to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
  S12.EN to TextRecognition.getClient(TextRecognizerOptions.Builder().build())
 )
 suspend fun run(src:Bitmap):List<B12>{
  val all=mutableListOf<B12>()
  for(rot in intArrayOf(0,90,270)){
   val base=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true)
   val w=(base.width*2).coerceAtMost(3200);val h=(base.height*2).coerceAtMost(3200)
   val img=Bitmap.createScaledBitmap(base,w,h,true)
   if(base!==src)base.recycle()
   try{for((script,rec) in rs){
    val res=rec.process(InputImage.fromBitmap(img,0)).await()
    for(tb in res.textBlocks)for(line in tb.lines){
     val text=line.text.trim();val bb=line.boundingBox?:continue
     if(text.count{it.isLetter()}<2)continue
     val q=Rect((bb.left/2f).toInt(),(bb.top/2f).toInt(),(bb.right/2f).toInt(),(bb.bottom/2f).toInt())
     val box=map12(q,rot,src.width,src.height)
     val conf=line.elements.mapNotNull{it.confidence}.average().toFloat().takeIf{it>0}?:0.5f
     all+=B12(text,box,conf,script)
    }
   }}finally{img.recycle()}
  }
  return select12(all)
 }
 private fun cjk(s:String)=s.count{it.code in 0x3040..0x30ff||it.code in 0x3400..0x9fff||it.code in 0xac00..0xd7af}
 private fun ov(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val i=(r-l).toLong()*(d-t);val z=min(a.width().toLong()*a.height(),b.width().toLong()*b.height());return if(z==0L)0f else i.toFloat()/z}
 private fun near(a:Rect,b:Rect)=abs(a.centerX()-b.centerX())<max(a.width(),b.width())*1.5f&&abs(a.centerY()-b.centerY())<max(a.height(),b.height())*2.5f
 private fun select12(x:List<B12>):List<B12>{
  val c=x.filter{it.script!=S12.EN&&cjk(it.text)>=1}
  return x.sortedByDescending{it.conf*100f+cjk(it.text)*180f}.filter{b->
   b.text.count{it.isLetter()}>=2 && !(b.script==S12.EN&&c.any{ov(it.box,b.box)>=0.03f||near(it.box,b.box)})
  }.fold(mutableListOf<B12>()){out,b->if(out.none{ov(it.box,b.box)>=0.5f})out.add(b);out}.sortedWith(compareBy{it.box.top}.thenBy{it.box.left})
 }
 private fun map12(q:Rect,rot:Int,w:Int,h:Int):Rect{if(rot==0)return Rect(q);val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x};return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))}
 override fun close(){rs.forEach{it.second.close()}}
}

private fun group12(bs:List<B12>):List<R12>{
 val out=mutableListOf<R12>()
 for(b in bs){
  val i=out.indexOfFirst{r->abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.4f&&abs(r.box.centerY()-b.box.centerY())<=max(r.box.height(),b.box.height())*2.4f}
  if(i<0)out+=R12(Rect(b.box),b.text,b.script) else{val r=out[i];out[i]=R12(Rect(r.box).apply{union(b.box)},(r.text+" "+b.text).replace(Regex("\\s+")," ").trim(),r.script)}
 }
 return out
}

private class Tr12:AutoCloseable{
 private val cs=mutableMapOf<S12,Translator>()
 suspend fun go(text:String,s:S12):String{val src=when(s){S12.ZH->"zh";S12.JA->"ja";S12.KO->"ko";S12.EN->"en"};val c=cs.getOrPut(s){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage("pt").build())};c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await();return c.translate(text).await().trim()}
 override fun close(){cs.values.forEach{it.close()};cs.clear()}
}

private fun bg12(b:Bitmap,q:Rect):Int{
 val l=max(0,q.left-q.width()/2);val t=max(0,q.top-q.height());val r=min(b.width-1,q.right+q.width()/2);val d=min(b.height-1,q.bottom+q.height())
 var sr=0;var sg=0;var sb=0;var n=0
 var y=t;while(y<=d){var x=l;while(x<=r){val c=b.getPixel(x,y);val lum=.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c);if(lum>=220){sr+=Color.red(c);sg+=Color.green(c);sb+=Color.blue(c);n++};x+=max(3,(r-l)/20)};y+=max(3,(d-t)/20)}
 return if(n==0)Color.WHITE else Color.rgb(sr/n,sg/n,sb/n).let{c->if(Color.red(c)>232&&Color.green(c)>232&&Color.blue(c)>232)Color.WHITE else c}
}

private fun render12(src:Bitmap,pairs:List<Pair<R12,String>>):Bitmap{
 val out=src.copy(Bitmap.Config.ARGB_8888,true);val canvas=Canvas(out)
 for((r,text) in pairs){if(text.isBlank())continue;val q=RectF(r.box).apply{inset(-r.box.width()*.75f,-r.box.height()*1.35f)};val path=Path().apply{addOval(q,Path.Direction.CW)};val bg=bg12(src,r.box);val fill=Paint(3).apply{color=bg};canvas.save();canvas.clipPath(path);canvas.drawPath(path,fill);canvas.restore();draw12(canvas,path,q,text,bg)}
 return out
}
private fun draw12(c:Canvas,path:Path,q:RectF,text:String,bg:Int){
 val p=Paint(3).apply{color=if((.299*Color.red(bg)+.587*Color.green(bg)+.114*Color.blue(bg))>150)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",0)}
 var size=(q.height()*.055f).coerceIn(12f,30f);var lines:List<String>
 while(true){p.textSize=size;lines=wrap12(text,p,q.width()*.48f);if(lines.size*(p.fontMetrics.bottom-p.fontMetrics.top)<=q.height()*.35f||size<=10f)break;size-=1f}
 val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=q.centerY()-lines.size*lh/2f-p.fontMetrics.top;c.save();c.clipPath(path);for(s in lines){c.drawText(s,q.centerX()-p.measureText(s)/2f,y,p);y+=lh};c.restore()
}
private fun wrap12(text:String,p:Paint,w:Float):List<String>{val a=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+"))else text.map{it.toString()};val out=mutableListOf<String>();var cur="";for(t in a){val z=if(cur.isEmpty())t else "$cur $t";if(p.measureText(z)<=w)cur=z else{if(cur.isNotEmpty())out+=cur;cur=t}};if(cur.isNotEmpty())out+=cur;return out}

@Composable private fun App12(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open&&pages.isNotEmpty())Reader12(pages){open=false}else Home12{picker.launch(arrayOf("image/*"))}}
@Composable private fun Home12(open:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(24.dp));Button(onClick=open){Text("Abrir imagens")}}}}
@Composable private fun Reader12(uris:List<Uri>,back:()->Unit){
 val ctx=LocalContext.current;val scope=rememberCoroutineScope();var idx by remember{mutableStateOf(0)};var src by remember{mutableStateOf<Bitmap?>(null)};var shown by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var zoom by remember{mutableStateOf(1f)};var tx by remember{mutableStateOf(0f)};var ty by remember{mutableStateOf(0f)}
 LaunchedEffect(idx){src=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(uris[idx])?.use{BitmapFactory.decodeStream(it)}};shown=src;status="";zoom=1f;tx=0f;ty=0f}
 Scaffold(topBar={TopAppBar(title={Text("Página ${idx+1} / ${uris.size}")},navigationIcon={TextButton(onClick=back){Text("Voltar")}},actions={Button(onClick={scope.launch{val b=src;if(b==null)return@launch;status="Processando…";val result=withContext(Dispatchers.Default){val o=Ocr12();val tr=Tr12();try{val regions=group12(o.run(b));val pairs=regions.mapNotNull{r->runCatching{tr.go(r.text,r.script)}.getOrNull()?.takeIf{it.isNotBlank()}?.let{r to it}};render12(b,pairs) to pairs.size}finally{o.close();tr.close()}};shown=result.first;status="${result.second} região(ões) traduzida(s)."}}}){Text("Traduzir")}})}){p->Box(Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surface)){shown?.let{Image(it.asImageBitmap(),null,ContentScale.Fit,Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures{_,pan,z,_->{zoom=(zoom*z).coerceIn(1f,5f);tx+=pan.x;ty+=pan.y}}}.graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=tx;translationY=ty})};if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=72.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(20.dp));Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=idx>0,onClick={idx--}){Text("Anterior")};Button(enabled=idx<uris.lastIndex,onClick={idx++}){Text("Próxima")}}}}
}
