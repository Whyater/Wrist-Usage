package io.github.whyater.wristusage.phone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.whyater.wristusage.phone.sync.SaveResult
import io.github.whyater.wristusage.phone.sync.SyncState
import io.github.whyater.wristusage.phone.sync.UsageSync
import io.github.whyater.wristusage.phone.sync.WatchStatus
import io.github.whyater.wristusage.phone.work.FetchScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What the setup screen shows. [error] sits under the URL field. */
data class SetupUi(val input: String = "", val busy: Boolean = false, val error: String? = null)

class MainViewModel(
    private val sync: UsageSync,
    private val scheduler: FetchScheduler,
) : ViewModel() {
    val state: StateFlow<SyncState> = sync.state

    private val _watch = MutableStateFlow<WatchStatus?>(null)
    val watch: StateFlow<WatchStatus?> = _watch.asStateFlow()

    private val _setup = MutableStateFlow(SetupUi())
    val setup: StateFlow<SetupUi> = _setup.asStateFlow()

    private val _showSetup = MutableStateFlow(sync.state.value.url == null)

    /** True on first launch (no URL yet) and whenever the user opens the setup screen. */
    val showSetup: StateFlow<Boolean> = _showSetup.asStateFlow()

    private var lastAutoRefresh: Instant? = null

    init {
        // A watch app installed later can only learn the URL if we put it again.
        viewModelScope.launch { sync.pushConfig() }
        if (sync.state.value.url != null) scheduler.ensurePeriodic()
    }

    /** Called each time the screen comes to the front. */
    fun onStart() {
        viewModelScope.launch { _watch.value = sync.watchStatus() }
        val now = Instant.now()
        val last = lastAutoRefresh
        if (sync.state.value.url != null && (last == null || Duration.between(last, now) > AUTO_REFRESH_GAP)) {
            lastAutoRefresh = now
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            sync.refresh()
            _watch.value = sync.watchStatus()
        }
    }

    fun openSetup() {
        _setup.value = SetupUi(input = sync.state.value.url.orEmpty())
        _showSetup.value = true
    }

    fun closeSetup() {
        if (sync.state.value.url != null) _showSetup.value = false
    }

    fun onInput(text: String) {
        _setup.update { it.copy(input = text, error = null) }
    }

    fun save() {
        val current = _setup.value
        if (current.busy) return
        _setup.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = sync.saveUrl(current.input)) {
                is SaveResult.Rejected -> _setup.update { it.copy(busy = false, error = result.message) }
                is SaveResult.Saved -> {
                    scheduler.ensurePeriodic()
                    lastAutoRefresh = Instant.now()
                    _setup.value = SetupUi()
                    _showSetup.value = false
                    _watch.value = sync.watchStatus()
                }
            }
        }
    }

    fun clear() {
        if (_setup.value.busy) return
        _setup.update { it.copy(busy = true) }
        viewModelScope.launch {
            sync.clearUrl()
            scheduler.cancelPeriodic()
            _setup.value = SetupUi()
            _showSetup.value = true
        }
    }

    private companion object {
        val AUTO_REFRESH_GAP: Duration = Duration.ofSeconds(60)
    }
}
