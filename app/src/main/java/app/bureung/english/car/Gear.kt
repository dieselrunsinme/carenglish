package app.bureung.english.car

enum class Gear {
    PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN;

    companion object {
        /** android.car VehicleGear values: N=0x1, R=0x2, P=0x4, D=0x8, manual 1st..9th = 0x10..0x1000 */
        fun fromAaos(value: Int): Gear = when (value) {
            0x4 -> PARK
            0x2 -> REVERSE
            0x1 -> NEUTRAL
            0x8 -> DRIVE
            else -> if (value >= 0x10) DRIVE else UNKNOWN
        }

        /** Pleos VehicleGear constants are GEAR_PARK, GEAR_DRIVE, ... */
        fun fromPleosName(name: String?): Gear = when (name) {
            "GEAR_PARK" -> PARK
            "GEAR_REVERSE" -> REVERSE
            "GEAR_NEUTRAL" -> NEUTRAL
            "GEAR_DRIVE" -> DRIVE
            null, "GEAR_UNKNOWN" -> UNKNOWN
            else -> if (name.startsWith("GEAR_")) DRIVE else UNKNOWN
        }
    }
}
