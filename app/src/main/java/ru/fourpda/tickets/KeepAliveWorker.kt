package ru.fourpda.tickets

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Worker для поддержания сервиса в активном состоянии
 * WorkManager более устойчив к усыплению чем обычные сервисы
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"
        const val WORK_NAME = "KeepAliveWork"
        const val KEEP_ALIVE_INTERVAL_MINUTES = 15L // Проверка каждые 15 минут

        fun scheduleWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val keepAliveRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                KEEP_ALIVE_INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                keepAliveRequest
            )

            Log.d(TAG, "✅ KeepAliveWorker запланирован (интервал: $KEEP_ALIVE_INTERVAL_MINUTES мин)")
        }

        fun cancelWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "🛑 KeepAliveWorker отменен")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "🔄 KeepAliveWorker: Проверка состояния сервиса...")

        return try {
            // Проверяем, был ли сервис остановлен пользователем
            val prefs = applicationContext.getSharedPreferences("service_state", Context.MODE_PRIVATE)
            if (prefs.getBoolean("explicitly_stopped", false)) {
                Log.d(TAG, "✅ Мониторинг остановлен пользователем - KeepAlive не нужен")
                return Result.success()
            }

            // Проверяем, авторизован ли пользователь
            if (!isUserAuthenticated()) {
                Log.d(TAG, "❌ Пользователь не авторизован - пропускаем проверку")
                return Result.success()
            }

            // НОВАЯ ЛОГИКА: Проверяем точные будильники вместо FGS
            if (!ExactAlarmScheduler.canScheduleExactAlarms(applicationContext)) {
                Log.w(TAG, "⚠️ Нет разрешения на точные будильники")
                return Result.success()
            }
            
            Log.d(TAG, "✅ Точные будильники работают, FGS больше не используется")

            // Проверяем и перепланируем точные будильники если нужно
            val intervalSeconds = SettingsActivity.getRefreshInterval(applicationContext)
            ExactAlarmScheduler.scheduleNextAlarmSeconds(applicationContext, intervalSeconds)
            Log.d(TAG, "✅ Перепланирован точный будильник на $intervalSeconds секунд")

            // Обновляем время последней проверки
            saveLastCheckTime()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка в KeepAliveWorker: ${e.message}")
            Result.retry()
        }
    }

    private fun isUserAuthenticated(): Boolean {
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

    private fun isServiceRunning(): Boolean {
        return try {
            val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = manager.getRunningServices(100)
            
            runningServices.any { 
                ForegroundMonitorService::class.java.name == it.service.className 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки сервиса: ${e.message}")
            false
        }
    }

    private fun startMonitoringService() {
        // УСТАРЕЛО: Больше не запускаем ForegroundService
        // Теперь используем только точные будильники через ExactAlarmScheduler
        Log.d(TAG, "ℹ️ startMonitoringService() устарел - используем ExactAlarmScheduler")
        
        try {
            // Проверяем, что точные будильники настроены
            val intervalSeconds = SettingsActivity.getRefreshInterval(applicationContext)
            ExactAlarmScheduler.scheduleNextAlarmSeconds(applicationContext, intervalSeconds)
            Log.d(TAG, "✅ Перепланирован точный будильник на $intervalSeconds секунд")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка перепланирования будильника: ${e.message}")
        }
    }

    private fun sendPingToService() {
        val pingIntent = Intent(applicationContext, ForegroundMonitorService::class.java).apply {
            action = "ru.fourpda.tickets.action.PING"
        }
        applicationContext.startService(pingIntent)
        Log.d(TAG, "📡 Пинг отправлен сервису")
    }

    private fun saveLastCheckTime() {
        val prefs = applicationContext.getSharedPreferences("keep_alive_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_check_time", System.currentTimeMillis()).apply()
    }
}
