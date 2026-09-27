package com.example.astargazer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.astargazer.ui.MainScreen
import com.example.astargazer.ui.theme.AStargazerTheme

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
