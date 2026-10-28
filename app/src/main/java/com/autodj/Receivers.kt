package com.autodj

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        val id = i.getLongExtra("id", 0); val trigger = i.getLongExtra("trigger", 0)
        val item = Store.items.find { it.id == id } ?: return
        val now = System.currentTimeMillis()
        if (i.getBooleanExtra("snooze", false)) { start(c, id); return }      // snooze: just ring again
        if (item.lastFired >= trigger) return                                   // duplicate delivery: ignore
        Store.update(id) { it.copy(lastFired = trigger) }
        val next = ZonedDateTime.now().let { n -> if (n.toInstant().toEpochMilli() < trigger) java.time.Instant.ofEpochMilli(trigger).atZone(n.zone) else n }
        if (item.repeat == "ONCE" || item.repeat == "COUNTDOWN") Store.update(id) { it.copy(enabled = false) } else Scheduler.schedule(c, Store.items.first { it.id == id }, next)
        if (now - trigger > (if (item.missedRule == "LATE") 60 else 10) * 60_000L) { Store.log("Missed ${item.title} (phone was off); not replayed"); return }   // no backlog replay
        if (item.skipNext) { Store.update(id) { it.copy(skipNext = false) }; Store.log("Skipped ${item.title}"); return }
        if (item.repeat == "INTERVAL" && inQuiet(next.hour)) { Store.log("Quiet hours: skipped ${item.title}"); return }
        Store.log("Ran ${item.title}")
        if (!item.speak && !item.music && !item.bell) Notify.show(c, item) else start(c, id)
    }
    private fun inQuiet(h: Int): Boolean { val s = Store.settings; return if (s.quietFrom > s.quietTo) h >= s.quietFrom || h < s.quietTo else h in s.quietFrom until s.quietTo }
    private fun start(c: Context, id: Long) =
        ContextCompat.startForegroundService(c, Intent(c, PlaybackService::class.java).putExtra("id", id))
}

object Notify {
    fun show(c: Context, i: Item) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) { Store.log("Notification blocked: allow notifications for Auto DJ"); return }
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminder", "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(i.id.hashCode(), NotificationCompat.Builder(c, "reminder").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(i.title).setContentText(i.message.ifBlank { "Reminder" }).setAutoCancel(true).build())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Store.init(c); Scheduler.rescheduleAll(c); Store.log("Alarms restored (${i.action?.substringAfterLast('.')})") }
}
