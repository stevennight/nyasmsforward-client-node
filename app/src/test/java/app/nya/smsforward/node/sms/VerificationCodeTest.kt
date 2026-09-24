package app.nya.smsforward.node.sms

import kotlin.test.Test
import kotlin.test.assertEquals

/** The same cases as the server's code_test.go: both sides must agree on what a code is. */
class VerificationCodeTest {
    @Test
    fun `finds codes next to a keyword and nothing else`() {
        val cases = listOf(
            "【示例商城】验证码 583921，5 分钟内有效，请勿泄露给他人。" to "583921",
            "【示例平台】登录验证码：204817（10 分钟内有效）。如非本人操作请忽略。" to "204817",
            "【示例出行】动态码 918273，请勿转告他人。" to "918273",
            "【示例平台】您的验证码是 730154，用于注册账号，5 分钟内有效。" to "730154",
            "Your verification code is 4821. Do not share it." to "4821",
            "CODE: 990011" to "990011",
            "583921 是您的验证码，请勿泄露" to "583921",
            "204817为验证码" to "204817",
            "【示例银行】您尾号 1234 的账户于 09:12 支出 58.00 元，余额 2,310.45 元。" to null,
            "【示例快递】您的包裹已放入 3 号柜，取件码 4-7-2011，请于 24 小时内取件。" to null,
            "你到家了吗？" to null,
            "" to null,
        )
        for ((body, want) in cases) assertEquals(want, VerificationCode.find(body), body)
    }
}
