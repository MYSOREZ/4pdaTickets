package ru.fourpda.tickets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.Calendar

/**
 * BroadcastReceiver для периодического пробуждения через AlarmManager
 * Более надежный способ для борьбы с усыплением на MIUI
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val ALARM_INTERVAL_MINUTES = 10 // Интервал проверки (резервная защита)
        const val ACTION_CHECK_SERVICE = "ru.fourpda.tickets.CHECK_SERVICE"

        fun scheduleAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_CHECK_SERVICE
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            )

            // Отменяем предыдущие алармы
            alarmManager.cancel(pendingIntent)

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                add(Calendar.MINUTE, ALARM_INTERVAL_MINUTES)
            }

            // Используем точный алларм для надежности
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    // Android 12+ требует специальное разрешение для точных аллармов
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            calendar.timeInMillis,
                            pendingIntent
                        )
                        Log.d(TAG, "✅ Точный алларм установлен (Android 12+)")
                    } else {
                        // Используем неточный алларм если нет разрешения
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            calendar.timeInMillis,
                            pendingIntent
                        )
                        Log.d(TAG, "⚠️ Установлен неточный алларм (нет разрешения на точные)")
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                    Log.d(TAG, "✅ Точный алларм установлен")
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                    Log.d(TAG, "✅ Алларм установлен")
                }
            }

            Log.d(TAG, "⏰ Следующая резервная проверка через $ALARM_INTERVAL_MINUTES минут")
        }

        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            )
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "🛑 Алларм отменен")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "⏰ AlarmReceiver сработал: ${intent.action}")

        // Получаем WakeLock для гарантии выполнения
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "4pdaTickets:AlarmWakeLock"
        )

        try {
            // Держим устройство пробужденным на время проверки
            wakeLock.acquire(10_000L) // 10 секунд максимум

            when (intent.action) {
                ACTION_CHECK_SERVICE -> {
                    checkAndRestartService(context)
                    // Планируем следующий алларм
                    scheduleAlarm(context)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка в AlarmReceiver: ${e.message}")
        } finally {
            // Освобождаем WakeLock
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun checkAndRestartService(context: Context) {
        Log.d(TAG, "🔍 Проверка состояния сервиса через AlarmReceiver")

        // Проверяем, был ли сервис остановлен пользователем
        val prefs = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
        if (prefs.getBoolean("explicitly_stopped", false)) {
            Log.d(TAG, "✅ Сервис был остановлен пользователем, AlarmReceiver отменяет проверку.")
            // Важно также отменить будущие алармы, если сервис остановлен
            cancelAlarm(context)
            return
        }

        // Проверяем авторизацию
        if (!isUserAuthenticated(context)) {
            Log.d(TAG, "❌ Пользователь не авторизован")
            return
        }

        // Проверяем состояние сервиса
        if (!isServiceRunning(context)) {
            Log.d(TAG, "⚠️ Сервис не активен - запускаем!")
            startMonitoringService(context)
        } else {
            Log.d(TAG, "✅ Сервис работает")
            // Отправляем сигнал сервису для подтверждения активности
            sendKeepAliveSignal(context)
        }

        // Сохраняем время последней проверки
        saveLastAlarmTime(context)
    }

    private fun isUserAuthenticated(context: Context): Boolean {
        return try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://4pda.to") ?: ""
            
            cookies.contains("member_id=") && 
            cookies.contains("pass_hash=") && 
            !cookies.contains("member_id=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = manager.getRunningServices(100)
            
            runningServices.any { 
                ForegroundMonitorService::class.java.name == it.service.className 
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startMonitoringService(context: Context) {
        try {
            // УСТАРЕЛО: Больше не запускаем ForegroundService
            // Теперь используем только точные будильники через ExactAlarmScheduler
            Log.d(TAG, "ℹ️ startMonitoringService() устарел - используем ExactAlarmScheduler")
            
            // Проверяем, что точные будильники настроены
            val intervalSeconds = SettingsActivity.getRefreshInterval(context)
            if (ExactAlarmScheduler.canScheduleExactAlarms(context)) {
                ExactAlarmScheduler.scheduleNextAlarmSeconds(context, intervalSeconds)
                Log.d(TAG, "✅ Перепланирован точный будильник на $intervalSeconds секунд")
            } else {
                Log.w(TAG, "⚠️ Нет разрешения на точные будильники")
            }

            Log.d(TAG, "✅ Точные будильники настроены через AlarmReceiver")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка настройки будильников: ${e.message}")
        }
    }

    private fun sendKeepAliveSignal(context: Context) {
        val intent = Intent(context, ForegroundMonitorService::class.java).apply {
            action = "ru.fourpda.tickets.action.KEEP_ALIVE"
        }
        context.startService(intent)
    }

    private fun saveLastAlarmTime(context: Context) {
        val prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_alarm_time", System.currentTimeMillis()).apply()
    }
}
