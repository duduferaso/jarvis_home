package br.com.jarvishome.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente WebSocket mínimo (RFC 6455), sem dependências externas.
 * Suficiente para a API do Home Assistant: mensagens de texto, quadros binários
 * de áudio, ping/pong e fragmentação.
 */
class MiniWebSocket private constructor(private val socket: Socket) {

    private val input: InputStream = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val random = SecureRandom()
    @Volatile private var closed = false

    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(v) { socket.soTimeout = v }

    private fun handshake(uri: URI, port: Int) {
        val keyBytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val req = "GET " + path + " HTTP/1.1\r\n" +
            "Host: " + uri.host + ":" + port + "\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: " + key + "\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: JarvisHome\r\n\r\n"
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()

        val status = readLine()
        if (!status.contains(" 101")) throw IOException("WebSocket recusado: $status")
        var accept: String? = null
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                accept = line.substring(idx + 1).trim()
            }
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
        if (accept != expected) throw IOException("Handshake WebSocket inválido")
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException("Conexão fechada no handshake")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray) = sendFrame(OP_BINARY, data)

    @Synchronized private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed) throw IOException("WebSocket fechado")
        val len = payload.size
        output.write(0x80 or opcode)
        when {
            len < 126 -> output.write(0x80 or len)
            len <= 0xFFFF -> {
                output.write(0x80 or 126)
                output.write((len shr 8) and 0xFF)
                output.write(len and 0xFF)
            }
            else -> {
                output.write(0x80 or 127)
                for (i in 7 downTo 0) output.write(((len.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        output.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        output.write(masked)
        output.flush()
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("Conexão fechada")
            off += r
        }
        return buf
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Conexão fechada")
        return b
    }

    /** Lê a próxima mensagem de texto (responde pings e ignora binários). */
    fun readText(): String {
        val message = ByteArrayOutputStream()
        var inText = false
        while (true) {
            val b0 = readByte()
            val b1 = readByte()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) {
                len = 0
                for (i in 0 until 8) len = (len shl 8) or readByte().toLong()
            }
            if (len > MAX_MESSAGE) throw IOException("Mensagem grande demais: $len bytes")
            val mask = if (masked) readFully(4) else null
            val payload = readFully(len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            when (opcode) {
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> {}
                OP_CLOSE -> {
                    close()
                    throw EOFException("Servidor fechou o WebSocket")
                }
                OP_TEXT -> {
                    message.reset()
                    message.write(payload)
                    inText = true
                    if (fin) return message.toString("UTF-8")
                }
                OP_CONTINUATION -> if (inText) {
                    message.write(payload)
                    if (fin) return message.toString("UTF-8")
                }
                else -> {} // binário do servidor: não usado
            }
        }
    }

    fun close() {
        if (closed) return
        try { sendFrame(OP_CLOSE, ByteArray(0)) } catch (_: Exception) {}
        closed = true
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONTINUATION = 0
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10
        private const val MAX_MESSAGE = 8L * 1024 * 1024

        /** Aceita http(s):// ou ws(s)://. */
        fun connect(url: String, timeoutMs: Int): MiniWebSocket {
            val uri = URI(url.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://"))
            val secure = uri.scheme == "wss"
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val socket = if (secure) SSLSocketFactory.getDefault().createSocket() else Socket()
            socket.connect(InetSocketAddress(uri.host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            val ws = MiniWebSocket(socket)
            try {
                ws.handshake(uri, port)
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw e
            }
            return ws
        }
    }
}
