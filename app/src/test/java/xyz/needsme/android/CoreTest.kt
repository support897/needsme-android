package xyz.needsme.android

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val json = """[{"id":"a","name":"Claude","url":"https://claude.ai/","group":"Chat","how":"web"},{"id":"b","name":"Suno","url":"https://suno.com/","group":"Music","how":"web"},{"id":"t","name":"Codex CLI","url":"","group":"Agent","how":"terminal"}]"""
    @Test fun catalogKeepsOnlyWebAIs() { assertEquals(2, Catalog.parse(json).size) }
    @Test fun searchAndGroupFilter() {
        val l = Catalog.parse(json)
        assertEquals(listOf("Claude"), Catalog.filter(l, "cla", "All").map { it.name })
        assertEquals(listOf("Suno"), Catalog.filter(l, "", "Music").map { it.name })
    }
    @Test fun accountsRoundTrip() {
        val a = Account(id = "x1", aiId = "a", aiName = "Claude", url = "https://claude.ai/", nickname = "Work", emoji = "x", note = "n")
        assertEquals(listOf(a), Store.decode(Store.encode(listOf(a))))
        assertEquals("Work", a.title); assertEquals("acct_x1", a.profile)
    }
    @Test fun badStoredJsonGivesEmptyList() { assertTrue(Store.decode("not json").isEmpty()) }
    @Test fun titleFallsBackToAiName() { assertEquals("Claude", Account(aiId = "a", aiName = "Claude", url = "u", nickname = "").title) }
    @Test fun googleAndAppleSignInGoToRealBrowser() {
        assertEquals("google", providerFor("https://accounts.google.com/o/oauth2/auth"))
        assertEquals("apple", providerFor("https://appleid.apple.com/auth/authorize"))
        assertNull(providerFor("https://support897.github.io/needsme/app/"))
    }
    @Test fun parseUser() {
        assertNull(parseUser("null")); assertNull(parseUser("{}"))
        assertEquals("u1", parseUser("""{"uid":"u1","email":"e","apiKey":"k","refresh":"r"}""")?.uid)
    }

    // Items 2, 9, 14: one shared list, newest change wins, removals reach every device
    @Test fun sharedMergeKeepsBothDevicesAndNewestWins() {
        val phone = SharedList.fromAccount(Account(id = "p1", aiId = "chatgpt", aiName = "ChatGPT", url = "https://chatgpt.com/", nickname = "Work", color = 0x3E7BFA, at = 100), "")
        val desk = Shared("d1", "claude", "Claude", "https://claude.ai/", "Main", "my coding AI", "", "", "#A8C256", 7, 7, 200, false)
        var m = SharedList.merge(listOf(phone), listOf(desk))
        assertEquals(setOf("p1", "d1"), m.map { it.id }.toSet())
        assertEquals("#3E7BFA", m.first { it.id == "p1" }.color)
        m = SharedList.merge(m, listOf(desk.copy(name = "Home", at = 300))); assertEquals("Home", m.first { it.id == "d1" }.name)
        m = SharedList.merge(m, listOf(desk.copy(name = "Old", at = 50))); assertEquals("Home", m.first { it.id == "d1" }.name)
        m = SharedList.merge(m, listOf(phone.copy(del = true, at = 400))); assertTrue(m.first { it.id == "p1" }.del)
    }
    @Test fun sharedJsonRoundTripAndComputerShape() {
        val s = Shared("d1", "claude", "Claude", "", "Main", "desc", "x", "", "#A8C256", 5, 3, 9, false)
        assertEquals(listOf(s), SharedList.parse(SharedList.encode(listOf(s))))
        val a = SharedList.toAccount(s, null) { if (it == "claude") "https://claude.ai/" else null }
        assertEquals("https://claude.ai/", a.url); assertEquals("", a.nickname); assertEquals(0xA8C256L, a.color); assertEquals(5, a.nf); assertEquals(3, a.use)
        // same ids and times = no write, even if small details differ between apps
        assertTrue(SharedList.same(listOf(s), listOf(s.copy(name = "x"))))
        assertFalse(SharedList.same(listOf(s), listOf(s.copy(at = 10))))
    }
    @Test fun legacyPhoneListIsBroughtIn() {
        val old = Store.encode(listOf(Account(id = "o1", aiId = "custom-5", aiName = "MyBot", url = "https://bot.example.com", nickname = "")))
        val l = SharedList.fromLegacy(old); assertEquals("custom", l[0].ai); assertEquals("https://bot.example.com", l[0].url)
    }
    // Items 3 and 5: one alert per new reply, the AI's exact words, right switch
    @Test fun alertUsesExactReplyAndRightSwitch() {
        assertEquals("Here is your plan.", Notify.decide(true, "old", false, "Here is your plan.", null, false, NF_ALL, 7))
        assertNull(Notify.decide(true, "old", false, "Here is your plan.", null, false, NF_ALL and NF_DONE.inv(), 7))
        assertNull(Notify.decide(false, "same", false, "same", null, false, NF_ALL, 7))
        assertNull(Notify.decide(true, "old", false, "new", null, true, NF_ALL, 7)) // she is looking at it
        assertNull(Notify.decide(true, "old", true, "half", null, false, NF_ALL, 7)) // still typing
        assertNull(Notify.decide(false, "old", false, "Do you want me to send it?", null, false, NF_ALL, 5)) // Needs Me switched off
        assertEquals("Do you want me to send it?", Notify.decide(false, "old", false, "Do you want me to send it?", null, false, NF_ALL, 7))
        assertNull(Notify.decide(false, "old", false, "dup", "dup", false, NF_ALL, 7)) // never twice
        assertEquals("ChatGPT \u00b7 Work", Notify.titleOf(Account(aiId = "chatgpt", aiName = "ChatGPT", url = "u", nickname = "Work")))
    }
}
