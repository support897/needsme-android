package xyz.needsme.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.security.SecureRandom

const val APP_URL = "https://support897.github.io/needsme/app/"
const val BRIDGE_URL = "https://support897.github.io/needsme/desktop-signin.html"

data class User(val uid: String, val email: String, val name: String, val apiKey: String, val refresh: String)

/** Reads the signed-in Needs Me user out of the web app's own storage and reports it back. */
const val READ_USER_JS = """(async()=>{try{const dbs=await indexedDB.databases();if(!dbs.some(d=>d.name==='firebaseLocalStorageDb')){NMBridge.user('null');return}
const q=indexedDB.open('firebaseLocalStorageDb');q.onerror=()=>NMBridge.user('null');q.onsuccess=()=>{try{const g=q.result.transaction('firebaseLocalStorage').objectStore('firebaseLocalStorage').getAll();
g.onsuccess=()=>{const u=g.result.find(x=>String(x.fbase_key).startsWith('firebase:authUser'));NMBridge.user(u&&u.value?JSON.stringify({uid:u.value.uid,email:u.value.email||'',name:u.value.displayName||'',apiKey:u.value.apiKey,refresh:(u.value.stsTokenManager||{}).refreshToken||''}):'null')};
g.onerror=()=>NMBridge.user('null')}catch(e){NMBridge.user('null')}}}catch(e){NMBridge.user('null')}})()"""

fun parseUser(json: String?): User? = try {
    if (json == null || json == "null") null else JSONObject(json).let {
        if (it.optString("uid").isBlank()) null else User(it.getString("uid"), it.optString("email"), it.optString("name"), it.optString("apiKey"), it.optString("refresh"))
    }
} catch (e: Exception) { null }

/** Is this address Google's or Apple's sign-in page? Those refuse to run inside an app, so they open in the real browser. */
fun providerFor(url: String): String? = when {
    Regex("providerId=google\\.com|accounts\\.google\\.com").containsMatchIn(url) -> "google"
    Regex("providerId=apple\\.com|appleid\\.apple\\.com").containsMatchIn(url) -> "apple"
    else -> null
}

/** Turns the needsme://auth link from the browser into the stored Firebase user. Returns the JS to run, or null if the link is wrong or old. */
fun userFromLink(link: Uri, expectedState: String?, startedAt: Long, now: Long = System.currentTimeMillis()): JSONObject? {
    val st = link.getQueryParameter("state"); val blob = link.fragment
    if (expectedState == null || st != expectedState || now - startedAt > 10 * 60 * 1000 || blob.isNullOrEmpty()) return null
    return try {
        val v = JSONObject(String(Base64.decode(blob, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        if (v.optString("uid").isBlank() || !v.has("stsTokenManager") || v.optString("apiKey").isBlank()) null else v
    } catch (e: Exception) { null }
}

class AuthBridge(private val onUser: (String) -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    @JavascriptInterface fun user(json: String) { h.post { onUser(json) } }
}

/** The Needs Me sign-in (Apple, Google, email) is the real web app, kept signed in on this phone until log out. */
class AuthHost(private val activity: Activity, private val onUser: (User?) -> Unit) {
    var pendingState: String? = null; var pendingAt = 0L
    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        addJavascriptInterface(AuthBridge { onUser(parseUser(it)) }, "NMBridge")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = route(r.url.toString())
            override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript(READ_USER_JS, null) }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: android.os.Message): Boolean {
                val tmp = WebView(activity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(x: WebView, r: WebResourceRequest): Boolean = route(r.url.toString()).also { x.destroy() }
                    override fun onPageStarted(x: WebView, url: String?, b: android.graphics.Bitmap?) { if (url != null && route(url)) x.stopLoading() }
                }
                (m.obj as WebView.WebViewTransport).webView = tmp; m.sendToTarget(); return true
            }
        }
    }
    private val poll = object : Runnable { override fun run() { web.evaluateJavascript(READ_USER_JS, null); web.postDelayed(this, 3000) } }
    fun start() { if (web.url == null) web.loadUrl(APP_URL); web.removeCallbacks(poll); web.postDelayed(poll, 3000) }
    fun stop() { web.removeCallbacks(poll) }

    private fun route(url: String): Boolean {
        val p = providerFor(url) ?: return false
        startBrowser(p); return true
    }
    fun startBrowser(provider: String) {
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        pendingState = b.joinToString("") { "%02x".format(it) }; pendingAt = System.currentTimeMillis()
        val u = Uri.parse(BRIDGE_URL).buildUpon().appendQueryParameter("state", pendingState).appendQueryParameter("p", provider).build()
        CustomTabsIntent.Builder().build().launchUrl(activity, u)
    }
    fun finish(link: Uri) {
        val v = userFromLink(link, pendingState, pendingAt) ?: return
        pendingState = null
        val js = """(async()=>{const v=${v};await new Promise((res,rej)=>{const q=indexedDB.open('firebaseLocalStorageDb');
q.onupgradeneeded=()=>q.result.createObjectStore('firebaseLocalStorage',{keyPath:'fbase_key'});q.onerror=()=>rej(q.error);
q.onsuccess=()=>{const t=q.result.transaction('firebaseLocalStorage','readwrite');t.objectStore('firebaseLocalStorage').put({fbase_key:'firebase:authUser:'+v.apiKey+':[DEFAULT]',value:v});t.oncomplete=res;t.onerror=()=>rej(t.error)}});NMBridge.user(JSON.stringify({uid:v.uid,email:v.email||'',name:v.displayName||'',apiKey:v.apiKey,refresh:(v.stsTokenManager||{}).refreshToken||''}))})()"""
        web.evaluateJavascript(js, null)
    }
    fun logout(done: () -> Unit) {
        web.evaluateJavascript("(async()=>{try{const d=await indexedDB.databases();for(const x of d)indexedDB.deleteDatabase(x.name);localStorage.clear();sessionStorage.clear()}catch(e){}})()", null)
        CookieManager.getInstance().removeAllCookies { web.postDelayed({ web.loadUrl(APP_URL); done() }, 400) }
    }
}
