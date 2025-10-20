package ru.fourpda.tickets

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Обновляет служебное уведомление без использования постоянного FGS
 * Избегает ограничения Android 15 в 6 часов для foreground services
 */
object NotificationUpdater {
    private const val TAG = "NotificationUpdater"
    private const val NOTIFICATION_ID = 1

    fun updateServiceNotification(context: Context) {
        try {
            // Гарантируем наличие каналов перед любым notify()
            NotificationChannels.ensure(context)

            val prefs = context.getSharedPreferences("ticket_stats", Context.MODE_PRIVATE)
            val totalTickets = prefs.getInt("total_tickets", 0)
            val processedTickets = prefs.getInt("processed_tickets", 0)
            val unprocessedTickets = prefs.getInt("unprocessed_tickets", 0)
            val inProgressTickets = prefs.getInt("in_progress_tickets", 0)
            val isUserAuthed = prefs.getBoolean("is_user_authed", false)

            val intervalSec = SettingsActivity.getRefreshInterval(context).toLong()

            // Проверяем авторизацию через cookies если статистика пустая
            if (!isUserAuthed && totalTickets == 0) {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://4pda.to") ?: ""
                val isAuth = cookies.contains("member_id=") &&
                        cookies.contains("pass_hash=") &&
                        !cookies.contains("member_id=0")

                if (isAuth) {
                    prefs.edit().putBoolean("is_user_authed", true).apply()
                    Log.d(TAG, "✅ Обнаружена авторизация через cookies")
                }
            }

            val notification = createOngoingNotification(
                context = context,
                totalTickets = totalTickets,
                processedTickets = processedTickets,
                unprocessedTickets = unprocessedTickets,
                inProgressTickets = inProgressTickets,
                isUserAuthed = prefs.getBoolean("is_user_authed", false), // Перечитываем после возможного обновления
                intervalSec = intervalSec
            )

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)

            Log.d(
                TAG,
                "✅ Служебное уведомление обновлено: $totalTickets тикетов, интервал ${intervalSec}с, авторизован=${prefs.getBoolean("is_user_authed", false)}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обновления уведомления: ${e.message}")
        }
    }

    fun showInitialServiceNotification(context: Context) {
        try {
            // Гарантируем наличие каналов перед любым notify()
            NotificationChannels.ensure(context)

            val intervalSec = SettingsActivity.getRefreshInterval(context).toLong()

            // Проверяем авторизацию через cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://4pda.to") ?: ""
            val isAuth = cookies.contains("member_id=") &&
                    cookies.contains("pass_hash=") &&
                    !cookies.contains("member_id=0")

            val notification = createInitialNotification(
                context = context,
                isUserAuthed = isAuth,
                intervalSec = intervalSec
            )

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)

            Log.d(
                TAG,
                "✅ Начальное служебное уведомление показано, интервал ${intervalSec}с, авторизован=$isAuth"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка показа начального уведомления: ${e.message}")
        }
    }

    private fun createOngoingNotification(
        context: Context,
        totalTickets: Int,
        processedTickets: Int,
        unprocessedTickets: Int,
        inProgressTickets: Int,
        isUserAuthed: Boolean,
        intervalSec: Long
    ) = NotificationCompat.Builder(context, NotificationChannels.SERVICE_CHANNEL_ID)
        .setContentTitle("Мониторинг тикетов")
        .setContentText(if (isUserAuthed) "Мониторинг активен" else "Войдите в свой аккаунт")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                if (isUserAuthed) {
                    "Мониторинг активен\nВсего: $totalTickets\nОбработанные/необработанные: $processedTickets/$unprocessedTickets\nВ работе: $inProgressTickets\nИнтервал: ${formatInterval(intervalSec)}"
                } else {
                    "Войдите в свой аккаунт"
                }
            )
        )
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentIntent(createTicketPendingIntent(context))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)  // Постоянное уведомление
        .setAutoCancel(false)
        .setSilent(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .build()

    private fun createInitialNotification(
        context: Context,
        isUserAuthed: Boolean,
        intervalSec: Long
    ) = NotificationCompat.Builder(context, NotificationChannels.SERVICE_CHANNEL_ID)
        .setContentTitle("Мониторинг тикетов")
        .setContentText(if (isUserAuthed) "Мониторинг запущен" else "Войдите в свой аккаунт")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                if (isUserAuthed) {
                    "Мониторинг запущен\nЗагрузка статистики...\nИнтервал: ${formatInterval(intervalSec)}"
                } else {
                    "Войдите в свой аккаунт"
                }
            )
        )
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentIntent(createTicketPendingIntent(context))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)  // Постоянное уведомление
        .setAutoCancel(false)
        .setSilent(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .build()

    private fun createTicketPendingIntent(context: Context): android.app.PendingIntent {
        val ticketUrl = "https://4pda.to/forum/index.php?act=ticket"
        val intent = IntentDebugger.createRobustFourpdaIntent(context, ticketUrl)

        return android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                        android.app.PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    private fun formatInterval(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}с"
            seconds < 3600 -> "${seconds / 60}мин"
            else -> "${seconds / 3600}ч ${(seconds % 3600) / 60}мин"
        }
    }

    fun hideServiceNotification(context: Context) {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            Log.d(TAG, "✅ Служебное уведомление скрыто")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка скрытия уведомления: ${e.message}")
        }
    }
}
