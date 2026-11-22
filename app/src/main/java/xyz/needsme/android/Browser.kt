package xyz.needsme.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Each saved account gets its own private browser profile, so logins never mix (like profiles in Chrome). */
object Profiles {
    fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    fun delete(name: String) { if (supported()) try { ProfileStore.getInstance().deleteProfile(name) } catch (_: Exception) {} }
}

class AccountWeb(val activity: Activity, val account: Account, private val pickFile: (ValueCallback<Array<Uri>>, Intent) -> Unit) {
    @SuppressLint("SetJavaScriptEnabled")
    val web = WebView(activity).apply {
        if (Profiles.supported()) WebViewCompat.setProfile(this, account.profile) // must come before the first load
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true); settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "http" || u.scheme == "https") return false
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: android.os.Message): Boolean {
                val tmp = WebView(activity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(x: WebView, r: WebResourceRequest): Boolean { v.loadUrl(r.url.toString()); x.destroy(); return true }
                }
                (m.obj as WebView.WebViewTransport).webView = tmp; m.sendToTarget(); return true
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                pickFile(cb, p.createIntent()); return true
            }
        }
        loadUrl(account.url)
    }
}

/** Hosts one WebView at a time inside Compose without recreating it, so every tab keeps its place. */
fun FrameLayout.show(v: android.view.View) {
    if (childCount == 1 && getChildAt(0) === v) return
    removeAllViews(); (v.parent as? ViewGroup)?.removeView(v)
    addView(v, FrameLayout.LayoutParams(-1, -1))
}
