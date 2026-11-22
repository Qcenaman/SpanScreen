package com.qcenaman.spanscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(24), dp(20), dp(24))
}

private fun Activity.title(text: String) = TextView(this).apply {
    this.text = text; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(8))
}

private fun Activity.label(text: String, size: Float = 15f) = TextView(this).apply {
    this.text = text; textSize = size; setPadding(0, dp(6), 0, dp(6))
}

private fun Activity.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; setOnClickListener { onClick() }
}

private fun Activity.scroll(content: View) = ScrollView(this).apply { addView(content) }

private fun describe(s: DeviceSpec) =
    String.format(Locale.US, "%.1f\" · %dx%d · %.0f Hz", s.diagonalInches, s.widthPx, s.heightPx, s.refreshHz)

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(title("SpanScreen"))
        col.addView(label("Put devices side by side and spread one picture, video or game across all of them.\n\n" +
            "Pick one device to be the host (it has the controls). On every other device, choose Join."))
        col.addView(button("Host on this device") { startActivity(Intent(this, HostActivity::class.java)) })
        col.addView(button("Join a host") { startActivity(Intent(this, JoinActivity::class.java)) })
        setContentView(scroll(col))
    }
}

class HostActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var gapLabel: TextView
    private lateinit var arrangeBtn: Button
    private lateinit var fitBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = LocalDevice.spec(this)
        try {
            HostSession.start(this, me, LocalDevice.hevcDecodes(me.widthPx, me.heightPx))
        } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage("Couldn't start hosting: ${e.message}").show()
        }
        val col = column()
        col.addView(title("Host"))
        col.addView(label("Open SpanScreen on the other devices and choose Join. They'll appear below.\n" +
            "Tick the ones to use. The list order is their order on the table: left to right, or top to bottom."))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        arrangeBtn = button("") {
            HostSession.arrangement = if (HostSession.arrangement == Arrangement.ROW) Arrangement.COLUMN else Arrangement.ROW
            refresh()
        }
        col.addView(arrangeBtn)
        fitBtn = button("") {
            HostSession.fit = if (HostSession.fit == FitMode.FILL) FitMode.FIT else FitMode.FILL
            refresh()
        }
        col.addView(fitBtn)
        gapLabel = label("")
        col.addView(gapLabel)
        col.addView(SeekBar(this).apply {
            max = 30
            progress = (HostSession.gapMm * 2).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { HostSession.gapMm = p / 2f; refresh() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(button("Show a picture") { pick("image/*", REQ_IMAGE) })
        col.addView(button("Play a video") { pick("video/*", REQ_VIDEO) })
        col.addView(button("Play a game") { pickGame() })
        col.addView(button("Stop on all devices") { HostSession.stopAll() })
        status = label("", 14f).apply { setTextColor(Color.GRAY) }
        col.addView(status)
        setContentView(scroll(col))
        status.text = GameController.shizukuState().let { "Ready. Game mode: $it." }
    }

    override fun onResume() {
        super.onResume()
        HostSession.listener = { refresh() }
        HostSession.status = { status.text = it }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        HostSession.listener = null
        HostSession.status = null
    }

    override fun onDestroy() {
        if (isFinishing) HostSession.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        HostSession.updateSelf(LocalDevice.spec(this))
    }

    private fun refresh() {
        list.removeAllViews()
        val members = HostSession.members
        members.forEachIndexed { i, m ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cb = CheckBox(this).apply {
                text = "${i + 1}. ${m.spec.name}${if (m.isSelf) " (this device)" else ""}\n${describe(m.spec)}"
                isChecked = m.included
                setOnCheckedChangeListener { _, c -> m.included = c; refreshSummary() }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply { text = "▲"; setOnClickListener { HostSession.move(m, -1) } },
                LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(Button(this).apply { text = "▼"; setOnClickListener { HostSession.move(m, 1) } },
                LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(row)
        }
        if (members.size <= 1) list.addView(label("Waiting for other devices to join…", 14f).apply { setTextColor(Color.GRAY) })
        refreshSummary()
    }

    private fun refreshSummary() {
        arrangeBtn.text = if (HostSession.arrangement == Arrangement.ROW) "Layout: side by side (tap to stack)" else "Layout: stacked top to bottom (tap for side by side)"
        fitBtn.text = if (HostSession.fit == FitMode.FILL) "Pictures/videos: fill all screens (tap to fit whole)" else "Pictures/videos: fit whole (tap to fill)"
        val sel = HostSession.selected()
        val size = if (sel.isNotEmpty()) {
            val c = WallLayout.place(sel.map { it.spec }, HostSession.arrangement, HostSession.gapMm).second
            String.format(Locale.US, " · wall %.0f × %.0f mm, %d device(s)", c.w, c.h, sel.size)
        } else ""
        gapLabel.text = String.format(Locale.US, "Gap between screens (bezels): %.1f mm", HostSession.gapMm) + size
    }

    @Suppress("DEPRECATION")
    private fun pick(type: String, req: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        status.text = "Loading…"
        thread {
            val f = File(cacheDir, if (requestCode == REQ_IMAGE) "src_image" else "src_video_${System.currentTimeMillis()}")
            if (requestCode == REQ_VIDEO) cacheDir.listFiles()?.filter { it.name.startsWith("src_video_") }?.forEach { it.delete() }
            try {
                contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't open that file" }
                return@thread
            }
            if (requestCode == REQ_IMAGE) HostSession.showImage(f) else HostSession.showVideo(f)
        }
    }

    private fun pickGame() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(pm).toString() to "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Game to spread across the screens")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i -> HostSession.startGame(apps[i].second) }
            .show()
    }

    companion object {
        private const val REQ_IMAGE = 1
        private const val REQ_VIDEO = 2
    }
}

class JoinActivity : Activity() {
    private lateinit var hosts: LinearLayout
    private lateinit var status: TextView
    private val found = LinkedHashMap<String, String>()
    private var stopBrowse: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(title("Join"))
        col.addView(label("Hosts on your Wi-Fi:"))
        hosts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(hosts)
        col.addView(label("Or type the host's IP address:"))
        val ip = EditText(this).apply { hint = "192.168.1.20"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        col.addView(ip)
        col.addView(button("Join this IP") { ip.text.toString().trim().takeIf { it.isNotEmpty() }?.let { join(it) } })
        col.addView(button("Leave") { PeerSession.disconnect(); status.text = "Not connected" })
        status = label("Not connected", 14f).apply { setTextColor(Color.GRAY) }
        col.addView(status)
        setContentView(scroll(col))
        refreshHosts()
    }

    override fun onResume() {
        super.onResume()
        PeerSession.status = { status.text = it }
        if (PeerSession.connected) status.text = "Connected to ${PeerSession.hostName}. Waiting for the host to start something."
        stopBrowse = Discovery.browse(this) { name, host ->
            found[host] = name
            refreshHosts()
        }
    }

    override fun onPause() {
        super.onPause()
        stopBrowse?.invoke()
        stopBrowse = null
    }

    override fun onDestroy() {
        if (isFinishing) { PeerSession.status = null; PeerSession.disconnect() }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val s = LocalDevice.spec(this)
        PeerSession.updateSpec(s, LocalDevice.hevcDecodes(s.widthPx, s.heightPx))
    }

    private fun refreshHosts() {
        hosts.removeAllViews()
        if (found.isEmpty()) hosts.addView(label("Searching…", 14f).apply { setTextColor(Color.GRAY) })
        found.forEach { (host, name) -> hosts.addView(button("$name  ($host)") { join(host) }) }
    }

    private fun join(host: String) {
        status.text = "Connecting to $host…"
        val s = LocalDevice.spec(this)
        PeerSession.connect(this, host, s, LocalDevice.hevcDecodes(s.widthPx, s.heightPx))
    }
}
