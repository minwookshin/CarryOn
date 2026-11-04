package com.minwook.pleoswork

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Retained while the work task lives; a native map taking focus must not release its listener. */
class WorkSessionViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CompanionRepository(application)
    val gateway = PleosGateway(application)
    private val coordinator = NativeGuidanceCoordinator(
        work = { repository.state.value }, update = repository::update,
        vehicle = { gateway.state.value }, requestDestination = gateway::requestDestination,
    )

    init {
        gateway.start()
        viewModelScope.launch {
            combine(repository.state, gateway.state) { _, _ -> Unit }.collect { coordinator.synchronize() }
        }
        viewModelScope.launch {
            while (true) { delay(1000); coordinator.synchronize() }
        }
    }

    fun reconnect() { gateway.stop(); gateway.start() }

    override fun onCleared() { gateway.stop(); super.onCleared() }
}
