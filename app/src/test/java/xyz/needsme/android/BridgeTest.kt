package xyz.needsme.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeTest {
    @Test fun needsYouAlertUsesTheAisOwnWords() {
        val a = Bridge.alertFor(JSONObject("""{"ai":"Polar","project":"Site","needs_you":"Pick A or B?"}"""))!!
        assertEquals("Polar needs you", a.first); assertEquals("Pick A or B?", a.second)
    }
    @Test fun finishedAlertShowsSummary() {
        val a = Bridge.alertFor(JSONObject("""{"ai":"Claude","finished":true,"summary":"Report done"}"""))!!
        assertEquals("Claude finished", a.first); assertEquals("Report done", a.second)
    }
    @Test fun quietUpdatesDoNotAlert() { assertNull(Bridge.alertFor(JSONObject("""{"ai":"Claude","summary":"small change"}"""))) }
}
