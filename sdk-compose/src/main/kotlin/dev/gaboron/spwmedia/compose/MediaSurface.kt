package dev.gaboron.spwmedia.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import dev.gaboron.spwmedia.sdk.SharedBuffer
import kotlinx.coroutines.delay
import org.jetbrains.skia.*

/**
 * Displays a BGRA/RGBA shared frame using the host's Compose/Skia implementation.
 * The caller owns [buffer]; dispose this composable before closing it or the producer session.
 * Frames use CPU upload; no zero-copy/GPU interoperability guarantee is made.
 */
@Composable
fun MediaSurface(buffer: SharedBuffer, modifier: Modifier = Modifier) {
    var image by remember(buffer) { mutableStateOf<Image?>(null) }
    var sequence by remember(buffer) { mutableLongStateOf(-1) }
    val drawLock = remember(buffer) { Any() }
    DisposableEffect(buffer) { onDispose { synchronized(drawLock) { image?.close(); image = null } } }
    LaunchedEffect(buffer) {
        while (true) {
            val frame = buffer.latest()
            if (frame != null && frame.sequence != sequence && frame.width > 0 && frame.height > 0) {
                val color = if (buffer.descriptor.format == "RGBA") ColorType.RGBA_8888 else ColorType.BGRA_8888
                val next = Image.makeRaster(ImageInfo(frame.width, frame.height, color, ColorAlphaType.OPAQUE), frame.bytes, frame.width * 4)
                synchronized(drawLock) { val previous = image; image = next; sequence = frame.sequence; previous?.close() }
            }
            delay(33)
        }
    }
    Canvas(modifier) {
        synchronized(drawLock) {
            image?.let { frame -> drawIntoCanvas { canvas ->
                canvas.skiaCanvas.drawImageRect(frame, Rect.makeWH(size.width, size.height))
            } }
        }
    }
}
