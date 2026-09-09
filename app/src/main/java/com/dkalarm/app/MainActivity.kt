package com.dkalarm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.dkalarm.app.analysis.AttackClassifier
import com.dkalarm.app.analysis.ScreenshotAnalyzer
import com.dkalarm.app.alarm.AlarmScheduler
import com.dkalarm.app.alarm.ScheduleResult
import com.dkalarm.app.model.AttackCandidate
import com.dkalarm.app.model.AttackColor
import com.dkalarm.app.model.CrownState
import com.dkalarm.app.model.EditableAttack
import com.dkalarm.app.model.ValidationState
import com.dkalarm.app.storage.AlarmStore
import com.dkalarm.app.storage.SettingsStore
import com.dkalarm.app.ui.DKAlarmScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                    DKAlarmScreen(
                        state = vm.uiState,
                        onPick = { picker.launch("image/*") },
                        onLeadChanged = vm::setLeadSeconds,
                        onEdit = vm::editAttack,
                        onSchedule = vm::scheduleConfirmed,
                        onRequestExactPermission = { startActivity(vm.exactAlarmSettingsIntent()) },
                        onClearMessage = vm::clearMessage
                    )
                    LaunchedEffect(Unit) {
                        handleIntent(intent)
                    }
                }
            }
        }
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
    val screenshotDuplicate: Boolean = false
)

class DKAlarmViewModel(private val activity: MainActivity) : ViewModel() {
    private val analyzer = ScreenshotAnalyzer()
    private val scheduler = AlarmScheduler(activity)
    private val alarmStore = AlarmStore(activity)
    private val settings = SettingsStore(activity)
    private val classifier = AttackClassifier()

    var uiState by mutableStateOf(DKAlarmUiState(leadSeconds = settings.leadSeconds))
        private set

    fun analyzeUri(uri: Uri) {
        activity.lifecycleScope.launch {
            uiState = uiState.copy(analyzing = true, message = null, screenshotDuplicate = false)
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
                val candidates = analyzer.analyze(bitmap, Instant.now())
                val editable = candidates.map { it.toEditable() }
                val duplicate = candidates.firstOrNull()?.sourceScreenshotHash?.let(alarmStore::isScreenshotSeen) == true
                uiState = uiState.copy(
                    analyzing = false,
                    attacks = editable,
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

    fun setLeadSeconds(value: Int) {
        settings.leadSeconds = value
        uiState = uiState.copy(leadSeconds = settings.leadSeconds)
    }

    fun editAttack(index: Int, edited: EditableAttack) {
        val list = uiState.attacks.toMutableList()
        if (index !in list.indices) return
        list[index] = edited
        uiState = uiState.copy(attacks = list)
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
        uiState = uiState.copy(
            exactPermissionMissing = false,
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

    fun parseEditedArrival(text: String): Instant? {
        val cleaned = text.trim()
        val formats = listOf("H:mm:ss", "HH:mm:ss", "d.M.yyyy H:mm:ss", "dd.MM.yyyy HH:mm:ss")
        val zone = ZoneId.systemDefault()
        formats.forEach { pattern ->
            runCatching {
                if (pattern.startsWith("H") || pattern.startsWith("HH")) {
                    val time = java.time.LocalTime.parse(cleaned, DateTimeFormatter.ofPattern(pattern))
                    val now = LocalDateTime.now(zone)
                    var dt = now.toLocalDate().atTime(time)
                    if (dt.atZone(zone).toInstant().isBefore(Instant.now().minusSeconds(120))) dt = dt.plusDays(1)
                    return dt.atZone(zone).toInstant()
                } else {
                    return LocalDateTime.parse(cleaned, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant()
                }
            }
        }
        return null
    }

    class Factory(private val activity: MainActivity) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DKAlarmViewModel(activity) as T
    }
}
