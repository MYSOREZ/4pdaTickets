package ru.fourpda.tickets

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

object DateUtils {

    private val RU = Locale("ru", "RU")
    private val ZONE: ZoneId get() = ZoneId.systemDefault()

    // Предкомпилированные регулярки (после normalize с ASCII-цифрами)
    private val RE_TODAY = Regex("^Сегодня,?\\s*(\\d{1,2}):(\\d{2})$", RegexOption.IGNORE_CASE)
    private val RE_YESTERDAY = Regex("^Вчера,?\\s*(\\d{1,2}):(\\d{2})$", RegexOption.IGNORE_CASE)
    private val RE_ABSOLUTE = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})[\\s,]+(\\d{1,2}):(\\d{2})$")
    private val RE_MOBILE_OLD = Regex("^(\\d{1,2}):(\\d{2})\\s*\\((\\d{1,2})\\.(\\d{1,2})\\)$")
    private val RE_FALLBACK_DATE = Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})$")

    /**
     * Унифицированное форматирование:
     * - Сегодня, H:mm
     * - Вчера, H:mm
     * - dd.MM.yy, H:mm
     *
     * Поддерживаемые входы:
     * - "Сегодня, 0:01", "Вчера, 23:59"
     * - "24.08.24, 0:01", "24.08.2025, 0:01", "24.08.25 0:01"
     * - "08:40 (12.07)" — мобильный формат
     */
    fun formatTicketDate(raw: String): String {
        val nowMillis = System.currentTimeMillis()
        val ts = parseTicketDateToTimestamp(raw, nowMillis)
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZONE)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZONE)

        val sameDay = zdt.toLocalDate() == now.toLocalDate()
        val yesterday = zdt.toLocalDate() == now.minusDays(1).toLocalDate()
        val time = "${zdt.hour}:${zdt.minute.toString().padStart(2, '0')}"

        return when {
            sameDay -> "Сегодня, $time"
            yesterday -> "Вчера, $time"
            else -> "%02d.%02d.%02d, %s".format(
                RU,
                zdt.dayOfMonth,
                zdt.monthValue,
                zdt.year % 100,
                time
            )
        }
    }

    /**
     * Совместимый форматтер для "dd.MM.yyyy HH:mm".
     */
    fun formatFullDate(dateString: String): String = formatTicketDate(dateString)

    /**
     * Надёжный парсер тикетной даты в millis.
     * Исправляет кейс Android 15: "24.08.24, 0:01" в день "24.08.25" → текущий год, «Сегодня, 0:01».
     */
    fun parseTicketDateToTimestamp(raw: String, nowMillis: Long = System.currentTimeMillis()): Long {
        val zone = ZONE
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val s = normalize(raw)

        // Сегодня
        RE_TODAY.matchEntire(s)?.let { m ->
            val (h, mi) = m.destructured
            return now.withHour(h.toInt()).withMinute(mi.toInt())
                .withSecond(0).withNano(0).toInstant().toEpochMilli()
        }

        // Вчера
        RE_YESTERDAY.matchEntire(s)?.let { m ->
            val (h, mi) = m.destructured
            return now.minusDays(1).withHour(h.toInt()).withMinute(mi.toInt())
                .withSecond(0).withNano(0).toInstant().toEpochMilli()
        }

        // Абсолютная дата: dd.MM.yy[, ]HH:mm или dd.MM.yyyy[, ]HH:mm
        RE_ABSOLUTE.matchEntire(s)?.let { m ->
            val (dStr, mStr, yStr, hStr, miStr) = m.destructured
            val d = dStr.toInt()
            val mo = mStr.toInt()
            var y = yStr.toInt()
            val h = hStr.toInt()
            val mi = miStr.toInt()

            // 2-значный год → 2000+yy
            if (yStr.length == 2) y = 2000 + y

            // Если день/месяц совпали с сегодняшними, а год другой — форсируем текущий год
            if (d == now.dayOfMonth && mo == now.monthValue && y != now.year) {
                y = now.year
            }

            return safeEpoch(y, mo, d, h, mi, zone, nowMillis)
        }

        // Старый мобильный формат: "HH:mm (dd.MM)"
        RE_MOBILE_OLD.matchEntire(s)?.let { m ->
            val (hStr, miStr, dStr, mStr) = m.destructured
            val h = hStr.toInt()
            val mi = miStr.toInt()
            val d = dStr.toInt()
            val mo = mStr.toInt()

            // Если дата «в будущем» относительно сегодня — значит прошлый год
            val y = when {
                mo > now.monthValue -> now.year - 1
                mo == now.monthValue && d > now.dayOfMonth -> now.year - 1
                else -> now.year
            }

            return safeEpoch(y, mo, d, h, mi, zone, nowMillis)
        }

        // Фолбэк: только dd.MM.yy(yyyy) без времени → ставим 00:00
        RE_FALLBACK_DATE.matchEntire(s)?.let { m ->
            val (dStr, mStr, yStr) = m.destructured
            val d = dStr.toInt()
            val mo = mStr.toInt()
            var y = yStr.toInt()
            if (yStr.length == 2) y = 2000 + y
            if (d == now.dayOfMonth && mo == now.monthValue && y != now.year) y = now.year
            return safeEpoch(y, mo, d, 0, 0, zone, nowMillis)
        }

        // Неопознанный формат — возвращаем "сейчас", чтобы не ломать UX
        return nowMillis
    }

    /* ----------------------- private helpers ----------------------- */

    // Перевод всех десятичных цифр Unicode в ASCII '0'..'9'
    private fun toAsciiDigits(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val type = Character.getType(ch)
            if (type == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                val v = Character.getNumericValue(ch)
                if (v in 0..9) {
                    sb.append(('0'.code + v).toChar())
                } else {
                    sb.append(ch) // страхуемся: неожиданный numericValue
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    // Нормализация проблемных символов из WebView/HTML: NBSP/узкие пробелы, альтернативные запятые, bidi-маркеры и т.д.
    private fun normalize(input: String): String {
        var s = input

        // Удаляем/заменяем невидимые/необычные пробелы и bidi-маркеры
        s = s.replace('\u00A0', ' ')  // NBSP
            .replace('\u202F', ' ')   // NNBSP
            .replace('\u2007', ' ')   // Figure space
            .replace('\u2060', ' ')   // Word joiner
            .replace('\u200B', ' ')   // Zero width space
            .replace('\u2009', ' ')   // Thin space
            .replace('\u205F', ' ')   // Medium mathematical space
            .replace('\u2028', ' ')   // Line separator
            .replace('\u2029', ' ')   // Paragraph separator
            .replace('\u200E', ' ')   // LRM
            .replace('\u200F', ' ')   // RLM
            .replace('\u202A', ' ')
            .replace('\u202B', ' ')
            .replace('\u202C', ' ')
            .replace('\u202D', ' ')
            .replace('\u202E', ' ')
            .trim()

        // Альтернативные «запятые» → обычная запятая
        s = s.replace('\u060C', ',')   // Arabic comma
            .replace('\u201A', ',')    // Single low-9 quotation
            .replace('\u201E', ',')    // Double low-9 quotation

        // Сжать повторные пробелы/знаки
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("\\s+,\\s+"), ", ")
        s = s.replace(Regex(",\\s+"), ", ")

        // Все десятичные цифры Unicode → ASCII
        s = toAsciiDigits(s)

        return s
    }

    private fun safeEpoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId,
        fallbackNowMillis: Long
    ): Long {
        return try {
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            fallbackNowMillis
        }
    }
}
