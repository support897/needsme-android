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
        settings.setSupportMultipleWindows(true); settings.javaScriptCanOpenWindowsAutomatically = true; settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "http" || u.scheme == "https") return false
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            // Item 6: a pop-up or "open in new window" link gets its own real window on top, in the same saved login.
            // The page that opened it stays connected to it (window.opener), so Google, Microsoft and Apple sign-in pop-ups work.
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: android.os.Message): Boolean = PopupWindow.open(activity, account.profile, m, pickFile)
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

/** A real pop-up window: same private profile as its account, full screen on top, closes when the page closes it or on Back/Close. */
object PopupWindow {
    @SuppressLint("SetJavaScriptEnabled")
    fun open(activity: Activity, profile: String?, m: android.os.Message, pickFile: (ValueCallback<Array<Uri>>, Intent) -> Unit): Boolean {
        val child = WebView(activity)
        if (profile != null && Profiles.supported()) try { WebViewCompat.setProfile(child, profile) } catch (_: Exception) {}
        child.settings.javaScriptEnabled = true; child.settings.domStorageEnabled = true
        child.settings.javaScriptCanOpenWindowsAutomatically = true; child.settings.setSupportMultipleWindows(true)
        val box = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.WHITE) }
        val dialog = android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val bar = android.widget.Button(activity).apply { text = "Close window"; isAllCaps = false; setOnClickListener { dialog.dismiss() } }
        box.addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2))
        box.addView(child, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(box)
        dialog.setOnDismissListener { try { (child.parent as? ViewGroup)?.removeView(child); child.destroy() } catch (_: Exception) {} }
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(x: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "http" || u.scheme == "https") return false
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(w: WebView) { dialog.dismiss() }
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, mm: android.os.Message): Boolean = open(activity, profile, mm, pickFile)
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean { pickFile(cb, p.createIntent()); return true }
        }
        (m.obj as WebView.WebViewTransport).webView = child; m.sendToTarget()
        dialog.show()
        return true
    }
}
