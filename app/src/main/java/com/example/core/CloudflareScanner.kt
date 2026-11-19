package com.example.core

import com.example.data.local.CleanIpEntity
import com.example.data.model.ScanProgress
import com.example.data.model.ScannerConfig
import com.example.scanner.CloudflareCleanIpScanner

/**
 * Facade delegating to [CloudflareCleanIpScanner] for backward compatibility across the app and tests.
 */
object CloudflareScanner {
    val defaultCloudflareCidrs: List<String> = CloudflareCleanIpScanner.officialCloudflareCidrs

    suspend fun runScan(
        config: ScannerConfig,
        onProgress: (ScanProgress) -> Unit,
        onHit: (CleanIpEntity) -> Unit,
    ): List<CleanIpEntity> = CloudflareCleanIpScanner.runScan(config, onProgress, onHit)

    fun sampleIpsFromCidr(cidr: String, count: Int): List<String> =
        CloudflareCleanIpScanner.sampleIpsFromCidr(cidr, count)
}
