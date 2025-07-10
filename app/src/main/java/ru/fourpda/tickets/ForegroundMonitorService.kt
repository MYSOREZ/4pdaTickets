package ru.fourpda.tickets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.*
import androidx.core.app.NotificationCompat

class ForegroundMonitorService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val ACTION_START_MONITORING = "ru.fourpda.tickets.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "ru.fourpda.tickets.action.STOP_MONITORING"
        const val ACTION_REFRESH_WEBVIEW = "ru.fourpda.tickets.action.REFRESH_WEBVIEW"

        private const val NOTIFICATION_ID = 1

        // КАНАЛЫ УВЕДОМЛЕНИЙ
        const val CHANNEL_SERVICE_ID = "service_channel"
        const val CHANNEL_TICKETS_ID = "tickets_channel"

        private const val TICKETS_URL = "https://4pda.to/forum/index.php?act=ticket"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isServiceStarted = false
    private var currentRefreshInterval = 30_000L  // По умолчанию 30 сек
    private var refreshCount = 0

    private var totalTickets = 0
    private var processedTickets = 0
    private var unprocessedTickets = 0
    private var inProgressTickets = 0
    private var isUserAuthed = false
    private var statsReceiver: BroadcastReceiver? = null

    private var webView: WebView? = null
    private var ticketMonitor: TicketMonitor? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ForegroundMonitorService создан")

        // Создаем каналы уведомлений В СЕРВИСЕ
        createNotificationChannels()

        registerStatsReceiver()
        initWebViewInService()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Канал для служебных уведомлений
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Мониторинг тикетов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о работе сервиса мониторинга"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            // Канал для уведомлений о новых тикетах
            val ticketsChannel = NotificationChannel(
                CHANNEL_TICKETS_ID,
                "Новые тикеты",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых тикетах 4PDA"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
                lightColor = android.graphics.Color.BLUE
            }

            // Создаем каналы
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(ticketsChannel)

            Log.d(TAG, "✅ Каналы уведомлений созданы в сервисе")
        } else {
            Log.d(TAG, "📱 Android < 8.0 - каналы не нужны")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                return START_NOT_STICKY  // Не перезапускать после остановки
            }
            ACTION_REFRESH_WEBVIEW -> refreshWebView()
        }
        return START_STICKY  // Только для обычной работы - перезапуск если система убьёт
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "⚠️ onTaskRemoved: Приложение закрыто через Recent Apps, но сервис продолжает работать")
        super.onTaskRemoved(rootIntent)

        // Обновляем уведомление, чтобы показать что приложение закрыто
        if (isServiceStarted) {
            val notification = createServiceNotification("Приложение закрыто, мониторинг продолжается")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        ticketMonitor?.stopMonitoring()
        webView?.destroy()
        unregisterStatsReceiver()
        Log.d(TAG, "ForegroundMonitorService уничтожен")
    }

    // Получение интервала из настроек
    private fun getCurrentRefreshInterval(): Long {
        val intervalSeconds = SettingsActivity.getRefreshInterval(this)
        Log.d(TAG, "📅 Интервал из настроек: $intervalSeconds секунд")
        return intervalSeconds * 1000L
    }

    // Оптимизированная инициализация WebView для фонового режима
    private fun initWebViewInService() {
        try {
            Log.d(TAG, "Инициализируем WebView в сервисе")

            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                settings.cacheMode = WebSettings.LOAD_NO_CACHE

                // В сервисе отключаем изображения для экономии ресурсов
                settings.blockNetworkImage = true
                settings.setLoadsImagesAutomatically(false)

                CookieManager.getInstance().setAcceptCookie(true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "Сервис: Страница загружена: $url")
                        if (url?.contains("act=ticket") == true) {
                            isUserAuthed = true
                            Handler(Looper.getMainLooper()).postDelayed({
                                ticketMonitor?.searchNow()
                            }, 2000)
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        Log.d(TAG, "Сервис: Страница начала загружаться: $url")
                    }
                }

                loadUrl(TICKETS_URL)
            }

            // TicketMonitor должен быть инициализирован
            ticketMonitor = TicketMonitor(
                context = this,
                webView = webView!!
            ) { statusMessage ->
                Log.d(TAG, "Статус от TicketMonitor: $statusMessage")
            }

            Log.d(TAG, "✅ WebView и TicketMonitor инициализированы в сервисе")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка инициализации WebView в сервисе: ${e.message}")
        }
    }

    // Запуск мониторинга
    private fun startMonitoring() {
        if (isServiceStarted) return
        isServiceStarted = true
        refreshCount = 0

        // Получаем интервал из настроек
        currentRefreshInterval = getCurrentRefreshInterval()
        Log.d(TAG, "🔄 Установлен интервал обновления: ${currentRefreshInterval/1000} секунд")

        startForeground(NOTIFICATION_ID, createServiceNotification())
        ticketMonitor?.startMonitoring()
        scheduleNextRefresh(0)

        Log.d(TAG, "Мониторинг запущен в сервисе с интервалом ${currentRefreshInterval/1000} сек")
    }

    // Полная остановка сервиса с очисткой
    private fun stopMonitoring() {
        if (!isServiceStarted) return
        isServiceStarted = false

        Log.d(TAG, "🛑 Начинаем полную остановку сервиса")

        // 1. Останавливаем все таймеры
        handler.removeCallbacksAndMessages(null)

        // 2. Останавливаем TicketMonitor
        ticketMonitor?.stopMonitoring()

        // 3. Полная очистка WebView и освобождение памяти
        webView?.apply {
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
        webView = null
        ticketMonitor = null

        // 4. Удаляем все уведомления приложения
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.apply {
            cancel(NOTIFICATION_ID)
            cancelAll() // Удаляем все уведомления приложения
        }

        // 5. Принудительная остановка foreground service
        stopForeground(true)  // true = удалить уведомление
        stopSelf()  // Принудительная остановка

        Log.d(TAG, "✅ Сервис принудительно остановлен и очищен")
    }

    private fun scheduleNextRefresh(delay: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            refreshWebView()
        }, delay)
    }

    // Используем актуальные настройки при каждом обновлении
    private fun refreshWebView() {
        if (!isServiceStarted) return
        refreshCount++

        // Обновляем интервал из настроек при каждом refresh
        val newInterval = getCurrentRefreshInterval()
        if (newInterval != currentRefreshInterval) {
            currentRefreshInterval = newInterval
            Log.d(TAG, "🔄 Обновлен интервал: ${currentRefreshInterval/1000} секунд")
        }

        Log.d(TAG, "Обновление WebView #$refreshCount (интервал: ${currentRefreshInterval/1000}с)")

        webView?.loadUrl("javascript:window.location.reload(true)")
        scheduleNextRefresh(currentRefreshInterval)
    }

    private fun createTicketIntent(): Intent {
        val ticketUrl = "https://4pda.to/forum/index.php?act=ticket"

        // Сначала пытаемся открыть в приложении 4PDA
        val fourpdaIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ticketUrl)).apply {
            setPackage("ru.fourpda.client")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Проверяем, может ли система обработать этот intent
        return if (packageManager.resolveActivity(fourpdaIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            Log.d(TAG, "✅ Служебное уведомление: открываем в приложении 4PDA")
            fourpdaIntent
        } else {
            Log.d(TAG, "❌ Служебное уведомление: приложение 4PDA не найдено")
            Intent(Intent.ACTION_VIEW, Uri.parse(ticketUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }

    // Служебное уведомление теперь открывает прямую ссылку на тикеты
    private fun createServiceNotification(customText: String? = null): Notification {
        val notificationIntent = createTicketIntent()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val (statusText, detailText) = if (!isUserAuthed) {
            "Войдите в свой аккаунт" to ""
        } else {
            val status = customText ?: "Мониторинг активен"
            val intervalSec = currentRefreshInterval / 1000

            status to "Всего: $totalTickets\nОбработанные/необработанные: $processedTickets/$unprocessedTickets\nВ работе: $inProgressTickets\nИнтервал: ${intervalSec}с"
        }

        val multiLineText = if (detailText.isNotEmpty()) {
            "$statusText\n$detailText"
        } else {
            statusText
        }

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("Мониторинг тикетов")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(multiLineText))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun updateNotificationWithStats() {
        if (!isServiceStarted) return

        try {
            val notification = createServiceNotification()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)

            val intervalSec = currentRefreshInterval / 1000
            val statusLog = if (isUserAuthed) {
                "Всего: $totalTickets, обработанные/необработанные: $processedTickets/$unprocessedTickets, в работе: $inProgressTickets (${intervalSec}с)"
            } else {
                "Требуется авторизация"
            }
            Log.d(TAG, "Уведомление обновлено: $statusLog")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления уведомления: ${e.message}")
        }
    }

    private fun registerStatsReceiver() {
        statsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "📡 ForegroundService получил broadcast: ${intent?.action}")

                when (intent?.action) {
                    "ru.fourpda.tickets.TICKET_STATS" -> {
                        totalTickets = intent.getIntExtra("total", 0)
                        processedTickets = intent.getIntExtra("processed", 0)
                        unprocessedTickets = intent.getIntExtra("unprocessed", 0)
                        inProgressTickets = intent.getIntExtra("in_progress", 0)
                        Log.d(TAG, "📊 Получена статистика тикетов: всего=$totalTickets, обработанных=$processedTickets, необработанных=$unprocessedTickets, в работе=$inProgressTickets")
                        updateNotificationWithStats()
                    }
                    "ru.fourpda.tickets.AUTH_STATUS" -> {
                        isUserAuthed = intent.getBooleanExtra("isAuthed", false)
                        Log.d(TAG, "🔑 Получен статус авторизации: $isUserAuthed")
                        updateNotificationWithStats()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("ru.fourpda.tickets.TICKET_STATS")
            addAction("ru.fourpda.tickets.AUTH_STATUS")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, filter)
        }

        Log.d(TAG, "✅ StatsReceiver зарегистрирован в ForegroundService")
    }

    private fun unregisterStatsReceiver() {
        statsReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "✅ StatsReceiver отключен")
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка отключения StatsReceiver: ${e.message}")
            }
        }
        statsReceiver = null
    }
}
