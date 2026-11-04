package com.minwook.pleoswork

import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var session: WorkSessionViewModel
    private var launchToken by mutableLongStateOf(0L)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::session.isInitialized) session.reconnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchToken = savedInstanceState?.getLong("native-companion-launch") ?: System.currentTimeMillis()
        session = ViewModelProvider(this)[WorkSessionViewModel::class.java]
        val repository = session.repository
        val gateway = session.gateway
        val needed = listOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN", "pleos.car.permission.CAR_DOORS", "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH", "pleos.car.permission.NAVI_CUSTOM_MAP")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        setContent {
            val work by repository.state.collectAsStateWithLifecycle()
            val vehicle by gateway.state.collectAsStateWithLifecycle()
            val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            var presentedLaunch by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
            // One public handoff per launcher entry. Route/state callbacks and onResume
            // must not reopen navigation or repeatedly swap the user's two windows.
            LaunchedEffect(launchToken, work.guidanceMode, vehicle.navigationStatus, vehicle.canWork, lifecycle) {
                if (work.guidanceMode == GuidanceMode.NATIVE_SAMPLE && vehicle.canWork &&
                    vehicle.navigationStatus == "연결됨" && lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
                    presentedLaunch != launchToken) {
                    // Let Pleos finish the launcher transition before handing off.
                    // Leaving the visible lifecycle cancels the pending handoff.
                    delay(750)
                    presentedLaunch = launchToken
                    launchNativeCompanion(gateway)
                }
            }
            CarbonTheme {
                VehicleCompanionApp(work, vehicle, repository, gateway)
            }
        }
    }

    private fun launchNativeCompanion(gateway: PleosGateway) {
        // Use Android's public launcher entry, not a private navigation activity.
        // On 2.1.0, the SDK Foreground request alone can be ignored during app entry.
        val launcher = packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app")
        if (launcher == null) {
            gateway.openNavigation()
            return
        }
        runCatching { startActivity(launcher) }
            .onSuccess { android.util.Log.i("PleosWork", "Native companion launcher request sent") }
            .onFailure { gateway.openNavigation() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) launchToken++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("native-companion-launch", launchToken)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (packageName.endsWith(".qa") && event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(android.view.KeyEvent.KEYCODE_TAB, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_ESCAPE)) {
            android.util.Log.d("WorkKeyboardQA", "key=${event.keyCode} shift=${event.isShiftPressed} alt=${event.isAltPressed}")
        }
        return super.dispatchKeyEvent(event)
    }
}
