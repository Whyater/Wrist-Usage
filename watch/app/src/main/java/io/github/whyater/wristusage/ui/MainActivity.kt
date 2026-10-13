package io.github.whyater.wristusage.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import io.github.whyater.wristusage.data.SnapshotRepository
import io.github.whyater.wristusage.domain.RefreshPolicy
import io.github.whyater.wristusage.work.Refresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UsageTheme { AppScaffold { UsageRoute() } } }
    }
}

/** Owns the data for the screen: cache first, then a refresh if the cache is old. */
@Composable
private fun UsageRoute() {
    val context = LocalContext.current
    val repo = remember { SnapshotRepository(context) }
    var state by remember { mutableStateOf(repo.load()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The worker writes to the same cache, so follow it: the screen updates even if this screen did not ask.
    LaunchedEffect(repo) { repo.changes().collect { state = it } }

    // Ticks so countdowns and "Updated ... ago" stay right while the screen is open.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }

    fun refresh() {
        // A tap always refreshes, unless a fetch is already running.
        if (busy || !RefreshPolicy.shouldRefresh(state, Instant.now(), forced = true)) return
        scope.launch {
            busy = true
            // Tell the tile and complication inside the IO block, so leaving the app mid-refresh
            // cannot skip it.
            state = withContext(Dispatchers.IO) {
                repo.refresh().also { Refresher.requestSurfaceUpdates(context) }
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (RefreshPolicy.shouldRefresh(state, Instant.now())) refresh()
    }

    UsageScreen(state = state, now = now, refreshing = busy, onRefresh = ::refresh)
}
