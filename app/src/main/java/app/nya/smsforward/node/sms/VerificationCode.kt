package app.nya.smsforward.node.sms

/**
 * Finds the verification code in an SMS, with the same rules as the server (internal/server/sms/code.go) so the phone and
 * the web show the same code.
 *
 * It is deliberately conservative: only digits next to a verification keyword count, so amounts, dates and parcel
 * numbers are not mistaken for codes.
 */
object VerificationCode {
    // "验证码 583921", "动态码：918273", "code is 1234": the keyword comes first.
    private val afterKeyword = Regex("(?i)(?:验证码|校验码|动态码|动态密码|安全码|确认码|code)[^0-9]{0,8}([0-9]{4,8})")

    // "583921 是您的验证码", "204817为验证码": the digits come first.
    private val beforeKeyword = Regex("([0-9]{4,8})[^0-9]{0,6}(?:为|是)?(?:您的|本次)?(?:验证码|校验码|动态码)")

    fun find(body: String): String? =
        afterKeyword.find(body)?.groupValues?.get(1) ?: beforeKeyword.find(body)?.groupValues?.get(1)
}
