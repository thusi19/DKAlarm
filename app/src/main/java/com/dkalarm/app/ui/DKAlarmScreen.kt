package com.dkalarm.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dkalarm.app.DKAlarmViewModel
import com.dkalarm.app.core.Rules
import com.dkalarm.app.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Composable
fun DKAlarmScreen(vm:DKAlarmViewModel,onPick:()->Unit,onExact:()->Unit,onNotifications:()->Unit,onNotificationSettings:()->Unit) {
    var tab by remember {mutableStateOf(0)}
    var confirmClear by remember {mutableStateOf(false)}
    val preview=vm.plannedPreview()
    val enabled=vm.scheduler.enabledIdentities(vm.stored)
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("DK Alarm",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("Útoky ze screenshotu • alarm 1 minutu před dopadem",style=MaterialTheme.typography.bodyMedium)
            Text("Časy hry: Praha",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(tab==0,{tab=0},{Text("Import")})
                FilterChip(tab==1,{tab=1},{Text("Alarmy (${vm.stored.count {!it.fired&&it.attack.alarmTime!!.isAfter(Instant.now())}})")})
            }
        }
        item {
            if(!vm.exact||!vm.notifications)Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Dokonči nastavení",fontWeight=FontWeight.Bold)
                    if(!vm.notifications) {
                        Button(onNotifications){Text("Povolit oznámení")}
                        TextButton(onNotificationSettings){Text("Otevřít nastavení oznámení")}
                    }
                    if(!vm.exact)Button(onExact){Text("Povolit přesné alarmy")}
                }
            }
        }
        if(vm.message.isNotBlank())item {Text(vm.message,color=MaterialTheme.colorScheme.primary)}
        if(tab==0) {
            item {
                var dateText by remember(vm.screenshotDate){mutableStateOf(vm.screenshotDate.toString())}
                Text("Datum pořízení screenshotu",fontWeight=FontWeight.SemiBold)
                Text("Podle něj se přepočítá „dnes“ a „zítra“. Starší obrázek potřebuje původní datum.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(dateText,{dateText=it},label={Text("RRRR-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                val date=runCatching {LocalDate.parse(dateText)}.getOrNull()
                if(date!=null&&date!=vm.screenshotDate)OutlinedButton({vm.setDate(date)},enabled=!vm.busy){Text("Použít datum a přepočítat")}
                Button(onPick,enabled=!vm.busy&&date==vm.screenshotDate,modifier=Modifier.fillMaxWidth()){Text("Vybrat screenshot")}
                if(vm.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(vm.progress)}
            }
            if(vm.attacks.isNotEmpty())item {
                Button({vm.save()},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("Aktivovat potvrzené alarmy")}
                Text("Nejisté řádky se neaktivují. Minutové série: 1., 3., 5. alarm, samostatně pro každou vesnici.",style=MaterialTheme.typography.bodySmall)
            }
            items(vm.attacks,key={it.id}) {attack->
                ReviewCard(attack,attack.identity in preview,vm)
            }
        } else {
            item {
                OutlinedButton({vm.testAlarm()}){Text("Vyzkoušet zvuk a oznámení")}
                Text("Zvuk se ztiší tlačítkem v oznámení nebo za 60 sekund. Zkontroluj hlasitost budíku.",style=MaterialTheme.typography.bodySmall)
                if(vm.stored.isNotEmpty())TextButton({confirmClear=true}){Text("Zrušit všechny alarmy")}
                if(vm.stored.isEmpty())Text("Zatím tu nejsou uložené alarmy.")
            }
            items(vm.stored.sortedBy {it.attack.arrivalTime},key={it.attack.identity}) {entry->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        val a=entry.attack
                        Text(a.villageName,fontWeight=FontWeight.Bold)
                        Text(a.label)
                        Text("Příchod: ${DATE_FORMAT.format(a.arrivalTime!!)}")
                        Text(when {
                            entry.fired -> "Alarm již zazněl"
                            a.alarmTime!!.isBefore(Instant.now()) -> "Čas alarmu uplynul"
                            a.identity !in enabled -> "Vynechán v minutové sérii"
                            !vm.exact||!vm.notifications -> "ČEKÁ NA OPRÁVNĚNÍ"
                            else -> "Alarm: ${TIME_FORMAT.format(a.alarmTime!!)}"
                        })
                        TextButton({vm.cancel(a.identity)}){Text("Odstranit")}
                    }
                }
            }
        }
        item {Spacer(Modifier.height(24.dp))}
    }
    if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text("Zrušit všechny alarmy?")},
        text={Text("Odstraní se i uložené minutové série.")},confirmButton={TextButton({vm.clearAll();confirmClear=false}){Text("Zrušit alarmy")}},
        dismissButton={TextButton({confirmClear=false}){Text("Zpět")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(a:Attack,enabled:Boolean,vm:DKAlarmViewModel) {
    var expanded by remember(a.id){mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(a.villageName,Modifier.weight(1f),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                Checkbox(a.selected,{vm.edit(a.id,a.copy(selected=it))})
            }
            Text(if(a.nobleState==Rules.Noble.MAYBE)"JEDNOTKA NEJISTÁ / ZKONTROLOVAT • ${Rules.label(false,a.attackColor)}" else a.label,fontWeight=FontWeight.SemiBold)
            Text("Příchod: ${a.arrivalTime?.let(TIME_FORMAT::format) ?: "NEROZPOZNÁN"}")
            if(a.arrivalTime!=null)Text("Datum: ${a.arrivalTime.atZone(GAME_ZONE).toLocalDate()}",style=MaterialTheme.typography.bodySmall)
            Text(when {
                !a.selected -> "Nezařazen"
                !a.reviewed -> "Alarm čeká na kontrolu"
                !a.important -> "Běžný zelený útok – bez alarmu"
                a.alarmTime==null -> "Alarm: neplatný čas"
                !a.alarmTime!!.isAfter(Instant.now()) -> "Čas alarmu už uplynul"
                !enabled -> "Alarm: vynechán v minutové sérii"
                else -> "Alarm: ${TIME_FORMAT.format(a.alarmTime!!)}"
            })
            TextButton({expanded=!expanded}){Text(if(expanded)"Skrýt kontrolu" else "Zkontrolovat / upravit")}
            if(expanded) {
                val source=vm.bitmap
                if(source!=null&&a.rowBottom>a.rowTop) {
                    val crop=remember(source,a.id){android.graphics.Bitmap.createBitmap(source,0,a.rowTop,source.width,a.rowBottom-a.rowTop)}
                    Text("Původní řádek – posuň do strany pro zvětšené čtení",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Image(crop.asImageBitmap(),"Původní řádek útoku",Modifier.width(1400.dp).height(96.dp))
                    }
                }
                Text("Přečtená jednotka: ${a.rawCommand}",style=MaterialTheme.typography.bodySmall)
                Text("Přečtený příchod: ${a.rawArrival}",style=MaterialTheme.typography.bodySmall)
                if(a.warning.isNotBlank())Text(a.warning,color=MaterialTheme.colorScheme.error)
                OutlinedTextField(a.villageName,{vm.edit(a.id,a.copy(villageName=it,reviewed=false))},label={Text("Název cílové vesnice")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(a.coordinates,{vm.edit(a.id,a.copy(coordinates=it,reviewed=false))},label={Text("Souřadnice pro rozlišení vesnic")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                var text by remember(a.id){mutableStateOf(a.arrivalTime?.let(DATE_FORMAT::format).orEmpty())}
                OutlinedTextField(text,{value->
                    text=value
                    val time=runCatching {
                        LocalDateTime.parse(value,DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).let { local ->
                            require(GAME_ZONE.rules.getValidOffsets(local).size==1);local.atZone(GAME_ZONE).toInstant()
                        }
                    }.getOrNull()
                    vm.edit(a.id,a.copy(arrivalTime=time,reviewed=false))
                },label={Text("Příchod: dd.MM.rrrr HH:mm:ss")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                Text("Je v povelu text Šlechta?",fontWeight=FontWeight.SemiBold)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Rules.Noble.values().forEach { state -> FilterChip(a.nobleState==state,
                        {vm.edit(a.id,a.copy(nobleState=state,isNoble=state==Rules.Noble.YES,reviewed=false))},
                        {Text(when(state){Rules.Noble.YES->"Ano";Rules.Noble.NO->"Ne";else->"Nejisté"})}) }
                }
                Text("Barva útoku")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Rules.Color.values().forEach {color->FilterChip(a.attackColor==color,
                        {vm.edit(a.id,a.copy(attackColor=color,reviewed=false))},
                        {Text(when(color){Rules.Color.GREEN->"Zelená";Rules.Color.RED->"Červená";Rules.Color.BROWN->"Hnědá";else->"Nejistá"})})}
                }
                Button({vm.edit(a.id,a.copy(reviewed=true,warning=""))},
                    enabled=a.arrivalTime!=null&&a.villageName.isNotBlank()&&a.nobleState!=Rules.Noble.MAYBE&&
                        (a.coordinates.isBlank()||Regex("\\d{3}\\|\\d{3}").matches(a.coordinates))&&
                        (a.attackColor!=Rules.Color.UNKNOWN||a.isNoble)) {Text("Potvrzuji údaje tohoto řádku")}
                Text("Korunka sama Šlechtu nepotvrzuje.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
