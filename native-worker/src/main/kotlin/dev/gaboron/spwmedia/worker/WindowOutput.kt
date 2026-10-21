package dev.gaboron.spwmedia.worker

import java.awt.Graphics
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** LGPL-compatible window output using the JRE's desktop backend and libmpv software frames. */
internal class WindowOutput(width: Int, height: Int, private val parentWindow: Long = 0) : AutoCloseable {
    private val latest = AtomicReference<BufferedImage?>()
    private lateinit var window: JFrame
    private lateinit var panel: JPanel
    init {
        SwingUtilities.invokeAndWait {
            panel = object : JPanel() {
                override fun paintComponent(graphics: Graphics) { super.paintComponent(graphics); latest.get()?.let { graphics.drawImage(it, 0, 0, getWidth(), getHeight(), null) } }
            }
            window = JFrame("SPW Media").apply { defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE; isUndecorated = parentWindow != 0L; contentPane = panel; setSize(width, height); isVisible = true }
            if (parentWindow != 0L) {
                val api = Native.load("user32", WindowApi::class.java)
                val child = Native.getComponentPointer(window)
                val parent = Pointer(parentWindow)
                require(api.IsWindow(parent)) { "Parent window is not valid" }
                val style = api.GetWindowLongPtrW(child, -16)
                api.SetWindowLongPtrW(child, -16, (style and 0x80cf0000L.inv()) or 0x40000000L)
                Native.setLastError(0)
                val previous = api.SetParent(child, parent)
                check(previous != null || Native.getLastError() == 0) { "Cannot attach player to parent window" }
                api.SetWindowPos(child, null, 0, 0, width, height, 0x0020 or 0x0004 or 0x0010)
            }
        }
    }
    fun resize(width: Int, height: Int) { require(width > 0 && height > 0); SwingUtilities.invokeLater { window.setSize(width, height) } }
    fun present(bytes: ByteArray, width: Int, height: Int) {
        val frame = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val colors = IntArray(width * height) { i -> val p = i * 4; ((bytes[p + 2].toInt() and 255) shl 16) or ((bytes[p + 1].toInt() and 255) shl 8) or (bytes[p].toInt() and 255) }
        frame.setRGB(0, 0, width, height, colors, 0, width)
        latest.set(frame); panel.repaint()
    }
    override fun close() { SwingUtilities.invokeLater { window.dispose() }; latest.set(null) }
}

/** Windows x64 borrowed-window interop stays inside the isolated worker. */
private interface WindowApi : Library {
    fun IsWindow(window: Pointer): Boolean
    fun GetWindowLongPtrW(window: Pointer, index: Int): Long
    fun SetWindowLongPtrW(window: Pointer, index: Int, value: Long): Long
    fun SetParent(child: Pointer, parent: Pointer): Pointer?
    fun SetWindowPos(window: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
}
