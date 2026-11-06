package com.example.fluffydisco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.fluffydisco.navigation.AppNavigation
import com.example.fluffydisco.ui.theme.FluffyDiscoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FluffyDiscoTheme {
                AppNavigation()
            }
        }
    }
}