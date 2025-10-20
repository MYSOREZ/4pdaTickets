package ru.fourpda.tickets

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.fourpda.tickets.databinding.ActivityDebugMenuBinding

class DebugMenuActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DebugMenuActivity"
    }

    private lateinit var ui: ActivityDebugMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        ui = ActivityDebugMenuBinding.inflate(layoutInflater)
        setContentView(ui.root)

        Log.d(TAG, "=== DEBUG MENU OPENED ===")

        initUI()
        updateDebugStatus()
    }

    private fun initUI() {
        // Кнопка для включения/выключения debug режима
        ui.btnToggleDebugMode.setOnClickListener {
            val isEnabled = !DebugUtils.isDebugModeEnabled(this)
            DebugUtils.setDebugMode(this, isEnabled)
            updateDebugStatus()
            Toast.makeText(this, "Debug режим ${if (isEnabled) "включен" else "выключен"}", Toast.LENGTH_SHORT).show()
        }

        // Предустановленные конфигурации
        ui.btnPresetMiui14.setOnClickListener {
            DebugUtils.applyPresetConfiguration(this, "miui_14")
            updateDebugStatus()
            Toast.makeText(this, "Применена симуляция MIUI 14", Toast.LENGTH_SHORT).show()
        }

        ui.btnPresetOppoColorOS.setOnClickListener {
            DebugUtils.applyPresetConfiguration(this, "oppo_coloros")
            updateDebugStatus()
            Toast.makeText(this, "Применена симуляция OPPO ColorOS", Toast.LENGTH_SHORT).show()
        }

        ui.btnPresetHuaweiEMUI.setOnClickListener {
            DebugUtils.applyPresetConfiguration(this, "huawei_emui")
            updateDebugStatus()
            Toast.makeText(this, "Применена симуляция Huawei EMUI", Toast.LENGTH_SHORT).show()
        }

        ui.btnPresetSamsungOneUI.setOnClickListener {
            DebugUtils.applyPresetConfiguration(this, "samsung_oneui")
            updateDebugStatus()
            Toast.makeText(this, "Применена симуляция Samsung One UI", Toast.LENGTH_SHORT).show()
        }

        // Кнопка сброса всех настроек
        ui.btnResetDebugSettings.setOnClickListener {
            DebugUtils.resetDebugSettings(this)
            updateDebugStatus()
            Toast.makeText(this, "Все debug настройки сброшены", Toast.LENGTH_SHORT).show()
        }

        // Кнопка тестирования MiuiUtils
        ui.btnTestMiuiUtils.setOnClickListener {
            testMiuiUtils()
        }

        // Кнопка отладки уведомлений
        ui.btnDebugNotifications.setOnClickListener {
            debugNotifications()
        }

        // Кнопка тестирования уведомлений
        ui.btnTestNotifications.setOnClickListener {
            testNotifications()
        }

        // Кнопка диагностики интентов
        ui.btnDebugIntents.setOnClickListener {
            debugIntents()
        }

        // Кнопка открытия MiuiSetupActivity
        ui.btnOpenMiuiSetup.setOnClickListener {
            openMiuiSetupActivity()
        }

        // Кнопка просмотра логов
        ui.btnViewLogs.setOnClickListener {
            viewLogFiles()
        }

        // Кнопка закрытия
        ui.btnClose.setOnClickListener {
            finish()
        }
    }

    private fun updateDebugStatus() {
        val isDebugEnabled = DebugUtils.isDebugModeEnabled(this)

        // Обновляем текст кнопки
        ui.btnToggleDebugMode.text = if (isDebugEnabled) "Выключить Debug режим" else "Включить Debug режим"

        // Показываем/скрываем дополнительные элементы
        val visibility = if (isDebugEnabled) View.VISIBLE else View.GONE
        ui.layoutDebugControls.visibility = visibility

        if (isDebugEnabled) {
            // Обновляем статус симуляции
            val status = DebugUtils.getDebugStatus(this)
            val statusText = buildString {
                append("🔧 Debug режим активен\n\n")
                append("Текущие настройки:\n")
                append("• MIUI: ${if (status["simulate_miui"] == true) "✅ Включена" else "❌ Выключена"}\n")
                append("• Китайская прошивка: ${if (status["simulate_chinese_rom"] == true) "✅ Включена" else "❌ Выключена"}\n")
                append("• Производитель: ${status["simulate_manufacturer"] ?: "не задан"}\n")
                append("• Бренд: ${status["simulate_brand"] ?: "не задан"}\n")
                append("• Версия MIUI: ${status["simulate_miui_version"] ?: "не задана"}\n\n")
                append("Реальные значения:\n")
                append("• Производитель: ${Build.MANUFACTURER}\n")
                append("• Бренд: ${Build.BRAND}\n")
                append("• Модель: ${Build.MODEL}")
            }
            ui.textDebugStatus.text = statusText
        } else {
            ui.textDebugStatus.text = "Debug режим выключен"
        }
    }

    private fun testMiuiUtils() {
        val context = this

        Log.d(TAG, "=== TESTING MIUI UTILS ===")

        val testResults = buildString {
            append("🧪 Результаты тестирования MiuiUtils:\n\n")

            val isMiui = MiuiUtils.isMiui(context)
            append("• isMiui(): ${if (isMiui) "✅ true" else "❌ false"} ${if (DebugUtils.isSimulateMiui(context)) "(симуляция)" else "(реальное)"}\n")

            val isChineseRom = MiuiUtils.isChineseRom(context)
            append("• isChineseRom(): ${if (isChineseRom) "✅ true" else "❌ false"} ${if (DebugUtils.isSimulateChineseRom(context)) "(симуляция)" else "(реальное)"}\n")

            val miuiVersion = MiuiUtils.miuiVersion(context)
            val debugMiuiVersion = DebugUtils.getSimulateMiuiVersion(context)
            val isSimulatingMiuiVersion = debugMiuiVersion != null && debugMiuiVersion.isNotEmpty()
            val isSimulatingAnyPlatform = DebugUtils.isDebugModeEnabled(context) &&
                (DebugUtils.getSimulateManufacturer(context) != null || isSimulatingMiuiVersion)
            append("• miuiVersion(): $miuiVersion ${if (isSimulatingAnyPlatform) "(симуляция)" else "(реальное)"}\n")

            val manufacturer = DebugUtils.getSimulateManufacturer(context) ?: Build.MANUFACTURER
            val brand = DebugUtils.getSimulateBrand(context) ?: Build.BRAND

            append("• Производитель: $manufacturer\n")
            append("• Бренд: $brand\n\n")

            val specialPerms = MiuiUtils.checkSpecialPerms(context)
            append("Специальные разрешения:\n")
            specialPerms.forEach { (perm, granted) ->
                append("• $perm: ${if (granted) "✅ Разрешено" else "❌ Запрещено"}\n")
            }
        }

        Log.d(TAG, testResults)
        ui.textTestResults.text = testResults
        ui.textTestResults.visibility = View.VISIBLE
        Toast.makeText(this, "Тестирование завершено, см. результаты ниже", Toast.LENGTH_SHORT).show()
    }

    private fun debugNotifications() {
        Log.d(TAG, "=== DEBUGGING NOTIFICATIONS ===")

        val debugInfo = NotificationDebugger.debugNotificationSystem(this)

        ui.textTestResults.text = debugInfo
        ui.textTestResults.visibility = View.VISIBLE

        Toast.makeText(this, "Отладка уведомлений завершена, см. результаты ниже", Toast.LENGTH_LONG).show()
    }

    private fun testNotifications() {
        Log.d(TAG, "=== TESTING NOTIFICATIONS ===")

        // Гарантируем наличие каналов перед отправкой тестовых уведомлений
        NotificationChannels.ensure(this)

        val results = buildString {
            append("🧪 Тестирование уведомлений:\n\n")

            val quickCheck = NotificationDebugger.quickCheck(this@DebugMenuActivity)
            append(quickCheck)
            append("\n")

            // Тестируем служебные уведомления (служебный канал)
            val serviceTest = NotificationDebugger.testNotification(
                this@DebugMenuActivity,
                NotificationChannels.SERVICE_CHANNEL_ID,
                "Служебное уведомление",
                "Тест канала службы мониторинга"
            )
            append("📱 Служебный канал: $serviceTest\n")

            // Тестируем уведомления о тикетах (канал тикетов)
            val ticketsTest = NotificationDebugger.testNotification(
                this@DebugMenuActivity,
                NotificationChannels.TICKETS_CHANNEL_ID,
                "Новый тикет",
                "Тест канала уведомлений о тикетах"
            )
            append("🎫 Канал тикетов: $ticketsTest\n")
        }

        Log.d(TAG, results)
        ui.textTestResults.text = results
        ui.textTestResults.visibility = View.VISIBLE
        Toast.makeText(this, "Тестовые уведомления отправлены!", Toast.LENGTH_SHORT).show()
    }

    private fun debugIntents() {
        Log.d(TAG, "=== DEBUGGING INTENTS ===")

        val debugInfo = IntentDebugger.debugFourpdaIntents(this)

        ui.textTestResults.text = debugInfo
        ui.textTestResults.visibility = View.VISIBLE

        Toast.makeText(this, "Диагностика интентов завершена, см. результаты ниже", Toast.LENGTH_LONG).show()
    }

    private fun openMiuiSetupActivity() {
        Log.d(TAG, "Opening MiuiSetupActivity...")
        try {
            val intent = android.content.Intent(this, MiuiSetupActivity::class.java)
            startActivity(intent)
            Toast.makeText(this, "Открываем инструкцию по настройке MIUI", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия MiuiSetupActivity: ${e.message}")
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun viewLogFiles() {
        Log.d(TAG, "=== VIEWING LOG FILES ===")

        val logFiles = FileLogger.getLogFiles()
        val logDirectory = FileLogger.getLogDirectory()

        val logInfo = buildString {
            append("📁 Лог-файлы приложения\n\n")

            if (logDirectory != null) {
                append("📂 Папка логов: $logDirectory\n\n")
            } else {
                append("❌ Папка логов недоступна\n\n")
            }

            if (logFiles.isNotEmpty()) {
                append("📄 Найдено файлов: ${logFiles.size}\n\n")

                logFiles.take(10).forEach { file ->
                    val sizeKB = file.length() / 1024
                    val lastModified = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(file.lastModified()))

                    append("• ${file.name}\n")
                    append("  Размер: ${sizeKB} KB\n")
                    append("  Изменен: $lastModified\n\n")
                }

                if (logFiles.size > 10) {
                    append("... и еще ${logFiles.size - 10} файлов\n\n")
                }

                val latestLog = logFiles.firstOrNull()
                if (latestLog != null) {
                    append("📋 Последние записи из ${latestLog.name}:\n")
                    append("─".repeat(40) + "\n")

                    try {
                        val lines = latestLog.readLines().takeLast(20)

                        val criticalLines = lines.filter { line ->
                            line.contains("💥 КРИТИЧЕСКАЯ ОШИБКА") ||
                                    line.contains("onTimeout") ||
                                    line.contains("ВОЗМОЖНЫЙ КРЭШ") ||
                                    line.contains("ERROR") ||
                                    line.contains("Exception")
                        }

                        if (criticalLines.isNotEmpty()) {
                            append("🚨 НАЙДЕНЫ КРИТИЧЕСКИЕ ОШИБКИ:\n")
                            criticalLines.forEach { line ->
                                append("⚠️ $line\n")
                            }
                            append("─".repeat(40) + "\n")
                        }

                        lines.forEach { line ->
                            append("$line\n")
                        }
                    } catch (e: Exception) {
                        append("❌ Ошибка чтения файла: ${e.message}\n")
                    }
                }
            } else {
                append("📄 Лог-файлы не найдены\n")
                append("💡 Возможные причины:\n")
                append("• Приложение только что установлено\n")
                append("• Нет разрешения на запись\n")
                append("• Внешнее хранилище недоступно\n")
            }
        }

        Log.d(TAG, logInfo)
        ui.textTestResults.text = logInfo
        ui.textTestResults.visibility = View.VISIBLE
        Toast.makeText(this, "Информация о лог-файлах загружена", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateDebugStatus()
    }
}
