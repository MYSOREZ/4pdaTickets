package ru.fourpda.tickets

import android.content.Context
import android.util.Log

/**
 * Простая защита от дублей уведомлений по тикетам.
 * Хранит ключи уже показанных тикетов и подавляет повторные уведомления
 * до тех пор, пока тикет не изменился (по updatedAt) или не был обработан.
 * 
 * ОСОБЕННОСТЬ: при каждом новом запуске приложения показывает все активные тикеты,
 * чтобы пользователь не пропустил старые невыполненные задачи.
 */
object TicketNotifyDeduper {
    private const val TAG = "TicketDeduper"
    private const val PREFS = "ticket_notify_dedup"
    private const val KEY_SEEN = "seen_keys"
    private const val KEY_SESSION_ID = "app_session_id"
    
    private var currentSessionId: Long = 0

    /**
     * Сгенерировать стабильный ключ тикета: id + версия содержимого
     */
    fun key(ticket: TicketInfo): String {
        val base = if (ticket.id.isNotEmpty()) ticket.id else ticket.url
        val version = (ticket.title + "|" + ticket.status + "|" + ticket.lastUpdate).hashCode()
        return "$base:$version"
    }

    /**
     * Сброс дедупликации при первом запуске приложения.
     * Вызывается один раз при старте приложения, чтобы показать все активные тикеты.
     */
    fun clearOnFirstLaunch(context: Context) {
        if (currentSessionId == 0L) {
            currentSessionId = System.currentTimeMillis()
        }
        
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSessionId = prefs.getLong(KEY_SESSION_ID, 0L)
        
        if (lastSessionId != currentSessionId) {
            // Новая сессия - сбрасываем показанные тикеты
            prefs.edit()
                .remove(KEY_SEEN)
                .putLong(KEY_SESSION_ID, currentSessionId)
                .apply()
            Log.d(TAG, "🔄 Новая сессия приложения: сброс показанных тикетов для повторного отображения")
        }
    }

    /**
     * Вернуть только новые (ещё не показанные в текущей сессии) тикеты
     */
    fun filterNew(context: Context, list: List<TicketInfo>): List<TicketInfo> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        val filtered = list.filter { !seen.contains(key(it)) }
        
        if (filtered.size < list.size) {
            Log.d(TAG, "📋 Отфильтровано дублей: ${list.size - filtered.size} из ${list.size}")
        }
        
        return filtered
    }

    /**
     * Пометить список тикетов как показанные в текущей сессии
     */
    fun markShown(context: Context, list: List<TicketInfo>) {
        if (list.isEmpty()) return
        
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()).toMutableSet()
        var changed = false
        
        list.forEach {
            if (set.add(key(it))) changed = true
        }
        
        if (changed) {
            prefs.edit().putStringSet(KEY_SEEN, set).apply()
            Log.d(TAG, "✅ Помечено показанными: ${list.size} тикетов")
        }
        
        // Лёгкая защита от разрастания
        if (set.size > 1000) {
            val trimmed = set.toList().takeLast(500).toSet()
            prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
            Log.d(TAG, "🧹 Очищен старый кеш: оставлено 500 из ${set.size} записей")
        }
    }

    /**
     * Полный сброс антидубликата (для отладки/ручной очистки)
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SEEN)
            .remove(KEY_SESSION_ID)
            .apply()
        currentSessionId = 0L
        Log.d(TAG, "🗑️ Полная очистка дедупликатора")
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