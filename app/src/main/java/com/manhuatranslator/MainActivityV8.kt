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
import kotlin.math.max
import kotlin.math.min

enum class V8Script { CHINESE, JAPANESE, KOREAN, LATIN }
data class V8Block(val text:String,val box:Rect,val confidence:Float,val script:V8Script)
data class V8Region(val box:Rect,val text:String,val script:V8Script)

class MainActivityV8:ComponentActivity(){
    override fun onCreate(state:Bundle?){super.onCreate(state);setContent{V8App()}}
}

private class V8Ocr:AutoCloseable{
    private data class R(val script:V8Script,val recognizer:TextRecognizer)
    private val recognizers=listOf(
        R(V8Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        R(V8Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        R(V8Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        R(V8Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )

    suspend fun run(src:Bitmap):List<V8Block>{
        val all=mutableListOf<V8Block>()
        for(rotation in intArrayOf(0,90,270)){
            val image=if(rotation==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rotation.toFloat())},true)
            try{
                for(item in recognizers){
                    val result=item.recognizer.process(InputImage.fromBitmap(image,0)).await()
                    for(block in result.textBlocks) for(line in block.lines){
                        val text=line.text.trim()
                        val box=line.boundingBox ?: continue
                        if(text.isEmpty()) continue
                        val confidence=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat() ?: 0.5f
                        all += V8Block(text,mapBox(box,rotation,src.width,src.height),confidence,item.script)
                    }
                }
            }finally{if(image!==src)image.recycle()}
        }
        return select(all)
    }

    private fun select(input:List<V8Block>):List<V8Block>{
        val cjk=input.filter{it.script!=V8Script.LATIN && it.text.count(::isCjk)>=1}
        val sorted=input.sortedByDescending{score(it)}
        val result=mutableListOf<V8Block>()
        for(item in sorted){
            if(item.text.count{it.isLetter()}<2) continue
            if(item.script==V8Script.LATIN && cjk.any{overlap(it.box,item.box)>=0.20f && it.confidence>=item.confidence*0.35f}) continue
            if(result.none{overlap(it.box,item.box)>=0.55f}) result += item
        }
        return result.sortedWith(compareBy<V8Block>{it.box.top}.thenBy{it.box.left})
    }

    private fun score(b:V8Block):Double{
        val cjk=b.text.count(::isCjk)
        val letters=b.text.count{it.isLetter()}
        val latinPenalty=if(b.script==V8Script.LATIN && cjk==0) 0.0 else 15.0
        return b.confidence*120.0+cjk*55.0+letters*2.0+latinPenalty
    }

    private fun isCjk(c:Char)=c.code in 0x3040..0x30ff || c.code in 0x3400..0x9fff || c.code in 0xac00..0xd7af
    private fun overlap(a:Rect,b:Rect):Float{
        val left=max(a.left,b.left);val top=max(a.top,b.top);val right=min(a.right,b.right);val bottom=min(a.bottom,b.bottom)
        if(right<=left||bottom<=top)return 0f
        val inter=(right-left).toLong()*(bottom-top)
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

private fun group8(blocks:List<V8Block>):List<V8Region>{
    val regions=mutableListOf<V8Region>()
    for(block in blocks){
        val idx=regions.indexOfFirst{region->
            val verticalGap=max(0,max(region.box.top,block.box.top)-min(region.box.bottom,block.box.bottom))
            val horizontalGap=max(0,max(region.box.left,block.box.left)-min(region.box.right,block.box.right))
            val yClose=verticalGap.toFloat()<=max(45f,max(region.box.height(),block.box.height()).toFloat()*1.8f)
            val xClose=horizontalGap.toFloat()<=max(30f,min(region.box.width(),block.box.width()).toFloat()*0.6f)
            (yClose && abs(region.box.centerX()-block.box.centerX())<=max(region.box.width(),block.box.width())*1.5f) ||
            (xClose && abs(region.box.centerY()-block.box.centerY())<=max(80f,max(region.box.height(),block.box.height()).toFloat()*3f))
        }
        if(idx<0) regions += V8Region(Rect(block.box),block.text,block.script)
        else{
            val old=regions[idx]
            val mergedText=(old.text+" "+block.text).replace(Regex("\\s+")," ").trim()
            val script=when{
                mergedText.any{it.code in 0xac00..0xd7af}->V8Script.KOREAN
                mergedText.any{it.code in 0x3040..0x30ff}->V8Script.JAPANESE
                mergedText.any{it.code in 0x3400..0x9fff}->V8Script.CHINESE
                else->old.script
            }
            regions[idx]=V8Region(Rect(old.box).apply{union(block.box)},mergedText,script)
        }
    }
    return regions
}

private class V8Translator:AutoCloseable{
    private val clients=mutableMapOf<String,Translator>()
    suspend fun translate(text:String,script:V8Script):String{
        val source=when(script){V8Script.CHINESE->"zh";V8Script.JAPANESE->"ja";V8Script.KOREAN->"ko";V8Script.LATIN->"en"}
        val client=clients.getOrPut(source){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build())}
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return client.translate(text).await().trim()
    }
    override fun close(){clients.values.forEach{it.close()};clients.clear()}
}

private fun luminance(color:Int)=0.299*Color.red(color)+0.587*Color.green(color)+0.114*Color.blue(color)
private fun sampleBackground(bitmap:Bitmap,box:Rect):Int{
    val left=max(0,box.left-box.width()/2);val top=max(0,box.top-box.height()/2)
    val right=min(bitmap.width-1,box.right+box.width()/2);val bottom=min(bitmap.height-1,box.bottom+box.height()/2)
    val pixels=mutableListOf<Int>()
    val stepX=max(4,(right-left)/20);val stepY=max(4,(bottom-top)/20)
    var y=top
    while(y<=bottom){var x=left;while(x<=right){val c=bitmap.getPixel(x,y);if(luminance(c)>=225)pixels+=c;x+=stepX};y+=stepY}
    if(pixels.isEmpty())return Color.WHITE
    val rs=pixels.map{Color.red(it)}.sorted();val gs=pixels.map{Color.green(it)}.sorted();val bs=pixels.map{Color.blue(it)}.sorted();val m=pixels.size/2
    return if(rs[m]>=235&&gs[m]>=235&&bs[m]>=235)Color.WHITE else Color.rgb(rs[m],gs[m],bs[m])
}

private fun safeArea8(bitmap:Bitmap,box:Rect):Pair<Path,RectF>{
    val padX=(box.width()*0.38f).coerceAtLeast(18f)
    val padY=(box.height()*0.42f).coerceAtLeast(18f)
    val rect=RectF((box.left-padX).coerceAtLeast(2f),(box.top-padY).coerceAtLeast(2f),(box.right+padX).coerceAtMost(bitmap.width-2f),(box.bottom+padY).coerceAtMost(bitmap.height-2f))
    val path=Path();path.addOval(rect,Path.Direction.CW)
    return path to rect
}

private fun render8(src:Bitmap,pairs:List<Pair<V8Region,String>>):Bitmap{
    val out=src.copy(Bitmap.Config.ARGB_8888,true);val canvas=Canvas(out)
    for((region,text) in pairs){
        if(text.isBlank())continue
        val (path,bounds)=safeArea8(src,region.box)
        val bg=sampleBackground(src,region.box)
        val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=bg}
        canvas.save();canvas.clipPath(path);canvas.drawPath(path,fill);canvas.restore()
        drawText8(canvas,path,bounds,text,bg)
    }
    return out
}

private fun drawText8(canvas:Canvas,path:Path,bounds:RectF,text:String,bg:Int){
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(luminance(bg)>150)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)}
    var size=(bounds.height()*0.09f).coerceIn(15f,42f);var lines:List<String>
    while(true){
        paint.textSize=size;lines=wrap8(text,paint,bounds.width()*0.60f)
        val height=lines.size*(paint.fontMetrics.bottom-paint.fontMetrics.top)
        if(height<=bounds.height()*0.58f||size<=10f)break
        size-=1f
    }
    val lineHeight=paint.fontMetrics.bottom-paint.fontMetrics.top
    var y=bounds.centerY()-lines.size*lineHeight/2f-paint.fontMetrics.top
    canvas.save();canvas.clipPath(path)
    for(line in lines){canvas.drawText(line,bounds.centerX()-paint.measureText(line)/2f,y,paint);y+=lineHeight}
    canvas.restore()
}

private fun wrap8(text:String,paint:Paint,width:Float):List<String>{
    val tokens=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+")) else text.map{it.toString()}
    val result=mutableListOf<String>();var current=""
    for(token in tokens){
        val next=if(current.isEmpty())token else "$current $token"
        if(paint.measureText(next)<=width)current=next
        else{
            if(current.isNotEmpty())result+=current
            if(paint.measureText(token)<=width)current=token
            else{
                var part=""
                for(ch in token){
                    if(paint.measureText(part+ch)<=width)part+=ch
                    else{if(part.isNotEmpty())result+=part;part=ch.toString()}
                }
                current=part
            }
        }
    }
    if(current.isNotEmpty())result+=current
    return result
}

@Composable private fun V8App(){
    var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}}
    if(open&&pages.isNotEmpty())V8Reader(pages){open=false}else V8Home{picker.launch(arrayOf("image/*"))}
}

@Composable private fun V8Home(onOpen:()->Unit){
    Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){padding->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}
        }
    }
}

@Composable private fun V8Reader(uris:List<Uri>,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var offsetX by remember{mutableStateOf(0f)};var offsetY by remember{mutableStateOf(0f)}
    LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;offsetX=0f;offsetY=0f;status=""}
    DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}}
    val shown=translated?:bitmap
    Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={scope.launch{
        status="Processando…";val source=bitmap
        if(source!=null)withContext(Dispatchers.Default){val ocr=V8Ocr();val translator=V8Translator();try{val blocks=ocr.run(source);val regions=group8(blocks);val pairs=regions.mapNotNull{region->try{val t=translator.translate(region.text,region.script);if(t.isBlank())null else region to t}catch(_:Throwable){null}};val result=render8(source,pairs);withContext(Dispatchers.Main){translated=result;status="${pairs.size} região(ões) traduzida(s)."}}finally{ocr.close();translator.close()}}
    }}){Text("Traduzir")}})}){padding->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface)){
            if(shown!=null)Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);offsetX+=pan.x;offsetY+=pan.y}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=offsetX;translationY=offsetY})
            if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=72.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal=24.dp,vertical=12.dp))
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=index>0,onClick={index--}){Text("Anterior")};Button(enabled=index<uris.lastIndex,onClick={index++}){Text("Próxima")}}
        }
    }
}
