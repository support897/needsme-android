# NeedsMe Ai for Android

All your AIs in one calm place. Sign in to each AI once, add more than one account per AI, and switch in one tap, like profiles on Instagram.

- Kotlin and Jetpack Compose, one screen, no extra frameworks
- Every account gets its own private browser profile (WebView multi-profile), so logins never mix
- Browser-style tabs: + opens a searchable AI picker, x closes a tab
- 44 AIs plus "any other AI by link" (assets/catalog.json, same list as the Mac app)
- Needs Me sign-in (Apple, Google, email) uses the real web app. Google and Apple open in your real browser and return through needsme://auth
- The list of AIs, nicknames and emoji sync to your Needs Me account. AI passwords and cookies never leave the phone

## Build
```
gradle assembleRelease      # signed with app/release.jks, or set NM_KEYSTORE and NM_KS_PASS
# From v2.3.1 there is ONE fixed release key: GitHub secrets NM_KEYSTORE_B64 + NM_KS_PASS, backup in Cloudflare R2 bucket needsme-private/android-signing/.
# Always sign with it, or phones cannot update (older v2.0-v2.3.0 were each signed with a different key).
gradle testDebugUnitTest
```
Requires JDK 17 and Android SDK 34.

Tested: 7 unit tests pass and a signed release APK builds. Not yet tested on a real phone or emulator.
