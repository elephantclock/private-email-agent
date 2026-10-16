package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

/** How much a message needs the user. URGENT and HIGH notify. Declared most to least important. */
@Serializable
enum class Importance {
    URGENT,
    HIGH,
    NORMAL,
    LOW,
    ;

    val notifies: Boolean get() = this == URGENT || this == HIGH
}
