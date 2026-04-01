package org.ddev.nabucidclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.ddev.nabucidclient.domain.NodeRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class NodeFlowIntegrationTest {

    @Test
    fun connectAndFetchCid_fromPublicNode() {
        val repository = NodeRepository()
        val connect = repository.connect(
            "/dns4/ipfs.infra.cf.team/tcp/4001/p2p/12D3KooWKiqj21VphU2eE25438to5xeny6eP6d3PXT93ZczagPLT"
        )
        assertTrue(connect.isSuccess)
        assertTrue((connect.getOrNull() ?: 0L) >= 0L)

        val fetched = repository.fetchByCid("QmTBimFzPPP2QsB7TQGc2dr4BZD4i7Gm2X1mNtb6DqN9Dr")
        val ping = repository.ping()
        repository.shutdown()

        assertTrue(fetched.isSuccess)
        assertTrue(ping.isSuccess)
        assertTrue((fetched.getOrNull()?.sizeBytes ?: 0) > 0)
        assertFalse(fetched.getOrNull()?.base64Preview.isNullOrBlank())
    }

    @Test
    fun pingFailsForInvalidAddress() {
        val repository = NodeRepository()
        val connect = repository.connect("/dns4/invalid.invalid/tcp/4001/p2p/12D3KooWKiqj21VphU2eE25438to5xeny6eP6d3PXT93ZczagPLT")
        repository.shutdown()

        assertTrue(connect.isFailure)
    }
}
