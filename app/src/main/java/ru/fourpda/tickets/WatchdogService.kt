package ru.fourpda.tickets

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Watchdog-сервис для дополнительной защиты от усыпления.
 * Примечание: основной механизм — точные будильники + короткие задачи (WorkManager).
 * Этот сервис оставлен как опциональный слой совместимости (например, для агрессивных прошивок),
 * не перезапускает ForegroundMonitorService и не зависит от него.
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val WATCHDOG_INTERVAL = 5 * 60 * 1000L // 5 минут
        private const val NOTIFICATION_ID = 2001

        fun startWatchdog(context: Context) {
            // УСТАРЕЛО: по умолчанию не используем Watchdog — основной механизм через точные будильники.
            // Если потребуется реальный запуск, вызовите явно startService(Intent(..., WatchdogService::class.java)).
            Log.d(TAG, "ℹ️ WatchdogService устарел - используем ExactAlarmScheduler (по умолчанию)")
        }

        fun stopWatchdog(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val wdJob = SupervisorJob()
    private val wdScope = CoroutineScope(Dispatchers.Default + wdJob)
    private var isRunning = false

    /* ------------------------------- lifecycle ------------------------------- */

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🐕 WatchdogService создан")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "4pdaTickets:WatchdogWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🐕 WatchdogService запущен")

        if (!isRunning) {
            isRunning = true
            // ВАЖНО: гарантируем каналы до старта foreground-уведомления
            NotificationChannels.ensure(this)
            startForeground(NOTIFICATION_ID, createNotification())
            startLoop()
            acquireWakeLock()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🐕 WatchdogService уничтожен")

        isRunning = false
        wdScope.cancel()
        releaseWakeLock()

        if (!isStoppedExplicitly()) scheduleRestart()
    }

    /* -------------------------------- watchdog -------------------------------- */

    private fun startLoop() = wdScope.launch {
        while (isActive && isRunning) {
            try {
                checkAndProtect()
                delay(WATCHDOG_INTERVAL)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка цикла: ${e.message}")
            }
        }
    }

    private suspend fun checkAndProtect() = withContext(Dispatchers.IO) {
        Log.d(TAG, "🐕 Проверяем состояние фоновых механизмов…")

        // 1) Пользователь явно остановил мониторинг — завершаем Watchdog
        if (isStoppedExplicitly()) {
            Log.d(TAG, "✅ Мониторинг остановлен пользователем — Watchdog останавливается")
            stopSelf()
            return@withContext
        }

        // 2) Убедимся, что точные будильники (основа мониторинга) актуальны
        val intervalSeconds = SettingsActivity.getRefreshInterval(this@WatchdogService)
        if (ExactAlarmScheduler.canScheduleExactAlarms(this@WatchdogService)) {
            // Идемпотентное перепланирование (якорное внутри планировщика)
            ExactAlarmScheduler.scheduleNextAlarmSeconds(this@WatchdogService, intervalSeconds)
            Log.d(TAG, "✅ Обеспечено расписание точных будильников (интервал ${intervalSeconds}s)")
        } else {
            Log.w(TAG, "⚠️ Нет разрешения на точные будильники — мониторинг может быть редким")
        }

        // 3) Контроль WorkManager (keep-alive маркер)
        checkWorkManager()

        // 4) Контроль резервного AlarmReceiver (10 минут)
        checkAlarmManager()

        // 5) Обновление маркера активности Watchdog
        updateLastActivity()

        // 6) MIUI/китайские прошивки — мягкие обходы ограничений
        if (MiuiUtils.isMiui()) performMiuiChecks()
    }

    /* -------------------------------- helpers -------------------------------- */

    private fun checkWorkManager() {
        val p = getSharedPreferences("keep_alive_prefs", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last_check_time", 0) > 20 * 60 * 1000) { // 20 минут
            Log.w(TAG, "⚠️ WorkManager неактивен — перепланируем")
            KeepAliveWorker.scheduleWork(this)
        }
    }

    private fun checkAlarmManager() {
        val p = getSharedPreferences("alarm_prefs", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last_alarm_time", 0) > 25 * 60 * 1000) { // ~2.5 интервала по 10 мин
            Log.w(TAG, "⚠️ AlarmReceiver неактивен — перепланируем")
            AlarmReceiver.scheduleAlarm(this)
        }
    }

    private fun updateLastActivity() =
        getSharedPreferences("watchdog_prefs", MODE_PRIVATE)
            .edit()
            .putLong("last_activity_time", System.currentTimeMillis())
            .apply()

    /* --------------------------- MIUI-специфика --------------------------- */

    private fun performMiuiChecks() {
        Log.d(TAG, "🔍 MIUI-проверки")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am.isBackgroundRestricted) {
            Log.w(TAG, "⚠️ Система ограничила работу в фоне — мягкий обход")
            bypassRestrictions()
        }
    }

    private fun bypassRestrictions() {
        // Перевзять wakelock, отправить «будильник» внутри процесса
        releaseWakeLock(); acquireWakeLock()
        Handler(Looper.getMainLooper()).post {
            try {
                sendBroadcast(Intent("ru.fourpda.tickets.WAKE_UP").setPackage(packageName))
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обхода ограничений: ${e.message}")
            }
        }
    }

    /* ------------------------------ WakeLock ------------------------------ */

    private fun acquireWakeLock() {
        try {
            wakeLock?.takeIf { !it.isHeld }?.apply {
                acquire(10 * 60 * 1000L)
                Log.d(TAG, "🔋 WakeLock удержан")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось взять WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось освободить WakeLock: ${e.message}")
        }
    }

    /* ---------------------------- notification ---------------------------- */

    private fun createNotification(): Notification =
        Notification.Builder(this).apply {
            setContentTitle("Защита мониторинга")
            setContentText("Watchdog активен")
            setSmallIcon(android.R.drawable.ic_menu_manage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Используем централизованный канал
                setChannelId(NotificationChannels.SERVICE_CHANNEL_ID)
            }
        }.build()

    /* ------------------------------ перезапуск ----------------------------- */

    private fun isStoppedExplicitly(): Boolean =
        getSharedPreferences("service_state", MODE_PRIVATE)
            .getBoolean("explicitly_stopped", false)

    private fun scheduleRestart() {
        Log.d(TAG, "📅 Планируем перезапуск WatchdogService через 5 минут")

        val pi = PendingIntent.getService(
            this, 9999,
            Intent(this, WatchdogService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0
        )

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val time = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(5)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, pi)
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, pi)
        }
    }
}
