package org.ddev.nabucidclient.network

import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import org.peergos.BlockRequestAuthoriser
import org.peergos.HostBuilder
import org.peergos.EmbeddedIpfs
import org.peergos.HashedBlock
import org.peergos.Want
import org.peergos.blockstore.RamBlockstore
import org.peergos.config.IdentitySection
import org.peergos.protocol.dht.RamRecordStore
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.system.measureTimeMillis

class NabuNodeClient {
    data class ConnectionInfo(
        val multiAddress: String,
        val host: String,
        val port: Int,
        val peerId: String
    )

    private var currentConnection: ConnectionInfo? = null
    private var ipfs: EmbeddedIpfs? = null

    fun connect(multiAddressValue: String, timeoutMillis: Int = 6_000): Long {
        shutdown()
        val address = parseMultiAddress(multiAddressValue)
        val peerId = extractPeerId(multiAddressValue)
        val connectLatency = startEmbeddedNodeAndConnect(multiAddressValue, peerId, timeoutMillis)
        currentConnection = ConnectionInfo(multiAddressValue, address.first, address.second, peerId)
        return connectLatency
    }

    fun fetchBlockByCid(cidValue: String): FetchResult {
        val connection = currentConnection ?: throw IllegalStateException("Сначала подключитесь к ноде")
        val node = ipfs ?: throw IllegalStateException("IPFS узел не инициализирован")
        val blockBytes = readCidFromNode(node, cidValue, connection.peerId)
        val utf8 = runCatching { String(blockBytes, StandardCharsets.UTF_8) }.getOrNull().orEmpty()
        val hasReadableText = utf8.isNotBlank() && utf8.all { it == '\n' || it == '\r' || it.code in 32..126 || it.code >= 0x0400 }
        return FetchResult(
            cid = cidValue,
            sizeBytes = blockBytes.size,
            textPreview = if (hasReadableText) utf8.take(4_000) else null,
            base64Preview = Base64.getEncoder().encodeToString(blockBytes).take(4_000)
        )
    }

    fun pingCurrentNode(timeoutMillis: Int = 4_000): Long {
        val connection = currentConnection ?: throw IllegalStateException("Сначала подключитесь к ноде")
        return tcpPing(connection.host, connection.port, timeoutMillis)
    }

    fun shutdown() {
        currentConnection = null
        val node = ipfs
        ipfs = null
        runCatching { node?.stop()?.join() }
    }

    private fun tcpPing(host: String, port: Int, timeoutMillis: Int): Long {
        val socket = Socket()
        try {
            return measureTimeMillis {
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun extractPeerId(multiAddress: String): String {
        val marker = "/p2p/"
        val idx = multiAddress.indexOf(marker)
        if (idx < 0) {
            throw IllegalArgumentException("Multiaddress должен содержать /p2p/<peerId>")
        }
        return multiAddress.substring(idx + marker.length)
    }

    private fun parseMultiAddress(value: String): Pair<String, Int> {
        val parts = value.split('/').filter { it.isNotBlank() }
        if (parts.size < 4) {
            throw IllegalArgumentException("Некорректный multiaddress")
        }
        val hostIndex = when (parts[0]) {
            "ip4", "dns4", "dns", "dns6", "ip6" -> 1
            else -> throw IllegalArgumentException("Поддерживаются только /ip4, /ip6, /dns, /dns4, /dns6 адреса")
        }
        val tcpIndex = parts.indexOf("tcp")
        if (tcpIndex < 0 || tcpIndex + 1 >= parts.size) {
            throw IllegalArgumentException("Multiaddress должен содержать /tcp/<port>")
        }
        val host = parts[hostIndex]
        val port = parts[tcpIndex + 1].toIntOrNull()
            ?: throw IllegalArgumentException("Порт в multiaddress должен быть числом")
        return host to port
    }

    private fun startEmbeddedNodeAndConnect(multiAddressValue: String, peerIdValue: String, timeoutMillis: Int): Long {
        val node = buildEmbeddedNode(multiAddressValue)
        val remotePeer = PeerId.fromBase58(peerIdValue)
        val remoteAddr = Multiaddr.fromString(multiAddressValue)
        return try {
            val latency = measureTimeMillis {
                node.start()
                node.node.addressBook.addAddrs(remotePeer, timeoutMillis.toLong(), remoteAddr)
                // Реальный сетевой RTT до удаленной ноды для отображения latency.
                tcpPing(parseMultiAddress(multiAddressValue).first, parseMultiAddress(multiAddressValue).second, timeoutMillis)
            }
            ipfs = node
            latency
        } catch (t: Throwable) {
            runCatching { node.stop().join() }
            throw t
        }
    }

    private fun buildEmbeddedNode(bootstrapAddress: String): EmbeddedIpfs {
        val identityBuilder = HostBuilder().generateIdentity()
        val priv: PrivKey = identityBuilder.privateKey
        val peerId: PeerId = identityBuilder.peerId
        val identity = IdentitySection(priv.bytes(), peerId)
        val authoriser = BlockRequestAuthoriser { _, _, _ ->
            CompletableFuture.completedFuture(true)
        }
        return EmbeddedIpfs.build(
            RamRecordStore(),
            RamBlockstore(),
            false,
            listOf(MultiAddress("/ip4/0.0.0.0/tcp/0")),
            listOf(MultiAddress(bootstrapAddress)),
            identity,
            authoriser,
            Optional.empty()
        )
    }

    private fun readCidFromNode(node: EmbeddedIpfs, cidValue: String, peerIdValue: String): ByteArray {
        val cid = runCatching { Cid.decode(cidValue) }
            .getOrElse { throw IllegalArgumentException("Некорректный CID") }
        val peer = PeerId.fromBase58(peerIdValue)
        val blocks: List<HashedBlock> = runCatching {
            node.getBlocks(listOf(Want(cid)), setOf(peer), true)
        }.getOrElse { throwable ->
            val mapped = when (throwable) {
                is SocketTimeoutException -> IllegalStateException("Таймаут при получении CID через ноду")
                is UnknownHostException -> IllegalStateException("Нода недоступна")
                else -> throwable
            }
            throw mapped
        }
        return blocks.firstOrNull()?.block
            ?: throw IllegalStateException("Блок по CID не найден на ноде")
    }
}

data class FetchResult(
    val cid: String,
    val sizeBytes: Int,
    val textPreview: String?,
    val base64Preview: String
)
