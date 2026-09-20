package app.nya.smsforward.node.send

import java.security.MessageDigest

/** SHA-256 of a message text, hex. Lets the sent box be compared with what a task sent without storing message text. */
object BodyHash {
    fun of(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
