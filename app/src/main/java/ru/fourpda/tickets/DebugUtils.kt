package ru.fourpda.tickets

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Debug-утилиты для симуляции различных платформ и прошивок
 */
object DebugUtils {
    
    private const val TAG = "DebugUtils"
    private const val DEBUG_PREFS_NAME = "DebugSettings"
    
    // Ключи для настроек debug
    private const val KEY_DEBUG_MODE = "debug_mode_enabled"
    private const val KEY_SIMULATE_MIUI = "simulate_miui"
    private const val KEY_SIMULATE_CHINESE_ROM = "simulate_chinese_rom"
    private const val KEY_SIMULATE_MANUFACTURER = "simulate_manufacturer"
    private const val KEY_SIMULATE_BRAND = "simulate_brand"
    private const val KEY_SIMULATE_MIUI_VERSION = "simulate_miui_version"
    
    private fun getDebugPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(DEBUG_PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // Проверка включен ли debug режим
    fun isDebugModeEnabled(context: Context): Boolean {
        return getDebugPrefs(context).getBoolean(KEY_DEBUG_MODE, false)
    }
    
    // Включение/выключение debug режима
    fun setDebugMode(context: Context, enabled: Boolean) {
        getDebugPrefs(context).edit()
            .putBoolean(KEY_DEBUG_MODE, enabled)
            .apply()
        Log.d(TAG, "Debug mode ${if (enabled) "enabled" else "disabled"}")
    }
    
    // Симуляция MIUI
    fun setSimulateMiui(context: Context, simulate: Boolean) {
        getDebugPrefs(context).edit()
            .putBoolean(KEY_SIMULATE_MIUI, simulate)
            .apply()
        Log.d(TAG, "Simulate MIUI: $simulate")
    }
    
    fun isSimulateMiui(context: Context): Boolean {
        return isDebugModeEnabled(context) && 
               getDebugPrefs(context).getBoolean(KEY_SIMULATE_MIUI, false)
    }
    
    // Симуляция китайской прошивки
    fun setSimulateChineseRom(context: Context, simulate: Boolean) {
        getDebugPrefs(context).edit()
            .putBoolean(KEY_SIMULATE_CHINESE_ROM, simulate)
            .apply()
        Log.d(TAG, "Simulate Chinese ROM: $simulate")
    }
    
    fun isSimulateChineseRom(context: Context): Boolean {
        return isDebugModeEnabled(context) && 
               getDebugPrefs(context).getBoolean(KEY_SIMULATE_CHINESE_ROM, false)
    }
    
    // Симуляция производителя
    fun setSimulateManufacturer(context: Context, manufacturer: String) {
        getDebugPrefs(context).edit()
            .putString(KEY_SIMULATE_MANUFACTURER, manufacturer)
            .apply()
        Log.d(TAG, "Simulate manufacturer: $manufacturer")
    }
    
    fun getSimulateManufacturer(context: Context): String? {
        return if (isDebugModeEnabled(context)) {
            getDebugPrefs(context).getString(KEY_SIMULATE_MANUFACTURER, null)
        } else null
    }
    
    // Симуляция бренда
    fun setSimulateBrand(context: Context, brand: String) {
        getDebugPrefs(context).edit()
            .putString(KEY_SIMULATE_BRAND, brand)
            .apply()
        Log.d(TAG, "Simulate brand: $brand")
    }
    
    fun getSimulateBrand(context: Context): String? {
        return if (isDebugModeEnabled(context)) {
            getDebugPrefs(context).getString(KEY_SIMULATE_BRAND, null)
        } else null
    }
    
    // Симуляция версии MIUI
    fun setSimulateMiuiVersion(context: Context, version: String) {
        getDebugPrefs(context).edit()
            .putString(KEY_SIMULATE_MIUI_VERSION, version)
            .apply()
        Log.d(TAG, "Simulate MIUI version: $version")
    }
    
    fun getSimulateMiuiVersion(context: Context): String? {
        return if (isDebugModeEnabled(context)) {
            getDebugPrefs(context).getString(KEY_SIMULATE_MIUI_VERSION, null)
        } else null
    }
    
    // Сброс всех debug настроек
    fun resetDebugSettings(context: Context) {
        getDebugPrefs(context).edit().clear().apply()
        Log.d(TAG, "All debug settings cleared")
    }
    
    // Получение текущего статуса debug режима
    fun getDebugStatus(context: Context): Map<String, Any> {
        val prefs = getDebugPrefs(context)
        return mapOf(
            "debug_mode" to prefs.getBoolean(KEY_DEBUG_MODE, false),
            "simulate_miui" to prefs.getBoolean(KEY_SIMULATE_MIUI, false),
            "simulate_chinese_rom" to prefs.getBoolean(KEY_SIMULATE_CHINESE_ROM, false),
            "simulate_manufacturer" to (prefs.getString(KEY_SIMULATE_MANUFACTURER, "") ?: ""),
            "simulate_brand" to (prefs.getString(KEY_SIMULATE_BRAND, "") ?: ""),
            "simulate_miui_version" to (prefs.getString(KEY_SIMULATE_MIUI_VERSION, "") ?: "")
        )
    }
    
    // Предустановленные конфигурации для быстрого тестирования
    fun applyPresetConfiguration(context: Context, preset: String) {
        val editor = getDebugPrefs(context).edit()
        
        when (preset) {
            "miui_14" -> {
                editor.putBoolean(KEY_DEBUG_MODE, true)
                editor.putBoolean(KEY_SIMULATE_MIUI, true)
                editor.putBoolean(KEY_SIMULATE_CHINESE_ROM, true)
                editor.putString(KEY_SIMULATE_MANUFACTURER, "Xiaomi")
                editor.putString(KEY_SIMULATE_BRAND, "Xiaomi")
                editor.putString(KEY_SIMULATE_MIUI_VERSION, "14")
            }
            "oppo_coloros" -> {
                editor.putBoolean(KEY_DEBUG_MODE, true)
                editor.putBoolean(KEY_SIMULATE_MIUI, false)
                editor.putBoolean(KEY_SIMULATE_CHINESE_ROM, true)
                editor.putString(KEY_SIMULATE_MANUFACTURER, "OPPO")
                editor.putString(KEY_SIMULATE_BRAND, "OPPO")
                editor.remove(KEY_SIMULATE_MIUI_VERSION) // Удаляем, чтобы было null
            }
            "huawei_emui" -> {
                editor.putBoolean(KEY_DEBUG_MODE, true)
                editor.putBoolean(KEY_SIMULATE_MIUI, false)
                editor.putBoolean(KEY_SIMULATE_CHINESE_ROM, true)
                editor.putString(KEY_SIMULATE_MANUFACTURER, "Huawei")
                editor.putString(KEY_SIMULATE_BRAND, "Huawei")
                editor.remove(KEY_SIMULATE_MIUI_VERSION) // Удаляем, чтобы было null
            }
            "samsung_oneui" -> {
                editor.putBoolean(KEY_DEBUG_MODE, true)
                editor.putBoolean(KEY_SIMULATE_MIUI, false)
                editor.putBoolean(KEY_SIMULATE_CHINESE_ROM, false)
                editor.putString(KEY_SIMULATE_MANUFACTURER, "Samsung")
                editor.putString(KEY_SIMULATE_BRAND, "Samsung")
                editor.remove(KEY_SIMULATE_MIUI_VERSION) // Удаляем, чтобы было null
            }
            "disable_all" -> {
                editor.clear()
            }
        }
        
        editor.apply()
        Log.d(TAG, "Applied preset configuration: $preset")
    }
}
