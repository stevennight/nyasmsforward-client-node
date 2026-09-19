package app.nya.smsforward.node.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Expected values were produced with `sha256sum` over the UTF-8 input, and are also listed in docs/协议.md §5. */
class DedupeKeyTest {
    @Test
    fun `matches the documented test vectors`() {
        assertEquals(
            "sha256:3a42e812f242b041d3885bc806d25bee27e294704a857c429df351c817483e6e",
            DedupeKey.compute("d_01J", 1, "106901234", 1789830000000, "【示例商城】验证码 583921，5 分钟内有效。"),
        )
        // The body may contain the separator: it is last, so the input stays unambiguous.
        assertEquals(
            "sha256:2f81e1c96e0e717efd9fdc88b1066c5a34814c5242c1570ccdc6206d3c8c117d",
            DedupeKey.compute("d_01J", 2, "示例银行", 1789830060000, "余额 2,310.45 元|含竖线"),
        )
    }

    @Test
    fun `is stable for the same message and differs when anything changes`() {
        val base = DedupeKey.compute("d", 1, "a", 1L, "b")
        assertEquals(base, DedupeKey.compute("d", 1, "a", 1L, "b"))
        assertNotEquals(base, DedupeKey.compute("d2", 1, "a", 1L, "b"))
        assertNotEquals(base, DedupeKey.compute("d", 2, "a", 1L, "b"))
        assertNotEquals(base, DedupeKey.compute("d", 1, "a2", 1L, "b"))
        assertNotEquals(base, DedupeKey.compute("d", 1, "a", 2L, "b"))
        assertNotEquals(base, DedupeKey.compute("d", 1, "a", 1L, "b2"))
    }
}
