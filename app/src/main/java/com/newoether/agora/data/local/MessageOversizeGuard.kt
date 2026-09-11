package com.newoether.agora.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.util.CrashReporter

/**
 * Keeps every persisted message row comfortably below the SQLite CursorWindow limit (~2 MB).
 * A row must fit ENTIRELY into one CursorWindow or every `SELECT *` touching it fails with
 * SQLiteBlobTooBigException ("Row too big to fit into CursorWindow") — permanently, since the
 * row is re-read on every open. The UI list query guards itself with SQL-side substr caps, but
 * bare `SELECT *` paths (conversation snapshots, task summaries, search, export pages) cannot.
 *
 * Two defenses:
 *  1. [enforce] runs on every write path (repository upsert + import replace/merge) and
 *     truncates oversized payload columns before they reach the DB, so giant values can never
 *     be persisted again.
 *  2. [repairExisting] runs once per DB open (wired in ChatDatabase.build) and cures rows
 *     written by older builds. It uses pure SQL only: size checks via length(CAST(col AS BLOB))
 *     and truncation via substr() happen INSIDE SQLite, so no oversized value is ever
 *     materialized into a CursorWindow during the repair itself.
 *
 * Truncation keeps the first [KEEP_CHARS] characters plus a marker with the original size.
 * This is deliberately lossy for giant payloads: by the time a payload exceeds 1 MB it has
 * already been delivered in full into the model context; the DB copy only needs to stay
 * readable. Spilling full payloads to disk is a possible follow-up, not a prerequisite.
 */
object MessageOversizeGuard {
    /** UTF-8 byte size above which a column value is truncated (CursorWindow is ~2 MB). */
    const val THRESHOLD_BYTES = 1_000_000

    /** Characters kept after truncation: 300k chars × 4 B max UTF-8 ≈ 1.2 MB, safely under the window. */
    const val KEEP_CHARS = 300_000

    /** TEXT payload columns that can grow unbounded. Compile-time constants — safe to inline in SQL. */
    private val GUARDED_COLUMNS = listOf("text", "thoughts", "toolCallJson", "attachmentMeta")

    /** Write-path guard: returns an equivalent entity whose payload columns are window-safe. */
    fun enforce(entity: MessageEntity): MessageEntity {
        var e = entity
        if (isOversized(e.text)) e = e.copy(text = truncate(e.text, "text"))
        if (isOversized(e.thoughts)) e = e.copy(thoughts = truncate(e.thoughts.orEmpty(), "thoughts"))
        if (isOversized(e.toolCallJson)) e = e.copy(toolCallJson = truncate(e.toolCallJson.orEmpty(), "toolCallJson"))
        if (isOversized(e.attachmentMeta)) e = e.copy(attachmentMeta = truncate(e.attachmentMeta.orEmpty(), "attachmentMeta"))
        return e
    }

    /**
     * Startup sweep over every oversized payload column in [messages]. Runs on Room's open
     * path; all work is native SQLite, so cost is a single streamed full-table scan regardless
     * of row count. Never throws — a sweep failure must not block the database from opening.
     */
    fun repairExisting(db: SupportSQLiteDatabase) {
        try {
            val lengthExpr = GUARDED_COLUMNS.joinToString(", ") { "length(CAST($it AS BLOB))" }
            val whereExpr = GUARDED_COLUMNS.joinToString(" OR ") { "length(CAST($it AS BLOB)) > ?" }
            val args: Array<Any> = Array(GUARDED_COLUMNS.size) { THRESHOLD_BYTES }
            db.query("SELECT id, $lengthExpr FROM messages WHERE $whereExpr", args).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    GUARDED_COLUMNS.forEachIndexed { index, column ->
                        if (!cursor.isNull(index + 1)) {
                            val bytes = cursor.getLong(index + 1)
                            if (bytes > THRESHOLD_BYTES) {
                                db.execSQL(
                                    "UPDATE messages SET $column = substr($column, 1, ?) || ? WHERE id = ?",
                                    arrayOf<Any>(KEEP_CHARS, truncationMarker(column, bytes), id)
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            CrashReporter.note("OversizeGuard.repairExisting failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Cheap char-count fast path first; byte size only computed for long values. */
    private fun isOversized(value: String?): Boolean {
        if (value == null || value.length <= KEEP_CHARS) return false
        return value.toByteArray(Charsets.UTF_8).size > THRESHOLD_BYTES
    }

    private fun truncate(value: String, column: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8).size
        return value.take(KEEP_CHARS) + truncationMarker(column, bytes.toLong())
    }

    private fun truncationMarker(column: String, originalBytes: Long): String =
        "\n\n[…已截断：$column 原 ${originalBytes / 1024} KB 超出单行存储上限]"
}
