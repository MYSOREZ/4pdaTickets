package ru.fourpda.tickets

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ru.fourpda.tickets.databinding.ActivityTicketListBinding
import org.json.JSONArray

class TicketListActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TicketListActivity"
        private const val TICKETS_URL = "https://4pda.to/forum/index.php?act=ticket"
    }

    private lateinit var ui: ActivityTicketListBinding
    private lateinit var adapter: TicketAdapter
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Статистика
    private var totalTickets = 0
    private var processedTickets = 0
    private var unprocessedTickets = 0
    private var inProgressTickets = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            supportActionBar?.hide()
            
            ui = ActivityTicketListBinding.inflate(layoutInflater)
            setContentView(ui.root)

            Log.d(TAG, "=== Ticket List Activity Created ===")

            initUI()
            initWebView()
            loadTickets()
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка в onCreate: ${e.message}", e)
            // Пытаемся показать ошибку пользователю, если возможно
            try {
                Toast.makeText(this, "Ошибка инициализации приложения: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {
                Log.e(TAG, "Не удалось показать Toast: ${ex.message}")
            }
            // Завершаем активность с ошибкой
            finish()
        }
    }

    private fun initUI() {
        // Настройка RecyclerView
        adapter = TicketAdapter { ticket ->
            openTicket(ticket)
        }
        
        ui.recyclerTickets.apply {
            layoutManager = LinearLayoutManager(this@TicketListActivity)
            adapter = this@TicketListActivity.adapter
        }

        // Кнопка назад
        ui.btnBack.setOnClickListener {
            finish()
        }

        // Кнопка обновить
        ui.btnRefreshTickets.setOnClickListener {
            loadTickets()
        }
    }

    private fun initWebView() {
        try {
            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                // Устанавливаем правильный Accept-Language для корректного отображения времени
                val extraHeaders = HashMap<String, String>()
                extraHeaders["Accept-Language"] = "ru-RU,ru;q=0.9,en;q=0.8"
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                // Очистка конфликтующих куков
                cookieManager.flush()

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        try {
                            Log.d(TAG, "Страница загружена: $url")
                            if (url?.contains("act=ticket") == true) {
                                // Страница с тикетами загружена, извлекаем данные
                                this@TicketListActivity.handler.postDelayed({
                                    if (!this@TicketListActivity.isFinishing && !this@TicketListActivity.isDestroyed) {
                                        this@TicketListActivity.extractTickets()
                                        // Additional logs for debugging
                                        Log.d(TAG, "Extracting tickets after delay.")
                                    }
                                }, 2000)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка в onPageFinished: ${e.message}", e)
                            this@TicketListActivity.runOnUiThread {
                                showMessage("Ошибка загрузки страницы")
                                showLoading(false)
                            }
                        }
                    }
                    
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        Log.e(TAG, "WebView ошибка: $description на $failingUrl")
                        this@TicketListActivity.runOnUiThread {
                            showMessage("Ошибка загрузки страницы: $description")
                            showLoading(false)
                        }
                    }
                }
                
                addJavascriptInterface(JsBridge(), "TicketListJS")
                
                // Set WebView dimensions to ensure proper viewport
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации WebView: ${e.message}")
            showMessage("Ошибка инициализации WebView")
            showLoading(false)
        }
    }

    private fun loadTickets() {
        showLoading(true)
        // Загружаем с дополнительными заголовками для правильной локализации
        val extraHeaders = HashMap<String, String>()
        extraHeaders["Accept-Language"] = "ru-RU,ru;q=0.9,en;q=0.8"
        webView?.loadUrl(TICKETS_URL, extraHeaders)
    }

    private fun extractTickets() {
        val js = """
            (function () {
              try {
                const tickets = [];
                let processedCount = 0;
                let unprocessedCount = 0;
                let inProgressCount = 0;
                let totalCount = 0;
                
                // Функция для ожидания
                function delay(ms) {
                    return new Promise(resolve => setTimeout(resolve, ms));
                }
                
                // Проверяем, какая версия сайта загружена
                console.log('URL:', window.location.href);
                console.log('User-Agent:', navigator.userAgent);
                console.log('Viewport width:', window.innerWidth);
                
                // Проверяем наличие элементов десктопной версии
                const hasDesktopElements = document.querySelector('.ticket-description-topic-title') !== null;
                console.log('Десктопные элементы найдены:', hasDesktopElements);
                
                // Ищем все строки тикетов
                // Исключаем заголовки таблицы (row-description) и содержимое тикетов (t-row-content)
                let allRows = document.querySelectorAll('div.t-row:not(.row-description):not(.t-row-content)');
                
                // Фильтруем только настоящие тикеты с id
                allRows = Array.from(allRows).filter(row => row.getAttribute('t_id') || row.id.includes('t-row-'));
                
                totalCount = allRows.length;
                
                if (totalCount === 0) {
                    TicketListJS.onError('Тикеты не найдены на странице. Возможно, у вас нет тикетов.');
                    return;
                }
                
                // Сначала кликаем на все тикеты, чтобы развернуть их содержимое
                console.log('Разворачиваем все тикеты...');
                for (let i = 0; i < allRows.length; i++) {
                    const row = allRows[i];
                    const titleEl = row.querySelector('.t-title');
                    if (titleEl) {
                        titleEl.click();
                        console.log('Развернули тикет #' + (i + 1));
                    }
                }
                
                // Ждем немного, чтобы контент загрузился
                setTimeout(function() {
                    console.log('Начинаем парсинг развернутых тикетов...');
                    
                allRows.forEach(function(row) {
                  try {
                    // Извлекаем ID тикета
                    const id = row.getAttribute('t_id') || row.id.replace('t-row-', '');
                    
                    if (!id) {
                        console.log('Пропускаем строку без ID:', row);
                        return;
                    }
                    
                    // Определяем статус по классам строки
                    const rowClasses = row.className || '';
                    let finalStatus = -1;
                    
                    if (rowClasses.includes('row-status-0')) {
                        finalStatus = 0; // Новый
                    } else if (rowClasses.includes('row-status-1')) {
                        finalStatus = 1; // В работе  
                    } else if (rowClasses.includes('row-status-2')) {
                        finalStatus = 2; // Обработан
                    }
                    
                    // Если не нашли статус в классах строки, ищем в ячейке статуса
                    if (finalStatus === -1) {
                        const statusCell = row.querySelector('.t-status');
                        if (statusCell) {
                            const statusClasses = statusCell.className;
                            if (statusClasses.includes('status-0')) finalStatus = 0;
                            else if (statusClasses.includes('status-1')) finalStatus = 1;
                            else if (statusClasses.includes('status-2')) finalStatus = 2;
                        }
                    }
                    
                    // Считаем статистику
                    if (finalStatus === 0) unprocessedCount++;
                    else if (finalStatus === 1) inProgressCount++;
                    else if (finalStatus === 2) processedCount++;
                    
                    // Извлекаем заголовок
                    const titleEl = row.querySelector('.t-title a');
                    const title = titleEl ? titleEl.getAttribute('title') || titleEl.textContent.trim() : 'Без названия';
                    
                    // Извлекаем раздел  
                    const sectionEl = row.querySelector('.t-description a');
                    const section = sectionEl ? sectionEl.getAttribute('title') || sectionEl.textContent.trim() : 'Неизвестный раздел';
                    
                    // Извлекаем дату
                    const dateEl = row.querySelector('.t-date a');
                    const date = dateEl ? dateEl.textContent.trim() : '';
                    
                    // Извлекаем ответственного модератора
                    const modEl = row.querySelector('.t-mod a');
                    const moderator = modEl ? modEl.textContent.trim() : '';
                    
                    // Извлекаем тему тикета и отправителя
                    // Тема и отправитель находятся в следующем элементе после строки тикета
                    let topic = '';
                    let sender = '';
                    let postAuthor = '';
                    
                    // Сначала попробуем найти развернутое содержимое тикета
                    let contentEl = document.querySelector('#t-row-content-' + id);
                    if (!contentEl) {
                        // Если не нашли по ID, ищем следующий элемент
                        const nextRow = row.nextElementSibling;
                        if (nextRow && nextRow.classList.contains('t-row-content')) {
                            contentEl = nextRow;
                        }
                    }
                    
                    if (contentEl) {
                        console.log('Нашли контент для тикета', id);
                        const contentHTML = contentEl.innerHTML;
                        console.log('HTML содержимое (первые 500 символов):', contentHTML.substring(0, 500));
                        
                        // Извлекаем тему различными способами
                        // Способ 1: Ищем ссылку после текста "Тема:"
                        const topicMatch = contentHTML.match(/Тема:<\/strong>\s*<a[^>]+>([^<]+)<\/a>/);
                        if (topicMatch) {
                            topic = topicMatch[1].trim();
                            console.log('Тема найдена через regex:', topic);
                        } else {
                            // Способ 2: Ищем первую ссылку с findpost
                            const topicLink = contentEl.querySelector('a[href*="findpost"]');
                            if (topicLink) {
                                topic = topicLink.textContent.trim();
                                console.log('Тема найдена через querySelector:', topic);
                            }
                        }
                        
                        // Извлекаем автора поста
                        const authorMatch = contentHTML.match(/Автор поста:<\/strong>\s*<a[^>]+>([^<]+)<\/a>/);
                        if (authorMatch) {
                            postAuthor = authorMatch[1].trim();
                            console.log('Автор поста найден через regex:', postAuthor);
                        } else {
                            // Альтернативный способ: второй showuser после темы
                            const userLinks = contentEl.querySelectorAll('a[href*="showuser"]');
                            if (userLinks.length > 0) {
                                // Первая ссылка обычно автор поста
                                postAuthor = userLinks[0].textContent.trim();
                                console.log('Автор поста найден через querySelector:', postAuthor);
                            }
                        }
                        
                        // Извлекаем отправителя тикета (модератор, который его создал)
                        const senderEl = contentEl.querySelector('.td-member a');
                        if (senderEl) {
                            sender = senderEl.textContent.trim();
                            console.log('Отправитель тикета:', sender);
                        }
                        
                        // Если не нашли тему или автора, пробуем альтернативные методы
                        if (!topic || !postAuthor) {
                            const messageEl = contentEl.querySelector('.td-message');
                            if (messageEl) {
                                const messageText = messageEl.textContent;
                                console.log('Текст сообщения:', messageText.substring(0, 200));
                                
                                if (!topic) {
                                    // Ищем тему в тексте
                                    const topicTextMatch = messageText.match(/Тема:\s*([^\n]+)/);
                                    if (topicTextMatch) {
                                        topic = topicTextMatch[1].trim();
                                        console.log('Тема найдена в тексте:', topic);
                                    }
                                }
                                
                                if (!postAuthor) {
                                    // Ищем автора в тексте
                                    const authorTextMatch = messageText.match(/Автор поста:\s*([^\s\n]+)/);
                                    if (authorTextMatch) {
                                        postAuthor = authorTextMatch[1].trim();
                                        console.log('Автор найден в тексте:', postAuthor);
                                    }
                                }
                            }
                        }
                    } else {
                        console.log('Не найден контент для тикета', id);
                        console.log('Попытка найти в текущей строке...');
                        
                        // Для мобильной версии информация может быть прямо в строке
                        const titleEl = row.querySelector('.t-title');
                        if (titleEl) {
                            const titleText = titleEl.textContent;
                            // Иногда в заголовке есть и тема
                            if (titleText.includes(' - ')) {
                                const parts = titleText.split(' - ');
                                if (parts.length > 1) {
                                    topic = parts[0].trim();
                                }
                            }
                        }
                    }
                    
                    tickets.push({
                      id: id,
                      title: title,
                      section: section,
                      date: date,
                      status: finalStatus,
                      moderator: moderator,
                      topic: topic,
                      sender: sender,
                      postAuthor: postAuthor
                    });
                    
                    console.log('Найден тикет:', id, title, 'Статус:', finalStatus);
                  } catch (rowError) {
                    console.error('Ошибка обработки строки:', rowError);
                  }
                });
                
                console.log('Всего найдено тикетов:', totalCount);
                console.log('Новых:', unprocessedCount, 'В работе:', inProgressCount, 'Обработанных:', processedCount);
                
                TicketListJS.onStats(totalCount, processedCount, unprocessedCount, inProgressCount);
                TicketListJS.onTickets(JSON.stringify(tickets));
                
                }, 1000); // Ждем секунду после кликов
                
              } catch (e) {
                console.error('Общая ошибка парсинга:', e);
                TicketListJS.onError('Ошибка: ' + e.message);
              }
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js, null)
    }

    private fun showLoading(show: Boolean) {
        ui.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        ui.recyclerTickets.visibility = if (show) View.GONE else View.VISIBLE
        ui.statsContainer.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            ui.textMessage.visibility = View.GONE
        }
    }

    private fun showMessage(message: String) {
        ui.textMessage.text = message
        ui.textMessage.visibility = View.VISIBLE
        ui.recyclerTickets.visibility = View.GONE
    }

    private fun updateStats() {
        ui.textTotalTickets.text = "Всего: $totalTickets"
        ui.textProcessedTickets.text = "Обработано: $processedTickets"
        ui.textUnprocessedTickets.text = "Новые: $unprocessedTickets"
        ui.textInProgressTickets.text = "В работе: $inProgressTickets"
    }

    private fun openTicket(ticket: TicketData) {
        // Сейчас расширение/сворачивание обрабатывается в адаптере
        Log.d(TAG, "Клик по тикету: ${ticket.title} (ID: ${ticket.id})")
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onTickets(jsonTickets: String) {
            runOnUiThread {
                try {
                    val ticketsArray = JSONArray(jsonTickets)
                    val ticketsList = mutableListOf<TicketData>()
                    
                    for (i in 0 until ticketsArray.length()) {
                        val ticketJson = ticketsArray.getJSONObject(i)
                        val ticket = TicketData(
                            id = ticketJson.getString("id"),
                            title = ticketJson.getString("title"),
                            section = ticketJson.getString("section"),
                            date = ticketJson.getString("date"),
                            status = ticketJson.getInt("status"),
                            moderator = ticketJson.optString("moderator", ""),
                            topic = ticketJson.optString("topic", ""),
                            sender = ticketJson.optString("sender", ""),
                            postAuthor = ticketJson.optString("postAuthor", "")
                        )
                        ticketsList.add(ticket)
                    }
                    
                    adapter.setTickets(ticketsList)
                    showLoading(false)
                    
                    if (ticketsList.isEmpty()) {
                        showMessage("У вас пока нет тикетов")
                    }
                    
                    Log.d(TAG, "Загружено тикетов: ${ticketsList.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка парсинга тикетов: ${e.message}")
                    showMessage("Ошибка загрузки тикетов")
                    showLoading(false)
                }
            }
        }

        @JavascriptInterface
        fun onStats(total: Int, processed: Int, unprocessed: Int, inProgress: Int) {
            runOnUiThread {
                totalTickets = total
                processedTickets = processed
                unprocessedTickets = unprocessed
                inProgressTickets = inProgress
                updateStats()
                Log.d(TAG, "Статистика: всего=$total, обработано=$processed, новые=$unprocessed, в работе=$inProgress")
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            runOnUiThread {
                Log.e(TAG, "JavaScript error: $error")
                showMessage("Ошибка загрузки данных")
                showLoading(false)
                Toast.makeText(this@TicketListActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }
}
