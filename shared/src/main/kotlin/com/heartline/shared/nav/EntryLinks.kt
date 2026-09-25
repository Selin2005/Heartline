package com.heartline.shared.nav

/** Where a screen was opened from outside the app (a widget, a tile, a notification…). */
enum class EntrySource(val tag: String) {
    WIDGET("widget"),
    QUICK_SETTINGS("qs"),
    NOTIFICATION("notif"),
    TILE("tile"),
    COMPLICATION("complication");

    companion object {
        fun of(tag: String?): EntrySource? = entries.firstOrNull { it.tag == tag }
    }
}

/** A route to open, and where the request came from (null = from inside Heartline, e.g. the other device). */
data class EntryLink(val route: String, val source: EntrySource?) {
    /**
     * Opened from a home-screen or watch-face surface: the screen is the only one on the back
     * stack, so Back returns to where the user came from instead of the app's home.
     */
    val external: Boolean get() = source != null
}

object EntryLinks {
    const val PARAM = "src"

    /** [route] marked as opened from [source] (added as a `src` query parameter). */
    fun tag(route: String, source: EntrySource): String = route + (if ('?' in route) "&" else "?") + "$PARAM=${source.tag}"

    /** Splits a route (with an optional query) into the plain route and its [EntrySource]. */
    fun parse(link: String): EntryLink {
        val q = link.indexOf('?')
        if (q < 0) return EntryLink(link, null)
        val params = link.substring(q + 1).split('&').filter { it.isNotEmpty() }
        val source = params.firstOrNull { it.startsWith("$PARAM=") }?.substringAfter('=')?.let(EntrySource::of)
        val rest = params.filterNot { it.startsWith("$PARAM=") }
        val path = link.substring(0, q)
        return EntryLink(if (rest.isEmpty()) path else path + "?" + rest.joinToString("&"), source)
    }
}
