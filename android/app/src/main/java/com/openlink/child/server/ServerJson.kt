package com.openlink.child.server

import kotlinx.serialization.json.Json

/**
 * One JSON configuration for the whole listener: content negotiation, WebSocket frames, and the
 * hand-rolled JsonObject parsing in `PUT /policies/{packageName}`.
 *
 * `ignoreUnknownKeys` keeps a newer parent app from breaking an older child; `encodeDefaults`
 * means a field the parent expects is always present rather than silently omitted because it
 * happened to equal its default.
 */
val apiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
}
