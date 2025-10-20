# Design Document

## Overview

Система будет модифицирована для поддержки пользовательских интервалов проверки с предупреждениями о рисках. Вместо принудительного увеличения коротких интервалов до 60 секунд, система будет показывать диалог с предупреждением и позволять пользователю выбрать.

## Architecture

### Компоненты для модификации:

1. **ExactAlarmScheduler** - убрать принудительное ограничение, добавить проверку пользовательских предпочтений
2. **IntervalWarningDialog** - новый диалог для предупреждения о рисках
3. **SharedPreferences** - хранение пользовательских предпочтений
4. **SettingsActivity** - добавить опцию сброса предупреждений

## Components and Interfaces

### IntervalWarningDialog
```kotlin
class IntervalWarningDialog {
    fun showWarning(context: Context, intervalSeconds: Int, callback: (useOriginal: Boolean) -> Unit)
    private fun createWarningMessage(intervalSeconds: Int): String
}
```

### UserPreferences (новый класс)
```kotlin
object UserPreferences {
    fun shouldShowIntervalWarning(context: Context, intervalSeconds: Int): Boolean
    fun setIntervalWarningShown(context: Context, intervalSeconds: Int, useOriginal: Boolean)
    fun resetIntervalWarnings(context: Context)
}
```

### Модифицированный ExactAlarmScheduler
```kotlin
object ExactAlarmScheduler {
    fun scheduleNextAlarmSeconds(context: Context, intervalSeconds: Int, forceOriginal: Boolean = false)
    private fun handleShortInterval(context: Context, intervalSeconds: Int, callback: (finalInterval: Int) -> Unit)
}
```

## Data Models

### SharedPreferences структура:
```
interval_warnings_prefs:
- "warning_shown_for_X" (boolean) - показывалось ли предупреждение для интервала X секунд
- "use_original_for_X" (boolean) - выбрал ли пользователь использовать оригинальный интервал X
- "warnings_reset_timestamp" (long) - время последнего сброса предупреждений
```

## Error Handling

1. **Отсутствие разрешений на точные будильники** - fallback на WorkManager
2. **Ошибка показа диалога** - использовать безопасный интервал по умолчанию
3. **Ошибка сохранения предпочтений** - логировать и продолжить с временными настройками

## Testing Strategy

### Unit Tests:
- UserPreferences логика сохранения/загрузки
- ExactAlarmScheduler обработка коротких интервалов
- IntervalWarningDialog создание сообщений

### Integration Tests:
- Полный flow: короткий интервал → предупреждение → выбор пользователя → планирование будильника
- Сброс предупреждений через настройки
- Повторное использование сохраненных предпочтений

### UI Tests:
- Показ диалога предупреждения
- Корректность текста предупреждения
- Функциональность кнопок в диалоге