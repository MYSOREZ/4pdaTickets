package ru.fourpda.tickets

import android.content.Context
import android.util.Log

/**
 * Простая защита от дублей уведомлений по тикетам.
 * Хранит ключи уже показанных тикетов и подавляет повторные уведомления
 * до тех пор, пока тикет не изменился (по updatedAt) или не был обработан.
 */
object TicketNotifyDeduper {
    private const val TAG = "TicketDeduper"
    private const val PREFS = "ticket_notify_dedup"
    private const val KEY_SEEN = "seen_keys"

    /**
     * Сгенерировать стабильный ключ тикета: id + версия содержимого
     */
    fun key(ticket: TicketInfo): String {
        val base = if (ticket.id.isNotEmpty()) ticket.id else ticket.url
        val version = (ticket.title + "|" + ticket.status + "|" + ticket.lastUpdate).hashCode()
        return "$base:$version"
    }

    /**
     * Вернуть только новые (ещё не показанные) тикеты
     */
    fun filterNew(context: Context, list: List<TicketInfo>): List<TicketInfo> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        return list.filter { !seen.contains(key(it)) }
    }

    /**
     * Пометить список тикетов как показанные
     */
    fun markShown(context: Context, list: List<TicketInfo>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()).toMutableSet()
        var changed = false
        list.forEach {
            if (set.add(key(it))) changed = true
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_SEEN, set).apply()
            Log.d(TAG, "Помечено показанными: ${list.size}")
        }
        // Лёгкая защита от разрастания
        if (set.size > 1000) {
            val trimmed = set.toList().takeLast(500).toSet()
            prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
        }
    }

    /**
     * Сбросить антидубликат (для отладки/после обработки)
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_SEEN).apply()
    }
}

// Минимальная модель тикета для дедупликации
data class TicketInfo(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val status: String = "",
    val lastUpdate: String = ""
)
