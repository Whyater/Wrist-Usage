package io.github.whyater.wristusage

import io.github.whyater.wristusage.data.SnapshotParser

object TestData {
    /** The example from docs/snapshot-format.md. */
    const val DOC_SAMPLE = """
{
  "schema": 1,
  "generated_at": "2026-10-13T04:23:52Z",
  "providers": [
    {
      "id": "claude",
      "name": "Claude",
      "plan": "Max 20x",
      "status": "ok",
      "message": null,
      "limits": [
        { "id": "session", "label": "5-hour", "percent": 14, "resets_at": "2026-10-13T04:39:59Z" },
        { "id": "weekly", "label": "Weekly", "percent": 41, "resets_at": "2026-10-14T13:59:59Z" },
        { "id": "weekly_fable", "label": "Fable weekly", "percent": 0, "resets_at": "2026-10-14T14:00:00Z" }
      ]
    },
    {
      "id": "codex",
      "name": "Codex",
      "plan": "Pro Lite",
      "status": "ok",
      "message": null,
      "limits": [
        { "id": "weekly", "label": "Weekly", "percent": 8, "resets_at": "2026-10-17T19:50:59Z" }
      ]
    }
  ]
}
"""

    fun sample() = SnapshotParser.parse(DOC_SAMPLE)

    /** Builds a one-provider JSON with the given limit percents, for planner tests. */
    fun json(providers: List<Triple<String, String, List<Double>>>, status: String = "ok"): String {
        val body = providers.joinToString(",") { (id, name, percents) ->
            val limits = percents.mapIndexed { i, p ->
                """{"id":"l$i","label":"Limit $i","percent":$p,"resets_at":"2026-10-13T08:00:00Z"}"""
            }.joinToString(",")
            """{"id":"$id","name":"$name","plan":null,"status":"$status","message":null,"limits":[$limits]}"""
        }
        return """{"schema":1,"generated_at":"2026-10-13T04:00:00Z","providers":[$body]}"""
    }
}
