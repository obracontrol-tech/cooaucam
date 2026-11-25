package es.jmprestige.cooaucam

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private val camIp = "192.168.1.1"
    private val version = "0.3"
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var cam: PtpIp
    private var player: ExoPlayer? = null

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var frame: FrameLayout
    private lateinit var playerView: PlayerView
    private val logText = StringBuilder()
    private var snapshot: Map<Int, String> = emptyMap()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cam = PtpIp(camIp,
            onEvent = { code, ps -> onCamEvent(code, ps) },
            onClosed = { msg -> log(msg) })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            fitsSystemWindows = true
        }

        root.addView(TextView(this).apply {
            text = "CooauCAM v$version"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })

        status = TextView(this).apply {
            text = "Sin conectar"
            setTextColor(Color.parseColor("#9AD0FF"))
            textSize = 16f
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(status)

        // Directo con marco (se pondra rojo al grabar)
        frame = FrameLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply { setColor(Color.parseColor("#333333")) }
        }
        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(row(
            btn("Conectar", "#1565C0") { connect() },
            btn("Directo", "#2E7D32") { startLive() },
            btn("Hora", "#455A64") { setClock() }
        ))

        root.addView(TextView(this).apply {
            text = "PRUEBA: pulsa A, espera 15 s y vuelve a pulsar A. Luego quita la Wi-Fi de la camara y mira su galeria."
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(2))
        })
        root.addView(row(
            btn("A (grabar?)", "#C62828") { testSafe("A", 0x100E, listOf(0, 0)) },
            btn("Archivos", "#37474F") { listFiles() }
        ))

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#C8E6C9"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(logView)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        root.addView(btn("Copiar registro (para pegarlo a Claude)", "#00695C") { copyLog() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })

        setContentView(root)
        log("CooauCAM v$version. Conecta el movil a la Wi-Fi CU-SPC05 y pulsa Conectar.")
    }

    private fun btn(text: String, color: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            maxLines = 1
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color)); cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    if (i > 0) leftMargin = dp(6)
                })
            }
        }

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            logText.append("$t  $msg\n")
            logView.text = logText
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    /** Obliga a la app a usar la Wi-Fi de la camara aunque no tenga internet. */
    private fun bindToWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                cm.bindProcessToNetwork(n)
                log("Usando la Wi-Fi (sin internet) para hablar con la camara")
                return
            }
        }
        log("AVISO: no veo ninguna Wi-Fi conectada. Conecta el movil a CU-SPC05.")
    }

    private fun connect() {
        setStatus("Conectando...")
        worker.execute {
            try {
                bindToWifi()
                cam.connect()
                log("Conectado. La camara entiende ${cam.ops.size} ordenes y ${cam.props.size} ajustes")
                log("Ordenes: " + cam.ops.joinToString(" ") { "%04X".format(it) })
                refreshStatus()
                log("Listo. (El directo NO se abre solo: pulsa Directo si lo quieres ver)")
            } catch (e: Exception) {
                setStatus("Error al conectar")
                log("ERROR al conectar: ${e.message}")
            }
        }
    }

    private fun refreshStatus() {
        val bat = cam.battery()
        val st = try { cam.storage() } catch (e: Exception) { null }
        val gb = 1024.0 * 1024.0 * 1024.0
        val s = buildString {
            append("Conectado")
            if (bat != null) append("  ·  Bateria $bat%")
            if (st != null) append("  ·  Tarjeta %.1f GB libres".format(st.second / gb))
        }
        setStatus(s)
    }

    private fun startLive() {
        try {
            player?.release()
            val load = DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 250).build()
            val p = ExoPlayer.Builder(this).setLoadControl(load).build()
            player = p
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    log("Directo: error ${error.errorCodeName}. Pulsa Directo para reintentar.")
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) log("Directo funcionando")
                }
            })
            val src = RtspMediaSource.Factory()
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse("rtsp://$camIp/")))
            p.setMediaSource(src)
            p.prepare()
            p.playWhenReady = true
            log("Abriendo directo rtsp://$camIp/ ...")
        } catch (e: Exception) {
            log("Directo: ${e.message}")
        }
    }

    private fun readAll(): Map<Int, String> {
        val m = mutableMapOf<Int, String>()
        for (p in cam.props) {
            try { m[p] = PtpIp.hex(cam.getProp(p), 40) } catch (_: Exception) {}
        }
        return m
    }

    private fun checkChanges(title: String) {
        if (!cam.connected) { log("Primero pulsa Conectar"); return }
        worker.execute {
            try {
                val now = readAll()
                var any = false
                for ((k, v) in now) {
                    if (snapshot[k] != v) {
                        log("  CAMBIO %04X: %s  ->  %s".format(k, snapshot[k] ?: "?", v)); any = true
                    }
                }
                if (!any) log("  ($title: ningun ajuste ha cambiado)")
                snapshot = now
                refreshStatus()
            } catch (e: Exception) {
                log("  error leyendo ajustes: ${e.message}")
            }
        }
    }

    private fun test(name: String, code: Int, params: List<Int>) {
        if (!cam.connected) { log("Primero pulsa Conectar"); return }
        log("===== PRUEBA $name: orden %04X %s".format(code, params.joinToString(",")))
        setStatus("Probando $name... espera")
        worker.execute {
            try {
                val before = try { cam.handles().toSet() } catch (e: Exception) { null }
                val r = cam.op(code, params)
                val meaning = when (r.code) {
                    0x2001 -> "OK"
                    0x2005 -> "no soportada"
                    0x2002 -> "error general"
                    0x2019 -> "camara ocupada"
                    0x201D -> "parametro no valido"
                    else -> "?"
                }
                log("  Respuesta %04X (%s)".format(r.code, meaning))
                Thread.sleep(4000)
                val after = try { cam.handles() } catch (e: Exception) { log("  (la camara no deja leer la tarjeta: puede estar GRABANDO)"); null }
                if (before != null && after != null) {
                    val nuevos = after.filter { it !in before }
                    if (nuevos.isEmpty()) {
                        log("  RESULTADO $name: ningun archivo nuevo")
                    } else {
                        for (h in nuevos) {
                            val (fn, sz) = cam.fileInfo(h)
                            val tipo = when {
                                fn.uppercase().endsWith(".JPG") -> "FOTO"
                                fn.uppercase().endsWith(".MP4") || fn.uppercase().endsWith(".MOV") -> "VIDEO"
                                else -> "archivo"
                            }
                            log("  RESULTADO $name: $tipo nuevo -> $fn (%.1f MB)".format(sz / 1048576.0))
                        }
                    }
                }
                refreshStatus()
            } catch (e: Exception) {
                log("  ERROR: ${e.message}")
                log("  La camara ha dejado de contestar. Si sigue asi, apagala y enciendela y pulsa Conectar.")
                setStatus("La camara no contesta")
                cam.close()
            }
        }
    }

    /** Envia la orden y NO pregunta nada mas a la camara: solo escucha sus avisos. */
    private fun testSafe(name: String, code: Int, params: List<Int>) {
        if (!cam.connected) { log("Primero pulsa Conectar"); return }
        player?.release(); player = null
        log("===== PRUEBA $name: orden %04X (modo seguro, no se pregunta nada despues)".format(code))
        worker.execute {
            try {
                val r = cam.op(code, params)
                log("  Respuesta %04X %s".format(r.code, if (r.code == 0x2001) "(OK)" else ""))
                log("  Escuchando avisos de la camara... (si llega alguno saldra aqui)")
            } catch (e: Exception) {
                log("  ERROR: ${e.message}")
                log("  La camara no contesta. Quita su Wi-Fi y mira si esta grabando.")
                cam.close()
            }
        }
    }

    private fun listFiles() {
        if (!cam.connected) { log("Primero pulsa Conectar"); return }
        worker.execute {
            try {
                val hs = cam.handles()
                log("Archivos en la tarjeta: ${hs.size}")
                for (h in hs.takeLast(6)) {
                    val (fn, sz) = cam.fileInfo(h)
                    log("   $fn  (%.1f MB)".format(sz / 1048576.0))
                }
            } catch (e: Exception) {
                log("No puedo leer la tarjeta: ${e.message}")
            }
        }
    }

    private fun onCamEvent(code: Int, ps: List<Long>) {
        val name = when (code) {
            0x4002 -> "archivo nuevo"
            0x4003 -> "archivo borrado"
            0x4006 -> "ajuste cambiado"
            0x400D -> "captura terminada"
            else -> "aviso propio de la camara"
        }
        log("  AVISO %04X (%s) %s".format(code, name, ps.joinToString(" ") { "%08X".format(it) }))
        if (false && code == 0x4006 && ps.isNotEmpty()) {
            worker.execute {
                try {
                    val p = ps[0].toInt()
                    log("    nuevo valor de %04X: %s".format(p, PtpIp.hex(cam.getProp(p), 40)))
                } catch (_: Exception) {}
            }
        }
    }

    private fun setClock() {
        if (!cam.connected) { log("Primero pulsa Conectar"); return }
        val now = SimpleDateFormat("yyyyMMdd'T'HHmmss'.0'", Locale.US).format(Date())
        worker.execute {
            try {
                val c = cam.setProp(0x5011, PtpIp.ptpString(now))
                log("Poner hora $now -> respuesta %04X %s".format(c, if (c == 0x2001) "(OK)" else ""))
                val back = PtpIp.asText(cam.getProp(0x5011))
                log("La camara ahora tiene la hora: $back")
            } catch (e: Exception) {
                log("Error poniendo la hora: ${e.message}")
            }
        }
    }

    private fun copyLog() {
        val cb = getSystemService(ClipboardManager::class.java)
        cb.setPrimaryClip(ClipData.newPlainText("CooauCAM", logText.toString()))
        Toast.makeText(this, "Registro copiado. Pegalo en el chat de Claude.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        player?.release()
        worker.execute { cam.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
