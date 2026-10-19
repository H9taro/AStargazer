package jp.hisanet.astargazer.util

import android.content.Context
import android.content.Intent
import android.location.Location
import jp.hisanet.astargazer.model.IntervalShootingState
import jp.hisanet.astargazer.service.IntervalShootingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object IntervalShootingManager {
    private val _state = MutableStateFlow<IntervalShootingState>(IntervalShootingState.Idle)
    val state: StateFlow<IntervalShootingState> = _state.asStateFlow()

    fun updateState(newState: IntervalShootingState) {
        _state.value = newState
    }

    fun startService(
        context: Context,
        exposureSeconds: Double,
        iso: Int,
        location: Location?
    ) {
        val intent = Intent(context, IntervalShootingService::class.java).apply {
            action = IntervalShootingService.ACTION_START
            putExtra(IntervalShootingService.EXTRA_EXPOSURE_SECONDS, exposureSeconds)
            putExtra(IntervalShootingService.EXTRA_ISO, iso)
            putExtra(IntervalShootingService.EXTRA_LOCATION, location)
        }
        context.startForegroundService(intent)
    }

    fun stopService(context: Context) {
        val intent = Intent(context, IntervalShootingService::class.java).apply {
            action = IntervalShootingService.ACTION_STOP
        }
        context.startService(intent)
    }
}
