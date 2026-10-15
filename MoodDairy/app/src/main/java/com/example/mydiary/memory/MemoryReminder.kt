package com.example.mydiary.memory

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.local.SecurePreferences
import kotlinx.coroutines.*
import org.json.JSONArray

class MemoryReminder : JobService() {
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        work=CoroutineScope(Dispatchers.IO).launch {
            try {
                val session=SessionManager(this@MemoryReminder)
                if(session.isLoggedIn()) {
                    val store=MemoryStore(this@MemoryReminder,session)
                    val rows=(store.api("future-letters") as JSONArray).objects()
                    val prefs=SecurePreferences.create(this@MemoryReminder,"memory_reminders_secure")
                    val due=rows.filter{it.optString("state")=="sealed"&&it.optBoolean("due")&&!prefs.getBoolean("${store.user}_${it.getString("id")}",false)}
                    val manager=getSystemService(NotificationManager::class.java)
                    if(due.isNotEmpty()&&session.getUserId()==store.user&&manager.areNotificationsEnabled()) {
                        manager.createNotificationChannel(NotificationChannel("future_letters","时光信箱",NotificationManager.IMPORTANCE_DEFAULT))
                        val intent=android.content.Intent(this@MemoryReminder,MemoryActivity::class.java)
                        val pending=android.app.PendingIntent.getActivity(this@MemoryReminder,1744,intent,android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                        manager.notify(1744,NotificationCompat.Builder(this@MemoryReminder,"future_letters").setSmallIcon(android.R.drawable.ic_dialog_email)
                            .setContentTitle("时光信箱有新消息").setContentText("有一封到期的信，等你准备好时再打开。")
                            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                        for(row in due) prefs.edit().putBoolean("${store.user}_${row.getString("id")}",true).apply()
                    }
                }
            } catch(_:Exception) { /* Next scheduled run or foreground refresh will retry. */ }
            finally { jobFinished(params,false) }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {work?.cancel(); return true}
    companion object {
        fun schedule(context:Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(1744,ComponentName(context,MemoryReminder::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6*60*60*1000L).setPersisted(true).build())
        }
    }
}
