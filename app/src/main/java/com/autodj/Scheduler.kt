package com.autodj

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.*

object Scheduler {
    fun occursOn(i: Item, d: LocalDate): Boolean {
        if (i.endDate.isNotEmpty() && d.isAfter(LocalDate.parse(i.endDate))) return false
        if (i.holidayUntil.isNotEmpty() && !d.isAfter(LocalDate.parse(i.holidayUntil))) return false
        return when (i.repeat) {
            "COUNTDOWN" -> false
            "MONTHLY" -> { val a = LocalDate.parse(i.date); !d.isBefore(a) && d.dayOfMonth == minOf(a.dayOfMonth, d.lengthOfMonth()) }
            "YEARLY" -> { val a = LocalDate.parse(i.date); !d.isBefore(a) && d.monthValue == a.monthValue && d.dayOfMonth == minOf(a.dayOfMonth, d.lengthOfMonth()) }
            "ONCE" -> d.toString() == i.date
            "DAILY" -> true
            "WEEKDAYS" -> d.dayOfWeek.value in i.days
            else -> i.days.isEmpty() || d.dayOfWeek.value in i.days
        }
    }
    private fun slots(i: Item, d: LocalDate, z: ZoneId): List<ZonedDateTime> =
        if (i.repeat == "INTERVAL") {
            val step = i.intervalMin.coerceAtLeast(1)
            (i.activeFrom * 60..i.activeTo * 60 step step).filter { it < 1440 }.map { d.atTime(it / 60, it % 60).atZone(z) }
        } else listOf(d.atTime(i.hour, i.minute).atZone(z))

    fun candidates(i: Item, now: ZonedDateTime): Sequence<ZonedDateTime> = sequence {
        for (n in 0L..400L) {
            val d = now.toLocalDate().plusDays(n)
            if (occursOn(i, d)) for (t in slots(i, d, now.zone)) yield(t)
        }
    }
    /** honorSkip=true is for display ("next alarm"); the OS alarm is set with false so the receiver can consume the skip. */
    fun nextTrigger(i: Item, now: ZonedDateTime, honorSkip: Boolean = false): ZonedDateTime? {
        if (!i.enabled || i.paused) return null
        if (i.repeat == "COUNTDOWN") return Instant.ofEpochMilli(i.startAt).atZone(now.zone).takeIf { it.isAfter(now) }
        val s = candidates(i, now).filter { it.isAfter(now) }
        return if (honorSkip && i.skipNext) s.drop(1).firstOrNull() else s.firstOrNull()
    }
    fun nextOf(items: List<Item>, now: ZonedDateTime): Pair<Item, ZonedDateTime>? =
        items.mapNotNull { i -> nextTrigger(i, now, true)?.let { i to it } }.minByOrNull { it.second }
    fun today(items: List<Item>, now: ZonedDateTime) =
        items.filter { it.enabled && !it.paused && it.repeat != "INTERVAL" && it.repeat != "COUNTDOWN" && occursOn(it, now.toLocalDate()) }.sortedBy { it.hour * 60 + it.minute }
    fun current(items: List<Item>, now: ZonedDateTime) =
        today(items, now).lastOrNull { it.hour * 60 + it.minute <= now.hour * 60 + now.minute }

    private fun pi(c: Context, id: Long, trigger: Long, snooze: Boolean): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id).putExtra("trigger", trigger).putExtra("snooze", snooze)
        return PendingIntent.getBroadcast(c, id.hashCode() * 2 + if (snooze) 1 else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun canExact(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun set(c: Context, id: Long, at: Long, snooze: Boolean, clock: Boolean) {
        val am = c.getSystemService(AlarmManager::class.java); val p = pi(c, id, at, snooze)
        try {
            if (!canExact(c)) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)   // approximate until permission is granted
            else if (clock) {
                val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), p)
            } else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }
    fun cancel(c: Context, id: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, id, 0, false))
    }
    fun schedule(c: Context, i: Item, after: ZonedDateTime = ZonedDateTime.now()) {
        cancel(c, i.id)
        val t = nextTrigger(i, after) ?: return
        set(c, i.id, t.toInstant().toEpochMilli(), false, i.repeat != "INTERVAL")
    }
    fun rescheduleAll(c: Context) = Store.items.forEach { if (it.enabled && it.id != Store.TEST_ID) schedule(c, it) }
}
