package app.nya.smsforward.node.sms

/** A row from the platform SMS provider that can be compared with a server delete request. */
data class SmsDeleteCandidate(
    val id: Long,
    val address: String?,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val simSlot: Int?,
)

/** Matches provider rows without depending on Android classes, so the rules stay unit-testable. */
object SmsDeleteMatcher {
    data class Match(val id: Long, val distance: Long, val bodyRank: Int)

    fun find(request: DeleteSms, candidates: Iterable<SmsDeleteCandidate>, windowMs: Long): Match? {
        val requestedPeer = PeerKey.normalize(request.peer)
        val requestedBody = normalizeBody(request.body)
        val compactRequestedBody = compactBody(requestedBody)
        return candidates.mapNotNull { candidate ->
            if (PeerKey.normalize(candidate.address.orEmpty()) != requestedPeer) return@mapNotNull null
            if (request.simSlot != null && candidate.simSlot != null && request.simSlot != candidate.simSlot) return@mapNotNull null
            val bodyRank = when {
                normalizeBody(candidate.body) == requestedBody -> 0
                compactBody(candidate.body) == compactRequestedBody -> 1
                else -> return@mapNotNull null
            }
            val distance = listOfNotNull(
                candidate.date.takeIf { it > 0 }?.let { kotlin.math.abs(it - request.deviceTime) },
                candidate.dateSent.takeIf { it > 0 }?.let { kotlin.math.abs(it - request.deviceTime) },
            ).minOrNull() ?: Long.MAX_VALUE
            if (distance > windowMs) return@mapNotNull null
            Match(candidate.id, distance, bodyRank)
        }.minWithOrNull(compareBy<Match> { it.bodyRank }.thenBy { it.distance }.thenBy { it.id })
    }

    private fun normalizeBody(body: String): String = body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    private fun compactBody(body: String): String = normalizeBody(body).replace(Regex("\\s+"), " ")
}
