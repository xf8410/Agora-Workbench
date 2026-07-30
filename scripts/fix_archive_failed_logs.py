from pathlib import Path

p = Path('app/src/main/java/com/newoether/agora/tool/GitHubToolProvider.kt')
s = p.read_text()
a = s.find('    private suspend fun failedLogs(')
b = s.find('    private suspend fun dispatch(', a)
if a < 0 or b < 0:
    raise SystemExit('failedLogs markers missing')
new = r'''    private suspend fun failedLogs(repo: String, runId: Long, maxChars: Int): String {
        val jobs = client.request("GET", "/repos/$repo/actions/runs/$runId/jobs?per_page=100")
        requireOk(jobs.code, jobs.body)
        val failed = (json.parseToJsonElement(jobs.body).jsonObject["jobs"]?.jsonArray
            ?: JsonArray(emptyList())).filter { it.jsonObject.string("conclusion") == "failure" }
        var remaining = maxChars
        val entries = mutableListOf<JsonObject>()
        for (element in failed) {
            if (remaining <= 0) break
            val job = element.jsonObject
            val budget = minOf(remaining, 20_000)
            val response = client.requestBounded(
                "GET", "/repos/$repo/actions/jobs/${job.long("id")}/logs", maxChars = budget
            )
            val focused = response.body.lineSequence().filter { line ->
                val lower = line.lowercase()
                "error" in lower || "failed" in lower || "exception" in lower ||
                    "> task" in lower || "e:" in lower
            }.take(250).joinToString("\n").ifBlank { response.body.take(budget) }
            remaining -= focused.length
            entries += buildJsonObject {
                put("job_id", job.long("id"))
                put("name", job.string("name"))
                put("log", focused)
                put("truncated", response.truncated || response.body.length > focused.length)
            }
        }
        return buildJsonObject {
            put("run_id", runId)
            put("failed_jobs", JsonArray(entries))
            put("truncated", remaining <= 0)
        }.toString()
    }

'''
p.write_text(s[:a] + new + s[b:])
