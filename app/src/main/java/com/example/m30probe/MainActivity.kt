package com.example.m30probe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.KeyEvent
import android.animation.ValueAnimator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

private data class Target(
    val name: String,
    val t: Int,
    val c1: Int,
    val c2: Int
)

class MainActivity : Activity() {

    // ------------------------------------------------------------
    // USB AND KEYBOARD
    // ------------------------------------------------------------

    private val permAction = "com.example.m30probe.USB_PERMISSION"
    private val vendorId = 0x19F5

    private lateinit var usb: UsbManager

    private val io = Executors.newSingleThreadExecutor()

    private var kb: Kb? = null

    @Volatile
    private var profile = 0

    @Volatile
    private var activeProfile = -1

    private var trig = ByteArray(1024)
    private var keymap = ByteArray(512)
    private var keymapDef = ByteArray(512)

    private val keyIdx = ArrayList<Int>()
    private val boxes = ArrayList<CheckBox>()
    private val targets = ArrayList<Target>()
    private val sb = StringBuilder()

    // ------------------------------------------------------------
    // UI REFERENCES
    // ------------------------------------------------------------

    private lateinit var logView: TextView
    private lateinit var status: TextView
    private lateinit var grid: GridLayout
    private lateinit var keySearch: EditText
    private lateinit var pageHost: LinearLayout

    private val pages = linkedMapOf<String, LinearLayout>()
    private val navigationButtons = linkedMapOf<String, Button>()

    private lateinit var actS: Slider
    private lateinit var pressS: Slider
    private lateinit var relS: Slider

    private lateinit var rtSw: Switch
    private lateinit var contSw: Switch

    private lateinit var targetSpin: Spinner
    private lateinit var notificationBanner: TextView
    private lateinit var pageTitle: TextView
    private lateinit var pageSubtitle: TextView

    private var tabletLayoutEnabled = false
    private var wideLayoutEnabled = false
    private val pageCardRows = mutableMapOf<String, LinearLayout>()
    private val pageCardCounts = mutableMapOf<String, Int>()

    private lateinit var selectionCount: TextView
    private lateinit var lightColorInput: EditText
    private lateinit var lightBrightness: SeekBar
    private lateinit var lightBrightnessLabel: TextView
    private lateinit var lightPreview: View
    private lateinit var lightStatus: TextView
    private lateinit var travelVisual: KeyTravelView

    private val notificationChannelId = "m30_config_notifications"
    private var notificationCounter = 100

    // ------------------------------------------------------------
    // DARK THEME COLORS
    // ------------------------------------------------------------

    private val backgroundColor = Color.rgb(11, 17, 25)
    private val cardColor = Color.rgb(20, 30, 42)
    private val secondaryCardColor = Color.rgb(31, 43, 56)

    private val accentColor = Color.rgb(99, 215, 195)
    private val successColor = Color.rgb(35, 145, 111)
    private val warningColor = Color.rgb(210, 157, 66)
    private val errorColor = Color.rgb(198, 75, 86)

    private val primaryText = Color.rgb(242, 246, 249)
    private val secondaryText = Color.rgb(166, 181, 195)

    /** A switch illustration driven by digital HID key events and the configured actuation threshold. */
    private inner class KeyTravelView : View(this@MainActivity) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private var keyName = "—"
        private var keyDown = false
        private var pressAmount = 0f
        private var actuationMm = 2.0f
        private var animator: ValueAnimator? = null

        fun setKeyEvent(name: String, pressed: Boolean) {
            keyName = name
            keyDown = pressed
            animator?.cancel()
            animator = ValueAnimator.ofFloat(pressAmount, if (pressed) 1f else 0f).apply {
                duration = 115
                addUpdateListener {
                    pressAmount = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun setActuation(valueMm: Float) {
            actuationMm = valueMm.coerceIn(0.1f, 3.4f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val left = dp(18).toFloat()
            val right = w - dp(18)
            val top = dp(22).toFloat()
            val bottom = h - dp(32)
            val scaleX = right - dp(36)
            val scaleTop = top + dp(20)
            val scaleBottom = bottom - dp(5)
            val scaleHeight = scaleBottom - scaleTop
            val thresholdY = scaleTop + scaleHeight * (actuationMm / 3.5f)

            ink.style = Paint.Style.FILL
            ink.color = Color.rgb(13, 22, 30)
            canvas.drawRoundRect(RectF(left, top, scaleX - dp(20), bottom), dp(18).toFloat(), dp(18).toFloat(), ink)

            // Plate and switch housing.
            val centerX = (left + scaleX - dp(20)) / 2f
            val plateY = top + (bottom - top) * 0.70f
            ink.color = Color.rgb(68, 89, 103)
            canvas.drawRoundRect(RectF(left + dp(20), plateY, scaleX - dp(44), plateY + dp(12)), dp(5).toFloat(), dp(5).toFloat(), ink)
            ink.color = Color.rgb(92, 119, 132)
            canvas.drawRoundRect(RectF(centerX - dp(45), plateY - dp(73), centerX + dp(45), bottom - dp(12)), dp(13).toFloat(), dp(13).toFloat(), ink)
            ink.color = Color.rgb(47, 68, 79)
            canvas.drawRoundRect(RectF(centerX - dp(31), plateY - dp(58), centerX + dp(31), bottom - dp(12)), dp(9).toFloat(), dp(9).toFloat(), ink)

            // Spring and stem are illustrative; Android HID only reports key down/up.
            val springTop = plateY - dp(52) + dp((pressAmount * 11).toInt())
            val springBottom = plateY - dp(11)
            val spring = Path().apply {
                moveTo(centerX, springTop)
                val turns = 6
                for (i in 1..turns) {
                    val y = springTop + (springBottom - springTop) * i / (turns + 1)
                    lineTo(centerX + if (i % 2 == 0) dp(9) else -dp(9), y)
                }
                lineTo(centerX, springBottom)
            }
            ink.color = accentColor
            ink.strokeWidth = dp(3).toFloat()
            ink.style = Paint.Style.STROKE
            ink.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(spring, ink)
            ink.style = Paint.Style.FILL

            // Keycap moves on a real down/up event, but is not a millimeter reading.
            val capDrop = dp((pressAmount * 24).toInt()).toFloat()
            val capTop = top + dp(28) + capDrop
            val capBottom = plateY - dp(49) + capDrop
            val cap = Path().apply {
                moveTo(centerX - dp(63), capBottom)
                lineTo(centerX - dp(52), capTop + dp(14))
                quadTo(centerX - dp(49), capTop, centerX - dp(36), capTop)
                lineTo(centerX + dp(36), capTop)
                quadTo(centerX + dp(49), capTop, centerX + dp(52), capTop + dp(14))
                lineTo(centerX + dp(63), capBottom)
                close()
            }
            ink.color = if (keyDown) accentColor else Color.rgb(153, 181, 191)
            canvas.drawPath(cap, ink)
            ink.color = if (keyDown) Color.rgb(45, 139, 130) else Color.rgb(105, 135, 146)
            canvas.drawRoundRect(RectF(centerX - dp(22), capBottom - dp(3), centerX + dp(22), capBottom + dp(18)), dp(6).toFloat(), dp(6).toFloat(), ink)

            // Millimeter scale indicates the configured actuation point only.
            ink.color = Color.rgb(106, 127, 139)
            ink.strokeWidth = dp(1).toFloat()
            val tickX = right - dp(49)
            for (i in 0..7) {
                val y = scaleTop + scaleHeight * i / 7f
                canvas.drawLine(tickX, y, tickX + dp(if (i % 2 == 0) 12 else 7), y, ink)
                if (i % 2 == 0) {
                    ink.style = Paint.Style.FILL
                    ink.textSize = sp(11f)
                    ink.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    ink.color = secondaryText
                    canvas.drawText("%.1f".format(Locale.US, i * 0.5f), tickX - dp(38), y + dp(4), ink)
                    ink.color = Color.rgb(106, 127, 139)
                    ink.style = Paint.Style.STROKE
                }
            }
            ink.color = accentColor
            ink.strokeWidth = dp(2).toFloat()
            canvas.drawLine(tickX - dp(5), thresholdY, right - dp(15), thresholdY, ink)
            ink.style = Paint.Style.FILL
            val marker = Path().apply {
                moveTo(right - dp(10), thresholdY)
                lineTo(right, thresholdY - dp(7))
                lineTo(right, thresholdY + dp(7))
                close()
            }
            canvas.drawPath(marker, ink)

            ink.color = primaryText
            ink.textSize = sp(16f)
            ink.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(if (keyDown) "$keyName  ·  pressed" else "$keyName  ·  released", left + dp(16), bottom + dp(22), ink)
            ink.color = accentColor
            ink.textSize = sp(12f)
            canvas.drawText("Actuation  %.2f mm".format(Locale.US, actuationMm), tickX - dp(8), thresholdY - dp(10), ink)
        }

        private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    }

    // ------------------------------------------------------------
    // USB TRANSPORT
    // ------------------------------------------------------------

    private inner class Kb(
        val conn: UsbDeviceConnection,
        val itf: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint?
    ) {

        private fun out(req: ByteArray): Boolean {
            val w = if (epOut != null) {
                conn.bulkTransfer(epOut, req, req.size, 1000)
            } else {
                conn.controlTransfer(
                    0x21,
                    0x09,
                    0x0200,
                    itf.id,
                    req,
                    req.size,
                    1000
                )
            }

            return w >= 0
        }

        fun drain() {
            val b = ByteArray(64)
            var n = 0

            while (n < 32 && conn.bulkTransfer(epIn, b, 64, 5) > 0) {
                n++
            }
        }

        private fun reply(cmd: Int, timeout: Int): ByteArray? {
            repeat(10) {
                val buf = ByteArray(64)
                val r = conn.bulkTransfer(epIn, buf, 64, timeout)

                if (r <= 0) return null

                if (buf[0] == 0x55.toByte() ||
                    buf[1] == cmd.toByte()
                ) {
                    return buf
                }
            }

            return null
        }

        fun simple(cmd: Int): ByteArray? {
            drain()

            val req = ByteArray(64)

            req[0] = 0x55
            req[1] = cmd.toByte()
            req[3] = 0x20
            req[4] = 0x20

            if (!out(req)) return null

            val rep = reply(cmd, 1000) ?: return null

            return rep.copyOfRange(8, 40)
        }

        fun read(
            cmd: Int,
            addr: Int,
            total: Int
        ): ByteArray? {

            val res = ByteArray(total)
            var off = 0

            while (off < total) {

                val len = minOf(56, total - off)
                val a = addr + off

                val lo = a and 0xFF
                val hi = (a shr 8) and 0xFF

                val req = ByteArray(64)

                req[0] = 0x55
                req[1] = cmd.toByte()

                req[3] = ((lo + hi + len) and 0xFF).toByte()
                req[4] = len.toByte()
                req[5] = lo.toByte()
                req[6] = hi.toByte()

                drain()

                if (!out(req)) {
                    log("USB write failed (read cmd %02X)".format(cmd))
                    return null
                }

                val rep = reply(cmd, 1000)

                if (rep == null) {
                    log("No reply cmd=%02X addr=%d".format(cmd, a))
                    return null
                }

                System.arraycopy(
                    rep,
                    8,
                    res,
                    off,
                    minOf(len, rep.size - 8)
                )

                off += len
            }

            return res
        }

        fun write(
            cmd: Int,
            addr: Int,
            data: ByteArray
        ): Boolean {

            var off = 0

            while (off < data.size) {

                val len = minOf(56, data.size - off)
                val a = addr + off

                val lo = a and 0xFF
                val hi = (a shr 8) and 0xFF

                val req = ByteArray(64)

                req[0] = 0x55
                req[1] = cmd.toByte()

                req[4] = len.toByte()
                req[5] = lo.toByte()
                req[6] = hi.toByte()
                req[7] = 0

                var sum = len + lo + hi

                for (i in 0 until len) {
                    req[8 + i] = data[off + i]
                    sum += data[off + i].toInt() and 0xFF
                }

                req[3] = (sum and 0xFF).toByte()

                drain()

                if (!out(req)) {
                    log("USB write failed (cmd %02X)".format(cmd))
                    return false
                }

                reply(cmd, 400)

                off += len
            }

            return true
        }

        fun sendProfileSwitch(p: Int) {

            drain()

            val req = ByteArray(64)

            req[0] = 0x55
            req[1] = 0x0E
            req[2] = 0

            req[3] = ((1 + p) and 0xFF).toByte()
            req[4] = 1

            req[5] = 0
            req[6] = 0
            req[7] = 0

            req[8] = p.toByte()

            out(req)

            reply(0x0E, 500)
        }

        fun close() {
            try {
                conn.releaseInterface(itf)
            } catch (_: Exception) {
            }

            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------
    // BASIC UI HELPERS
    // ------------------------------------------------------------

    private fun dp(x: Int): Int {
        return (x * resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(
        color: Int,
        radius: Int = 14
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun createText(
        value: String,
        size: Float = 14f,
        color: Int = primaryText,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun createCard(): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(if (tabletLayoutEnabled) 22 else 16),
                dp(if (tabletLayoutEnabled) 20 else 16),
                dp(if (tabletLayoutEnabled) 22 else 16),
                dp(if (tabletLayoutEnabled) 20 else 16)
            )

            background = roundedBackground(cardColor, if (tabletLayoutEnabled) 20 else 16).apply {
                setStroke(dp(1), Color.rgb(39, 54, 69))
            }
        }
    }

    private fun addCard(
        parent: LinearLayout,
        child: View,
        fullWidth: Boolean = false
    ) {

        val pageName = pages.entries.firstOrNull { it.value === parent }?.key
        if (!wideLayoutEnabled || pageName == null) {
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(if (tabletLayoutEnabled) 16 else 12)
            parent.addView(child, params)
            return
        }

        if (fullWidth) {
            val cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cardRow.addView(child, LinearLayout.LayoutParams(-1, -2))
            parent.addView(cardRow, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(16)
            })
            pageCardRows.remove(pageName)
            pageCardCounts.remove(pageName)
            return
        }

        var count = pageCardCounts[pageName] ?: 0
        var cardRow = pageCardRows[pageName]
        if (cardRow == null || count >= 2) {
            cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            parent.addView(cardRow, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(16)
            })
            pageCardRows[pageName] = cardRow
            count = 0
        }

        val params = LinearLayout.LayoutParams(0, -2, 1f)
        if (count == 0) params.marginEnd = dp(12)
        cardRow.addView(child, params)
        count += 1
        if (count == 2) {
            pageCardRows.remove(pageName)
            pageCardCounts.remove(pageName)
        } else {
            pageCardCounts[pageName] = count
        }
    }

    private fun createPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun showPage(name: String) {
        val pageHeadings = mapOf(
            "device" to ("Device" to "Connect your keyboard and choose a profile."),
            "keys" to ("Keyboard setup" to "Tune trigger response and key assignments."),
            "lighting" to ("Lighting" to "Choose a color and brightness for the active profile."),
            "backup" to ("Backups" to "Save or restore your keyboard settings."),
            "log" to ("Activity" to "Review keyboard connection and change results.")
        )
        val heading = pageHeadings[name] ?: ("M30" to "Keyboard settings")
        pageTitle.text = heading.first
        pageSubtitle.text = heading.second
        pages.forEach { (key, page) ->
            page.visibility = if (key == name) View.VISIBLE else View.GONE
        }
        navigationButtons.forEach { (key, button) ->
            val selected = key == name
            button.background = roundedBackground(
                if (selected) Color.rgb(29, 58, 63) else Color.TRANSPARENT,
                12
            )
            button.setTextColor(if (selected) accentColor else secondaryText)
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (name == "lighting" && ::lightColorInput.isInitialized) {
            refreshLighting()
        }
    }

    private fun addSectionTitle(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null
    ) {

        parent.addView(
            createText(
                title,
                if (tabletLayoutEnabled) 19f else 16f,
                primaryText,
                true
            )
        )

        if (subtitle != null) {
            parent.addView(
                createText(
                    subtitle,
                    if (tabletLayoutEnabled) 14f else 12f,
                    secondaryText
                )
            )
        }

        val spacer = View(this)

        parent.addView(
            spacer,
            LinearLayout.LayoutParams(
                1,
                dp(if (tabletLayoutEnabled) 16 else 12)
            )
        )
    }

    private fun styleButton(button: Button) {

        button.isAllCaps = false
        button.textSize = if (tabletLayoutEnabled) 14f else 12f
        button.setTextColor(primaryText)

        button.background = roundedBackground(
            Color.rgb(38, 57, 74),
            14
        )

        button.minHeight = dp(if (tabletLayoutEnabled) 52 else 46)
        button.setPadding(
            dp(if (tabletLayoutEnabled) 14 else 10),
            dp(8),
            dp(if (tabletLayoutEnabled) 14 else 10),
            dp(8)
        )
    }

    private fun btn(
        t: String,
        f: () -> Unit
    ): Button {

        val b = Button(this)

        b.text = t

        styleButton(b)

        b.setOnClickListener {
            f()
        }

        return b
    }

    private fun row(vararg views: View): LinearLayout {

        val r = LinearLayout(this)

        r.orientation = LinearLayout.HORIZONTAL

        for (v in views) {

            val lp = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

            lp.marginEnd = dp(if (tabletLayoutEnabled) 10 else 6)

            r.addView(v, lp)
        }

        return r
    }

    // ------------------------------------------------------------
    // SLIDER
    // ------------------------------------------------------------

    private inner class Slider(
        val title: String,
        val min: Int,
        val max: Int,
        init0: Int
    ) {

        val label = TextView(this@MainActivity)

        val bar = SeekBar(this@MainActivity)

        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(12))
        }

        val value: Int
            get() = min + bar.progress

        init {

            label.setTextColor(primaryText)
            label.textSize = 14f

            bar.max = max - min
            bar.progress = init0 - min

            bar.progressTintList =
                android.content.res.ColorStateList.valueOf(accentColor)

            bar.thumbTintList =
                android.content.res.ColorStateList.valueOf(accentColor)

            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        s: SeekBar?,
                        p: Int,
                        fromUser: Boolean
                    ) {
                        refresh()
                        if (::travelVisual.isInitialized && title == "Actuation Point") {
                            travelVisual.setActuation(value / 100f)
                        }
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {
                    }

                    override fun onStopTrackingTouch(s: SeekBar?) {
                    }
                }
            )

            val steps = LinearLayout(this@MainActivity)

            steps.orientation = LinearLayout.HORIZONTAL

            for ((txt, d) in listOf(
                "-10" to -10,
                "-1" to -1,
                "+1" to 1,
                "+10" to 10
            )) {

                val b = Button(this@MainActivity)

                b.text = txt
                styleButton(b)
                b.textSize = 10f

                b.setOnClickListener {
                    setTo(value + d)
                }

                steps.addView(
                    b,
                    LinearLayout.LayoutParams(
                        0,
                        dp(40),
                        1f
                    )
                )
            }

            view.addView(label)
            view.addView(bar)
            view.addView(steps)

            refresh()
        }

        fun refresh() {

            label.text = "$title: " +
                "%.2f mm".format(
                    Locale.US,
                    value / 100.0
                )
        }

        fun setTo(v: Int) {

            bar.progress = (v - min).coerceIn(
                0,
                bar.max
            )

            refresh()
        }
    }

    // ------------------------------------------------------------
    // NOTIFICATION SYSTEM
    // ------------------------------------------------------------

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                notificationChannelId,
                "M30 Configuration",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            channel.description =
                "Notifications for M30 keyboard configuration changes."

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33) {

            if (checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    700
                )
            }
        }
    }

    private fun showNotification(
        title: String,
        message: String,
        type: String = "success"
    ) {

        runOnUiThread {

            val color = when (type) {
                "error" -> errorColor
                "warning" -> warningColor
                else -> successColor
            }

            // IN-APP BANNER

            if (::notificationBanner.isInitialized) {

                notificationBanner.text =
                    "$title\n$message"

                notificationBanner.background =
                    roundedBackground(color, 12)

                notificationBanner.visibility = View.VISIBLE

                notificationBanner.postDelayed({

                    if (::notificationBanner.isInitialized) {
                        notificationBanner.visibility = View.GONE
                    }

                }, 5000)
            }

            // ANDROID SYSTEM NOTIFICATION

            if (Build.VERSION.SDK_INT >= 33) {

                if (checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@runOnUiThread
                }
            }

            val icon = when (type) {
                "error" -> android.R.drawable.stat_notify_error
                else -> android.R.drawable.stat_sys_upload_done
            }

            val builder = if (Build.VERSION.SDK_INT >= 26) {

                Notification.Builder(
                    this,
                    notificationChannelId
                )

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val notification = builder
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(
                    Notification.BigTextStyle().bigText(message)
                )
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.notify(
                notificationCounter++,
                notification
            )
        }
    }

    // ------------------------------------------------------------
    // LOGGING
    // ------------------------------------------------------------

    private fun log(s: String) {

        synchronized(sb) {
            sb.append(s).append('\n')
        }

        runOnUiThread {

            if (::logView.isInitialized) {

                logView.text = synchronized(sb) {
                    sb.toString()
                }
            }
        }
    }

    private inline fun guard(f: () -> Unit) {

        try {
            f()
        } catch (e: Exception) {

            log("ERROR: $e")

            showNotification(
                "Operation Failed",
                e.message ?: e.toString(),
                "error"
            )
        }
    }

    // ------------------------------------------------------------
    // USB PERMISSION RECEIVER
    // ------------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {

        override fun onReceive(
            c: Context,
            i: Intent
        ) {

            if (i.action != permAction) return

            val dev: UsbDevice? =
                if (Build.VERSION.SDK_INT >= 33) {

                    i.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java
                    )

                } else {

                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE
                    )
                }

            if (
                i.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                ) && dev != null
            ) {

                io.execute {
                    guard {
                        connect(dev)
                    }
                }

            } else {

                log("USB permission denied")

                showNotification(
                    "USB Permission Denied",
                    "Allow USB access to connect your keyboard.",
                    "error"
                )
            }
        }
    }

    // ------------------------------------------------------------
    // MAIN UI
    // ------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        usb = getSystemService(
            Context.USB_SERVICE
        ) as UsbManager

        buildTargets()

        tabletLayoutEnabled = resources.configuration.smallestScreenWidthDp >= 600
        wideLayoutEnabled = resources.configuration.screenWidthDp >= 900

        createNotificationChannel()

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = 0
        }

        val root = LinearLayout(this).apply {
            orientation = if (wideLayoutEnabled) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val outerPadding = if (tabletLayoutEnabled) dp(24) else dp(16)
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            setBackgroundColor(backgroundColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createText("M30", if (tabletLayoutEnabled) 30f else 27f, primaryText, true))
            addView(createText("Keyboard control", 14f, secondaryText))
        }

        // NOTIFICATION BANNER
        notificationBanner = TextView(this).apply {
            textSize = if (tabletLayoutEnabled) 15f else 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            background = roundedBackground(accentColor, 12)
        }

        val nav = LinearLayout(this).apply {
            orientation = if (wideLayoutEnabled) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listOf(
            "device" to "Device",
            "keys" to "Keys",
            "lighting" to "RGB",
            "backup" to "Backup",
            "log" to "Log"
        ).forEach { (page, label) ->
            val button = btn(label) { showPage(page) }.apply {
                textSize = if (tabletLayoutEnabled) 15f else 13f
                minWidth = 0
                minHeight = dp(if (wideLayoutEnabled) 54 else if (tabletLayoutEnabled) 52 else 46)
                gravity = if (wideLayoutEnabled) android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.CENTER
                setPadding(dp(if (wideLayoutEnabled) 18 else 6), dp(8), dp(8), dp(8))
                contentDescription = "$label page"
            }
            navigationButtons[page] = button
            val params = if (wideLayoutEnabled) {
                LinearLayout.LayoutParams(-1, dp(54))
            } else {
                LinearLayout.LayoutParams(0, dp(if (tabletLayoutEnabled) 52 else 46), 1f)
            }
            params.bottomMargin = if (wideLayoutEnabled) dp(8) else 0
            params.marginEnd = if (wideLayoutEnabled) 0 else dp(4)
            nav.addView(button, params)
        }

        pageHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listOf("device", "keys", "lighting", "backup", "log").forEach { name ->
            val page = createPage()
            pages[name] = page
            pageHost.addView(page)
        }
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(backgroundColor)
            addView(pageHost)
        }

        pageTitle = createText("Device", if (tabletLayoutEnabled) 28f else 22f, primaryText, true)
        pageSubtitle = createText("Connect and manage your keyboard.", if (tabletLayoutEnabled) 15f else 13f, secondaryText)
        val pageHeading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pageTitle)
            val subtitleParams = LinearLayout.LayoutParams(-1, -2)
            subtitleParams.topMargin = dp(4)
            addView(pageSubtitle, subtitleParams)
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (wideLayoutEnabled) {
            val sidebar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = roundedBackground(cardColor, 20)
                addView(header)
                val separator = View(this@MainActivity).apply { setBackgroundColor(secondaryCardColor) }
                addView(separator, LinearLayout.LayoutParams(-1, dp(1)).apply {
                    topMargin = dp(24)
                    bottomMargin = dp(20)
                })
                addView(nav, LinearLayout.LayoutParams(-1, -2))
                val footer = createText("M30 HE  ·  USB", 12f, secondaryText)
                val footerParams = LinearLayout.LayoutParams(-1, -2)
                footerParams.topMargin = dp(20)
                addView(footer, footerParams)
            }
            val sidebarParams = LinearLayout.LayoutParams(dp(220), -1)
            sidebarParams.marginEnd = dp(22)
            root.addView(sidebar, sidebarParams)
        } else {
            root.addView(header)
            val navParams = LinearLayout.LayoutParams(-1, -2)
            navParams.topMargin = dp(16)
            navParams.bottomMargin = dp(14)
            root.addView(nav, navParams)
        }

        val bannerParams = LinearLayout.LayoutParams(-1, -2)
        bannerParams.bottomMargin = dp(16)
        mainContent.addView(notificationBanner, bannerParams)
        val headingParams = LinearLayout.LayoutParams(-1, -2)
        headingParams.bottomMargin = dp(if (tabletLayoutEnabled) 20 else 14)
        mainContent.addView(pageHeading, headingParams)
        mainContent.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val mainContentParams = if (wideLayoutEnabled) {
            LinearLayout.LayoutParams(0, -1, 1f)
        } else {
            LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(mainContent, mainContentParams)

        // CONNECTION CARD

        val connectionCard = createCard()

        addSectionTitle(
            connectionCard,
            "DEVICE CONNECTION",
            "Connect your M30 HE keyboard using USB OTG."
        )

        connectionCard.addView(
            btn("Connect & Read Keyboard") {
                connectClicked()
            }
        )

        status = createText(
            "● Not connected",
            13f,
            secondaryText
        )

        status.setPadding(
            0,
            dp(12),
            0,
            0
        )

        connectionCard.addView(status)

        addCard(pages.getValue("device"), connectionCard)

        // PROFILE CARD

        val profileCard = createCard()

        addSectionTitle(
            profileCard,
            "PROFILES",
            "Choose the profile you want to configure."
        )

        profileCard.addView(
            row(
                btn("PROFILE 0") {
                    loadClicked(0)
                },
                btn("PROFILE 1") {
                    loadClicked(1)
                }
            )
        )
        profileCard.addView(
            row(
                btn("PROFILE 2") {
                    loadClicked(2)
                },
                btn("PROFILE 3") {
                    loadClicked(3)
                }
            )
        )

        val switchButton = btn(
            "Set Current Profile as Active"
        ) {

            val p = profile

            io.execute {
                guard {
                    switchProfile(p)
                }
            }
        }

        profileCard.addView(switchButton)

        addCard(pages.getValue("device"), profileCard)

        // KEY SELECTOR CARD

        val keysCard = createCard()

        addSectionTitle(
            keysCard,
            "KEY SELECTOR",
            "Choose the keys you want to configure."
        )

        selectionCount = createText(
            "0 keys selected",
            12f,
            secondaryText
        )

        keysCard.addView(selectionCount)

        keySearch = EditText(this).apply {
            hint = "Search detected keys"
            setHintTextColor(secondaryText)
            setTextColor(primaryText)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(secondaryCardColor, 10)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderGrid(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        val searchParams = LinearLayout.LayoutParams(-1, dp(48))
        searchParams.topMargin = dp(8)
        searchParams.bottomMargin = dp(4)
        keysCard.addView(keySearch, searchParams)

        keysCard.addView(
            row(
                btn("Select All") {
                    boxes.forEach {
                        it.isChecked = true
                    }
                },
                btn("Clear Selection") {
                    boxes.forEach {
                        it.isChecked = false
                    }
                }
            )
        )

        grid = GridLayout(this)

        grid.columnCount = if (tabletLayoutEnabled) 6 else 4

        grid.setPadding(
            0,
            dp(8),
            0,
            dp(4)
        )

        keysCard.addView(grid)

        addCard(pages.getValue("keys"), keysCard)

        // TRIGGER SETTINGS CARD

        val triggerCard = createCard()

        addSectionTitle(
            triggerCard,
            "ACTUATION & RAPID TRIGGER",
            "Configure actuation and rapid trigger distances."
        )

        actS = Slider(
            "Actuation Point",
            10,
            340,
            200
        )

        rtSw = Switch(this).apply {
            text = "Rapid Trigger"
            setTextColor(primaryText)
            textSize = 14f
        }

        contSw = Switch(this).apply {
            text = "Continuous Rapid Trigger"
            setTextColor(primaryText)
            textSize = 14f
        }

        pressS = Slider(
            "RT Press Distance",
            1,
            220,
            50
        )

        relS = Slider(
            "RT Release Distance",
            1,
            220,
            50
        )

        triggerCard.addView(actS.view)
        triggerCard.addView(rtSw)
        triggerCard.addView(contSw)
        triggerCard.addView(pressS.view)
        triggerCard.addView(relS.view)

        triggerCard.addView(
            btn("Apply Trigger Settings") {
                applyClicked()
            }
        )

        addCard(pages.getValue("keys"), triggerCard)

        // Live visual: Android supplies digital key events; travel is not reported by this protocol.
        val travelCard = createCard()
        addSectionTitle(
            travelCard,
            "LIVE KEY VISUAL",
            "Press a key to see it move. The scale marks your actuation setting, not measured travel."
        )
        travelVisual = KeyTravelView().apply {
            contentDescription = "Key switch illustration with configured actuation point and live key press state"
            setActuation(actS.value / 100f)
        }
        travelCard.addView(travelVisual, LinearLayout.LayoutParams(-1, dp(280)))
        travelCard.addView(createText(
            "Live press state · analog travel is not available from this keyboard connection.",
            if (tabletLayoutEnabled) 13f else 11f,
            secondaryText
        ))
        addCard(pages.getValue("keys"), travelCard, fullWidth = true)

        // REMAPPING CARD

        val remapCard = createCard()

        addSectionTitle(
            remapCard,
            "KEY REMAPPING",
            "Change the assigned function of selected keys."
        )

        targetSpin = Spinner(this)

        targetSpin.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targets.map { it.name }
        )

        remapCard.addView(targetSpin)

        remapCard.addView(
            row(
                btn("Apply Remapping") {
                    remapClicked(false)
                },
                btn("Factory Mapping") {
                    remapClicked(true)
                }
            )
        )

        addCard(pages.getValue("keys"), remapCard, fullWidth = true)

        // BACKUP CARD

        val backupCard = createCard()

        addSectionTitle(
            backupCard,
            "BACKUP & RESTORE",
            "Your original profile is automatically backed up."
        )

        backupCard.addView(
            row(
                btn("Copy Backup") {
                    copyBackup()
                },
                btn("Restore Backup") {
                    restoreClicked()
                }
            )
        )

        addCard(pages.getValue("backup"), backupCard, fullWidth = true)

        // LOG CARD

        val logCard = createCard()

        addSectionTitle(
            logCard,
            "ACTIVITY LOG",
            "Keyboard communication and verification details."
        )

        logView = createText(
            "Waiting for keyboard connection...",
            11f,
            secondaryText
        )

        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)

        logCard.addView(logView)

        logCard.addView(
            btn("Copy Activity Log") {

                val cm = getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

                cm.setPrimaryClip(
                    ClipData.newPlainText(
                        "m30",
                        synchronized(sb) {
                            sb.toString()
                        }
                    )
                )

                showNotification(
                    "Log Copied",
                    "Activity log copied to clipboard."
                )
            }
        )

        addCard(pages.getValue("log"), logCard, fullWidth = true)

        // RGB LIGHTING CARD
        val lightingCard = createCard()
        addSectionTitle(
            lightingCard,
            "KEYBOARD LIGHTING",
            "Set a solid RGB color and brightness for this profile."
        )
        lightColorInput = EditText(this).apply {
            hint = "RRGGBB"
            setHintTextColor(secondaryText)
            setTextColor(primaryText)
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(secondaryCardColor, 10)
            setText("FFFFFF")
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateLightPreview()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        lightingCard.addView(lightColorInput)
        lightPreview = View(this).apply {
            background = roundedBackground(Color.WHITE, 8)
        }
        val previewParams = LinearLayout.LayoutParams(-1, dp(34))
        previewParams.topMargin = dp(10)
        previewParams.bottomMargin = dp(8)
        lightingCard.addView(lightPreview, previewParams)
        lightBrightnessLabel = createText("Brightness: 50%", 14f, primaryText, true)
        lightingCard.addView(lightBrightnessLabel)
        lightBrightness = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    lightBrightnessLabel.text = "Brightness: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        lightingCard.addView(lightBrightness)
        lightStatus = createText("Connect the keyboard to read its lighting settings.", 12f, secondaryText)
        lightStatus.setPadding(0, dp(8), 0, dp(10))
        lightingCard.addView(lightStatus)
        lightingCard.addView(row(
            btn("Read Lighting") { refreshLighting() },
            btn("Apply Color") { applyLightingClicked() }
        ))
        addCard(pages.getValue("lighting"), lightingCard, fullWidth = true)

        pages.values.forEach { it.visibility = View.GONE }
        pages.getValue("device").visibility = View.VISIBLE

        setContentView(root)
        showPage("device")

        // USB RECEIVER

        val filter = IntentFilter(permAction)

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                receiver,
                filter
            )
        }

        requestNotificationPermission()
    }

    override fun onDestroy() {

        super.onDestroy()

        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }

        kb?.close()
        kb = null

        io.shutdownNow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (::travelVisual.isInitialized && event.repeatCount == 0) {
            travelVisual.setKeyEvent(keyNameForEvent(keyCode), true)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (::travelVisual.isInitialized) {
            travelVisual.setKeyEvent(keyNameForEvent(keyCode), false)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun keyNameForEvent(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')

    // ------------------------------------------------------------
    // KEY NAMING
    // ------------------------------------------------------------

    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun hidName(c: Int): String {

        return when (c) {

            in 4..29 ->
                letters[c - 4].toString()

            in 30..38 ->
                (c - 29).toString()

            39 -> "0"

            40 -> "Enter"
            41 -> "Esc"
            42 -> "Bksp"
            43 -> "Tab"
            44 -> "Space"

            45 -> "-"
            46 -> "="
            47 -> "["
            48 -> "]"
            49 -> "\\"
            51 -> ";"
            52 -> "'"
            53 -> "`"
            54 -> ","
            55 -> "."
            56 -> "/"
            57 -> "Caps"

            in 58..69 ->
                "F${c - 57}"

            70 -> "PrtSc"
            71 -> "ScrLk"
            72 -> "Pause"
            73 -> "Ins"
            74 -> "Home"
            75 -> "PgUp"
            76 -> "Del"
            77 -> "End"
            78 -> "PgDn"

            79 -> "Right"
            80 -> "Left"
            81 -> "Down"
            82 -> "Up"

            83 -> "NumLk"
            84 -> "N/"
            85 -> "N*"
            86 -> "N-"
            87 -> "N+"
            88 -> "NEnt"

            in 89..97 ->
                "N${c - 88}"

            98 -> "N0"
            99 -> "N."
            101 -> "Menu"

            in 104..115 ->
                "F${c - 91}"

            else ->
                "0x%02X".format(c)
        }
    }

    private fun modName(m: Int): String {

        val p = ArrayList<String>()

        if (m and 1 != 0) p.add("LCtrl")
        if (m and 2 != 0) p.add("LShift")
        if (m and 4 != 0) p.add("LAlt")
        if (m and 8 != 0) p.add("LWin")

        if (m and 16 != 0) p.add("RCtrl")
        if (m and 32 != 0) p.add("RShift")
        if (m and 64 != 0) p.add("RAlt")
        if (m and 128 != 0) p.add("RWin")

        return if (p.isEmpty()) {
            "-"
        } else {
            p.joinToString("+")
        }
    }

    private fun labelOf(i: Int): String {

        val t = keymap[3 * i].toInt() and 0xFF
        val c1 = keymap[3 * i + 1].toInt() and 0xFF
        val c2 = keymap[3 * i + 2].toInt() and 0xFF

        return when (t) {

            16 -> {

                if (c2 != 0) {
                    hidName(c2) +
                        (if (c1 != 0) "+m" else "")
                } else {
                    modName(c1)
                }
            }

            240 -> {

                if (c1 == 255) {
                    if (c2 == 0) "FN" else "FN$c2"
                } else {
                    "fn%02X.%02X".format(c1, c2)
                }
            }

            48 -> {

                when (c1) {

                    233 -> "Vol+"
                    234 -> "Vol-"
                    226 -> "Mute"
                    205 -> "Play"
                    181 -> "Next"
                    182 -> "Prev"
                    183 -> "Stop"

                    else -> "media%02X".format(c1)
                }
            }

            else -> "T$t"
        }
    }

    private fun buildTargets() {

        for (i in 0 until 26) {
            targets.add(
                Target(
                    letters[i].toString(),
                    16,
                    0,
                    4 + i
                )
            )
        }

        for (i in 1..9) {
            targets.add(
                Target(
                    i.toString(),
                    16,
                    0,
                    29 + i
                )
            )
        }

        targets.add(
            Target("0", 16, 0, 39)
        )

        for (i in 1..12) {
            targets.add(
                Target(
                    "F$i",
                    16,
                    0,
                    57 + i
                )
            )
        }

        for (i in 13..24) {
            targets.add(
                Target(
                    "F$i",
                    16,
                    0,
                    91 + i
                )
            )
        }

        val sp = listOf(

            "Enter" to 40,
            "Esc" to 41,
            "Backspace" to 42,
            "Tab" to 43,
            "Space" to 44,

            "Caps" to 57,
            "Del" to 76,
            "Home" to 74,
            "End" to 77,

            "PgUp" to 75,
            "PgDn" to 78,

            "Up" to 82,
            "Down" to 81,
            "Left" to 80,
            "Right" to 79,

            "PrtSc" to 70,
            "Menu" to 101
        )

        for ((n, c) in sp) {
            targets.add(
                Target(n, 16, 0, c)
            )
        }

        targets.add(Target("LCtrl", 16, 1, 0))
        targets.add(Target("LShift", 16, 2, 0))
        targets.add(Target("LAlt", 16, 4, 0))
        targets.add(Target("LWin", 16, 8, 0))

        targets.add(Target("Vol+", 48, 233, 0))
        targets.add(Target("Vol-", 48, 234, 0))
        targets.add(Target("Mute", 48, 226, 0))
        targets.add(Target("Play/Pause", 48, 205, 0))
        targets.add(Target("Next track", 48, 181, 0))
        targets.add(Target("Prev track", 48, 182, 0))
    }

    // ------------------------------------------------------------
    // CONNECT AND LOAD
    // ------------------------------------------------------------

    private fun connectClicked() {

        synchronized(sb) {
            sb.setLength(0)
        }

        log("USB devices: ${usb.deviceList.size}")

        val dev = usb.deviceList.values.firstOrNull {
            it.vendorId == vendorId
        }

        if (dev == null) {

            log("No device with VID 19F5.")

            showNotification(
                "Keyboard Not Found",
                "No compatible M30 keyboard was detected.",
                "error"
            )

            return
        }

        if (usb.hasPermission(dev)) {

            io.execute {
                guard {
                    connect(dev)
                }
            }

        } else {

            val flags =
                if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val pi = PendingIntent.getBroadcast(
                this,
                0,
                Intent(permAction).setPackage(packageName),
                flags
            )

            usb.requestPermission(dev, pi)
        }
    }

    private fun connect(dev: UsbDevice) {

        kb?.close()
        kb = null

        log(
            "Found VID=%04X PID=%04X".format(
                dev.vendorId,
                dev.productId
            )
        )

        val conn = usb.openDevice(dev)

        if (conn == null) {

            log("openDevice failed")

            showNotification(
                "USB Connection Failed",
                "Android could not open the keyboard.",
                "error"
            )

            return
        }

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

                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        i = ep
                    } else {
                        o = ep
                    }
                }
            }

            val ok =
                itf.interfaceClass == 3 &&
                    i != null &&
                    i.maxPacketSize == 64

            if (ok && (chosen == null || itf.id == 1)) {

                chosen = itf
                epIn = i
                epOut = o
            }
        }

        if (chosen == null || epIn == null) {

            log("No HID interface with 64-byte endpoint.")

            showNotification(
                "Unsupported USB Interface",
                "No compatible 64-byte HID endpoint was found.",
                "error"
            )

            conn.close()
            return
        }

        if (!conn.claimInterface(chosen, true)) {

            log("claimInterface failed")

            showNotification(
                "USB Interface Failed",
                "Could not claim the keyboard interface.",
                "error"
            )

            conn.close()
            return
        }

        val k = Kb(
            conn,
            chosen,
            epIn,
            epOut
        )

        kb = k

        val info = k.simple(0x03)

        if (info != null) {
            log(
                "Firmware %02X%02X".format(
                    info[1],
                    info[0]
                )
            )
        }

        if (loadProfile(-1)) {

            showNotification(
                "Keyboard Connected",
                "M30 keyboard successfully connected and read."
            )
        }
    }

    private fun loadClicked(p: Int) {

        profile = p

        io.execute {
            guard {
                if (loadProfile(p)) {
                    showNotification(
                        "Profile Loaded",
                        "Profile $p is ready for configuration."
                    )
                }
            }
        }
    }

    private fun updateLightPreview() {
        if (!::lightPreview.isInitialized || !::lightColorInput.isInitialized) return
        val rgb = lightColorInput.text.toString().trim().removePrefix("#")
        if (!rgb.matches(Regex("[0-9a-fA-F]{6}"))) return
        val red = rgb.substring(0, 2).toInt(16)
        val green = rgb.substring(2, 4).toInt(16)
        val blue = rgb.substring(4, 6).toInt(16)
        lightPreview.background = roundedBackground(Color.rgb(red, green, blue), 8)
    }

    private fun refreshLighting() {
        val p = profile
        if (kb == null) {
            lightStatus.text = "Connect the keyboard to read its lighting settings."
            return
        }
        lightStatus.text = "Reading profile $p lighting settings..."
        io.execute {
            guard {
                val config = kb?.read(0x05, 64 * p, 64)
                if (config == null || config.size != 64) {
                    log("Lighting read failed for profile $p.")
                    runOnUiThread { lightStatus.text = "Could not read lighting settings for profile $p." }
                    return@guard
                }
                val color = "%02X%02X%02X".format(
                    Locale.US,
                    config[14].toInt() and 0xFF,
                    config[15].toInt() and 0xFF,
                    config[16].toInt() and 0xFF
                )
                val brightness = (config[9].toInt() and 0xFF).coerceIn(0, 100)
                runOnUiThread {
                    lightColorInput.setText(color)
                    lightBrightness.progress = brightness
                    lightBrightnessLabel.text = "Brightness: $brightness%"
                    lightStatus.text = "Profile $p loaded. Apply Color enables lighting effect 1."
                    updateLightPreview()
                }
            }
        }
    }

    private fun applyLightingClicked() {
        val color = lightColorInput.text.toString().trim()
        if (!color.removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))) {
            showNotification("Invalid RGB Color", "Enter six hexadecimal digits, such as 40A8FF.", "warning")
            return
        }
        val brightness = lightBrightness.progress
        val p = profile
        lightStatus.text = "Applying profile $p lighting settings..."
        io.execute {
            guard {
                val k = kb
                if (k == null) {
                    runOnUiThread { lightStatus.text = "Connect the keyboard before applying lighting." }
                    showNotification("Keyboard Not Connected", "Connect the keyboard before applying lighting.", "warning")
                    return@guard
                }
                val current = k.read(0x05, 64 * p, 64)
                if (current == null || current.size != 64) {
                    log("Lighting read failed; nothing was written for profile $p.")
                    runOnUiThread { lightStatus.text = "Could not read profile $p; nothing was changed." }
                    showNotification("Lighting Read Failed", "Could not load profile $p before writing.", "error")
                    return@guard
                }
                val updated = configureSingleColor(current, color, brightness)
                if (!k.write(0x06, 64 * p, updated)) {
                    log("Lighting write failed for profile $p.")
                    runOnUiThread { lightStatus.text = "The keyboard did not accept the lighting settings." }
                    showNotification("Lighting Write Failed", "The keyboard did not accept the lighting settings.", "error")
                    return@guard
                }
                val verified = k.read(0x05, 64 * p, 64)
                val matches = verified != null && verified.size == 64 &&
                    listOf(9, 12, 14, 15, 16).all { verified[it] == updated[it] }
                if (!matches) {
                    log("Lighting settings were written, but read-back did not match for profile $p.")
                    runOnUiThread { lightStatus.text = "Settings were written, but verification failed." }
                    showNotification("Lighting Verification Failed", "The keyboard's read-back did not match the requested color.", "warning")
                    return@guard
                }
                log("Verified profile $p RGB color #${color.removePrefix("#").uppercase(Locale.US)} at $brightness% brightness.")
                runOnUiThread {
                    lightStatus.text = "Profile $p lighting settings verified."
                    showNotification("Lighting Updated", "RGB color and brightness were verified on profile $p.")
                }
            }
        }
    }

    private fun loadProfile(p0: Int): Boolean {

        val k = kb

        if (k == null) {
            log("Connect first.")
            return false
        }

        val base = k.simple(0x04)

        if (base != null) {
            activeProfile = base[0].toInt() and 0xFF
        }

        var p = p0

        if (p < 0) {

            p = if (activeProfile in 0..3) {
                activeProfile
            } else {
                0
            }

            profile = p
        }

        val t = k.read(
            0xA0,
            1024 * p,
            1024
        ) ?: return false

        val km = k.read(
            0x08,
            2048 * p,
            512
        ) ?: return false

        val kd = k.read(
            0x07,
            2048 * p,
            512
        ) ?: return false

        trig = t
        keymap = km
        keymapDef = kd

        saveOriginalBackup(p)

        keyIdx.clear()

        for (i in 0 until 128) {

            val ty = keymap[3 * i].toInt() and 0xFF

            if (ty != 0 && ty != 255) {
                keyIdx.add(i)
            }
        }

        log(
            "Loaded profile $p " +
                "(keyboard's active profile: $activeProfile), " +
                "${keyIdx.size} keys"
        )

        runOnUiThread {

            rebuildGrid()

            status.text =
                "● Connected  |  Editing P$p  |  Active P$activeProfile"

            status.setTextColor(
                Color.rgb(83, 211, 160)
            )
        }

        return true
    }

    private fun switchProfile(p: Int) {

        val k = kb

        if (k == null) {
            log("Connect first.")
            return
        }

        log("Switching keyboard to profile $p ...")

        k.sendProfileSwitch(p)

        Thread.sleep(1200)

        if (loadProfile(p)) {

            showNotification(
                "Profile Switched",
                "Keyboard is now using profile $p."
            )
        }
    }

    // ------------------------------------------------------------
    // KEY SELECTION
    // ------------------------------------------------------------

    private fun rebuildGrid() {

        grid.removeAllViews()
        boxes.clear()

        for (i in keyIdx) {

            val cb = CheckBox(this)

            cb.text = labelOf(i) + "\n#" + i
            cb.textSize = if (tabletLayoutEnabled) 13f else 11f

            cb.setTextColor(primaryText)

            cb.buttonTintList =
                android.content.res.ColorStateList.valueOf(accentColor)

            cb.tag = i

            cb.setPadding(
                dp(if (tabletLayoutEnabled) 8 else 4),
                dp(if (tabletLayoutEnabled) 9 else 5),
                dp(if (tabletLayoutEnabled) 8 else 4),
                dp(if (tabletLayoutEnabled) 9 else 5)
            )

            cb.setBackground(
                roundedBackground(
                    secondaryCardColor,
                    if (tabletLayoutEnabled) 10 else 8
                )
            )

            cb.setOnCheckedChangeListener { _, _ ->
                onSelection()
            }

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )

            lp.width = 0

            lp.setMargins(
                dp(2),
                dp(2),
                dp(2),
                dp(2)
            )

            boxes.add(cb)
            grid.addView(cb, lp)
        }

        renderGrid(keySearch.text?.toString().orEmpty())
        updateSelectionCount()
    }

    private fun renderGrid(query: String) {
        if (!::grid.isInitialized) return
        val normalized = query.trim()
        boxes.forEach { box ->
            box.visibility = if (
                normalized.isEmpty() ||
                box.text.toString().contains(normalized, ignoreCase = true)
            ) View.VISIBLE else View.GONE
        }
    }

    private fun selected(): List<Int> {

        return boxes
            .filter { it.isChecked }
            .map { it.tag as Int }
    }

    private fun updateSelectionCount() {

        if (::selectionCount.isInitialized) {

            val count = selected().size

            selectionCount.text =
                "$count keys selected"
        }
    }

    private fun onSelection() {

        updateSelectionCount()

        val sel = selected()

        if (sel.size != 1) return

        val i = sel[0]

        val d = IntArray(8) {
            trig[i * 8 + it].toInt() and 0xFF
        }

        actS.setTo(
            (((d[3] and 1) shl 8) or d[2]) + 1
        )

        pressS.setTo(
            (((d[5] and 1) shl 8) or d[4]) + 1
        )

        relS.setTo(
            (((d[7] and 1) shl 8) or d[6]) + 1
        )

        val mode = d[1] and 0x0F

        rtSw.isChecked = mode > 0
        contSw.isChecked = mode >= 2
    }

    // ------------------------------------------------------------
    // APPLY TRIGGER SETTINGS
    // ------------------------------------------------------------

    private fun setLE9(
        e: ByteArray,
        off: Int,
        raw: Int
    ) {

        e[off] = (raw and 0xFF).toByte()

        e[off + 1] = (
            (e[off + 1].toInt() and 0xFE) or
                ((raw shr 8) and 1)
            ).toByte()
    }

    private fun applyClicked() {

        val sel = selected()

        if (sel.isEmpty()) {

            showNotification(
                "No Keys Selected",
                "Select at least one key first.",
                "warning"
            )

            return
        }

        val act = actS.value
        val rt = rtSw.isChecked
        val cont = contSw.isChecked
        val pr = pressS.value
        val rl = relS.value

        val p = profile

        io.execute {

            guard {
                applyTriggers(
                    p,
                    sel,
                    act,
                    rt,
                    cont,
                    pr,
                    rl
                )
            }
        }
    }

    private fun applyTriggers(
        p: Int,
        sel: List<Int>,
        act: Int,
        rt: Boolean,
        cont: Boolean,
        pr: Int,
        rl: Int
    ) {

        val k = kb

        if (k == null) {
            log("Connect first.")
            return
        }

        val wanted = HashMap<Int, ByteArray>()

        for (i in sel) {

            val e = trig.copyOfRange(
                i * 8,
                i * 8 + 8
            )

            val mode = if (rt) {
                if (cont) 2 else 1
            } else {
                0
            }

            e[1] = (
                (e[1].toInt() and 0xF0) or mode
                ).toByte()

            setLE9(e, 2, act - 1)

            if (rt) {

                setLE9(e, 4, pr - 1)
                setLE9(e, 6, rl - 1)
            }

            if (!k.write(
                    0xA1,
                    1024 * p + 8 * i,
                    e
                )
            ) {

                log("Write failed at key #$i, stopping.")

                showNotification(
                    "Trigger Write Failed",
                    "Failed while writing key #$i.",
                    "error"
                )

                return
            }

            wanted[i] = e
        }

        val t2 = k.read(
            0xA0,
            1024 * p,
            1024
        )

        if (t2 == null) {

            log("Written, but read-back failed.")

            showNotification(
                "Verification Failed",
                "Settings were written, but read-back failed.",
                "warning"
            )

            return
        }

        var ok = 0

        for (i in sel) {

            if (
                t2.copyOfRange(
                    i * 8,
                    i * 8 + 8
                ).contentEquals(wanted[i]!!)
            ) {

                ok++

            } else {

                log(
                    "MISMATCH key #$i wanted " +
                        "${hex(wanted[i]!!)} got " +
                        "${hex(t2.copyOfRange(i * 8, i * 8 + 8))}"
                )
            }
        }

        trig = t2

        log(
            "Trigger settings: $ok/${sel.size} " +
                "keys verified by read-back."
        )

        if (ok == sel.size) {

            showNotification(
                "Settings Applied",
                "Rapid Trigger settings verified on all $ok selected keys."
            )

        } else {

            showNotification(
                "Verification Warning",
                "Only $ok of ${sel.size} keys were verified.",
                "warning"
            )
        }
    }

    // ------------------------------------------------------------
    // KEY REMAPPING
    // ------------------------------------------------------------

    private fun remapClicked(factory: Boolean) {

        val sel = selected()

        if (sel.isEmpty()) {

            showNotification(
                "No Keys Selected",
                "Select at least one key first.",
                "warning"
            )

            return
        }

        val tg = targets[
            targetSpin.selectedItemPosition
        ]

        val p = profile

        io.execute {

            guard {
                remap(
                    p,
                    sel,
                    tg,
                    factory
                )
            }
        }
    }

    private fun remap(
        p: Int,
        sel: List<Int>,
        tg: Target,
        factory: Boolean
    ) {

        val k = kb

        if (k == null) {
            log("Connect first.")
            return
        }

        for (i in sel) {

            val d = if (factory) {

                keymapDef.copyOfRange(
                    3 * i,
                    3 * i + 3
                )

            } else {

                byteArrayOf(
                    tg.t.toByte(),
                    tg.c1.toByte(),
                    tg.c2.toByte()
                )
            }

            if (!k.write(
                    0x09,
                    2048 * p + 3 * i,
                    d
                )
            ) {

                log("Write failed at key #$i, stopping.")

                showNotification(
                    "Remapping Failed",
                    "Could not remap key #$i.",
                    "error"
                )

                return
            }
        }

        val km = k.read(
            0x08,
            2048 * p,
            512
        )

        if (km == null) {

            log("Written, but read-back failed.")

            showNotification(
                "Remapping Verification Failed",
                "Mappings were written, but read-back failed.",
                "warning"
            )

            return
        }

        var ok = 0

        for (i in sel) {

            val want = if (factory) {

                keymapDef.copyOfRange(
                    3 * i,
                    3 * i + 3
                )

            } else {

                byteArrayOf(
                    tg.t.toByte(),
                    tg.c1.toByte(),
                    tg.c2.toByte()
                )
            }

            if (
                km.copyOfRange(
                    3 * i,
                    3 * i + 3
                ).contentEquals(want)
            ) {

                ok++

            } else {

                log("MISMATCH key #$i")
            }
        }

        keymap = km

        log(
            "Remap: $ok/${sel.size} keys verified." +
                if (factory) {
                    " (factory mapping)"
                } else {
                    " -> ${tg.name}"
                }
        )

        if (ok == sel.size) {

            showNotification(
                "Remapping Complete",
                "$ok selected keys successfully verified."
            )

        } else {

            showNotification(
                "Remapping Warning",
                "Only $ok of ${sel.size} keys verified.",
                "warning"
            )
        }

        runOnUiThread {

            val checked = selected()

            rebuildGrid()

            for (b in boxes) {

                if ((b.tag as Int) in checked) {
                    b.isChecked = true
                }
            }
        }
    }

    // ------------------------------------------------------------
    // BACKUP AND RESTORE
    // ------------------------------------------------------------

    private fun hex(b: ByteArray): String {

        return b.joinToString("") {
            "%02X".format(it)
        }
    }

    private fun unhex(s: String): ByteArray {

        return ByteArray(s.length / 2) {
            s.substring(
                2 * it,
                2 * it + 2
            ).toInt(16).toByte()
        }
    }

    private fun backupFile(p: Int): File {

        return File(
            filesDir,
            "backup_p$p.txt"
        )
    }

    private fun saveOriginalBackup(p: Int) {

        val f = backupFile(p)

        if (f.exists()) return

        f.writeText(
            "T " + hex(trig) +
                "\nK " + hex(keymap) +
                "\n"
        )

        log("Saved original backup for profile $p")
    }

    private fun copyBackup() {

        val f = backupFile(profile)

        if (!f.exists()) {

            showNotification(
                "Backup Not Found",
                "No backup exists for profile $profile.",
                "warning"
            )

            return
        }

        val cm = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        cm.setPrimaryClip(
            ClipData.newPlainText(
                "m30backup",
                "profile ${profile}\n" + f.readText()
            )
        )

        log("Backup for profile $profile copied to clipboard.")

        showNotification(
            "Backup Copied",
            "Profile $profile backup copied to clipboard."
        )
    }

    private fun restoreClicked() {

        val p = profile

        if (!backupFile(p).exists()) {

            showNotification(
                "Backup Not Found",
                "No backup exists for profile $p.",
                "warning"
            )

            return
        }

        AlertDialog.Builder(this)
            .setTitle("Restore Profile")
            .setMessage(
                "Overwrite trigger settings and layer-0 key mapping " +
                    "of profile $p with the saved original?"
            )
            .setPositiveButton("Restore") { _, _ ->

                io.execute {

                    guard {
                        restore(p)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restore(p: Int) {

        val k = kb

        if (k == null) {
            log("Connect first.")
            return
        }

        var t: ByteArray? = null
        var km: ByteArray? = null

        for (line in backupFile(p).readLines()) {

            if (line.startsWith("T ")) {
                t = unhex(line.substring(2).trim())
            }

            if (line.startsWith("K ")) {
                km = unhex(line.substring(2).trim())
            }
        }

        if (
            t == null ||
            t.size != 1024 ||
            km == null ||
            km.size < 384
        ) {

            log("Backup file is damaged.")

            showNotification(
                "Backup Damaged",
                "The saved backup file is invalid.",
                "error"
            )

            return
        }

        if (!k.write(
                0xA1,
                1024 * p,
                t
            )
        ) {

            log("Restore failed (trigger).")

            showNotification(
                "Restore Failed",
                "Could not restore trigger settings.",
                "error"
            )

            return
        }

        if (!k.write(
                0x09,
                2048 * p,
                km.copyOf(384)
            )
        ) {

            log("Restore failed (keymap).")

            showNotification(
                "Restore Failed",
                "Could not restore key mappings.",
                "error"
            )

            return
        }

        log("Backup written, reloading ...")

        if (loadProfile(p)) {

            showNotification(
                "Backup Restored",
                "Profile $p trigger settings and layer-0 mappings restored."
            )
        }
    }
}
