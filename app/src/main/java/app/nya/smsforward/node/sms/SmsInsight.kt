package app.nya.smsforward.node.sms

/**
 * What the full edition recognizes in a message besides the verification code: a bank account movement or a parcel
 * pickup code. Like [VerificationCode] it is conservative: a message that does not clearly match gets no card at all.
 */
sealed class SmsInsight {
    /** "尾号 1234 支出 58.00 元，余额 2,310.45 元": [income] false for money going out. Amounts keep the SMS's own text. */
    data class Bank(val income: Boolean, val amount: String, val balance: String?, val cardTail: String?) : SmsInsight()

    /** "取件码 4-7-2011": the code to show at the parcel locker or station. */
    data class Parcel(val code: String) : SmsInsight()

    companion object {
        private const val AMOUNT = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"

        // The movement keyword, then at most a few non-digit characters ("人民币", "RMB", "：") before the amount.
        private val movement = Regex(
            "(支出|消费|扣款|扣费|转出|取现|取款|付款|支付|收入|存入|转入|入账|汇入|到账|退款|代发工资|工资)[^0-9，。,;；]{0,10}$AMOUNT\\s*元?",
        )
        private val balance = Regex("余额[^0-9，。,;；]{0,10}$AMOUNT")
        private val cardTail = Regex("尾号\\s*[:：]?\\s*([0-9]{3,4})")
        // Something that says this is about an account, so "我支付了 50" in a chat is not a bank card.
        private val bankContext = Regex("尾号|账户|帐户|余额|银行|储蓄卡|信用卡|借记卡|钱包")
        private val incomeWords = setOf("收入", "存入", "转入", "入账", "汇入", "到账", "退款", "代发工资", "工资")

        private val pickup = Regex("(?:取件码|取货码|提货码|取件号|取餐码)[^0-9A-Za-z]{0,6}([0-9A-Za-z]{1,8}(?:-[0-9A-Za-z]{1,8}){0,4})")

        fun find(body: String): SmsInsight? {
            pickup.find(body)?.let { m ->
                val code = m.groupValues[1]
                if (code.count { it.isLetterOrDigit() } >= 3 && code.any { it.isDigit() }) return Parcel(code)
            }
            if (!bankContext.containsMatchIn(body)) return null
            val move = movement.find(body) ?: return null
            // The amount right after "余额" is the balance, not the movement ("余额 2,310.45 元" alone is no movement).
            return Bank(
                income = move.groupValues[1] in incomeWords,
                amount = move.groupValues[2],
                balance = balance.find(body)?.groupValues?.get(1),
                cardTail = cardTail.find(body)?.groupValues?.get(1),
            )
        }
    }
}
