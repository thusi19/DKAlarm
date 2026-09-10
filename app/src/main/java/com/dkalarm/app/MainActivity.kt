package com.dkalarm.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import com.dkalarm.app.alarm.AlarmScheduler
import com.dkalarm.app.alarm.AlarmSoundService
import com.dkalarm.app.analysis.ScreenshotAnalyzer
import com.dkalarm.app.core.Rules
import com.dkalarm.app.model.*
import com.dkalarm.app.storage.AlarmStore
import com.dkalarm.app.ui.DKAlarmScreen
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var vm: DKAlarmViewModel
    private val picker=registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.analyze(it) } }
    private val notifyPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm=ViewModelProvider(this)[DKAlarmViewModel::class.java]
        setContent { MaterialTheme { DKAlarmScreen(vm,
            onPick={picker.launch("image/*")},
            onExact={startActivity(vm.scheduler.exactAlarmSettingsIntent())},
            onNotifications={if(Build.VERSION.SDK_INT>=33&&!vm.scheduler.canNotify())notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else startActivity(vm.scheduler.notificationSettingsIntent())},
            onNotificationSettings={startActivity(vm.scheduler.notificationSettingsIntent())}) } }
        if(savedInstanceState==null)handle(intent)
    }
    override fun onResume() {super.onResume();if(::vm.isInitialized)vm.refresh()}
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);handle(intent)}
    private fun handle(intent:Intent?) {
        val uri:Uri?=when(intent?.action) {
            Intent.ACTION_SEND -> if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        uri?.let { vm.analyze(it) }
    }
}

class DKAlarmViewModel(app:Application):AndroidViewModel(app) {
    val scheduler=AlarmScheduler(app)
    private val store=AlarmStore(app)
    private val analyzer=ScreenshotAnalyzer()
    var attacks by mutableStateOf<List<Attack>>(emptyList());private set
    var stored by mutableStateOf<List<StoredAttack>>(emptyList());private set
    var bitmap by mutableStateOf<Bitmap?>(null);private set
    var screenshotDate by mutableStateOf(LocalDate.now(GAME_ZONE));private set
    var busy by mutableStateOf(false);private set
    var progress by mutableStateOf("");private set
    var message by mutableStateOf("");private set
    var exact by mutableStateOf(false);private set
    var notifications by mutableStateOf(false);private set
    private var job:Job?=null
    private var lastUri:Uri?=null
    private var importGeneration=0
    init {refresh()}
    fun refresh() {
        exact=scheduler.canScheduleExact();notifications=scheduler.canNotify()
        runCatching {stored=store.all();scheduler.reconcile()}.onFailure {message="Obnovení alarmů selhalo: ${it.message}"}
    }
    fun setDate(date:LocalDate) {screenshotDate=date;lastUri?.let(::analyze)}
    fun analyze(uri:Uri) {
        job?.cancel();lastUri=uri
        val generation=++importGeneration
        job=viewModelScope.launch {
            busy=true;progress="Připravuji screenshot…";message="";attacks=emptyList()
            try {
                val decoded=withContext(Dispatchers.IO) {
                    val resolver=getApplication<Application>().contentResolver
                    if(Build.VERSION.SDK_INT>=28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri)) { decoder,info,_ ->
                        require(info.size.width.toLong()*info.size.height<=32_000_000) {"Obrázek je příliš velký. Rozděl ho na několik screenshotů."}
                        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    } else {
                        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) }
                        require(options.outWidth.toLong()*options.outHeight<=32_000_000){"Obrázek je příliš velký."}
                        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: error("Obrázek nelze otevřít.")
                    }
                }
                bitmap=decoded
                attacks=analyzer.analyze(decoded,screenshotDate) { done,total -> viewModelScope.launch {if(generation==importGeneration)progress="Čtu řádek $done / $total"} }
                message="Rozpoznáno ${attacks.size} řádků. Potvrzené Šlechty: ${attacks.count {it.isNoble}}. Nejisté jednotky: ${attacks.count {it.nobleState==Rules.Noble.MAYBE}}. Zkontroluj datum a označené řádky."
            } catch(e:CancellationException) {throw e}
            catch(e:Exception){message="Analýza selhala: ${e.message}"}
            finally {if(generation==importGeneration)busy=false}
        }
    }
    fun edit(id:String,value:Attack) {attacks=attacks.map {if(it.id==id)value else it}}
    fun plannedPreview():Set<String> {
        val combined=(stored.map {it.attack}+attacks.filter {it.selected&&it.reviewed&&it.important&&it.arrivalTime!=null}).distinctBy {it.identity}
        return Rules.enabled(combined.map {Rules.Event(it.identity,it.villageKey,it.alarmTime!!.epochSecond)})
    }
    fun save() {
        if(!scheduler.canScheduleExact()||!scheduler.canNotify()) {refresh();message="Nejdřív povol oznámení a přesné alarmy.";return}
        val ready=attacks.filter {it.selected&&it.reviewed&&it.nobleState!=Rules.Noble.MAYBE&&it.important&&it.villageName.isNotBlank()&&it.alarmTime?.isAfter(Instant.now())==true}
        val unresolved=attacks.count {it.selected&&!it.reviewed}
        if(ready.isEmpty()){message="Žádný potvrzený důležitý útok s budoucím časem alarmu. Ke kontrole: $unresolved.";return}
        try {
            val added=store.merge(ready);scheduler.reconcile();stored=store.all()
            val enabled=scheduler.enabledIdentities(stored)
            val suppressed=ready.count {it.identity !in enabled}
            message="Uloženo $added nových útoků; ${ready.size-added} už existovalo. V minutové sérii vynecháno $suppressed. Ke kontrole zbývá $unresolved."
        } catch(e:Exception){message="Alarmy se nepodařilo aktivovat: ${e.message}. Zkontroluj oprávnění a zkus znovu."}
        exact=scheduler.canScheduleExact();notifications=scheduler.canNotify()
    }
    fun cancel(identity:String) {runCatching {store.remove(identity);scheduler.reconcile();stored=store.all()}.onFailure {message="Zrušení selhalo: ${it.message}"}}
    fun clearAll() {scheduler.cancelAll();stored=emptyList();message="Všechny uložené alarmy jsou zrušeny."}
    fun testAlarm() {
        if(!scheduler.canNotify()){message="Povol oznámení a hlasitost budíku.";return}
        getApplication<Application>().startForegroundService(Intent(getApplication(),AlarmSoundService::class.java).setAction("TEST"))
    }
}
