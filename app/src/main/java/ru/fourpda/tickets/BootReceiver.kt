package ru.fourpda.tickets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "=== ПОЛУЧЕН BROADCAST: $action ===")

        // Логируем системную информацию
        logSystemInfo(context)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "📱 Устройство загружено (BOOT_COMPLETED)")
                handleDeviceBoot(context, "Обычная загрузка")
            }

            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "⚡ Быстрая загрузка Android (QUICKBOOT_POWERON)")
                handleDeviceBoot(context, "Быстрая загрузка Android")
            }

            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "⚡ Быстрая загрузка HTC (HTC QUICKBOOT)")
                handleDeviceBoot(context, "Быстрая загрузка HTC")
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "🔄 Приложение обновлено (MY_PACKAGE_REPLACED)")
                handleAppUpdate(context)
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.dataString
                if (packageName?.contains(context.packageName) == true) {
                    Log.d(TAG, "🔄 Наше приложение обновлено (PACKAGE_REPLACED)")
                    handleAppUpdate(context)
                }
            }

            else -> {
                Log.d(TAG, "⚠️ Неизвестный action: $action")
            }
        }
    }

    private fun handleDeviceBoot(context: Context, bootType: String) {
        Log.d(TAG, "🚀 Обработка загрузки устройства: $bootType")

        try {
            // Даем системе время стабилизироваться после загрузки
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkAuthenticationAndStartService(context, "Автозапуск при загрузке: $bootType")
            }, 5000) // 5 секунд задержки для стабильности

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при обработке загрузки: ${e.message}")
        }
    }

    private fun handleAppUpdate(context: Context) {
        Log.d(TAG, "🔄 Обработка обновления приложения")

        try {
            // После обновления проверяем настройки и состояние с большей задержкой
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkAuthenticationAndStartService(context, "Автозапуск после обновления")
            }, 3000) // 3 секунды задержки

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка при обработке обновления: ${e.message}")
        }
    }

    private fun checkAuthenticationAndStartService(context: Context, reason: String) {
        Log.d(TAG, "🔍 Проверяем авторизацию пользователя...")

        if (isUserAuthenticated(context)) {
            Log.d(TAG, "✅ Пользователь авторизован - запускаем мониторинг")

            // Дополнительная проверка - не запущен ли уже сервис
            if (!isMonitoringServiceRunning(context)) {
                startMonitoringService(context, reason)
            } else {
                Log.d(TAG, "ℹ️ Сервис мониторинга уже запущен")
            }
        } else {
            Log.d(TAG, "❌ Пользователь не авторизован - автозапуск отменен")
            Log.d(TAG, "💡 Для автозапуска необходимо войти в аккаунт в приложении")
        }
    }

    private fun isUserAuthenticated(context: Context): Boolean {
        return try {
            Log.d(TAG, "🍪 Инициализация CookieManager...")

            // Инициализация CookieManager
            val cookieManager = CookieManager.getInstance()

            // Включаем прием cookies
            cookieManager.setAcceptCookie(true)

            // Для Android 5.0+ настраиваем дополнительные параметры
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Принудительная синхронизация cookies из хранилища
                cookieManager.flush()

                // Настройка WebSettings для корректной работы cookies
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        WebSettings.getDefaultUserAgent(context)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Не удалось инициализировать WebSettings: ${e.message}")
                }
            }

            // Получаем cookies для 4PDA
            val cookies = cookieManager.getCookie("https://4pda.to") ?: ""
            Log.d(TAG, "🍪 Найдено cookies: ${cookies.length} символов")

            if (cookies.isNotEmpty()) {
                Log.d(TAG, "🍪 Cookies preview: ${cookies.take(200)}...")
            } else {
                Log.d(TAG, "🍪 Cookies пустые или не найдены")
            }

            // Проверяем наличие необходимых cookies для авторизации
            val hasValidCookies = cookies.contains("member_id=") &&
                    cookies.contains("pass_hash=") &&
                    !cookies.contains("member_id=0") &&
                    cookies.isNotEmpty()

            if (hasValidCookies) {
                Log.d(TAG, "✅ Найдены валидные cookies для автозапуска")

                // Дополнительная проверка - извлекаем member_id для подтверждения
                val memberIdRegex = "member_id=([^;]+)".toRegex()
                val memberIdMatch = memberIdRegex.find(cookies)
                val memberId = memberIdMatch?.groupValues?.get(1) ?: "unknown"

                Log.d(TAG, "👤 Member ID: $memberId")

                if (memberId != "0" && memberId != "unknown" && memberId.isNotEmpty()) {
                    Log.d(TAG, "✅ Cookies подтверждены: пользователь $memberId авторизован")
                    return true
                } else {
                    Log.d(TAG, "❌ Member ID невалидный: $memberId")
                    return false
                }
            } else {
                Log.d(TAG, "❌ Валидные cookies не найдены")
                Log.d(TAG, "🔍 Причины: member_id=${cookies.contains("member_id=")}, " +
                        "pass_hash=${cookies.contains("pass_hash=")}, " +
                        "not_zero=${!cookies.contains("member_id=0")}, " +
                        "not_empty=${cookies.isNotEmpty()}")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки авторизации: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun startMonitoringService(context: Context, reason: String) {
        try {
            Log.d(TAG, "🚀 Запуск ForegroundMonitorService")
            Log.d(TAG, "📋 Причина: $reason")

            val serviceIntent = Intent(context, ForegroundMonitorService::class.java).apply {
                action = ForegroundMonitorService.ACTION_START_MONITORING
                putExtra("auto_start_reason", reason)
                putExtra("started_by", "BootReceiver")
                putExtra("start_timestamp", System.currentTimeMillis())
            }

            // УСТАРЕЛО: Больше не запускаем ForegroundService
            // Теперь используем только точные будильники через ExactAlarmScheduler
            Log.d(TAG, "ℹ️ Автозапуск ForegroundService отключен - используем ExactAlarmScheduler")
            
            // Проверяем, что точные будильники настроены
            val intervalSeconds = SettingsActivity.getRefreshInterval(context)
            if (ExactAlarmScheduler.canScheduleExactAlarms(context)) {
                ExactAlarmScheduler.scheduleNextAlarmSeconds(context, intervalSeconds)
                Log.d(TAG, "✅ Перепланирован точный будильник на $intervalSeconds секунд")
            } else {
                Log.w(TAG, "⚠️ Нет разрешения на точные будильники")
            }

            Log.d(TAG, "✅ Точные будильники успешно настроены автоматически")

            // Запускаем дополнительные механизмы защиты от усыпления
            KeepAliveWorker.scheduleWork(context)
            AlarmReceiver.scheduleAlarm(context)
            
            // НОВОЕ: Восстанавливаем точные будильники после перезагрузки
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ExactAlarmScheduler.canScheduleExactAlarms(context)) {
                ExactAlarmScheduler.scheduleNextAlarm(context, 5)
                Log.d(TAG, "✅ Восстановлены точные будильники после перезагрузки")
            } else {
                Log.d(TAG, "⚠️ Точные будильники недоступны - используем резервные механизмы")
            }
            
            Log.d(TAG, "✅ Запущены все механизмы защиты от усыпления при автозапуске")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запуска ForegroundMonitorService: ${e.message}")
            e.printStackTrace()

            // Пытаемся настроить точные будильники через несколько секунд
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    Log.d(TAG, "🔄 Повторная попытка настройки точных будильников...")
                    
                    val intervalSeconds = SettingsActivity.getRefreshInterval(context)
                    if (ExactAlarmScheduler.canScheduleExactAlarms(context)) {
                        ExactAlarmScheduler.scheduleNextAlarmSeconds(context, intervalSeconds)
                        Log.d(TAG, "✅ Повторная настройка точных будильников выполнена")
                    } else {
                        Log.w(TAG, "⚠️ Все еще нет разрешения на точные будильники")
                    }

                    Log.d(TAG, "✅ Повторная настройка выполнена")

                } catch (retryException: Exception) {
                    Log.e(TAG, "❌ Повторный запуск также неудачен: ${retryException.message}")
                }
            }, 10000) // Повторная попытка через 10 секунд
        }
    }

    private fun isMonitoringServiceRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = manager.getRunningServices(100)

            for (service in runningServices) {
                if (ForegroundMonitorService::class.java.name == service.service.className) {
                    Log.d(TAG, "🔍 ForegroundMonitorService уже работает (PID: ${service.pid})")
                    return true
                }
            }

            Log.d(TAG, "🔍 ForegroundMonitorService не найден в запущенных сервисах")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки состояния сервиса: ${e.message}")
            return false
        }
    }

    private fun logSystemInfo(context: Context) {
        try {
            Log.d(TAG, "📊 === СИСТЕМНАЯ ИНФОРМАЦИЯ ===")
            Log.d(TAG, "📱 Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            Log.d(TAG, "🤖 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Log.d(TAG, "📦 Приложение: ${context.packageName}")
            Log.d(TAG, "⏰ Время события: ${System.currentTimeMillis()}")
            Log.d(TAG, "🕐 Время работы системы: ${android.os.SystemClock.elapsedRealtime()}ms")

            // Проверяем настройки приложения
            val refreshInterval = SettingsActivity.getRefreshInterval(context)
            Log.d(TAG, "⚙️ Настройка интервала: $refreshInterval секунд")

            // Проверяем состояние батареи (если возможно)
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && batteryManager != null) {
                    val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    Log.d(TAG, "🔋 Уровень батареи: $batteryLevel%")
                }
            } catch (e: Exception) {
                Log.d(TAG, "🔋 Не удалось получить информацию о батарее")
            }

            Log.d(TAG, "📊 === КОНЕЦ СИСТЕМНОЙ ИНФОРМАЦИИ ===")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка логирования системной информации: ${e.message}")
        }
    }
}
