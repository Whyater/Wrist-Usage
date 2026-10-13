package io.github.whyater.wristusage.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.domain.ComplicationContent
import io.github.whyater.wristusage.domain.UsageFormat
import io.github.whyater.wristusage.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Shows the highest used percent across every limit, as a ring (RANGED_VALUE) or as text (SHORT_TEXT). */
class UsageComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, percent = 41.0, title = "Claude", description = "Highest usage 41 percent")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // The cache read parses JSON, so keep it off the main thread.
        val state = withContext(Dispatchers.IO) { SnapshotRepository(this@UsageComplicationService).load() }
        val content = ComplicationContent.from(state.snapshot, Instant.now())
        return build(request.complicationType, content.percent, content.title, content.description)
    }

    private fun build(type: ComplicationType, percent: Double?, title: String, description: String): ComplicationData? {
        val text = PlainComplicationText.Builder(percent?.let(UsageFormat::percentText) ?: "--").build()
        val titleText = PlainComplicationText.Builder(title).build()
        val content = PlainComplicationText.Builder(description).build()
        val tap = tapIntent()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (percent ?: 0.0).toFloat().coerceIn(0f, 100f),
                min = 0f,
                max = 100f,
                contentDescription = content,
            ).setText(text).setTitle(titleText).setTapAction(tap).build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, content)
                .setTitle(titleText)
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
