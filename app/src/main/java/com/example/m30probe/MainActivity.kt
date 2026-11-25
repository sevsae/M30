package com.example.m30probe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// PROBE v2. READ ONLY.
// Frame (from FuryCube web app): 55 CMD 00 CHK LEN ADDR_LO ADDR_HI 00 ...
//   CHK = (ADDR_LO + ADDR_HI + LEN) & 0xFF.  Reply data starts at byte 8.
// Reads used: 03 (info), 04 (base), 05 (config), A0 (key trigger table).
// NEVER sends 01, 02, 06, 09, A1, A8, A9, EE or any write.
class MainActivity : Activity() {

    private val action = "com.example.m30probe.USB_PERMISSION"
    private val vendorId = 0x19F5
    private lateinit var logView: TextView
    private lateinit var usb: UsbManager
    private val sb = StringBuilder()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != action) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                Thread { probe(dev) }.start()
            } else {
                say("USB permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val probeBtn = Button(this).apply { text = "Probe keyboard (read only)" }
        val copyBtn = Button(this).apply { text = "Copy log" }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(probeBtn)
        root.addView(copyBtn)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        probeBtn.setOnClickListener { start() }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("m30probe", sb.toString()))
            say("(log copied)")
        }
        say("v2 read-only. Plug keyboard in with USB-C OTG, tap Probe.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun say(s: String) {
        sb.append(s).append('\n')
        runOnUiThread { logView.text = sb.toString() }
    }

    private fun hex(b: ByteArray, from: Int = 0, to: Int = b.size) =
        (from until to).joinToString(" ") { "%02X".format(b[it]) }

    private fun start() {
        sb.setLength(0)
        say("USB devices seen: ${usb.deviceList.size}")
        usb.deviceList.values.forEach { say("  VID=%04X PID=%04X".format(it.vendorId, it.productId)) }
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == vendorId }
        if (dev == null) { say("No device with VID 19F5 found."); return }
        if (usb.hasPermission(dev)) {
            Thread { probe(dev) }.start()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun probe(dev: UsbDevice) {
        say("Found VID=%04X PID=%04X, interfaces=%d".format(dev.vendorId, dev.productId, dev.interfaceCount))
        val conn = usb.openDevice(dev)
        if (conn == null) { say("openDevice failed"); return }

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
            say("  iface id=%d class=%d inMax=%s outMax=%s".format(itf.id, itf.interfaceClass, i?.maxPacketSize, o?.maxPacketSize))
            val ok = itf.interfaceClass == 3 && i != null && i.maxPacketSize == 64
            if (ok && (chosen == null || itf.id == 1)) { chosen = itf; epIn = i; epOut = o }
        }
        if (chosen == null || epIn == null) { say("No HID interface with 64-byte IN endpoint."); conn.close(); return }
        say("Using interface ${chosen.id} (out endpoint: ${if (epOut != null) "yes" else "no, using control SET_REPORT"})")
        if (!conn.claimInterface(chosen, true)) { say("claimInterface failed"); conn.close(); return }

        try {
            // 03 = info. Web app sends 55 03 00 20 20.
            val info = readBlock(conn, chosen, epIn, epOut, 0x03, 0, 32, rawChk = 32)
            say("INFO  data: " + (info?.let { hex(it) } ?: "none"))
            if (info != null && info.size > 7) {
                say("  fw_ver=%02X%02X keycode_new=%02X".format(info[1], info[0], info[7]))
            }

            // 04 = base (profile index etc).
            val base = readBlock(conn, chosen, epIn, epOut, 0x04, 0, 32, rawChk = 32)
            say("BASE  data: " + (base?.let { hex(it) } ?: "none"))

            // 05 = config, 64 bytes.
            val cfg = readBlock(conn, chosen, epIn, epOut, 0x05, 0, 64)
            say("CONFIG data: " + (cfg?.let { hex(it) } ?: "none"))
            if (cfg != null && cfg.size >= 8) {
                val b4 = cfg[4].toInt() and 0xFF
                val b7 = cfg[7].toInt() and 0xFF
                say("  reportRate=%d tick=%d debounce=%d rtBottomMode=%d".format(
                    b4 and 15, (b4 shr 4) and 15, (b7 shr 5) and 7, (b7 shr 1) and 1))
            }

            // A0 = key trigger table, profile 0, 128 keys x 8 bytes = 1024.
            val trig = readBlock(conn, chosen, epIn, epOut, 0xA0, 0, 1024)
            if (trig == null) {
                say("TRIGGER read failed.")
            } else {
                say("TRIGGER raw (first 256 bytes):")
                for (r in 0 until 8) say("  " + hex(trig, r * 32, r * 32 + 32))
                say("Decoded keys (idx: actuation / rtPress / rtRelease, raw units, mode):")
                var shown = 0
                for (k in 0 until 128) {
                    val d = IntArray(8) { trig[k * 8 + it].toInt() and 0xFF }
                    if (d.all { it == 0 }) continue
                    val act = ((d[3] and 1) shl 8 or d[2]) + 1
                    val prs = ((d[5] and 1) shl 8 or d[4]) + 1
                    val rel = ((d[7] and 1) shl 8 or d[6]) + 1
                    val pdz = (d[5] shr 1) and 127
                    val rdz = (d[7] shr 1) and 127
                    say("  key %3d: act=%d prs=%d rel=%d dz=%d/%d mode=%02X sw=%d".format(
                        k, act, prs, rel, pdz, rdz, d[1], d[0] and 15))
                    shown++
                }
                say("Shown $shown keys. Unit maybe 0.01 mm (0.02 on some models).")
            }
            say("Done. Tap Copy log and send it to Claude.")
        } finally {
            conn.releaseInterface(chosen)
            conn.close()
        }
    }

    // Read `total` bytes, 56 per packet. Returns only data bytes (reply offset 8).
    private fun readBlock(
        conn: UsbDeviceConnection, itf: UsbInterface, epIn: UsbEndpoint, epOut: UsbEndpoint?,
        cmd: Int, base: Int, total: Int, rawChk: Int = -1
    ): ByteArray? {
        val out = ByteArray(total)
        var off = 0
        while (off < total) {
            val len = minOf(56, total - off)
            val addr = base + off
            val lo = addr and 0xFF
            val hi = (addr shr 8) and 0xFF
            val chk = if (rawChk >= 0) rawChk else (lo + hi + len) and 0xFF
            val rep = exchange(conn, itf, epIn, epOut, cmd, chk, len, lo, hi) ?: return null
            val n = minOf(len, rep.size - 8)
            if (n <= 0) return null
            System.arraycopy(rep, 8, out, off, n)
            off += len
        }
        return out
    }

    private fun exchange(
        conn: UsbDeviceConnection, itf: UsbInterface, epIn: UsbEndpoint, epOut: UsbEndpoint?,
        cmd: Int, chk: Int, len: Int, lo: Int, hi: Int
    ): ByteArray? {
        val req = ByteArray(64)
        req[0] = 0x55
        req[1] = cmd.toByte()
        req[2] = 0
        req[3] = chk.toByte()
        req[4] = len.toByte()
        req[5] = lo.toByte()
        req[6] = hi.toByte()
        val w = if (epOut != null) conn.bulkTransfer(epOut, req, req.size, 1000)
        else conn.controlTransfer(0x21, 0x09, 0x0200, itf.id, req, req.size, 1000)
        if (w < 0) { say("  write failed ($w) cmd=%02X".format(cmd)); return null }
        repeat(8) {
            val buf = ByteArray(64)
            val r = conn.bulkTransfer(epIn, buf, buf.size, 1000)
            if (r <= 0) { say("  no reply cmd=%02X".format(cmd)); return null }
            if (buf[0] == 0x55.toByte() || buf[1] == cmd.toByte()) return buf
            say("  skip packet: " + hex(buf, 0, 8))
        }
        return null
    }
}package com.example.m30probe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// PROBE v2. READ ONLY.
// Frame (from FuryCube web app): 55 CMD 00 CHK LEN ADDR_LO ADDR_HI 00 ...
//   CHK = (ADDR_LO + ADDR_HI + LEN) & 0xFF.  Reply data starts at byte 8.
// Reads used: 03 (info), 04 (base), 05 (config), A0 (key trigger table).
// NEVER sends 01, 02, 06, 09, A1, A8, A9, EE or any write.
class MainActivity : Activity() {

    private val action = "com.example.m30probe.USB_PERMISSION"
    private val vendorId = 0x19F5
    private lateinit var logView: TextView
    private lateinit var usb: UsbManager
    private val sb = StringBuilder()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != action) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                Thread { probe(dev) }.start()
            } else {
                say("USB permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val probeBtn = Button(this).apply { text = "Probe keyboard (read only)" }
        val copyBtn = Button(this).apply { text = "Copy log" }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(probeBtn)
        root.addView(copyBtn)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        probeBtn.setOnClickListener { start() }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("m30probe", sb.toString()))
            say("(log copied)")
        }
        say("v2 read-only. Plug keyboard in with USB-C OTG, tap Probe.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun say(s: String) {
        sb.append(s).append('\n')
        runOnUiThread { logView.text = sb.toString() }
    }

    private fun hex(b: ByteArray, from: Int = 0, to: Int = b.size) =
        (from until to).joinToString(" ") { "%02X".format(b[it]) }

    private fun start() {
        sb.setLength(0)
        say("USB devices seen: ${usb.deviceList.size}")
        usb.deviceList.values.forEach { say("  VID=%04X PID=%04X".format(it.vendorId, it.productId)) }
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == vendorId }
        if (dev == null) { say("No device with VID 19F5 found."); return }
        if (usb.hasPermission(dev)) {
            Thread { probe(dev) }.start()
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), flags)
            usb.requestPermission(dev, pi)
        }
    }

    private fun probe(dev: UsbDevice) {
        say("Found VID=%04X PID=%04X, interfaces=%d".format(dev.vendorId, dev.productId, dev.interfaceCount))
        val conn = usb.openDevice(dev)
        if (conn == null) { say("openDevice failed"); return }

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
            say("  iface id=%d class=%d inMax=%s outMax=%s".format(itf.id, itf.interfaceClass, i?.maxPacketSize, o?.maxPacketSize))
            val ok = itf.interfaceClass == 3 && i != null && i.maxPacketSize == 64
            if (ok && (chosen == null || itf.id == 1)) { chosen = itf; epIn = i; epOut = o }
        }
        if (chosen == null || epIn == null) { say("No HID interface with 64-byte IN endpoint."); conn.close(); return }
        say("Using interface ${chosen.id} (out endpoint: ${if (epOut != null) "yes" else "no, using control SET_REPORT"})")
        if (!conn.claimInterface(chosen, true)) { say("claimInterface failed"); conn.close(); return }

        try {
            // 03 = info. Web app sends 55 03 00 20 20.
            val info = readBlock(conn, chosen, epIn, epOut, 0x03, 0, 32, rawChk = 32)
            say("INFO  data: " + (info?.let { hex(it) } ?: "none"))
            if (info != null && info.size > 7) {
                say("  fw_ver=%02X%02X keycode_new=%02X".format(info[1], info[0], info[7]))
            }

            // 04 = base (profile index etc).
            val base = readBlock(conn, chosen, epIn, epOut, 0x04, 0, 32, rawChk = 32)
            say("BASE  data: " + (base?.let { hex(it) } ?: "none"))

            // 05 = config, 64 bytes.
            val cfg = readBlock(conn, chosen, epIn, epOut, 0x05, 0, 64)
            say("CONFIG data: " + (cfg?.let { hex(it) } ?: "none"))
            if (cfg != null && cfg.size >= 8) {
                val b4 = cfg[4].toInt() and 0xFF
                val b7 = cfg[7].toInt() and 0xFF
                say("  reportRate=%d tick=%d debounce=%d rtBottomMode=%d".format(
                    b4 and 15, (b4 shr 4) and 15, (b7 shr 5) and 7, (b7 shr 1) and 1))
            }

            // A0 = key trigger table, profile 0, 128 keys x 8 bytes = 1024.
            val trig = readBlock(conn, chosen, epIn, epOut, 0xA0, 0, 1024)
            if (trig == null) {
                say("TRIGGER read failed.")
            } else {
                say("TRIGGER raw (first 256 bytes):")
                for (r in 0 until 8) say("  " + hex(trig, r * 32, r * 32 + 32))
                say("Decoded keys (idx: actuation / rtPress / rtRelease, raw units, mode):")
                var shown = 0
                for (k in 0 until 128) {
                    val d = IntArray(8) { trig[k * 8 + it].toInt() and 0xFF }
                    if (d.all { it == 0 }) continue
                    val act = ((d[3] and 1) shl 8 or d[2]) + 1
                    val prs = ((d[5] and 1) shl 8 or d[4]) + 1
                    val rel = ((d[7] and 1) shl 8 or d[6]) + 1
                    val pdz = (d[5] shr 1) and 127
                    val rdz = (d[7] shr 1) and 127
                    say("  key %3d: act=%d prs=%d rel=%d dz=%d/%d mode=%02X sw=%d".format(
                        k, act, prs, rel, pdz, rdz, d[1], d[0] and 15))
                    shown++
                }
                say("Shown $shown keys. Unit maybe 0.01 mm (0.02 on some models).")
            }
            say("Done. Tap Copy log and send it to Claude.")
        } finally {
            conn.releaseInterface(chosen)
            conn.close()
        }
    }

    // Read `total` bytes, 56 per packet. Returns only data bytes (reply offset 8).
    private fun readBlock(
        conn: UsbDeviceConnection, itf: UsbInterface, epIn: UsbEndpoint, epOut: UsbEndpoint?,
        cmd: Int, base: Int, total: Int, rawChk: Int = -1
    ): ByteArray? {
        val out = ByteArray(total)
        var off = 0
        while (off < total) {
            val len = minOf(56, total - off)
            val addr = base + off
            val lo = addr and 0xFF
            val hi = (addr shr 8) and 0xFF
            val chk = if (rawChk >= 0) rawChk else (lo + hi + len) and 0xFF
            val rep = exchange(conn, itf, epIn, epOut, cmd, chk, len, lo, hi) ?: return null
            val n = minOf(len, rep.size - 8)
            if (n <= 0) return null
            System.arraycopy(rep, 8, out, off, n)
            off += len
        }
        return out
    }

    private fun exchange(
        conn: UsbDeviceConnection, itf: UsbInterface, epIn: UsbEndpoint, epOut: UsbEndpoint?,
        cmd: Int, chk: Int, len: Int, lo: Int, hi: Int
    ): ByteArray? {
        val req = ByteArray(64)
        req[0] = 0x55
        req[1] = cmd.toByte()
        req[2] = 0
        req[3] = chk.toByte()
        req[4] = len.toByte()
        req[5] = lo.toByte()
        req[6] = hi.toByte()
        val w = if (epOut != null) conn.bulkTransfer(epOut, req, req.size, 1000)
        else conn.controlTransfer(0x21, 0x09, 0x0200, itf.id, req, req.size, 1000)
        if (w < 0) { say("  write failed ($w) cmd=%02X".format(cmd)); return null }
        repeat(8) {
            val buf = ByteArray(64)
            val r = conn.bulkTransfer(epIn, buf, buf.size, 1000)
            if (r <= 0) { say("  no reply cmd=%02X".format(cmd)); return null }
            if (buf[0] == 0x55.toByte() || buf[1] == cmd.toByte()) return buf
            say("  skip packet: " + hex(buf, 0, 8))
        }
        return null
    }
}
