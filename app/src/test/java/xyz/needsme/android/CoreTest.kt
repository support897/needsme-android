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
}
