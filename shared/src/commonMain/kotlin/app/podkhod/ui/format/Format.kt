package app.podkhod.ui.format

import app.podkhod.resources.*
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.model.SetKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

object Format {
    /** Genitive month names, as in "2 октября". */
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    private fun local(millis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

    private fun today() = Clock.System.todayIn(TimeZone.currentSystemDefault())

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** 62.5 -> "62,5", 60.0 -> "60", 1.25 -> "1,25". */
    fun weight(kg: Double): String {
        val scaled = (kg * 100).roundToLong()
        val whole = scaled / 100
        val frac = abs(scaled % 100)
        val sign = if (scaled < 0 && whole == 0L) "-" else ""
        return when {
            frac == 0L -> "$sign$whole"
            frac % 10 == 0L -> "$sign$whole,${frac / 10}"
            else -> "$sign$whole,${frac.toString().padStart(2, '0')}"
        }
    }

    /** Accepts both "62,5" and "62.5". */
    fun parseWeight(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it < 1000 }

    fun volume(kg: Double): String {
        val rounded = kg.roundToLong()
        return rounded.toString().reversed().chunked(3).joinToString("\u202F").reversed()
    }

    fun signedVolume(kg: Double): String = (if (kg >= 0) "+" else "−") + volume(abs(kg)) + " кг"

    /** 95 -> "1:35"; 3725 -> "1:02:05". */
    fun clock(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m.toInt())}:${two(sec.toInt())}" else "$m:${two(sec.toInt())}"
    }

    /** Rest durations from the plan: 90 -> "1:30", 60 -> "1 мин", 45 -> "45 с". */
    fun rest(sec: Int): String = when {
        sec < 60 -> "$sec с"
        sec % 60 == 0 -> "${sec / 60} мин"
        else -> clock(sec.toLong())
    }

    /** Workout length: "48 мин", "1 ч 05 мин". */
    fun duration(totalSec: Long): String {
        val min = (totalSec.coerceAtLeast(0) + 30) / 60
        return if (min < 60) "$min мин" else "${min / 60} ч ${two((min % 60).toInt())} мин"
    }

    fun range(min: Int, max: Int): String = if (min == max) "$min" else "$min–$max"

    fun time(millis: Long): String = local(millis).let { "${two(it.hour)}:${two(it.minute)}" }

    /** "2 октября, чт" this year, "2 октября 2025" otherwise. */
    fun date(millis: Long): String {
        val dt = local(millis)
        val dayMonth = "${dt.day} ${months[dt.month.ordinal]}"
        return if (dt.year == today().year) "$dayMonth, ${weekdays[dt.dayOfWeek.ordinal]}" else "$dayMonth ${dt.year}"
    }

    /** Chart axis dates: "2.10". */
    fun shortDate(millis: Long): String = local(millis).let { "${it.day}.${two(it.month.ordinal + 1)}" }

    /** "сегодня в 18:42" / "2 октября, чт в 18:42". */
    fun moment(millis: Long): String {
        val day = if (local(millis).date == today()) "сегодня" else date(millis)
        return "$day в ${time(millis)}"
    }

    /** "2026-10-08", for file names. */
    fun isoToday(): String = today().toString()

    fun setResult(weightKg: Double?, reps: Int?, durationSec: Int?): String = buildString {
        if (weightKg != null && weightKg > 0) append(weight(weightKg)).append(" × ")
        when {
            durationSec != null -> append(durationSec).append(" с")
            reps != null -> append(reps)
        }
    }

    fun muscle(m: Muscle): StringResource = when (m) {
        Muscle.CHEST -> Res.string.muscle_chest
        Muscle.LATS -> Res.string.muscle_lats
        Muscle.UPPER_BACK -> Res.string.muscle_upper_back
        Muscle.DELTS_FRONT -> Res.string.muscle_delts_front
        Muscle.DELTS_SIDE -> Res.string.muscle_delts_side
        Muscle.DELTS_REAR -> Res.string.muscle_delts_rear
        Muscle.BICEPS -> Res.string.muscle_biceps
        Muscle.TRICEPS -> Res.string.muscle_triceps
        Muscle.FOREARMS -> Res.string.muscle_forearms
        Muscle.QUADS -> Res.string.muscle_quads
        Muscle.HAMSTRINGS -> Res.string.muscle_hamstrings
        Muscle.GLUTES -> Res.string.muscle_glutes
        Muscle.CALVES -> Res.string.muscle_calves
        Muscle.ABS -> Res.string.muscle_abs
        Muscle.LOWER_BACK -> Res.string.muscle_lower_back
    }

    fun setKind(k: SetKind): StringResource = when (k) {
        SetKind.WARMUP -> Res.string.set_kind_warmup
        SetKind.WORK -> Res.string.set_kind_work
        SetKind.FAILURE -> Res.string.set_kind_failure
        SetKind.DROP -> Res.string.set_kind_drop
    }

    fun feeling(value: Int): StringResource = when (value) {
        1 -> Res.string.feeling_hard
        3 -> Res.string.feeling_easy
        else -> Res.string.feeling_ok
    }
}
