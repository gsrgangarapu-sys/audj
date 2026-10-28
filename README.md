# Auto DJ (Android, standalone)
Kotlin + Jetpack Compose. Everything runs on the phone. No account, no PC.

## Get an installable debug APK (no Android Studio needed)
1. Create a new GitHub repository and upload this folder's contents (keep the .github folder).
2. Open the Actions tab -> "Build debug APK" -> wait for the green check.
3. Open the run, download the artifact "AutoDJ-debug-apk", unzip, copy app-debug.apk to your phone and open it
   (allow "Install unknown apps" for your file manager when asked).
If the build fails, open the failed step and send me the red error lines.

## Or build locally
Android Studio (Koala+): File -> Open this folder, accept Gradle sync, Run on a phone with USB debugging.

## Source map
- Store.kt: models, JSON storage, backup/restore, load-error recovery
- Scheduler.kt: next-run rules (once, daily, weekdays, monthly, yearly, interval, countdown), end date, holiday, pause, AlarmManager
- Receivers.kt: alarm delivery (duplicate guard, missed rules, skip, quiet hours, silent notifications), reboot/timezone recovery
- PlaybackService.kt: foreground playback, voice, fade-in, ducking, pause, auto stop, audio-interruption handling, fallback sound
- Assistant.kt: offline command parser with confirm and undo
- MainActivity.kt: Home, Timetable, Audio, Settings, assistant, guided setup, dark mode
