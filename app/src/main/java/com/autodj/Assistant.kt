package com.autodj

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.time.*
import java.time.format.DateTimeFormatter

/** Direct command parser. Works offline; no cloud AI. English commands only in this version. */
object Assistant {
    sealed class Pending {
        class AmPm(val h: Int, val m: Int, val date: LocalDate?) : Pending()
        class Hours(val base: Item) : Pending()
        class Confirm(val item: Item, val say: String) : Pending()
    }
    var pending: Pending? = null
    var lastCreated: Item? = null
    private val words = listOf("zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve")
    private fun num(s: String) = s.toIntOrNull() ?: words.indexOf(s).takeIf { it >= 0 }
    private fun fmt(h: Int, m: Int) = LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a"))

    fun handle(c: Context, raw: String): String {
        var t = raw.lowercase().replace(Regex("[?.!,]"), " ").replace("hello auto dj", "").replace("auto dj", "").replace(Regex("\\s+"), " ").trim()
        val now = ZonedDateTime.now(); val items = Store.items.filter { it.id != Store.TEST_ID }
        pending?.let { p ->
            val yes = Regex("\\b(yes|yeah|ok|okay|confirm|sure)\\b").containsMatchIn(t)
            if (Regex("\\b(no|cancel|stop)\\b").containsMatchIn(t) && !yes) { pending = null; return "Okay, cancelled." }
            when (p) {
                is Pending.Confirm -> if (yes) { Store.upsert(p.item); Scheduler.schedule(c, p.item); lastCreated = p.item; pending = null; return "Saved. Say undo to remove it." }
                is Pending.AmPm -> {
                    val pm = Regex("\\b(pm|p m|evening|night|afternoon)\\b").containsMatchIn(t); val am = Regex("\\b(am|a m|morning)\\b").containsMatchIn(t)
                    if (pm || am) { val h = (p.h % 12) + if (pm) 12 else 0; return alarm(h, p.m, p.date) }
                }
                is Pending.Hours -> {
                    val m = Regex("(\\d{1,2})\\s*(am|pm)?\\s*(?:to|-|till|until)\\s*(\\d{1,2})\\s*(am|pm)?").find(t)
                    val (a, b) = if (m != null) hr(m.groupValues[1].toInt(), m.groupValues[2]) to hr(m.groupValues[3].toInt(), m.groupValues[4]) else if ("daytime" in t || "day time" in t) 8 to 20 else return "Between which hours? For example, 8 AM to 8 PM, or say daytime."
                    val it = p.base.copy(activeFrom = a, activeTo = b)
                    val every = if (it.intervalMin % 60 == 0) "${it.intervalMin / 60} hours" else "${it.intervalMin} minutes"
                    val say = "Remind you every $every to ${it.title.lowercase()}, from ${fmt(a, 0)} to ${fmt(b, 0)}. Say yes to save."
                    pending = Pending.Confirm(it, say); return say
                }
            }
            if (p is Pending.Confirm) return "Say yes to save, or no to cancel."
            if (p is Pending.AmPm) return "Is that AM or PM?"
        }
        val rm = Regex("remind me every (\\w+) (hour|hours|minute|minutes) to (.+)").find(t)
        return when {
            "undo" in t -> lastCreated?.let { Store.remove(it.id); Scheduler.cancel(c, it.id); lastCreated = null; "Removed ${it.title}." } ?: "Nothing to undo."
            Regex("what time|time is it|current time").containsMatchIn(t) -> "It is ${now.format(DateTimeFormatter.ofPattern("h:mm a"))}."
            Regex("what.*(date|day)|today.s date").containsMatchIn(t) && "timetable" !in t -> "Today is ${now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))}."
            Regex("what should i do|current task|what now|right now").containsMatchIn(t) ->
                Scheduler.current(items, now)?.let { "Now: ${it.title}, since ${fmt(it.hour, it.minute)}." } ?: "Nothing is scheduled for now."
            Regex("what.s next|what is next|next task|next alarm").containsMatchIn(t) ->
                Scheduler.nextOf(items, now)?.let { (i, z) -> "Next: ${i.title} at ${z.format(DateTimeFormatter.ofPattern("h:mm a"))}${if (z.toLocalDate() != now.toLocalDate()) " on " + z.format(DateTimeFormatter.ofPattern("EEEE")) else ""}." } ?: "You have no upcoming alarms."
            Regex("timetable|today.s (plan|activities|schedule)|read my schedule").containsMatchIn(t) ->
                Scheduler.today(items, now).let { l -> if (l.isEmpty()) "Nothing is scheduled today." else "Today: " + l.joinToString(". ") { "${fmt(it.hour, it.minute)}, ${it.title}" } }
            Regex("^(play|start)\\b").containsMatchIn(t) -> {
                if (Store.settings.music.isEmpty()) "Add songs first, in Songs and Voice."
                else { ContextCompat.startForegroundService(c, Intent(c, PlaybackService::class.java).setAction("PLAY")); "Playing your music." }
            }
            Regex("^(stop|pause)\\b").containsMatchIn(t) -> { c.startService(Intent(c, PlaybackService::class.java).setAction("STOP")); "Stopped." }
            "snooze" in t -> {
                val n = Regex("(\\w+)\\s*min").find(t)?.groupValues?.get(1)?.let { num(it) }
                val it = items.find { x -> x.id == Store.nowId }
                if (it == null) "Nothing is ringing right now."
                else { Scheduler.set(c, it.id, System.currentTimeMillis() + (n ?: it.snoozeMin) * 60_000L, true, false)
                    c.startService(Intent(c, PlaybackService::class.java).setAction("STOP")); "Snoozed for ${n ?: it.snoozeMin} minutes." }
            }
            rm != null -> {
                val n = num(rm.groupValues[1]) ?: return "How often? Say a number, like every two hours."
                val mins = if (rm.groupValues[2].startsWith("hour")) n * 60 else n
                val base = Item(System.currentTimeMillis(), rm.groupValues[3].replaceFirstChar { it.uppercase() }, repeat = "INTERVAL", intervalMin = mins, bell = true, message = "Time to ${rm.groupValues[3]}")
                pending = Pending.Hours(base); "During which hours? For example, 8 AM to 8 PM, or say daytime."
            }
            Regex("(wake me|alarm).*\\b(at|for)\\b").containsMatchIn(t) -> {
                val m = Regex("(\\w+?)(?::(\\d{2}))?\\s*(am|pm|a m|p m)?(?:\\s|$)").findAll(t.substringAfter(" at ").substringAfter(" for ")).firstOrNull { num(it.groupValues[1]) != null }
                if (m == null) "What time should I set it for?" else {
                    val h = num(m.groupValues[1])!!; val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                    val day = if ("tomorrow" in t) LocalDate.now().plusDays(1) else null
                    val mer = m.groupValues[3].replace(" ", "").ifEmpty { when { "morning" in t -> "am"; Regex("evening|night|afternoon").containsMatchIn(t) -> "pm"; else -> "" } }
                    if (h > 12 || h == 0) alarm(h, min, day)
                    else if (mer.isEmpty()) { pending = Pending.AmPm(h, min, day); "Is that AM or PM?" }
                    else alarm(h % 12 + if (mer == "pm") 12 else 0, min, day)
                }
            }
            else -> "I didn't catch that. Try: what time is it, what is next, read today's timetable, wake me at 6 tomorrow, play my music, stop, or snooze ten minutes."
        }
    }
    private fun hr(h: Int, mer: String) = if (mer == "pm") h % 12 + 12 else if (mer == "am") h % 12 else h
    private fun alarm(h: Int, m: Int, d: LocalDate?): String {
        val now = LocalDateTime.now()
        val date = d ?: if (LocalDateTime.of(LocalDate.now(), LocalTime.of(h, m)).isAfter(now)) LocalDate.now() else LocalDate.now().plusDays(1)
        val it = Item(System.currentTimeMillis(), "Alarm", h, m, "ONCE", date = date.toString(), music = Store.settings.music.isNotEmpty(), message = "Good morning")
        val say = "Set an alarm for ${fmt(h, m)} on ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}. Say yes to save."
        pending = Pending.Confirm(it, say); return say
    }
}
