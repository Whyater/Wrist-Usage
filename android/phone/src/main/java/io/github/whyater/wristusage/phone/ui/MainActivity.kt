package io.github.whyater.wristusage.phone.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.whyater.wristusage.phone.graph
import io.github.whyater.wristusage.phone.sync.WatchLabels
import kotlinx.coroutines.delay
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = applicationContext.graph
        setContent {
            PhoneTheme {
                val vm: MainViewModel = viewModel(
                    factory = viewModelFactory { initializer { MainViewModel(graph.sync, graph.scheduler) } },
                )
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }

                val state by vm.state.collectAsStateWithLifecycle()
                val watch by vm.watch.collectAsStateWithLifecycle()
                val setup by vm.setup.collectAsStateWithLifecycle()
                val showSetup by vm.showSetup.collectAsStateWithLifecycle()
                val now by rememberNow(state)

                if (showSetup) {
                    SetupScreen(
                        ui = setup,
                        hasSavedUrl = state.url != null,
                        onInput = vm::onInput,
                        onSave = vm::save,
                        onClear = vm::clear,
                        onCancel = vm::closeSetup,
                    )
                } else {
                    UsageScreen(
                        state = state,
                        watch = WatchLabels.of(watch, state.lastPushAt, now),
                        now = now,
                        onRefresh = vm::refresh,
                        onOpenSetup = vm::openSetup,
                    )
                }
            }
        }
    }
}

/** The current time, refreshed every 30 seconds while the screen is visible and whenever [key] changes. */
@Composable
private fun rememberNow(key: Any?): State<Instant> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(Instant.now(), key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                delay(30_000)
            }
        }
    }
}
