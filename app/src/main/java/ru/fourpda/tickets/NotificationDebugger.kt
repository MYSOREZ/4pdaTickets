package ru.fourpda.tickets

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Утилита для отладки состояния уведомлений
 */
object NotificationDebugger {
    private const val TAG = "NotificationDebugger"

    /**
     * Проверяет все аспекты системы уведомлений
     */
    fun debugNotificationSystem(context: Context): String {
        val debug = StringBuilder()
        debug.appendLine("=== ОТЛАДКА СИСТЕМЫ УВЕДОМЛЕНИЙ ===")

        // 1. Основная информация о системе
        debug.appendLine("📱 Android версия: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        debug.appendLine("📱 Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 2. Общие разрешения на уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val areNotificationsEnabled = notificationManager.areNotificationsEnabled()
            debug.appendLine("🔔 Уведомления разрешены: $areNotificationsEnabled")
        }

        // 3. Важность уведомлений (для Android 7.1+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val importance = notificationManager.importance
            debug.appendLine("⚠️ Важность уведомлений: $importance (${getImportanceDescription(importance)})")
        }

        // 4. Каналы уведомлений (для Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            debug.appendLine("\n=== КАНАЛЫ УВЕДОМЛЕНИЙ ===")

            val channels = notificationManager.notificationChannels
            debug.appendLine("📺 Всего каналов: ${channels.size}")

            channels.forEach { channel ->
                debug.appendLine("📺 Канал: ${channel.id}")
                debug.appendLine("   📛 Название: ${channel.name}")
                debug.appendLine("   📝 Описание: ${channel.description ?: "Нет описания"}")
                debug.appendLine("   ⚠️ Важность: ${channel.importance} (${getImportanceDescription(channel.importance)})")
                debug.appendLine("   🔔 Звук: ${if (channel.sound != null) "Включен" else "Отключен"}")
                debug.appendLine("   📳 Вибрация: ${channel.shouldVibrate()}")
                debug.appendLine("   💡 Световой индикатор: ${channel.shouldShowLights()}")
                debug.appendLine("   🏷️ Значок: ${channel.canShowBadge()}")
                debug.appendLine("")
            }

            // Проверяем наши специфические каналы
            checkSpecificChannel(notificationManager, NotificationChannels.SERVICE_CHANNEL_ID, debug)
            checkSpecificChannel(notificationManager, NotificationChannels.TICKETS_CHANNEL_ID, debug)
        }

        // 5. Активные уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            debug.appendLine("=== АКТИВНЫЕ УВЕДОМЛЕНИЯ ===")
            val activeNotifications = notificationManager.activeNotifications
            debug.appendLine("📱 Активных уведомлений: ${activeNotifications.size}")

            activeNotifications.forEach { statusBarNotification ->
                debug.appendLine("📱 ID: ${statusBarNotification.id}, Tag: ${statusBarNotification.tag ?: "null"}")
                debug.appendLine("   📋 Пакет: ${statusBarNotification.packageName}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    debug.appendLine("   📺 Канал: ${statusBarNotification.notification.channelId}")
                }
                debug.appendLine("")
            }
        }

        val result = debug.toString()
        Log.d(TAG, result)
        return result
    }

    /**
     * Проверяет конкретный канал уведомлений
     */
    private fun checkSpecificChannel(
        notificationManager: NotificationManager,
        channelId: String,
        debug: StringBuilder
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(channelId)
            if (channel != null) {
                debug.appendLine("✅ Канал '$channelId' существует и настроен")
            } else {
                debug.appendLine("❌ Канал '$channelId' НЕ НАЙДЕН!")
            }
        }
    }

    /**
     * Описание уровня важности уведомлений
     */
    private fun getImportanceDescription(importance: Int): String {
        return when (importance) {
            NotificationManager.IMPORTANCE_NONE -> "NONE (уведомления заблокированы)"
            NotificationManager.IMPORTANCE_MIN -> "MIN (только в панели уведомлений)"
            NotificationManager.IMPORTANCE_LOW -> "LOW (без звука и вибрации)"
            NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT (со звуком)"
            NotificationManager.IMPORTANCE_HIGH -> "HIGH (со звуком и всплывающими)"
            else -> "UNSPECIFIED ($importance)"
        }
    }

    /**
     * Тестирует отправку уведомления для проверки работоспособности
     */
    fun testNotification(context: Context, channelId: String, title: String, text: String): String {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setContentTitle("🧪 ТЕСТ: $title")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, notification)

            Log.d(TAG, "✅ Тестовое уведомление отправлено (ID: $notificationId)")
            "✅ Тестовое уведомление отправлено успешно"
        } catch (e: Exception) {
            val error = "❌ Ошибка отправки тестового уведомления: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    /**
     * Быстрая проверка - работают ли уведомления вообще
     */
    fun quickCheck(context: Context): String {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return buildString {
            appendLine("=== БЫСТРАЯ ПРОВЕРКА ===")

            // Разрешены ли уведомления
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val enabled = notificationManager.areNotificationsEnabled()
                appendLine("🔔 Уведомления ${if (enabled) "РАЗРЕШЕНЫ" else "ЗАБЛОКИРОВАНЫ"}")
                if (!enabled) {
                    appendLine("❌ ПРОБЛЕМА: Уведомления отключены в настройках системы!")
                }
            }

            // Есть ли наши каналы
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serviceChannel = notificationManager.getNotificationChannel(NotificationChannels.SERVICE_CHANNEL_ID)
                val ticketsChannel = notificationManager.getNotificationChannel(NotificationChannels.TICKETS_CHANNEL_ID)

                appendLine("📺 Канал службы: ${if (serviceChannel != null) "OK" else "ОТСУТСТВУЕТ"}")
                appendLine("📺 Канал тикетов: ${if (ticketsChannel != null) "OK" else "ОТСУТСТВУЕТ"}")

                if (serviceChannel == null || ticketsChannel == null) {
                    appendLine("❌ ПРОБЛЕМА: Каналы уведомлений не созданы!")
                }
            }
        }
    }
}
