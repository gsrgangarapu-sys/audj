package com.autodj

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings as Sys
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val Teal = Color(0xFF0F8F8A); val Navy = Color(0xFF12274A); val Red = Color(0xFFE0392B)
private val DAYN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

object Voice {
    var tts: TextToSpeech? = null
    private fun vol() = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Store.settings.voiceVol) }
    fun say(t: String) { tts?.let { it.language = Locale("en"); it.speak(t, TextToSpeech.QUEUE_FLUSH, vol(), "r") } }
    fun preview(text: String) { tts?.let { e ->
        val l = Locale(Store.settings.lang)
        if (e.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) e.language = l
        Store.settings.voiceName.takeIf { it.isNotEmpty() }?.let { n -> e.voices?.find { it.name == n }?.let { v -> e.voice = v } }
        e.speak(text, TextToSpeech.QUEUE_FLUSH, vol(), "p") } }
    fun installed(lang: String) = tts?.isLanguageAvailable(Locale(lang))?.let { it >= TextToSpeech.LANG_AVAILABLE } ?: false
    fun voicesFor(lang: String) = try { tts?.voices?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }?.sortedBy { it.name }?.take(6) ?: emptyList() } catch (e: Exception) { emptyList() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Store.init(this)
        Voice.tts = TextToSpeech(this) {}
        Scheduler.rescheduleAll(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val cs = if (dark) darkColorScheme(primary = Color(0xFF2BB5AE), onPrimary = Color(0xFF06201F), background = Color(0xFF101A2B), surface = Color(0xFF182640),
                onBackground = Color(0xFFF3EFE3), onSurface = Color(0xFFF3EFE3), secondaryContainer = Color(0xFF17433F), onSecondaryContainer = Color(0xFFF3EFE3), error = Color(0xFFFF8A7D))
            else lightColorScheme(primary = Teal, background = Color(0xFFFDF8EE), surface = Color.White, onBackground = Navy, onSurface = Navy,
                secondaryContainer = Color(0xFFDFF1EE), onSecondaryContainer = Navy, error = Red)
            MaterialTheme(colorScheme = cs) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() } }
        }
    }
    override fun onDestroy() { Voice.tts?.shutdown(); super.onDestroy() }
}

// ---------- shared pieces ----------
@Composable fun Page(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content(); Spacer(Modifier.height(72.dp)) }
@Composable fun Box2(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
@Composable fun Big(t: String) = Text(t, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
@Composable fun H1(t: String) = Text(t, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
@Composable fun Btn(t: String, mod: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, mod.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) { Text(t, fontWeight = FontWeight.Bold) }
@Composable fun Soft(t: String, mod: Modifier = Modifier, onClick: () -> Unit) =
    Button(onClick, mod.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) { Text(t, fontWeight = FontWeight.Bold) }
@Composable fun Scroller(content: @Composable RowScope.() -> Unit) = Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
fun hm(h: Int, m: Int) = LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a"))
fun stop(c: Context) { c.startService(Intent(c, PlaybackService::class.java).setAction("STOP")) }
fun playMusic(c: Context, day: Int = -1) = ContextCompat.startForegroundService(c, Intent(c, PlaybackService::class.java).setAction("PLAY").putExtra("day", day))
fun fileOk(c: Context, u: String) = try { c.contentResolver.openFileDescriptor(Uri.parse(u), "r")?.use { true } ?: false } catch (e: Exception) { false }
fun notifOn(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()
fun batteryOk(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
fun resched(c: Context, id: Long) { Store.items.find { it.id == id }?.let { if (it.enabled && !it.paused) Scheduler.schedule(c, it) else Scheduler.cancel(c, it.id) } }
fun describe(i: Item): String = when (i.repeat) {
    "INTERVAL" -> "Every ${i.intervalMin} min, ${hm(i.activeFrom, 0)}–${hm(i.activeTo, 0)}"
    "COUNTDOWN" -> "Countdown ${i.countdownSec} s"
    "ONCE" -> "${hm(i.hour, i.minute)} · ${i.date}"
    "MONTHLY" -> "${hm(i.hour, i.minute)} · monthly, day ${LocalDate.parse(i.date).dayOfMonth}"
    "YEARLY" -> "${hm(i.hour, i.minute)} · yearly, ${LocalDate.parse(i.date).format(DateTimeFormatter.ofPattern("d MMM"))}"
    "DAILY" -> "${hm(i.hour, i.minute)} · Daily"
    else -> "${hm(i.hour, i.minute)} · " + i.days.sorted().joinToString(" ") { DAYN[it - 1] }
}

@Composable fun App() {
    var tab by remember { mutableIntStateOf(0) }; var assist by remember { mutableStateOf(false) }
    if (!Store.settings.setupDone) { Setup(); return }
    val tabs = listOf("Home" to "🏠", "Timetable" to "⏰", "Audio" to "🎵", "Settings" to "⚙️")
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { FloatingActionButton({ assist = true }, containerColor = Teal, contentColor = Color.White, shape = CircleShape) { Text("🎤", fontSize = 26.sp) } },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { tabs.forEachIndexed { i, t ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(t.second, fontSize = 22.sp) }, label = { Text(t.first, fontSize = 12.sp, maxLines = 1) }) } } }
    ) { p -> Box(Modifier.padding(p).statusBarsPadding()) { when (tab) { 0 -> Home(); 1 -> Timetable(); 2 -> Audio(); else -> SettingsScreen() } } }
    if (assist) Dialog({ assist = false }, DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) { Soft("Close") { assist = false } }; AssistantScreen() } } }
}

// ---------- Home ----------
@Composable fun Home() {
    val c = LocalContext.current; val s = Store.settings
    val now by produceState(ZonedDateTime.now()) { while (true) { value = ZonedDateTime.now(); delay(1000) } }
    val items = Store.items.filter { it.id != Store.TEST_ID }
    val next = Scheduler.nextOf(items, now); val cur = Scheduler.current(items, now)
    val nextAct = Scheduler.today(items, now).firstOrNull { it.hour * 60 + it.minute > now.hour * 60 + now.minute }
    var vol by remember { mutableFloatStateOf(s.musicVol) }
    Page {
        Text(now.format(DateTimeFormatter.ofPattern("h:mm a")), fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) + " · " + when (now.hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" })
        Store.loadError?.let { Box2 { Text("Your saved data couldn’t be read: $it", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Text("The damaged file was kept as autodj.json.bad. Restore a backup in Settings, or start fresh. Alarms already registered with Android may still ring."); Soft("Start fresh") { Store.reset() } } }
        if (!notifOn(c)) Box2 { Text("Notifications are off. Reminders can’t be shown.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold); Text("Fix it in Settings → Permissions.") }
        if (!Scheduler.canExact(c)) Box2 { Text("Exact alarms are off. Alarms may be late.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold); Text("Fix it in Settings → Permissions.") }
        Box2 { Text("Now"); Big(cur?.title ?: "No activity right now"); Text("Next: " + (nextAct?.let { "${it.title} · ${hm(it.hour, it.minute)}" } ?: "nothing else today")) }
        Box2 { Text("Music"); Big(Store.nowPlaying ?: "Nothing playing")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (Store.nowPlaying == null) Btn("▶ Play", Modifier.weight(1f), s.music.isNotEmpty() || s.weekMusic.isNotEmpty()) { playMusic(c) }
                else Btn(if (Store.playerPaused) "▶ Resume" else "⏸ Pause", Modifier.weight(1f)) { PlaybackService.live?.togglePause() }
                Soft("■ Stop", Modifier.weight(1f)) { stop(c) } }
            Text("Volume ${(vol * 100).toInt()}%"); Slider(vol, { vol = it; PlaybackService.live?.applyVolume(it) }, onValueChangeFinished = { Store.set(Store.settings.copy(musicVol = vol)) })
            if (s.music.isEmpty() && s.weekMusic.isEmpty()) Text("No songs yet. Add some in the Audio tab.") }
        Box2 { Text("Next alarm"); if (next == null) { Big("No alarms yet"); Text("Add one in the Timetable tab.") } else {
            val d = Duration.between(now, next.second); Big("${hm(next.second.hour, next.second.minute)} · ${next.first.title}")
            Text(if (d.toHours() > 0) "In ${d.toHours()} h ${d.toMinutes() % 60} min" else if (d.toMinutes() > 0) "In ${d.toMinutes()} min" else "In ${d.seconds} s") } }
        Button({ stop(c) }, Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Text("■ Stop All", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        Text("Today", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        val today = Scheduler.today(items, now)
        if (today.isEmpty()) Text("Nothing scheduled today.")
        today.forEach { i -> Box2 { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(i.title, fontWeight = FontWeight.Bold); Text(hm(i.hour, i.minute)) }
            val done = i.doneDate == LocalDate.now().toString()
            Soft(if (done) "✓ Done" else "Mark done") { Store.update(i.id) { x -> x.copy(doneDate = if (done) "" else LocalDate.now().toString()) } } } } }
    }
}

// ---------- Timetable ----------
@Composable fun Timetable() {
    val c = LocalContext.current; var edit by remember { mutableStateOf<Item?>(null) }
    val items = Store.items.filter { it.id != Store.TEST_ID }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Timetable", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Btn("＋ Add") { edit = Item(System.currentTimeMillis(), "") } }
        if (items.isEmpty()) Box2 { Text("No routines yet. Tap Add to create one, such as Wake up at 6:00 AM.") }
        items.sortedBy { it.hour * 60 + it.minute }.forEach { i -> Box2 {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp); Text(describe(i))
                    Text(listOfNotNull("Voice".takeIf { i.speak }, "Music".takeIf { i.music }, "Bell".takeIf { i.bell }, "Vibrate".takeIf { i.vibrate }).joinToString(" + ").ifEmpty { "Notification" })
                    if (i.paused) Text("Paused", fontWeight = FontWeight.Bold); if (i.skipNext) Text("Next run skipped", fontWeight = FontWeight.Bold)
                    if (i.holidayUntil.isNotEmpty() && i.holidayUntil >= LocalDate.now().toString()) Text("On holiday until ${i.holidayUntil}")
                    if (i.endDate.isNotEmpty()) Text("Ends ${i.endDate}") }
                Switch(i.enabled, { on -> Store.update(i.id) { it.copy(enabled = on) }; resched(c, i.id) }) }
            Scroller { Soft("Edit") { edit = i }; Soft(if (i.skipNext) "Undo skip" else "Skip next") { Store.update(i.id) { it.copy(skipNext = !it.skipNext) } }
                Soft(if (i.paused) "Resume" else "Pause") { Store.update(i.id) { it.copy(paused = !it.paused) }; resched(c, i.id) } }
            Scroller { Soft("Duplicate") { val n = i.copy(id = System.currentTimeMillis(), title = i.title + " copy", lastFired = 0); Store.upsert(n); resched(c, n.id) }
                val onHol = i.holidayUntil >= LocalDate.now().toString() && i.holidayUntil.isNotEmpty()
                Soft(if (onHol) "End holiday" else "Holiday 7 days") { Store.update(i.id) { it.copy(holidayUntil = if (onHol) "" else LocalDate.now().plusDays(7).toString()) }; resched(c, i.id) }
                Soft("Delete") { Scheduler.cancel(c, i.id); Store.remove(i.id) } }
        } }
    }
    edit?.let { Editor(it, { edit = null }) { n -> Store.upsert(n); resched(c, n.id); edit = null } }
}

@Composable fun Editor(start: Item, onCancel: () -> Unit, onSave: (Item) -> Unit) {
    val c = LocalContext.current; var i by remember { mutableStateOf(start) }; var err by remember { mutableStateOf("") }
    fun pickDate(cur: String, f: (String) -> Unit) { val d = runCatching { LocalDate.parse(cur) }.getOrDefault(LocalDate.now()); DatePickerDialog(c, { _, y, m, dd -> f(LocalDate.of(y, m + 1, dd).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show() }
    fun num(v: String, d: Int = 0) = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: d
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Box(Modifier.statusBarsPadding()) { Page {
        Text(if (start.title.isEmpty()) "New activity" else "Edit activity", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        OutlinedTextField(i.title, { i = i.copy(title = it) }, label = { Text("Name (for example, Morning prayer)") }, modifier = Modifier.fillMaxWidth())
        Text("Repeat"); Scroller { listOf("ONCE" to "Once", "DAILY" to "Daily", "WEEKDAYS" to "Weekdays", "MONTHLY" to "Monthly", "YEARLY" to "Yearly", "INTERVAL" to "Every N min", "COUNTDOWN" to "Countdown").forEach { (k, l) ->
            FilterChip(i.repeat == k, { i = i.copy(repeat = k) }, { Text(l) }) } }
        if (i.repeat !in listOf("INTERVAL", "COUNTDOWN")) Soft("Time: ${hm(i.hour, i.minute)}") { TimePickerDialog(c, { _, h, m -> i = i.copy(hour = h, minute = m) }, i.hour, i.minute, false).show() }
        if (i.repeat in listOf("ONCE", "MONTHLY", "YEARLY")) Soft("${if (i.repeat == "ONCE") "Date" else "Starting date"}: ${i.date.ifEmpty { "choose" }}") { pickDate(i.date) { d -> i = i.copy(date = d) } }
        if (i.repeat == "WEEKDAYS") Scroller { DAYN.forEachIndexed { n, l -> FilterChip((n + 1) in i.days, { i = i.copy(days = if ((n + 1) in i.days) i.days - (n + 1) else i.days + (n + 1)) }, { Text(l) }) } }
        if (i.repeat == "INTERVAL") {
            OutlinedTextField(i.intervalMin.toString(), { i = i.copy(intervalMin = num(it)) }, label = { Text("Every (minutes; 60 = hourly)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(i.activeFrom.toString(), { i = i.copy(activeFrom = num(it).coerceIn(0, 23)) }, label = { Text("Active from (hour 0–23)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(i.activeTo.toString(), { i = i.copy(activeTo = num(it).coerceIn(0, 23)) }, label = { Text("Active until (hour 0–23)") }, modifier = Modifier.fillMaxWidth()) }
        if (i.repeat == "COUNTDOWN") OutlinedTextField(i.countdownSec.toString(), { i = i.copy(countdownSec = num(it)) }, label = { Text("Start in (seconds, 5–86400)") }, modifier = Modifier.fillMaxWidth())
        if (i.repeat != "ONCE" && i.repeat != "COUNTDOWN") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Soft("End date: ${i.endDate.ifEmpty { "none" }}") { pickDate(i.endDate.ifEmpty { LocalDate.now().plusMonths(1).toString() }) { d -> i = i.copy(endDate = d) } }
            if (i.endDate.isNotEmpty()) Soft("Clear") { i = i.copy(endDate = "") } }
        Text("Alert")
        listOf("Spoken message" to i.speak, "Music" to i.music, "Bell" to i.bell, "Vibration" to i.vibrate, "Loop music" to i.loop).forEachIndexed { n, (l, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(v, { b -> i = when (n) { 0 -> i.copy(speak = b); 1 -> i.copy(music = b); 2 -> i.copy(bell = b); 3 -> i.copy(vibrate = b); else -> i.copy(loop = b) } }); Text(l) } }
        Text("With none of Spoken, Music or Bell selected, this is a silent notification.")
        OutlinedTextField(i.message, { i = i.copy(message = it) }, label = { Text("Spoken message (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(i.stopAfterMin.toString(), { i = i.copy(stopAfterMin = num(it, 1).coerceIn(1, 240)) }, label = { Text("Stop automatically after (minutes)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(i.snoozeMin.toString(), { i = i.copy(snoozeMin = num(it, 5).coerceIn(1, 60)) }, label = { Text("Snooze (minutes)") }, modifier = Modifier.fillMaxWidth())
        Text("If the phone was off at the scheduled time"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(i.missedRule == "SKIP", { i = i.copy(missedRule = "SKIP") }, { Text("Skip it") }); FilterChip(i.missedRule == "LATE", { i = i.copy(missedRule = "LATE") }, { Text("Run if under 1 h late") }) }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Btn("Save activity", Modifier.fillMaxWidth()) {
            err = when {
                i.title.isBlank() -> "Enter a name."
                i.repeat in listOf("ONCE", "MONTHLY", "YEARLY") && i.date.isEmpty() -> "Choose a date."
                i.repeat == "WEEKDAYS" && i.days.isEmpty() -> "Choose at least one day."
                i.repeat == "INTERVAL" && (i.intervalMin < 1 || i.activeTo <= i.activeFrom) -> "Check the interval and active hours."
                i.repeat == "COUNTDOWN" && i.countdownSec !in 5..86400 -> "Countdown must be 5 to 86400 seconds."
                !(i.speak || i.music || i.bell || i.vibrate) && false -> ""
                else -> ""
            }
            if (err.isEmpty()) onSave(i.copy(enabled = true, lastFired = 0, startAt = if (i.repeat == "COUNTDOWN") System.currentTimeMillis() + i.countdownSec * 1000L else 0))
        }
        Soft("Cancel", Modifier.fillMaxWidth(), onCancel)
    } } } }
}

// ---------- Audio ----------
@Composable fun Audio() {
    val c = LocalContext.current; val s = Store.settings; var day by remember { mutableIntStateOf(0) }
    val list = if (day == 0) s.music else s.weekMusic[day] ?: emptyList()
    fun setList(l: List<String>) = Store.set(if (day == 0) s.copy(music = l) else s.copy(weekMusic = s.weekMusic + (day to l)))
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { try { c.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        setList((list + uris.map { it.toString() }).distinct()) }
    var vm by remember { mutableFloatStateOf(s.musicVol) }; var vv by remember { mutableFloatStateOf(s.voiceVol) }
    val ok = remember(list) { list.associateWith { fileOk(c, it) } }
    Page {
        H1("Audio")
        Box2 { Big("Playlists"); Scroller { FilterChip(day == 0, { day = 0 }, { Text("Default") }); DAYN.forEachIndexed { n, l -> FilterChip(day == n + 1, { day = n + 1 }, { Text(l) }) } }
            Text(if (day == 0) "Plays on any day without its own playlist." else "Plays on ${DAYN[day - 1]}s. If empty, the Default playlist plays.")
            if (list.isEmpty()) Text("No songs here yet.")
            list.forEachIndexed { n, u -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (ok[u] == false) "⚠ " else "") + (Uri.parse(u).lastPathSegment?.substringAfterLast('/') ?: "Song ${n + 1}"), Modifier.weight(1f), maxLines = 1)
                Soft("Remove") { setList(list - u) } }
                if (ok[u] == false) Text("File unavailable. The default alarm sound plays instead. Remove it or add it again.", color = MaterialTheme.colorScheme.error) }
            Btn("Add songs from phone", Modifier.fillMaxWidth()) { pick.launch(arrayOf("audio/*")) }
            Soft("▶ Preview this playlist", Modifier.fillMaxWidth()) { if (list.isNotEmpty() || s.music.isNotEmpty()) playMusic(c, day) }
            Text("Songs stay on this phone and play offline.") }
        Box2 { Big("Spoken announcements"); Scroller { listOf("en" to "English", "te" to "తెలుగు", "hi" to "हिन्दी").forEach { (k, l) -> FilterChip(s.lang == k, { Store.set(s.copy(lang = k, voiceName = "")) }, { Text(l) }) } }
            val vs = Voice.voicesFor(s.lang)
            if (!Voice.installed(s.lang)) { Text("No voice for this language is installed. Until you install one, the device default speaks.", color = MaterialTheme.colorScheme.error)
                Soft("Open voice settings") { try { c.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { c.startActivity(Intent(Sys.ACTION_SETTINGS)) } } }
            else if (vs.isNotEmpty()) { Text("Voice"); Scroller { FilterChip(s.voiceName.isEmpty(), { Store.set(s.copy(voiceName = "")) }, { Text("Default") }); vs.forEachIndexed { n, v -> FilterChip(s.voiceName == v.name, { Store.set(s.copy(voiceName = v.name)) }, { Text("Voice ${n + 1}") }) } } }
            Soft("▶ Preview announcement", Modifier.fillMaxWidth()) { Voice.preview("Time for your stretch break.") } }
        Box2 { Big("Levels"); Text("Music ${(vm * 100).toInt()}%"); Slider(vm, { vm = it }, onValueChangeFinished = { Store.set(Store.settings.copy(musicVol = vm)) })
            Text("Voice ${(vv * 100).toInt()}%"); Slider(vv, { vv = it }, onValueChangeFinished = { Store.set(Store.settings.copy(voiceVol = vv)) })
            Text("Music fades in over about 15 seconds. During an announcement, playing music is lowered, then restored.") }
        Box2 { Big("Recorded announcements"); Text("Not in this version. Type a spoken message in an activity instead.") }
    }
}

// ---------- Assistant ----------
@Composable fun AssistantScreen() {
    val c = LocalContext.current
    var heard by remember { mutableStateOf("") }; var reply by remember { mutableStateOf("Tap the microphone and speak.") }; var typed by remember { mutableStateOf("") }
    fun run(t: String) { heard = t; reply = Assistant.handle(c, t); Voice.say(reply) }
    val rec = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { run(it) } }
    Page {
        H1("Ask Auto DJ")
        Box2 { Text("Recognized speech"); Big(heard.ifEmpty { "…" }); Text("Auto DJ says"); Text(reply, fontSize = 18.sp) }
        Button({ try { rec.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true).putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command")) }
            catch (e: ActivityNotFoundException) { reply = "Speech recognition isn’t available on this phone. Type a command instead." } },
            Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(40.dp)) { Text("🎤  Tap to speak", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        OutlinedTextField(typed, { typed = it }, label = { Text("Or type a command") }, modifier = Modifier.fillMaxWidth())
        Soft("Send", Modifier.fillMaxWidth()) { if (typed.isNotBlank()) { run(typed); typed = "" } }
        if (Assistant.pending is Assistant.Pending.Confirm) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Btn("Yes, save", Modifier.weight(1f)) { run("yes") }; Soft("Cancel", Modifier.weight(1f)) { run("no") } }
        if (Assistant.lastCreated != null) Soft("Undo last change", Modifier.fillMaxWidth()) { run("undo") }
        Box2 { Text("Try"); Text("• What time is it?\n• What should I do now?\n• What is next?\n• Read today’s timetable\n• Set an alarm for six tomorrow morning\n• Remind me every two hours to drink water\n• Play my morning playlist\n• Stop music\n• Snooze ten minutes") }
        Box2 { Text("Privacy", fontWeight = FontWeight.Bold)
            Text("Listening uses Android’s own speech screen, which shows when the microphone is on. Auto DJ never receives or stores your audio. Depending on your phone, Android’s speech service may send audio to an online service; Auto DJ asks for offline recognition first. Commands are understood in English.") }
    }
}

// ---------- Settings ----------
@Composable fun SettingsScreen() {
    val c = LocalContext.current; val s = Store.settings; var tick by remember { mutableIntStateOf(0) }
    val back = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val notifAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { c.contentResolver.openOutputStream(it)?.use { o -> o.write(Store.toJson().toByteArray()) } } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { try { Store.fromJson(c.contentResolver.openInputStream(it)!!.bufferedReader().readText()); Scheduler.rescheduleAll(c) } catch (e: Exception) { Store.log("Restore failed: ${e.message}") } } }
    key(tick) { Page {
        H1("Settings")
        Box2 { Big("Permissions"); val n = notifOn(c)
            Text("Notifications: ${if (n) "allowed" else "off"}"); if (!n) Soft("Allow notifications") { if (Build.VERSION.SDK_INT >= 33) notifAsk.launch(Manifest.permission.POST_NOTIFICATIONS) else back.launch(Intent(Sys.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Sys.EXTRA_APP_PACKAGE, c.packageName)) }
            val ex = Scheduler.canExact(c); Text("Exact alarms: ${if (ex) "allowed" else "off (alarms may be late)"}")
            if (!ex && Build.VERSION.SDK_INT >= 31) Soft("Allow exact alarms") { back.launch(Intent(Sys.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))) }
            val ign = batteryOk(c); Text("Battery: ${if (ign) "unrestricted" else "may be restricted by your phone"}")
            if (!ign) Soft("Open battery settings") { back.launch(Intent(Sys.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            Text("Microphone: handled by Android’s speech screen when you tap the microphone button. Auto DJ itself doesn’t hold microphone access.")
            Soft("Refresh") { tick++ } }
        Box2 { Big("Holiday mode"); Text("Pauses every activity until the chosen date. Activities resume by themselves.")
            val on = Store.items.any { it.holidayUntil >= LocalDate.now().toString() && it.holidayUntil.isNotEmpty() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Soft(if (on) "End holiday mode" else "Pause all for 7 days") { val d = if (on) "" else LocalDate.now().plusDays(7).toString(); Store.items.forEach { i -> Store.update(i.id) { it.copy(holidayUntil = d) }; resched(c, i.id) } } } }
        Box2 { Big("Quiet hours"); Text("For repeating interval reminders: ${hm(s.quietFrom, 0)} to ${hm(s.quietTo, 0)}")
            Scroller { Soft("From −") { Store.set(s.copy(quietFrom = (s.quietFrom + 23) % 24)) }; Soft("From +") { Store.set(s.copy(quietFrom = (s.quietFrom + 1) % 24)) }
                Soft("To −") { Store.set(s.copy(quietTo = (s.quietTo + 23) % 24)) }; Soft("To +") { Store.set(s.copy(quietTo = (s.quietTo + 1) % 24)) } }
            Text("Wake-up and one-time alarms still ring during quiet hours.") }
        Box2 { Big("Timezone"); Text("${ZoneId.systemDefault()} (the device timezone). Alarms are rescheduled if it changes.") }
        Box2 { Big("When two things happen together"); Text("1. A music alarm replaces any music already playing.\n2. A spoken reminder lowers playing music, speaks, then music returns to its level.\n3. A silent notification never interrupts audio.\n4. If a reminder is missed because the phone was off, it follows the activity’s missed rule. Missed reminders are never replayed in a batch.") }
        Box2 { Big("When alarms can’t ring"); Text("• Phone switched off: nothing rings. Alarms are restored when it starts, and missed ones follow their rule.\n• App force-stopped from Android settings: Android cancels its alarms. Open Auto DJ once to restore them.\n• Aggressive battery saving on some brands can delay alarms. Set battery to Unrestricted.\n• Exact alarm access revoked: alarms may be late until you allow it again.") }
        Box2 { Big("Test with the screen locked"); Text("Schedules a test alarm 60 seconds from now. Lock your phone and wait.")
            Btn("Start 60-second test", Modifier.fillMaxWidth()) { startTest(c) } }
        Box2 { Big("Backup"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Soft("Export") { export.launch("auto-dj-backup.json") }; Soft("Restore") { import.launch(arrayOf("application/json")) } }
            Text("Includes activities and settings. Song files aren’t copied; re-add them after restoring on a new phone.") }
        Box2 { Big("Privacy"); Text("Everything is stored on this phone. No account. Recordings aren’t kept. No cloud service is used unless you add one later; no private API key is ever stored in the app.") }
        Box2 { Big("Conversational AI"); Text("Not available in this version. Alarms and commands never need it.") }
        Box2 { Big("“Hello Auto DJ” listening"); Text("Not available in this version. Android restricts background microphone use; it will be added as a separate feature and tested on real devices first.") }
        Box2 { Big("Activity"); if (Store.history.isEmpty()) Text("Nothing has run yet.")
            Store.history.take(10).forEach { Text("${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(it.at)} — ${it.text}") } }
        Soft("Run setup again", Modifier.fillMaxWidth()) { Store.set(s.copy(setupDone = false)) }
    } }
}
fun startTest(c: Context) {
    val t = Item(Store.TEST_ID, "Test alarm", music = Store.settings.music.isNotEmpty(), message = "This is your Auto DJ test alarm", stopAfterMin = 1, enabled = false)
    Store.upsert(t); Scheduler.set(c, t.id, System.currentTimeMillis() + 60_000, true, true); Store.log("Test alarm scheduled for 60 seconds")
}

// ---------- Setup ----------
@Composable fun Check(title: String, ok: Boolean?, okText: String, fixText: String, action: (@Composable ColumnScope.() -> Unit)? = null) = Box2 {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(when (ok) { true -> "✅"; false -> "❌"; null -> "⚪" }, fontSize = 20.sp); Big(title) }
    Text(if (ok == true) okText else fixText); action?.invoke(this) }

@Composable fun Setup() {
    val c = LocalContext.current; val s = Store.settings; var tick by remember { mutableIntStateOf(0) }
    var voiceHeard by remember { mutableStateOf<Boolean?>(null) }; var musicHeard by remember { mutableStateOf<Boolean?>(null) }
    val back = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val notifAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { try { c.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        Store.set(Store.settings.copy(music = (Store.settings.music + uris.map { it.toString() }).distinct())) }
    Box(Modifier.statusBarsPadding().navigationBarsPadding()) { key(tick) { Page {
        Text("Welcome to", fontSize = 18.sp); Text("AUTO DJ", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold); Text("Your day, on your phone. Everything stays on this phone.")
        Box2 { Big("Language and timezone"); Scroller { listOf("en" to "English", "hi" to "हिन्दी", "te" to "తెలుగు").forEach { (k, l) -> FilterChip(s.lang == k, { Store.set(s.copy(lang = k, voiceName = "")) }, { Text(l) }) } }
            Text("Timezone: ${ZoneId.systemDefault()} (from your device)") }
        Check("Notifications", notifOn(c), "Allowed.", "Needed so reminders can appear with Stop and Snooze. If no dialog appears, open Settings → Apps → Auto DJ → Notifications.") {
            Soft("Allow notifications") { if (Build.VERSION.SDK_INT >= 33) notifAsk.launch(Manifest.permission.POST_NOTIFICATIONS) else back.launch(Intent(Sys.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Sys.EXTRA_APP_PACKAGE, c.packageName)) } }
        Check("Alarm access", Scheduler.canExact(c), "Exact alarms allowed.", "Needed so wake-up alarms ring at the exact minute. Without it they can be late. Tap below and switch on “Alarms & reminders”.") {
            Soft("Open alarm access") { if (Build.VERSION.SDK_INT >= 31) back.launch(Intent(Sys.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))) } }
        Check("Battery", batteryOk(c), "Unrestricted.", "Recommended: allow Auto DJ to run unrestricted so your phone doesn’t delay alarms. Some brands also need “Auto start” turned on.") { Soft("Open battery settings") { back.launch(Intent(Sys.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        Check("Music", s.music.isNotEmpty() && fileOk(c, s.music.first()), "${s.music.size} songs selected and readable.", if (s.music.isEmpty()) "Choose songs stored on this phone. They play offline." else "The first song can’t be opened. Choose it again.") { Soft("Choose songs") { pick.launch(arrayOf("audio/*")) } }
        Check("Spoken voice", Voice.installed(s.lang), "A voice for this language is installed.", "No voice for this language is installed, so the device default will speak. Install one in Android Settings → Text-to-speech.") {
            Soft("Open voice settings") { try { c.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { c.startActivity(Intent(Sys.ACTION_SETTINGS)) } } }
        Check("Test spoken reminder", voiceHeard, "You heard it.", if (voiceHeard == false) "Raise the phone’s volume (alarm volume), check the voice above, then try again." else "Play it, then say whether you heard it.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Soft("▶ Play") { Voice.preview("Good morning. This is Auto DJ.") }; Soft("I heard it") { voiceHeard = true }; Soft("I didn’t") { voiceHeard = false } } }
        Check("Test music", musicHeard, "You heard it.", if (musicHeard == false) "Check that the song file opens in another player and the alarm volume is up. Auto DJ plays a default sound if a file fails." else "Plays your playlist. Say whether you heard it.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Soft("▶ Play") { playMusic(c) }; Soft("■ Stop") { stop(c) }; Soft("Heard") { musicHeard = true }; Soft("No") { musicHeard = false } } }
        val ran = Store.history.firstOrNull { it.text == "Ran Test alarm" }
        Check("Screen-locked alarm", if (ran != null) true else null, "The test alarm rang.", "Start the test, lock your phone, and wait 60 seconds. Then tap Refresh. If nothing rang, allow exact alarms and set battery to Unrestricted.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Soft("Start 60-second test") { startTest(c) }; Soft("Refresh") { tick++ } } }
        Box2 { Big("Voice commands and microphone"); Text("When you tap the microphone, Android’s own speech screen opens and asks for microphone access then. Auto DJ never records or keeps your audio. Depending on your phone, Android’s speech service may use an online service.") }
        Check("First activity", Store.items.any { it.title == "Wake up" }, "Wake up at 6:00 AM added.", "Adds Wake up at 6:00 AM every day. You can edit it later.") {
            Soft("Add Wake up") { if (Store.items.none { it.title == "Wake up" }) { val i = Item(System.currentTimeMillis(), "Wake up", 6, 0, "DAILY", music = s.music.isNotEmpty(), message = "Good morning. Time to wake up."); Store.upsert(i); Scheduler.schedule(c, i); tick++ } } }
        Btn("Finish setup", Modifier.fillMaxWidth()) { Store.set(Store.settings.copy(setupDone = true)) }
        Text("Failed checks can be fixed later in Settings.")
    } } }
}
