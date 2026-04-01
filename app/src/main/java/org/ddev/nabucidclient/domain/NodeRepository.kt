package org.ddev.nabucidclient.domain

import org.ddev.nabucidclient.network.FetchResult
import org.ddev.nabucidclient.network.NabuNodeClient

class NodeRepository(
    private val nabuNodeClient: NabuNodeClient = NabuNodeClient()
) {
    fun connect(multiAddress: String): Result<Long> = runCatching {
        nabuNodeClient.connect(multiAddress)
    }

    fun fetchByCid(cid: String): Result<FetchResult> = runCatching {
        nabuNodeClient.fetchBlockByCid(cid)
    }

    fun ping(): Result<Long> = runCatching {
        nabuNodeClient.pingCurrentNode()
    }

    fun shutdown() {
        nabuNodeClient.shutdown()
    }
}
