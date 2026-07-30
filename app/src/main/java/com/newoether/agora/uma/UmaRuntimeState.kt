package com.newoether.agora.uma

import org.json.JSONArray
import org.json.JSONObject

/** Process-local bounded snapshot shared by the monitor and the LLM tool provider. */
object UmaRuntimeState {
    private val lock = Any()
    private var previous: JSONObject? = null
    private var current: JSONObject? = null
    private var changes: JSONObject = JSONObject().put("available", false)
    private var capturedAt: Long = 0L

    fun update(rawSummary: String): JSONObject = synchronized(lock) {
        val next = JSONObject(rawSummary)
        val old = current
        previous = old
        current = next
        capturedAt = System.currentTimeMillis()
        changes = diff(old, next).put("captured_at", capturedAt)
        changes
    }

    fun snapshotJson(): String = synchronized(lock) {
        val value = current ?: return@synchronized JSONObject()
            .put("ok", false).put("error", "No SO snapshot has been captured yet").toString()
        JSONObject()
            .put("ok", true)
            .put("captured_at", capturedAt)
            .put("summary", value)
            .put("changes", changes)
            .toString()
    }

    fun changesJson(): String = synchronized(lock) { changes.toString() }

    private fun diff(old: JSONObject?, next: JSONObject): JSONObject {
        if (old == null) return JSONObject()
            .put("available", true).put("initial", true)
            .put("meaningful", true).put("changed", JSONArray(listOf("initial_snapshot")))
        val changed = JSONArray()
        fun compare(label: String, a: Any?, b: Any?) {
            if ((a?.toString() ?: "null") != (b?.toString() ?: "null")) changed.put(label)
        }
        compare("turn", old.opt("turn"), next.opt("turn"))
        compare("month", old.opt("month"), next.opt("month"))
        compare("half", old.opt("half"), next.opt("half"))
        compare("scenario", old.opt("scenario"), next.opt("scenario"))
        compare("stats", old.optJSONObject("stats"), next.optJSONObject("stats"))
        compare("trainings", old.optJSONArray("trainings"), next.optJSONArray("trainings"))
        compare("ramen", old.optJSONObject("ramen"), next.optJSONObject("ramen"))
        compare("ai", old.optJSONObject("ai"), next.optJSONObject("ai"))
        return JSONObject()
            .put("available", true)
            .put("initial", false)
            .put("meaningful", changed.length() > 0)
            .put("changed", changed)
    }
}
