package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*
import java.util.concurrent.CompletionStage

/** Windows-only browser sessions. No website-specific logic is included. */
class WebEngine internal constructor(private val client: MediaRuntime) {
    /** Opens a browser using the owning plugin's persistent profile. Empty allowedOrigins disables page-to-plugin messaging. */
    fun open(options: WebOptions = WebOptions()): RuntimeTask<Browser> = client.task("web", "open", Protocol.json.encodeToJsonElement(options).jsonObject) { Browser(it.jsonObject.text("id"), client) }
}
/** WebView2 session; closing the view retains cookies and storage. */
class Browser internal constructor(id: String, client: MediaRuntime) : MediaSession(id, "web", client) {
    /** Navigate the existing view. */
    fun navigate(url: String) = client.sessionTask(capability, id, "navigate", obj("url" to url))
    /** Execute trusted script on the browser STA thread; returns WebView2's JSON result. */
    fun evaluate(script: String): CompletionStage<JsonElement> = client.sessionTask(capability, id, "evaluate", obj("script" to script))
    /** Send JSON data via chrome.webview's message event without script interpolation. */
    fun postMessage(message: JsonElement) = client.sessionTask(capability, id, "postMessage", obj("message" to message))
    /** Resize in device pixels; embedding parent remains caller-owned. */
    fun resize(width: Int, height: Int): CompletionStage<JsonElement> { require(width > 0 && height > 0); return client.sessionTask(capability, id, "resize", obj("width" to width, "height" to height)) }
    /** Retrieve cookies for a URI from this plugin's profile. Sensitive values must not be logged. */
    fun cookies(uri: String = ""): CompletionStage<List<WebCookie>> = client.sessionTask(capability, id, "cookies", obj("uri" to uri)).thenApply { Protocol.json.decodeFromJsonElement(it) }
    /** Add or replace a cookie in this plugin's profile. */
    fun setCookie(cookie: WebCookie) = client.sessionTask(capability, id, "setCookie", Protocol.json.encodeToJsonElement(cookie).jsonObject)
    /** Explicitly clear all browsing data for this profile, including cookies and local storage. */
    fun clearData() = client.sessionTask(capability, id, "clearData")
    /** Drain origin-checked messages from the bounded backend queue. */
    fun messages(): CompletionStage<JsonElement> = client.sessionTask(capability, id, "messages")
}
