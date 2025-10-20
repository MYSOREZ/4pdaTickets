package ru.fourpda.tickets

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.io.BufferedReader

/**
 * Мини-утилиты для работы с MIUI/HyperOS и другими китайскими прошивками.
 * Реализовано только то, что реально нужно приложению:
 *  • распознавание прошивки/производителя;
 *  • переход в настройки автозапуска / энергосбережения;
 *  • проверка/запрос спец-разрешений;
 *  • безопасный запуск Activity из любого Context.
 */
object MiuiUtils {

    /* ---------- внутренние константы ---------- */

    private const val TAG = "MiuiUtils"

    // MIUI системные пропы (на HyperOS часто отсутствуют)
    private val MIUI_PROPS = listOf(
        "ro.miui.ui.version.code",
        "ro.miui.ui.version.name",
        "ro.miui.internal.storage"
    )

    // Пакеты, по которым можно узнать Xiaomi/MIUI/HyperOS
    private val XIAOMI_PACKAGES = listOf(
        "com.miui.securitycenter",
        "com.miui.powerkeeper"
    )

    private val PROP_CACHE = hashMapOf<String, String>()   // кэш getprop

    /* ---------- служебные функции ---------- */

    private fun getProp(prop: String): String =
        PROP_CACHE.getOrPut(prop) {
            try {
                Runtime.getRuntime().exec(arrayOf("getprop", prop))
                    .inputStream
                    .bufferedReader()
                    .use { br: BufferedReader -> br.readLine() ?: "" }
                    .trim()
            } catch (e: Exception) {
                Log.w(TAG, "getprop $prop error: ${e.message}")
                ""
            }
        }

    private fun hasAnyPackage(ctx: Context, pkgs: List<String>): Boolean {
        val pm = ctx.packageManager
        for (p in pkgs) {
            try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(p, 0)
                return true
            } catch (_: Exception) { /* ignore */ }
        }
        return false
    }

    private fun brandOrManufacturer(ctx: Context): Pair<String, String> {
        val man = (DebugUtils.getSimulateManufacturer(ctx) ?: Build.MANUFACTURER ?: "").lowercase()
        val br = (DebugUtils.getSimulateBrand(ctx) ?: Build.BRAND ?: "").lowercase()
        return man to br
    }

    /* ---------- публичные API: детект ОС/прошивки ---------- */

    /** true — устройство с MIUI по системным пропам */
    fun isMiuiProps(): Boolean = MIUI_PROPS.any { getProp(it).isNotBlank() }

    /** true — Xiaomi/HyperOS/MIUI по бренду/пакетам/пропам (с учётом debug-режима) */
    fun isXiaomiRom(context: Context): Boolean {
        if (DebugUtils.isSimulateMiui(context)) return true
        val (m, b) = brandOrManufacturer(context)
        val byBrand = listOf("xiaomi", "redmi", "poco").any { m.contains(it) || b.contains(it) }
        val byPkgs = hasAnyPackage(context, XIAOMI_PACKAGES)
        val byProps = isMiuiProps()
        return byBrand || byPkgs || byProps
    }

    /** true — устройство с MIUI (по пропам) */
    fun isMiui(): Boolean = isMiuiProps()

    /** true — устройство с MIUI/HyperOS (учёт debug-режима/бренда/пакетов) */
    fun isMiui(context: Context): Boolean =
        DebugUtils.isSimulateMiui(context) || isXiaomiRom(context)

    /** true — любой китайский бренд (по бренду) */
    fun isChineseRom(): Boolean {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        val brands = listOf(
            "xiaomi", "redmi", "poco",
            "oppo", "vivo", "realme",
            "oneplus", "huawei", "honor",
            "meizu", "zte", "nubia"
        )
        return brands.any { m.contains(it) || b.contains(it) }
    }

    /** true — любой китайский бренд (учёт debug-режима) */
    fun isChineseRom(context: Context): Boolean {
        if (DebugUtils.isSimulateChineseRom(context)) return true
        val (m, b) = brandOrManufacturer(context)
        val brands = listOf(
            "xiaomi", "redmi", "poco",
            "oppo", "vivo", "realme",
            "oneplus", "huawei", "honor",
            "meizu", "zte", "nubia"
        )
        return brands.any { m.contains(it) || b.contains(it) }
    }

    /** Человекочитаемое имя UI: MIUI/HyperOS/One UI/EMUI/ColorOS/Stock */
    fun miuiVersion(context: Context): String {
        // Если симулируется MIUI с версией
        DebugUtils.getSimulateMiuiVersion(context)?.let { return "MIUI $it" }

        // Сначала настоящие MIUI пропы
        val name = getProp("ro.miui.ui.version.name")
        val code = getProp("ro.miui.ui.version.code")
        if (name.isNotEmpty() || code.isNotEmpty()) {
            return if (name.isNotEmpty()) "MIUI $name" else "MIUI (code:$code)"
        }

        // Xiaomi без MIUI-пропов — считаем HyperOS
        if (isXiaomiRom(context)) return "HyperOS"

        // Иначе по производителю
        val man = (DebugUtils.getSimulateManufacturer(context) ?: Build.MANUFACTURER).lowercase()
        return when {
            man.equals("samsung", ignoreCase = true) -> "Samsung One UI"
            man.equals("huawei", ignoreCase = true)  -> "EMUI"
            man.equals("oppo", ignoreCase = true)    -> "ColorOS"
            man.equals("vivo", ignoreCase = true)    -> "Funtouch"
            man.equals("oneplus", ignoreCase = true) -> "OxygenOS"
            else -> "Stock Android"
        }
    }

    /** Упрощённая версия по пропам (сохранили для обратной совместимости) */
    fun miuiVersion(): String {
        val name = getProp("ro.miui.ui.version.name")
        val code = getProp("ro.miui.ui.version.code")
        return when {
            name.isNotEmpty() -> "MIUI $name"
            code.isNotEmpty() -> "MIUI (code:$code)"
            else -> "MIUI"
        }
    }

    /* ---------- переходы в настройки ---------- */

    /** открыть экран автозапуска; вернёт true, если удалось */
    fun openAutoStart(context: Context): Boolean = launchFirst(
        context, listOf(
            // Xiaomi / MIUI / HyperOS
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),

            // OPPO / ColorOS
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            // vivo / iQOO
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            ),
            // Huawei
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
        )
    )

    /** открыть экран энергосбережения; вернёт true, если удалось */
    fun openPower(context: Context): Boolean = launchFirst(
        context, listOf(
            // Xiaomi PowerKeeper
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager))
            },
            // OPPO
            Intent().setComponent(
                ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgauge.PowerUsageModelActivity"
                )
            ),
            // Samsung
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            ),
            // Общий экран игнорирования оптимизаций
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    )

    /** Открыть экран настроек приложения */
    fun openAppSettings(context: Context): Boolean = launchFirst(
        context, listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    )

    /* ---------- спец-разрешения ---------- */

    /**
     * Проверка важных спец-разрешений и статусов.
     * Ключи:
     *  - overlay: разрешение рисовать поверх окон (Android M+)
     *  - exact_alarm: можно ли ставить точные будильники (Android S+)
     *  - battery_ignoring: игнорирование оптимизации батареи (Android M+)
     *  - notifications: разрешены ли уведомления (Android 13+ влияет)
     */
    fun checkSpecialPerms(context: Context): Map<String, Boolean> = buildMap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            put("overlay", Settings.canDrawOverlays(context))
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            put("battery_ignoring", pm.isIgnoringBatteryOptimizations(context.packageName))
        } else {
            put("overlay", true)
            put("battery_ignoring", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            put("exact_alarm", am.canScheduleExactAlarms())
        } else {
            put("exact_alarm", true) // до S разрешение не требуется
        }
        put("notifications", NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    /**
     * Запрос критичных разрешений через системные экраны (по возможности).
     * NB: Запускает активити из произвольного контекста, поэтому добавляет FLAG_ACTIVITY_NEW_TASK.
     */
    fun requestSpecialPerms(context: Context) {
        // Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // Exact alarms
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // Battery optimization (спросить добавление в исключения)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                // Сначала точечный экран
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    // Общий список
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    /* ---------- приватные хелперы ---------- */

    /**
     * Пытается запустить первый доступный Intent из списка.
     * Всегда добавляет FLAG_ACTIVITY_NEW_TASK, т.к. может вызываться из Service/Receiver/Context.
     */
    private fun launchFirst(ctx: Context, intents: List<Intent>): Boolean {
        val pm = ctx.packageManager
        for (raw in intents) {
            val it = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    ctx.startActivity(it)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Intent failed: ${e.message}")
            }
        }
        return false
    }
}
