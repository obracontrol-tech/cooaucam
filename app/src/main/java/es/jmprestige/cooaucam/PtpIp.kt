package es.jmprestige.cooaucam

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Cliente PTP/IP minimo para camaras con chip iCatch (COOAU CU-SPC05).
 * Puerta 15740: canal de ordenes + canal de avisos.
 */
class PtpIp(
    private val ip: String,
    private val onEvent: (code: Int, params: List<Long>) -> Unit,
    private val onClosed: (String) -> Unit
) {
    class Resp(val code: Int, val data: ByteArray, val params: List<Long>)

    private var cmdSock: Socket? = null
    private var evtSock: Socket? = null
    private lateinit var cin: DataInputStream
    private lateinit var cout: OutputStream
    private var tid = 0
    var props: List<Int> = emptyList()
        private set
    var ops: List<Int> = emptyList()
        private set
    val connected: Boolean get() = cmdSock?.isConnected == true && cmdSock?.isClosed == false

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun readPacket(inp: DataInputStream): Pair<Int, ByteArray> {
        val h = ByteArray(8)
        inp.readFully(h)
        val bb = le(h)
        val len = bb.int
        val type = bb.int
        val p = ByteArray(maxOf(0, len - 8))
        inp.readFully(p)
        return type to p
    }

    private fun sendPacket(out: OutputStream, type: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(8 + payload.size)
        bb.putInt(type)
        bb.put(payload)
        out.write(bb.array())
        out.flush()
    }

    @Synchronized
    fun connect() {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(ip, 15740), 5000)
        s.soTimeout = 8000
        s.tcpNoDelay = true
        cmdSock = s
        cin = DataInputStream(s.getInputStream())
        cout = s.getOutputStream()

        val name = "CooauCAM".toByteArray(Charsets.UTF_16LE)
        val init = ByteBuffer.allocate(16 + name.size + 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        val u = UUID.randomUUID()
        init.putLong(u.mostSignificantBits)
        init.putLong(u.leastSignificantBits)
        init.put(name)
        init.putShort(0)
        init.putInt(0x00010000)
        sendPacket(cout, 1, init.array())
        val (t, p) = readPacket(cin)
        if (t != 2) throw Exception("La camara no acepto la conexion (tipo $t)")
        val conn = le(p).int

        val e = Socket()
        e.connect(InetSocketAddress(ip, 15740), 5000)
        e.soTimeout = 8000
        evtSock = e
        val ein = DataInputStream(e.getInputStream())
        val eout = e.getOutputStream()
        sendPacket(eout, 3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(conn).array())
        readPacket(ein)
        e.soTimeout = 0
        Thread {
            try {
                while (true) {
                    val (et, ep) = readPacket(ein)
                    if (et == 8 && ep.size >= 2) {
                        val bb = le(ep)
                        val code = bb.short.toInt() and 0xFFFF
                        if (bb.remaining() >= 4) bb.int
                        val ps = mutableListOf<Long>()
                        while (bb.remaining() >= 4) ps.add(bb.int.toLong() and 0xFFFFFFFFL)
                        onEvent(code, ps)
                    } else if (et == 13) {
                        sendPacket(eout, 14, ByteArray(0))
                    }
                }
            } catch (ex: Exception) {
                onClosed("Canal de avisos cerrado: ${ex.message}")
            }
        }.start()

        tid = 0
        op(0x1002, listOf(1))
        readDeviceInfo()
    }

    @Synchronized
    fun op(code: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null): Resp {
        tid++
        val bb = ByteBuffer.allocate(4 + 2 + 4 + 4 * params.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(if (dataOut != null) 2 else 1)
        bb.putShort(code.toShort())
        bb.putInt(tid)
        params.forEach { bb.putInt(it) }
        sendPacket(cout, 6, bb.array())
        if (dataOut != null) {
            val sd = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            sd.putInt(tid)
            sd.putLong(dataOut.size.toLong())
            sendPacket(cout, 9, sd.array())
            val ed = ByteBuffer.allocate(4 + dataOut.size).order(ByteOrder.LITTLE_ENDIAN)
            ed.putInt(tid)
            ed.put(dataOut)
            sendPacket(cout, 12, ed.array())
        }
        val data = ByteArrayOutputStream()
        while (true) {
            val (t, p) = readPacket(cin)
            when (t) {
                10, 12 -> if (p.size > 4) data.write(p, 4, p.size - 4)
                7 -> {
                    val r = le(p)
                    val c = r.short.toInt() and 0xFFFF
                    if (r.remaining() >= 4) r.int
                    val ps = mutableListOf<Long>()
                    while (r.remaining() >= 4) ps.add(r.int.toLong() and 0xFFFFFFFFL)
                    return Resp(c, data.toByteArray(), ps)
                }
            }
        }
    }

    private fun readList16(bb: ByteBuffer): List<Int> {
        val n = bb.int
        return (0 until n).map { bb.short.toInt() and 0xFFFF }
    }

    private fun skipString(bb: ByteBuffer) {
        val n = bb.get().toInt() and 0xFF
        bb.position(bb.position() + n * 2)
    }

    private fun readDeviceInfo() {
        val r = op(0x1001)
        val bb = le(r.data)
        bb.short; bb.int; bb.short
        skipString(bb)
        bb.short
        ops = readList16(bb)
        readList16(bb)
        props = readList16(bb)
    }

    fun getProp(code: Int): ByteArray = op(0x1015, listOf(code)).data

    fun setProp(code: Int, value: ByteArray): Int = op(0x1016, listOf(code), value).code

    fun battery(): Int? = try {
        val d = getProp(0x5001); if (d.isNotEmpty()) d[0].toInt() and 0xFF else null
    } catch (e: Exception) { null }

    /** Devuelve (total, libre) en bytes de la primera tarjeta. */
    fun storage(): Pair<Long, Long>? {
        val ids = op(0x1004).data
        if (ids.size < 8) return null
        val bb = le(ids)
        val n = bb.int
        if (n < 1) return null
        val sid = bb.int
        val info = le(op(0x1005, listOf(sid)).data)
        info.short; info.short; info.short
        val total = info.long
        val free = info.long
        return total to free
    }

    fun close() {
        try { cmdSock?.close() } catch (_: Exception) {}
        try { evtSock?.close() } catch (_: Exception) {}
        cmdSock = null
        evtSock = null
    }

    companion object {
        fun ptpString(s: String): ByteArray {
            val chars = s.toByteArray(Charsets.UTF_16LE)
            val out = ByteArrayOutputStream()
            out.write(s.length + 1)
            out.write(chars)
            out.write(0); out.write(0)
            return out.toByteArray()
        }

        fun hex(b: ByteArray, max: Int = 24): String {
            if (b.isEmpty()) return "(vacio)"
            return b.take(max).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
                if (b.size > max) " ..." else ""
        }

        /** Si el valor es un texto PTP lo devuelve legible. */
        fun asText(b: ByteArray): String? {
            if (b.size < 3) return null
            val n = b[0].toInt() and 0xFF
            if (n < 2 || 1 + n * 2 != b.size) return null
            return String(b, 1, (n - 1) * 2, Charsets.UTF_16LE)
        }
    }
}
