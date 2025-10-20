# Решение проблемы 6-часового лимита Android 15 для фонового мониторинга

## Проблема

На Android 15 и HyperOS приложения с постоянными foreground-сервисами типа `dataSync` и `mediaProcessing` ограничены **6 часами суммарной работы за 24 часа**. После исчерпания лимита:

- Система вызывает `Service.onTimeout()`
- Если сервис не остановится за несколько секунд → `RemoteServiceException` и крэш
- Повторный запуск FGS невозможен без вывода приложения на передний план пользователем
- В логах: `ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync`

## Наше решение

Мы **полностью отказались от постоянного foreground-сервиса** и перешли на архитектуру с точными будильниками.

### Архитектура "до" (проблемная)
```
MainActivity → startForegroundService() → ForegroundMonitorService (постоянно активен) → лимит 6ч → крэш
```

### Архитектура "после" (безопасная)
```
MainActivity → ExactAlarmScheduler → ExactAlarmReceiver → QuickCheckWorker (короткая задача) → NotificationUpdater → планирование следующего алларма
```

## Ключевые компоненты решения

### 1. ExactAlarmScheduler.kt
**Назначение:** Планирование точных будильников для периодических проверок

**Особенности:**
- Использует `AlarmManager.setExactAndAllowWhileIdle()` для надежного срабатывания
- **Минимальный интервал: 60 секунд** (для стабильности и соответствия политикам)
- Использует `ELAPSED_REALTIME_WAKEUP` (независимо от смены времени)
- **Якорное планирование** - избегает дрейф времени
- Автоматический fallback на WorkManager при отсутствии разрешений
- Требует разрешение `SCHEDULE_EXACT_ALARM` на Android 14+

```kotlin
fun scheduleNextAlarmSeconds(context: Context, intervalSeconds: Int) {
    val safeInterval = maxOf(intervalSeconds, 60) // Минимум 1 минута
    
    // Якорное планирование для избежания дрейфа
    val lastScheduled = prefs.getLong("last_scheduled", 0L)
    val now = SystemClock.elapsedRealtime()
    val triggerTime = if (lastScheduled == 0L) {
        now + (safeInterval * 1000L)
    } else {
        val intervalMs = safeInterval * 1000L
        val n = ((now - lastScheduled) / intervalMs) + 1
        lastScheduled + (n * intervalMs)
    }
    
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        triggerTime,
        pendingIntent
    )
}
```

### 2. ExactAlarmReceiver.kt
**Назначение:** Обработка срабатывания будильников

**Особенности:**
- Запускает короткую задачу `QuickCheckWorker` через WorkManager
- **Использует `enqueueUniqueWork`** для предотвращения наложений
- **Constraints:** требует подключение к сети (`CONNECTED`)
- Сразу планирует следующий будильник
- Обновляет служебные уведомления
- Не держит процесс активным

```kotlin
WorkManager.getInstance(context).enqueueUniqueWork(
    "ticket_check_work",
    ExistingWorkPolicy.REPLACE,
    workRequest
)
```

### 3. QuickCheckWorker.kt
**Назначение:** Выполнение быстрой проверки тикетов

**Особенности:**
- Работает 5-15 секунд и завершается
- **Защита от наложений** через `@Volatile isRunning` флаг
- Использует WebView для загрузки страницы тикетов (создается в главном потоке)
- JavaScript интерфейс для парсинга DOM
- **Timeout 15 секунд** с автоматическим retry при превышении
- Отправляет push-уведомления при обнаружении новых тикетов
- **НЕ использует foreground-сервис**

```kotlin
companion object {
    @Volatile
    private var isRunning = false
}

override fun doWork(): Result {
    if (isRunning) {
        Log.w(TAG, "⚠️ Предыдущая проверка еще выполняется, пропускаем")
        return Result.success()
    }
    isRunning = true
    try {
        // Выполнение работы
    } finally {
        isRunning = false
    }
}
```

### 4. NotificationUpdater.kt
**Назначение:** Управление уведомлениями без FGS

**Особенности:**
- Показывает ongoing-уведомления БЕЗ foreground-сервиса
- Обновляет статистику тикетов
- Два типа уведомлений:
  - Служебное (постоянное, с ongoing=true)
  - Push о новых тикетах (временное)

### 5. Немедленная проверка при запуске
**Назначение:** Мгновенная загрузка статистики при старте мониторинга

**Реализация в MainActivity.startMonitoringService():**
```kotlin
// Сначала запускаем немедленную проверку
val immediateWorkRequest = OneTimeWorkRequestBuilder<QuickCheckWorker>()
    .setConstraints(constraints)
    .addTag("immediate_start_check")
    .build()

WorkManager.getInstance(this).enqueueUniqueWork(
    "immediate_ticket_check",
    ExistingWorkPolicy.REPLACE,
    immediateWorkRequest
)

// Затем запускаем регулярные проверки с интервалом из настроек
ExactAlarmScheduler.scheduleNextAlarmSeconds(this, intervalSeconds)
```

**Преимущества:**
- Статистика загружается **сразу** при нажатии "Запустить мониторинг" (3-4 секунды)
- Пользователь не ждет первый интервал (60+ секунд)
- Дальнейшие обновления по расписанию

### 6. TicketMonitor.kt
**Назначение:** JavaScript интерфейс для WebView

**Особенности:**
- Парсит DOM страницы тикетов
- Определяет статусы тикетов (0=новый, 2=обработанный)
- Передает данные в Android через `@JavascriptInterface`
- Обрабатывает статистику и отдельные тикеты

## Преимущества нашего решения

### ✅ Нет ограничения 6 часов
- Точные будильники не подпадают под лимит FGS
- Можем работать сутками без перерыва
- Нет риска `RemoteServiceException`

### ✅ Энергоэффективность
- Приложение "спит" между проверками
- Активность только на 5-10 секунд каждый интервал
- Система может оптимизировать расписание

### ✅ Надежность
- Будильники срабатывают даже в Doze Mode
- Не зависят от убийц фона MIUI/HyperOS
- Работают на всех версиях Android

### ✅ Гибкость интервалов
- **Минимум 1 минута** (для стабильности и соответствия политикам)
- До нескольких часов
- Настраивается пользователем через SettingsActivity
- Автоматический fallback на WorkManager (15+ минут) при отсутствии разрешений

### ✅ Мгновенный отклик
- **Немедленная проверка** при запуске мониторинга (3-4 секунды)
- Пользователь сразу видит актуальную статистику
- Нет долгого ожидания первого интервала

## Настройки для пользователей

Для максимальной надежности рекомендуем пользователям:

### HyperOS/MIUI настройки:
1. **Батарея:** "Без ограничений" для приложения
2. **Автозапуск:** Включить
3. **Отзыв разрешений:** Отключить "При бездействии отзывать разрешения"
4. **Безопасность:** Lock Apps - закрепить приложение

### Android настройки:
1. **Точные будильники:** Разрешить через "Будильники и напоминания" (критично!)
2. **Оптимизация батареи:** Исключить приложение
3. **Уведомления:** Разрешить для всех каналов
4. **Doze Mode:** При частых интервалах система может "склеивать" будильники

## Технические детали

### Разрешения в AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

**Важно:** Используем только `SCHEDULE_EXACT_ALARM` (не `USE_EXACT_ALARM`), так как:
- `USE_EXACT_ALARM` предназначен только для будильников/календарей
- Google Play часто отклоняет приложения с `USE_EXACT_ALARM`
- `WAKE_LOCK` не нужен - WorkManager сам управляет wake locks

### Компоненты в AndroidManifest.xml:
```xml
<receiver android:name=".ExactAlarmReceiver" 
    android:enabled="true" 
    android:exported="false" />

<receiver android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter android:priority="1000">
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

### Логика перезапуска после перезагрузки:
- `BootReceiver` автоматически восстанавливает мониторинг
- Проверяет, был ли мониторинг активен до перезагрузки
- Планирует первый будильник

## Мониторинг и отладка

### Ключевые логи для проверки:
```
MainActivity: 🚀 Запущена немедленная проверка при старте мониторинга
ExactAlarmScheduler: ✅ Точный будильник установлен на X секунд
ExactAlarmReceiver: ⏰ Точный будильник сработал
QuickCheckWorker: 🔍 Начинаем быструю проверку тикетов
QuickCheckWorker: 📊 Перехвачена статистика из console: DEBUG
QuickCheckWorker: ✅ Быстрая проверка завершена успешно
NotificationUpdater: ✅ Служебное уведомление обновлено
```

### Признаки правильной работы:
- **Немедленная проверка** при запуске (3-4 секунды)
- Регулярные срабатывания будильников по интервалу
- Обновление статистики тикетов в уведомлениях
- Push-уведомления при новых тикетах
- Отсутствие ошибок `ForegroundServiceDidNotStartInTimeException` в логах

## Совместимость

### ✅ Поддерживаемые версии Android:
- Android 12+ (API 31+) - полная поддержка
- Android 14+ - требует разрешение на точные будильники
- Android 15 - полностью совместимо, обходит лимит 6ч

### ✅ Протестировано на:
- HyperOS (Xiaomi)
- MIUI 14/15
- Stock Android
- Custom ROM (crDroid)

## Критические улучшения (Must-fix)

### ✅ Исправлено в коде:
1. **Разрешения:** Убран `USE_EXACT_ALARM` и `WAKE_LOCK` - оставлен только `SCHEDULE_EXACT_ALARM`
2. **Стабильное планирование:** Переход на `ELAPSED_REALTIME_WAKEUP` + якорное планирование
3. **Минимальный интервал:** 60 секунд для соответствия политикам и стабильности
4. **Защита от наложений:** `enqueueUniqueWork` + `@Volatile isRunning` флаг
5. **Fallback механизм:** Автоматический переход на WorkManager при отсутствии разрешений
6. **Network constraints:** Требование подключения к сети для WorkManager задач
7. **Немедленная проверка:** Мгновенная загрузка статистики при запуске мониторинга
8. **Исправлены крэши:** Удалены все вызовы `startForegroundService()` без `startForeground()`
9. **Настройки интервала:** Пользовательский интерфейс для изменения интервала проверок (60-600 сек)

### ⚠️ Важные ограничения:
- **Doze Mode:** Система может "склеивать" частые будильники (< 5 минут)
- **Battery Saver:** Может блокировать точные будильники
- **Google Play:** Требует обоснование использования точных будильников
- **Энергопотребление:** Частые интервалы (< 1 минуты) не рекомендуются

## Заключение

Наше решение полностью устраняет проблему 6-часового лимита Android 15, обеспечивая:

1. **Стабильную работу** без крэшей и ограничений
2. **Энергоэффективность** за счет коротких задач и разумных интервалов
3. **Надежность** на всех версиях Android и прошивках
4. **Соответствие политикам** Google Play и Android
5. **Защиту от наложений** и системных ограничений
6. **Мгновенный отклик** - статистика загружается сразу при запуске
7. **Гибкие настройки** - пользователь может настроить интервал проверок

### Итоговые исправления:
- ✅ **Устранены крэши** `ForegroundServiceDidNotStartInTimeException`
- ✅ **Добавлена немедленная проверка** при запуске мониторинга
- ✅ **Настраиваемый интервал** от 60 секунд до 10 минут
- ✅ **Стабильная работа** без 6-часового лимита Android 15

Архитектура с точными будильниками + короткими WorkManager задачами + немедленной проверкой - это современный и правильный подход для фонового мониторинга на Android 15+.