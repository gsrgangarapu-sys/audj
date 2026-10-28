package com.autodj

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.util.Locale

class PlaybackService : Service(), TextToSpeech.OnInitListener {
    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null; private var ttsReady = false; private var onTts: (() -> Unit)? = null
    private var player: MediaPlayer? = null; private var ring: Ringtone? = null
    private var tracks = listOf<Uri>(); private var idx = 0; private var item: Item? = null; private var overlay = false
    private var focus: AudioFocusRequest? = null
    private var playDay = -1
    companion object { var live: PlaybackService? = null }
    fun applyVolume(v: Float) { try { player?.setVolume(v, v) } catch (_: Exception) {} }
    fun togglePause() { player?.let { try { if (it.isPlaying) { it.pause(); Store.playerPaused = true } else { it.start(); Store.playerPaused = false } } catch (_: Exception) {} } }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        live = this; Store.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("alarm", "Alarms and reminders", NotificationManager.IMPORTANCE_HIGH))
        tts = TextToSpeech(this, this)
    }
    override fun onInit(s: Int) {
        ttsReady = s == TextToSpeech.SUCCESS
        if (ttsReady) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { h.post { afterSpeech() } }
            @Deprecated("") override fun onError(u: String?) { h.post { afterSpeech() } }
        }) else Store.log("Voice engine unavailable on this phone")
        onTts?.invoke(); onTts = null
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        when (i?.action) {
            "STOP" -> { finish(); return START_NOT_STICKY }
            "SNOOZE" -> { snooze(); return START_NOT_STICKY }
            "DONE" -> { item?.let { d -> Store.update(d.id) { it.copy(doneDate = LocalDate.now().toString()) } }; finish(); return START_NOT_STICKY }
        }
        val id = i?.getLongExtra("id", 0) ?: 0
        playDay = if (i?.action == "PLAY") i.getIntExtra("day", -1) else -1
        val it = if (i?.action == "PLAY") Item(0, "Music", music = true, speak = false, loop = true, stopAfterMin = 60) else Store.items.find { x -> x.id == id }
        val title = it?.title ?: "Auto DJ"
        val n = notif(title, "Tap Stop or Snooze")
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7, n)
        if (it == null) { finish(); return START_NOT_STICKY }
        begin(it); return START_NOT_STICKY
    }

    private fun notif(t: String, text: String): Notification {
        fun a(act: String, label: String) = NotificationCompat.Action(0, label,
            PendingIntent.getService(this, act.hashCode(), Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE))
        return NotificationCompat.Builder(this, "alarm").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(t).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true)
            .addAction(a("STOP", "Stop")).addAction(a("SNOOZE", "Snooze")).addAction(a("DONE", "Done")).build()
    }
    private fun begin(n: Item) {
        val set = Store.settings
        if (player != null && !n.music) {                       // overlap rule: a voice reminder ducks playing music, then music resumes
            overlay = true; player?.setVolume(set.musicVol * 0.25f, set.musicVol * 0.25f)
        } else {
            cleanup(); item = n; overlay = false
            Store.nowPlaying = n.title; Store.nowId = n.id
            h.postDelayed({ finish() }, n.stopAfterMin * 60_000L)    // automatic stop
            requestFocus()
            if (n.vibrate) getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
            if (n.bell) RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
        }
        val go = { speak(n) }
        if (n.bell) h.postDelayed(go, 1500) else go()
    }
    private fun speak(n: Item) {
        if (!n.speak) { afterSpeech(); return }
        val t = tts
        val run = {
            if (!ttsReady || t == null) afterSpeech() else {
                val loc = Locale(Store.settings.lang)
                if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
                else Store.log("Voice for '${loc.displayLanguage}' isn't installed; using device default")
                Store.settings.voiceName.takeIf { it.isNotEmpty() }?.let { nm -> t.voices?.find { it.name == nm }?.let { v -> t.voice = v } }
                val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Store.settings.voiceVol); putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
                t.speak(n.message.ifBlank { n.title }, TextToSpeech.QUEUE_FLUSH, b, "u")
            }
        }
        if (ttsReady || t == null) run() else onTts = { run() }
    }
    private fun afterSpeech() {
        if (overlay) { overlay = false; val v = Store.settings.musicVol; player?.setVolume(v, v); return }
        val n = item ?: return
        if (n.music) startMusic() else h.postDelayed({ if (player == null) finish() }, 3000)
    }
    private fun startMusic() {
        val day = if (playDay >= 0) playDay else LocalDate.now().dayOfWeek.value   // weekday playlist, else default
        tracks = Store.settings.listFor(day).map { Uri.parse(it) }; idx = 0
        if (tracks.isEmpty()) fallback("No songs selected; played the default alarm sound") else play()
    }
    private fun play() {
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(this, tracks[idx]); mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { it.start(); fade(it, 0) }
            mp.setOnCompletionListener { idx++; if (idx >= tracks.size) { if (item?.loop == true) { idx = 0; play() } else finish() } else play() }
            mp.setOnErrorListener { _, _, _ -> next("Could not play a song"); true }
            player = mp; mp.prepareAsync()
        } catch (e: Exception) { next("Could not open song: ${e.message}") }
    }
    private fun next(why: String) { Store.log(why); if (idx + 1 < tracks.size) { idx++; play() } else fallback("$why; played the default alarm sound") }
    private fun fade(mp: MediaPlayer, step: Int) {
        if (player !== mp) return
        val v = Store.settings.musicVol * (step / 10f).coerceAtMost(1f)
        try { mp.setVolume(v, v) } catch (_: Exception) { return }
        if (step < 10) h.postDelayed({ fade(mp, step + 1) }, 1500)     // ~15 s fade-in
    }
    private fun fallback(reason: String) {
        Store.log(reason)
        getSystemService(NotificationManager::class.java).notify(8, NotificationCompat.Builder(this, "alarm")
            .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Auto DJ playback problem").setContentText(reason).build())
        ring = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?.also { it.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(); it.play() }
    }
    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            .setOnAudioFocusChangeListener { f -> h.post { try { when (f) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { player?.pause(); Store.log("Audio interrupted; paused") }
                AudioManager.AUDIOFOCUS_GAIN -> if (!Store.playerPaused) player?.start() } } catch (_: Exception) {} } }.build()
        am.requestAudioFocus(focus!!)
    }
    private fun snooze() {
        item?.takeIf { it.id > 1 }?.let { Scheduler.set(this, it.id, System.currentTimeMillis() + it.snoozeMin * 60_000L, true, false); Store.log("Snoozed ${it.title} ${it.snoozeMin} min") }
        finish()
    }
    private fun cleanup() {
        Store.playerPaused = false; h.removeCallbacksAndMessages(null); player?.release(); player = null; ring?.stop(); ring = null
        tts?.stop(); getSystemService(Vibrator::class.java).cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }
    private fun finish() { cleanup(); Store.nowPlaying = null; Store.nowId = 0; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { live = null; cleanup(); tts?.shutdown(); Store.nowPlaying = null; super.onDestroy() }
}
