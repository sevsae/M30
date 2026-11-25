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

// READ-ONLY probe v3. Sends only: 55 01, 55 04 (len 16), 55 07 (read).
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
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
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
        say("Plug keyboard into phone with USB-C OTG, then tap Probe.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun say(s: String) {
        sb.append(s).append('\n')
        runOnUiThread { logView.text = sb.toString() }
    }

    private fun hex(b: ByteArray, n: Int = b.size) =
        (0 until n).joinToString(" ") { "%02X".format(b[it]) }

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
        say("Using interface ${chosen.id}")
        if (!conn.claimInterface(chosen, true)) { say("claimInterface failed"); conn.close(); return }

        try {
            say("Sending 55 01")
            val info = request(conn, chosen, epIn, epOut, 0x01, 0, 0, 0)
            say("  reply: " + (info?.let { hex(it) } ?: "none"))

            say("Sending 55 04 len=16")
            val p = request(conn, chosen, epIn, epOut, 0x04, 16, 0, 0)
            say("  reply: " + (p?.let { hex(it) } ?: "none"))

            say("Scanning 55 07 reads, addr 0000-3F00 step 0100...")
            var hits = 0
            for (base in 0 until 0x4000 step 0x100) {
                val r = request(conn, chosen, epIn, epOut, 0x07, 56, base and 0xFF, base shr 8, 250, 1, true)
                if (r != null) {
                    hits++
                    say("HIT addr=%04X: %s".format(base, hex(r)))
                }
            }
            say("Scan done, hits=$hits")
            say("Tap Copy log and send it to Claude.")
        } finally {
            conn.releaseInterface(chosen)
            conn.close()
        }
    }

    private fun request(
        conn: UsbDeviceConnection, itf: UsbInterface, epIn: UsbEndpoint, epOut: UsbEndpoint?,
        cmd: Int, len: Int, lo: Int, hi: Int,
        timeout: Int = 700, tries: Int = 3, quiet: Boolean = false
    ): ByteArray? {
        val req = ByteArray(64)
        req[0] = 0x55
        req[1] = cmd.toByte()
        req[4] = len.toByte()
        req[5] = lo.toByte()
        req[6] = hi.toByte()
        val w = if (epOut != null) conn.bulkTransfer(epOut, req, req.size, 1000)
        else conn.controlTransfer(0x21, 0x09, 0x0200, itf.id, req, req.size, 1000)
        if (!quiet) say("  write result=$w")
        if (w < 0) return null
        for (k in 0 until tries) {
            val buf = ByteArray(64)
            val r = conn.bulkTransfer(epIn, buf, buf.size, timeout)
            if (!quiet) say("  read[$k] result=$r " + (if (r > 0) hex(buf, r) else ""))
            if (r > 0) return buf
        }
        return null
    }
}
