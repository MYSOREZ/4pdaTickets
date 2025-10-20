package ru.fourpda.tickets

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.fourpda.tickets.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        const val PREFS_NAME = "TicketMonitorSettings"
        const val KEY_REFRESH_INTERVAL = "refresh_interval"
        const val DEFAULT_REFRESH_INTERVAL = 300
        const val MIN_REFRESH_INTERVAL = 60
        const val MAX_REFRESH_INTERVAL = 600

        // Статический метод для получения текущего интервала
        fun getRefreshInterval(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
        }


    }

    private lateinit var ui: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    
    // Для отслеживания тройного клика
    private var lastClickTime = 0L
    private var clickCount = 0
    private val multiClickInterval = 500L // Интервал между кликами в миллисекундах

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        ui = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        Log.d(TAG, "=== ОТКРЫТЫ НАСТРОЙКИ (МИНИМАЛИСТИЧНЫЙ ДИЗАЙН) ===")

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        initSettings()
        initButtons()
        initDebugTitleClick()
    }

    private fun initSettings() {
        val currentInterval = prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)

        Log.d(TAG, "📋 Инициализация настроек. Текущий интервал: $currentInterval сек")

        // Настройка SeekBar
        ui.seekBarRefreshInterval.apply {
            min = MIN_REFRESH_INTERVAL
            max = MAX_REFRESH_INTERVAL
            progress = currentInterval
        }

        // Отображаем текущее значение
        updateIntervalText(currentInterval)

        // Обработчик изменения SeekBar
        ui.seekBarRefreshInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateIntervalText(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Скрываем статус при начале изменения
                hideStatusMessage()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val newInterval = seekBar?.progress ?: DEFAULT_REFRESH_INTERVAL
                saveRefreshInterval(newInterval)
            }
        })



        Log.d(TAG, "✅ Настройки инициализированы успешно")
    }

    private fun updateIntervalText(intervalSeconds: Int) {
        // Форматируем время
        val displayText = when {
            intervalSeconds >= 60 -> {
                val minutes = intervalSeconds / 60
                val seconds = intervalSeconds % 60
                if (seconds == 0) {
                    "Текущее значение: $minutes мин"
                } else {
                    "Текущее значение: $minutes мин $seconds сек"
                }
            }
            else -> "Текущее значение: $intervalSeconds секунд"
        }
        ui.textCurrentInterval.text = displayText

        // Обновленные подсказки
        val hint = when {
            intervalSeconds == DEFAULT_REFRESH_INTERVAL -> "(рекомендуемое)"
            intervalSeconds < 60 -> "(быстро - больше нагрузки на батарею)"
            intervalSeconds in 60..180 -> "(сбалансированно)"
            intervalSeconds in 181..300 -> "(экономично)"
            intervalSeconds > 300 -> "(максимальная экономия батареи)"
            else -> ""
        }
        ui.textIntervalHint.text = hint

        Log.d(TAG, "🔄 Обновлен интерфейс: $displayText $hint")
    }

    private fun saveRefreshInterval(intervalSeconds: Int) {
        // Сохраняем в SharedPreferences
        prefs.edit().putInt(KEY_REFRESH_INTERVAL, intervalSeconds).apply()

        // Форматируем время для отображения
        val timeText = formatTime(intervalSeconds)

        // Показываем статус сохранения
        showStatusMessage("✅ Интервал сохранен: $timeText")

        // Кратковременное уведомление
        Toast.makeText(this, "Интервал: $timeText", Toast.LENGTH_SHORT).show()

        Log.d(TAG, "💾 Интервал сохранен: $intervalSeconds секунд ($timeText)")
    }

    private fun initButtons() = with(ui) {

        // Кнопка возврата в главное меню
        btnBackToMain.setOnClickListener {
            Log.d(TAG, "🏠 Возврат в главное меню")
            finish()
        }

        // Кнопка сброса настроек
        btnResetToDefault.setOnClickListener {
            Log.d(TAG, "🔄 Сброс настроек по умолчанию")
            resetToDefaults()
        }
    }

    private fun initDebugTitleClick() {
        // Обработчик тройного клика по заголовку настроек
        ui.textSettingsTitle.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < multiClickInterval) {
                clickCount++
                if (clickCount == 3) {
                    clickCount = 0 // Сбрасываем счётчик
                    openDebugMenu()
                }
            } else {
                clickCount = 1 // Начинаем сначала
            }
            lastClickTime = currentTime
        }
    }

    private fun openDebugMenu() {
        // Запускаем DebugMenuActivity
        Log.d(TAG, "🔧 Открытие Debug меню")
        startActivity(Intent(this, DebugMenuActivity::class.java))
    }



    private fun resetToDefaults() {
        // Устанавливаем значение по умолчанию
        ui.seekBarRefreshInterval.progress = DEFAULT_REFRESH_INTERVAL
        updateIntervalText(DEFAULT_REFRESH_INTERVAL)
        saveRefreshInterval(DEFAULT_REFRESH_INTERVAL)

        // Форматируем время для отображения
        val timeText = formatTime(DEFAULT_REFRESH_INTERVAL)

        // Показываем подтверждение
        showStatusMessage("✅ Сброшено до $timeText")

        Toast.makeText(this,
            "Настройки сброшены до $timeText",
            Toast.LENGTH_SHORT).show()

        Log.d(TAG, "🔄 Настройки успешно сброшены до $DEFAULT_REFRESH_INTERVAL секунд ($timeText)")
    }

    private fun showStatusMessage(message: String) {
        ui.textSaveStatus.apply {
            text = message
            visibility = View.VISIBLE
        }

        // Автоматически скрываем через 3 секунды
        ui.textSaveStatus.postDelayed({
            hideStatusMessage()
        }, 3000)
    }

    private fun hideStatusMessage() {
        ui.textSaveStatus.visibility = View.GONE
    }
    
    private fun formatTime(seconds: Int): String {
        return when {
            seconds >= 60 -> {
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                if (remainingSeconds == 0) {
                    "$minutes мин"
                } else {
                    "$minutes мин $remainingSeconds сек"
                }
            }
            else -> "$seconds сек"
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 SettingsActivity возобновлена")

        // Обновляем отображение при возврате
        val currentInterval = prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
        ui.seekBarRefreshInterval.progress = currentInterval
        updateIntervalText(currentInterval)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "📱 SettingsActivity приостановлена")
        hideStatusMessage()
    }

    override fun onBackPressed() {
        Log.d(TAG, "🔙 Нажата системная кнопка 'Назад'")
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 SettingsActivity уничтожена")
    }
}
