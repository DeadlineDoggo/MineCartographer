package minecartographer.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * An eyedropper for anything on the screen, other applications included: the screen is captured once, shown full-screen
 * as a picture with a magnifier at the pointer and the color under it, and a click takes that color (Esc or a right
 * click cancels). Reading the screen can be blocked by the system (Wayland without a screen-capture permission): then
 * the capture comes out empty and [onFailed] says so.
 */
object ScreenPicker {
    private const val LOUPE_CELLS = 11
    private const val LOUPE_CELL = 12

    /**
     * Captures the screen in the background and then shows the picker. On Wayland the first capture asks for permission
     * in a system dialog (KDE: "allow screen capture"); Java waits for the answer up to its screenshot timeout, which is
     * raised here so a person has time to read and click. [onWaiting] tells them while that dialog is up. The permission
     * is remembered by Java, so later picks don't ask again.
     */
    fun pick(onPicked: (Int) -> Unit, onFailed: (String) -> Unit, onWaiting: (String) -> Unit = {}) {
        // Read by Java's Wayland screen capture when it starts; harmless elsewhere.
        if (System.getProperty("awt.robot.screenshotTimeout") == null) System.setProperty("awt.robot.screenshotTimeout", "60000")
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }.reduceOrNull { a, b -> a.union(b) }
            ?: return onFailed("No screen found.")
        thread(isDaemon = true, name = "screen picker") {
            val waiting = thread(isDaemon = true, name = "screen picker notice") {
                runCatching { Thread.sleep(700) }.onSuccess {
                    SwingUtilities.invokeLater { onWaiting("Allow the screen capture in the system dialog; the eyedropper opens when it is allowed.") }
                }
            }
            fun capture() = runCatching { Robot().createScreenCapture(bounds) }
            // A second try after the first one came back empty: the first may have run out while the dialog was open.
            var shot = capture()
            if (shot.getOrNull()?.let(::looksBlocked) != false) {
                Thread.sleep(400)
                shot = capture()
            }
            waiting.interrupt()
            val image = shot.getOrNull()
            SwingUtilities.invokeLater {
                when {
                    image == null -> onFailed("Could not read the screen: ${shot.exceptionOrNull()?.message}")
                    looksBlocked(image) -> onFailed("The system gave no picture of the screen (the capture was not allowed). Try again and allow it, or type the hex value.")
                    else -> show(bounds, image, onPicked)
                }
            }
        }
    }

    /** A capture the system refused is one flat color. */
    private fun looksBlocked(image: BufferedImage): Boolean {
        val first = image.getRGB(0, 0)
        for (i in 1..40) if (image.getRGB(i * (image.width - 1) / 40, i * (image.height - 1) / 40) != first) return false
        return true
    }

    private fun show(bounds: Rectangle, shot: BufferedImage, onPicked: (Int) -> Unit) {
        val dialog = JDialog().apply {
            isUndecorated = true
            isAlwaysOnTop = true
            this.bounds = bounds
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        }
        var at = MouseInfo.getPointerInfo()?.location?.let { Point(it.x - bounds.x, it.y - bounds.y) } ?: Point(bounds.width / 2, bounds.height / 2)
        fun colorAt(p: Point) = if (p.x in 0 until shot.width && p.y in 0 until shot.height) shot.getRGB(p.x, p.y) and 0xFFFFFF else null

        val panel = object : JPanel() {
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.drawImage(shot, 0, 0, null)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val size = LOUPE_CELLS * LOUPE_CELL
                // Beside the pointer, on the side that has room.
                val x = if (at.x + 28 + size > width) at.x - 28 - size else at.x + 28
                val y = if (at.y + 28 + size + 28 > height) at.y - 28 - size - 28 else at.y + 28
                val half = LOUPE_CELLS / 2
                for (dy in 0 until LOUPE_CELLS) for (dx in 0 until LOUPE_CELLS) {
                    val c = colorAt(Point(at.x - half + dx, at.y - half + dy)) ?: 0x202020
                    g2.color = Color(c)
                    g2.fillRect(x + dx * LOUPE_CELL, y + dy * LOUPE_CELL, LOUPE_CELL, LOUPE_CELL)
                }
                g2.color = Color(0xE0000000.toInt(), true)
                g2.stroke = BasicStroke(3f)
                g2.drawRect(x, y, size, size)
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(1.5f)
                g2.drawRect(x + half * LOUPE_CELL, y + half * LOUPE_CELL, LOUPE_CELL, LOUPE_CELL)
                // The color under the pointer: a swatch and its hex.
                val rgb = colorAt(at) ?: 0
                g2.color = Color(0xEE1E1F22.toInt(), true)
                g2.fillRoundRect(x, y + size + 6, size, 22, 8, 8)
                g2.color = Color(rgb)
                g2.fillRect(x + 5, y + size + 10, 14, 14)
                g2.color = Color.WHITE
                g2.font = Font(Font.MONOSPACED, Font.BOLD, 12)
                g2.drawString("#%06X   Esc cancels".format(rgb), x + 26, y + size + 22)
            }
        }
        panel.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) { at = e.point; panel.repaint() }
            override fun mouseDragged(e: MouseEvent) { at = e.point; panel.repaint() }
        })
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                dialog.dispose()
                if (e.button == MouseEvent.BUTTON1) colorAt(e.point)?.let(onPicked)
            }
        })
        dialog.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ESCAPE) dialog.dispose() }
        })
        dialog.contentPane = panel
        dialog.isVisible = true
        dialog.requestFocus()
    }
}
