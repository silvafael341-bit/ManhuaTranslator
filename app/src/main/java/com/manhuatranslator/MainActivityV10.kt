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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class V10Script { CHINESE, JAPANESE, KOREAN, LATIN }
private data class V10Block(val text:String,val box:Rect,val confidence:Float,val script:V10Script)
private data class V10Region(val box:Rect,val text:String,val script:V10Script)
private data class V10Shape(val path:Path,val bounds:RectF)

class MainActivityV10:ComponentActivity(){
    override fun onCreate(state:Bundle?){super.onCreate(state);setContent{V10App()}}
}

private class V10Ocr:AutoCloseable{
    private data class R(val script:V10Script,val recognizer:TextRecognizer)
    private val rs=listOf(
        R(V10Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        R(V10Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        R(V10Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        R(V10Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )
    suspend fun run(src:Bitmap):List<V10Block>{
        val all=mutableListOf<V10Block>()
        for(rot in intArrayOf(0,90,270)){
            val img=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true)
            try{for(r in rs){
                val result=r.recognizer.process(InputImage.fromBitmap(img,0)).await()
                for(tb in result.textBlocks) for(line in tb.lines){
                    val raw=line.text.trim();val q=line.boundingBox?:continue
                    if(raw.count{it.isLetter()}<2)continue
                    val conf=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:0.5f
                    all+=V10Block(raw,map10(q,rot,src.width,src.height),conf,r.script)
                }
            }}finally{if(img!==src)img.recycle()}
        }
        return choose10(all)
    }
    private fun choose10(input:List<V10Block>):List<V10Block>{
        val cjk=input.filter{it.script!=V10Script.LATIN&&countCjk10(it.text)>=2}
        val sorted=input.sortedByDescending{it.confidence*100+countCjk10(it.text)*80+it.text.count{c->c.isLetter()}}
        val out=mutableListOf<V10Block>()
        for(x in sorted){
            val latin=x.script==V10Script.LATIN
            if(latin&&cjk.any{over10(it.box,x.box)>=0.08f||near10(it.box,x.box)})continue
            if(out.none{over10(it.box,x.box)>=0.5f})out+=x
        }
        return out.sortedWith(compareBy<V10Block>{it.box.top}.thenBy{it.box.left})
    }
    private fun near10(a:Rect,b:Rect):Boolean=abs(a.centerX()-b.centerX())<=max(a.width(),b.width())*1.2f&&abs(a.centerY()-b.centerY())<=max(a.height(),b.height())*1.8f
    private fun over10(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val i=(r-l).toLong()*(d-t);val ar=min(a.width().toLong()*a.height(),b.width().toLong()*b.height());return if(ar<=0L)0f else i.toFloat()/ar.toFloat()}
    private fun map10(q:Rect,rot:Int,w:Int,h:Int):Rect{if(rot==0)return Rect(q);val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x};return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))}
    override fun close(){rs.forEach{it.recognizer.close()}}
}

private fun countCjk10(s:String)=s.count{it.code in 0x3040..0x30ff||it.code in 0x3400..0x9fff||it.code in 0xac00..0xd7af}

private fun group10(blocks:List<V10Block>):List<V10Region>{
    val out=mutableListOf<V10Region>()
    for(b in blocks){
        val i=out.indexOfFirst{r->
            val vg=max(0,max(r.box.top,b.box.top)-min(r.box.bottom,b.box.bottom))
            val hg=max(0,max(r.box.left,b.box.left)-min(r.box.right,b.box.right))
            val col=abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.3f
            val row=abs(r.box.centerY()-b.box.centerY())<=max(r.box.height(),b.box.height())*2.2f
            (vg<=max(32f,max(r.box.height(),b.box.height()).toFloat()*1.4f)&&col)||(hg<=max(24f,min(r.box.width(),b.box.width()).toFloat()*0.55f)&&row)
        }
        if(i<0)out+=V10Region(Rect(b.box),b.text,b.script) else{
            val r=out[i];val text=(r.text+" "+b.text).replace(Regex("\\s+")," ").trim()
            val script=when{countCjk10(text)>0&&text.any{it.code in 0xac00..0xd7af}->V10Script.KOREAN;countCjk10(text)>0&&text.any{it.code in 0x3040..0x30ff}->V10Script.JAPANESE;countCjk10(text)>0->V10Script.CHINESE;else->r.script}
            out[i]=V10Region(Rect(r.box).apply{union(b.box)},text,script)
        }
    }
    return out
}

private class V10Translator:AutoCloseable{
    private val clients=mutableMapOf<String,Translator>()
    suspend fun tr(text:String,script:V10Script):String{
        val source=when(script){V10Script.CHINESE->"zh";V10Script.JAPANESE->"ja";V10Script.KOREAN->"ko";V10Script.LATIN->"en"}
        val c=clients.getOrPut(source){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build())}
        c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await();return c.translate(text).await().trim()
    }
    override fun close(){clients.values.forEach{it.close()};clients.clear()}
}

private fun lum10(c:Int)=0.299*Color.red(c)+0.587*Color.green(c)+0.114*Color.blue(c)
private fun bg10(b:Bitmap,q:Rect):Int{
    val l=max(0,q.left-q.width()/2);val t=max(0,q.top-q.height()/2);val r=min(b.width-1,q.right+q.width()/2);val d=min(b.height-1,q.bottom+q.height()/2)
    val v=mutableListOf<Int>();val sx=max(3,(r-l)/22);val sy=max(3,(d-t)/22);var y=t
    while(y<=d){var x=l;while(x<=r){val c=b.getPixel(x,y);if(lum10(c)>=220)v+=c;x+=sx};y+=sy}
    if(v.isEmpty())return Color.WHITE
    val rr=v.map{Color.red(it)}.sorted();val gg=v.map{Color.green(it)}.sorted();val bb=v.map{Color.blue(it)}.sorted();val m=v.size/2
    return if(rr[m]>=232&&gg[m]>=232&&bb[m]>=232)Color.WHITE else Color.rgb(rr[m],gg[m],bb[m])
}

private fun shape10(bitmap:Bitmap,q:Rect):V10Shape{
    val cx=q.centerX().toFloat();val cy=q.centerY().toFloat();val start=max(q.width(),q.height()).toFloat()*0.75f;val maxR=max(q.width(),q.height()).toFloat()*2.2f;val bg=bg10(bitmap,q);val pts=ArrayList<Pair<Float,Float>>();val n=96
    for(i in 0 until n){
        val a=i.toFloat()/n*2f*PI.toFloat();val dx=cos(a);val dy=sin(a);var radius=start;var last=start
        while(radius<=maxR){
            val x=(cx+dx*radius).toInt();val y=(cy+dy*radius).toInt();if(x<1||y<1||x>=bitmap.width-1||y>=bitmap.height-1)break
            val c=bitmap.getPixel(x,y);val diff=abs(Color.red(c)-Color.red(bg))+abs(Color.green(c)-Color.green(bg))+abs(Color.blue(c)-Color.blue(bg));
            if(lum10(c)<185&&diff>90){last=radius-2f;break};last=radius;radius+=2f
        }
        pts+=cx+dx*last to cy+dy*last
    }
    val smooth=pts.mapIndexed{j,p->val a=pts[(j+n-1)%n];val c=pts[(j+1)%n];((a.first+p.first+c.first)/3f) to ((a.second+p.second+c.second)/3f)}
    val path=Path();path.moveTo(smooth[0].first,smooth[0].second);for(p in smooth.drop(1))path.lineTo(p.first,p.second);path.close()
    val bounds=RectF(q.left.toFloat(),q.top.toFloat(),q.right.toFloat(),q.bottom.toFloat()).apply{inset(-q.width()*0.35f,-q.height()*0.55f)}
    return V10Shape(path,bounds)
}

private fun render10(src:Bitmap,pairs:List<Pair<V10Region,String>>):Bitmap{
    val out=src.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(out)
    for((r,text) in pairs){if(text.isBlank())continue;val s=shape10(src,r.box);val bg=bg10(src,r.box);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=bg};c.save();c.clipPath(s.path);c.drawPath(s.path,p);c.restore();draw10(c,s.path,s.bounds,text,bg)}
    return out
}

private fun draw10(c:Canvas,path:Path,b:RectF,text:String,bg:Int){
    val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(lum10(bg)>150)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)};var size=(b.height()*0.07f).coerceIn(14f,34f);var lines:List<String>
    while(true){p.textSize=size;lines=wrap10(text,p,b.width()*0.48f);val h=lines.size*(p.fontMetrics.bottom-p.fontMetrics.top);if(h<=b.height()*0.42f||size<=10f)break;size-=1f}
    val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=b.centerY()-lines.size*lh/2f-p.fontMetrics.top;c.save();c.clipPath(path);for(line in lines){c.drawText(line,b.centerX()-p.measureText(line)/2f,y,p);y+=lh};c.restore()
}
private fun wrap10(text:String,p:Paint,w:Float):List<String>{val tokens=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+"))else text.map{it.toString()};val out=mutableListOf<String>();var cur="";for(t in tokens){val next=if(cur.isEmpty())t else "$cur $t";if(p.measureText(next)<=w)cur=next else{if(cur.isNotEmpty())out+=cur;if(p.measureText(t)<=w)cur=t else{var part="";for(ch in t){if(p.measureText(part+ch)<=w)part+=ch else{if(part.isNotEmpty())out+=part;part=ch.toString()}};cur=part}}};if(cur.isNotEmpty())out+=cur;return out}

@Composable private fun V10App(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open&&pages.isNotEmpty())V10Reader(pages){open=false}else V10Home{picker.launch(arrayOf("image/*"))}}
@Composable private fun V10Home(onOpen:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}}}}

@Composable private fun V10Reader(uris:List<Uri>,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var ox by remember{mutableStateOf(0f)};var oy by remember{mutableStateOf(0f)}
    LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;ox=0f;oy=0f;status=""}
    DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}}
    val shown=translated?:bitmap
    Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={
        scope.launch{
            status="Processando…";val src=bitmap
            if(src!=null){
                val result=withContext(Dispatchers.Default){val o=V10Ocr();val tr=V10Translator();try{val blocks=o.run(src);val regions=group10(blocks);val pairs=regions.mapNotNull{r->try{val t=tr.tr(r.text,r.script);if(t.isBlank())null else r to t}catch(_:Throwable){null}};render10(src,pairs) to pairs.size}finally{o.close();tr.close()}}
                translated=result.first;status="${result.second} região(ões) traduzida(s)."
            }
        }
    }){Text("Traduzir")}})}){p->
        Box(Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surface)){
            if(shown!=null)Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);ox+=pan.x;oy+=pan.y}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=ox;translationY=oy})
            if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=72.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal=24.dp,vertical=12.dp))
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=index>0,onClick={index--}){Text("Anterior")};Button(enabled=index<uris.lastIndex,onClick={index++}){Text("Próxima")}}
        }
    }
}
