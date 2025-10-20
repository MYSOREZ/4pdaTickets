package ru.fourpda.tickets

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.util.ArrayDeque
import java.util.Deque

class TicketMonitor(
    private val context: Context,
    private val webView: WebView,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "TicketMonitor"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue: Deque<Ticket> = ArrayDeque()
    private var queueBusy = false
    private var isMonitoringActive = false

    data class Ticket(
        val id: String,
        val title: String,
        val section: String,
        val date: String,
        val topic: String,
        val description: String
    )

    init {
        webView.addJavascriptInterface(JsBridge(), "TicketJS")
        Log.d(TAG, "📱 TicketMonitor инициализирован с JavaScript интерфейсом")

        // Добавляем отладочную проверку JavaScript интерфейса
        Handler(Looper.getMainLooper()).postDelayed({
            webView.evaluateJavascript("typeof TicketJS") { result ->
                Log.d(TAG, "🔧 Проверка TicketJS: $result")
            }
        }, 1000)
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
        queue.clear()
        queueBusy = false
        // Очищаем также дедуплицированные записи
        TicketNotifyDeduper.clear(context)
    }

    // Публичные методы для вызова из QuickCheckWorker при необходимости
    fun handleTickets(jsonStr: String) {
        val jsBridge = JsBridge()
        jsBridge.onTickets(jsonStr)
    }

    fun handleNoTickets() {
        val jsBridge = JsBridge()
        jsBridge.onNoTickets()
    }

    fun handleFullStats(total: Int, processed: Int, unprocessed: Int, inProgress: Int) {
        val jsBridge = JsBridge()
        jsBridge.onFullStats(total, processed, unprocessed, inProgress)
    }

    fun handleLogDebug(msg: String) {
        val jsBridge = JsBridge()
        jsBridge.logDebug(msg)
    }

    fun handleError(msg: String) {
        val jsBridge = JsBridge()
        jsBridge.err(msg)
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

        // ВНИМАНИЕ: экранирование в RegExp приведено в соответствие, чтобы не вызывать SyntaxError
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
                
                // Сначала кликаем на все тикеты со статусом 0, чтобы развернуть их
                const status0Rows = [];
                allRows.forEach(function(row) {
                  const rowClasses = row.className;
                  const rowStatusMatch = rowClasses.match(/row-status-(\d+)/);
                  const rowStatus = rowStatusMatch ? rowStatusMatch[1] : 'unknown';
                  
                  const statusCell = row.querySelector('.t-status');
                  const cellClasses = statusCell ? statusCell.className : '';
                  const cellStatusMatch = cellClasses.match(/status-(\d+)/);
                  const cellStatus = cellStatusMatch ? cellStatusMatch[1] : 'unknown';
                  
                  const isStatus0 = (rowStatus === '0' || cellStatus === '0' || 
                                    row.classList.contains('row-status-0') || 
                                    (statusCell && statusCell.classList.contains('status-0')));
                  
                  if (isStatus0) {
                    status0Rows.push(row);
                    // Кликаем на тикет, чтобы развернуть его
                    const titleEl = row.querySelector('.t-title');
                    if (titleEl) {
                      titleEl.click();
                      debugInfo += 'Кликнули на тикет для разворачивания. ';
                    }
                  }
                });
                
                // Ждем немного, чтобы контент загрузился
                setTimeout(function() {
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
                      
                      // Извлекаем тему (topic) и описание
                      let topic = '';
                      let description = '';
                      
                      // Попробуем найти развернутое содержимое
                      let contentEl = document.querySelector('#t-row-content-' + id);
                      if (!contentEl) {
                        // Если не нашли по ID, ищем следующий элемент
                        const nextRow = row.nextElementSibling;
                        if (nextRow && nextRow.classList.contains('t-row-content')) {
                          contentEl = nextRow;
                        }
                      }
                      
                      if (contentEl) {
                        // Исправлено: корректное экранирование слэшей в RegExp
                        const topicMatch = contentEl.innerHTML.match(/Тема:<\/?strong>\s*<a[^>]+>([^<]+)<\/a>/);
                        if (topicMatch) {
                          topic = topicMatch[1].trim();
                        } else {
                          const topicLink = contentEl.querySelector('a[href*="findpost"]');
                          if (topicLink) {
                            topic = topicLink.textContent.trim();
                          }
                        }
                        
                        // Извлекаем описание из последнего элемента td-message
                        const tdMessageElements = contentEl.querySelectorAll('.td-message');
                        if (tdMessageElements.length > 0) {
                          const lastTdMessage = tdMessageElements[tdMessageElements.length - 1];
                          
                          // --- Улучшенная логика извлечения текста v3 ---
                          let messageHTML = lastTdMessage.innerHTML;

                          // Аккуратно удаляем блок с IP и QMS, где бы он ни был
                          messageHTML = messageHTML.replace(/IP:[\s\S]*?QMS/g, '');

                          // Теперь обрабатываем очищенный HTML
                          messageHTML = messageHTML.replace(/<br\s*\/?>(?=\s*\n?)/gi, '\n');
                          messageHTML = messageHTML.replace(/<\/(p|div)>/gi, '\n');
                          let descText = messageHTML.replace(/<[^>]+>/g, '').trim();

                          // Финальная очистка пробелов
                          descText = descText.replace(/(\s*\n\s*)+/g, '\n').trim();
                          // --- Конец улучшенной логики ---

                          // Ограничиваем длину описания
                          if (descText.length > 100) {
                            description = descText.substring(0, 100) + '...';
                          } else {
                            description = descText;
                          }
                        }
                      }
                      
                      // Если тема не найдена, используем заголовок тикета как резерв
                      if (!topic) {
                        topic = 'Тема не определена';
                      }
                      
                      tickets.push({id: id, title: title, section: section, date: date, topic: topic, description: description});
                      debugInfo += 'Добавлен: ' + title + ' (Тема: ' + topic + '); ';
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
                  
                }, 1000); // Ждем секунду после кликов
                
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
            FileLogger.d(TAG, "🎯 JavaScript нашел тикеты со статусом 0: $j")
            val arr = org.json.JSONArray(j)
            val totalTicketsFound = arr.length()

            // Создаем список всех найденных тикетов и соответствующих TicketInfo
            val allTickets = mutableListOf<Ticket>()
            val allTicketInfos = mutableListOf<TicketInfo>()

            repeat(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val ticket = Ticket(
                    obj.getString("id"),
                    obj.getString("title"),
                    obj.getString("section"),
                    obj.getString("date"),
                    obj.getString("topic"),
                    obj.getString("description")
                )
                allTickets.add(ticket)
                
                // Создаем TicketInfo для дедупликации
                allTicketInfos.add(
                    TicketInfo(
                        id = ticket.id,
                        title = ticket.title,
                        url = "https://4pda.to/forum/index.php?act=ticket&s=thread&t_id=${ticket.id}",
                        status = "${ticket.topic}|${ticket.description}", // Используем topic+description как статус для отслеживания изменений
                        lastUpdate = ticket.date
                    )
                )
            }

            // Фильтруем только новые (еще не показанные) тикеты
            val newTicketInfos = TicketNotifyDeduper.filterNew(context, allTicketInfos)
            
            if (newTicketInfos.isEmpty()) {
                Log.d(TAG, "⚠️ Все тикеты уже были показаны ранее, пропускаем дубли: $totalTicketsFound")
                onStatusUpdate("Активно тикетов: $totalTicketsFound (дубли пропущены)")
                return
            }

            // Находим соответствующие тикеты для показа
            val newTicketIds = newTicketInfos.map { it.id }.toSet()
            val ticketsToShow = allTickets.filter { it.id in newTicketIds }

            Log.d(TAG, "✅ Найдено новых тикетов для показа: ${ticketsToShow.size} из $totalTicketsFound")

            // Добавляем в очередь для показа
            queue.addAll(ticketsToShow)
            if (!queueBusy && queue.isNotEmpty()) {
                processQueue()
            }

            // Помечаем как показанные ПОСЛЕ успешной постановки в очередь
            TicketNotifyDeduper.markShown(context, newTicketInfos)

            onStatusUpdate("Активно тикетов: $totalTicketsFound (новых: ${ticketsToShow.size})")
        }

        @JavascriptInterface
        fun onNoTickets() {
            Log.d(TAG, "JavaScript: Тикетов со статусом 0 пока нет")
            FileLogger.d(TAG, "JavaScript: Тикетов со статусом 0 пока нет")
            onStatusUpdate("Активно тикетов: 0")
        }

        @JavascriptInterface
        fun onFullStats(totalCount: Int, processedCount: Int, unprocessedCount: Int, inProgressCount: Int) {
            Log.d(TAG, "📊 Получена полная статистика: всего=$totalCount, обработанных=$processedCount, необработанных=$unprocessedCount, в работе=$inProgressCount")
            sendTicketStats(totalCount, processedCount, unprocessedCount, inProgressCount)

            // Передаем статистику в правильном формате для QuickCheckWorker
            val statusMessage = "Мониторинг: всего=$totalCount, обработано=$processedCount, новые=$unprocessedCount, в работе=$inProgressCount"
            onStatusUpdate(statusMessage)
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
        Log.d(TAG, "🔗 Создаем интент для уведомления о тикете: $ticketUrl")
        // Используем робастный метод создания интента с поддержкой HyperOS/MIUI
        return IntentDebugger.createRobustFourpdaIntent(context, ticketUrl)
    }

    private fun showNotification(ticket: Ticket) {
        Log.d(TAG, "📱 Показываем уведомление в стиле 'билет в кружке':")
        Log.d(TAG, "  - ID: ${ticket.id}, Section: ${ticket.section}, Topic: ${ticket.topic}")

        // Гарантируем наличие каналов (особенно важно на Android 13 после чистой установки)
        NotificationChannels.ensure(context)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ticketUrl = "https://4pda.to/forum/index.php?act=ticket&s=thread&t_id=${ticket.id}"
        val intent = createTicketIntent(ticketUrl)

        // Создаем TicketInfo для генерации стабильного ключа уведомления
        val ticketInfo = TicketInfo(
            id = ticket.id,
            title = ticket.title,
            url = ticketUrl,
            status = "${ticket.topic}|${ticket.description}", // Используем topic+description как статус
            lastUpdate = ticket.date
        )
        
        // Используем стабильный ключ для ID уведомления
        val notificationId = TicketNotifyDeduper.key(ticketInfo).hashCode()

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId, // Используем тот же ID для PendingIntent
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        // 1. Создаем участников диалога
        val user = Person.Builder()
            .setName("Вы")
            .setKey("user")
            .build()

        // "Собеседник" - раздел форума. Имя начинается с эмодзи, чтобы он попал в аватар.
        val chatPartner = Person.Builder()
            .setName("🎫 ${ticket.section}")
            .setKey(ticket.section)
            .build()

        // 2. Готовим данные для сообщения
        val ticketTimestamp = DateUtils.parseTicketDateToTimestamp(ticket.date)
        val formattedDate = DateUtils.formatTicketDate(ticket.date)
        val messageText = "Тема: ${ticket.topic}\nТикет:\n${ticket.description}\n\n$formattedDate"

        // 3. Создаем сообщение от "собеседника"
        val message = NotificationCompat.MessagingStyle.Message(
            messageText,
            ticketTimestamp,
            chatPartner
        )

        // 4. Создаем стиль 1-на-1 диалога
        val messagingStyle = NotificationCompat.MessagingStyle(user).addMessage(message)

        // 5. Собираем уведомление
        val notification = NotificationCompat.Builder(context, NotificationChannels.TICKETS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(notificationId, notification)
        Log.d(TAG, "✅ Уведомление в стиле 'билет в кружке' отправлено с ID: $notificationId")
    }
}