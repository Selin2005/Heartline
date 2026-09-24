package com.heartline.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.heartline.wear.ui.HeartlineWearApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent?.getStringExtra(EXTRA_ROUTE)
        setContent { HeartlineWearApp(startRoute = route) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_BP = "blood_pressure"
        const val ROUTE_ECG = "ecg"
        const val ROUTE_HEART_RATE = "heart_rate"
    }
}
