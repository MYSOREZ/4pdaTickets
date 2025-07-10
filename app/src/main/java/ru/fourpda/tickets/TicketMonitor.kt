package ru.fourpda.tickets

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import java.util.*

class TicketMonitor(
    private val context: Context,
    private val webView: WebView,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "TicketMonitor"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val shownNotificationIds = mutableSetOf<String>()
    private val queue: Deque<Ticket> = ArrayDeque()
    private var queueBusy = false
    private var isMonitoringActive = false

    data class Ticket(
        val id: String,
        val title: String,
        val section: String,
        val date: String
    )

    init {
        webView.addJavascriptInterface(JsBridge(), "TicketJS")
        Log.d(TAG, "📱 TicketMonitor инициализирован с JavaScript интерфейсом")
    }

    fun startMonitoring() {
        Log.d(TAG, "🚀 Запуск мониторинга тикетов")
        isMonitoringActive = true
    }

    fun stopMonitoring() {
        Log.d(TAG, "⏹️ Остановка мониторинга")
        isMonitoringActive = false
    }

    fun searchNow() {
        Log.d(TAG, "🔍 Поиск тикетов по запросу")
        if (isMonitoringActive) {
            executeJavaScript()
        }
    }

    fun clearHistory() {
        Log.d(TAG, "🧹 Очистка истории уведомлений")
        shownNotificationIds.clear()
        queue.clear()
        queueBusy = false
    }

    private fun sendTicketStats(totalCount: Int, processedCount: Int, unprocessedCount: Int, inProgressCount: Int) {
        val intent = Intent("ru.fourpda.tickets.TICKET_STATS").apply {
            putExtra("total", totalCount)
            putExtra("processed", processedCount)
            putExtra("unprocessed", unprocessedCount)
            putExtra("in_progress", inProgressCount)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "📊 Отправлена статистика: всего=$totalCount, обработанных=$processedCount, необработанных=$unprocessedCount, в работе=$inProgressCount")
    }

    private fun executeJavaScript() {
        Log.d(TAG, "Внедрение JavaScript для поиска тикетов")

        val js = """
            (function () {
              try {
                const tickets = [];
                let processedCount = 0;
                let unprocessedCount = 0;
                let inProgressCount = 0;
                let totalCount = 0;
                let debugInfo = 'DEBUG [' + new Date().toLocaleTimeString() + ']: Анализ страницы тикетов 4PDA: ';
                
                const allRows = document.querySelectorAll('div.t-row[id^="t-row-"]');
                totalCount = allRows.length;
                debugInfo += 'Найдено строк тикетов: ' + totalCount + '. ';
                
                allRows.forEach(function(row, index) {
                  const rowClasses = row.className;
                  const rowStatusMatch = rowClasses.match(/row-status-(\d+)/);
                  const rowStatus = rowStatusMatch ? rowStatusMatch[1] : 'unknown';
                  
                  const statusCell = row.querySelector('.t-status');
                  const cellClasses = statusCell ? statusCell.className : '';
                  const cellStatusMatch = cellClasses.match(/status-(\d+)/);
                  const cellStatus = cellStatusMatch ? cellStatusMatch[1] : 'unknown';
                  
                  debugInfo += 'Тикет ' + index + ': row-status-' + rowStatus + ', cell-status-' + cellStatus + '; ';
                  
                  const isStatus0 = (rowStatus === '0' || cellStatus === '0' || 
                                    row.classList.contains('row-status-0') || 
                                    (statusCell && statusCell.classList.contains('status-0')));
                  
                  const isStatus1 = (rowStatus === '1' || cellStatus === '1' || 
                                    row.classList.contains('row-status-1') || 
                                    (statusCell && statusCell.classList.contains('status-1')));

                  const isStatus2 = (rowStatus === '2' || cellStatus === '2' || 
                                    row.classList.contains('row-status-2') || 
                                    (statusCell && statusCell.classList.contains('status-2')));
                  
                  if (isStatus0) {
                    unprocessedCount++;
                    debugInfo += '🔥 НАЙДЕН СТАТУС-0! ';
                    
                    const id = row.getAttribute('t_id') || row.id.replace('t-row-', '');
                    const titleEl = row.querySelector('.t-title a');
                    const title = titleEl ? titleEl.textContent.trim() : 'Без названия';
                    const sectionEl = row.querySelector('.t-description a');
                    const section = sectionEl ? sectionEl.textContent.trim() : 'Неизвестный раздел';
                    const dateEl = row.querySelector('.t-date a');
                    const date = dateEl ? dateEl.textContent.trim() : '';
                    
                    tickets.push({id: id, title: title, section: section, date: date});
                    debugInfo += 'Добавлен: ' + title + '; ';
                  } else if (isStatus1) {
                    inProgressCount++;
                    debugInfo += '⚠️ НАЙДЕН СТАТУС-1! ';
                  } else if (isStatus2) {
                    processedCount++;
                    debugInfo += '✅ НАЙДЕН СТАТУС-2! ';
                  }
                });
                
                debugInfo += 'СТАТИСТИКА СТАТУСОВ: всего=' + totalCount + ', обработанных=' + processedCount + ', необработанных=' + unprocessedCount + ', в работе=' + inProgressCount + '. ';
                
                console.log(debugInfo);
                TicketJS.logDebug(debugInfo);
                
                TicketJS.onFullStats(totalCount, processedCount, unprocessedCount, inProgressCount);
                
                if (tickets.length) {
                  TicketJS.onTickets(JSON.stringify(tickets));
                } else {
                  TicketJS.onNoTickets();
                }
                
              } catch (e) {
                TicketJS.err('Ошибка в JavaScript: ' + e.message);
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onTickets(j: String) {
            Log.d(TAG, "🎯 JavaScript нашел тикеты со статусом 0: $j")
            val arr = org.json.JSONArray(j)
            var newTicketsCount = 0
            val totalTicketsFound = arr.length()

            repeat(arr.length()) { i ->
                arr.getJSONObject(i).apply {
                    val ticket = Ticket(
                        getString("id"),
                        getString("title"),
                        getString("section"),
                        getString("date")
                    )

                    if (ticket.id !in shownNotificationIds) {
                        shownNotificationIds.add(ticket.id)
                        queue.add(ticket)
                        newTicketsCount++
                    }
                }
            }

            if (!queueBusy && queue.isNotEmpty()) processQueue()
            onStatusUpdate("Активно тикетов: $totalTicketsFound")
        }

        @JavascriptInterface
        fun onNoTickets() {
            Log.d(TAG, "JavaScript: Тикетов со статусом 0 пока нет")
            onStatusUpdate("Активно тикетов: 0")
        }

        @JavascriptInterface
        fun onFullStats(totalCount: Int, processedCount: Int, unprocessedCount: Int, inProgressCount: Int) {
            Log.d(TAG, "📊 Получена полная статистика: всего=$totalCount, обработанных=$processedCount, необработанных=$unprocessedCount, в работе=$inProgressCount")
            sendTicketStats(totalCount, processedCount, unprocessedCount, inProgressCount)
        }

        @JavascriptInterface
        fun logDebug(debugInfo: String) {
            Log.d(TAG, debugInfo)
        }

        @JavascriptInterface
        fun err(m: String) = Log.e(TAG, "JS-error: $m")
    }

    private fun processQueue() {
        if (queue.isEmpty()) {
            queueBusy = false
            return
        }
        queueBusy = true
        showNotification(queue.removeFirst())
        handler.postDelayed({ processQueue() }, 1500)
    }

    private fun createTicketIntent(ticketUrl: String): Intent {
        // Сначала пытаемся открыть в приложении 4PDA
        val fourpdaIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ticketUrl)).apply {
            setPackage("ru.fourpda.client")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Проверяем, может ли система обработать этот intent
        return if (context.packageManager.resolveActivity(fourpdaIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            Log.d(TAG, "✅ Открываем в приложении 4PDA")
            fourpdaIntent
        } else {
            Log.d(TAG, "❌ Приложение 4PDA не найдено, создаем обычный intent")
            Intent(Intent.ACTION_VIEW, Uri.parse(ticketUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }

    private fun showNotification(ticket: Ticket) {
        Log.d(TAG, "📱 Показываем уведомление: ${ticket.title}")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ticketUrl = "https://4pda.to/forum/index.php?act=ticket&s=thread&t_id=${ticket.id}"
        val intent = createTicketIntent(ticketUrl)

        val pendingIntent = PendingIntent.getActivity(
            context,
            ticket.id.hashCode(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(context, ForegroundMonitorService.CHANNEL_TICKETS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("🎫 Новый тикет: ${ticket.title}")
            .setContentText("${ticket.section} • ${ticket.date}")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Тикет: ${ticket.title}\nРаздел: ${ticket.section}\nВремя: ${ticket.date}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(ticket.id.hashCode(), notification)
        Log.d(TAG, "✅ Уведомление отправлено с ссылкой: $ticketUrl")
    }
}
