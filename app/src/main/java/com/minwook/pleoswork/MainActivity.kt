package com.minwook.pleoswork

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var session: WorkSessionViewModel
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::session.isInitialized) session.reconnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[WorkSessionViewModel::class.java]
        val repository = session.board
        val gateway = session.gateway
        val needed = listOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_ENERGY", "pleos.car.permission.CAR_ENERGY", "pleos.car.permission.CAR_DOORS", "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH", "pleos.car.permission.NAVI_CUSTOM_MAP")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        setContent {
            val work by repository.state.collectAsStateWithLifecycle()
            val vehicle by gateway.state.collectAsStateWithLifecycle()
            val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            LaunchedEffect(lifecycle) { session.foreground = lifecycle.isAtLeast(Lifecycle.State.STARTED) }
            // Foreground handoff is requested by the navigation controls only.
            CarbonTheme {
                OperationsApp(work, vehicle, session)
            }
        }
    }

}
