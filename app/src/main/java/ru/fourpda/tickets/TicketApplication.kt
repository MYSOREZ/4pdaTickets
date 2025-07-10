package ru.fourpda.tickets

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log

class TicketApplication : Application() {

    companion object {
        private const val TAG = "TicketApp"
        lateinit var instance: TicketApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "🚀 TicketApplication создано")

        // Не запускаем сервис автоматически
        // startMonitoringService()
    }

    private fun startMonitoringService() {
        try {
            val serviceIntent = Intent(this, ForegroundMonitorService::class.java).apply {
                action = ForegroundMonitorService.ACTION_START_MONITORING
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Log.d(TAG, "✅ Мониторинг запущен из Application")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запуска из Application: ${e.message}")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "⚠️ Мало памяти - но мониторинг продолжается")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "🔄 Освобождение памяти, уровень: $level")
    }
}
