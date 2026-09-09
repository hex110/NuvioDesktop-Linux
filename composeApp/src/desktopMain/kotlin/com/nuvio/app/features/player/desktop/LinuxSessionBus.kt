package com.nuvio.app.features.player.desktop

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A very small synchronous D-Bus client for the session bus.
 *
 * NUVIO-LINUX: exists for one reason -- an inhibit is only held for as long as
 * the connection that took it stays open. `dbus-send`, `gdbus` and `busctl` all
 * open a connection, make one call and exit, so a screensaver inhibit taken
 * through them is released within milliseconds of being acquired; shells log it
 * as "peer :1.x died, removing inhibitor" immediately after the acquire. To
 * actually hold an inhibit the app has to own the connection, which means
 * speaking the protocol here rather than shelling out.
 *
 * Only what that needs is implemented: EXTERNAL auth over an AF_UNIX socket,
 * Hello, and method calls whose arguments and return values are strings and
 * uint32s. Everything arriving that is not the reply we are waiting for --
 * NameAcquired and friends -- is read and dropped.
 *
 * Java 16+ for AF_UNIX support in java.nio. Kept in its own class so that on an
 * older runtime the linkage error lands here and the caller can fall back to
 * `dbus-send`, rather than taking the whole screen-awake feature down with it.
 */
internal class LinuxSessionBus private constructor(private val channel: SocketChannel) : Closeable {

    private var nextSerial = 1

    /** The body of a successful reply, read positionally. */
    class Reply internal constructor(body: ByteBuffer, order: ByteOrder) {
        private val reader = DbusReader(body, order)
        fun uint32(): Int = reader.uint32()
        fun string(): String = reader.string()
    }

    /** Arguments may be [String] (marshalled `s`) or [Int] (marshalled `u`). */
    fun call(
        destination: String,
        path: String,
        iface: String,
        member: String,
        vararg args: Any,
    ): Reply {
        val signature = StringBuilder()
        val body = DbusWriter()
        for (arg in args) when (arg) {
            is String -> { signature.append('s'); body.string(arg) }
            is Int -> { signature.append('u'); body.uint32(arg) }
            else -> throw IllegalArgumentException("unsupported D-Bus argument ${arg.javaClass}")
        }
        val bodyBytes = body.toByteArray()
        val serial = nextSerial++

        val fields = DbusWriter()
        fields.field(FIELD_PATH, 'o', path)
        fields.field(FIELD_DESTINATION, 's', destination)
        fields.field(FIELD_INTERFACE, 's', iface)
        fields.field(FIELD_MEMBER, 's', member)
        if (signature.isNotEmpty()) fields.field(FIELD_SIGNATURE, 'g', signature.toString())
        val fieldBytes = fields.toByteArray()

        val message = DbusWriter()
        message.u8('l'.code)          // little-endian, matching DbusWriter
        message.u8(TYPE_METHOD_CALL)
        message.u8(0)                 // flags
        message.u8(1)                 // protocol version
        message.uint32(bodyBytes.size)
        message.uint32(serial)
        message.uint32(fieldBytes.size)
        message.raw(fieldBytes)
        message.align(8)              // the body starts on an 8-byte boundary
        message.raw(bodyBytes)
        writeFully(ByteBuffer.wrap(message.toByteArray()))

        while (true) {
            val reply = readMessage()
            if (reply.replySerial != serial) continue
            if (reply.type == TYPE_ERROR) throw IOException("D-Bus error: ${reply.errorName}")
            return Reply(reply.body, reply.order)
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }

    private class Message(
        val type: Int,
        val replySerial: Int,
        val errorName: String?,
        val body: ByteBuffer,
        val order: ByteOrder,
    )

    private fun readMessage(): Message {
        val fixed = ByteBuffer.wrap(readFully(16))
        val order =
            if (fixed.get(0) == 'l'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        fixed.order(order)
        val type = fixed.get(1).toInt()
        val bodyLength = fixed.getInt(4)
        val fieldsLength = fixed.getInt(12)
        // The header field array's own length excludes the padding that aligns
        // the body behind it.
        val padding = (8 - fieldsLength % 8) % 8
        val rest = readFully(fieldsLength + padding + bodyLength)

        var replySerial = 0
        var errorName: String? = null
        val fields = DbusReader(
            ByteBuffer.wrap(rest, 0, fieldsLength).slice().order(order), order
        )
        while (fields.hasRemaining()) {
            fields.align(8)
            val code = fields.u8()
            val signature = fields.signature()
            when {
                code == FIELD_REPLY_SERIAL && signature == "u" -> replySerial = fields.uint32()
                code == FIELD_ERROR_NAME && signature == "s" -> errorName = fields.string()
                else -> fields.skipValue(signature)
            }
        }
        val body = ByteBuffer.wrap(rest, fieldsLength + padding, bodyLength).slice().order(order)
        return Message(type, replySerial, errorName, body, order)
    }

    private fun authenticate() {
        // The leading NUL is part of the handshake, not the SASL exchange.
        writeFully(ByteBuffer.wrap(byteArrayOf(0)))
        sendLine("AUTH EXTERNAL " + uidHex())
        val reply = readLine()
        if (!reply.startsWith("OK")) throw IOException("D-Bus auth rejected: $reply")
        sendLine("BEGIN")
    }

    /** Required first call: without it the bus rejects everything else. */
    private fun hello() {
        call(DBUS_SERVICE, DBUS_PATH, DBUS_SERVICE, "Hello")
    }

    private fun sendLine(line: String) =
        writeFully(ByteBuffer.wrap((line + "\r\n").toByteArray(StandardCharsets.UTF_8)))

    private fun readLine(): String {
        val out = StringBuilder()
        val one = ByteBuffer.allocate(1)
        while (true) {
            one.clear()
            if (channel.read(one) < 0) throw IOException("session bus closed during auth")
            val c = one.get(0).toInt().toChar()
            if (c == '\n') return out.toString().trimEnd('\r')
            out.append(c)
        }
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) < 0) throw IOException("session bus closed")
        }
    }

    private fun readFully(count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw IOException("session bus closed")
        }
        return buffer.array()
    }

    companion object {
        private const val TYPE_METHOD_CALL = 1
        private const val TYPE_ERROR = 3

        private const val FIELD_PATH = 1
        private const val FIELD_INTERFACE = 2
        private const val FIELD_MEMBER = 3
        private const val FIELD_ERROR_NAME = 4
        private const val FIELD_REPLY_SERIAL = 5
        private const val FIELD_DESTINATION = 6
        private const val FIELD_SIGNATURE = 8

        const val DBUS_SERVICE = "org.freedesktop.DBus"
        const val DBUS_PATH = "/org/freedesktop/DBus"

        /** Null when there is no session bus we can reach; the caller falls back. */
        fun connect(): LinuxSessionBus? {
            val path = socketPath() ?: return null
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            return runCatching {
                channel.connect(UnixDomainSocketAddress.of(path))
                LinuxSessionBus(channel).apply {
                    authenticate()
                    hello()
                }
            }.getOrElse {
                runCatching { channel.close() }
                null
            }
        }

        /**
         * Abstract-namespace addresses (`unix:abstract=`) are deliberately not
         * handled: java.nio cannot open one, and returning null here gets the
         * caller onto the `dbus-send` fallback instead of throwing.
         */
        private fun socketPath(): String? {
            System.getenv("DBUS_SESSION_BUS_ADDRESS")
                ?.split(';')
                ?.forEach { address ->
                    if (!address.startsWith("unix:")) return@forEach
                    address.removePrefix("unix:").split(',').forEach { part ->
                        if (part.startsWith("path=")) return part.removePrefix("path=")
                    }
                }
            return System.getenv("XDG_RUNTIME_DIR")
                ?.let { "$it/bus" }
                ?.takeIf { Files.exists(Paths.get(it)) }
        }

        /** EXTERNAL auth sends the uid as its ASCII digits, hex-encoded. */
        private fun uidHex(): String {
            val uid = Files.readAllLines(Paths.get("/proc/self/status"))
                .firstOrNull { it.startsWith("Uid:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?: throw IOException("cannot read own uid")
            return uid.toByteArray(StandardCharsets.UTF_8)
                .joinToString("") { "%02x".format(it) }
        }
    }
}

/** Little-endian marshaller. Alignment is relative to the start of the buffer. */
private class DbusWriter {
    private val out = ByteArrayOutputStream()

    fun align(boundary: Int) {
        while (out.size() % boundary != 0) out.write(0)
    }

    fun u8(value: Int) = out.write(value and 0xFF)

    fun raw(bytes: ByteArray) = out.write(bytes, 0, bytes.size)

    fun uint32(value: Int) {
        align(4)
        out.write(value)
        out.write(value ushr 8)
        out.write(value ushr 16)
        out.write(value ushr 24)
    }

    fun string(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        uint32(bytes.size)
        raw(bytes)
        u8(0)
    }

    fun signature(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        u8(bytes.size)
        raw(bytes)
        u8(0)
    }

    /** One `(yv)` header field. Structs inside an array align to 8. */
    fun field(code: Int, type: Char, value: String) {
        align(8)
        u8(code)
        signature(type.toString())
        if (type == 'g') signature(value) else string(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

private class DbusReader(private val buffer: ByteBuffer, order: ByteOrder) {
    init {
        buffer.order(order)
    }

    private val base = buffer.position()

    fun hasRemaining(): Boolean = buffer.hasRemaining()

    fun align(boundary: Int) {
        while ((buffer.position() - base) % boundary != 0) buffer.get()
    }

    fun u8(): Int = buffer.get().toInt() and 0xFF

    fun uint32(): Int {
        align(4)
        return buffer.int
    }

    fun string(): String {
        val bytes = ByteArray(uint32())
        buffer.get(bytes)
        buffer.get() // trailing NUL
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun signature(): String {
        val bytes = ByteArray(u8())
        buffer.get(bytes)
        buffer.get() // trailing NUL
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Header fields we do not care about still have to be stepped over. */
    fun skipValue(signature: String) {
        when (signature) {
            "s", "o" -> string()
            "g" -> this.signature()
            "u" -> uint32()
            else -> throw IOException("unsupported D-Bus type '$signature'")
        }
    }
}
