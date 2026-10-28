package com.autodj

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** days: 1=Mon..7=Sun. repeat: ONCE | DAILY | WEEKDAYS | INTERVAL */
data class Item(
    val id: Long, val title: String, val hour: Int = 6, val minute: Int = 0,
    val repeat: String = "DAILY", val days: List<Int> = emptyList(),
    val intervalMin: Int = 120, val activeFrom: Int = 8, val activeTo: Int = 20,
    val date: String = "", val speak: Boolean = true, val message: String = "",
    val music: Boolean = false, val vibrate: Boolean = true, val bell: Boolean = false,
    val loop: Boolean = false, val stopAfterMin: Int = 10, val snoozeMin: Int = 10,
    val enabled: Boolean = true, val skipNext: Boolean = false, val holidayUntil: String = "",
    val lastFired: Long = 0, val doneDate: String = "",
    val startAt: Long = 0, val paused: Boolean = false, val endDate: String = "",
    val missedRule: String = "SKIP", val countdownSec: Int = 60
)
data class Settings(
    val music: List<String> = emptyList(), val lang: String = "en",
    val musicVol: Float = 0.8f, val voiceVol: Float = 0.8f,
    val quietFrom: Int = 22, val quietTo: Int = 6, val setupDone: Boolean = false, val aiOn: Boolean = false,
    val weekMusic: Map<Int, List<String>> = emptyMap(), val voiceName: String = ""
)
fun Settings.listFor(d: Int): List<String> = if (d == 0) music else weekMusic[d]?.takeIf { it.isNotEmpty() } ?: music
data class LogEntry(val at: Long, val text: String)

object Store {
    const val TEST_ID = 1L
    private lateinit var file: File
    private var inited = false
    var items by mutableStateOf(listOf<Item>()); private set
    var settings by mutableStateOf(Settings()); private set
    var history by mutableStateOf(listOf<LogEntry>()); private set
    var nowPlaying by mutableStateOf<String?>(null)
    var nowId by mutableStateOf(0L)
    var playerPaused by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)

    @Synchronized fun init(c: Context) {
        if (inited) return
        file = File(c.applicationContext.filesDir, "autodj.json")
        try { if (file.exists()) fromJson(file.readText()) } catch (e: Exception) { loadError = e.message ?: "unreadable file"; file.renameTo(File(file.path + ".bad")) }
        inited = true
    }
    @Synchronized private fun persist() {
        val tmp = File(file.path + ".tmp"); tmp.writeText(toJson()); tmp.renameTo(file)
    }
    @Synchronized fun reset() { items = emptyList(); history = emptyList(); loadError = null; persist() }
    @Synchronized fun upsert(i: Item) { items = items.filter { it.id != i.id } + i; persist() }
    @Synchronized fun remove(id: Long) { items = items.filter { it.id != id }; persist() }
    @Synchronized fun update(id: Long, f: (Item) -> Item) { items = items.map { if (it.id == id) f(it) else it }; persist() }
    @Synchronized fun set(s: Settings) { settings = s; persist() }
    @Synchronized fun log(t: String) { history = (listOf(LogEntry(System.currentTimeMillis(), t)) + history).take(60); persist() }

    fun toJson(): String = JSONObject().apply {
        put("items", JSONArray(items.map { it.toJson() }))
        put("history", JSONArray(history.map { JSONObject().put("at", it.at).put("t", it.text) }))
        put("settings", JSONObject().apply {
            val s = settings
            put("music", JSONArray(s.music)); put("lang", s.lang); put("musicVol", s.musicVol.toDouble()); put("voiceVol", s.voiceVol.toDouble())
            put("quietFrom", s.quietFrom); put("quietTo", s.quietTo); put("setupDone", s.setupDone); put("aiOn", s.aiOn); put("voiceName", s.voiceName)
            put("weekMusic", JSONObject().also { w -> s.weekMusic.forEach { (k, v) -> w.put(k.toString(), JSONArray(v)) } })
        })
    }.toString(2)

    @Synchronized fun fromJson(t: String) {
        val o = JSONObject(t)
        items = (0 until o.optJSONArray("items").let { it?.length() ?: 0 }).map { itemFrom(o.getJSONArray("items").getJSONObject(it)) }
        history = (0 until (o.optJSONArray("history")?.length() ?: 0)).map { val h = o.getJSONArray("history").getJSONObject(it); LogEntry(h.getLong("at"), h.getString("t")) }
        o.optJSONObject("settings")?.let { s ->
            settings = Settings(
                List(s.optJSONArray("music")?.length() ?: 0) { s.getJSONArray("music").getString(it) }, s.optString("lang", "en"),
                s.optDouble("musicVol", 0.8).toFloat(), s.optDouble("voiceVol", 0.8).toFloat(),
                s.optInt("quietFrom", 22), s.optInt("quietTo", 6), s.optBoolean("setupDone"), s.optBoolean("aiOn"),
                weekMap(s.optJSONObject("weekMusic")), s.optString("voiceName"))
        }
        if (inited) persist()
    }
}

fun Item.toJson() = JSONObject().apply {
    put("id", id); put("title", title); put("hour", hour); put("minute", minute); put("repeat", repeat); put("days", JSONArray(days))
    put("intervalMin", intervalMin); put("activeFrom", activeFrom); put("activeTo", activeTo); put("date", date)
    put("speak", speak); put("message", message); put("music", music); put("vibrate", vibrate); put("bell", bell); put("loop", loop)
    put("stopAfterMin", stopAfterMin); put("snoozeMin", snoozeMin); put("enabled", enabled); put("skipNext", skipNext)
    put("holidayUntil", holidayUntil); put("lastFired", lastFired); put("doneDate", doneDate)
    put("startAt", startAt); put("paused", paused); put("endDate", endDate); put("missedRule", missedRule); put("countdownSec", countdownSec)
}
fun weekMap(o: JSONObject?): Map<Int, List<String>> =
    o?.keys()?.asSequence()?.associate { k -> k.toInt() to List(o.getJSONArray(k).length()) { o.getJSONArray(k).getString(it) } } ?: emptyMap()
fun itemFrom(o: JSONObject) = Item(
    o.getLong("id"), o.getString("title"), o.optInt("hour", 6), o.optInt("minute", 0), o.optString("repeat", "DAILY"),
    List(o.optJSONArray("days")?.length() ?: 0) { o.getJSONArray("days").getInt(it) },
    o.optInt("intervalMin", 120), o.optInt("activeFrom", 8), o.optInt("activeTo", 20), o.optString("date"),
    o.optBoolean("speak", true), o.optString("message"), o.optBoolean("music"), o.optBoolean("vibrate", true), o.optBoolean("bell"),
    o.optBoolean("loop"), o.optInt("stopAfterMin", 10), o.optInt("snoozeMin", 10), o.optBoolean("enabled", true),
    o.optBoolean("skipNext"), o.optString("holidayUntil"), o.optLong("lastFired"), o.optString("doneDate"),
    o.optLong("startAt"), o.optBoolean("paused"), o.optString("endDate"), o.optString("missedRule", "SKIP"), o.optInt("countdownSec", 60))
