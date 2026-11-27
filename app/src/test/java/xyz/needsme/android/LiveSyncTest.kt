package xyz.needsme.android

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Runs only when NM_LIVE_REFRESH is set: talks to the real Needs Me server with a test account. */
class LiveSyncTest {
    private val key = "AIzaSyD3pfsSI-NpEPZgZhk4jiqdIZjyfmbkTVs"
    @Test fun phoneSeesComputerListAndComputerSeesPhone() {
        val refresh = System.getenv("NM_LIVE_REFRESH") ?: ""; val uid = System.getenv("NM_LIVE_UID") ?: ""
        assumeTrue(refresh.isNotBlank())
        val phone = SharedList.fromAccount(Account(id = "phone-test-1", aiId = "chatgpt", aiName = "ChatGPT", url = "https://chatgpt.com/", nickname = "Phone test", at = System.currentTimeMillis()), "")
        val merged = Sync.sharedRound(key, refresh, uid, listOf(phone))
        println("LIVE merged ids: " + merged.map { it.id + "/" + it.name + "/del=" + it.del })
        assertTrue(merged.any { it.id == "phone-test-1" })
        val expect = System.getenv("NM_LIVE_EXPECT") ?: "desk-test-1"
        assertTrue("other-device entry missing: $expect", merged.any { it.id == expect })
        val again = Sync.sharedRound(key, refresh, uid, merged)
        assertTrue(SharedList.same(merged, again))
    }
}
