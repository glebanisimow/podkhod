package app.podkhod.ui.format

/**
 * Russian quantity strings. The UI is Russian-only, but resource plurals pick the form by the system
 * locale: on an English phone "9 упражнений" would use the English "other" rule. The forms therefore live
 * here and are always chosen with Russian rules; `%d` is replaced by the count.
 */
enum class RuPlural(private val one: String, private val few: String, private val many: String) {
    EXERCISES("%d упражнение", "%d упражнения", "%d упражнений"),
    SETS_REMAINING("Остался %d подход", "Осталось %d подхода", "Осталось %d подходов"),
    SETS("%d подход", "%d подхода", "%d подходов"),
    SETS_NOT_DONE(
        "Не выполнен %d подход — он не попадёт в историю.",
        "Не выполнено %d подхода — они не попадут в историю.",
        "Не выполнено %d подходов — они не попадут в историю.",
    ),
    SESSIONS("%d тренировка", "%d тренировки", "%d тренировок");

    fun format(count: Int): String {
        val n = kotlin.math.abs(count)
        val form = when {
            n % 10 == 1 && n % 100 != 11 -> one
            n % 10 in 2..4 && n % 100 !in 12..14 -> few
            else -> many
        }
        return form.replace("%d", count.toString())
    }
}

fun ruPlural(plural: RuPlural, count: Int): String = plural.format(count)
