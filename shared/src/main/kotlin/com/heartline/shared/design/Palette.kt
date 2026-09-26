package com.heartline.shared.design

/**
 * One UI-inspired colour tokens shared by the phone and watch themes (ARGB).
 * See docs/MASTER_PLAN.md §3.1.
 */
object Palette {
    object Light {
        const val BACKGROUND = 0xFFF6F6F8
        const val SURFACE = 0xFFFFFFFF
        const val SURFACE_VARIANT = 0xFFEDEDF0
        const val ON_BACKGROUND = 0xFF111114
        const val ON_SURFACE_VARIANT = 0xFF6E6E76
        const val DIVIDER = 0xFFE3E3E6
        const val PRIMARY = 0xFF3E7BFA
        const val ECG = 0xFFF2445B
        const val BP = 0xFF8C5CF6
        const val SPO2 = 0xFF14B8C4
        const val TEMP = 0xFFFF8A3D
        const val BODY = 0xFF2FBF71
        const val STRESS = 0xFFF5B400
        const val HEART_RATE = 0xFFFF5E7E
        const val STATUS_NORMAL = 0xFF2FBF71
        const val STATUS_WARN = 0xFFF59E0B
        const val STATUS_ALERT = 0xFFE5484D
        const val ECG_GRID_MAJOR = 0xFFF4B6BE
        const val ECG_GRID_MINOR = 0xFFFBE3E6
        const val ECG_TRACE = 0xFF1B1B1F
    }

    object Dark {
        const val BACKGROUND = 0xFF000000
        const val SURFACE = 0xFF17171A
        const val SURFACE_VARIANT = 0xFF252529
        const val ON_BACKGROUND = 0xFFFAFAFA
        const val ON_SURFACE_VARIANT = 0xFFA0A0A8
        const val DIVIDER = 0xFF2C2C30
        const val PRIMARY = 0xFF5C91FF
        const val ECG = 0xFFFF5A6E
        const val BP = 0xFFA47CFF
        const val SPO2 = 0xFF2BD0DC
        const val TEMP = 0xFFFFA064
        const val BODY = 0xFF45D487
        const val STRESS = 0xFFFFC933
        const val HEART_RATE = 0xFFFF7A94
        const val STATUS_NORMAL = 0xFF45D487
        const val STATUS_WARN = 0xFFFBB63C
        const val STATUS_ALERT = 0xFFFF6369
        const val ECG_GRID_MAJOR = 0xFF5A2A31
        const val ECG_GRID_MINOR = 0xFF2E1A1D
        const val ECG_TRACE = 0xFFF5F5F7
    }

    /** Watch surfaces on the OLED black background. */
    object Watch {
        const val BACKGROUND = 0xFF000000
        const val SURFACE = 0xFF1C1C1F
        const val SURFACE_HIGH = 0xFF2A2A2E
    }
}

/** Accent colours the user can pick for the watch (dark theme values, ARGB). */
enum class Accent(val argb: Long) {
    BLUE(0xFF5C91FF),
    VIOLET(0xFFA47CFF),
    TEAL(0xFF2BD0DC),
    ROSE(0xFFFF7A94),
    AMBER(0xFFFFB84D),
    GREEN(0xFF45D487)
}
