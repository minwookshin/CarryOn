package com.minwook.pleoswork

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WorkSessionViewModel(application: Application) : AndroidViewModel(application) {
    val board = OperationsRepository(application)
    val gateway = PleosGateway(application)
    private val credentials = WorkCredentialStore(application)
    val apiBase = BuildConfig.WORK_API_URL.ifBlank { if (BuildConfig.DEBUG) "http://127.0.0.1:8797" else "" }
    private val api = WorkServiceClient(apiBase)
    private var tokens: WorkTokens? = credentials.load()?.takeIf { it.issuer == apiBase }
    private var connectionJob: Job? = null
    private var connectionGeneration = 0
    var foreground = false
    var workVisible by mutableStateOf(false)

    init {
        gateway.start()
        viewModelScope.launch { gateway.state.collect { board.vehicle(it) } }
        viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000)
                val elapsed = SystemClock.elapsedRealtime()
                val car = gateway.state.value
                val route = board.state.value.route
                if (workVisible && board.state.value.canPresentNative && route?.mode == GuidanceMode.NATIVE_SAMPLE && route.status == GuidanceStatus.REQUESTED &&
                    route.issuedAt == 0L && route.target != null && car.canWork && car.navigationStatus == "연결됨") {
                    val accepted = gateway.requestDestination(route.target)
                    board.update { s -> if (s.route != route) s else s.copy(route = route.copy(startedAt = System.currentTimeMillis(), issuedAt = System.currentTimeMillis(),
                        status = if (accepted) GuidanceStatus.REQUESTED else GuidanceStatus.FAILED,
                        message = if (accepted) "목적지 전달됨" else "목적지를 전달하지 못했습니다")) }
                }
                board.tick(car, System.currentTimeMillis(), elapsed - last, foreground && workVisible)
                last = elapsed
            }
        }
        if (tokens != null) sync()
    }

    fun selectMode(mode: ServiceMode, refrigerated: Boolean) {
        if (!gateway.state.value.canWork) return
        board.selectMode(mode, refrigerated)
    }
    fun loadExample() {
        if (!gateway.state.value.canWork) return
        disconnect(); board.loadExample()
    }
    fun beginConnection() {
        if (!gateway.state.value.canWork || board.state.value.connecting) return
        connectionJob?.cancel()
        val generation = ++connectionGeneration
        board.update { it.copy(connecting = true, grant = null, connectionMessage = "연결 코드 준비 중") }
        connectionJob = viewModelScope.launch {
            try {
                val grant = api.begin()
                if (generation != connectionGeneration) return@launch
                board.update { it.copy(grant = grant, connectionMessage = "단말에서 코드를 확인하고 승인하세요") }
                val end = SystemClock.elapsedRealtime() + grant.expiresIn * 1000
                var interval = grant.interval.coerceAtLeast(1) * 1000
                var received: WorkTokens? = null
                while (isActive && SystemClock.elapsedRealtime() < end && received == null) {
                    delay(interval)
                    try { received = api.poll(grant) } catch (e: WorkApiFailure) {
                        when (e.code) { "authorization_pending" -> Unit; "slow_down" -> interval += 5000; else -> throw e }
                    }
                }
                if (received == null) throw WorkApiFailure("expired_token")
                if (generation != connectionGeneration) return@launch
                credentials.save(received); tokens = received
                board.update { it.copy(connecting = false, grant = null, connectionMessage = "업무 기록 수신 중") }
                synchronizeLoop(generation, forceSnapshot = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == connectionGeneration) failure(e, true) }
        }
    }
    private fun sync() {
        connectionJob?.cancel()
        val generation = ++connectionGeneration
        connectionJob = viewModelScope.launch { synchronizeLoop(generation, false) }
    }
    private suspend fun synchronizeLoop(generation: Int, forceSnapshot: Boolean) {
        var snapshotNeeded = forceSnapshot
        var retry = 2000L
        while (generation == connectionGeneration) {
            try {
                var token = tokens ?: return
                if (System.currentTimeMillis() >= token.storedAt + token.expiresIn * 1000 - 30_000) {
                    token = api.refresh(token)
                    if (generation != connectionGeneration) return
                    credentials.save(token); tokens = token
                }
                val ledger = board.state.value.ledger
                if (snapshotNeeded || ledger == null || ledger.accountId != token.accountId || ledger.streamId.startsWith("local-") || ledger.streamId.startsWith("legacy:")) {
                    val snapshot = api.snapshot(token)
                    if (generation != connectionGeneration) return
                    board.ledger(snapshot.copy(lastSyncAt = System.currentTimeMillis())); snapshotNeeded = false
                } else {
                    val batch = api.changes(token, ledger.cursor)
                    if (generation != connectionGeneration) return
                    if (batch.streamId != ledger.streamId) { snapshotNeeded = true; continue }
                    board.ledger(OperationsReducer.batch(ledger, batch, System.currentTimeMillis()))
                }
                board.update { it.copy(connected = true, connecting = false, connectionMessage = "연결됨") }
                retry = 2000
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation != connectionGeneration) return
                if (e is WorkApiFailure && (e.httpStatus == 401 || e.code in setOf("invalid_grant", "access_denied"))) {
                    tokens = null; credentials.clear(); failure(e, true); return
                }
                failure(e, false); retry = (retry * 2).coerceAtMost(30_000)
            }
            delay(retry)
        }
    }
    private fun failure(e: Exception, authorization: Boolean) {
        val message = when ((e as? WorkApiFailure)?.code) {
            "expired_token" -> "연결 코드 만료 · 새 코드를 요청하세요"
            "access_denied" -> "단말에서 연결을 거절했습니다"
            "invalid_grant", "invalid_token" -> "다시 연결 필요 · 작업 기록은 보존됩니다"
            "endpoint_not_configured" -> "회사 연결 주소가 설정되지 않았습니다"
            else -> if (authorization) "연결할 수 없습니다 · 단말 시험 서버를 확인하세요" else "연결 끊김 · 마지막 기록을 표시합니다"
        }
        board.update { it.copy(connected = false, connecting = false, grant = if (authorization) null else it.grant, connectionMessage = message) }
    }
    fun disconnect() {
        connectionGeneration++; connectionJob?.cancel(); connectionJob = null
        val old = tokens; tokens = null; credentials.clear()
        board.update { it.copy(connected = false, connecting = false, grant = null, connectionMessage = "연결 중지 · 작업 기록 보존") }
        if (old != null) viewModelScope.launch { runCatching { api.revoke(old) } }
    }
    fun reconnect() { gateway.stop(); gateway.start() }

    override fun onCleared() { gateway.stop(); super.onCleared() }
}
