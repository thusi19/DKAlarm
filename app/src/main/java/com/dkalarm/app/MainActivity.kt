package com.dkalarm.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dkalarm.app.alarm.AlarmScheduler
import com.dkalarm.app.alarm.ScheduleResult
import com.dkalarm.app.analysis.ScreenshotAnalyzer
import com.dkalarm.app.capture.QuickScanService
import com.dkalarm.app.model.AttackCandidate
import com.dkalarm.app.model.CrownState
import com.dkalarm.app.model.EditableAttack
import com.dkalarm.app.model.ValidationState
import com.dkalarm.app.storage.AlarmStore
import com.dkalarm.app.storage.ReviewStore
import com.dkalarm.app.storage.SettingsStore
import com.dkalarm.app.ui.DKAlarmScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var vm: DKAlarmViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this, DKAlarmViewModel.Factory(this))[DKAlarmViewModel::class.java]
        requestNotificationsIfNeeded()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) vm.analyzeUri(uri)
                    }
                    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        val data = result.data
                        if (result.resultCode == Activity.RESULT_OK && data != null) {
                            QuickScanService.start(this@MainActivity, result.resultCode, data)
                            vm.setQuickScanActive(true)
                        } else {
                            vm.setMessage("Sdílení obrazovky nebylo povoleno. Rychlý SCAN se nespustil.")
                        }
                    }

                    DKAlarmScreen(
                        state = vm.uiState,
                        onPick = { picker.launch("image/*") },
                        onStartQuickScan = {
                            if (!Settings.canDrawOverlays(this@MainActivity)) {
                                vm.setMessage("Nejdřív povol DK Alarmu zobrazit plovoucí tlačítko nad ostatními aplikacemi.")
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            } else {
                                val manager = getSystemService(MediaProjectionManager::class.java)
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        },
                        onStopQuickScan = {
                            startService(Intent(this@MainActivity, QuickScanService::class.java).setAction(QuickScanService.ACTION_STOP))
                            vm.setQuickScanActive(false)
                        },
                        onLeadChanged = vm::setLeadSeconds,
                        onEdit = vm::editAttack,
                        onSchedule = vm::scheduleConfirmed,
                        onRequestExactPermission = { startActivity(vm.exactAlarmSettingsIntent()) },
                        onClearMessage = vm::clearMessage
                    )

                    LaunchedEffect(Unit) { handleIntent(intent) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) vm.loadPendingReviews()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { handleIntent(intent) }
    }

    private suspend fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { vm.analyzeUri(it) }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

data class DKAlarmUiState(
    val analyzing: Boolean = false,
    val attacks: List<EditableAttack> = emptyList(),
    val leadSeconds: Int = 60,
    val message: String? = null,
    val exactPermissionMissing: Boolean = false,
    val screenshotDuplicate: Boolean = false,
    val quickScanActive: Boolean = false,
    val pendingReviewCount: Int = 0
)

class DKAlarmViewModel(private val activity: MainActivity) : ViewModel() {
    private val analyzer = ScreenshotAnalyzer()
    private val scheduler = AlarmScheduler(activity)
    private val alarmStore = AlarmStore(activity)
    private val reviewStore = ReviewStore(activity)
    private val settings = SettingsStore(activity)

    var uiState by androidx.compose.runtime.mutableStateOf(
        DKAlarmUiState(leadSeconds = settings.leadSeconds, attacks = reviewStore.load(), pendingReviewCount = reviewStore.load().size)
    )
        private set

    fun analyzeUri(uri: Uri) {
        activity.lifecycleScope.launch {
            uiState = uiState.copy(analyzing = true, message = null, screenshotDuplicate = false)
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
                val candidates = analyzer.analyze(bitmap, Instant.now())
                bitmap.recycle()
                val editable = candidates.map { it.toEditable() }
                val duplicate = candidates.firstOrNull()?.sourceScreenshotHash?.let(alarmStore::isScreenshotSeen) == true
                uiState = uiState.copy(
                    analyzing = false,
                    attacks = editable,
                    pendingReviewCount = editable.count { it.crown == CrownState.MAYBE || it.validation != ValidationState.OK },
                    screenshotDuplicate = duplicate,
                    message = when {
                        editable.isEmpty() -> "Na screenshotu jsem nenašel žádný spolehlivě oddělený řádek s časem."
                        duplicate -> "Tento screenshot už byl importován. Výsledky můžeš zkontrolovat, duplicitní alarmy se nevytvoří."
                        else -> "Nalezeno ${editable.size} řádků. Před alarmy zkontroluj hlavně korunky a čas."
                    }
                )
            } catch (t: Throwable) {
                uiState = uiState.copy(analyzing = false, message = "Analýza selhala: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun loadPendingReviews() {
        val pending = reviewStore.load()
        if (pending.isNotEmpty()) {
            uiState = uiState.copy(
                attacks = pending,
                pendingReviewCount = pending.size,
                message = "Rychlý SCAN našel ${pending.size} řádků, které potřebují kontrolu."
            )
        } else {
            uiState = uiState.copy(pendingReviewCount = 0)
        }
    }

    fun setQuickScanActive(value: Boolean) { uiState = uiState.copy(quickScanActive = value) }
    fun setMessage(value: String) { uiState = uiState.copy(message = value) }

    fun setLeadSeconds(value: Int) {
        settings.leadSeconds = value
        uiState = uiState.copy(leadSeconds = settings.leadSeconds)
    }

    fun editAttack(index: Int, edited: EditableAttack) {
        val list = uiState.attacks.toMutableList()
        if (index !in list.indices) return
        list[index] = edited
        reviewStore.save(list)
        uiState = uiState.copy(attacks = list, pendingReviewCount = list.count { it.crown == CrownState.MAYBE || it.validation != ValidationState.OK })
    }

    fun scheduleConfirmed() {
        val unresolved = uiState.attacks.filter { it.crown == CrownState.MAYBE || it.arrivalInstant == null || it.validation != ValidationState.OK }
        if (unresolved.isNotEmpty()) {
            uiState = uiState.copy(message = "Nejdřív oprav všechny řádky označené ZKONTROLOVAT. U nejisté korunky zvol ANO nebo NE.")
            return
        }
        if (!scheduler.canScheduleExact()) {
            uiState = uiState.copy(exactPermissionMissing = true, message = "Android zatím nepovoluje přesné alarmy. Povol aplikaci „Alarmy a připomenutí“ a pak potvrď znovu.")
            return
        }

        var scheduled = 0
        var duplicates = 0
        var skipped = 0
        uiState.attacks.forEach { a ->
            val arrival = a.arrivalInstant ?: return@forEach
            when (scheduler.schedule(a.targetVillage, arrival, uiState.leadSeconds, a.crown, a.color, a.sourceScreenshotHash)) {
                is ScheduleResult.Scheduled -> scheduled++
                is ScheduleResult.Duplicate -> duplicates++
                is ScheduleResult.Invalid -> skipped++
                ScheduleResult.ExactPermissionRequired -> Unit
            }
        }
        uiState.attacks.firstOrNull()?.sourceScreenshotHash?.let(alarmStore::markScreenshotSeen)
        reviewStore.clear()
        uiState = uiState.copy(
            exactPermissionMissing = false,
            attacks = emptyList(),
            pendingReviewCount = 0,
            message = "Alarmy: vytvořeno $scheduled, duplicitních $duplicates, bez alarmu $skipped."
        )
    }

    fun exactAlarmSettingsIntent(): Intent = scheduler.exactAlarmSettingsIntent()
    fun clearMessage() { uiState = uiState.copy(message = null) }

    private fun decodeBitmap(uri: Uri): Bitmap {
        val input = activity.contentResolver.openInputStream(uri) ?: error("Nelze otevřít obrázek")
        input.use { return BitmapFactory.decodeStream(it) ?: error("Nelze dekódovat screenshot") }
    }

    private fun AttackCandidate.toEditable() = EditableAttack(
        id = id,
        targetVillage = targetVillage.text,
        arrivalText = absoluteArrivalText.text,
        arrivalInstant = arrivalInstant,
        color = color,
        crown = crown,
        validation = validation,
        warnings = warnings,
        sourceScreenshotHash = sourceScreenshotHash
    )

    class Factory(private val activity: MainActivity) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DKAlarmViewModel(activity) as T
    }
}
