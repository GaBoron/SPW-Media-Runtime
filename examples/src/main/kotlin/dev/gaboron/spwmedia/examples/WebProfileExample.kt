package dev.gaboron.spwmedia.examples

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.sdk.*
import kotlinx.serialization.json.*
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletionException

/** User-requested browser flows. The caller owns each returned browser and closes it when its view closes. */
class WebProfileExample(private val media: MediaRuntime) {
    /** Install a caller-provided cookie before navigation. All state remains scoped to this plugin/profile. */
    fun openAuthenticated(url: String, allowedOrigin: String, cookie: WebCookie): CompletionStage<Browser> =
        media.web.open(WebOptions(profile = "account", allowedOrigins = listOf(allowedOrigin))).result.thenCompose { browser ->
            browser.setCookie(cookie).thenCompose { browser.navigate(url) }.handle { _, failure ->
                if (failure != null) { runCatching { browser.close() }; throw CompletionException(failure) }
                browser
            }
        }

    /** Fixed trusted script; results belong to the caller's UI, not ordinary logs. */
    fun documentTitle(browser: Browser): CompletionStage<String?> = browser.evaluate("document.title").thenApply { value ->
        (value as? JsonPrimitive)?.contentOrNull
    }

    /** JSON transport avoids interpolating a media title into executable JavaScript. */
    fun notifyTitle(browser: Browser, title: String): CompletionStage<JsonElement> =
        browser.postMessage(obj("type" to "media-title", "title" to title))

    /** Drain messages accepted by allowedOrigins. The page may produce replies on a later poll. */
    fun messages(browser: Browser): CompletionStage<JsonElement> = browser.messages()
}
