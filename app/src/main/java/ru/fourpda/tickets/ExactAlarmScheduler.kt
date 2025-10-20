package ru.fourpda.tickets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.*
import kotlin.math.ceil

/**
 * Планировщик точных будильников для частых проверок (каждые 5 минут)
 * Используется как альтернатива постоянному FGS для обхода лимита Android 15
 */
object ExactAlarmScheduler {
    private const val TAG = "ExactAlarmScheduler"
    private const val REQUEST_CODE = 12345
    const val ACTION_EXACT_ALARM = "ru.fourpda.tickets.EXACT_ALARM"
    
    fun scheduleNextAlarm(context: Context, intervalMinutes: Int = 5) {
        val intervalSeconds = SettingsActivity.getRefreshInterval(context)
        scheduleNextAlarmSeconds(context, intervalSeconds)
    }
    
    fun scheduleNextAlarmSeconds(context: Context, intervalSeconds: Int) {
        // Используем пользовательский интервал как есть (минимум 60 сек установлен в настройках)
        val safeInterval = intervalSeconds
        
        if (!canScheduleExactAlarms(context)) {
            Log.w(TAG, "⚠️ Нет разрешения на точные будильники - переходим на WorkManager")
            scheduleWorkManagerFallback(context, safeInterval)
            return
        }
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
            action = ACTION_EXACT_ALARM
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Якорное планирование для избежания дрейфа
        val prefs = context.getSharedPreferences("alarm_schedule", Context.MODE_PRIVATE)
        val lastScheduled = prefs.getLong("last_scheduled", 0L)
        val now = SystemClock.elapsedRealtime()
        
        val triggerTime = if (lastScheduled == 0L) {
            now + (safeInterval.toLong() * 1000L)
        } else {
            val intervalMs = safeInterval.toLong() * 1000L
            val n = ceil(((now - lastScheduled).toDouble() / intervalMs.toDouble())).toLong() + 1L
            lastScheduled + (n * intervalMs)
        }
        
        // Сохраняем время планирования
        prefs.edit().putLong("last_scheduled", triggerTime).apply()
        
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            }
            
            val triggerInSeconds = (triggerTime - now) / 1000L
            val successMessage = "✅ Точный будильник установлен на ${safeInterval} секунд (через ${triggerInSeconds}с)"
            Log.d(TAG, successMessage)
            FileLogger.d(TAG, successMessage)
        } catch (e: Exception) {
            val errorMessage = "❌ Ошибка установки точного будильника: ${e.message}"
            Log.e(TAG, errorMessage)
            FileLogger.e(TAG, errorMessage, e)
            scheduleWorkManagerFallback(context, safeInterval)
        }
    }
    
    private fun scheduleWorkManagerFallback(context: Context, intervalSeconds: Int) {
        Log.w(TAG, "🔄 Переход на WorkManager fallback с интервалом ${maxOf(intervalSeconds, 900)} секунд")
        // Здесь можно добавить логику WorkManager periodic с минимумом 15 минут
        // Пока просто логируем
    }
    
    private fun scheduleInexactAlarm(context: Context, intervalMinutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
            action = ACTION_EXACT_ALARM
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        
        val triggerTime = System.currentTimeMillis() + (intervalMinutes * 60 * 1000L)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        
        Log.d(TAG, "⚠️ Установлен неточный будильник на ${intervalMinutes} минут")
    }
    
    private fun scheduleInexactAlarmSeconds(context: Context, intervalSeconds: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
            action = ACTION_EXACT_ALARM
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        
        val triggerTime = System.currentTimeMillis() + (intervalSeconds * 1000L)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        
        Log.d(TAG, "⚠️ Установлен неточный будильник на ${intervalSeconds} секунд")
    }
    
    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ExactAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
        )
        
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "🛑 Точные будильники отменены")
    }
    
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true // На Android < 12 разрешение не требуется
        }
    }
    
    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}