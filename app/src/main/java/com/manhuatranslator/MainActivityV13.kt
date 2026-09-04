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

private enum class S13 { ZH, JA, KO, EN }
private data class B13(val text:String,val box:Rect,val confidence:Float,val script:S13)
private data class R13(val box:Rect,val text:String,val script:S13)
private data class M13(val mask:BooleanArray,val left:Int,val top:Int,val width:Int,val height:Int,val bounds:RectF)

class MainActivityV13:ComponentActivity(){
    override fun onCreate(state:Bundle?){super.onCreate(state);setContent{App13()}}
}

private fun cjk13(s:String):Int=s.count{it.code in 0x3040..0x30ff||it.code in 0x3400..0x9fff||it.code in 0xac00..0xd7af}
private fun score13(b:B13):Float=b.confidence*100f+cjk13(b.text)*45f+b.text.count{it.isLetterOrDigit()}*0.5f

private class Ocr13:AutoCloseable{
    private data class X(val s:S13,val r:TextRecognizer)
    private val xs=listOf(
        X(S13.ZH,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
        X(S13.JA,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        X(S13.KO,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        X(S13.EN,TextRecognition.getClient(TextRecognizerOptions.Builder().build()))
    )
    suspend fun run(src:Bitmap):List<B13>{
        val all=mutableListOf<B13>()
        for(rot in intArrayOf(0,90,270)){
            val img=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true)
            try{
                for(x in xs){
                    val res=x.r.process(InputImage.fromBitmap(img,0)).await()
                    for(tb in res.textBlocks) for(line in tb.lines){
                        val t=line.text.trim(); val q=line.boundingBox?:continue
                        val cc=cjk13(t)
                        if(t.length<2)continue
                        val conf=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:0.5f
                        val box=map13(q,rot,src.width,src.height)
                        if(x.s==S13.EN && cc==0 && t.filter{it.isLetter()}.length<3)continue
                        all+=B13(t,box,conf,x.s)
                    }
                }
            }finally{if(img!==src)img.recycle()}
        }
        val cjk=all.filter{it.script!=S13.EN&&cjk13(it.text)>=1}
        val sorted=all.sortedByDescending(::score13)
        val out=mutableListOf<B13>()
        for(b in sorted){
            val duplicate=out.any{over13(it.box,b.box)>0.45f}
            if(duplicate)continue
            if(b.script==S13.EN && cjk.any{over13(it.box,b.box)>0.04f||near13(it.box,b.box)})continue
            out+=b
        }
        return out.sortedWith(compareBy<B13>{it.box.top}.thenBy{it.box.left})
    }
    private fun near13(a:Rect,b:Rect)=abs(a.centerX()-b.centerX())<=max(a.width(),b.width())*1.1f&&abs(a.centerY()-b.centerY())<=max(a.height(),b.height())*1.7f
    private fun over13(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val i=(r-l).toLong()*(d-t);val ar=min(a.width().toLong()*a.height(),b.width().toLong()*b.height());return if(ar<=0)0f else i.toFloat()/ar}
    private fun map13(q:Rect,rot:Int,w:Int,h:Int):Rect{if(rot==0)return Rect(q);val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x};return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))}
    override fun close(){xs.forEach{it.r.close()}}
}

private class Tr13:AutoCloseable{
    private val clients=mutableMapOf<String,Translator>()
    suspend fun go(text:String,s:S13):String{
        val lang=when(s){S13.ZH->"zh";S13.JA->"ja";S13.KO->"ko";S13.EN->"en"}
        val tr=clients.getOrPut(lang){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(lang).setTargetLanguage("pt").build())}
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return tr.translate(text).await().trim()
    }
    override fun close(){clients.values.forEach{it.close()};clients.clear()}
}

private fun lum13(c:Int)=0.299f*Color.red(c)+0.587f*Color.green(c)+0.114f*Color.blue(c)
private fun dist13(a:Int,b:Int)=abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b))

private fun estimateBg13(src:Bitmap,q:Rect):Int{
    val l=max(0,q.left-q.width());val t=max(0,q.top-q.height());val r=min(src.width-1,q.right+q.width());val d=min(src.height-1,q.bottom+q.height())
    val samples=ArrayList<Int>()
    fun add(x:Int,y:Int){if(x in 0 until src.width&&y in 0 until src.height)samples+=src.getPixel(x,y)}
    for(x in l..r step max(2,(r-l)/18)){add(x,t);add(x,d)}
    for(y in t..d step max(2,(d-t)/18)){add(l,y);add(r,y)}
    if(samples.isEmpty())return Color.WHITE
    val bright=samples.filter{lum13(it)>205f}
    val pool=if(bright.size>=max(4,samples.size/5))bright else samples
    val rr=pool.map{Color.red(it)}.sorted();val gg=pool.map{Color.green(it)}.sorted();val bb=pool.map{Color.blue(it)}.sorted();val m=pool.size/2
    return Color.rgb(rr[m],gg[m],bb[m])
}

private fun findSeed13(src:Bitmap,q:Rect,bg:Int):Pair<Int,Int>{
    val l=max(0,q.left-q.width()/3);val t=max(0,q.top-q.height()/3);val r=min(src.width-1,q.right+q.width()/3);val d=min(src.height-1,q.bottom+q.height()/3)
    var best=Pair(q.centerX().coerceIn(0,src.width-1),q.centerY().coerceIn(0,src.height-1));var bd=Int.MAX_VALUE
    for(y in t..d step max(2,(d-t)/24)) for(x in l..r step max(2,(r-l)/24)){
        val c=src.getPixel(x,y);val dd=dist13(c,bg)
        if(dd<bd && lum13(c)>175f){bd=dd;best=x to y}
    }
    return best
}

private fun mask13(src:Bitmap,q:Rect):M13{
    val bg=estimateBg13(src,q);val seed=findSeed13(src,q,bg)
    val marginX=max(18,q.width()/2);val marginY=max(18,q.height()/2)
    val l=max(0,min(q.left,seed.first)-marginX);val t=max(0,min(q.top,seed.second)-marginY);val r=min(src.width-1,max(q.right,seed.first)+marginX);val d=min(src.height-1,max(q.bottom,seed.second)+marginY)
    val w=r-l+1;val h=d-t+1;val ok=BooleanArray(w*h);val seen=BooleanArray(w*h);val queue=IntArray(w*h);var head=0;var tail=0
    val seedX=(seed.first-l).coerceIn(0,w-1);val seedY=(seed.second-t).coerceIn(0,h-1)
    val seedC=src.getPixel(seed.first.coerceIn(0,src.width-1),seed.second.coerceIn(0,src.height-1))
    val base=dist13(seedC,bg);val threshold=max(55,base+55)
    fun allowed(x:Int,y:Int):Boolean{
        val c=src.getPixel(x+l,y+t);val dd=dist13(c,bg);val ld=abs(lum13(c)-lum13(bg))
        return dd<=threshold || (lum13(bg)>185f&&lum13(c)>190f&&ld<55f)
    }
    val si=seedY*w+seedX;seen[si]=true;queue[tail++]=si
    val dirs=intArrayOf(1,0,-1,0,0,1,0,-1)
    while(head<tail){val id=queue[head++];val y=id/w;val x=id%w;ok[id]=true;for(k in 0 until 8 step 2){val nx=x+dirs[k];val ny=y+dirs[k+1];if(nx !in 0 until w||ny !in 0 until h)continue;val ni=ny*w+nx;if(!seen[ni]&&allowed(nx,ny)){seen[ni]=true;queue[tail++]=ni}}}
    repeat(2){
        val copy=ok.clone()
        for(y in 1 until h-1) for(x in 1 until w-1) if(!copy[y*w+x]){
            val n=(if(copy[y*w+x-1])1 else 0)+(if(copy[y*w+x+1])1 else 0)+(if(copy[(y-1)*w+x])1 else 0)+(if(copy[(y+1)*w+x])1 else 0)
            if(n>=3)ok[y*w+x]=true
        }
    }
    var minX=r;var minY=d;var maxX=l;var maxY=t;var count=0
    for(y in 0 until h)for(x in 0 until w)if(ok[y*w+x]){count++;minX=min(minX,x+l);minY=min(minY,y+t);maxX=max(maxX,x+l);maxY=max(maxY,y+t)}
    if(count<max(80,q.width()*q.height()/30)){minX=q.left;minY=q.top;maxX=q.right;maxY=q.bottom}
    return M13(ok,l,t,w,h,RectF(minX.toFloat(),minY.toFloat(),(maxX+1).toFloat(),(maxY+1).toFloat()))
}

private fun fill13(canvas:Canvas,m:M13,color:Int){val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color};for(y in 0 until m.height){var run=-1;for(x in 0 until m.width+1){val on=x<m.width&&m.mask[y*m.width+x];if(on&&run<0)run=x;if((!on||x==m.width)&&run>=0){canvas.drawRect((m.left+run).toFloat(),(m.top+y).toFloat(),(m.left+x).toFloat(),(m.top+y+1).toFloat(),p);run=-1}}}}

private fun wrap13(text:String,p:Paint,width:Float):List<String>{
    val tokens=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+"))else text.map{it.toString()};val out=mutableListOf<String>();var cur=""
    for(tok in tokens){val n=if(cur.isEmpty())tok else "$cur $tok";if(p.measureText(n)<=width)cur=n else{if(cur.isNotEmpty())out+=cur;if(p.measureText(tok)<=width)cur=tok else{var part="";for(ch in tok){if(p.measureText(part+ch)<=width)part+=ch else{if(part.isNotEmpty())out+=part;part=ch.toString()}};cur=part}}}
    if(cur.isNotEmpty())out+=cur;return out
}

private fun drawText13(canvas:Canvas,m:M13,text:String,bg:Int){
    val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(lum13(bg)>150)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)}
    var size=22f;var lines:List<String>;val w=m.bounds.width()*0.78f;val h=m.bounds.height()*0.70f
    while(true){p.textSize=size;lines=wrap13(text,p,w);val total=lines.size*(p.fontMetrics.bottom-p.fontMetrics.top);if(total<=h||size<=10f)break;size-=1f}
    val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=m.bounds.centerY()-lines.size*lh/2f-p.fontMetrics.top
    canvas.save();canvas.clipRect(m.bounds);for(line in lines){canvas.drawText(line,m.bounds.centerX()-p.measureText(line)/2f,y,p);y+=lh};canvas.restore()
}

private fun render13(src:Bitmap,pairs:List<Pair<R13,String>>):Bitmap{
    val out=src.copy(Bitmap.Config.ARGB_8888,true);val canvas=Canvas(out)
    for((r,text) in pairs){if(text.isBlank())continue;val m=mask13(src,r.box);val bg=estimateBg13(src,r.box);fill13(canvas,m,bg);drawText13(canvas,m,text,bg)}
    return out
}

private fun group13(blocks:List<B13>):List<R13>{
    val out=mutableListOf<R13>()
    for(b in blocks){
        val i=out.indexOfFirst{r->
            val sameX=abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.15f
            val sameY=abs(r.box.centerY()-b.box.centerY())<=max(r.box.height(),b.box.height())*2.0f
            val gapY=max(0,max(r.box.top,b.box.top)-min(r.box.bottom,b.box.bottom));val gapX=max(0,max(r.box.left,b.box.left)-min(r.box.right,b.box.right))
            (sameX&&gapY<=max(28,r.box.height()/2))||(sameY&&gapX<=max(28,min(r.box.width(),b.box.width())/2))
        }
        if(i<0)out+=R13(Rect(b.box),b.text,b.script) else{val r=out[i];val txt=(r.text+" "+b.text).replace(Regex("\\s+")," ");out[i]=R13(Rect(r.box).apply{union(b.box)},txt,script13(txt,r.script))}
    }
    return out
}
private fun script13(t:String,old:S13):S13=when{t.any{it.code in 0xac00..0xd7af}->S13.KO;t.any{it.code in 0x3040..0x30ff}->S13.JA;cjk13(t)>0->S13.ZH;else->old}

@Composable private fun App13(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open&&pages.isNotEmpty())Reader13(pages){open=false}else Home13{picker.launch(arrayOf("image/*"))}}
@Composable private fun Home13(onOpen:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}}}}

@Composable private fun Reader13(uris:List<Uri>,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var ox by remember{mutableStateOf(0f)};var oy by remember{mutableStateOf(0f)}
    LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;ox=0f;oy=0f;status=""}
    DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}}
    val shown=translated?:bitmap
    Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={scope.launch{status="Processando…";val src=bitmap;if(src!=null){val result=withContext(Dispatchers.Default){val o=Ocr13();val tr=Tr13();try{val blocks=o.run(src);val regions=group13(blocks);val pairs=regions.mapNotNull{r->try{tr.go(r.text,r.script).takeIf{it.isNotBlank()}?.let{t->r to t}}catch(_:Throwable){null}};render13(src,pairs) to pairs.size}finally{o.close();tr.close()}};translated=result.first;status="${result.second} região(ões) traduzida(s)."}}}){Text("Traduzir")}})}){p->Box(Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surface)){if(shown!=null)Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().pointerInput(index){detectTransformGestures{_,pan,zoom,_->{scale=(scale*zoom).coerceIn(1f,4f);ox+=pan.x;oy+=pan.y}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=ox;translationY=oy}});if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=76.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal=16.dp,vertical=10.dp));Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(onClick={if(index>0)index--},enabled=index>0){Text("Anterior")};Button(onClick={if(index<uris.lastIndex)index++},enabled=index<uris.lastIndex){Text("Próxima")}}}}}
}
