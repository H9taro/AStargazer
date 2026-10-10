package jp.hisanet.astargazer

import android.os.Bundle
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
}
