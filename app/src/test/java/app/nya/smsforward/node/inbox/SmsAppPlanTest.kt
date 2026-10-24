package app.nya.smsforward.node.inbox

import kotlin.test.Test
import kotlin.test.assertEquals

class SmsAppPlanTest {
    private fun plan(switchOn: Boolean, turningOff: Boolean = false, isDefault: Boolean = false, componentsOn: Boolean = false) =
        planSmsApp(SmsAppState(switchOn, turningOff, isDefault, componentsOn))

    @Test
    fun `off stays off and keeps the components disabled`() {
        assertEquals(SmsAppStep(switchOn = false, turningOff = false, componentsOn = false), plan(switchOn = false))
    }

    @Test
    fun `on enables the components even before the app is the default`() {
        assertEquals(SmsAppStep(switchOn = true, turningOff = false, componentsOn = true), plan(switchOn = true))
    }

    @Test
    fun `components left on by a switched-off app are disabled`() {
        assertEquals(false, plan(switchOn = false, componentsOn = true).componentsOn)
    }

    @Test
    fun `while default nothing is dropped, whatever the switch says`() {
        // e.g. the switch was lost or turned off: incoming SMS would have nobody to store them.
        assertEquals(SmsAppStep(switchOn = true, turningOff = false, componentsOn = true), plan(switchOn = false, isDefault = true))
    }

    @Test
    fun `a switch-off waits while still the default SMS app`() {
        assertEquals(
            SmsAppStep(switchOn = true, turningOff = true, componentsOn = true),
            plan(switchOn = true, turningOff = true, isDefault = true, componentsOn = true),
        )
    }

    @Test
    fun `a waiting switch-off completes once another SMS app took over`() {
        assertEquals(
            SmsAppStep(switchOn = false, turningOff = false, componentsOn = false),
            plan(switchOn = true, turningOff = true, isDefault = false, componentsOn = true),
        )
    }
}
