package dev.gaboron.spwmedia.examples

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import dev.gaboron.spwmedia.compose.MediaSurface
import dev.gaboron.spwmedia.sdk.SharedBuffer
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** Explicit preview only. Dispose Compose before unmapping the consumer buffer. */
class ExamplePreview(private val buffer: SharedBuffer, private val onClosed: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var window: ComposeWindow? = null
    fun show() = SwingUtilities.invokeLater {
        if (closed.get()) return@invokeLater
        window = ComposeWindow().apply {
            title = "SPW Media Example · 视频预览"
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            setSize(640, 360)
            setContent { MediaSurface(buffer, Modifier.fillMaxSize()) }
            addWindowListener(object : WindowAdapter() { override fun windowClosing(event: WindowEvent) { close() } })
            isVisible = true
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val dispose = { window?.dispose(); window = null; buffer.close(); onClosed() }
        if (SwingUtilities.isEventDispatchThread()) dispose() else SwingUtilities.invokeAndWait(dispose)
    }
}
