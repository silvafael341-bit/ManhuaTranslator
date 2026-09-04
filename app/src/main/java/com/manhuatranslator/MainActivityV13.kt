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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class V13Script { CHINESE, JAPANESE, KOREAN, LATIN }
private data class V13Block(val text:String,val box:Rect,val confidence:Float,val script:V13Script)
private data class V13Region(val box:Rect,val text:String,val script:V13Script)
private data class V13Mask(val bitmap:Bitmap,val originX:Int,val originY:Int,val bounds:Rect)

class MainActivityV13:ComponentActivity(){
    override fun onCreate(state:Bundle?){super.onCreate(state);setContent{V13App()}}
}

private class V13Ocr:AutoCloseable{
    private data class R(val script:V13Script,val recognizer:TextRecognizer)
    private val rs=listOf(
        R(V13Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        R(V13Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        R(V13Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        R(V13Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )
    suspend fun run(src:Bitmap):List<V13Block>{
        val all=mutableListOf<V13Block>()
        for(rot in intArrayOf(0,90,270)){
            val img=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true)
            try{
                for(r in rs){
                    val result=r.recognizer.process(InputImage.fromBitmap(img,0)).await()
                    for(tb in result.textBlocks) for(line in tb.lines){
                        val raw=line.text.trim()
                        val q=line.boundingBox?:continue
                        val cjk=countCjk13(raw)
                        if(raw.count{it.isLetter()}<2 && cjk<1) continue
                        val conf=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:0.5f
                        all+=V13Block(raw,map13(q,rot,src.width,src.height),conf,r.script)
                    }
                }
            }finally{if(img!==src)img.recycle()}
        }
        return choose13(all)
    }
    private fun choose13(input:List<V13Block>):List<V13Block>{
        val cjk=input.filter{it.script!=V13Script.LATIN && countCjk13(it.text)>0}
        val pool=if(cjk.isNotEmpty()) cjk else input
        val sorted=pool.sortedByDescending{it.confidence*100f+countCjk13(it.text)*180f+it.text.length.coerceAtMost(40)}
        val out=mutableListOf<V13Block>()
        for(x in sorted){
            if(out.none{overlap13(it.box,x.box)>=0.45f}) out+=x
        }
        return out.sortedWith(compareBy<V13Block>{it.box.top}.thenBy{it.box.left})
    }
    private fun overlap13(a:Rect,b:Rect):Float{
        val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom)
        if(r<=l||d<=t)return 0f
        val i=(r-l).toLong()*(d-t).toLong();val ar=min(a.width().toLong()*a.height().toLong(),b.width().toLong()*b.height().toLong())
        return if(ar<=0L)0f else i.toFloat()/ar.toFloat()
    }
    private fun map13(q:Rect,rot:Int,w:Int,h:Int):Rect{
        if(rot==0)return Rect(q)
        val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x}
        return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))
    }
    override fun close(){rs.forEach{it.recognizer.close()}}
}

private fun countCjk13(s:String)=s.count{it.code in 0x3040..0x30ff||it.code in 0x3400..0x9fff||it.code in 0xac00..0xd7af}

private fun group13(blocks:List<V13Block>):List<V13Region>{
    val out=mutableListOf<V13Region>()
    for(b in blocks){
        val i=out.indexOfFirst{r->
            val vertical=max(0,max(r.box.top,b.box.top)-min(r.box.bottom,b.box.bottom))
            val horizontal=max(0,max(r.box.left,b.box.left)-min(r.box.right,b.box.right))
            val sameCol=abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.4f
            val sameRow=abs(r.box.centerY()-b.box.centerY())<=max(r.box.height(),b.box.height())*2.4f
            (vertical<=max(36f,max(r.box.height(),b.box.height()).toFloat()*1.5f)&&sameCol)||(horizontal<=max(28f,min(r.box.width(),b.box.width()).toFloat()*0.6f)&&sameRow)
        }
        if(i<0)out+=V13Region(Rect(b.box),b.text,b.script) else{
            val r=out[i]
            val text=(r.text+" "+b.text).replace(Regex("\\s+")," ").trim()
            val script=when{
                text.any{it.code in 0xac00..0xd7af}->V13Script.KOREAN
                text.any{it.code in 0x3040..0x30ff}->V13Script.JAPANESE
                text.any{it.code in 0x3400..0x9fff}->V13Script.CHINESE
                else->r.script
            }
            out[i]=V13Region(Rect(r.box).apply{union(b.box)},text,script)
        }
    }
    return out
}

private class V13Translator:AutoCloseable{
    private val clients=mutableMapOf<String,Translator>()
    suspend fun tr(text:String,script:V13Script):String{
        val source=when(script){V13Script.CHINESE->"zh";V13Script.JAPANESE->"ja";V13Script.KOREAN->"ko";V13Script.LATIN->"en"}
        val c=clients.getOrPut(source){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build())}
        c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return c.translate(text).await().trim()
    }
    override fun close(){clients.values.forEach{it.close()};clients.clear()}
}

private fun colorDistance13(a:Int,b:Int):Int=abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b))
private fun lum13(c:Int):Float=(0.299f*Color.red(c)+0.587f*Color.green(c)+0.114f*Color.blue(c))

private fun estimateBg13(bitmap:Bitmap,box:Rect):Int{
    val l=max(0,box.left-box.width());val t=max(0,box.top-box.height()*2);val r=min(bitmap.width-1,box.right+box.width());val d=min(bitmap.height-1,box.bottom+box.height()*2)
    val values=mutableListOf<Int>();val stepX=max(4,(r-l)/24);val stepY=max(4,(d-t)/24)
    var y=t
    while(y<=d){var x=l;while(x<=r){if(x<box.left-6||x>box.right+6||y<box.top-6||y>box.bottom+6)values+=bitmap.getPixel(x,y);x+=stepX};y+=stepY}
    if(values.isEmpty())return Color.WHITE
    val bright=values.filter{lum13(it)>180f};val use=if(bright.size>=values.size/5)bright else values
    val rs=use.map{Color.red(it)}.sorted();val gs=use.map{Color.green(it)}.sorted();val bs=use.map{Color.blue(it)}.sorted();val m=use.size/2
    return Color.rgb(rs[m],gs[m],bs[m])
}

private fun makeMask13(src:Bitmap,box:Rect):V13Mask?{
    val padX=max(80,box.width()*3);val padY=max(120,box.height()*6)
    val l=max(0,box.left-padX);val t=max(0,box.top-padY);val r=min(src.width,box.right+padX);val b=min(src.height,box.bottom+padY)
    val w=r-l;val h=b-t;if(w<10||h<10)return null
    val bg=estimateBg13(src,box);val data=BooleanArray(w*h)
    for(y in 0 until h){val sy=t+y;for(x in 0 until w){val c=src.getPixel(l+x,sy);val d=colorDistance13(c,bg);val ld=abs(lum13(c)-lum13(bg));data[y*w+x]=(d<=78&&ld<=48)||(lum13(bg)>210f&&lum13(c)>205f&&d<=105)}}
    repeat(2){
        val dil=data.copyOf()
        for(y in 2 until h-2) for(x in 2 until w-2) if(!data[y*w+x]){var on=false;for(dy in -2..2)for(dx in -2..2)if(data[(y+dy)*w+x+dx])on=true;if(on)dil[y*w+x]=true}
        val ero=dil.copyOf()
        for(y in 2 until h-2) for(x in 2 until w-2){var on=true;for(dy in -2..2)for(dx in -2..2)if(!dil[(y+dy)*w+x+dx])on=false;ero[y*w+x]=on}
        for(i in data.indices)data[i]=ero[i]
    }
    var sx=(box.centerX()-l).coerceIn(0,w-1);var sy=(box.centerY()-t).coerceIn(0,h-1);var best=sx to sy;var bestD=Int.MAX_VALUE
    for(y in max(0,sy-20)..min(h-1,sy+20))for(x in max(0,sx-40)..min(w-1,sx+40))if(data[y*w+x]){val d=abs(x-sx)+abs(y-sy);if(d<bestD){bestD=d;best=x to y}}
    sx=best.first;sy=best.second;if(!data[sy*w+sx])return null
    val seen=BooleanArray(w*h);val queue=IntArray(w*h);var head=0;var tail=0;queue[tail++]=sy*w+sx;seen[sy*w+sx]=true
    var minX=w;var maxX=0;var minY=h;var maxY=0;var count=0
    while(head<tail){val idx=queue[head++];val y=idx/w;val x=idx-y*w;count++;minX=min(minX,x);maxX=max(maxX,x);minY=min(minY,y);maxY=max(maxY,y)
        if(x>0){val n=idx-1;if(data[n]&&!seen[n]){seen[n]=true;queue[tail++]=n}};if(x+1<w){val n=idx+1;if(data[n]&&!seen[n]){seen[n]=true;queue[tail++]=n}};if(y>0){val n=idx-w;if(data[n]&&!seen[n]){seen[n]=true;queue[tail++]=n}};if(y+1<h){val n=idx+w;if(data[n]&&!seen[n]){seen[n]=true;queue[tail++]=n}}
    }
    if(count<max(100,box.width()*box.height()/4)||count>w*h*0.82)return null
    val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val mp=IntArray(w*h);for(i in mp.indices)if(seen[i])mp[i]=Color.WHITE;mask.setPixels(mp,0,w,0,0,w,h)
    return V13Mask(mask,l,t,Rect(l+minX,t+minY,l+maxX+1,t+maxY+1))
}

private fun replaceWithMask13(bitmap:Bitmap,mask:V13Mask,color:Int){
    val w=mask.bitmap.width;val h=mask.bitmap.height;val pixels=IntArray(w*h);mask.bitmap.getPixels(pixels,0,w,0,0,w,h);val old=IntArray(w*h);bitmap.getPixels(old,0,w,mask.originX,mask.originY,w,h)
    for(i in pixels.indices)if(Color.alpha(pixels[i])>0)old[i]=color
    bitmap.setPixels(old,0,w,mask.originX,mask.originY,w,h)
}

private fun renderText13(canvas:Canvas,mask:V13Mask,text:String,bg:Int){
    val b=RectF(mask.bounds);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(lum13(bg)>150f)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)}
    var size=(b.height()*0.055f).coerceIn(16f,32f);var lines:List<String>
    while(true){paint.textSize=size;lines=wrap13(text,paint,b.width()*0.55f);val h=lines.size*(paint.fontMetrics.bottom-paint.fontMetrics.top);if(h<=b.height()*0.48f||size<=11f)break;size-=1f}
    val lineH=paint.fontMetrics.bottom-paint.fontMetrics.top;var y=b.centerY()-lines.size*lineH/2f-paint.fontMetrics.top
    for(line in lines){canvas.drawText(line,b.centerX()-paint.measureText(line)/2f,y,paint);y+=lineH}
}

private fun wrap13(text:String,p:Paint,maxW:Float):List<String>{
    val tokens=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+"))else text.map{it.toString()};val out=mutableListOf<String>();var cur=""
    for(token in tokens){val next=if(cur.isEmpty())token else "$cur $token";if(p.measureText(next)<=maxW)cur=next else{if(cur.isNotEmpty())out+=cur;if(p.measureText(token)<=maxW)cur=token else{var part="";for(ch in token){if(p.measureText(part+ch)<=maxW)part+=ch else{if(part.isNotEmpty())out+=part;part=ch.toString()}};cur=part}}}
    if(cur.isNotEmpty())out+=cur;return out
}

private fun render13(src:Bitmap,pairs:List<Pair<V13Region,String>>):Bitmap{
    val out=src.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(out)
    for((region,text) in pairs){val mask=makeMask13(src,region.box)?:continue;val bg=estimateBg13(src,region.box);replaceWithMask13(out,mask,bg);renderText13(c,mask,text,bg);mask.bitmap.recycle()}
    return out
}

@Composable private fun V13App(){
    var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}}
    if(open&&pages.isNotEmpty())V13Reader(pages){open=false}else V13Home{picker.launch(arrayOf("image/*"))}
}

@Composable private fun V13Home(onOpen:()->Unit){
    Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}}}
}

@Composable private fun V13Reader(uris:List<Uri>,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var ox by remember{mutableStateOf(0f)};var oy by remember{mutableStateOf(0f)}
    LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;ox=0f;oy=0f;status=""}
    DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}}
    val shown=translated?:bitmap
    Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={scope.launch{status="Processando…";val src=bitmap;if(src!=null){val result=withContext(Dispatchers.Default){val o=V13Ocr();val tr=V13Translator();try{val blocks=o.run(src);val regions=group13(blocks);val pairs=regions.mapNotNull{r->try{val t=tr.tr(r.text,r.script);if(t.isBlank())null else r to t}catch(_:Throwable){null}};render13(src,pairs) to pairs.size}finally{o.close();tr.close()}};translated=result.first;status="${result.second} região(ões) traduzida(s)."}}}){Text("Traduzir")}})}){p->Box(Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surface)){
        if(shown!=null)Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=scale,scaleY=scale,translationX=ox,translationY=oy).pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);ox+=pan.x;oy+=pan.y}})
        if(status.isNotEmpty())Text(status,Modifier.align(Alignment.BottomCenter).padding(16.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=18.dp,vertical=10.dp))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=index>0,onClick={index--;translated=null}){Text("Anterior")};Button(enabled=index<uris.lastIndex,onClick={index++;translated=null}){Text("Próxima")}}
    }}
}
