package io.github.whyater.wristusage.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import io.github.whyater.wristusage.R
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.domain.RefreshPolicy
import io.github.whyater.wristusage.ui.MainActivity
import io.github.whyater.wristusage.work.Refresher
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The main surface. Renders from the cache immediately and, when [RefreshPolicy] allows it, queues a
 * background refresh that pushes a new tile when it lands.
 */
class UsageTileService : TileService() {

    override fun onTileAddEvent(requestParams: EventBuilders.TileAddEvent) {
        background.execute {
            Refresher.schedulePeriodic(this)
            Refresher.refreshNow(this)
        }
    }

    // Reading the cache and parsing JSON happen on the background executor, not the main thread.
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            background.execute {
                try {
                    completer.set(buildTile(requestParams))
                } catch (t: Throwable) {
                    completer.setException(t)
                }
            }
            "UsageTileService.onTileRequest"
        }

    private fun buildTile(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val repo = SnapshotRepository(this)
        val now = Instant.now()
        var state = repo.load(now)

        val tapId = requestParams.currentState.lastClickableId
        val tapped = UsageTileLayout.isRefreshTap(tapId) && repo.consumeTap(tapId)

        if (RefreshPolicy.shouldRefresh(state, now, forced = tapped) && Refresher.requestRefresh(this)) {
            state = state.copy(refreshing = true)
        }
        Refresher.schedulePeriodic(this)

        val layout = UsageTileLayout.render(
            state = state,
            now = now,
            screenDp = requestParams.deviceConfiguration.screenWidthDp.toFloat(),
            targets = TileTargets(packageName, MainActivity::class.java.name),
        )
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS.toMillis())
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(RESET_ICON_ID, drawableImage(R.drawable.ic_tile_reset))
            .addIdToImageMapping(REFRESH_ICON_ID, drawableImage(R.drawable.ic_tile_refresh))
            .build()
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(resources)
            "UsageTileService.onTileResourcesRequest"
        }
    }

    private fun drawableImage(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build(),
            )
            .build()

    private companion object {
        val background: Executor = Executors.newSingleThreadExecutor()

        /** The host caches tile images by this version. Bump it whenever a tile drawable changes. */
        const val RESOURCES_VERSION = "1"

        /** The system asks for a fresh layout this often while the tile is in use, which keeps "Updated 4m ago" honest. */
        val FRESHNESS: Duration = Duration.ofMinutes(5)
    }
}
