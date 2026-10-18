package jp.hisanet.astargazer

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import jp.hisanet.astargazer.ui.MainScreen
import jp.hisanet.astargazer.ui.theme.AStargazerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AStargazerTheme {
                MainScreen()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("OrientationDebug", "MainActivity onConfigurationChanged: orientation=${newConfig.orientation}")
    }
}
