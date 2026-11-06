package com.example.stylustest

/**
 * Represents a single evdev event line from `getevent -l <device>`.
 *
 * Example input line:
 *   /dev/input/event8: 0003 0030 00000001
 *   (type code value)  where type=0003(EV_ABS), code=0030(ABS_BRAKE), value=1
 */
data class EvdevEvent(
    val device: String,
    val type: Int,       // e.g. 0x0003 = EV_ABS
    val code: Int,       // e.g. 0x0030 = ABS_BRAKE
    val value: Int,      // raw value
    val rawLine: String
)

/**
 * Parses getevent output lines into [EvdevEvent] objects.
 *
 * Line format: "/dev/input/eventN: TTTT CCCC VVVVVVVV"
 *   TTTT = event type (hex), CCCC = event code (hex), VVVVVVVV = value (hex)
 */
object EvdevParser {
    // EV_ABS = 0x0003
    private const val EV_ABS = 0x0003

    fun parse(line: String, device: String): EvdevEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // Expected: /dev/input/event8: 0003 0030 00000001
        val parts = trimmed.split(limit = 4)
        if (parts.size < 4) return null

        // parts[0] = "/dev/input/event8:"
        // parts[1] = type (hex), parts[2] = code (hex), parts[3] = value (hex)
        val typeStr = parts[1].trim()
        val codeStr = parts[2].trim()
        val valueStr = parts[3].trim()

        val type = try { Integer.parseInt(typeStr, 16) } catch (_: NumberFormatException) { return null }
        val code = try { Integer.parseInt(codeStr, 16) } catch (_: NumberFormatException) { return null }
        val value = try { Integer.parseInt(valueStr, 16) } catch (_: NumberFormatException) { return null }

        return EvdevEvent(device, type, code, value, trimmed)
    }

    /** Returns true if this event is an absolute axis event (EV_ABS). */
    fun isAbsAxis(event: EvdevEvent): Boolean = event.type == EV_ABS
}

/**
 * Known ABS axis codes from linux/input.h. The Xiaomi Focus Pen Pro reports
 * ABS_BRAKE (0x30) among others. We don't hardcode which one is roll — the UI
 * lets the user pick.
 */
object AbsAxis {
    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_PRESSURE = 0x18
    const val ABS_DISTANCE = 0x19
    const val ABS_TILT_X = 0x36
    const val ABS_TILT_Y = 0x37
    const val ABS_BRAKE = 0x30
    const val ABS_MISC = 0x28
    const val ABS_RX = 0x2b
    const val ABS_RY = 0x2c
    const val ABS_RZ = 0x2d

    fun name(code: Int): String = when (code) {
        ABS_X -> "ABS_X"
        ABS_Y -> "ABS_Y"
        ABS_PRESSURE -> "ABS_PRESSURE"
        ABS_DISTANCE -> "ABS_DISTANCE"
        ABS_TILT_X -> "ABS_TILT_X"
        ABS_TILT_Y -> "ABS_TILT_Y"
        ABS_BRAKE -> "ABS_BRAKE"
        ABS_MISC -> "ABS_MISC"
        ABS_RX -> "ABS_RX"
        ABS_RY -> "ABS_RY"
        ABS_RZ -> "ABS_RZ"
        else -> "ABS_0x${code.toString(16).uppercase()}"
    }
}

/**
 * Holds the latest value and min/max range for each ABS axis seen on a device.
 */
data class AxisInfo(
    val code: Int,
    var latestValue: Int = 0,
    var min: Int = Int.MAX_VALUE,
    var max: Int = Int.MIN_VALUE,
    var changedAt: Long = 0
)

/**
 * Reads evdev data from a Shizuku shell session and maintains [AxisInfo] for each axis.
 */
class EvdevReader(
    private val shizuku: ShizukuManager,
    private val device: String,
    private val onAxisUpdate: (AxisInfo) -> Unit,
    private val onError: (String) -> Unit
) {
    private var session: ShizukuManager.GetEventSession? = null
    private val axes = mutableMapOf<Int, AxisInfo>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        session = shizuku.startGetEvent(device) { line ->
            val event = EvdevParser.parse(line, device)
            if (event == null) return@startGetEvent
            if (!EvdevParser.isAbsAxis(event)) return@startGetEvent

            val info = axes.getOrPut(event.code) {
                AxisInfo(code = event.code)
            }
            info.latestValue = event.value
            info.min = coerceAtMost(info.min, event.value)
            info.max = coerceAtLeast(info.max, event.value)
            info.changedAt = System.currentTimeMillis()

            // Post update back via callback (caller is responsible for main-thread dispatch)
            onAxisUpdate(info)
        }
    }

    fun stop() {
        started = false
        session?.stop()
        session = null
    }

    fun getAxis(code: Int): AxisInfo? = axes[code]

    fun allAxes(): List<AxisInfo> = axes.values.toList()

    private fun coerceAtMost(a: Int, b: Int): Int = if (a < b) a else b
    private fun coerceAtLeast(a: Int, b: Int): Int = if (a > b) a else b
}
