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

private enum class V14Script { CHINESE, JAPANESE, KOREAN, LATIN }
private data class V14Block(val text:String,val box:Rect,val confidence:Float,val script:V14Script)
private data class V14Region(val box:Rect,val text:String,val script:V14Script)
private data class V14Mask(val bitmap:Bitmap,val originX:Int,val originY:Int,val bounds:Rect)

class MainActivityV14:ComponentActivity(){override fun onCreate(state:Bundle?){super.onCreate(state);setContent{V14App()}}}

private class V14Ocr:AutoCloseable{
 private data class R(val script:V14Script,val recognizer:TextRecognizer)
 private val rs=listOf(R(V14Script.CHINESE,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),R(V14Script.JAPANESE,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),R(V14Script.KOREAN,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),R(V14Script.LATIN,TextRecognition.getClient(TextRecognizerOptions.Builder().build())))
 suspend fun run(src:Bitmap):List<V14Block>{val all=mutableListOf<V14Block>();for(rot in intArrayOf(0,90,270)){val img=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true);try{for(r in rs){val result=r.recognizer.process(InputImage.fromBitmap(img,0)).await();for(tb in result.textBlocks)for(line in tb.lines){val raw=line.text.trim();val q=line.boundingBox?:continue;val cjk=countCjk14(raw);if(raw.count{it.isLetter()}<2&&cjk<1)continue;val conf=line.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:0.5f;all+=V14Block(raw,map14(q,rot,src.width,src.height),conf,r.script)}}}finally{if(img!==src)img.recycle()}};return choose14(all)}
 private fun choose14(input:List<V14Block>):List<V14Block>{val cjk=input.filter{it.script!=V14Script.LATIN&&countCjk14(it.text)>0};val pool=if(cjk.isNotEmpty())cjk else input;val sorted=pool.sortedByDescending{it.confidence*100f+countCjk14(it.text)*180f+it.text.length.coerceAtMost(40)};val out=mutableListOf<V14Block>();for(x in sorted)if(out.none{overlap14(it.box,x.box)>=0.45f})out+=x;return out.sortedWith(compareBy<V14Block>{it.box.top}.thenBy{it.box.left})}
 private fun overlap14(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val area=(r-l).toLong()*(d-t).toLong();val base=min(a.width().toLong()*a.height().toLong(),b.width().toLong()*b.height().toLong());return if(base<=0L)0f else area.toFloat()/base.toFloat()}
 private fun map14(q:Rect,rot:Int,w:Int,h:Int):Rect{if(rot==0)return Rect(q);val p=listOf(q.left to q.top,q.right to q.top,q.left to q.bottom,q.right to q.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x};return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))}
 override fun close(){rs.forEach{it.recognizer.close()}}
}
private fun countCjk14(s:String)=s.count{it.code in 0x3040..0x30ff||it.code in 0x3400..0x9fff||it.code in 0xac00..0xd7af}
private fun group14(blocks:List<V14Block>):List<V14Region>{val out=mutableListOf<V14Region>();for(b in blocks){val i=out.indexOfFirst{r->val v=max(0,max(r.box.top,b.box.top)-min(r.box.bottom,b.box.bottom));val h=max(0,max(r.box.left,b.box.left)-min(r.box.right,b.box.right));val c=abs(r.box.centerX()-b.box.centerX())<=max(r.box.width(),b.box.width())*1.4f;val row=abs(r.box.centerY()-b.box.centerY())<=max(r.box.height(),b.box.height())*2.4f;(v<=max(36f,max(r.box.height(),b.box.height()).toFloat()*1.5f)&&c)||(h<=max(28f,min(r.box.width(),b.box.width()).toFloat()*0.6f)&&row)};if(i<0)out+=V14Region(Rect(b.box),b.text,b.script)else{val r=out[i];val text=(r.text+" "+b.text).replace(Regex("\\s+")," ").trim();val script=when{ text.any{it.code in 0xac00..0xd7af}->V14Script.KOREAN;text.any{it.code in 0x3040..0x30ff}->V14Script.JAPANESE;text.any{it.code in 0x3400..0x9fff}->V14Script.CHINESE;else->r.script};out[i]=V14Region(Rect(r.box).apply{union(b.box)},text,script)}};return out}
private class V14Translator:AutoCloseable{private val clients=mutableMapOf<String,Translator>();suspend fun tr(text:String,script:V14Script):String{val source=when(script){V14Script.CHINESE->"zh";V14Script.JAPANESE->"ja";V14Script.KOREAN->"ko";V14Script.LATIN->"en"};val c=clients.getOrPut(source){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage("pt").build())};c.downloadModelIfNeeded(DownloadConditions.Builder().build()).await();return c.translate(text).await().trim()};override fun close(){clients.values.forEach{it.close()};clients.clear()}}
private fun lum14(c:Int):Float=0.299f*Color.red(c)+0.587f*Color.green(c)+0.114f*Color.blue(c)
private fun median14(v:List<Int>):Int{if(v.isEmpty())return Color.WHITE;val r=v.map{Color.red(it)}.sorted();val g=v.map{Color.green(it)}.sorted();val b=v.map{Color.blue(it)}.sorted();val m=v.size/2;return Color.rgb(r[m],g[m],b[m])}
private fun bg14(src:Bitmap,box:Rect):Int{val v=mutableListOf<Int>();val l=max(0,box.left-box.width()/2);val t=max(0,box.top-box.height());val r=min(src.width-1,box.right+box.width()/2);val b=min(src.height-1,box.bottom+box.height());val sx=max(3,(r-l)/18);val sy=max(3,(b-t)/18);var y=t;while(y<=b){var x=l;while(x<=r){if(x<box.left-8||x>box.right+8||y<box.top-8||y>box.bottom+8)v+=src.getPixel(x,y);x+=sx};y+=sy};val br=v.filter{lum14(it)>175f};return median14(if(br.size>=v.size/5)br else v)}
private fun isBoundary14(src:Bitmap,x:Int,y:Int,ix:Int,iy:Int):Boolean{if(x<1||x>=src.width-1||y<1||y>=src.height-1)return false;val c=src.getPixel(x,y);if(lum14(c)>115f)return false;return lum14(src.getPixel(x+ix,y+iy))>150f||lum14(src.getPixel(x+ix*2,y+iy*2))>150f}
private fun edgeH14(src:Bitmap,y:Int,start:Int,end:Int,step:Int):Int?{var x=start;var run=0;while(if(step<0)x>=end else x<=end){if(isBoundary14(src,x,y,-step.coerceIn(-1,1),0)){run++;if(run>=2)return x}else run=0;x+=step};return null}
private fun edgeV14(src:Bitmap,x:Int,start:Int,end:Int,step:Int):Int?{var y=start;var run=0;while(if(step<0)y>=end else y<=end){if(isBoundary14(src,x,y,0,-step.coerceIn(-1,1))){run++;if(run>=2)return y}else run=0;y+=step};return null}
private fun makeMask14(src:Bitmap,box:Rect):V14Mask?{val padX=max(100,box.width()*2);val padY=max(160,box.height()*3);val l=max(0,box.left-padX);val t=max(0,box.top-padY);val r=min(src.width,box.right+padX);val b=min(src.height,box.bottom+padY);if(r-l<20||b-t<20)return null;val mw=r-l;val bx1=(box.left-l).coerceIn(0,mw-1);val bx2=(box.right-l).coerceIn(0,mw-1);val cx=(bx1+bx2)/2;val x=(l+cx).coerceIn(1,src.width-2);val top=(edgeV14(src,x,(box.top-10).coerceIn(1,src.height-2),t+1,-1)?:max(t,box.top-padY/2)).coerceIn(t,b-2);val bottom=(edgeV14(src,x,(box.bottom+10).coerceIn(1,src.height-2),b-2,1)?:min(b-1,box.bottom+padY/2)).coerceIn(top+2,b-1);val mh=bottom-top+1;val le=IntArray(mh){-1};val re=IntArray(mh){-1};for(yy in 0 until mh){val gy=top+yy;edgeH14(src,gy,(box.left-10).coerceIn(l+1,r-2),l+1,-1)?.let{le[yy]=it-l};edgeH14(src,gy,(box.right+10).coerceIn(l+1,r-2),r-2,1)?.let{re[yy]=it-l}};val valid=(0 until mh).count{le[it]>=0&&re[it]>=0&&re[it]-le[it]>=max(10,box.width()/3)};if(valid<max(8,mh/8))return null;fun fill(a:IntArray){var last=-1;for(i in a.indices)if(a[i]>=0){if(last>=0&&i-last>1){val aa=a[last];val zz=a[i];for(j in last+1 until i)a[j]=aa+(zz-aa)*(j-last)/(i-last)};last=i};if(last>=0)for(i in 0 until last)a[i]=a[last];var first=-1;for(i in a.indices.reversed())if(a[i]>=0){first=i;break};if(first>=0)for(i in first+1 until a.size)a[i]=a[first]};fill(le);fill(re);val mask=BooleanArray(mw*mh);var minX=mw;var maxX=0;var minY=mh;var maxY=0;var count=0;for(yy in 0 until mh){var left=le[yy];var right=re[yy];if(left<0||right<=left)continue;left=(left+1).coerceIn(0,mw-1);right=(right-1).coerceIn(left+1,mw-1);if(left>bx1+box.width()||right<bx2-box.width())continue;for(xx in left..right){mask[yy*mw+xx]=true;count++};minX=min(minX,left);maxX=max(maxX,right);minY=min(minY,yy);maxY=max(maxY,yy)};if(count<max(120,box.width()*box.height()/2)||count.toLong()>mw.toLong()*mh.toLong()*7L/10L)return null;val out=Bitmap.createBitmap(mw,mh,Bitmap.Config.ARGB_8888);val pix=IntArray(mw*mh);for(i in pix.indices)if(mask[i])pix[i]=Color.WHITE;out.setPixels(pix,0,mw,0,0,mw,mh);return V14Mask(out,l,top,Rect(l+minX,top+minY,l+maxX+1,top+maxY+1))}
private fun replace14(dst:Bitmap,m:V14Mask,color:Int){val w=m.bitmap.width;val h=m.bitmap.height;val mp=IntArray(w*h);val p=IntArray(w*h);m.bitmap.getPixels(mp,0,w,0,0,w,h);dst.getPixels(p,0,w,m.originX,m.originY,w,h);for(i in p.indices)if(Color.alpha(mp[i])>0)p[i]=color;dst.setPixels(p,0,w,m.originX,m.originY,w,h)}
private fun wrap14(text:String,p:Paint,maxW:Float):List<String>{val tok=if(text.any{it.isWhitespace()})text.trim().split(Regex("\\s+"))else text.map{it.toString()};val out=mutableListOf<String>();var cur="";for(t in tok){val n=if(cur.isEmpty())t else "$cur $t";if(p.measureText(n)<=maxW)cur=n else{if(cur.isNotEmpty())out+=cur;if(p.measureText(t)<=maxW)cur=t else{var part="";for(ch in t){if(p.measureText(part+ch)<=maxW)part+=ch else{if(part.isNotEmpty())out+=part;part=ch.toString()}};cur=part}}};if(cur.isNotEmpty())out+=cur;return out}
private fun text14(c:Canvas,m:V14Mask,s:String,bg:Int){val b=RectF(m.bounds);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(lum14(bg)>150f)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",Typeface.NORMAL)};var size=(min(b.width(),b.height())*.18f).coerceIn(16f,30f);var lines:List<String>;while(true){p.textSize=size;lines=wrap14(s,p,b.width()*.72f);val h=lines.size*(p.fontMetrics.bottom-p.fontMetrics.top);if(h<=b.height()*.52f||size<=11f)break;size-=1f};val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=b.centerY()-lines.size*lh/2f-p.fontMetrics.top;for(line in lines){c.drawText(line,b.centerX()-p.measureText(line)/2f,y,p);y+=lh}}
private fun render14(src:Bitmap,pairs:List<Pair<V14Region,String>>):Bitmap{val out=src.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(out);for((r,s) in pairs){val m=makeMask14(src,r.box)?:continue;val bg=bg14(src,r.box);replace14(out,m,bg);text14(c,m,s,bg);m.bitmap.recycle()};return out}
@Composable private fun V14App(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open&&pages.isNotEmpty())V14Reader(pages){open=false}else V14Home{picker.launch(arrayOf("image/*"))}}
@Composable private fun V14Home(onOpen:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Leitor de Manhua",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("OCR + tradução automática para português");Spacer(Modifier.height(24.dp));Button(onClick=onOpen){Text("Abrir imagens")}}}}
@Composable private fun V14Reader(uris:List<Uri>,onBack:()->Unit){val context=LocalContext.current;val scope=rememberCoroutineScope();var index by remember{mutableStateOf(0)};var bitmap by remember{mutableStateOf<Bitmap?>(null)};var translated by remember{mutableStateOf<Bitmap?>(null)};var status by remember{mutableStateOf("")};var scale by remember{mutableStateOf(1f)};var ox by remember{mutableStateOf(0f)};var oy by remember{mutableStateOf(0f)};LaunchedEffect(index,uris){bitmap=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uris[index])?.use{BitmapFactory.decodeStream(it)}};translated=null;scale=1f;ox=0f;oy=0f;status=""};DisposableEffect(Unit){onDispose{bitmap?.recycle();translated?.recycle()}};val shown=translated?:bitmap;Scaffold(topBar={TopAppBar(title={Text("Página ${index+1} / ${uris.size}")},navigationIcon={TextButton(onClick=onBack){Text("Voltar")}},actions={Button(onClick={scope.launch{status="Processando…";val src=bitmap;if(src!=null){val result=withContext(Dispatchers.Default){val o=V14Ocr();val tr=V14Translator();try{val blocks=o.run(src);val regions=group14(blocks);val pairs=regions.mapNotNull{r->try{val tt=tr.tr(r.text,r.script);if(tt.isBlank())null else r to tt}catch(_:Throwable){null}};render14(src,pairs) to pairs.size}finally{o.close();tr.close()}};translated=result.first;status="${result.second} região(ões) traduzida(s)."}}}){Text("Traduzir")}})}){p->Box(Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surface)){if(shown!=null)Image(bitmap=shown.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer(scaleX=scale,scaleY=scale,translationX=ox,translationY=oy).pointerInput(Unit){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);ox+=pan.x;oy+=pan.y}});if(status.isNotEmpty())Text(status,Modifier.align(Alignment.BottomCenter).padding(16.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=18.dp,vertical=10.dp));Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=index>0,onClick={index--;translated=null}){Text("Anterior")};Button(enabled=index<uris.lastIndex,onClick={index++;translated=null}){Text("Próxima")}}}}}
