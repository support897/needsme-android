package xyz.needsme.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class CheckInsTest {
    @Test fun nextNineAmFromEight() {
        val c = Calendar.getInstance(); c.set(2026, 10, 28, 8, 0, 0); c.set(Calendar.MILLISECOND, 0)
        assertEquals(3600000L, CheckIns.delayTo(9, c))
    }
    @Test fun nextNineAmFromTenIsTomorrow() {
        val c = Calendar.getInstance(); c.set(2026, 10, 28, 10, 0, 0); c.set(Calendar.MILLISECOND, 0)
        assertEquals(23L * 3600000L, CheckIns.delayTo(9, c))
    }
}
