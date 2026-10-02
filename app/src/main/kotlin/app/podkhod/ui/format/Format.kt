package app.podkhod.ui.format

import androidx.annotation.StringRes
import app.podkhod.R
import app.podkhod.domain.model.Muscle
import app.podkhod.domain.model.SetKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Format {
    private val ru = Locale.forLanguageTag("ru")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", ru)
    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM, EE", ru)
    private val dateYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
    private val shortDateFmt = DateTimeFormatter.ofPattern("d.MM", ru)

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
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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
        return if (min < 60) "$min мин" else "${min / 60} ч ${"%02d".format(min % 60)} мин"
    }

    fun range(min: Int, max: Int): String = if (min == max) "$min" else "$min–$max"

    fun time(millis: Long): String = timeFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun date(millis: Long): String {
        val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return if (dt.year == LocalDate.now().year) dateFmt.format(dt) else dateYearFmt.format(dt)
    }

    /** Chart axis dates: "2.10". */
    fun shortDate(millis: Long): String = shortDateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** "сегодня в 18:42" / "2 октября, чт в 18:42". */
    fun moment(millis: Long): String {
        val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val day = if (dt.toLocalDate() == LocalDate.now()) "сегодня" else date(millis)
        return "$day в ${time(millis)}"
    }

    fun setResult(weightKg: Double?, reps: Int?, durationSec: Int?): String = buildString {
        if (weightKg != null && weightKg > 0) append(weight(weightKg)).append(" × ")
        when {
            durationSec != null -> append(durationSec).append(" с")
            reps != null -> append(reps)
        }
    }

    @StringRes
    fun muscle(m: Muscle): Int = when (m) {
        Muscle.CHEST -> R.string.muscle_chest
        Muscle.LATS -> R.string.muscle_lats
        Muscle.UPPER_BACK -> R.string.muscle_upper_back
        Muscle.DELTS_FRONT -> R.string.muscle_delts_front
        Muscle.DELTS_SIDE -> R.string.muscle_delts_side
        Muscle.DELTS_REAR -> R.string.muscle_delts_rear
        Muscle.BICEPS -> R.string.muscle_biceps
        Muscle.TRICEPS -> R.string.muscle_triceps
        Muscle.FOREARMS -> R.string.muscle_forearms
        Muscle.QUADS -> R.string.muscle_quads
        Muscle.HAMSTRINGS -> R.string.muscle_hamstrings
        Muscle.GLUTES -> R.string.muscle_glutes
        Muscle.CALVES -> R.string.muscle_calves
        Muscle.ABS -> R.string.muscle_abs
        Muscle.LOWER_BACK -> R.string.muscle_lower_back
    }

    @StringRes
    fun setKind(k: SetKind): Int = when (k) {
        SetKind.WARMUP -> R.string.set_kind_warmup
        SetKind.WORK -> R.string.set_kind_work
        SetKind.FAILURE -> R.string.set_kind_failure
        SetKind.DROP -> R.string.set_kind_drop
    }

    @StringRes
    fun feeling(value: Int): Int = when (value) {
        1 -> R.string.feeling_hard
        3 -> R.string.feeling_easy
        else -> R.string.feeling_ok
    }
}
