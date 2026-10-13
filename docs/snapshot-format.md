# Snapshot format

The Mac collector writes one JSON file. The watch downloads it from any URL and shows it.
Only usage numbers go in here. No tokens, emails, or account IDs.

```json
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
```

| Field | Meaning |
|---|---|
| `schema` | Format version. Currently `1`. |
| `generated_at` | When the collector ran, UTC, ISO 8601 with `Z`. |
| `providers[].id` | Stable lowercase id (`claude`, `codex`). |
| `providers[].name` | Display name. |
| `providers[].plan` | Plan name for display, or `null` if unknown. |
| `providers[].status` | `ok`, `auth_expired` (sign in again on the Mac), or `error`. |
| `providers[].message` | Short human-readable reason when status is not `ok`, else `null`. Under 40 characters. |
| `limits[].id` | Stable id within the provider. |
| `limits[].label` | Short display label, 14 characters or fewer. |
| `limits[].percent` | Used share of the limit, 0 to 100. May be a decimal. |
| `limits[].resets_at` | When the window resets, UTC ISO 8601 with `Z`, or `null`. |

Providers with a non-`ok` status keep an empty `limits` list. Readers must ignore unknown fields.
