package dev.gaboron.spwmedia.worker

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference

/** libmpv client ABI. These types are never exposed to a consumer plugin. */
internal interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
    fun mpv_command(handle: Pointer, arguments: Pointer): Int
    fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer
    fun mpv_free(pointer: Pointer)
    fun mpv_terminate_destroy(handle: Pointer)
    fun mpv_render_context_create(context: PointerByReference, handle: Pointer, parameters: Pointer): Int
    fun mpv_render_context_render(context: Pointer, parameters: Pointer): Int
    fun mpv_render_context_free(context: Pointer)
}
@Structure.FieldOrder("type", "data")
internal class RenderParameter : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}
