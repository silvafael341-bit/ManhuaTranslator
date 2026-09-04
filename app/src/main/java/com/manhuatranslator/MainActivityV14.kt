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
import com.google.mlkit.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.*
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.*

private enum class S14{ZH,JA,KO,EN}
private data class B14(val t:String,val r:Rect,val c:Float,val s:S14)
private data class Shape14(val mask:BooleanArray,val x:Int,val y:Int,val w:Int,val h:Int,val bounds:Rect)
class MainActivityV14:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App14()}}}
private fun cjk14(s:String)=s.count{it.code in 0x3040..0x30ff||it.code in 0x3400..0x9fff||it.code in 0xac00..0xd7af}
private fun map14(r:Rect,rot:Int,w:Int,h:Int):Rect{if(rot==0)return Rect(r);val p=listOf(r.left to r.top,r.right to r.top,r.left to r.bottom,r.right to r.bottom).map{(x,y)->if(rot==90)y to h-x else w-y to x};return Rect(p.minOf{it.first}.coerceIn(0,w),p.minOf{it.second}.coerceIn(0,h),p.maxOf{it.first}.coerceIn(0,w),p.maxOf{it.second}.coerceIn(0,h))}
private class O14:AutoCloseable{
 private data class X(val s:S14,val r:TextRecognizer)
 private val xs=listOf(X(S14.ZH,TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),X(S14.JA,TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),X(S14.KO,TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),X(S14.EN,TextRecognition.getClient(TextRecognizerOptions.Builder().build())))
 suspend fun run(src:Bitmap):List<B14>{val a=mutableListOf<B14>();for(rot in intArrayOf(0,90,270)){val im=if(rot==0)src else Bitmap.createBitmap(src,0,0,src.width,src.height,Matrix().apply{postRotate(rot.toFloat())},true);try{for(x in xs){val z=x.r.process(InputImage.fromBitmap(im,0)).await();for(tb in z.textBlocks)for(l in tb.lines){val t=l.text.trim();val q=l.boundingBox?:continue;if(t.length<2)continue;val cf=l.elements.mapNotNull{it.confidence}.takeIf{it.isNotEmpty()}?.average()?.toFloat()?:.5f;a+=B14(t,map14(q,rot,src.width,src.height),cf,x.s)}}}finally{if(im!==src)im.recycle()}};val c=a.filter{it.s!=S14.EN&&cjk14(it.t)>0};val p=if(c.isNotEmpty())c else a;val out=mutableListOf<B14>();for(v in p.sortedByDescending{it.c*100+cjk14(it.t)*180+min(40,it.t.length)})if(out.none{ov14(it.r,v.r)>=.45f})out+=v;return out.sortedWith(compareBy<B14>{it.r.top}.thenBy{it.r.left})}
 private fun ov14(a:Rect,b:Rect):Float{val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val d=min(a.bottom,b.bottom);if(r<=l||d<=t)return 0f;val i=(r-l).toLong()*(d-t);val z=min(a.width().toLong()*a.height(),b.width().toLong()*b.height());return if(z==0L)0f else i.toFloat()/z}
 override fun close(){xs.forEach{it.r.close()}}
}
private class T14:AutoCloseable{private val m=mutableMapOf<String,Translator>();suspend fun tr(t:String,s:S14):String{val l=when(s){S14.ZH->"zh";S14.JA->"ja";S14.KO->"ko";S14.EN->"en"};val x=m.getOrPut(l){Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(l).setTargetLanguage("pt").build())};x.downloadModelIfNeeded(DownloadConditions.Builder().build()).await();return x.translate(t).await().trim()};override fun close(){m.values.forEach{it.close()};m.clear()}}
private fun lum14(c:Int)=.299f*Color.red(c)+.587f*Color.green(c)+.114f*Color.blue(c)
private fun dst14(a:Int,b:Int)=abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b))
private fun bg14(src:Bitmap,r:Rect):Int{val v=mutableListOf<Int>();val l=max(0,r.left-r.width()/3);val t=max(0,r.top-r.height()/2);val x=min(src.width-1,r.right+r.width()/3);val y=min(src.height-1,r.bottom+r.height()/2);val sx=max(3,(x-l)/14);val sy=max(3,(y-t)/14);var yy=t;while(yy<=y){var xx=l;while(xx<=x){if(xx<r.left-8||xx>r.right+8||yy<r.top-8||yy>r.bottom+8)v+=src.getPixel(xx,yy);xx+=sx};yy+=sy};if(v.isEmpty())return Color.WHITE;val q=v.filter{lum14(it)>180};val u=if(q.size>=max(4,v.size/5))q else v;val rr=u.map{Color.red(it)}.sorted();val gg=u.map{Color.green(it)}.sorted();val bb=u.map{Color.blue(it)}.sorted();val k=u.size/2;return Color.rgb(rr[k],gg[k],bb[k])}
private fun shape14(src:Bitmap,r:Rect):Shape14?{val cx=r.centerX();val cy=r.centerY();val bg=bg14(src,r);val n=360;val rad=IntArray(n){-1};fun inside(c:Int):Boolean{val d=dst14(c,bg);val dl=abs(lum14(c)-lum14(bg));return(d<=90&&dl<=60)||(lum14(bg)>205&&lum14(c)>198&&d<=135)};val maxR=min(800,max(src.width,src.height));for(i in 0 until n){val a=2*PI*i/n;var bad=0;var hit=-1;var z=6;while(z<maxR){val x=(cx+cos(a)*z).roundToInt();val y=(cy+sin(a)*z).roundToInt();if(x<1||x>=src.width-1||y<1||y>=src.height-1)break;if(inside(src.getPixel(x,y))){bad=0}else{bad++;if(bad>=7){var stable=0;for(k in 1..10){val q=z+k;val xx=(cx+cos(a)*q).roundToInt();val yy=(cy+sin(a)*q).roundToInt();if(xx in 1 until src.width-1&&yy in 1 until src.height-1&&!inside(src.getPixel(xx,yy)))stable++};if(stable>=7){hit=z-6;break};bad=0}};z+=2};rad[i]=hit};val good=rad.count{it>0};if(good<n*.6)return null;val vals=rad.filter{it>0}.sorted();val med=vals[vals.size/2];for(i in rad.indices)if(rad[i]<0)rad[i]=med;for(i in rad.indices){val p=rad[(i+n-1)%n];val q=rad[(i+1)%n];if(abs(rad[i]-p)>med*.5&&abs(rad[i]-q)>med*.5)rad[i]=(p+q)/2};val minR=max(22,min(r.width(),r.height())/2);val maxR2=max(minR*2,max(r.width(),r.height())*2);for(i in rad.indices)rad[i]=rad[i].coerceIn(minR,maxR2);val l=max(0,cx-maxR2-3);val t=max(0,cy-maxR2-3);val rr=min(src.width,cx+maxR2+4);val bb=min(src.height,cy+maxR2+4);val w=rr-l;val h=bb-t;val mask=BooleanArray(w*h);var mnx=w;var mxx=0;var mny=h;var mxy=0;var count=0;for(y in 0 until h)for(x in 0 until w){val dx=x+l-cx;val dy=y+t-cy;val d=hypot(dx.toDouble(),dy.toDouble());var ai=((atan2(dy.toDouble(),dx.toDouble())/(2*PI))*n).roundToInt()%n;if(ai<0)ai+=n;if(d<=rad[ai]-3){mask[y*w+x]=true;count++;mnx=min(mnx,x);mxx=max(mxx,x);mny=min(mny,y);mxy=max(mxy,y)}};if(count<r.width()*r.height()/2)return null;return Shape14(mask,l,t,w,h,Rect(l+mnx,t+mny,l+mxx+1,t+mxy+1))}
private fun fill14(dst:Bitmap,s:Shape14,color:Int){val p=IntArray(s.w*s.h);dst.getPixels(p,0,s.w,s.x,s.y,s.w,s.h);for(i in p.indices)if(s.mask[i])p[i]=color;dst.setPixels(p,0,s.w,s.x,s.y,s.w,s.h)}
private fun wrap14(t:String,p:Paint,w:Float):List<String>{val tok=if(t.any{it.isWhitespace()})t.trim().split(Regex("\\s+"))else t.map{it.toString()};val o=mutableListOf<String>();var cur="";for(q in tok){val n=if(cur.isEmpty())q else "$cur $q";if(p.measureText(n)<=w)cur=n else{if(cur.isNotEmpty())o+=cur;if(p.measureText(q)<=w)cur=q else{var z="";for(ch in q){if(p.measureText(z+ch)<=w)z+=ch else{if(z.isNotEmpty())o+=z;z=ch.toString()}};cur=z}}};if(cur.isNotEmpty())o+=cur;return o}
private fun text14(c:Canvas,s:Shape14,t:String,bg:Int){val r=RectF(s.bounds);val p=Paint(1).apply{color=if(lum14(bg)>145)Color.BLACK else Color.WHITE;typeface=Typeface.create("sans",0)};var sz=min(r.width(),r.height())*.12f;var lines:List<String>;while(true){p.textSize=sz;lines=wrap14(t,p,r.width()*.62f);val h=lines.size*(p.fontMetrics.bottom-p.fontMetrics.top);if(h<=r.height()*.45f||sz<=12)break;sz-=1};val lh=p.fontMetrics.bottom-p.fontMetrics.top;var y=r.centerY()-lines.size*lh/2-p.fontMetrics.top;for(q in lines){c.drawText(q,r.centerX()-p.measureText(q)/2,y,p);y+=lh}}
private fun render14(src:Bitmap,pairs:List<Pair<B14,String>>):Bitmap{val out=src.copy(Bitmap.Config.ARGB_8888,true);val c=Canvas(out);for((r,t) in pairs){val s=shape14(src,r.r)?:continue;val bg=bg14(src,r.r);fill14(out,s,bg);text14(c,s,t,bg)};return out}
@Composable private fun App14(){var pages by remember{mutableStateOf<List<Uri>>(emptyList())};var open by remember{mutableStateOf(false)};val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty()){pages=it;open=true}};if(open)Reader14(pages){open=false}else Home14{pick.launch(arrayOf("image/*"))}}
@Composable private fun Home14(open:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Manhua Translator")})}){p->Column(Modifier.fillMaxSize().padding(p),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("Tradutor de manhua para português");Spacer(Modifier.height(20.dp));Button(onClick=open){Text("Selecionar imagens")}}}}
@Composable private fun Reader14(pages:List<Uri>,back:()->Unit){val ctx=LocalContext.current;val scope=rememberCoroutineScope();var idx by remember{mutableIntStateOf(0)};var img by remember{mutableStateOf<Bitmap?>(null)};var busy by remember{mutableStateOf(false)};var status by remember{mutableStateOf("")};var scale by remember{mutableFloatStateOf(1f)};var tx by remember{mutableFloatStateOf(0f)};var ty by remember{mutableFloatStateOf(0f)};LaunchedEffect(idx){img=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(pages[idx])?.use{BitmapFactory.decodeStream(it)}};scale=1f;tx=0f;ty=0f;status=""};val b=img;Scaffold(topBar={TopAppBar(title={Text("Página ${idx+1} / ${pages.size}")},navigationIcon={TextButton(onClick=back){Text("Voltar")}},actions={Button(enabled=!busy&&b!=null,onClick={scope.launch{busy=true;status="Traduzindo...";val result=withContext(Dispatchers.Default){val o=O14();val t=T14();try{val blocks=o.run(b!!);val pairs=mutableListOf<Pair<B14,String>>();for(x in blocks){val q=t.tr(x.t,x.s);if(q.isNotBlank())pairs+=x to q};pairs.size to render14(b,pairs)}finally{o.close();t.close()}};img?.recycle();img=result.second;status="${result.first} região(ões) traduzida(s).";busy=false}}){Text(if(busy)"Traduzindo..." else "Traduzir")}}}){p->Box(Modifier.fillMaxSize().padding(p)){if(b!=null)Image(bitmap=b.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().pointerInput(idx){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,4f);tx+=pan.x;ty+=pan.y}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=tx;translationY=ty}};if(status.isNotBlank())Text(status,Modifier.align(Alignment.BottomCenter).padding(bottom=70.dp));Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween){Button(enabled=idx>0&&!busy,onClick={idx--}){Text("Anterior")};Button(enabled=idx<pages.lastIndex&&!busy,onClick={idx++}){Text("Próxima")}}}}}
