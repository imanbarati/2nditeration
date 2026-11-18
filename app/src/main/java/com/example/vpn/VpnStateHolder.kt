package com.example.vpn

import com.example.data.model.ConnectionStatus
import com.example.data.model.TunnelMode
import com.example.data.model.TunnelStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {
    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    fun updateStatus(
        status: ConnectionStatus,
        mode: TunnelMode? = null,
        profileName: String? = null,
        endpoint: String? = null,
        chainLabel: String? = null,
        localSocksAddress: String? = null,
        verifiedLatencyMs: Long? = null,
        errorMessage: String? = null,
    ) {
        _stats.update { current ->
            val resetTimer = status == ConnectionStatus.CONNECTED && current.status != ConnectionStatus.CONNECTED
            current.copy(
                status = status,
                mode = mode ?: current.mode,
                activeProfileName = profileName ?: current.activeProfileName,
                activeEndpoint = endpoint ?: current.activeEndpoint,
                activeChainLabel = chainLabel ?: current.activeChainLabel,
                localSocksAddress = if (status == ConnectionStatus.IDLE || status == ConnectionStatus.ERROR) null
                else localSocksAddress ?: current.localSocksAddress,
                startedAtMs = when {
                    resetTimer -> System.currentTimeMillis()
                    status == ConnectionStatus.IDLE || status == ConnectionStatus.ERROR -> 0L
                    else -> current.startedAtMs
                },
                rxBytes = if (resetTimer || status == ConnectionStatus.IDLE) 0L else current.rxBytes,
                txBytes = if (resetTimer || status == ConnectionStatus.IDLE) 0L else current.txBytes,
                dnsQueriesHandled = if (resetTimer || status == ConnectionStatus.IDLE) 0L else current.dnsQueriesHandled,
                blockedQueriesCount = if (resetTimer || status == ConnectionStatus.IDLE) 0L else current.blockedQueriesCount,
                proxiedConnections = if (resetTimer || status == ConnectionStatus.IDLE) 0L else current.proxiedConnections,
                verifiedLatencyMs = verifiedLatencyMs ?: if (status == ConnectionStatus.IDLE) null else current.verifiedLatencyMs,
                errorMessage = errorMessage
            )
        }
    }

    fun recordPacketTraffic(
        txDelta: Long,
        rxDelta: Long,
        dnsIncrement: Long = 0L,
        blockedIncrement: Long = 0L,
        proxiedIncrement: Long = 0L,
    ) {
        _stats.update { current ->
            current.copy(
                txBytes = current.txBytes + txDelta,
                rxBytes = current.rxBytes + rxDelta,
                dnsQueriesHandled = current.dnsQueriesHandled + dnsIncrement,
                blockedQueriesCount = current.blockedQueriesCount + blockedIncrement,
                proxiedConnections = current.proxiedConnections + proxiedIncrement
            )
        }
    }
}
