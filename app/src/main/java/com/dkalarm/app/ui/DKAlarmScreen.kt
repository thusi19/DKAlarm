package com.dkalarm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dkalarm.app.DKAlarmUiState
import com.dkalarm.app.model.AttackColor
import com.dkalarm.app.model.CrownState
import com.dkalarm.app.model.EditableAttack
import com.dkalarm.app.model.ValidationState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DKAlarmScreen(
    state: DKAlarmUiState,
    onPick: () -> Unit,
    onLeadChanged: (Int) -> Unit,
    onEdit: (Int, EditableAttack) -> Unit,
    onSchedule: () -> Unit,
    onRequestExactPermission: () -> Unit,
    onClearMessage: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("DK Alarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Screenshot → kontrola → přesný alarm. Žádné přihlášení ani automatické čtení DK.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onPick, enabled = !state.analyzing) { Text("Vybrat screenshot") }
            if (state.analyzing) {
                Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Analyzuji lokálně…")
            }
        }

        state.message?.let { message ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message)
                        if (state.exactPermissionMissing) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestExactPermission) { Text("Povolit přesné alarmy") }
                        }
                    }
                }
            }
        }

        if (state.attacks.isNotEmpty()) {
            item {
                Text("Předstih alarmu", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30,45,60,90,120).forEach { seconds ->
                        FilterChip(
                            selected = state.leadSeconds == seconds,
                            onClick = { onLeadChanged(seconds) },
                            label = { Text("$seconds s") }
                        )
                    }
                }
                var custom by remember(state.leadSeconds) { mutableStateOf(state.leadSeconds.toString()) }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { value ->
                        custom = value.filter(Char::isDigit).take(4)
                        custom.toIntOrNull()?.let(onLeadChanged)
                    },
                    label = { Text("Vlastní předstih (s)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        itemsIndexed(state.attacks, key = { _, it -> it.id }) { index, attack ->
            AttackReviewCard(index, attack, onEdit)
        }

        if (state.attacks.isNotEmpty()) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSchedule, modifier = Modifier.fillMaxWidth()) {
                    Text("Potvrdit a vytvořit alarmy")
                }
                Text(
                    "Řádek s nejistou korunkou nebo časem se musí ručně vyřešit. Zelený útok bez korunky se podle zadání nealarmuje.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AttackReviewCard(index: Int, attack: EditableAttack, onEdit: (Int, EditableAttack) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val headline = when (attack.crown) {
                CrownState.YES -> "👑 ŠLECHTA"
                CrownState.MAYBE -> "⚠️ MOŽNÁ ŠLECHTA – ZKONTROLOVAT"
                CrownState.NO -> when (attack.color) {
                    AttackColor.RED -> "🔴 ČERVENÝ"
                    AttackColor.BROWN -> "🟤 HNĚDÝ"
                    AttackColor.GREEN -> "🟢 ZELENÝ"
                    else -> "ÚTOK"
                }
            }
            Text(headline, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = attack.targetVillage,
                onValueChange = { onEdit(index, attack.copy(targetVillage = it)) },
                label = { Text("Cílová vesnice") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            var arrivalText by remember(attack.id, attack.arrivalText) { mutableStateOf(attack.arrivalText) }
            OutlinedTextField(
                value = arrivalText,
                onValueChange = { txt ->
                    arrivalText = txt
                    val parsed = parseArrival(txt)
                    onEdit(index, attack.copy(arrivalText = txt, arrivalInstant = parsed, validation = if (parsed != null) ValidationState.OK else ValidationState.CHECK_TIME))
                },
                label = { Text("Příchod (HH:mm:ss nebo dd.MM.yyyy HH:mm:ss)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Korunka – rozhodující znak", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CrownState.entries.forEach { state ->
                    FilterChip(
                        selected = attack.crown == state,
                        onClick = { onEdit(index, attack.copy(crown = state)) },
                        label = { Text(when(state) { CrownState.YES -> "ANO"; CrownState.MAYBE -> "NEJISTÁ"; CrownState.NO -> "NE" }) }
                    )
                }
            }

            Text("Barva")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(AttackColor.GREEN, AttackColor.RED, AttackColor.BROWN, AttackColor.UNKNOWN).forEach { color ->
                    FilterChip(
                        selected = attack.color == color,
                        onClick = { onEdit(index, attack.copy(color = color)) },
                        label = { Text(color.name) }
                    )
                }
            }

            if (attack.validation != ValidationState.OK) {
                Text("⚠️ ČAS ZKONTROLOVAT", fontWeight = FontWeight.Bold)
            } else {
                Text("✓ čas potvrzen / opraven")
            }
            attack.warnings.distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun parseArrival(text: String): Instant? {
    val zone = ZoneId.systemDefault()
    val cleaned = text.trim()
    val dtFormats = listOf("d.M.yyyy H:mm:ss", "dd.MM.yyyy HH:mm:ss")
    for (pattern in dtFormats) {
        runCatching { LocalDateTime.parse(cleaned, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant() }.getOrNull()?.let { return it }
    }
    val timeFormats = listOf("H:mm:ss", "HH:mm:ss")
    for (pattern in timeFormats) {
        val t = runCatching { LocalTime.parse(cleaned, DateTimeFormatter.ofPattern(pattern)) }.getOrNull() ?: continue
        var date = LocalDate.now(zone)
        var instant = date.atTime(t).atZone(zone).toInstant()
        if (instant.isBefore(Instant.now().minusSeconds(120))) {
            date = date.plusDays(1); instant = date.atTime(t).atZone(zone).toInstant()
        }
        return instant
    }
    return null
}
