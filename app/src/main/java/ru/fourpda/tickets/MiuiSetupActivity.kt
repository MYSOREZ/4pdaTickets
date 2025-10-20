package ru.fourpda.tickets

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton

class MiuiSetupActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnBatterySettings: MaterialButton
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_miui_setup)

        // Инициализация виджетов
        statusText = findViewById(R.id.textStatus)
        btnBatterySettings = findViewById(R.id.btnBatterySettings)

        // Настройка кнопок
        btnBatterySettings.setOnClickListener { openBatteryOptimizationSettings() }

        findViewById<MaterialButton>(R.id.btnAutoStartSettings).setOnClickListener {
            openAutoStartSettings()
        }

        findViewById<MaterialButton>(R.id.btnCheckStatus).setOnClickListener {
            checkCurrentStatus()
            Toast.makeText(this, "Статус обновлен", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnBackToMain).setOnClickListener { finish() }

        // Проверяем текущий статус при открытии
        checkCurrentStatus()
    }

    private fun openBatteryOptimizationSettings() {
        try {
            // Точечный запрос на игнор оптимизаций для нашего пакета
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Не удалось открыть точечный экран, пробуем общий список…",
                Toast.LENGTH_SHORT
            ).show()
            try {
                // Общий экран списка оптимизаций
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Экран оптимизации недоступен, открываю настройки приложения",
                    Toast.LENGTH_SHORT
                ).show()
                openAppSettings()
            }
        }
    }

    private fun openAutoStartSettings() {
        // Для Xiaomi/HyperOS/MIUI и других китайских прошивок — пробуем фирменные экраны
        val opened = MiuiUtils.openAutoStart(this)
        if (!opened) {
            // Фолбэк: откроем настройки приложения
            openAppSettings()
            Toast.makeText(
                this,
                "Не удалось открыть экран автозапуска. Откройте разрешения в настройках приложения.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun checkCurrentStatus() {
        val sb = StringBuilder()

        // Имя оболочки/версии (MIUI/HyperOS/и т.д.)
        val uiName = MiuiUtils.miuiVersion(this)
        sb.append("Система: ").append(uiName).append('\n')

        // Игнор оптимизации батареи
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        sb.append(if (batteryOk) "✅ Оптимизация батареи отключена\n" else "❌ Оптимизация батареи включена\n")
        btnBatterySettings.isEnabled = !batteryOk

        // Разрешение на точные будильники
        val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
        sb.append(if (exactOk) "✅ Разрешены точные будильники\n" else "❌ Точные будильники не разрешены\n")

        // Разрешение на уведомления
        val notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        sb.append(if (notifOk) "✅ Уведомления включены\n" else "❌ Уведомления отключены\n")

        // Признак включённого мониторинга (без опоры на «живой» сервис)
        val monitoringEnabled = prefs.getBoolean("enabled", false)
        sb.append(if (monitoringEnabled) "✅ Мониторинг включён\n" else "⚠️ Мониторинг выключен\n")

        statusText.text = sb.toString()
    }

    companion object {
        const val TAG = "MiuiSetup"
    }
}
