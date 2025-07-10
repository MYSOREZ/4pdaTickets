package ru.fourpda.tickets

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.fourpda.tickets.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        const val PREFS_NAME = "TicketMonitorSettings"
        const val KEY_REFRESH_INTERVAL = "refresh_interval"
        const val DEFAULT_REFRESH_INTERVAL = 30
        const val MIN_REFRESH_INTERVAL = 10
        const val MAX_REFRESH_INTERVAL = 300

        // Статический метод для получения текущего интервала
        fun getRefreshInterval(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
        }
    }

    private lateinit var ui: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        ui = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        Log.d(TAG, "=== ОТКРЫТЫ НАСТРОЙКИ (МИНИМАЛИСТИЧНЫЙ ДИЗАЙН) ===")

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        initSettings()
        initButtons()
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
        ui.textCurrentInterval.text = "Текущее значение: $intervalSeconds секунд"

        // Упрощенные подсказки
        val hint = when {
            intervalSeconds == DEFAULT_REFRESH_INTERVAL -> "(рекомендуемое)"
            intervalSeconds < 20 -> "(быстро - больше нагрузки на батарею)"
            intervalSeconds in 31..60 -> "(экономично)"
            intervalSeconds > 60 -> "(медленно - максимальная экономия)"
            else -> ""
        }
        ui.textIntervalHint.text = hint

        Log.d(TAG, "🔄 Обновлен интерфейс: $intervalSeconds сек $hint")
    }

    private fun saveRefreshInterval(intervalSeconds: Int) {
        // Сохраняем в SharedPreferences
        prefs.edit().putInt(KEY_REFRESH_INTERVAL, intervalSeconds).apply()

        // Показываем статус сохранения
        showStatusMessage("✅ Интервал сохранен: $intervalSeconds сек")

        // Кратковременное уведомление
        Toast.makeText(this, "Интервал: $intervalSeconds сек", Toast.LENGTH_SHORT).show()

        Log.d(TAG, "💾 Интервал сохранен: $intervalSeconds секунд")
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

    private fun resetToDefaults() {
        // Устанавливаем значение по умолчанию
        ui.seekBarRefreshInterval.progress = DEFAULT_REFRESH_INTERVAL
        updateIntervalText(DEFAULT_REFRESH_INTERVAL)
        saveRefreshInterval(DEFAULT_REFRESH_INTERVAL)

        // Показываем подтверждение
        showStatusMessage("✅ Сброшено до $DEFAULT_REFRESH_INTERVAL секунд")

        Toast.makeText(this,
            "Настройки сброшены до $DEFAULT_REFRESH_INTERVAL секунд",
            Toast.LENGTH_SHORT).show()

        Log.d(TAG, "🔄 Настройки успешно сброшены")
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
