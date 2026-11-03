package com.dkalarm.app.capture

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import com.dkalarm.app.model.GAME_ZONE

/** Private cache only; exporting a PNG is an explicit user action. */
object CaptureStore {
    data class Shot(val id:String,val file:File,val width:Int,val height:Int,val takenAt:Instant) {
        val date:LocalDate get()=takenAt.atZone(GAME_ZONE).toLocalDate()
    }
    private fun prefs(context:Context)=context.getSharedPreferences("dk_screen_capture",Context.MODE_PRIVATE)
    fun directory(context:Context)=File(context.cacheDir,"screen-captures").apply {mkdirs()}
    fun setActive(context:Context,active:Boolean) {
        prefs(context).edit().putLong("activeUntil",if(active)System.currentTimeMillis()+120_000 else 0).apply()
    }
    fun isActive(context:Context)=prefs(context).getLong("activeUntil",0)>System.currentTimeMillis()
    fun complete(context:Context,shot:Shot) {
        check(prefs(context).edit().putString("id",shot.id).putInt("width",shot.width)
            .putInt("height",shot.height).putLong("takenAt",shot.takenAt.toEpochMilli()).putLong("activeUntil",0).commit())
    }
    fun latest(context:Context):Shot? {
        val p=prefs(context);val id=p.getString("id",null) ?: return null
        if(!Regex("[a-f0-9-]{36}").matches(id))return null
        val file=File(directory(context),"$id.png")
        if(!file.isFile)return null
        return Shot(id,file,p.getInt("width",0),p.getInt("height",0),Instant.ofEpochMilli(p.getLong("takenAt",0)))
    }
    fun unseen(context:Context)=latest(context)?.takeIf {it.id!=prefs(context).getString("seen",null)}
    fun markSeen(context:Context,id:String) {prefs(context).edit().putString("seen",id).apply()}
}
