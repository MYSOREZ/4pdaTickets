package ru.fourpda.tickets

import android.Manifest
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ru.fourpda.tickets.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var ui: ActivityMainBinding
    private var statisticsReceiver: BroadcastReceiver? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val permissionRunnable = Runnable { requestRequiredPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        Log.d(TAG, "=== ЗАПУСК MAIN ACTIVITY ===")
        FileLogger.i(TAG, "=== ЗАПУСК MAIN ACTIVITY ===")
        
        // Проверяем, был ли предыдущий крэш
        checkForPreviousCrash()
        
        // Сбрасываем флаг разрешений при первом запуске после установки
        checkFirstRun()

        initWebView()
        initButtons()
        registerStatisticsReceiver()
        checkAuthenticationStatus()

        // Проверяем MIUI и показываем предупреждение при первом запуске
        checkMiuiAndShowWarning()
    }

    /* ----------------------------- WebView ----------------------------- */

    private fun initWebView() {
        ui.authWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(ui.authWebView, true)
            }
        }

        ui.authWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return when {
                    url.isMainPage() -> { onAuthenticationComplete(); true }
                    url?.contains("act=") == false && url?.startsWith("https://4pda.to") == true -> { onAuthenticationComplete(); true }
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("act=ticket") == true) onTicketsPageLoaded()
            }
        }
    }

    private fun String?.isMainPage() = this in listOf(
        "https://4pda.to/",
        "https://4pda.to",
        "https://4pda.to/forum/",
        "https://4pda.to/forum/index.php"
    )

    /* ----------------------- Аутентификация ----------------------- */

    private fun checkAuthenticationStatus() {
        val cm = CookieManager.getInstance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush()

        val cookies = cm.getCookie("https://4pda.to") ?: ""
        val isAuth = cookies.contains("member_id=") &&
                cookies.contains("pass_hash=") &&
                !cookies.contains("member_id=0")

        Log.d(TAG, "🔐 Проверка авторизации: ${if (isAuth) "авторизован" else "не авторизован"}")

        if (isAuth) {
            Log.d(TAG, "✅ Пользователь авторизован - показываем главный интерфейс")
            showMainInterface()
            loadTicketsPageInBackground()
        } else {
            Log.d(TAG, "❌ Пользователь не авторизован - показываем страницу входа")
            showAuthenticationPage()
        }
    }

    private fun showAuthenticationPage() {
        ui.authWebView.visibility = View.VISIBLE
        ui.controlPanel.visibility = View.GONE
        ui.authWebView.settings.apply {
            loadsImagesAutomatically = true
            blockNetworkImage = false
        }
        ui.authWebView.loadUrl("https://4pda.to/forum/index.php?act=auth")
    }

    private fun showMainInterface() {
        ui.authWebView.visibility = View.GONE
        ui.controlPanel.visibility = View.VISIBLE
        ui.statusText.text = "Загрузка страницы тикетов..."
        disableAllButtons()
    }

    private fun loadTicketsPageInBackground() {
        ui.authWebView.settings.apply {
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        ui.authWebView.loadUrl("https://4pda.to/forum/index.php?act=ticket")
    }

    private fun onAuthenticationComplete() {
        showMainInterface()
        mainHandler.postDelayed({ loadTicketsPageInBackground() }, 500)
        schedulePermissionRequest()
    }

    private fun onTicketsPageLoaded() {
        enableButtonsAfterTicketsLoad()
        // НЕ запрашиваем разрешения повторно при каждой загрузке страницы
        // schedulePermissionRequest() - убираем чтобы избежать зацикливания
    }

    /* ------------------- Разрешения / оптимизация ------------------- */

    private fun schedulePermissionRequest() {
        // Проверяем, не запрашиваем ли мы уже разрешения
        val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
        if (prefs.getBoolean("permissions_requested", false)) {
            Log.d(TAG, "⏭️ Разрешения уже обрабатывались, пропускаем планирование")
            return
        }
        
        // Дополнительная защита - проверяем есть ли уже запланированный вызов
        if (mainHandler.hasCallbacks(permissionRunnable)) {
            Log.d(TAG, "⏭️ Запрос разрешений уже запланирован, пропускаем")
            return
        }
        
        Log.d(TAG, "📅 Планируем запрос разрешений через 800ms")
        mainHandler.postDelayed(permissionRunnable, 800)
    }
    
    private fun resetPermissionFlags() {
        val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("permissions_requested", false).apply()
        Log.d(TAG, "🔄 Флаги разрешений сброшены")
    }
    
    private fun checkFirstRun() {
        val prefs = getSharedPreferences("app_state", Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run", true)
        
        if (isFirstRun) {
            Log.d(TAG, "🆕 Первый запуск приложения - сбрасываем все флаги")
            
            // Сбрасываем флаги разрешений
            resetPermissionFlags()
            
            // ВАЖНО: При первом запуске мониторинг должен быть остановлен
            val servicePrefs = getSharedPreferences("service_state", Context.MODE_PRIVATE)
            servicePrefs.edit()
                .putBoolean("explicitly_stopped", true)
                .putBoolean("stopped_by_timeout", false)
                .apply()
            Log.d(TAG, "🛑 Мониторинг отключен при первом запуске")
            
            // Отмечаем что первый запуск завершен
            prefs.edit().putBoolean("first_run", false).apply()
        } else {
            Log.d(TAG, "🔄 Повторный запуск приложения")
        }
    }

    private fun requestRequiredPermissions() {
        Log.d(TAG, "🔐 Запрашиваем необходимые разрешения")

        // Проверяем флаг, чтобы избежать зацикливания
        val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
        if (prefs.getBoolean("permissions_requested", false)) {
            Log.d(TAG, "✅ Разрешения уже запрашивались, пропускаем")
            return
        }

        // НЕМЕДЛЕННО устанавливаем флаг чтобы избежать повторных вызовов
        prefs.edit().putBoolean("permissions_requested", true).apply()
        Log.d(TAG, "🔒 Флаг permissions_requested установлен")
        
        // Отменяем все запланированные вызовы этого метода
        mainHandler.removeCallbacks(permissionRunnable)

        // Сначала запрашиваем разрешение на запись для логов
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            
            Log.d(TAG, "📁 Запрашиваем разрешение на запись во внешнее хранилище")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                1002
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "🔔 Запрашиваем разрешение на уведомления")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
                return
            }
        }

        // Все разрешения уже получены
        Log.d(TAG, "✅ Все разрешения уже предоставлены")
        requestBatteryOptimization()
    }

    override fun onRequestPermissionsResult(
        code: Int,
        perms: Array<out String>,
        res: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, res)
        when (code) {
            1002 -> {
                // Разрешение на запись во внешнее хранилище
                if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Разрешение на запись предоставлено", Toast.LENGTH_SHORT).show()
                    // Инициализируем FileLogger после получения разрешения
                    FileLogger.init(this)
                    Log.d(TAG, "✅ FileLogger инициализирован после получения разрешения")
                } else {
                    Toast.makeText(this, "Разрешение на запись отклонено - логи недоступны", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "⚠️ Разрешение на запись отклонено")
                }
                
                // Продолжаем с уведомлениями
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "🔔 Запрашиваем разрешение на уведомления")
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_NOTIFICATION_PERMISSION
                        )
                        return
                    }
                }
                
                // Все разрешения обработаны
                val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("permissions_requested", true).apply()
                requestBatteryOptimization()
            }
            REQUEST_NOTIFICATION_PERMISSION -> {
                Toast.makeText(
                    this,
                    if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED)
                        "Уведомления разрешены"
                    else
                        "Уведомления отклонены",
                    Toast.LENGTH_SHORT
                ).show()
                
                // Отмечаем что все разрешения обработаны
                val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("permissions_requested", true).apply()
                requestBatteryOptimization()
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                    Toast.makeText(
                        this,
                        "Выберите 'Разрешить' для стабильной работы",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    Toast.makeText(
                        this,
                        "Найдите приложение и отключите ограничения",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /* ------------------------------ UI ------------------------------ */

    private fun disableAllButtons() = with(ui) {
        btnStartMonitoring.isEnabled = false
        btnStopMonitoring.isEnabled = false
        btnMinimizeToNotification.isEnabled = false
        btnSettings.isEnabled = false
        btnLogout.isEnabled = false
        btnExitApp.isEnabled = true
    }

    private fun enableButtonsAfterTicketsLoad() {
        val isRunning = isMonitoringServiceRunning()
        Log.d(TAG, "🔄 enableButtonsAfterTicketsLoad: мониторинг ${if (isRunning) "запущен" else "остановлен"}")
        
        ui.btnStartMonitoring.isEnabled = !isRunning
        ui.btnStopMonitoring.isEnabled = isRunning
        ui.btnMinimizeToNotification.isEnabled = true
        ui.btnSettings.isEnabled = true
        ui.btnLogout.isEnabled = true
        ui.btnExitApp.isEnabled = true
        ui.statusText.text = if (isRunning) "Мониторинг запущен" else "Мониторинг остановлен"
    }

    private fun initButtons() = with(ui) {
        btnMyTickets.setOnClickListener {
            startActivity(Intent(this@MainActivity, TicketListActivity::class.java))
        }
        btnStartMonitoring.setOnClickListener { startMonitoringService() }
        btnStopMonitoring.setOnClickListener { stopMonitoringService() }
        btnMinimizeToNotification.setOnClickListener { minimizeToBackground() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        btnLogout.setOnClickListener { logout() }
        btnExitApp.setOnClickListener { exitApplication() }
    }

    /* ---------------------- Запуск / стоп сервисов ---------------------- */

    private fun startMonitoringService() {
        // Сбрасываем флаги остановки
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("explicitly_stopped", false)
            .putBoolean("stopped_by_timeout", false)
            .apply()

        // Проверяем разрешение на точные будильники для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ExactAlarmScheduler.canScheduleExactAlarms(this)) {
            showExactAlarmPermissionDialog()
            return
        }

        // НЕ запускаем ForegroundMonitorService - используем только точные будильники
        // val serviceIntent = Intent(this, ForegroundMonitorService::class.java).apply {
        //     action = ForegroundMonitorService.ACTION_START_MONITORING
        // }
        // startForegroundService(serviceIntent)
        Log.d(TAG, "🚀 Используем только точные будильники без постоянного сервиса")
        
        // Сначала запускаем немедленную проверку
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()
        
        val immediateWorkRequest = androidx.work.OneTimeWorkRequestBuilder<QuickCheckWorker>()
            .setConstraints(constraints)
            .addTag("immediate_start_check")
            .build()
        
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            "immediate_ticket_check",
            androidx.work.ExistingWorkPolicy.REPLACE,
            immediateWorkRequest
        )
        Log.d(TAG, "🚀 Запущена немедленная проверка при старте мониторинга")
        
        // Затем запускаем регулярные проверки с интервалом из настроек
        val intervalSeconds = SettingsActivity.getRefreshInterval(this)
        ExactAlarmScheduler.scheduleNextAlarmSeconds(this, intervalSeconds)
        
        // Оставляем только AlarmReceiver как дополнительный резерв
        // KeepAliveWorker.scheduleWork(this) - ОТКЛЮЧЕН: вызывает FGS крэш
        AlarmReceiver.scheduleAlarm(this)

        if (MiuiUtils.isChineseRom()) {
            WatchdogService.startWatchdog(this)
            Log.d(TAG, "🐕 Запущен WatchdogService для китайской прошивки")
        }

        ui.btnStartMonitoring.isEnabled = false
        ui.btnStopMonitoring.isEnabled = true
        ui.statusText.text = "Мониторинг запущен"
        
        val startMessage = "🚀 Мониторинг запущен пользователем"
        FileLogger.i(TAG, startMessage)
        
        // Показываем начальное служебное уведомление
        NotificationUpdater.showInitialServiceNotification(this)
        
        // Первая проверка произойдет через установленный интервал (10 секунд)
        
        Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoringService() {
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("explicitly_stopped", true)
            .apply()

        // Останавливаем все механизмы мониторинга
        ExactAlarmScheduler.cancelAlarm(this)
        // KeepAliveWorker.cancelWork(this) - ОТКЛЮЧЕН: больше не используется
        AlarmReceiver.cancelAlarm(this)

        // ForegroundMonitorService больше не используется
        // startService(
        //     Intent(this, ForegroundMonitorService::class.java).apply {
        //         action = ForegroundMonitorService.ACTION_STOP_MONITORING
        //     }
        // )

        if (MiuiUtils.isChineseRom()) {
            WatchdogService.stopWatchdog(this)
        }

        // Скрываем служебное уведомление
        NotificationUpdater.hideServiceNotification(this)

        ui.btnStartMonitoring.isEnabled = true
        ui.btnStopMonitoring.isEnabled = false
        ui.statusText.text = "Мониторинг остановлен"
        
        val stopMessage = "🛑 Мониторинг остановлен пользователем"
        FileLogger.i(TAG, stopMessage)
        
        Toast.makeText(this, "Мониторинг остановлен", Toast.LENGTH_SHORT).show()
    }

    /* ----------------------- Прочая логика UI ----------------------- */

    private fun minimizeToBackground() {
        if (isMonitoringServiceRunning()) {
            moveTaskToBack(true)
            Toast.makeText(this, "Приложение работает в фоне", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Сначала запустите мониторинг", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logout() {
        ui.statusText.text = "Выход из аккаунта..."
        disableAllButtons()
        ui.btnExitApp.isEnabled = true

        if (isMonitoringServiceRunning()) stopMonitoringService()

        ui.authWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                if (url.isMainPage()) {
                    clearCookiesAndShowAuth()
                    return true
                }
                return false
            }
        }

        ui.authWebView.loadUrl("https://4pda.to/forum/index.php?act=Login&CODE=03")
        mainHandler.postDelayed({ clearCookiesAndShowAuth() }, 10_000)
    }

    private fun clearCookiesAndShowAuth() {
        val cm = CookieManager.getInstance()
        ui.authWebView.clearCache(true)
        ui.authWebView.clearHistory()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.removeAllCookies { cm.flush() }
        } else {
            cm.removeAllCookie()
        }

        mainHandler.postDelayed({
            initWebView()
            showAuthenticationPage()
            Toast.makeText(this, "Выход выполнен", Toast.LENGTH_SHORT).show()
        }, 1_000)
    }

    private fun exitApplication() {
        Log.d(TAG, "🚪 Выход из приложения")
        
        // Останавливаем мониторинг если он запущен
        if (isMonitoringServiceRunning()) {
            Log.d(TAG, "🛑 Останавливаем мониторинг перед выходом")
            stopMonitoringService()
        }
        
        // Полностью закрываем приложение
        finishAffinity()
        Log.d(TAG, "✅ Приложение закрыто")
    }

    private fun isMonitoringServiceRunning(): Boolean {
        // Теперь проверяем по состоянию будильников, а не по сервису
        val prefs = getSharedPreferences("service_state", Context.MODE_PRIVATE)
        val explicitlyStopped = prefs.getBoolean("explicitly_stopped", true) // по умолчанию остановлен
        val canScheduleAlarms = ExactAlarmScheduler.canScheduleExactAlarms(this)
        val isRunning = !explicitlyStopped && canScheduleAlarms
        
        Log.d(TAG, "🔍 isMonitoringServiceRunning: explicitly_stopped=$explicitlyStopped, canScheduleAlarms=$canScheduleAlarms, result=$isRunning")
        
        return isRunning
    }

    /* --------------------- Получение статистики --------------------- */

    private fun registerStatisticsReceiver() {
        statisticsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val processed = intent?.getIntExtra("processed_tickets", 0) ?: 0
                val unprocessed = intent?.getIntExtra("unprocessed_tickets", 0) ?: 0
                runOnUiThread {
                    if (isMonitoringServiceRunning()) {
                        ui.statusText.text =
                            "Мониторинг: $processed/$unprocessed тикетов"
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                statisticsReceiver!!,
                IntentFilter("TICKET_STATISTICS_UPDATE")
            )
    }

    /* ------------------------- Lifecycle ------------------------- */

    override fun onResume() {
        super.onResume()
        if (ui.controlPanel.visibility == View.VISIBLE)
            enableButtonsAfterTicketsLoad()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Отмечаем что приложение корректно завершается
        try {
            val prefs = getSharedPreferences("crash_detection", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("app_was_running", false).apply()
            FileLogger.i(TAG, "📱 MainActivity корректно завершается")
        } catch (e: Exception) {
            // Игнорируем ошибки при завершении
        }
        
        mainHandler.removeCallbacks(permissionRunnable)
        statisticsReceiver?.let {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
    }

    override fun onBackPressed() {
        if (isMonitoringServiceRunning())
            minimizeToBackground()
        else
            super.onBackPressed()
    }

    /* ------------------- Разрешение на точные будильники ------------------- */

    private fun showExactAlarmPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⏰ Требуется разрешение")
            .setMessage("Для стабильной работы мониторинга каждые 5 минут требуется разрешение на точные будильники.\n\n" +
                    "Это поможет обойти ограничение Android 15 на 6 часов работы фоновых сервисов.")
            .setPositiveButton("Предоставить") { _, _ ->
                ExactAlarmScheduler.requestExactAlarmPermission(this)
            }
            .setNegativeButton("Использовать 15 минут") { _, _ ->
                // Запускаем с обычным WorkManager
                startMonitoringWithWorkManager()
            }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun startMonitoringWithWorkManager() {
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("explicitly_stopped", false)
            .apply()

        KeepAliveWorker.scheduleWork(this)
        AlarmReceiver.scheduleAlarm(this)

        ui.btnStartMonitoring.isEnabled = false
        ui.btnStopMonitoring.isEnabled = true
        ui.statusText.text = "Мониторинг запущен (каждые 15 мин)"
        Toast.makeText(this, "Мониторинг запущен с интервалом 15 минут", Toast.LENGTH_SHORT).show()
    }

    /* ------------------- MIUI-предупреждение ------------------- */

    private fun checkMiuiAndShowWarning() {
        if (!MiuiUtils.isMiui() && !MiuiUtils.isChineseRom()) return

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("miui_warning_shown", false)) return

        val manufacturer = Build.MANUFACTURER
        val message = if (MiuiUtils.isMiui()) {
            "Обнаружена ${MiuiUtils.miuiVersion()}\n\n" +
                    "Для стабильной работы мониторинга требуется специальная настройка системы.\n\n" +
                    "Открыть инструкцию?"
        } else {
            "Обнаружена прошивка $manufacturer\n\n" +
                    "Для стабильной работы мониторинга может потребоваться специальная настройка.\n\n" +
                    "Открыть инструкцию?"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Требуется настройка")
            .setMessage(message)
            .setPositiveButton("Открыть") { _, _ ->
                startActivity(Intent(this, MiuiSetupActivity::class.java))
            }
            .setNegativeButton("Позже", null)
            .show()

        prefs.edit().putBoolean("miui_warning_shown", true).apply()
    }
    
    private fun checkForPreviousCrash() {
        try {
            val prefs = getSharedPreferences("crash_detection", Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("app_was_running", false)
            val lastStartTime = prefs.getLong("last_start_time", 0)
            val currentTime = System.currentTimeMillis()
            
            if (wasRunning && lastStartTime > 0) {
                val timeDiff = currentTime - lastStartTime
                if (timeDiff < 60000) { // Если прошло меньше минуты
                    FileLogger.w(TAG, "⚠️ ВОЗМОЖНЫЙ КРЭШ ОБНАРУЖЕН!")
                    FileLogger.w(TAG, "Приложение было запущено ${timeDiff}ms назад и неожиданно завершилось")
                    FileLogger.logAppState(this)
                } else {
                    FileLogger.i(TAG, "✅ Нормальный запуск приложения (прошло ${timeDiff}ms)")
                }
            } else {
                FileLogger.i(TAG, "🆕 Первый запуск или нормальное завершение")
            }
            
            // Отмечаем что приложение запущено
            prefs.edit()
                .putBoolean("app_was_running", true)
                .putLong("last_start_time", currentTime)
                .apply()
                
        } catch (e: Exception) {
            FileLogger.e(TAG, "Ошибка проверки предыдущего крэша: ${e.message}", e)
        }
    }
}