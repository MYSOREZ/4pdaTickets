package ru.fourpda.tickets

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONArray
import ru.fourpda.tickets.databinding.ActivityTicketListBinding

class TicketListActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TicketListActivity"
        private const val TICKETS_URL = "https://4pda.to/forum/index.php?act=ticket"
    }

    private lateinit var ui: ActivityTicketListBinding
    private lateinit var adapter: TicketAdapter
    private var webView: WebView? = null
    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }

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
            try { Toast.makeText(this, "Ошибка инициализации: ${e.message}", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            finish()
        }
    }

    private fun initUI() {
        adapter = TicketAdapter { ticket -> openTicket(ticket) }
        ui.recyclerTickets.apply {
            layoutManager = LinearLayoutManager(this@TicketListActivity)
            adapter = this@TicketListActivity.adapter
        }
        ui.btnBack.setOnClickListener { finish() }
        ui.btnRefreshTickets.setOnClickListener { loadTickets() }
    }

    private fun initWebView() {
        try {
            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                @Suppress("DEPRECATION")
                settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                cookieManager.flush()

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        try {
                            Log.d(TAG, "Страница загружена: $url")
                            if (url?.contains("act=ticket") == true) {
                                // Используем uiHandler вместо handler, чтобы исключить NPE
                                uiHandler.postDelayed({
                                    if (!isFinishing && !isDestroyed) extractTickets()
                                }, 2000)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка в onPageFinished: ${e.message}", e)
                            runOnUiThread {
                                showMessage("Ошибка загрузки страницы")
                                showLoading(false)
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        Log.e(TAG, "WebView ошибка: $description на $failingUrl")
                        runOnUiThread {
                            showMessage("Ошибка загрузки страницы: $description")
                            showLoading(false)
                        }
                    }
                }

                addJavascriptInterface(JsBridge(), "TicketListJS")
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
        val extraHeaders = hashMapOf("Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8")
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
                
                // Ищем все строки тикетов (кроме заголовков и контента)
                let allRows = document.querySelectorAll('div.t-row:not(.row-description):not(.t-row-content)');
                allRows = Array.from(allRows).filter(row => row.getAttribute('t_id') || (row.id && row.id.includes('t-row-')));
                totalCount = allRows.length;
                if (totalCount === 0) {
                    TicketListJS.onError('Тикеты не найдены на странице.');
                    return;
                }
                
                // Разворачиваем все тикеты
                allRows.forEach((row) => { const t = row.querySelector('.t-title'); if (t) t.click(); });
                
                setTimeout(function() {
                  allRows.forEach(function(row){
                    try {
                      const id = row.getAttribute('t_id') || row.id.replace('t-row-', '');
                      if (!id) return;
                      const cls = row.className || '';
                      let status = -1;
                      if (cls.includes('row-status-0')) status = 0; else if (cls.includes('row-status-1')) status = 1; else if (cls.includes('row-status-2')) status = 2;
                      if (status === -1) {
                        const sc = row.querySelector('.t-status');
                        if (sc){ const scls = sc.className||''; if (scls.includes('status-0')) status=0; else if (scls.includes('status-1')) status=1; else if (scls.includes('status-2')) status=2; }
                      }
                      if (status === 0) unprocessedCount++; else if (status === 1) inProgressCount++; else if (status === 2) processedCount++;
                      const titleEl = row.querySelector('.t-title a');
                      const title = titleEl ? (titleEl.getAttribute('title') || titleEl.textContent.trim()) : 'Без названия';
                      const sectionEl = row.querySelector('.t-description a');
                      const section = sectionEl ? (sectionEl.getAttribute('title') || sectionEl.textContent.trim()) : 'Неизвестный раздел';
                      const dateEl = row.querySelector('.t-date a');
                      const date = dateEl ? dateEl.textContent.trim() : '';
                      const modEl = row.querySelector('.t-mod a');
                      const moderator = modEl ? modEl.textContent.trim() : '';
                      let topic = ''; let sender = ''; let postAuthor = '';
                      let contentEl = document.querySelector('#t-row-content-' + id);
                      if (!contentEl) { const nextRow = row.nextElementSibling; if (nextRow && nextRow.classList.contains('t-row-content')) contentEl = nextRow; }
                      if (contentEl) {
                        const html = contentEl.innerHTML;
                        const topicMatch = html.match(/Тема:<\/strong>\s*<a[^>]+>([^<]+)<\/a>/);
                        if (topicMatch) { topic = topicMatch[1].trim(); }
                        else { const topicLink = contentEl.querySelector('a[href*=\"findpost\"]'); if (topicLink) topic = topicLink.textContent.trim(); }
                        const authorMatch = html.match(/Автор поста:<\/strong>\s*<a[^>]+>([^<]+)<\/a>/);
                        if (authorMatch) { postAuthor = authorMatch[1].trim(); }
                        else { const userLinks = contentEl.querySelectorAll('a[href*=\"showuser\"]'); if (userLinks.length>0) postAuthor = userLinks[0].textContent.trim(); }
                        const senderEl = contentEl.querySelector('.td-member a'); if (senderEl) sender = senderEl.textContent.trim();
                        if (!topic || !postAuthor) {
                          const msgEl = contentEl.querySelector('.td-message');
                          if (msgEl) {
                            const msg = msgEl.textContent || '';
                            if (!topic) { const m = msg.match(/Тема:\s*([^\n]+)/); if (m) topic = m[1].trim(); }
                            if (!postAuthor) { const m2 = msg.match(/Автор поста:\s*([^\s\n]+)/); if (m2) postAuthor = m2[1].trim(); }
                          }
                        }
                      }
                      tickets.push({ id, title, section, date, status, moderator, topic, sender, postAuthor });
                    } catch (er) { console.error('row error', er); }
                  });
                  TicketListJS.onStats(totalCount, processedCount, unprocessedCount, inProgressCount);
                  TicketListJS.onTickets(JSON.stringify(tickets));
                }, 1000);
              } catch (e) {
                console.error('parse error', e);
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
        if (show) ui.textMessage.visibility = View.GONE
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
        Log.d(TAG, "Клик по тикету: ${ticket.title} (ID: ${ticket.id})")
        // Переход уже реализован в TicketAdapter через IntentDebugger
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onTickets(jsonTickets: String) {
            runOnUiThread {
                try {
                    val ticketsArray = JSONArray(jsonTickets)
                    val ticketsList = mutableListOf<TicketData>()
                    for (i in 0 until ticketsArray.length()) {
                        val t = ticketsArray.getJSONObject(i)
                        ticketsList.add(
                            TicketData(
                                id = t.optString("id"),
                                title = t.optString("title"),
                                section = t.optString("section"),
                                date = t.optString("date"),
                                status = t.optInt("status", 0),
                                moderator = t.optString("moderator"),
                                topic = t.optString("topic"),
                                sender = t.optString("sender"),
                                postAuthor = t.optString("postAuthor")
                            )
                        )
                    }
                    adapter.setTickets(ticketsList)
                    showLoading(false)
                    if (ticketsList.isEmpty()) showMessage("У вас пока нет тикетов")
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
