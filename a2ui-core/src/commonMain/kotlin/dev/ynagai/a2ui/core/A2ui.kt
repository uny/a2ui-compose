package dev.ynagai.a2ui.core

/** Library-wide constants for the A2UI protocol this renderer implements. */
public object A2ui {
    /**
     * The A2UI protocol version this library targets.
     *
     * This library implements v1.0 only and carries no v0.8/v0.9 compatibility layer — see the
     * "Protocol version" section of the README for why.
     */
    public const val PROTOCOL_VERSION: String = "v1.0"
}
