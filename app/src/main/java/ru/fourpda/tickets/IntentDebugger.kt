package ru.fourpda.tickets

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Утилита для отладки проблем с интентами в разных версиях Android и MIUI/HyperOS
 */
object IntentDebugger {
    private const val TAG = "IntentDebugger"
    private const val FOURPDA_PACKAGE = "ru.fourpda.client"

    /**
     * Полная диагностика работы интентов 4PDA
     */
    fun debugFourpdaIntents(context: Context): String {
        val debug = StringBuilder()
        debug.appendLine("=== ДИАГНОСТИКА ИНТЕНТОВ 4PDA ===")
        debug.appendLine("📱 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        debug.appendLine("📱 Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        
        // Проверяем MIUI/HyperOS
        val isMiui = MiuiUtils.isMiui(context)
        val miuiVersion = if (isMiui) MiuiUtils.miuiVersion(context) else null
        debug.appendLine("📱 MIUI/HyperOS: ${if (isMiui) "Да ($miuiVersion)" else "Нет"}")
        debug.appendLine()

        // 1. Проверяем установлено ли приложение 4PDA
        val packageManager = context.packageManager
        val is4pdaInstalled = try {
            packageManager.getPackageInfo(FOURPDA_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        
        debug.appendLine("📦 Приложение 4PDA установлено: ${if (is4pdaInstalled) "✅ Да" else "❌ Нет"}")
        
        if (!is4pdaInstalled) {
            debug.appendLine("❌ ПРОБЛЕМА: Приложение 4PDA не установлено!")
            return debug.toString()
        }

        // 2. Проверяем информацию о пакете 4PDA
        try {
            val packageInfo = packageManager.getPackageInfo(FOURPDA_PACKAGE, PackageManager.GET_ACTIVITIES)
            debug.appendLine("📦 Версия 4PDA: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
            debug.appendLine("📦 Активностей в 4PDA: ${packageInfo.activities?.size ?: 0}")
        } catch (e: Exception) {
            debug.appendLine("❌ Ошибка получения информации о пакете: ${e.message}")
        }
        debug.appendLine()

        // 3. Тестируем различные типы интентов
        debug.appendLine("=== ТЕСТИРОВАНИЕ ИНТЕНТОВ ===")
        
        // Тест 1: Главная страница тикетов
        val ticketsUrl = "https://4pda.to/forum/index.php?act=ticket"
        debug.appendLine("🔗 URL тикетов: $ticketsUrl")
        testIntent(context, ticketsUrl, "Главная страница тикетов", debug)
        
        // Тест 2: Конкретный тикет
        val specificTicketUrl = "https://4pda.to/forum/index.php?act=ticket&s=thread&t_id=12345"
        debug.appendLine("🔗 URL конкретного тикета: $specificTicketUrl")
        testIntent(context, specificTicketUrl, "Конкретный тикет", debug)
        
        // Тест 3: Простая ссылка на 4PDA
        val simpleUrl = "https://4pda.to/"
        debug.appendLine("🔗 Простая ссылка 4PDA: $simpleUrl")
        testIntent(context, simpleUrl, "Главная страница 4PDA", debug)

        debug.appendLine()
        debug.appendLine("=== АНАЛИЗ INTENT FILTERS ===")
        analyzeIntentFilters(context, debug)

        return debug.toString()
    }

    /**
     * Тестирует конкретный URL с разными подходами
     */
    private fun testIntent(context: Context, url: String, description: String, debug: StringBuilder) {
        debug.appendLine("\n--- Тест: $description ---")
        
        val packageManager = context.packageManager
        
        // Подход 1: Специфический интент для 4PDA
        val fourpdaIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(FOURPDA_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val resolveInfo1 = packageManager.resolveActivity(fourpdaIntent, PackageManager.MATCH_DEFAULT_ONLY)
        debug.appendLine("🎯 Специфический интент (setPackage): ${if (resolveInfo1 != null) "✅ Работает" else "❌ Не работает"}")
        if (resolveInfo1 != null) {
            debug.appendLine("   📋 Активность: ${resolveInfo1.activityInfo.name}")
        }

        // Подход 2: Универсальный интент (без setPackage)
        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val resolveInfos = packageManager.queryIntentActivities(genericIntent, PackageManager.MATCH_DEFAULT_ONLY)
        debug.appendLine("🌐 Универсальный интент: найдено ${resolveInfos.size} обработчиков")
        
        val fourpdaHandler = resolveInfos.find { it.activityInfo.packageName == FOURPDA_PACKAGE }
        if (fourpdaHandler != null) {
            debug.appendLine("   ✅ 4PDA может обработать URL")
            debug.appendLine("   📋 Активность: ${fourpdaHandler.activityInfo.name}")
        } else {
            debug.appendLine("   ❌ 4PDA не найден среди обработчиков")
        }

        // Подход 3: Явное указание компонента
        try {
            val resolveInfo = packageManager.resolveActivity(fourpdaIntent, 0)
            if (resolveInfo != null) {
                val explicitIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setClassName(FOURPDA_PACKAGE, resolveInfo.activityInfo.name)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                debug.appendLine("🎯 Явный компонент: ${resolveInfo.activityInfo.name}")
            }
        } catch (e: Exception) {
            debug.appendLine("❌ Ошибка создания явного интента: ${e.message}")
        }

        // Подход 4: Проверка с разными флагами
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val resolveInfo4 = packageManager.resolveActivity(fourpdaIntent, PackageManager.MATCH_ALL)
            debug.appendLine("🔍 С флагом MATCH_ALL: ${if (resolveInfo4 != null) "✅ Работает" else "❌ Не работает"}")
        }
    }

    /**
     * Анализирует intent filters приложения 4PDA
     */
    private fun analyzeIntentFilters(context: Context, debug: StringBuilder) {
        val packageManager = context.packageManager
        
        try {
            val packageInfo = packageManager.getPackageInfo(FOURPDA_PACKAGE, PackageManager.GET_ACTIVITIES)
            packageInfo.activities?.forEach { activityInfo ->
                debug.appendLine("📋 Активность: ${activityInfo.name}")
                debug.appendLine("   Экспортируемая: ${activityInfo.exported}")
                debug.appendLine("   Включена: ${activityInfo.enabled}")
            }
        } catch (e: Exception) {
            debug.appendLine("❌ Ошибка анализа активностей: ${e.message}")
        }

        // Проверяем intent filters для VIEW действий
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://4pda.to/"))
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        
        debug.appendLine("\n📱 Все обработчики https://4pda.to/:")
        resolveInfos.forEach { resolveInfo ->
            if (resolveInfo.activityInfo.packageName == FOURPDA_PACKAGE) {
                debug.appendLine("✅ ${resolveInfo.activityInfo.packageName}: ${resolveInfo.activityInfo.name}")
                debug.appendLine("   Приоритет: ${resolveInfo.priority}")
                debug.appendLine("   Экспортируемая: ${resolveInfo.activityInfo.exported}")
            }
        }
    }

    /**
     * Создает интент с расширенной диагностикой для HyperOS/MIUI
     */
    fun createRobustFourpdaIntent(context: Context, url: String): Intent {
        Log.d(TAG, "🔧 Создаем робастный интент для: $url")
        
        val packageManager = context.packageManager
        
        // Стратегия 1: Стандартный подход с setPackage
        val fourpdaIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(FOURPDA_PACKAGE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val resolveInfo = packageManager.resolveActivity(fourpdaIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo != null) {
            Log.d(TAG, "✅ Стандартный подход работает")
            return fourpdaIntent
        }
        
        // Стратегия 2: Попробуем найти подходящую активность вручную
        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val resolveInfos = packageManager.queryIntentActivities(genericIntent, PackageManager.MATCH_ALL)
        
        val fourpdaHandler = resolveInfos.find { it.activityInfo.packageName == FOURPDA_PACKAGE }
        if (fourpdaHandler != null) {
            Log.d(TAG, "✅ Найден обработчик через queryIntentActivities: ${fourpdaHandler.activityInfo.name}")
            return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setClassName(FOURPDA_PACKAGE, fourpdaHandler.activityInfo.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
        
        // Стратегия 3: Попробуем запустить главную активность 4PDA с данными
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(FOURPDA_PACKAGE)
            if (launchIntent != null) {
                Log.d(TAG, "✅ Используем основную активность с данными")
                return launchIntent.apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения launch intent: ${e.message}")
        }
        
        // Стратегия 4: Fallback - обычный браузерный интент
        Log.d(TAG, "❌ Все стратегии 4PDA не сработали, используем браузер")
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    }

    /**
     * Быстрая проверка - может ли приложение 4PDA обработать URL
     */
    fun canFourpdaHandleUrl(context: Context, url: String): Boolean {
        val packageManager = context.packageManager
        
        // Проверка 1: setPackage
        val fourpdaIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(FOURPDA_PACKAGE)
        }
        
        if (packageManager.resolveActivity(fourpdaIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            return true
        }
        
        // Проверка 2: queryIntentActivities
        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val resolveInfos = packageManager.queryIntentActivities(genericIntent, PackageManager.MATCH_ALL)
        
        return resolveInfos.any { it.activityInfo.packageName == FOURPDA_PACKAGE }
    }
}
