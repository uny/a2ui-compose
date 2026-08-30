package dev.ynagai.a2ui.material3

/**
 * The catalog's icon names, as SVG path data on the 24x24 grid.
 *
 * **Drawn here rather than taken from an icon library, and that is a dependency decision.** The
 * catalog names fifty-nine icons. Compose's bundled `material-icons-core` carries only some of
 * them -- `fastForward`, `rewind`, `skipNext`, `print`, `payment` and `camera` are among the ones
 * it does not -- and the artifact that carries the rest, `material-icons-extended`, is megabytes of
 * icons to reach a few dozen, on every target this module publishes to. So a design-system adapter
 * whose whole job is five hundred lines of renderers would have shipped an icon library as its
 * largest component.
 *
 * Path data rather than `ImageVector` builders because the catalog needs a path renderer anyway:
 * an `Icon`'s `name` may be an object carrying `svgPath`, which is an agent handing the renderer
 * exactly this kind of string. One code path draws both -- see [iconPathNodes] -- so the named
 * icons are exercised by every test that draws one, and the agent-supplied case is not a separate
 * thing that only runs when a payload happens to use it.
 *
 * These are drawn on Material's 24dp grid and read as the icons they name, but they are not traced
 * from Material Symbols and will not match one pixel for pixel. A host that wants the real set
 * registers its own `Icon` renderer -- one entry in [ComponentRegistry.with][dev.ynagai.a2ui.compose.ComponentRegistry.with]
 * -- and everything else here keeps working.
 *
 * `call` and `phone` share a glyph, as they do in Material Symbols: both are the handset.
 */
internal val ICON_PATHS: Map<String, String> = buildMap {
    put(
        "accountCircle",
        "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 3.5a3.25 3.25 0 1 1 0 6.5 3.25 3.25 0 0 1 " +
            "0-6.5zm0 14.4a7.9 7.9 0 0 1-5.6-2.3c.4-2.2 3-3.5 5.6-3.5s5.2 1.3 5.6 3.5a7.9 7.9 0 " +
            "0 1-5.6 2.3z",
    )
    put("add", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")
    put("arrowBack", "M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z")
    put("arrowForward", "M4 11h12.2l-5.6-5.6L12 4l8 8-8 8-1.4-1.4 5.6-5.6H4z")
    put(
        "attachFile",
        "M16.5 6v10.5a4.5 4.5 0 0 1-9 0V5.5a3 3 0 0 1 6 0v9.75a1.5 1.5 0 0 1-3 0V6.5H9v8.75a3 3 " +
            "0 0 0 6 0V5.5a4.5 4.5 0 0 0-9 0v11a6 6 0 0 0 12 0V6z",
    )
    put(
        "calendarToday",
        "M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 " +
            "0-2-2zm0 16H5V10h14v10zM5 8V6h14v2H5z",
    )
    put(
        "call",
        "M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2c.3-.3.7-.4 1-.2 1.2.4 2.4.6 3.6.6.6 0 1 .4 1 " +
            "1V20c0 .6-.4 1-1 1A17 17 0 0 1 3 4c0-.6.4-1 1-1h3.5c.6 0 1 .4 1 1 0 1.3.2 2.5.6 " +
            "3.6.1.4 0 .8-.2 1l-2.3 2.2z",
    )
    put(
        "camera",
        "M9 3L7.2 5H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-3.2L15 " +
            "3H9zm3 5.5a5 5 0 1 1 0 10 5 5 0 0 1 0-10zm0 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
    )
    put("check", "M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z")
    put("close", "M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z")
    put("delete", "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z")
    put("download", "M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z")
    put(
        "edit",
        "M3 17.25V21h3.75L17.8 9.94l-3.75-3.75L3 17.25zM20.7 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 " +
            "1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
    )
    put(
        "event",
        "M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 " +
            "0-2-2zm0 16H5V10h14v10zM7 12h5v5H7v-5z",
    )
    put("error", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z")
    put("fastForward", "M4 18l8.5-6L4 6v12zm9 0l8.5-6L13 6v12z")
    put(
        "favorite",
        "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 " +
            "2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 " +
            "11.54L12 21.35z",
    )
    put(
        "favoriteOff",
        "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 " +
            "3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 " +
            "16.5 3zm-4.4 15.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 " +
            "3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 " +
            "5.74-7.9 10.05z",
    )
    put("folder", "M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z")
    put(
        "help",
        "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 17h-2v-2h2v2zm2.07-7.75l-.9.92c-.72.73-1.17 " +
            "1.33-1.17 2.83h-2v-.5c0-1.1.45-2.1 1.17-2.83l1.24-1.26A1.96 1.96 0 0 0 12 6.75c-1.1 " +
            "0-2 .9-2 2H8a4 4 0 1 1 8 0c0 .88-.36 1.68-.93 2.25z",
    )
    put("home", "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z")
    put("info", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")
    put(
        "locationOn",
        "M12 2a7 7 0 0 0-7 7c0 5.25 7 13 7 13s7-7.75 7-13a7 7 0 0 0-7-7zm0 9.5a2.5 2.5 0 1 1 0-5 " +
            "2.5 2.5 0 0 1 0 5z",
    )
    put(
        "lock",
        "M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 " +
            "0 0-2-2zm-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm3.1-9H8.9V6a3.1 3.1 0 0 1 6.2 0v2z",
    )
    put(
        "lockOpen",
        "M18 8h-7V6a3.1 3.1 0 0 1 6.2 0H19A5 5 0 0 0 9 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 " +
            "2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zm-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4z",
    )
    put("mail", "M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z")
    put("menu", "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    put("moreHoriz", "M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm12 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm-6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
    put("moreVert", "M12 8a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
    put(
        "notifications",
        "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4.5-5.8V4.5a1.5 1.5 0 0 0-3 " +
            "0v.7A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z",
    )
    put(
        "notificationsOff",
        "M18 16v-.9L8.8 5.9c.5-.3 1.1-.6 1.7-.7v-.7a1.5 1.5 0 0 1 3 0v.7c2.6.6 4.5 3 4.5 " +
            "5.8v5zM4.4 3.1L3 4.5l3.2 3.2A6 6 0 0 0 6 11v5l-2 2v1h13.2l2.3 2.3 1.4-1.4L4.4 " +
            "3.1zM12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2z",
    )
    put("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    put(
        "payment",
        "M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z",
    )
    put("person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z")
    put("phone", getValue("call"))
    put(
        "photo",
        "M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 " +
            "3 3.5-4.5 4.5 6H5l3.5-4.5z",
    )
    put("play", "M8 5v14l11-7z")
    put(
        "print",
        "M19 8H5a3 3 0 0 0-3 3v6h4v4h12v-4h4v-6a3 3 0 0 0-3-3zm-3 11H8v-5h8v5zm3-7a1 1 0 1 1 " +
            "0-2 1 1 0 0 1 0 2zM18 3H6v4h12V3z",
    )
    put(
        "refresh",
        "M17.65 6.35A8 8 0 1 0 19.73 14h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    )
    put("rewind", "M11 18V6l-8.5 6 8.5 6zm9 0V6l-8.5 6 8.5 6z")
    put(
        "search",
        "M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 " +
            "4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 " +
            "0 1 9.5 14z",
    )
    put("send", "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z")
    put(
        "settings",
        "M19.4 13.06a7.7 7.7 0 0 0 0-2.12l1.7-1.32-1.6-2.77-2.02.68a7.6 7.6 0 0 0-1.62-.94L15.5 " +
            "4.6h-3.2l-.36 1.99a7.6 7.6 0 0 0-1.62.94l-2.02-.68-1.6 2.77 1.7 1.32a7.7 7.7 0 0 0 " +
            "0 2.12l-1.7 1.32 1.6 2.77 2.02-.68c.5.38 1.04.7 1.62.94l.36 2.01h3.2l.36-2.01c.58-.24 " +
            "1.12-.56 1.62-.94l2.02.68 1.6-2.77-1.7-1.32zM12 15.2a3.2 3.2 0 1 1 0-6.4 3.2 3.2 0 " +
            "0 1 0 6.4z",
    )
    put(
        "share",
        "M18 16.1c-.76 0-1.44.3-1.96.77L8.9 12.7c.05-.23.08-.46.08-.7s-.03-.47-.08-.7l7.05-4.11c.54.5 " +
            "1.25.81 2.05.81a3 3 0 1 0-3-3c0 .24.03.47.08.7L8.04 9.81A3 3 0 1 0 6 15c.8 0 " +
            "1.51-.31 2.05-.81l7.12 4.16c-.05.21-.07.43-.07.65a2.92 2.92 0 1 0 2.9-2.9z",
    )
    put(
        "shoppingCart",
        "M7 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm10 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM7.16 14.26l.03-.12.9-1.64h7.45c.75 " +
            "0 1.41-.41 1.75-1.03l3.58-6.49L19.13 4l-3.58 6.5H8.53L4.27 1.5H1v2h2l3.6 " +
            "7.59-1.35 2.44c-.16.28-.25.61-.25.97a2 2 0 0 0 2 2h12v-2H7.42c-.14 0-.26-.11-.26-.24z",
    )
    put("skipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    put("skipPrevious", "M6 6h2v12H6V6zm3.5 6l8.5 6V6l-8.5 6z")
    put("star", "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    put(
        "starHalf",
        "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 " +
            "9.24zM12 15.4V6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
    )
    put(
        "starOff",
        "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 " +
            "9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 " +
            "2.88 1 4.28L12 15.4z",
    )
    put("stop", "M6 6h12v12H6z")
    put("upload", "M5 20h14v-2H5v2zM12 4l-7 7h4v6h6v-6h4l-7-7z")
    put(
        "visibility",
        "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 " +
            "12.5a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
    )
    put(
        "visibilityOff",
        "M12 6.5a5.5 5.5 0 0 1 5.5 5.5c0 .65-.13 1.26-.35 1.83l3.21 3.21A13 13 0 0 0 23 " +
            "12c-1.73-4.39-6-7.5-11-7.5-1.27 0-2.49.2-3.64.57l2.37 2.37c.57-.22 1.18-.35 " +
            "1.83-.35zM2.7 3.4L1.3 4.8l2.5 2.5A12.9 12.9 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5 1.55 " +
            "0 3.03-.3 4.38-.84l3.32 3.32 1.41-1.41L2.7 3.4zM12 17a5 5 0 0 1-4.5-7.16l1.6 1.6A3 " +
            "3 0 0 0 12 15c.2 0 .4-.02.6-.06l1.6 1.6c-.68.3-1.42.46-2.2.46z",
    )
    put("volumeDown", "M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 7.97v8.05A4.47 4.47 0 0 0 16.5 12z")
    put("volumeMute", "M7 9v6h4l5 5V4l-5 5H7z")
    put(
        "volumeOff",
        "M16.5 12A4.5 4.5 0 0 0 14 7.97v2.21l2.45 2.45c.03-.2.05-.41.05-.63zM19 12c0 .94-.2 " +
            "1.82-.54 2.64l1.51 1.51A8.8 8.8 0 0 0 21 12a9 9 0 0 0-7-8.77v2.06A7 7 0 0 1 19 " +
            "12zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.43.93-2.25 1.18v2.06a8.9 " +
            "8.9 0 0 0 3.69-1.81L19.73 21 21 19.73 4.27 3zM12 4L9.91 6.09 12 8.18V4z",
    )
    put(
        "volumeUp",
        "M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 7.97v8.05A4.47 4.47 0 0 0 16.5 12zM14 " +
            "3.23v2.06a7 7 0 0 1 0 13.42v2.06a9 9 0 0 0 0-17.54z",
    )
    put("warning", "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z")
}
