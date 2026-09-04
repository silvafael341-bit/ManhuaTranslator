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
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.Translation
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

private enum class V9Script { CHINESE, JAPANESE, KOREAN, LATIN }
private data class V9Block(val text:String,val box:Rect,val confidence:Float,val script:V9Script)
private data class V9Region(val box:Rect,val text:String,val script:V9Script)

class MainActivityV9:ComponentActivity(){
    override fun onCreate(state:Bundle?){super.onCreate(state);setContent{V9App()}}
}

private class V9Ocr:AutoCloseable{
    private data class R(val script:V9Script,val recognizer:TextRecognizer)
    private val recognizers=listOf(
        R(V9Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        R(V9Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        R(V9Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        R(V9Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )
    suspend fun run(src:Bitmap):List<V9Block>{
        val all=mutableListOf<V9Block>()
        for(rotation in intArrayOf(0,90,270)){
            val image=if(rotation==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rotation.toFloat())},true)
            try{
                for(item in recognizers){
                    val result=item.recognizer.process(InputImage.fromBitmap(image,0)).await()
                    for(block in result.textBlocks) for(line in block.lines){
                        val raw=line.text.trim();val box=line.boundingBox ?: continue
                        if(raw.isEmpty())continue
                        val conf=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:0.5f
                        val text=cleanV9(raw,item.script)
                        if(text.count{it.isLetter()}<2)continue
                        all += V9Block(text,mapBox(box,rotation,src.width,src.height),conf,item.script)
                    }
                }
            }finally{if(image!==src)image.recycle()}
        }
        return selectV9(all)
    }
    private fun cleanV9(text:String,script:V9Script):String{
        if(script==V9Script.LATIN)return text
        return text.filter{c->c.isLetterOrDigit()||c.isWhitespace()||c in "，。！？、：；（）《》“”‘’…－-"}
            .replace(Regex("[A-Za-z]{2,}"),"")
            .replace(Regex("\\s+")," ").trim()
    }
    private fun selectV9(input:List<V9Block>):List<V9Block>{
        val cjk=input.filter{it.script!=V9Script.LATIN&&it.text.count(::isCjk)>=2}
        val sorted=input.sortedByDescending{scoreV9(it)}
        val result=mutableListOf<V9Block>()
        for(item in sorted){
            if(item.text.count{it.isLetter()}<2)continue
            val nearCjk=cjk.any{overlapV9(it.box,item.box)>=0.08f||nearV9(it.box,item.box)}
            if(item.script==V9Script.LATIN&&nearCjk)continue
            if(result.none{overlapV9(it.box,item.box)>=0.50f})result+=item
        }
        return result.sortedWith(compareBy<V9Block>{it.box.top}.thenBy{it.box.left})
    }
    private fun scoreV9(b:V9Block):Double{
        val cjk=b.text.count(::isCjk)
        val letters=b.text.count{it.isLetter()}
        val scriptBonus=if(b.script!=V9Script.LATIN&&cjk>=2)35.0 else 0.0
        return b.confidence*100.0+cjk*75.0+letters*1.5+scriptBonus
    }
    private fun isCjk(c:Char)=c.code in 0x3040..0x30ff||c.code in 0x3400..0x9fff||c.code in 0xac00..0xd7af
    private fun nearV9(a:Rect,b:Rect):Boolean{
        val ax=a.centerX();val ay=a.centerY();val bx=b.centerX();val by=b.centerY()
        val dx=abs(ax-bx);val dy=abs(ay-by)
        return dx<=max(a.width(),b.width())*1.2f&&dy<=max(a.height(),b.height())*1.8f
    }
    private fun overlapV9(a:Rect,b:Rect):Float{
        val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom)
        if(r<=l||d<=t)return 0f
        val inter=(r-l).toLong()*(d-t)
        val area=min(a.width().toLong()*a.height(),b.width().toLong()*b.height())
        return if(area<=0L)0f else inter.toFloat()/area.toFloat()
    }
    private fun mapBox(q:Rect,rotation:Int,w:Int,h:Int):Rect{
        if(rotation==0)return Rect(q)
        val pts=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rotation==90)y to h-x else w-y to x}
        return Rect(pts.minOf{it.first}.coerceIn(0,w),pts.minOf{it.second}.coerceIn(0,h),pts.maxOf{it.first}.coerceIn(0,w),pts.maxOf{it.second}.coerceIn(0,h))
    }
    override fun close(){recognizers.forEach{it.recognizer.close()}}
}

private fun groupV9(blocks:List<V9Block>):List<V9Region>{
    val regions=mutableListOf<V9Region>()
    for(b in blocks){
        val idx=regions.indexOfFirst{r->
            val vg=max(0,max(r.box.top,b.box.top)-min(r.box.bottom,b.box.bottom))
            val hg=max(0,max(r.box.left,b.box.left)-min(r.box.right,b.box.right))
            val sameColumn=abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.35f
            val sameRow=abs(r.box.centerY()-b.box.centerY())<=max(r.box.height(),b.box.height())*2.4f
            (vg<=max(35f,max(r.box.height(),b.box.height()).toFloat()*1.45f)&&sameColumn)||
            (hg<=max(25f,min(r.box.width(),b.box.width()).toFloat()*0.55f)&&sameRow)
        }
        if(idx<0)regions+=V9Region(Rect(b.box),b.text,b.script)
        else{
            val old=regions[idx]
            val merged=(old.text+" "+b.text).replace(Regex("\\s+")," ").trim()
            val script=when{
                merged.any{it.code in 0xac00..0xd7af}->V9Script.KOREAN
                merged.any{it.code in 0x3040..0x30ff}->V9Script.JAPANESE
                merged.any{it.code in 0x3400..0x9fff}->V9Script.CHINESE
                else->old.script
            }
            regions[idx]=V9Region(Rect(old.box).apply{union(b.box)},merged,script)
        }
    }
    return regions
}

private class V9Translator:AutoCloseable{
    private val clients=mutableMapOf<String,Translator>()
    suspend fun translate(text:String,script:V9Script):String{
        val source=when(script){V9Script.CHINESE->"zh";V9Script.JAPANESE->"ja";V9Script.KOREAN->"ko";V9Script.LATIN->"en"}
        val client=clients.getOrPut(source){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build())}
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return client.translate(text).await().trim()
    }
    override fun close(){clients.values.forEach{it.close()};clients.clear()}
}

private fun lum9(c:Int)=0.299*Color.red(c)+0.587*Color.green(c)+0.114*Color.blue(c)
private fun bg9(bitmap:Bitmap,box:Rect):Int{
    val l=max(0,box.left-box.width()/3);val t=max(0,box.top-box.height()/3)
    val r=min(bitmap.width-1,box.right+box.width()/3);val b=min(bitmap.height-1,box.bottom+box.height()/3)
    val values=mutableListOf<Int>();val sx=max(3,(r-l)/24);val sy=max(3,(b-t)/24)
    var y=t
    while(y<=b){var x=l;while(x<=r){val c=bitmap.getPixel(x,y);if(lum9(c)>=215)values+=c;x+=sx};y+=sy}
    if(values.isEmpty())return Color.WHITE
    val rs=values.map{Color.red(it)}.sorted();val gs=values.map{Color.green(it)}.sorted();val bs=values.map{Color.blue(it)}.sorted();val m=values.size/2
    return if(rs[m]>=232&&gs[m]>=232&&bs[m]>=232)Color.WHITE else Color.rgb(rs[m],gs[m],bs[m])
}

private data class V9Shape(val path:Path,val bounds:RectF)
private fun shapeV9(bitmap:Bitmap,box:Rect):V9Shape{
    val cx=box.centerX().toFloat();val cy=box.centerY().toFloat();val maxR=max(box.width(),box.height()).toFloat()*1.45f;val bg=bg9(bitmap,box);val points=ArrayList<Pair<Float,Float>>();val rays=72
    for(i in 0 until rays){
        val a=(i.toFloat()/rays)*2f*PI.toFloat();val dx=cos(a);val dy=sin(a);var last=12f;var darkRun=0;var radius=12f
        while(radius<=maxR){
            val x=(cx+dx*radius).toInt();val y=(cy+dy*radius).toInt();if(x<1||y<1||x>=bitmap.width-1||y>=bitmap.height-1)break
            val c=bitmap.getPixel(x,y);val nearBg=abs(Color.red(c)-Color.red(bg))<65&&abs(Color.green(c)-Color.green(bg))<65&&abs(Color.blue(c)-Color.blue(bg))<65
            if(!nearBg&&lum9(c)<180)darkRun++ else darkRun=max(0,darkRun-1)
            if(darkRun>=4){last=radius-4f;break};last=radius;radius+=2f
        }
        points+=cx+dx*last to cy+dy*last
    }
    val smoothed=points.mapIndexed{idx,p->val p0=points[(idx+rays-2)%rays];val p1=points[(idx+rays-1)%rays];val p2=points[(idx+1)%rays];val p3=points[(idx+2)%rays];((p0.first+p1.first+p.first+p2.first+p3.first)/5f) to ((p0.second+p1.second+p.second+p2.second+p3.second)/5f)}
    val path=Path();path.moveTo(smoothed[0].first,smoothed[0].second);for(p in smoothed.drop(1))path.lineTo(p.first,p.second);path.close()
    val bounds=RectF(box.left.toFloat(),box.top.toFloat(),box.right.toFloat(),box.bottom.toFloat()).apply{inset(-box.width()*0.55f,-box.height()*0.55f)}
    return V9Shape(path,bounds)
}

private fun renderV9(src:Bitmap,pairs:List<Pair<V9Region,String>>):Bitmap{
    val out=src.copy(Bitmap.Config.ARGB_8888,true);val canvas=Canvas(out)
    for((region,text) in pairs){if(text.isBlank())continue;val shape=shapeV9(src,region.box);val bg=bg9(src,region.box);val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=bg};canvas.save();canvas.clipPath(shape.path);canvas.drawPath(shape.path,fill);canvas.restore();textV9(canvas,shape.path,shape.bounds,text,bg)}
    return out
}

private fun textV9(canvas:Canvas,path:Path,shapeBounds:RectF,text:String,bg:Int){
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(lum9(bg)>150)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)};var size=(shapeBounds.height()*0.075f).coerceIn(14f,38f);var lines:List<String>
    while(true){paint.textSize=size;lines=wrapV9(text,paint,shapeBounds.width()*0.46f);val h=lines.size*(paint.fontMetrics.bottom-paint.fontMetrics.top);if(h<=shapeBounds.height()*0.42f||size<=10f)break;size-=1f}
    val lh=paint.fontMetrics.bottom-paint.fontMetrics.top;var y=shapeBounds.centerY()-lines.size*lh/2f-paint.fontMetrics.top;canvas.save();canvas.clipPath(path);for(line in lines){canvas.drawText(line,shapeBounds.centerX()-paint.measureText(line)/2f,y,paint);y+=lh};canvas.restore()
}
private fun wrapV9(text:String,paint:Paint,width:Float):List<String>{
    val tokens=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+")) else text.map{it.toString()};val out=mutableListOf<String>();var cur=""
    for(token in tokens){val next=if(cur.isEmpty())token else "$cur $token";if(paint.measureText(next)<=width)cur=next else{if(cur.isNotEmpty())out+=cur;if(paint.measureText(token)<=width)cur=token else{var part="";for(ch in token){if(paint.measureText(part+ch)<=width)part+=ch else{if(part.isNotEmpty())out+=part;part=ch.toString()}};cur=part}}}
    if(cur.isNotEmpty())out+=cur;return out
}

@Composable private fun V9App(){
    var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}}
    if(open&&pages.isNotEmpty())V9Reader(pages){open=false}else V9Home{picker.launch(arrayOf("image/*"))}
}
@Composable private fun V9Home(onOpen:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}}}}
@Composable private fun V9Reader(uris:List<Uri>,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var ox by remember{mutableStateOf(0f)};var oy by remember{mutableStateOf(0f)}
    LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;ox=0f;oy=0f;status=""}
    DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}}
    val shown=translated?:bitmap
    Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={scope.launch{status="Processando…";val src=bitmap;if(src!=null)withContext(Dispatchers.Default){val o=V9Ocr();val tr=V9Translator();try{val blocks=o.run(src);val regions=groupV9(blocks);val pairs=regions.mapNotNull{r->try{val t=tr.translate(r.text,r.script);if(t.isBlank())null else r to t}catch(_:Throwable){null}};val result=renderV9(src,pairs);withContext(Dispatchers.Main){translated=result;status="${pairs.size} região(ões) traduzida(s)."}}finally{o.close();tr.close()}}}){Text("Traduzir")}})}){p->Box(Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surface)){if(shown!=null)Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);ox+=pan.x;oy+=pan.y}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=ox;translationY=oy}};if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=72.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal=24.dp,vertical=12.dp));Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=index>0,onClick={index--}){Text("Anterior")};Button(enabled=index<uris.lastIndex,onClick={index++}){Text("Próxima")}}}}
}
