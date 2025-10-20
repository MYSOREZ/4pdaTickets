package ru.fourpda.tickets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Ресивер для точных будильников каждые 5 минут
 * Запускает короткую задачу проверки без постоянного FGS
 */
class ExactAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ExactAlarmReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val message = "⏰ Точный будильник сработал: ${intent.action}"
        Log.d(TAG, message)
        FileLogger.d(TAG, message)
        
        if (intent.action != ExactAlarmScheduler.ACTION_EXACT_ALARM) return
        
        // Получаем WakeLock для гарантии выполнения
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "4pdaTickets:ExactAlarmWakeLock"
        )
        
        try {
            wakeLock.acquire(30_000L) // 30 секунд максимум
            
            // Проверяем, не был ли мониторинг остановлен пользователем
            val prefs = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
            if (prefs.getBoolean("explicitly_stopped", false)) {
                val stopMessage = "✅ Мониторинг остановлен пользователем - отменяем будильники"
                Log.d(TAG, stopMessage)
                FileLogger.i(TAG, stopMessage)
                ExactAlarmScheduler.cancelAlarm(context)
                return
            }
            
            // Проверяем авторизацию
            if (!isUserAuthenticated(context)) {
                Log.d(TAG, "❌ Пользователь не авторизован")
                scheduleNext(context)
                return
            }
            
            // Запускаем короткую задачу проверки с proper constraints
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false) // Разрешаем работу при низком заряде
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<QuickCheckWorker>()
                .setConstraints(constraints)
                .addTag("exact_alarm_check")
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
            
            // Используем enqueueUniqueWork для предотвращения наложений
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ticket_check_work",
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
            val workMessage = "🚀 Запущена задача быстрой проверки"
            Log.d(TAG, workMessage)
            FileLogger.d(TAG, workMessage)
            
            // Обновляем служебное уведомление
            NotificationUpdater.updateServiceNotification(context)
            
            // Планируем следующий будильник
            scheduleNext(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка в ExactAlarmReceiver: ${e.message}")
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
    
    private fun scheduleNext(context: Context) {
        val intervalSeconds = SettingsActivity.getRefreshInterval(context)
        
        Log.d(TAG, "📅 Планируем следующий будильник через $intervalSeconds секунд")
        ExactAlarmScheduler.scheduleNextAlarmSeconds(context, intervalSeconds)
    }
    
    private fun isUserAuthenticated(context: Context): Boolean {
        return try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://4pda.to") ?: ""
            
            cookies.contains("member_id=") && 
            cookies.contains("pass_hash=") && 
            !cookies.contains("member_id=0")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки авторизации: ${e.message}")
            false
        }
    }
}