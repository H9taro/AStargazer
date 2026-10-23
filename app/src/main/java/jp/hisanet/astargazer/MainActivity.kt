package jp.hisanet.astargazer

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import jp.hisanet.astargazer.ui.MainScreen
import jp.hisanet.astargazer.ui.theme.AStargazerTheme
import jp.hisanet.astargazer.util.DeviceOrientationMonitor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceOrientationMonitor.start(applicationContext)
        enableEdgeToEdge()
        setContent {
            AStargazerTheme {
                MainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("BackgroundCapture", "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d("BackgroundCapture", "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d("BackgroundCapture", "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.d("BackgroundCapture", "onStop")
    }

    override fun onDestroy() {
        DeviceOrientationMonitor.stop()
        super.onDestroy()
        Log.d("BackgroundCapture", "onDestroy")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("OrientationDebug", "MainActivity onConfigurationChanged: orientation=${newConfig.orientation}")
    }
}
