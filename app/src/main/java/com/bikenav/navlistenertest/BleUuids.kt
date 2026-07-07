package com.bikenav.navlistenertest

import java.util.UUID

/**
 * These UUIDs must match exactly what you define on the ESP32 NimBLE server.
 * Keep this file as the single source of truth on the Android side, and
 * mirror the same values in a header on the firmware side.
 */
object BleUuids {
    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    // Phone writes navigation text here (distance + instruction)
    val NAV_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    // Reserved for your existing media/call control characteristic(s) — wire these
    // up to whatever you already defined in the NimBLE firmware.
    val MEDIA_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    const val DEVICE_NAME = "BikeNav"
}
