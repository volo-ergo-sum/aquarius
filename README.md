# Aquarius

**Aquarius is an independent, unofficial client.** It is not affiliated with, endorsed by, or sponsored by Google. Gemini and Google are trademarks of Google LLC. Aquarius loads the public `gemini.google.com` website in Mozilla's GeckoView engine — the same way any browser opens the same page. The name, the icon, and all of the code here belong to this project; no Google branding or artwork is used.

---

## 概要（日本語）

Android 純正の Gemini アプリ（`com.google.android.apps.bard`）は、実体としては 5 MB のランチャーです。INTERNET 権限すら宣言しておらず、タップすると前面に来るのは別パッケージ — Google アプリ（GSA、インストール実測 365 MiB）のアクティビティです。

Aquarius は `gemini.google.com/app` を GeckoView（Firefox のエンジン）で開くだけの、1 画面・1 サイトの独立クライアントです。ログインのセッションはこのアプリの UID サンドボックス内だけに存在し、端末の Google アカウントとも、クラウドバックアップとも繋がりません。

**正直に言うべきこと**: これはメモリ削減にはなりません。実測すると Aquarius のほうが**多く**使います（同時刻の PSS で 538 MiB 対 480 MiB）。APK も約 200 MB あります。得られるのは独立性であって、軽さではありません。数字と測り方は下の "Where it loses" に全部書いてあります。

---

## What this is

One activity, one `GeckoSession`, one site. No address bar, no tabs, no history UI. It opens `https://gemini.google.com/app` and gets out of the way.

It is deliberately **not** registered as a browser — there is no `VIEW`/`BROWSABLE` intent filter — so it never appears in the "open with" dialog for links on your device.

## Why it exists

Not because the stock app is slow. Because of what it actually is.

All of the following was measured on a Pixel 9a running Android 17 (SDK 37) on 2026-09-11. Every command is included so you can re-run it yourself.

### The stock Gemini app is a launcher for a different app

```console
$ adb shell dumpsys package com.google.android.apps.bard | grep -A4 'requested permissions:'
    requested permissions:
      android.permission.GET_PACKAGE_SIZE
      android.permission.ACCESS_NETWORK_STATE
      android.permission.WAKE_LOCK
```

Three permissions, and **no `INTERNET`**. An app that talks to a language model over the network does not declare network access, because it does not do the talking.

```console
$ adb shell am start -W -n com.google.android.apps.bard/.shellapp.BardEntryPointActivity
Activity: com.google.android.googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity
```

You launch `com.google.android.apps.bard`; what comes to the foreground belongs to `com.google.android.googlequicksearchbox` — the Google app (GSA).

| | Stock path | Aquarius |
|---|---|---|
| App you tap | `com.google.android.apps.bard`, **5.0 MiB** (5,205,799 B across 4 splits) | `dev.volo.aquarius`, **209.0 MiB** |
| Permissions declared by that app | 3, no `INTERNET` | 4 (see table below) |
| What actually renders the UI | GSA, **364.8 MiB** installed (382,480,741 B across 6 splits) | this APK |
| Sign-in state lives in | the device's Google account | this app's UID sandbox |

Sizes came from `adb shell stat -c '%s %n' <path>` over every path from `adb shell pm path <pkg>`.

### Startup

Three cold starts each, force-stopped between runs:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| Aquarius | 333 ms | 419 ms | 302 ms |
| Stock Gemini | 414 ms | 514 ms | 624 ms |

`adb shell am start -W …`, reading `TotalTime`.

Read this modestly. It is a real but small difference, it is noisy, and `TotalTime` measures time to first frame, not time until the page is usable — after which both are still loading content. A fourth Aquarius run taken while the system was busy recovering from several force-stops measured **962 ms**, which is slower than any stock run. Startup is not the reason to use this.

## Where it loses

This is the section most READMEs leave out.

### Memory is worse, not better

Both apps launched, both left to settle, measured at the same moment:

| | processes | PSS | RSS |
|---|---|---|---|
| Aquarius | 5 | **538 MiB** | **1,133 MiB** |
| bard + GSA | 5 | 480 MiB | 856 MiB |

```console
$ adb shell dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p'
$ adb shell ps -A -o RSS,NAME
```

Aquarius uses roughly 12 % more PSS and 32 % more RSS than the thing it replaces. GeckoView runs a parent process, a GPU process, a crash helper, and one or more content processes, and maps a **145 MiB** `libxul.so` into them. That is where the memory goes.

If you have seen a much more flattering comparison for this app, it was almost certainly comparing Google's **RSS** against Aquarius's **PSS**. RSS counts every shared page once per process; PSS divides shared pages among the processes sharing them. For an engine that maps one enormous shared library into five processes, that difference is the entire result. Compare like with like and the advantage disappears.

### The APK is about 200 MB

219,128,070 bytes, of which `libxul.so` is 152,296,768 bytes — 70 % of the package. This is what a complete independent browser engine costs. `arm64-v8a` only; there is no other ABI in the build. Installing takes roughly a minute because the device has to verify and optimise all of it.

### Things it does not do

- **Gemini Live is not available.** It does not exist in the `gemini.google.com` web app, so wrapping the web app cannot provide it. Text, voice dictation, image and file upload are the web app's features and are what you get.
- **No share-target.** You cannot share text or an image into Aquarius from another app; there is no `SEND` intent filter.
- **It does not take the assistant role.** Long-pressing the power button or swiping from the corner still invokes whatever assistant you have configured.
- **Not a browser.** Links to other sites, and anything the page opens with `target="_blank"`, are handed to your system browser.
- **Google Workspace / school accounts (SAML SSO) are untested.** Redirects are no longer filtered by host — a sign-in that bounces through an identity provider on the organisation's own domain now stays in the app instead of being ejected to the system browser. That removes the reason it could not work; nobody has confirmed that it does. Tested sign-in here was a personal account with a password and 2FA.
- **Passkeys are unlikely to work.** The app sets no `GeckoRuntime.ActivityDelegate`, and it is not on the allowlist for Google's privileged FIDO2 API. Password plus 2FA works; this is how it was tested.
- **A content-process crash costs you the page you were on.** `onCrash`/`onKill` reopen the session and reload `gemini.google.com/app`, so the app recovers by itself rather than sitting on a dead window — but it lands on the conversation list, not back where you were.
- **Debug builds only.** There is no release `signingConfig`, so `assembleRelease` produces an unsigned APK that Android will refuse to install. The debug APK is `android:debuggable`; do not distribute it.

## The User-Agent question

Aquarius does not spoof anything. There is no `userAgentOverride` and no `userAgentMode` anywhere in the source — the session is built with a bare `GeckoSessionSettings.Builder()` and Gecko's default User-Agent, which is the same string Firefox for Android sends.

This matters because Google declines to sign you in from a browser "embedded in a different application", and the usual way to get around that is to lie in the UA string. Aquarius does not need to, and the distinction is worth being precise about:

- It is not a WebView with a rewritten UA. It is Gecko, a general-purpose engine that is not a WebView at all.
- Google's own help page lists Firefox among the supported browsers for `gemini.google.com`.
- Nothing here removes or alters an access-control mechanism. A different engine simply does not have the WebView-specific properties in the first place.

The practical consequence for you: the engine version is load-bearing rather than an implementation detail, which is why the GeckoView dependency is pinned to an exact build instead of a range.

## What "independent" means here

Three concrete things, not a slogan.

**The profile is inside this app's sandbox.** GeckoView creates it at `<files>/mozilla/<salt>.default/`, chosen by Gecko's own code with no Java API to move it. `cookies.sqlite` and `key4.db` — the whole signed-in session — live in this app's private data directory.

**GeckoView never reads `AccountManager`.** Verified against the shipped AAR:

```console
$ unzip -p geckoview-154.0.20260824154132.aar classes.jar > c.jar && unzip -q c.jar -d cls
$ grep -rl 'android/accounts' cls/ | wc -l
0
```

Zero references. Signing in offers no account picker; you type the address yourself, and the accounts already on the device are not offered and cannot leak in.

**Nothing is backed up off the device.** `android:allowBackup="false"`, plus `data_extraction_rules.xml` excluding `root`, `file`, `database`, `sharedpref` and `external` from *both* `cloud-backup` and `device-transfer`. On API 31+ those two channels are configured separately from the `allowBackup` flag, so both are set explicitly. Your session is not copied to a Google account's Drive and does not survive a device transfer. Firefox ships the same way.

## Permissions

Seven permissions after manifest merging. Verify with `aapt2 dump badging <apk> | grep uses-permission`. They are not equivalent, so they are grouped by what they can actually do:

**Runtime — you are asked, and both start denied**

| Permission | Why | Declared by |
|---|---|---|
| `RECORD_AUDIO` | Gemini's voice input is a raw `getUserMedia` stream the page encodes itself; Gecko asks the embedder for the Android permission | this app |
| `CAMERA` | camera capture from the page's file/image picker | this app |

```console
$ adb shell dumpsys package dev.volo.aquarius | grep -A3 'runtime permissions:'
      runtime permissions:
        android.permission.CAMERA: granted=false, …
        android.permission.RECORD_AUDIO: granted=false, …
```

**Normal — granted at install, no dialog**

| Permission | Declared by |
|---|---|
| `INTERNET` | this app *and* the GeckoView AAR |
| `ACCESS_NETWORK_STATE` | this app *and* the GeckoView AAR |
| `WAKE_LOCK` | GeckoView AAR |
| `MODIFY_AUDIO_SETTINGS` | GeckoView AAR |

**Signature, self-defined — grants nothing to anyone**

`dev.volo.aquarius.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — declared by the app itself at `protectionLevel="signature"`; it is how androidx keeps its dynamically-registered broadcast receivers unexported.

The GeckoView AAR's own declarations can be checked with `unzip -p geckoview-*.aar AndroidManifest.xml`.

## Building

Nothing here depends on the author's machine.

**Requirements**

- **JDK 17 or newer.** Gradle 9.5.0 and AGP 9.3.2 set the floor; the project has been built with OpenJDK 21.0.12. (`compileOptions` targets Java 17 as a *language level* — that is not the JDK requirement.)
- **Android SDK Platform 37.1.** Not 37.0 — `androidx.core` 1.19.0 requires the `.1` minor, which is why `build.gradle.kts` sets `compileSdk = 37` and `compileSdkMinor = 1` separately.
- An **arm64-v8a** device running **Android 12 (API 31)** or newer. The build produces no other ABI, so x86_64 emulators will not work.
- About 250 MB of free disk for the GeckoView artifact, and network access to `maven.mozilla.org` (GeckoView is published there and is not mirrored to Maven Central).

**Point Gradle at your SDK** — either export `ANDROID_HOME`, or create `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

**Build**

```console
$ ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (~200 MB).

The first build downloads roughly 230 MB of GeckoView. `gradle.properties` deliberately ships conservative memory settings (`-Xmx1536m`, `workers.max=2`, `parallel=false`); packaging ~200 MB of native libraries is memory-hungry, and on a machine without headroom the daemon gets OOM-killed and surfaces as a bare `exit 137` that never mentions memory. Raise them if your machine has room — they are a floor that works, not a recommendation.

**Install**

```console
$ adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Or use `./deploy.sh`, which builds first, then installs and launches. It takes `DEVICE=<serial|host:port>` when more than one device is connected, and an optional `EXPECT_SERIAL` guard so an IP address that got reassigned cannot put a build on the wrong phone.

## Third-party notices

Aquarius embeds Mozilla's **GeckoView** `154.0.20260824154132`, licensed under the **Mozilla Public License 2.0**. Complete source for that build is at:

<https://hg.mozilla.org/releases/mozilla-release/rev/8b532c2140db30c193436254a61ce964e7d2a121>

The MPL-covered binaries in the APK are under `lib/arm64-v8a/` (`libxul.so`, `libmozglue.so`, `libnss3.so` and others).

The APK also contains **LGPL-2.1** components: `liblgpllibs.so` (libSoundTouch) and `libmozavcodec.so` / `libmozavutil.so` (FFmpeg). These are unmodified shared libraries loaded dynamically by the engine.

Mozilla does not ship any license or notice file inside the GeckoView AAR (`unzip -l geckoview-*.aar | grep -ci 'licen\|notice'` → `0`), so these notices are supplied here. Gecko's own full license text is inside the APK at `assets/omni.ja` → `chrome/toolkit/content/global/license.html`, though this app has no UI to reach it.

If you redistribute a built APK, you carry these obligations. Reproduce this section with it.

Aquarius is built on Mozilla's open-source GeckoView technology. **Aquarius is not officially associated with Mozilla or its products.**

## License

[Apache License 2.0](LICENSE).

## Trademarks

"Gemini" and "Google" are trademarks of Google LLC. "Firefox" and "GeckoView" are trademarks of the Mozilla Foundation. This project is affiliated with neither, and merely loads the public `gemini.google.com` website in an independent browser engine.
