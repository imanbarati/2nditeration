package com.example.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

/**
 * Lightweight user-space SOCKS5 (RFC 1928) + HTTP CONNECT proxy server listening on
 * 127.0.0.1:<port> (default 1819, matching Aether's local proxy interface).
 * Supports optional upstream SOCKS5 proxy chaining (e.g. Psiphon / Orbot local port).
 */
class LocalSocksProxyServer(
    private val port: Int,
    private val socketFactory: SocketFactory,
    private val upstreamHost: String? = null,
    private val upstreamPort: Int? = null,
    private val onTraffic: (txDelta: Long, rxDelta: Long, newConnection: Boolean) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(scope: CoroutineScope): Boolean {
        return try {
            stop()
            val server = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            isRunning = true
            BpbLogBuffer.info("SOCKS5", "Local Aether proxy listening on 127.0.0.1:$port")

            acceptJob = scope.launch(Dispatchers.IO) {
                while (isActive && !server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClient(client, scope) }
                }
            }
            true
        } catch (e: Exception) {
            BpbLogBuffer.error("SOCKS5", "Failed to bind 127.0.0.1:$port: ${e.message}")
            isRunning = false
            false
        }
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(client: Socket, scope: CoroutineScope) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val firstByte = input.read()
            if (firstByte == -1) {
                client.close()
                return
            }

            if (firstByte == 0x05) {
                handleSocks5(client, input, output, scope)
            } else {
                handleHttpConnect(firstByte, client, input, output, scope)
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private fun handleSocks5(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        scope: CoroutineScope,
    ) {
        val nMethods = input.read()
        if (nMethods <= 0) {
            client.close()
            return
        }
        val methods = ByteArray(nMethods)
        readFully(input, methods)
        // Reply NO AUTH (0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val header = ByteArray(4)
        if (!readFully(input, header)) {
            client.close()
            return
        }
        val cmd = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF
        if (cmd != 0x01) { // CONNECT only
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.close()
            return
        }

        val targetHost = when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
            }
            0x03 -> { // Domain
                val len = input.read()
                val dom = ByteArray(len)
                readFully(input, dom)
                String(dom, Charsets.UTF_8)
            }
            0x04 -> { // IPv6
                val addr = ByteArray(16)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "::"
            }
            else -> {
                client.close()
                return
            }
        }
        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val remote = connectTarget(targetHost, targetPort)
        if (remote == null) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }

        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        onTraffic(0L, 0L, true)
        relayStreams(client, remote, scope)
    }

    private fun handleHttpConnect(
        firstByte: Int,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        scope: CoroutineScope,
    ) {
        val sb = StringBuilder().append(firstByte.toChar())
        while (sb.length < 4096) {
            val ch = input.read()
            if (ch == -1) break
            sb.append(ch.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        val firstLine = sb.toString().lineSequence().firstOrNull().orEmpty()
        val parts = firstLine.split(" ")
        if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
            output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
            client.close()
            return
        }
        val hostPort = parts[1]
        val host = hostPort.substringBeforeLast(":")
        val targetPort = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
        val remote = connectTarget(host, targetPort)
        if (remote == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            client.close()
            return
        }
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()
        onTraffic(0L, 0L, true)
        relayStreams(client, remote, scope)
    }

    private fun connectTarget(host: String, targetPort: Int): Socket? {
        val uHost = upstreamHost?.takeIf { it.isNotBlank() }
        val uPort = upstreamPort?.takeIf { it in 1..65535 }
        if (uHost != null && uPort != null) {
            val chained = dialViaUpstreamSocks5(uHost, uPort, host, targetPort)
            if (chained != null) return chained
        }
        return runCatching {
            val s = socketFactory.createSocket()
            s.connect(InetSocketAddress(host, targetPort), 8000)
            s
        }.getOrNull()
    }

    private fun dialViaUpstreamSocks5(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Socket? {
        return runCatching {
            val sock = socketFactory.createSocket()
            sock.connect(InetSocketAddress(proxyHost, proxyPort), 3000)
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = ByteArray(2)
            readFully(inp, greet)
            if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                sock.close()
                return null
            }
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            val req = ByteArray(4 + 1 + hostBytes.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
            req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            out.write(req)
            out.flush()
            val resp = ByteArray(10)
            readFully(inp, resp)
            if (resp[1] != 0x00.toByte()) {
                sock.close()
                return null
            }
            sock
        }.getOrNull()
    }

    private fun relayStreams(local: Socket, remote: Socket, scope: CoroutineScope) {
        local.soTimeout = 0
        remote.soTimeout = 0
        val job1 = scope.launch(Dispatchers.IO) {
            pipe(local.getInputStream(), remote.getOutputStream(), isUpload = true)
            runCatching { remote.shutdownOutput() }
        }
        val job2 = scope.launch(Dispatchers.IO) {
            pipe(remote.getInputStream(), local.getOutputStream(), isUpload = false)
            runCatching { local.shutdownOutput() }
        }
        scope.launch(Dispatchers.IO) {
            job1.join()
            job2.join()
            runCatching { local.close() }
            runCatching { remote.close() }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, isUpload: Boolean) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
                output.flush()
                if (isUpload) onTraffic(read.toLong(), 0L, false)
                else onTraffic(0L, read.toLong(), false)
            }
        } catch (_: Exception) {
        }
    }

    private fun readFully(input: InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }
}
