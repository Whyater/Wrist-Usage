package io.github.whyater.wristusage.data

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Thrown when the downloaded JSON cannot be shown. [message] is short enough for the UI. */
class SnapshotException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Tolerant reader for the snapshot JSON: unknown fields are ignored, only schema 1 is accepted. */
object SnapshotParser {
    const val SUPPORTED_SCHEMA = 1

    @Throws(SnapshotException::class)
    fun parse(raw: String): Snapshot {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw SnapshotException("Not valid JSON", e)
        }

        if (!isSupportedSchema(root.opt("schema"))) {
            throw SnapshotException(
                if (root.isNull("schema")) "Missing schema" else "Unsupported format (schema ${root.opt("schema")})",
            )
        }

        val generatedAt = parseInstant(root.optStringOrNull("generated_at"))
            ?: throw SnapshotException("Missing or bad generated_at")

        val providersJson = root.optJSONArray("providers")
            ?: throw SnapshotException("Missing providers")

        val providers = buildList {
            for (i in 0 until providersJson.length()) {
                val obj = providersJson.optJSONObject(i) ?: continue
                parseProvider(obj)?.let(::add)
            }
        }
        // Later duplicates are dropped so ids stay unique (the app uses them to tell rows apart).
        return Snapshot(generatedAt, providers.distinctBy { it.id })
    }

    /** Only a JSON number equal to 1 passes: not 1.9, not the string "1". */
    private fun isSupportedSchema(value: Any?): Boolean =
        value is Number && value.toDouble() == SUPPORTED_SCHEMA.toDouble()

    private fun parseProvider(obj: JSONObject): Provider? {
        val id = obj.optStringOrNull("id")
        val name = obj.optStringOrNull("name") ?: id ?: return null
        val status = when (obj.optStringOrNull("status")) {
            "ok" -> ProviderStatus.Ok
            "auth_expired" -> ProviderStatus.AuthExpired
            else -> ProviderStatus.Error
        }

        val limitsJson = obj.optJSONArray("limits")
        val limits = buildList {
            if (limitsJson != null) {
                for (i in 0 until limitsJson.length()) {
                    val l = limitsJson.optJSONObject(i) ?: continue
                    parseLimit(l)?.let(::add)
                }
            }
        }

        return Provider(
            id = id ?: name.lowercase(),
            name = name,
            plan = obj.optStringOrNull("plan"),
            status = status,
            message = obj.optStringOrNull("message"),
            limits = limits.distinctBy { it.id },
        )
    }

    private fun parseLimit(obj: JSONObject): UsageLimit? {
        if (!obj.has("percent") || obj.isNull("percent")) return null
        val percent = obj.optDouble("percent", Double.NaN)
        if (percent.isNaN()) return null
        val id = obj.optStringOrNull("id")
        val label = obj.optStringOrNull("label") ?: id ?: return null
        return UsageLimit(
            id = id ?: label,
            label = label,
            percent = percent.coerceIn(0.0, 100.0),
            resetsAt = parseInstant(obj.optStringOrNull("resets_at")),
        )
    }

    private fun parseInstant(value: String?): Instant? {
        if (value == null) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** `optString` returns "null" for JSON null and "" for missing keys; this returns a real null. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
