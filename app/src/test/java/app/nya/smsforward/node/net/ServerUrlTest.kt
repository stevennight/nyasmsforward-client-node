package app.nya.smsforward.node.net

import app.nya.smsforward.node.net.ServerUrlResult.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerUrlTest {
    private fun ok(url: String, insecure: Boolean = false) = ServerUrlResult.Ok(url, insecure)
    private fun bad(reason: Reason) = ServerUrlResult.Invalid(reason)

    @Test
    fun `https addresses are accepted and normalized`() {
        assertEquals(ok("https://sms.example.com"), ServerUrl.validate("https://sms.example.com"))
        assertEquals(ok("https://sms.example.com"), ServerUrl.validate("  https://sms.example.com/  "))
        assertEquals(ok("https://sms.example.com:8443/base"), ServerUrl.validate("https://sms.example.com:8443/base/"))
    }

    @Test
    fun `plain http is only allowed for localhost and private networks`() {
        assertEquals(ok("http://localhost:8080", insecure = true), ServerUrl.validate("http://localhost:8080"))
        assertEquals(ok("http://127.0.0.1:8080", insecure = true), ServerUrl.validate("http://127.0.0.1:8080"))
        assertEquals(ok("http://192.168.1.5:8080", insecure = true), ServerUrl.validate("http://192.168.1.5:8080"))
        assertEquals(ok("http://10.0.0.2", insecure = true), ServerUrl.validate("http://10.0.0.2"))
        assertEquals(ok("http://172.16.0.9", insecure = true), ServerUrl.validate("http://172.16.0.9"))
        assertEquals(ok("http://172.31.255.1", insecure = true), ServerUrl.validate("http://172.31.255.1"))

        assertEquals(bad(Reason.PUBLIC_HTTP), ServerUrl.validate("http://sms.example.com"))
        assertEquals(bad(Reason.PUBLIC_HTTP), ServerUrl.validate("http://8.8.8.8"))
        assertEquals(bad(Reason.PUBLIC_HTTP), ServerUrl.validate("http://172.32.0.1"))
        assertEquals(bad(Reason.PUBLIC_HTTP), ServerUrl.validate("http://192.169.1.1"))
        assertEquals(bad(Reason.PUBLIC_HTTP), ServerUrl.validate("http://300.1.1.1"))
    }

    @Test
    fun `rejects empty input, other schemes and malformed hosts`() {
        assertEquals(bad(Reason.EMPTY), ServerUrl.validate("   "))
        assertEquals(bad(Reason.BAD_SCHEME), ServerUrl.validate("sms.example.com"))
        assertEquals(bad(Reason.BAD_SCHEME), ServerUrl.validate("ftp://sms.example.com"))
        assertEquals(bad(Reason.BAD_HOST), ServerUrl.validate("https://"))
        assertEquals(bad(Reason.BAD_HOST), ServerUrl.validate("https://user@sms.example.com"))
        assertEquals(bad(Reason.BAD_HOST), ServerUrl.validate("https://sms example.com"))
        assertEquals(bad(Reason.BAD_HOST), ServerUrl.validate("https://sms.example.com:99999"))
        assertEquals(bad(Reason.BAD_HOST), ServerUrl.validate("https://sms.example.com:abc"))
    }
}
