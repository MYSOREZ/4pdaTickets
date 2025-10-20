package ru.fourpda.tickets

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Worker для быстрой проверки тикетов без постоянного FGS
 * Выполняется по точным будильникам каждые 5 минут
 * ОПТИМИЗИРОВАН: минимальное время выполнения, легкий WebView
 */
class QuickCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "QuickCheckWorker"
        private const val CHECK_TIMEOUT_MS = 15_000L // Сокращено до 15 секунд
        @Volatile
        private var isRunning = false
    }

    override fun doWork(): Result {
        // Защита от наложений
        if (isRunning) {
            Log.w(TAG, "⚠️ Предыдущая проверка еще выполняется, пропускаем")
            return Result.success()
        }
        
        isRunning = true
        val startMessage = "🔍 Начинаем быструю проверку тикетов..."
        Log.d(TAG, startMessage)
        FileLogger.d(TAG, startMessage)
        
        return try {
            runBlocking {
                val success = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                    performQuickCheck()
                } ?: false
                
                if (success) {
                    val successMessage = "✅ Быстрая проверка завершена успешно"
                    Log.d(TAG, successMessage)
                    FileLogger.d(TAG, successMessage)
                    updateServiceNotification()
                    Result.success()
                } else {
                    val timeoutMessage = "⚠️ Быстрая проверка не завершилась в срок"
                    Log.w(TAG, timeoutMessage)
                    FileLogger.w(TAG, timeoutMessage)
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            val errorMessage = "❌ Ошибка быстрой проверки: ${e.message}"
            Log.e(TAG, errorMessage)
            FileLogger.e(TAG, errorMessage, e)
            Result.failure()
        } finally {
            isRunning = false
        }
    }
    
    private suspend fun performQuickCheck(): Boolean = suspendCancellableCoroutine { continuation ->
        // WebView нужно создавать в главном потоке
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // Создаем минимальный WebView только для проверки
                val webView = WebView(applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = true // Блокируем изображения
                        setLoadsImagesAutomatically(false)
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        // Отключаем все лишнее для скорости
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        allowFileAccess = false
                        allowContentAccess = false
                        databaseEnabled = false
                    }
                    
                    CookieManager.getInstance().setAcceptCookie(true)
                    
                    // Добавляем WebChromeClient для перехвата console.log
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            consoleMessage?.message()?.let { message ->
                                if (message.contains("СТАТИСТИКА СТАТУСОВ:")) {
                                    Log.d(TAG, "📊 Перехвачена статистика из console: $message")
                                    // Парсим статистику из console.log
                                    parseStatsFromConsole(message)
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                    
                    // Добавляем JavaScript интерфейс для связи с TicketMonitor
                    var ticketMonitor: TicketMonitor? = null
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onTickets(jsonStr: String) {
                            Log.d(TAG, "🎯 Android.onTickets вызван: $jsonStr")
                            ticketMonitor?.let { monitor ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    try {
                                        monitor.handleTickets(jsonStr)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Ошибка вызова onTickets: ${e.message}")
                                    }
                                }
                            }
                        }
                        
                        @android.webkit.JavascriptInterface
                        fun onNoTickets() {
                            Log.d(TAG, "🎯 Android.onNoTickets вызван")
                            ticketMonitor?.let { monitor ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    try {
                                        monitor.handleNoTickets()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Ошибка вызова onNoTickets: ${e.message}")
                                    }
                                }
                            }
                        }
                        
                        @android.webkit.JavascriptInterface
                        fun onFullStats(total: Int, processed: Int, unprocessed: Int, inProgress: Int) {
                            Log.d(TAG, "🎯 Android.onFullStats вызван: $total,$processed,$unprocessed,$inProgress")
                            ticketMonitor?.let { monitor ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    try {
                                        monitor.handleFullStats(total, processed, unprocessed, inProgress)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Ошибка вызова onFullStats: ${e.message}")
                                    }
                                }
                            }
                        }
                        
                        @android.webkit.JavascriptInterface
                        fun logDebug(msg: String) {
                            Log.d(TAG, "🎯 Android.logDebug: $msg")
                            ticketMonitor?.let { monitor ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    try {
                                        monitor.handleLogDebug(msg)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Ошибка вызова logDebug: ${e.message}")
                                    }
                                }
                            }
                        }
                        
                        @android.webkit.JavascriptInterface
                        fun err(msg: String) {
                            Log.e(TAG, "🎯 Android.err: $msg")
                            ticketMonitor?.let { monitor ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    try {
                                        monitor.handleError(msg)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Ошибка вызова err: ${e.message}")
                                    }
                                }
                            }
                        }
                    }, "Android")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url?.contains("act=ticket") == true) {
                                Log.d(TAG, "🔍 Страница тикетов загружена, запускаем быструю проверку")
                                
                                // Создаем временный TicketMonitor ТОЛЬКО для проверки
                                val monitor = TicketMonitor(
                                    context = applicationContext,
                                    webView = this@apply
                                ) { status ->
                                    Log.d(TAG, "📊 Получена статистика: $status")
                                    // Сохраняем статистику для уведомлений
                                    saveTicketStats(status)
                                }
                                
                                // Связываем с JavaScript интерфейсом
                                ticketMonitor = monitor
                                
                                monitor.startMonitoring()
                                
                                // Принудительно инициализируем JavaScript интерфейс
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    Log.d(TAG, "🔧 Принудительная инициализация JavaScript интерфейса")
                                    
                                    // Создаем упрощенный JavaScript интерфейс прямо в коде
                                    val initJs = """
                                        window.TicketJS = {
                                            onTickets: function(jsonStr) {
                                                console.log('TicketJS.onTickets called with: ' + jsonStr);
                                                Android.onTickets(jsonStr);
                                            },
                                            onNoTickets: function() {
                                                console.log('TicketJS.onNoTickets called');
                                                Android.onNoTickets();
                                            },
                                            onFullStats: function(total, processed, unprocessed, inProgress) {
                                                console.log('TicketJS.onFullStats called: ' + total + ',' + processed + ',' + unprocessed + ',' + inProgress);
                                                Android.onFullStats(total, processed, unprocessed, inProgress);
                                            },
                                            logDebug: function(msg) {
                                                console.log('TicketJS.logDebug: ' + msg);
                                                Android.logDebug(msg);
                                            },
                                            err: function(msg) {
                                                console.error('TicketJS.err: ' + msg);
                                                Android.err(msg);
                                            }
                                        };
                                        console.log('TicketJS interface initialized');
                                    """.trimIndent()
                                    
                                    evaluateJavascript(initJs) { result ->
                                        Log.d(TAG, "🔧 JavaScript интерфейс инициализирован: $result")
                                        
                                        // Проверяем доступность
                                        evaluateJavascript("typeof TicketJS !== 'undefined'") { checkResult ->
                                            Log.d(TAG, "🔧 TicketJS доступен: $checkResult")
                                            
                                            // Запускаем поиск тикетов
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                Log.d(TAG, "🔍 Запускаем поиск тикетов")
                                                monitor.searchNow()
                                                
                                                // Завершаем через 6 секунд
                                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                    Log.d(TAG, "⏹️ Завершаем мониторинг")
                                                    monitor.stopMonitoring()
                                                    cleanupWebView(this@apply)
                                                    if (continuation.isActive) {
                                                        continuation.resume(true)
                                                    }
                                                }, 6000)
                                            }, 1000)
                                        }
                                    }
                                }, 1000)
                            }
                        }
                        
                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            Log.e(TAG, "❌ WebView ошибка: $description")
                            cleanupWebView(view)
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }
                    
                    loadUrl("https://4pda.to/forum/index.php?act=ticket")
                }
                
                // Сокращенный таймаут - 12 секунд вместо 20
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (continuation.isActive) {
                        Log.w(TAG, "⏰ Таймаут быстрой проверки")
                        cleanupWebView(webView)
                        continuation.resume(false)
                    }
                }, 12_000)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка создания WebView: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
    
    private fun cleanupWebView(webView: WebView?) {
        try {
            webView?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
                onPause()
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка очистки WebView: ${e.message}")
        }
    }
    
    private fun saveTicketStats(status: String) {
        try {
            // Парсим статус для извлечения данных
            val prefs = applicationContext.getSharedPreferences("ticket_stats", Context.MODE_PRIVATE)
            
            // Отмечаем что пользователь авторизован (если дошли до проверки)
            prefs.edit().putBoolean("is_user_authed", true).apply()
            
            // Парсим статистику из строки статуса
            // Формат: "Мониторинг: всего=6, обработано=6, новые=0, в работе=0"
            if (status.contains("всего=") && status.contains("обработано=")) {
                val totalMatch = Regex("всего=(\\d+)").find(status)
                val processedMatch = Regex("обработано=(\\d+)").find(status)
                val newMatch = Regex("новые=(\\d+)").find(status)
                val inProgressMatch = Regex("в работе=(\\d+)").find(status)
                
                val total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val processed = processedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val newTickets = newMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val inProgress = inProgressMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val unprocessed = total - processed
                
                prefs.edit()
                    .putInt("total_tickets", total)
                    .putInt("processed_tickets", processed)
                    .putInt("unprocessed_tickets", unprocessed)
                    .putInt("new_tickets", newTickets)
                    .putInt("in_progress_tickets", inProgress)
                    .apply()
                
                Log.d(TAG, "💾 Сохранена статистика: всего=$total, обработано=$processed, необработано=$unprocessed, новые=$newTickets, в работе=$inProgress")
                
                // Отправляем broadcast для обновления UI
                val intent = Intent("TICKET_STATISTICS_UPDATE").apply {
                    putExtra("total_tickets", total)
                    putExtra("processed_tickets", processed)
                    putExtra("unprocessed_tickets", unprocessed)
                    putExtra("new_tickets", newTickets)
                    putExtra("in_progress_tickets", inProgress)
                }
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext)
                    .sendBroadcast(intent)
                
            } else {
                Log.d(TAG, "💾 Сохранена только информация об авторизации (статистика не найдена)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка сохранения статистики: ${e.message}")
        }
    }
    
    private fun parseStatsFromConsole(message: String) {
        try {
            // Парсим сообщение типа: "СТАТИСТИКА СТАТУСОВ: всего=6, обработанных=6, необработанных=0, в работе=0."
            val totalMatch = Regex("всего=(\\d+)").find(message)
            val processedMatch = Regex("обработанных=(\\d+)").find(message)
            val unprocessedMatch = Regex("необработанных=(\\d+)").find(message)
            val inProgressMatch = Regex("в работе=(\\d+)").find(message)
            
            val total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val processed = processedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val unprocessed = unprocessedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val inProgress = inProgressMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (total > 0 || processed > 0) {
                // Получаем предыдущую статистику для сравнения
                val prefs = applicationContext.getSharedPreferences("ticket_stats", Context.MODE_PRIVATE)
                val prevUnprocessed = prefs.getInt("unprocessed_tickets", 0)
                
                // Сохраняем новую статистику
                prefs.edit()
                    .putBoolean("is_user_authed", true)
                    .putInt("total_tickets", total)
                    .putInt("processed_tickets", processed)
                    .putInt("unprocessed_tickets", unprocessed)
                    .putInt("in_progress_tickets", inProgress)
                    .apply()
                
                Log.d(TAG, "💾 Сохранена статистика из console: всего=$total, обработано=$processed, необработано=$unprocessed, в работе=$inProgress")
                
                // Проверяем, появились ли новые необработанные тикеты
                if (unprocessed > prevUnprocessed) {
                    val newTickets = unprocessed - prevUnprocessed
                    Log.d(TAG, "🔥 Обнаружено $newTickets новых тикетов! (Уведомления должны прийти через TicketMonitor)")
                }
                
                // Обновляем уведомление
                updateServiceNotification()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка парсинга статистики из console: ${e.message}")
        }
    }
    

    

    
    private fun updateServiceNotification() {
        try {
            // Обновляем уведомление через NotificationUpdater
            NotificationUpdater.updateServiceNotification(applicationContext)
            Log.d(TAG, "📱 Уведомление обновлено после быстрой проверки")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обновления уведомления: ${e.message}")
        }
    }
}