package com.moeez.carnav

/** Which arrow to draw for a manoeuvre. */
enum class TurnIcon {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN, ROUNDABOUT, ARRIVE
}

/** Turns OSRM's manoeuvre codes into plain English and an arrow. */
object Instructions {

    /** Official English names looked up online, keyed by [Names.key]; set by NavModel. */
    var resolvedName: (String) -> String? = { null }

    fun text(step: Step): String {
        val road = roadName(step)
        val onto = if (road.isNotEmpty()) " onto $road" else ""
        val mod = step.modifier
        if (mod == "uturn" && step.type != "arrive") return "Make a U-turn$onto"

        return when (step.type) {
            "depart" -> if (road.isNotEmpty()) "Head out on $road" else "Start driving"
            "arrive" -> "Arrive at your destination"
            "roundabout", "rotary" -> {
                val exit = step.exit
                if (exit != null) "At the roundabout, take the ${ordinal(exit)} exit$onto"
                else "Go through the roundabout$onto"
            }
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            "roundabout turn" -> "At the roundabout, ${turnPhrase(mod).replaceFirstChar { it.lowercase() }}$onto"
            "merge" -> "Merge$onto".let { if (road.isEmpty()) "Merge ${side(mod)}".trim() else it }
            "on ramp" -> "Take the ramp$onto"
            "off ramp" -> "Take the exit$onto"
            "fork" -> "Keep ${side(mod).ifEmpty { "straight" }} at the fork$onto"
            "end of road" -> "At the end of the road, ${turnPhrase(mod).replaceFirstChar { it.lowercase() }}$onto"
            "new name", "continue", "notification" ->
                if (mod == null || mod == "straight") "Continue$onto".let { if (road.isEmpty()) "Continue straight" else it }
                else "${turnPhrase(mod)}$onto"
            else -> "${turnPhrase(mod)}$onto" // "turn" and anything unexpected
        }
    }

    fun icon(step: Step): TurnIcon = when {
        step.type == "arrive" -> TurnIcon.ARRIVE
        step.modifier == "uturn" -> TurnIcon.UTURN
        step.type == "roundabout" || step.type == "rotary" || step.type == "roundabout turn" -> TurnIcon.ROUNDABOUT
        else -> when (step.modifier) {
            "slight left" -> TurnIcon.SLIGHT_LEFT
            "left" -> TurnIcon.LEFT
            "sharp left" -> TurnIcon.SHARP_LEFT
            "slight right" -> TurnIcon.SLIGHT_RIGHT
            "right" -> TurnIcon.RIGHT
            "sharp right" -> TurnIcon.SHARP_RIGHT
            else -> TurnIcon.STRAIGHT
        }
    }

    /** Road name in English (Urdu-only OSM names are converted — see [Names]). */
    fun roadName(step: Step): String {
        val name = resolvedName(Names.key(step.name)) ?: Names.english(step.name)
        val ref = Names.english(step.ref)
        return when {
            name.isNotBlank() && ref.isNotBlank() && ref != name -> "$name ($ref)"
            name.isNotBlank() -> name
            else -> ref
        }
    }

    private fun turnPhrase(mod: String?): String = when (mod) {
        "slight left" -> "Bear left"
        "slight right" -> "Bear right"
        "sharp left" -> "Turn sharp left"
        "sharp right" -> "Turn sharp right"
        "left" -> "Turn left"
        "right" -> "Turn right"
        "uturn" -> "Make a U-turn"
        else -> "Go straight"
    }

    private fun side(mod: String?): String = when {
        mod == null -> ""
        mod.contains("left") -> "left"
        mod.contains("right") -> "right"
        else -> ""
    }

    private fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }
}
