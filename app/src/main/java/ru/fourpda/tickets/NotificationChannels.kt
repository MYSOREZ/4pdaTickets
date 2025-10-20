package ru.fourpda.tickets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val SERVICE_CHANNEL_ID = "service_channel"
    const val TICKETS_CHANNEL_ID = "tickets_channel"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Служебный канал: низкая важность, без звука/вибрации
        if (nm.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "Мониторинг тикетов",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Служебные уведомления о состоянии мониторинга"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        // Канал «новые тикеты»: обычная важность
        if (nm.getNotificationChannel(TICKETS_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    TICKETS_CHANNEL_ID,
                    "Новые тикеты",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Уведомления о новых тикетах 4PDA"
                }
            )
        }
    }
}
