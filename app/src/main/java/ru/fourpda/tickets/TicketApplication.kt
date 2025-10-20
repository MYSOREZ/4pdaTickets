package ru.fourpda.tickets

import android.app.Application
import android.content.Context
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

        // Инициализируем файловое логирование
        FileLogger.init(this)

        // Устанавливаем глобальный обработчик исключений
        setupGlobalExceptionHandler()

        // ВАЖНО: гарантируем, что каналы уведомлений существуют
        // (нужно до любого notify(), чтобы на Android 13 не было «немых» уведомлений)
        NotificationChannels.ensure(this)

        FileLogger.i(TAG, "🚀 TicketApplication создано")
        Log.d(TAG, "🚀 TicketApplication создано")

        // Не запускаем сервис автоматически
        // startMonitoringService()
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                // Записываем краш в лог
                FileLogger.e(TAG, "💥 КРИТИЧЕСКАЯ ОШИБКА! Приложение завершается аварийно")
                FileLogger.e(TAG, "Thread: ${thread.name}")
                FileLogger.e(TAG, "Exception: ${exception.javaClass.simpleName}: ${exception.message}")
                FileLogger.e(TAG, "StackTrace:", exception)

                // Записываем состояние приложения
                val prefs = getSharedPreferences("service_state", Context.MODE_PRIVATE)
                val wasMonitoring = prefs.getBoolean("explicitly_stopped", true) == false
                FileLogger.e(TAG, "Состояние мониторинга: ${if (wasMonitoring) "АКТИВЕН" else "ОСТАНОВЛЕН"}")

                // Принудительно сохраняем лог
                Thread.sleep(100) // Даем время на запись
            } catch (_: Exception) {
                // Если даже логирование упало - игнорируем
            }

            // Передаем управление стандартному обработчику
            defaultHandler?.uncaughtException(thread, exception)
        }

        FileLogger.d(TAG, "✅ Глобальный обработчик исключений установлен")
    }

    // Оставлено для совместимости, но не используется автоматически
    private fun startMonitoringService() {
        try {
            // УСТАРЕЛО: Больше не запускаем ForegroundService из Application
            // Теперь используем только точные будильники через ExactAlarmScheduler
            Log.d(TAG, "ℹ️ Автозапуск ForegroundService из Application отключен - используем ExactAlarmScheduler")

            // Проверяем, что точные будильники настроены (если пользователь уже включил мониторинг)
            val prefs = getSharedPreferences("service_state", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("explicitly_stopped", false)) {
                val intervalSeconds = SettingsActivity.getRefreshInterval(this)
                if (ExactAlarmScheduler.canScheduleExactAlarms(this)) {
                    ExactAlarmScheduler.scheduleNextAlarmSeconds(this, intervalSeconds)
                    Log.d(TAG, "✅ Перепланирован точный будильник на $intervalSeconds секунд")
                } else {
                    Log.w(TAG, "⚠️ Нет разрешения на точные будильники")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка настройки будильников из Application: ${e.message}")
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
