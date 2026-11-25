package com.example.m30probe

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

// M30 HE configurator v1.
// Protocol taken from the FuryCube web app JS:
//   frame  : 55 CMD 00 CHK LEN ADDR_LO ADDR_HI 00 DATA...   (64 bytes)
//   read   : CMD 03 info | 04 base | 07 default keymap | 08 keymap | A0 trigger table
//            CHK = (lo+hi+len)&FF
//   write  : CMD 09 keymap | A1 trigger table
//            CHK = (len+lo+hi+0+sum(data))&FF
//   trigger table: profile*1024 + key*8, 8 bytes per key
//   keymap       : profile*2048 + layer*512 + key*3, 3 bytes [type, code1, code2]
//   profile switch: 55 0E 00 (1+p) 01 00 00 00 p
// Never sent: reset (EE), boot (5F), calibration (A8/A9), config block (06).

private data class Target(val name: String, val t: Int, val c1: Int, val c2: Int)

class MainActivity : Activity() {

    private val permAction = "com.example.m30probe.USB_PERMISSION"
    private val vendorId = 0x19F5
    private lateinit var usb: UsbManager
    private val io = Executors.newSingleThreadExecutor()

    private var kb: Kb? = null
    @Volatile private var profile = 0
    @Volatile private var activeProfile = -1
    private var trig = ByteArray(1024)
    private var keymap = ByteArray(512)
    private var keymapDef = ByteArray(512)
    private val keyIdx = ArrayList<Int>()
    private val boxes = ArrayList<CheckBox>()
    private val targets = ArrayList<Target>()
    private val sb = StringBuilder()

    private lateinit var logView: TextView
    private lateinit var status: TextView
    private lateinit var grid: GridLayout
    private lateinit var actS: Slider
    private lateinit var pressS: Slider
    private lateinit var relS: Slider
    private lateinit var rtSw: Switch
    private lateinit var contSw: Switch
    private lateinit var targetSpin: Spinner

    // ---------------------------------------------------------------- USB transport
    private inner class Kb(
        val conn: UsbDeviceConnection, val itf: UsbInterface,
        val epIn: UsbEndpoint, val epOut: UsbEndpoint?
    ) {
        private fun out(req: ByteArray): Boolean {
            val w = if (epOut != null) conn.bulkTransfer(epOut, req, req.size, 1000)
            else conn.controlTransfer(0x21, 0x09, 0x0200, itf.id, req, req.size, 1000)
            return w >= 0
        }

        fun drain() {
            val b = ByteArray(64)
            var n = 0
            while (n < 32 && conn.bulkTransfer(epIn, b, 64, 5) > 0) n++
        }

        private fun reply(cmd: Int, timeout: Int): ByteArray? {
            repeat(10) {
                val buf = ByteArray(64)
                val r = conn.bulkTransfer(epIn, buf, 64, timeout)
                if (r <= 0) return null
                if (buf[0] == 0x55.toByte() || buf[1] == cmd.toByte()) return buf
            }
            return null
        }

        fun simple(cmd: Int): ByteArray? {
            drain()
            val req = ByteArray(64)
            req[0] = 0x55; req[1] = cmd.toByte(); req[3] = 0x20; req[4] = 0x20
            if (!out(req)) return null
            val rep = reply(cmd, 1000) ?: return null
            return rep.copyOfRange(8, 40)
        }

        fun read(cmd: Int, addr: Int, total: Int): ByteArray? {
            val res = ByteArray(total)
            var off = 0
            while (off < total) {
                val len = minOf(56, total - off)
                val a = addr + off
                val lo = a and 0xFF
                val hi = (a shr 8) and 0xFF
                val req = ByteArray(64)
                req[0] = 0x55; req[1] = cmd.toByte()
                req[3] = ((lo + hi + len) and 0xFF).toByte()
                req[4] = len.toByte(); req[5] = lo.toByte(); req[6] = hi.toByte()
                drain()
                if (!out(req)) { log("USB write failed (read cmd %02X)".format(cmd)); return null }
                val rep = reply(cmd, 1000)
                if (rep == null) { log("no reply cmd=%02X addr=%d".format(cmd, a)); return null }
                System.arraycopy(rep, 8, res, off, minOf(len, rep.size - 8))
                off += len
            }
            return res
        }

        fun write(cmd: Int, addr: Int, data: ByteArray): Boolean {
            var off = 0
            while (off < data.size) {
                val len = minOf(56, data.size - off)
                val a = addr + off
                val lo = a and 0xFF
                val hi = (a shr 8) and 0xFF
                val req = ByteArray(64)
                req[0] = 0x55; req[1] = cmd.toByte()
                req[4] = len.toByte(); req[5] = lo.toByte(); req[6] = hi.toByte(); req[7] = 0
                var sum = len + lo + hi
                for (i in 0 until len) {
                    req[8 + i] = data[off + i]
                    sum += data[off + i].toInt() and 0xFF
                }
                req[3] = (sum and 0xFF).toByte()
                drain()
                if (!out(req)) { log("USB write failed (cmd %02X)".format(cmd)); return false }
                reply(cmd, 400)
                off += len
            }
            return true
        }

        fun sendProfileSwitch(p: Int) {
            drain()
            val req = ByteArray(64)
            req[0] = 0x55; req[1] = 0x0E; req[2] = 0
            req[3] = ((1 + p) and 0xFF).toByte(); req[4] = 1
            req[5] = 0; req[6] = 0; req[7] = 0; req[8] = p.toByte()
            out(req)
            reply(0x0E, 500)
        }

        fun close() {
            try { conn.releaseInterface(itf) } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- UI helpers
    private inner class Slider(val title: String, val min: Int, val max: Int, init0: Int) {
        val label = TextView(this@MainActivity)
        val bar = SeekBar(this@MainActivity)
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val value: Int get() = min + bar.progress

        init {
            bar.max = max - min
            bar.progress = init0 - min
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { refresh() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            val steps = LinearLayout(this@MainActivity)
            for ((txt, d) in listOf("-10" to -10, "-1" to -1, "+1" to 1, "+10" to 10)) {
                val b = Button(this@MainActivity)
                b.text = txt
                b.isAllCaps = false
                b.setOnClickListener { setTo(value + d) }
                steps.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            view.addView(label); view.addView(bar); view.addView(steps)
            refresh()
        }

        fun refresh() { label.text = "$title: " + "%.2f mm".format(Locale.US, value / 100.0) }
        fun setTo(v: Int) { bar.progress = (v - min).coerceIn(0, bar.max); refresh() }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun head(t: String) = TextView(this).apply {
        text = t; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(14), 0, dp(4))
    }

    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t; b.isAllCaps = false
        b.setOnClickListener { f() }
        return b
    }

    private fun row(vararg v: View): LinearLayout {
        val r = LinearLayout(this)
        for (x in v) r.addView(x, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun log(s: String) {
        synchronized(sb) { sb.append(s).append('\n') }
        runOnUiThread { logView.text = synchronized(sb) { sb.toString() } }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != permAction) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null)
                io.execute { guard { connect(dev) } }
            else log("USB permission denied")
        }
    }

    private inline fun guard(f: () -> Unit) {
        try { f() } catch (e: Exception) { log("ERROR: $e") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildTargets()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        status = TextView(this).apply { text = "Plug keyboard in via OTG, tap Connect." }
        root.addView(btn("Connect & read keyboard") { connectClicked() })
        root.addView(status)

        root.addView(head("Profile"))
        root.addView(row(
            btn("Edit P0") { loadClicked(0) },
            btn("Edit P1") { loadClicked(1) },
            btn("Edit P2") { loadClicked(2) }
        ))
        root.addView(btn("Make edited profile the active one") {
            val p = profile
            io.execute { guard { switchProfile(p) } }
        })

        root.addView(head("1. Pick keys"))
        root.addView(row(
            btn("All") { boxes.forEach { it.isChecked = true } },
            btn("None") { boxes.forEach { it.isChecked = false } }
        ))
        grid = GridLayout(this).apply { columnCount = 5 }
        root.addView(grid)

        root.addView(head("2. Trigger settings"))
        actS = Slider("Actuation point", 10, 340, 200)
        rtSw = Switch(this).apply { text = "Rapid Trigger" }
        contSw = Switch(this).apply { text = "Continuous Rapid Trigger" }
        pressS = Slider("RT press distance", 1, 220, 50)
        relS = Slider("RT release distance", 1, 220, 50)
        root.addView(actS.view); root.addView(rtSw); root.addView(contSw)
        root.addView(pressS.view); root.addView(relS.view)
        root.addView(btn("Apply trigger settings to selected keys") { applyClicked() })

        root.addView(head("3. Remap selected keys (layer 0)"))
        targetSpin = Spinner(this)
        targetSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets.map { it.name })
        root.addView(targetSpin)
        root.addView(row(
            btn("Remap to this") { remapClicked(false) },
            btn("Factory mapping") { remapClicked(true) }
        ))

        root.addView(head("Backup"))
        root.addView(TextView(this).apply {
            text = "The first read of each profile is saved automatically inside the app."
        })
        root.addView(row(
            btn("Copy backup text") { copyBackup() },
            btn("Restore backup") { restoreClicked() }
        ))

        root.addView(head("Log"))
        logView = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        root.addView(logView)
        root.addView(btn("Copy log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("m30", synchronized(sb) { sb.toString() }))
        })

        setContentView(ScrollView(this).apply { addView(root) })

        val filter = IntentFilter(permAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        kb?.close()
    }

    // ---------------------------------------------------------------- key naming
    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun hidName(c: Int): String = when (c) {
        in 4..29 -> letters[c - 4].toString()
        in 30..38 -> (c - 29).toString()
        39 -> "0"
        40 -> "Enter"; 41 -> "Esc"; 42 -> "Bksp"; 43 -> "Tab"; 44 -> "Space"
        45 -> "-"; 46 -> "="; 47 -> "["; 48 -> "]"; 49 -> "\\"; 51 -> ";"; 52 -> "'"
        53 -> "`"; 54 -> ","; 55 -> "."; 56 -> "/"; 57 -> "Caps"
        in 58..69 -> "F${c - 57}"
        70 -> "PrtSc"; 71 -> "ScrLk"; 72 -> "Pause"; 73 -> "Ins"; 74 -> "Home"
        75 -> "PgUp"; 76 -> "Del"; 77 -> "End"; 78 -> "PgDn"
        79 -> "Right"; 80 -> "Left"; 81 -> "Down"; 82 -> "Up"
        83 -> "NumLk"; 84 -> "N/"; 85 -> "N*"; 86 -> "N-"; 87 -> "N+"; 88 -> "NEnt"
        in 89..97 -> "N${c - 88}"
        98 -> "N0"; 99 -> "N."; 101 -> "Menu"
        in 104..115 -> "F${c - 91}"
        else -> "0x%02X".format(c)
    }

    private fun modName(m: Int): String {
        val p = ArrayList<String>()
        if (m and 1 != 0) p.add("LCtrl"); if (m and 2 != 0) p.add("LShift")
        if (m and 4 != 0) p.add("LAlt"); if (m and 8 != 0) p.add("LWin")
        if (m and 16 != 0) p.add("RCtrl"); if (m and 32 != 0) p.add("RShift")
        if (m and 64 != 0) p.add("RAlt"); if (m and 128 != 0) p.add("RWin")
        return if (p.isEmpty()) "-" else p.joinToString("+")
    }

    private fun labelOf(i: Int): String {
        val t = keymap[3 * i].toInt() and 0xFF
        val c1 = keymap[3 * i + 1].toInt() and 0xFF
        val c2 = keymap[3 * i + 2].toInt() and 0xFF
        return when (t) {
            16 -> if (c2 != 0) hidName(c2) + (if (c1 != 0) "+m" else "") else modName(c1)
            240 -> if (c1 == 255) (if (c2 == 0) "FN" else "FN$c2") else "fn%02X.%02X".format(c1, c2)
            48 -> when (c1) {
                233 -> "Vol+"; 234 -> "Vol-"; 226 -> "Mute"; 205 -> "Play"
                181 -> "Next"; 182 -> "Prev"; 183 -> "Stop"; else -> "media%02X".format(c1)
            }
            else -> "T$t"
        }
    }

    private fun buildTargets() {
        for (i in 0 until 26) targets.add(Target(letters[i].toString(), 16, 0, 4 + i))
        for (i in 1..9) targets.add(Target(i.toString(), 16, 0, 29 + i))
        targets.add(Target("0", 16, 0, 39))
        for (i in 1..12) targets.add(Target("F$i", 16, 0, 57 + i))
        for (i in 13..24) targets.add(Target("F$i", 16, 0, 91 + i))
        val sp = listOf(
            "Enter" to 40, "Esc" to 41, "Backspace" to 42, "Tab" to 43, "Space" to 44,
            "Caps" to 57, "Del" to 76, "Home" to 74, "End" to 77, "PgUp" to 75, "PgDn" to 78,
            "Up" to 82, "Down" to 81, "Left" to 80, "Right" to 79, "PrtSc" to 70, "Menu" to 101
        )
        for ((n, c) in sp) targets.add(Target(n, 16, 0, c))
        targets.add(Target("LCtrl", 16, 1, 0)); targets.add(Target("LShift", 16, 2, 0))
        targets.add(Target("LAlt", 16, 4, 0)); targets.add(Target("LWin", 16, 8, 0))
        targets.add(Target("Vol+", 48, 233, 0)); targets.add(Target("Vol-", 48, 234, 0))
        targets.add(Target("Mute", 48, 226, 0)); targets.add(Target("Play/Pause", 48, 205, 0))
        targets.add(Target("Next track", 48, 181, 0)); targets.add(Target("Prev track", 48, 182, 0))
    }

    // ---------------------------------------------------------------- connect / load
    private fun connectClicked() {
        synchronized(sb) { sb.setLength(0) }
        log("USB devices: ${usb.deviceList.size}")
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == vendorId }
        if (dev == null) { log("No device with VID 19F5."); return }
        if (usb.hasPermission(dev)) {
            io.execute { guard { connect(dev) } }
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(permAction).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun connect(dev: UsbDevice) {
        kb?.close(); kb = null
        log("Found VID=%04X PID=%04X".format(dev.vendorId, dev.productId))
        val conn = usb.openDevice(dev)
        if (conn == null) { log("openDevice failed"); return }
        var chosen: UsbInterface? = null
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (n in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(n)
            var i: UsbEndpoint? = null
            var o: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
                }
            }
            val ok = itf.interfaceClass == 3 && i != null && i.maxPacketSize == 64
            if (ok && (chosen == null || itf.id == 1)) { chosen = itf; epIn = i; epOut = o }
        }
        if (chosen == null || epIn == null) { log("No HID interface with 64-byte endpoint."); conn.close(); return }
        if (!conn.claimInterface(chosen, true)) { log("claimInterface failed"); conn.close(); return }
        val k = Kb(conn, chosen, epIn, epOut)
        kb = k
        val info = k.simple(0x03)
        if (info != null) log("Firmware %02X%02X".format(info[1], info[0]))
        loadProfile(-1)
    }

    private fun loadClicked(p: Int) {
        profile = p
        io.execute { guard { loadProfile(p) } }
    }

    // p < 0 means "use the profile the keyboard reports as active"
    private fun loadProfile(p0: Int): Boolean {
        val k = kb
        if (k == null) { log("Connect first."); return false }
        val base = k.simple(0x04)
        if (base != null) activeProfile = base[0].toInt() and 0xFF
        var p = p0
        if (p < 0) { p = if (activeProfile in 0..3) activeProfile else 0; profile = p }
        val t = k.read(0xA0, 1024 * p, 1024) ?: return false
        val km = k.read(0x08, 2048 * p, 512) ?: return false
        val kd = k.read(0x07, 2048 * p, 512) ?: return false
        trig = t; keymap = km; keymapDef = kd
        saveOriginalBackup(p)
        keyIdx.clear()
        for (i in 0 until 128) {
            val ty = keymap[3 * i].toInt() and 0xFF
            if (ty != 0 && ty != 255) keyIdx.add(i)
        }
        log("Loaded profile $p (keyboard's active profile: $activeProfile), ${keyIdx.size} keys")
        runOnUiThread {
            rebuildGrid()
            status.text = "Editing profile $p  |  active on keyboard: $activeProfile  |  ${keyIdx.size} keys"
        }
        return true
    }

    private fun switchProfile(p: Int) {
        val k = kb
        if (k == null) { log("Connect first."); return }
        log("Switching keyboard to profile $p ...")
        k.sendProfileSwitch(p)
        Thread.sleep(1200)
        loadProfile(p)
    }

    private fun rebuildGrid() {
        grid.removeAllViews(); boxes.clear()
        for (i in keyIdx) {
            val cb = CheckBox(this)
            cb.text = labelOf(i) + "\n#" + i
            cb.textSize = 12f
            cb.tag = i
            cb.setOnCheckedChangeListener { _, _ -> onSelection() }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            grid.addView(cb, lp)
            boxes.add(cb)
        }
    }

    private fun selected(): List<Int> = boxes.filter { it.isChecked }.map { it.tag as Int }

    private fun onSelection() {
        val sel = selected()
        if (sel.size != 1) return
        val i = sel[0]
        val d = IntArray(8) { trig[i * 8 + it].toInt() and 0xFF }
        actS.setTo((((d[3] and 1) shl 8) or d[2]) + 1)
        pressS.setTo((((d[5] and 1) shl 8) or d[4]) + 1)
        relS.setTo((((d[7] and 1) shl 8) or d[6]) + 1)
        val mode = d[1] and 0x0F
        rtSw.isChecked = mode > 0
        contSw.isChecked = mode >= 2
    }

    // ---------------------------------------------------------------- apply triggers
    private fun setLE9(e: ByteArray, off: Int, raw: Int) {
        e[off] = (raw and 0xFF).toByte()
        e[off + 1] = ((e[off + 1].toInt() and 0xFE) or ((raw shr 8) and 1)).toByte()
    }

    private fun applyClicked() {
        val sel = selected()
        if (sel.isEmpty()) { log("Select at least one key first."); return }
        val act = actS.value
        val rt = rtSw.isChecked
        val cont = contSw.isChecked
        val pr = pressS.value
        val rl = relS.value
        val p = profile
        io.execute { guard { applyTriggers(p, sel, act, rt, cont, pr, rl) } }
    }

    private fun applyTriggers(p: Int, sel: List<Int>, act: Int, rt: Boolean, cont: Boolean, pr: Int, rl: Int) {
        val k = kb
        if (k == null) { log("Connect first."); return }
        val wanted = HashMap<Int, ByteArray>()
        for (i in sel) {
            val e = trig.copyOfRange(i * 8, i * 8 + 8)
            val mode = if (rt) (if (cont) 2 else 1) else 0
            e[1] = ((e[1].toInt() and 0xF0) or mode).toByte()
            setLE9(e, 2, act - 1)
            if (rt) { setLE9(e, 4, pr - 1); setLE9(e, 6, rl - 1) }
            if (!k.write(0xA1, 1024 * p + 8 * i, e)) { log("Write failed at key #$i, stopping."); return }
            wanted[i] = e
        }
        val t2 = k.read(0xA0, 1024 * p, 1024)
        if (t2 == null) { log("Written, but read-back failed."); return }
        var ok = 0
        for (i in sel) {
            if (t2.copyOfRange(i * 8, i * 8 + 8).contentEquals(wanted[i]!!)) ok++
            else log("MISMATCH key #$i wanted ${hex(wanted[i]!!)} got ${hex(t2.copyOfRange(i * 8, i * 8 + 8))}")
        }
        trig = t2
        log("Trigger settings: $ok/${sel.size} keys verified by read-back.")
    }

    // ---------------------------------------------------------------- remap
    private fun remapClicked(factory: Boolean) {
        val sel = selected()
        if (sel.isEmpty()) { log("Select at least one key first."); return }
        val tg = targets[targetSpin.selectedItemPosition]
        val p = profile
        io.execute { guard { remap(p, sel, tg, factory) } }
    }

    private fun remap(p: Int, sel: List<Int>, tg: Target, factory: Boolean) {
        val k = kb
        if (k == null) { log("Connect first."); return }
        for (i in sel) {
            val d = if (factory) keymapDef.copyOfRange(3 * i, 3 * i + 3)
            else byteArrayOf(tg.t.toByte(), tg.c1.toByte(), tg.c2.toByte())
            if (!k.write(0x09, 2048 * p + 3 * i, d)) { log("Write failed at key #$i, stopping."); return }
        }
        val km = k.read(0x08, 2048 * p, 512)
        if (km == null) { log("Written, but read-back failed."); return }
        var ok = 0
        for (i in sel) {
            val want = if (factory) keymapDef.copyOfRange(3 * i, 3 * i + 3)
            else byteArrayOf(tg.t.toByte(), tg.c1.toByte(), tg.c2.toByte())
            if (km.copyOfRange(3 * i, 3 * i + 3).contentEquals(want)) ok++
            else log("MISMATCH key #$i")
        }
        keymap = km
        log("Remap: $ok/${sel.size} keys verified." + if (factory) " (factory mapping)" else " -> ${tg.name}")
        runOnUiThread {
            val checked = selected()
            rebuildGrid()
            for (b in boxes) if ((b.tag as Int) in checked) b.isChecked = true
        }
    }

    // ---------------------------------------------------------------- backup
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun backupFile(p: Int) = File(filesDir, "backup_p$p.txt")

    private fun saveOriginalBackup(p: Int) {
        val f = backupFile(p)
        if (f.exists()) return
        f.writeText("T " + hex(trig) + "\nK " + hex(keymap) + "\n")
        log("Saved original backup for profile $p")
    }

    private fun copyBackup() {
        val f = backupFile(profile)
        if (!f.exists()) { log("No backup for profile $profile yet."); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("m30backup", "profile ${profile}\n" + f.readText()))
        log("Backup for profile $profile copied to clipboard.")
    }

    private fun restoreClicked() {
        val p = profile
        if (!backupFile(p).exists()) { log("No backup for profile $p."); return }
        AlertDialog.Builder(this)
            .setMessage("Overwrite trigger settings and layer-0 key mapping of profile $p with the saved original?")
            .setPositiveButton("Restore") { _, _ -> io.execute { guard { restore(p) } } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restore(p: Int) {
        val k = kb
        if (k == null) { log("Connect first."); return }
        var t: ByteArray? = null
        var km: ByteArray? = null
        for (line in backupFile(p).readLines()) {
            if (line.startsWith("T ")) t = unhex(line.substring(2).trim())
            if (line.startsWith("K ")) km = unhex(line.substring(2).trim())
        }
        if (t == null || t.size != 1024 || km == null || km.size < 384) { log("Backup file is damaged."); return }
        if (!k.write(0xA1, 1024 * p, t)) { log("Restore failed (trigger)."); return }
        if (!k.write(0x09, 2048 * p, km.copyOf(384))) { log("Restore failed (keymap)."); return }
        log("Backup written, reloading ...")
        loadProfile(p)
    }
}
