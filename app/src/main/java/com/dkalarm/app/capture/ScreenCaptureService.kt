package com.dkalarm.app.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dkalarm.app.MainActivity
import com.dkalarm.app.R
import com.dkalarm.app.core.RgbaFrame
import java.io.File
import java.time.Instant
import java.util.UUID

/** One user-requested still, with system consent. Never scrolls or refreshes another app. */
class ScreenCaptureService:Service() {
    companion object {
        const val PREPARE="com.dkalarm.capture.PREPARE"
        const val TAKE="com.dkalarm.capture.TAKE"
        const val CANCEL="com.dkalarm.capture.CANCEL"
        const val OPEN="com.dkalarm.capture.OPEN"
        const val ERROR="com.dkalarm.capture.ERROR"
        const val CODE="resultCode"
        const val DATA="resultData"
        const val NOTIFICATION_ID=4101
        const val RESULT_ID=4102
        private const val CHANNEL="screen_capture"
    }
    private lateinit var thread:HandlerThread
    private lateinit var handler:Handler
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private var taking=false
    private var finished=false
    private var width=0
    private var height=0
    private var readerGeneration=0
    private var frameAfter=0L
    private val notifications get()=getSystemService(NotificationManager::class.java)
    override fun onCreate() {
        super.onCreate()
        thread=HandlerThread("DK-one-screen-shot").apply {start()};handler=Handler(thread.looper)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Skenování obrazovky",NotificationManager.IMPORTANCE_LOW).apply {
            description="Ruční pořízení jednoho snímku a otevření výsledku";setSound(null,null)
        })
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            PREPARE -> {
                val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(DATA,Intent::class.java)
                    else @Suppress("DEPRECATION") (intent.getParcelableExtra(DATA) as? Intent)
                val code=intent.getIntExtra(CODE,Activity.RESULT_CANCELED)
                if(code!=Activity.RESULT_OK||data==null){stopSelf();return START_NOT_STICKY}
                // Android requires the visible projection service before getMediaProjection().
                val n=readyNotification()
                if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                else startForeground(NOTIFICATION_ID,n)
                handler.post {
                    if(projection!=null||finished)return@post
                    try {
                        projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(code,data)
                        check(projection!=null){"Android nepovolil zachycení obrazovky."}
                        projection!!.registerCallback(object:MediaProjection.Callback() {
                            override fun onStop() {if(!finished)fail("Snímání bylo přerušeno. Odemkni telefon a spusť nový sken.")}
                            override fun onCapturedContentResize(w:Int,h:Int) {
                                if(display!=null && !finished && (width!=w||height!=h))resize(w,h)
                            }
                        },handler)
                        CaptureStore.setActive(this,true)
                        handler.postDelayed({if(!finished)fail("Příprava skenu vypršela. Spusť nový sken.")},120_000)
                    } catch(e:Exception){fail(e.message ?: "Zachycení obrazovky se nepodařilo připravit.")}
                }
            }
            TAKE -> handler.post {
                if(projection==null||finished){stopSelf();return@post}
                if(taking)return@post
                taking=true
                notifications.notify(NOTIFICATION_ID,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Snímek za 2 sekundy")
                    .setContentText("Nech zobrazený přehled příchozích útoků.").setOngoing(true).setSilent(true).build())
                handler.postDelayed({takeFrame()},2_000)
            }
            CANCEL -> handler.post {finishCapture()}
            else -> stopSelf()
        }
        return START_NOT_STICKY // System grants are never reused after a process restart.
    }
    private fun readyNotification():Notification {
        val shot=PendingIntent.getActivity(this,1,Intent(this,CaptureTriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,2,Intent(this,ScreenCaptureService::class.java).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("DK Alarm – připraven ke skenu")
            .setContentText("Otevři tabulku útoků a klepni na Sejmout.")
            .setContentIntent(shot).addAction(0,"Sejmout",shot).addAction(0,"Zrušit",stop)
            .setOngoing(true).setSilent(true).build()
    }
    @Suppress("DEPRECATION")
    private fun takeFrame() {
        if(finished)return
        try {
            val wm=getSystemService(WindowManager::class.java)
            val size=if(Build.VERSION.SDK_INT>=30)wm.maximumWindowMetrics.bounds.let {it.width() to it.height()}
                else DisplayMetrics().let {wm.defaultDisplay.getRealMetrics(it);it.widthPixels to it.heightPixels}
            width=size.first;height=size.second
            check(width.toLong()*height<=16_000_000){"Rozlišení obrazovky je příliš velké."}
            reader=newReader(width,height)
            frameAfter=SystemClock.elapsedRealtime()+350
            display=projection!!.createVirtualDisplay("DK Alarm – jeden snímek",width,height,resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
            handler.postDelayed({if(!finished)fail("Obraz nebyl dostupný. Zkontroluj zvolenou aplikaci a zkus nový sken.")},12_000)
        } catch(e:Exception){fail(e.message ?: "Snímek se nepodařilo pořídit.")}
    }
    private fun newReader(w:Int,h:Int):ImageReader {
        val generation=++readerGeneration
        return ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3).apply {
            val consume=Runnable {consumeFrame(this,generation)}
            setOnImageAvailableListener({r ->
                val delay=frameAfter-SystemClock.elapsedRealtime()
                if(delay>0) {
                    // Keep the queued static frame until the capture surface has settled.
                    handler.removeCallbacks(consume);handler.postDelayed(consume,delay)
                } else consumeFrame(r,generation)
            },handler)
        }
    }
    private fun consumeFrame(r:ImageReader,generation:Int) {
        if(finished||generation!=readerGeneration)return
        val image=runCatching {r.acquireLatestImage()}.getOrNull() ?: return
        try {
            val capturedAt=Instant.now()
            val crop=image.cropRect;val plane=image.planes[0]
            val pixels=RgbaFrame.argb(plane.buffer,plane.rowStride,plane.pixelStride,crop.left,crop.top,crop.width(),crop.height())
            if(pixels.none {((it shr 16) and 255)>24 || ((it shr 8) and 255)>24 || (it and 255)>24}) {
                fail("Snímek je černý. Zvolená aplikace nemusí zachycení povolovat.");return
            }
            val bitmap=Bitmap.createBitmap(pixels,crop.width(),crop.height(),Bitmap.Config.ARGB_8888)
            save(bitmap,capturedAt)
        } catch(e:Exception){fail(e.message ?: "Snímek nelze přečíst.")}
        finally {image.close()}
    }
    private fun resize(w:Int,h:Int) {
        try {
            check(w>0&&h>0&&w.toLong()*h<=16_000_000){"Neplatné rozlišení snímku."}
            val previous=reader;val next=newReader(w,h)
            display?.resize(w,h,resources.configuration.densityDpi)
            display?.surface=next.surface
            reader=next;width=w;height=h
            frameAfter=SystemClock.elapsedRealtime()+350
            previous?.close()
        } catch(e:Exception){fail(e.message ?: "Změna rozlišení snímku selhala.")}
    }
    private fun save(bitmap:Bitmap,capturedAt:Instant) {
        var temporary:File?=null
        try {
            val id=UUID.randomUUID().toString()
            val file=File(CaptureStore.directory(this),"$id.png")
            temporary=File(file.parentFile,"$id.tmp")
            temporary.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            check(temporary.renameTo(file)){"Snímek se nepodařilo uložit."}
            CaptureStore.complete(this,CaptureStore.Shot(id,file,bitmap.width,bitmap.height,capturedAt))
            resultNotification("Snímek připraven – otevřít analýzu","${bitmap.width} × ${bitmap.height} • originál bez zmenšení")
            finishCapture()
        } catch(e:Exception){temporary?.delete();fail(e.message ?: "Uložení snímku selhalo.")}
        finally {bitmap.recycle()}
    }
    private fun resultNotification(title:String,text:String,success:Boolean=true) {
        val open=PendingIntent.getActivity(this,3,Intent(this,MainActivity::class.java).setAction(if(success)OPEN else ERROR).putExtra("error",text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(RESULT_ID,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).setSilent(true).build())
    }
    private fun fail(text:String) {
        if(finished)return
        android.util.Log.w("DK_CAPTURE",text)
        resultNotification("Sken se nepodařil",text,false)
        finishCapture()
    }
    private fun finishCapture() {
        if(finished)return
        finished=true;CaptureStore.setActive(this,false);handler.removeCallbacksAndMessages(null)
        runCatching {display?.release()};display=null
        runCatching {reader?.close()};reader=null
        runCatching {projection?.stop()};projection=null
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {
        handler.post {finishCapture();thread.quitSafely()}
        super.onDestroy()
    }
}
