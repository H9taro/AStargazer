package jp.hisanet.astargazer.model

sealed class IntervalShootingState {
    object Idle : IntervalShootingState()
    object Preparing : IntervalShootingState()
    data class Shooting(
        val shotCount: Int,
        val remainingShots: Int,
        val elapsedSeconds: Int,
        val latestImagePath: String?,
        val exposureSeconds: Double,
        val iso: Int
    ) : IntervalShootingState()
    object Stopping : IntervalShootingState()
    data class Error(val message: String) : IntervalShootingState()
}
