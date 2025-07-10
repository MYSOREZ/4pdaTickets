package ru.fourpda.tickets

import android.Manifest
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

        initWebView()
        initButtons()
        registerStatisticsReceiver()
        checkAuthenticationStatus()
    }

    private fun initWebView() {
        ui.authWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        val cm = CookieManager.getInstance().apply {
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

    private fun String?.isMainPage() = this in listOf("https://4pda.to/", "https://4pda.to", "https://4pda.to/forum/", "https://4pda.to/forum/index.php")

    private fun checkAuthenticationStatus() {
        val cm = CookieManager.getInstance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush()

        val cookies = cm.getCookie("https://4pda.to") ?: ""
        val isAuth = cookies.contains("member_id=") && cookies.contains("pass_hash=") && !cookies.contains("member_id=0")

        if (isAuth) {
            showMainInterface()
            loadTicketsPageInBackground()
        } else {
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
        schedulePermissionRequest()
    }

    private fun schedulePermissionRequest() {
        mainHandler.removeCallbacks(permissionRunnable)
        mainHandler.postDelayed(permissionRunnable, 800)
    }

    private fun requestRequiredPermissions() {
        Log.d(TAG, "🔐 Запрашиваем необходимые разрешения")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            } else {
                requestBatteryOptimization()
            }
        } else {
            requestBatteryOptimization()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQUEST_NOTIFICATION_PERMISSION) {
            Toast.makeText(this, if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) "Уведомления разрешены" else "Уведомления отклонены", Toast.LENGTH_SHORT).show()
            requestBatteryOptimization()
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
                    Toast.makeText(this, "Выберите 'Разрешить' для стабильной работы", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    Toast.makeText(this, "Найдите приложение и отключите ограничения", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disableAllButtons() {
        ui.btnStartMonitoring.isEnabled = false
        ui.btnStopMonitoring.isEnabled = false
        ui.btnMinimizeToNotification.isEnabled = false
        ui.btnSettings.isEnabled = false
        ui.btnLogout.isEnabled = false
        ui.btnExitApp.isEnabled = true
    }

    private fun enableButtonsAfterTicketsLoad() {
        val isRunning = isMonitoringServiceRunning()
        ui.btnStartMonitoring.isEnabled = !isRunning
        ui.btnStopMonitoring.isEnabled = isRunning
        ui.btnMinimizeToNotification.isEnabled = true
        ui.btnSettings.isEnabled = true
        ui.btnLogout.isEnabled = true
        ui.btnExitApp.isEnabled = true
        ui.statusText.text = if (isRunning) "Мониторинг запущен" else "Мониторинг остановлен"
    }

    private fun initButtons() = with(ui) {
        btnStartMonitoring.setOnClickListener { startMonitoringService() }
        btnStopMonitoring.setOnClickListener { stopMonitoringService() }
        btnMinimizeToNotification.setOnClickListener { minimizeToBackground() }
        btnSettings.setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        btnLogout.setOnClickListener { logout() }
        btnExitApp.setOnClickListener { exitApplication() }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, ForegroundMonitorService::class.java).apply { action = ForegroundMonitorService.ACTION_START_MONITORING }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        ui.btnStartMonitoring.isEnabled = false
        ui.btnStopMonitoring.isEnabled = true
        ui.statusText.text = "Мониторинг запущен"
        Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoringService() {
        startService(Intent(this, ForegroundMonitorService::class.java).apply { action = ForegroundMonitorService.ACTION_STOP_MONITORING })
        ui.btnStartMonitoring.isEnabled = true
        ui.btnStopMonitoring.isEnabled = false
        ui.statusText.text = "Мониторинг остановлен"
        Toast.makeText(this, "Мониторинг остановлен", Toast.LENGTH_SHORT).show()
    }

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
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isMainPage()) {
                    clearCookiesAndShowAuth()
                    return true
                }
                return false
            }
        }

        ui.authWebView.loadUrl("https://4pda.to/forum/index.php?act=Login&CODE=03")
        mainHandler.postDelayed({ clearCookiesAndShowAuth() }, 10000)
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
        }, 1000)
    }

    private fun exitApplication() {
        if (isMonitoringServiceRunning()) stopService(Intent(this, ForegroundMonitorService::class.java))
        finishAffinity()
    }

    private fun isMonitoringServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(50).any { it.service.className == ForegroundMonitorService::class.java.name }
    }

    private fun registerStatisticsReceiver() {
        statisticsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val processed = intent?.getIntExtra("processed_tickets", 0) ?: 0
                val unprocessed = intent?.getIntExtra("unprocessed_tickets", 0) ?: 0
                runOnUiThread {
                    if (isMonitoringServiceRunning()) ui.statusText.text = "Мониторинг: $processed/$unprocessed тикетов"
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statisticsReceiver!!, IntentFilter("TICKET_STATISTICS_UPDATE"))
    }

    override fun onResume() {
        super.onResume()
        if (ui.controlPanel.visibility == View.VISIBLE) enableButtonsAfterTicketsLoad()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(permissionRunnable)
        statisticsReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
    }

    override fun onBackPressed() {
        if (isMonitoringServiceRunning()) minimizeToBackground() else super.onBackPressed()
    }
}
