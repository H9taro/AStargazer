package jp.hisanet.astargazer

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.hisanet.astargazer.util.DeviceOrientationMonitor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceOrientationMonitorTest {

    @Test
    fun testComputeDeviceRotation() {
        assertEquals(0, DeviceOrientationMonitor.computeDeviceRotation(0))
        assertEquals(0, DeviceOrientationMonitor.computeDeviceRotation(20))
        assertEquals(0, DeviceOrientationMonitor.computeDeviceRotation(350))
        assertEquals(270, DeviceOrientationMonitor.computeDeviceRotation(90))
        assertEquals(180, DeviceOrientationMonitor.computeDeviceRotation(180))
        assertEquals(90, DeviceOrientationMonitor.computeDeviceRotation(270))
    }

    @Test
    fun testComputeJpegOrientation() {
        // sensorOrientation = 90 の場合の JPEG_ORIENTATION 検証
        assertEquals(90, DeviceOrientationMonitor.computeJpegOrientation(90, 0))
        assertEquals(90, DeviceOrientationMonitor.computeJpegOrientation(90, 90))
        assertEquals(270, DeviceOrientationMonitor.computeJpegOrientation(90, 180))
        assertEquals(270, DeviceOrientationMonitor.computeJpegOrientation(90, 270))
    }
}
