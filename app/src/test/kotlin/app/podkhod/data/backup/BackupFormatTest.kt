package app.podkhod.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {

    private fun row(vararg pairs: Pair<String, Any?>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private fun file(tables: Map<String, List<JsonObject>>, schema: Int = 4, format: String = BackupFormat.FORMAT) = BackupFile(
        format = format, formatVersion = 1, schemaVersion = schema, appVersion = "0.4.0", createdAt = 1L, tables = tables,
    )

    private val tables = mapOf(
        "workout_session" to listOf(row("id" to 1, "status" to "finished"), row("id" to 2, "status" to "active")),
        "session_exercise" to listOf(row("id" to 10, "session_id" to 1, "exercise_id" to "squat"), row("id" to 20, "session_id" to 2, "exercise_id" to "bench")),
        "set_log" to listOf(row("id" to 100, "session_exercise_id" to 10, "weight_kg" to 62.5), row("id" to 200, "session_exercise_id" to 20)),
        "busy_event" to listOf(row("id" to 1, "session_id" to 2, "exercise_id" to "bench"), row("id" to 2, "session_id" to null, "exercise_id" to "squat")),
        "profile" to listOf(row("id" to 1, "goal" to "balance")),
    )

    @Test
    fun `active sessions are left out and busy events lose the link`() {
        val t = BackupFormat.withoutActiveSessions(tables)
        assertEquals(listOf(1L), t.getValue("workout_session").map { (it["id"] as JsonPrimitive).content.toLong() })
        assertEquals(1, t.getValue("session_exercise").size)
        assertEquals(1, t.getValue("set_log").size)
        assertEquals(JsonNull, t.getValue("busy_event")[0]["session_id"])
        assertEquals(2, t.getValue("busy_event").size)
    }

    @Test
    fun `a file round-trips and previews its contents`() {
        val f = file(BackupFormat.withoutActiveSessions(tables))
        val r = BackupFormat.read(BackupFormat.encode(f), 4, setOf("squat", "bench"))
        r as BackupRead.Ok
        assertEquals(f, r.file)
        assertEquals(1, r.preview.sessions)
        assertEquals(1, r.preview.sets)
        assertTrue(r.preview.hasProfile)
        assertEquals("62.5", (r.file.tables.getValue("set_log")[0]["weight_kg"] as JsonPrimitive).content)
    }

    @Test
    fun `newer schema, foreign files and unknown exercises are refused`() {
        val newer = BackupFormat.read(BackupFormat.encode(file(emptyMap(), schema = 5)), 4, emptySet())
        assertTrue((newer as BackupRead.Invalid).problems.single().contains("Обнови"))

        val foreign = BackupFormat.read(BackupFormat.encode(file(emptyMap(), format = "other")), 4, emptySet())
        assertTrue(foreign is BackupRead.Invalid)

        assertTrue(BackupFormat.read("{not json", 4, emptySet()) is BackupRead.Invalid)

        val missing = BackupFormat.read(BackupFormat.encode(file(tables)), 4, setOf("squat"))
        assertTrue((missing as BackupRead.Invalid).problems.single().contains("bench"))

        val unknownTable = BackupFormat.read(BackupFormat.encode(file(mapOf("exercise" to emptyList()))), 4, emptySet())
        assertTrue((unknownTable as BackupRead.Invalid).problems.single().contains("exercise"))
    }

    @Test
    fun `an older schema is accepted`() {
        assertTrue(BackupFormat.read(BackupFormat.encode(file(emptyMap(), schema = 3)), 4, emptySet()) is BackupRead.Ok)
    }
}
