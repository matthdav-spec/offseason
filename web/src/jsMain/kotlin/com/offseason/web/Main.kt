package com.offseason.web

import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable

fun main() {
    // Surface any error while drawing, so problems show up in the browser console instead of a frozen screen.
    window.onerror = { msg, _, _, _, err -> console.error("Off-Season error: $msg", err); false }
    renderComposable(rootElementId = "root") { App() }
}
