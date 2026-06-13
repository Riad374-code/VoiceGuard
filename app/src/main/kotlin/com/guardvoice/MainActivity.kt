package com.guardvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.guardvoice.ui.GuardVoiceApp
import com.guardvoice.ui.theme.GuardVoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GuardVoiceTheme {
                GuardVoiceApp()
            }
        }
    }
}
