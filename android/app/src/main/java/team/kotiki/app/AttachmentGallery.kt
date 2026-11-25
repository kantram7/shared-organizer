package team.kotiki.app

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

@Composable internal fun AttachmentGallery(files:List<ChatAttachment>,initial:Int,store:AttachmentsStore,onDismiss:()->Unit,onResend:((ChatAttachment)->Unit)?=null) {
    if(files.isEmpty())return
    val pager=rememberPagerState(initialPage=initial.coerceIn(0,files.lastIndex),pageCount={files.size})
    var zoomed by remember{mutableStateOf(false)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val dialogView=LocalView.current
        // Only this dark media window gets light system icons; never mutate the Activity palette.
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let{window->
                WindowCompat.getInsetsController(window,dialogView).apply {
                    isAppearanceLightStatusBars=false
                    isAppearanceLightNavigationBars=false
                }
            }
        }
        Column(Modifier.fillMaxSize().background(ComposeColor(0xFF101820)).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text("${pager.currentPage+1} / ${files.size}",Modifier.weight(1f),color=ComposeColor.White,style=MaterialTheme.typography.titleMedium)
                AttachmentActions(files[pager.currentPage],store,ComposeColor.White)
                IconButton(onDismiss,Modifier.semantics{contentDescription="Закрыть фотографию"}){KotikiIcon(Glyph.CLOSE,color=ComposeColor.White)}
            }
            HorizontalPager(pager,Modifier.weight(1f).clipToBounds(),userScrollEnabled=!zoomed,key={files[it].id}){page->
                val item=files[page]
                var local by remember(item.id){mutableStateOf<File?>(null)}
                var progress by remember(item.id){mutableFloatStateOf(0f)}
                var error by remember(item.id){mutableStateOf<String?>(null)}
                var retry by remember(item.id){mutableIntStateOf(0)}
                var requested by remember(item.id){mutableStateOf(false)}
                LaunchedEffect(page==pager.currentPage,item.id,retry){
                    if(page!=pager.currentPage)return@LaunchedEffect
                    zoomed=false;error=null
                    try{local=store.download(item,onProgress={done,total->progress=done.toFloat()/total})}
                    catch(e:CancellationException){throw e}catch(_:Exception){error=if(item.expiresAt>0 && item.expiresAt<System.currentTimeMillis())"Срок хранения истёк. Локальной копии нет."else "Не удалось загрузить оригинал"}
                }
                Box(Modifier.fillMaxSize().clipToBounds(),contentAlignment=Alignment.Center){
                    val original=local
                    if(!item.mime.startsWith("image/"))Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_attachment_file),null,Modifier.size(64.dp),tint=ComposeColor.White)
                        Text(item.name,color=ComposeColor.White)
                        if(original!=null)Button({runCatching{store.open(item)}.onFailure{error="Нет приложения для этого формата"}}){Text("Открыть в другом приложении")}
                        else if(error==null){CircularProgressIndicator(color=ComposeColor.White);Text("Загружаем оригинал…",color=ComposeColor.White)}
                        error?.let{Text(it,color=ComposeColor.White);Button({retry++}){Text("Повторить")}}
                    }
                    else if(original!=null)AndroidView(factory={context->OriginalPhotoView(context,onZoom={active->if(page==pager.currentPage)zoomed=active},onFailure={error="Не удалось показать этот формат. Оригинал сохранён на телефоне.";local=null})},
                        update={it.open(original)},onReset=null,onRelease={it.release()},modifier=Modifier.fillMaxSize())
                    else Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                        if(error==null){CircularProgressIndicator(progress={progress.coerceIn(0f,1f)},color=ComposeColor.White);Text("Загружаем оригинал · ${(progress*100).toInt()}%",color=ComposeColor.White)}
                        else {
                            Text(error!!,color=ComposeColor.White)
                            if(item.expiresAt>0 && item.expiresAt<System.currentTimeMillis() && !store.hasLocal(item) && onResend!=null)Button({requested=true;onResend(item)},enabled=!requested){Text(if(requested)"Просьба отправлена"else "Попросить отправить снова")}
                            else {Button({retry++}){Text("Повторить")};if(store.hasLocal(item))TextButton({runCatching{store.open(item)}.onFailure{error="Нет приложения для этого формата"}}){Text("Открыть в другом приложении")}}
                        }
                    }
                }
            }
            Text(files[pager.currentPage].name,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),color=ComposeColor.White,style=MaterialTheme.typography.bodySmall,maxLines=2)
            if(files[pager.currentPage].mime.startsWith("image/"))Text("Двойной тап — увеличить · щипок — масштаб",Modifier.padding(horizontal=16.dp,vertical=8.dp),color=ComposeColor.White.copy(alpha=.65f),style=MaterialTheme.typography.labelSmall)
        }
    }
}

/** Draw visible source pixels, with EXIF orientation; never expand a small chat thumbnail. */
internal class OriginalPhotoView(context:Context,private val onZoom:(Boolean)->Unit,private val onFailure:()->Unit={}):View(context) {
    private val executor=Executors.newSingleThreadExecutor()
    private var path:String?=null
    private var sequence=0
    private var disposed=false
    private var decoder:BitmapRegionDecoder?=null
    private var preview:Bitmap?=null
    private var tile:Bitmap?=null
    private var tileRect=Rect()
    private var rawWidth=0
    private var rawHeight=0
    private var orientedWidth=0f
    private var orientedHeight=0f
    private val orientation=Matrix()
    private val imageMatrix=Matrix()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var zoom=1f
    private var panX=0f
    private var panY=0f
    private var lastX=0f
    private var lastY=0f
    private var loadingError=false
    private var requestKey=""
    private val requestTile=Runnable{decodeVisible()}
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean {changeZoom((zoom*detector.scaleFactor).coerceIn(1f,12f),detector.focusX,detector.focusY);return true}
        override fun onScaleBegin(detector:ScaleGestureDetector):Boolean {parent?.requestDisallowInterceptTouchEvent(true);return true}
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onDoubleTap(e:MotionEvent):Boolean {changeZoom(if(zoom>1.05f)1f else 3f,e.x,e.y);return true}
    })
    init {isClickable=true;contentDescription="Фото. Двойной тап или два пальца для увеличения"}
    fun open(file:File) {
        if(path==file.absolutePath || disposed)return
        path=file.absolutePath;loadingError=false;val token=++sequence
        executor.execute {
            var created:BitmapRegionDecoder?=null;var base:Bitmap?=null
            try {
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,options)
                require(options.outWidth>0 && options.outHeight>0)
                var sample=1;while(max(options.outWidth,options.outHeight)/sample>1800)sample*=2
                base=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
                @Suppress("DEPRECATION")
                created=runCatching{BitmapRegionDecoder.newInstance(file.absolutePath,false)}.getOrNull()
                if(created==null){
                    require(options.outWidth.toLong()*options.outHeight<=4_000_000L){"Unsupported large image format"}
                    base?.recycle();base=BitmapFactory.decodeFile(file.absolutePath)
                }
                require(base!=null){"Unsupported image"}
                val exif=runCatching{ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                post {
                    if(disposed || sequence!=token){base?.recycle();created?.recycle();return@post}
                    preview?.recycle();preview=base;decoder=created;rawWidth=options.outWidth;rawHeight=options.outHeight
                    orientation.reset()
                    when(exif){
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL->orientation.setScale(-1f,1f)
                        ExifInterface.ORIENTATION_ROTATE_180->orientation.setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL->orientation.setScale(1f,-1f)
                        ExifInterface.ORIENTATION_TRANSPOSE->{orientation.setScale(-1f,1f);orientation.postRotate(270f)}
                        ExifInterface.ORIENTATION_ROTATE_90->orientation.setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE->{orientation.setScale(-1f,1f);orientation.postRotate(90f)}
                        ExifInterface.ORIENTATION_ROTATE_270->orientation.setRotate(270f)
                    }
                    val bounds=RectF(0f,0f,rawWidth.toFloat(),rawHeight.toFloat());orientation.mapRect(bounds)
                    orientation.postTranslate(-bounds.left,-bounds.top);orientedWidth=bounds.width();orientedHeight=bounds.height();zoom=1f;panX=0f;panY=0f;updateImage()
                }
            }catch(_:Exception){base?.recycle();created?.recycle();post{if(!disposed && sequence==token){loadingError=true;invalidate();onFailure()}}}
        }
    }
    private fun fit()=if(orientedWidth>0 && orientedHeight>0)min(width/orientedWidth,height/orientedHeight)else 1f
    private fun changeZoom(next:Float,focusX:Float,focusY:Float) {
        val ratio=next/zoom;panX=(panX-(focusX-width/2f))*ratio+(focusX-width/2f);panY=(panY-(focusY-height/2f))*ratio+(focusY-height/2f);zoom=next
        onZoom(zoom>1.01f);updateImage()
    }
    private fun updateImage() {
        if(orientedWidth<=0 || width<=0 || height<=0)return
        val scale=fit()*zoom
        panX=panX.coerceIn(-max(0f,(orientedWidth*scale-width)/2),max(0f,(orientedWidth*scale-width)/2))
        panY=panY.coerceIn(-max(0f,(orientedHeight*scale-height)/2),max(0f,(orientedHeight*scale-height)/2))
        imageMatrix.set(orientation);imageMatrix.postScale(scale,scale);imageMatrix.postTranslate((width-orientedWidth*scale)/2+panX,(height-orientedHeight*scale)/2+panY)
        invalidate();removeCallbacks(requestTile);postDelayed(requestTile,40)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);updateImage()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(loadingError){paint.color=Color.WHITE;paint.textSize=16*resources.displayMetrics.scaledDensity;canvas.drawText("Не удалось показать изображение",16f,height/2f,paint);return}
        canvas.save();canvas.clipRect(0,0,width,height);canvas.concat(imageMatrix)
        preview?.takeUnless{it.isRecycled}?.let{canvas.drawBitmap(it,null,RectF(0f,0f,rawWidth.toFloat(),rawHeight.toFloat()),paint)}
        tile?.takeUnless{it.isRecycled}?.let{canvas.drawBitmap(it,null,tileRect,paint)}
        canvas.restore()
    }
    private fun decodeVisible() {
        val current=decoder?:return
        val inverse=Matrix();if(!imageMatrix.invert(inverse))return
        val area=RectF(0f,0f,width.toFloat(),height.toFloat());inverse.mapRect(area)
        val region=Rect(floor(area.left).toInt().coerceIn(0,rawWidth-1),floor(area.top).toInt().coerceIn(0,rawHeight-1),ceil(area.right).toInt().coerceIn(1,rawWidth),ceil(area.bottom).toInt().coerceIn(1,rawHeight))
        if(region.width()<=0 || region.height()<=0)return
        var sample=1;while(sample<(1 shl 29) && sample*2<=1f/(fit()*zoom))sample*=2
        val key="$region/$sample";if(key==requestKey)return;requestKey=key
        val token=sequence
        executor.execute {
            val result=runCatching{current.decodeRegion(region,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}.getOrNull()?:return@execute
            post{if(disposed || token!=sequence || key!=requestKey){result.recycle()}else{tile?.recycle();tile=result;tileRect=region;invalidate()}}
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        gestures.onTouchEvent(event);scaler.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{lastX=event.x;lastY=event.y;parent?.requestDisallowInterceptTouchEvent(zoom>1.01f)}
            MotionEvent.ACTION_POINTER_DOWN->parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE->{if(!scaler.isInProgress && zoom>1.01f && event.pointerCount==1){panX+=event.x-lastX;panY+=event.y-lastY;updateImage()};lastX=event.x;lastY=event.y}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{parent?.requestDisallowInterceptTouchEvent(false);performClick()}
        }
        return true
    }
    override fun performClick():Boolean=super.performClick()
    fun release(){if(disposed)return;disposed=true;++sequence;removeCallbacks(requestTile);executor.execute{decoder?.recycle();decoder=null};executor.shutdown();preview?.recycle();preview=null;tile?.recycle();tile=null}
}
