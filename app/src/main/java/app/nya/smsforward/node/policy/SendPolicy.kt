package app.nya.smsforward.node.policy

/**
 * How much the platform may make this phone send (docs/协议.md §7). Ordered from strictest to loosest,
 * so the effective policy of two layers is simply the smaller ordinal.
 *
 * The phone's own setting is authoritative: it is set only on the phone and reported to the server via `hello`.
 */
enum class SendPolicy(val wire: String) {
    /** Reject every send task. Default. */
    OFF("off"),

    /** Only replies, and only to numbers that recently messaged this phone. */
    REPLY("reply"),

    /** Replies plus new messages to any number. */
    ANY("any");

    companion object {
        /** Unknown or missing values fall back to the safest policy. */
        fun fromWire(value: String?): SendPolicy = entries.firstOrNull { it.wire == value } ?: OFF

        /** The effective policy when two layers (platform limit, phone setting) both apply. */
        fun stricter(a: SendPolicy, b: SendPolicy): SendPolicy = if (a.ordinal <= b.ordinal) a else b
    }
}
