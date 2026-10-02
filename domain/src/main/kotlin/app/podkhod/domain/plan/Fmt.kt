package app.podkhod.domain.plan

import app.podkhod.domain.model.MovementPattern
import app.podkhod.domain.model.Muscle
import kotlin.math.roundToLong

/** Russian number formatting for explanations: decimal comma, no trailing zeros. */
object Fmt {
    fun num(v: Double): String {
        val r = (v * 100.0).roundToLong() / 100.0
        val s = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
        return s.replace('.', ',')
    }

    fun kg(v: Double?): String = if (v == null) "—" else "${num(v)} кг"

    fun range(min: Int, max: Int): String = if (min == max) "$min" else "$min–$max"

    fun group(g: MuscleGroup): String = when (g) {
        MuscleGroup.CHEST -> "грудь"
        MuscleGroup.BACK -> "спина"
        MuscleGroup.QUADS -> "квадрицепсы"
        MuscleGroup.HAMSTRINGS -> "бицепс бедра"
        MuscleGroup.GLUTES -> "ягодицы"
        MuscleGroup.DELTS -> "плечи"
        MuscleGroup.BICEPS -> "бицепс"
        MuscleGroup.TRICEPS -> "трицепс"
        MuscleGroup.CALVES -> "икры"
        MuscleGroup.ABS -> "пресс"
    }

    fun muscle(m: Muscle): String = when (m) {
        Muscle.CHEST -> "грудь"
        Muscle.LATS -> "широчайшие"
        Muscle.UPPER_BACK -> "верх спины"
        Muscle.DELTS_FRONT -> "передняя дельта"
        Muscle.DELTS_SIDE -> "средняя дельта"
        Muscle.DELTS_REAR -> "задняя дельта"
        Muscle.BICEPS -> "бицепс"
        Muscle.TRICEPS -> "трицепс"
        Muscle.FOREARMS -> "предплечья"
        Muscle.QUADS -> "квадрицепсы"
        Muscle.HAMSTRINGS -> "бицепс бедра"
        Muscle.GLUTES -> "ягодицы"
        Muscle.CALVES -> "икры"
        Muscle.ABS -> "пресс"
        Muscle.LOWER_BACK -> "поясница"
    }

    fun pattern(p: MovementPattern): String = when (p) {
        MovementPattern.SQUAT -> "присед"
        MovementPattern.HINGE -> "наклон/тяга от пола"
        MovementPattern.LUNGE -> "выпады"
        MovementPattern.H_PUSH -> "горизонтальный жим"
        MovementPattern.V_PUSH -> "вертикальный жим"
        MovementPattern.H_PULL -> "горизонтальная тяга"
        MovementPattern.V_PULL -> "вертикальная тяга"
        MovementPattern.ISOLATION -> "изоляция"
        MovementPattern.CORE -> "кор"
        MovementPattern.CARRY -> "переноска"
    }

    fun equipment(code: String): String = EQUIPMENT[code] ?: code

    fun tag(code: String): String = TAGS[code] ?: code

    fun weekday(iso: Int): String = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс").getOrElse(iso - 1) { "?" }

    val EQUIPMENT = linkedMapOf(
        "barbell" to "штанга", "rack" to "стойка", "bench" to "скамья", "adjustable_bench" to "регулируемая скамья",
        "kettlebell" to "гири", "dumbbells" to "гантели", "pullup_bar" to "турник", "dip_bars" to "брусья", "rings" to "кольца",
        "lat_pulldown" to "верхний блок", "cable_row" to "горизонтальный блок", "chest_press_machine" to "тренажёр для жима",
        "leg_press" to "жим ногами", "leg_extension" to "разгибание ног", "leg_curl" to "сгибание ног",
        "cable_station" to "кроссовер", "belt_chain" to "пояс с цепью", "bands" to "резиновые петли",
        "hyperextension" to "гиперэкстензия",
    )

    val TAGS = linkedMapOf(
        "axial_load" to "осевая нагрузка", "deep_squat" to "глубокий присед", "knee_stress" to "нагрузка на колени",
        "lower_back_stress" to "нагрузка на поясницу", "grip_intensive" to "тяжёлый хват", "shoulder_stress" to "нагрузка на плечи",
        "overhead" to "над головой", "ballistic" to "баллистика", "spinal_flexion" to "скручивание позвоночника",
        "behind_neck" to "за голову",
    )

    fun role(r: SlotRole): String = when (r) {
        SlotRole.COMPOUND -> "базовое"
        SlotRole.ACCESSORY -> "вспомогательное"
        SlotRole.ISOLATION -> "изоляция"
        SlotRole.CORE -> "кор"
    }
}
