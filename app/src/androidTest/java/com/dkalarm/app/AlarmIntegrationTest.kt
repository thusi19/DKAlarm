package com.dkalarm.app

import android.app.AlarmManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.dkalarm.app.alarm.AlarmScheduler
import com.dkalarm.app.core.Rules
import com.dkalarm.app.model.Attack
import com.dkalarm.app.model.StoredAttack
import com.dkalarm.app.storage.AlarmStore
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlarmIntegrationTest {
    @Test fun durablePerVillageSeriesAndExactAlarm() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        fun shell(command:String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        val scheduler=AlarmScheduler(context)
        scheduler.cancelAll()
        try {
            assertTrue("Exact alarm permission",scheduler.canScheduleExact())
            assertTrue("Notification permission",scheduler.canNotify())
            val start=Instant.now().plusSeconds(600).let {Instant.ofEpochSecond(it.epochSecond)}
            fun a(id:String,village:String,offset:Long)=Attack(id,village,if(village=="A")"558|423" else "559|424",start.plusSeconds(offset),true,Rules.Noble.YES,Rules.Color.GREEN,reviewed=true)
            val first=a("a1","A",0); val second=a("a2","A",60); val third=a("a3","A",120);val other=a("b1","B",60)
            val store=AlarmStore(context)
            assertEquals(2,store.merge(listOf(first,second)))
            assertEquals(0,store.merge(listOf(first,second)))
            assertEquals(2,store.merge(listOf(third,other)))
            val reopened=AlarmStore(context).all()
            assertEquals(4,reopened.size)
            val enabled=scheduler.enabledIdentities(reopened)
            assertEquals(setOf(first.identity,third.identity,other.identity),enabled)
            scheduler.reconcile()
            val next=context.getSystemService(AlarmManager::class.java).nextAlarmClock
            assertNotNull(next)
            assertEquals(first.alarmTime!!.toEpochMilli(),next.triggerTime)
            store.fired(setOf(first.identity))
            assertFalse(scheduler.slots().any { slot->slot.attacks.any {it.identity==first.identity} })
            assertFalse(scheduler.slots().any { slot->slot.attacks.any {it.identity==second.identity} })
        } finally {scheduler.cancelAll()}
    }
}
