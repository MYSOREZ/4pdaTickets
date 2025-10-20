package ru.fourpda.tickets

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Система логирования в файл для отладки работы приложения
 * Сохраняет логи в /storage/emulated/0/4pdaTickets/logs/
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_DIR = "4pdaTickets"
    private const val LOG_SUBDIR = "logs"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5 MB
    private const val MAX_LOG_FILES = 5
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private var isInitialized = false
    private var logDir: File? = null
    
    fun init(context: Context) {
        try {
            if (isInitialized) return
            
            // Проверяем доступность внешнего хранилища
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                Log.w(TAG, "⚠️ Внешнее хранилище недоступно")
                return
            }
            
            // Проверяем разрешения на запись
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == 
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    Log.w(TAG, "⚠️ Нет разрешения на запись во внешнее хранилище")
                    return
                }
            }
            
            // Создаем папку для логов
            val externalStorage = Environment.getExternalStorageDirectory()
            logDir = File(externalStorage, "$LOG_DIR/$LOG_SUBDIR")
            
            if (!logDir!!.exists()) {
                val created = logDir!!.mkdirs()
                if (created) {
                    Log.d(TAG, "✅ Создана папка для логов: ${logDir!!.absolutePath}")
                } else {
                    Log.e(TAG, "❌ Не удалось создать папку для логов: ${logDir!!.absolutePath}")
                    Log.e(TAG, "Проверьте разрешения и доступность внешнего хранилища")
                    return
                }
            }
            
            isInitialized = true
            
            // Очищаем старые логи при инициализации
            cleanOldLogs()
            
            // Записываем стартовое сообщение
            writeLog("SYSTEM", "📱 FileLogger инициализирован. Папка: ${logDir!!.absolutePath}")
            writeLog("SYSTEM", "🚀 Приложение запущено. Версия Android: ${android.os.Build.VERSION.RELEASE}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка инициализации FileLogger: ${e.message}")
        }
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeLog(tag, "DEBUG: $message")
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLog(tag, "INFO: $message")
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeLog(tag, "WARN: $message")
    }
    
    fun e(tag: String, message: String) {
        Log.e(tag, message)
        writeLog(tag, "ERROR: $message")
    }
    
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
        writeLog(tag, "ERROR: $message\n${throwable.stackTraceToString()}")
    }
    
    private fun writeLog(tag: String, message: String) {
        if (!isInitialized || logDir == null) return
        
        try {
            val currentDate = Date()
            val fileName = "4pda_tickets_${fileNameFormat.format(currentDate)}.log"
            val logFile = File(logDir, fileName)
            
            // Проверяем размер файла
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                rotateLogFile(logFile)
            }
            
            val timestamp = dateFormat.format(currentDate)
            val logEntry = "[$timestamp] [$tag] $message\n"
            
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
                writer.flush()
            }
            
        } catch (e: Exception) {
            // Не логируем ошибки логирования в файл, чтобы избежать рекурсии
            Log.e(TAG, "Ошибка записи в лог-файл: ${e.message}")
        }
    }
    
    private fun rotateLogFile(currentFile: File) {
        try {
            val baseName = currentFile.nameWithoutExtension
            val extension = currentFile.extension
            
            // Сдвигаем существующие файлы
            for (i in MAX_LOG_FILES - 1 downTo 1) {
                val oldFile = File(currentFile.parent, "${baseName}_$i.$extension")
                val newFile = File(currentFile.parent, "${baseName}_${i + 1}.$extension")
                
                if (oldFile.exists()) {
                    if (i == MAX_LOG_FILES - 1) {
                        oldFile.delete() // Удаляем самый старый
                    } else {
                        oldFile.renameTo(newFile)
                    }
                }
            }
            
            // Переименовываем текущий файл
            val rotatedFile = File(currentFile.parent, "${baseName}_1.$extension")
            currentFile.renameTo(rotatedFile)
            
            Log.d(TAG, "🔄 Лог-файл ротирован: ${rotatedFile.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка ротации лог-файла: ${e.message}")
        }
    }
    
    private fun cleanOldLogs() {
        try {
            logDir?.listFiles()?.let { files ->
                val logFiles = files.filter { it.name.endsWith(".log") }
                    .sortedByDescending { it.lastModified() }
                
                // Оставляем только последние MAX_LOG_FILES файлов
                if (logFiles.size > MAX_LOG_FILES) {
                    logFiles.drop(MAX_LOG_FILES).forEach { file ->
                        if (file.delete()) {
                            Log.d(TAG, "🗑️ Удален старый лог-файл: ${file.name}")
                        }
                    }
                }
                
                // Удаляем файлы старше 7 дней
                val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                logFiles.filter { it.lastModified() < weekAgo }.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "🗑️ Удален устаревший лог-файл: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки старых логов: ${e.message}")
        }
    }
    
    fun getLogFiles(): List<File> {
        return try {
            logDir?.listFiles()?.filter { it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения списка лог-файлов: ${e.message}")
            emptyList()
        }
    }
    
    fun getLogDirectory(): String? {
        return logDir?.absolutePath
    }
    
    /**
     * Принудительно сохраняет все буферизованные логи на диск
     * Используется перед критическими операциями
     */
    fun flush() {
        try {
            // Записываем маркер принудительного сохранения
            writeLog("SYSTEM", "💾 Принудительное сохранение логов")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка принудительного сохранения: ${e.message}")
        }
    }
    
    /**
     * Записывает информацию о состоянии приложения
     */
    fun logAppState(context: Context) {
        try {
            val prefs = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
            val explicitlyStopped = prefs.getBoolean("explicitly_stopped", true)
            val stoppedByTimeout = prefs.getBoolean("stopped_by_timeout", false)
            val timeoutTimestamp = prefs.getLong("timeout_timestamp", 0)
            
            writeLog("STATE", "📊 Состояние приложения:")
            writeLog("STATE", "  - Остановлен пользователем: $explicitlyStopped")
            writeLog("STATE", "  - Остановлен по тайм-ауту: $stoppedByTimeout")
            if (timeoutTimestamp > 0) {
                val timeoutDate = java.util.Date(timeoutTimestamp)
                writeLog("STATE", "  - Время тайм-аута: ${dateFormat.format(timeoutDate)}")
            }
            
            // Проверяем состояние сервиса
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val isServiceRunning = manager.getRunningServices(100).any { 
                it.service.className == "ru.fourpda.tickets.ForegroundMonitorService" 
            }
            writeLog("STATE", "  - ForegroundService активен: $isServiceRunning")
            
        } catch (e: Exception) {
            writeLog("STATE", "❌ Ошибка получения состояния: ${e.message}")
        }
    }
}