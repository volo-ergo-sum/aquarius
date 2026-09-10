package dev.volo.aquarius;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole app: one activity, one {@link GeckoSession}, one site.
 *
 * Deliberately not a browser. There is no address bar, no tabs and no history
 * UI. The thing being replaced - com.google.android.apps.bard - is a 5 MB stub
 * that declares no INTERNET permission and hands off to
 * com.google.android.googlequicksearchbox, so the app you tap is not the app
 * that renders anything. This one renders it itself.
 *
 * What that buys is independence, not efficiency. Measured on a Pixel 9a
 * running Android 17, both apps up and settled at the same moment, this uses
 * MORE memory than the pair it replaces: 538 MiB PSS against 480 MiB, across a
 * similar number of processes. An engine that maps a 145 MiB libxul.so into
 * every content process is not going to win that comparison, and any figure
 * suggesting otherwise is RSS on one side and PSS on the other.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Aquarius";

    private static final String START_URL = "https://gemini.google.com/app";

    /**
     * Hosts the session is allowed to navigate to. Anything else is handed to
     * the system browser.
     *
     * Matched as {@code host.equals(d) || host.endsWith("." + d)}, so these are
     * eTLD+1 entries and cover every subdomain. Three of the four are NOT under
     * google.com - static assets, avatars and generated images, and the backend
     * APIs each live on their own registrable domain, and a rule that only
     * allowed *.google.com would drop all of them.
     *
     * Kept loose on purpose. This is a "should this open here or in a browser"
     * rule, not a security boundary: sign-in alone walks through a redirect
     * chain nobody should have to enumerate, and over-tightening it breaks
     * login rather than protecting anything.
     */
    private static final String[] ALLOWED_HOSTS = {
            "google.com",
            "gstatic.com",
            "googleusercontent.com",
            "googleapis.com",
    };

    /**
     * How long the splash may hold waiting for the page's first paint.
     *
     * 3s, not longer: past that the app has stopped feeling like it is starting
     * and started feeling like it has hung, and the page behind will keep
     * loading either way.
     */
    /**
     * How long the loading animation may cover the page before giving up.
     *
     * onFirstContentfulPaint never arrives if the load fails outright, and an
     * overlay with no other exit is an app that never starts.
     */
    private static final long LOADING_TIMEOUT_MS = 8000L;

    private GeckoSession session;
    private GeckoView geckoView;
    private boolean canGoBack;

    private android.widget.ImageView loading;
    private boolean firstPaintSeen;
    private boolean loadFinished;

    /** Downloads read a socket; that cannot happen on the UI thread. */
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();

    // Gecko asks for a file or a permission and wants the answer later, through
    // a GeckoResult or a Callback. The Activity Result APIs have to be
    // registered before the activity starts, so the launchers are permanent and
    // these two fields carry the in-flight request across the round trip. One at
    // a time is all the page ever asks for.
    @Nullable private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFileResult;
    @Nullable private GeckoSession.PromptDelegate.FilePrompt pendingFilePrompt;
    @Nullable private GeckoSession.PermissionDelegate.Callback pendingPermissionCallback;

    private ActivityResultLauncher<String[]> filePicker;
    private ActivityResultLauncher<String[]> multiFilePicker;
    private ActivityResultLauncher<String[]> permissionRequest;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Before super.onCreate: the compat library swaps the launch theme out
        // for postSplashScreenTheme here, and doing it later means the splash
        // theme is still in force when the window is created.
        androidx.core.splashscreen.SplashScreen splash =
                androidx.core.splashscreen.SplashScreen.installSplashScreen(this);

        // The platform splash is left to dismiss itself as soon as this
        // activity draws. Holding it was the obvious idea and the wrong one:
        // its icon animation is capped at 1000ms, so a two-second hold is two
        // seconds of a frozen picture, and an unbounded animation makes it draw
        // no icon at all. The loading view below carries the wait instead,
        // where nothing caps anything.
        //
        // splash is still needed for its side effect - installSplashScreen swaps
        // the launch theme for postSplashScreenTheme.
        if (splash == null) {
            throw new IllegalStateException("unreachable");
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        geckoView = findViewById(R.id.gecko);
        loading = findViewById(R.id.loading);
        applyWindowInsets(findViewById(R.id.root));
        startLoadingAnimation();

        registerLaunchers();

        // Paint the page's own background until Gecko has something to show,
        // rather than letting a white frame flash past on a dark page.
        geckoView.coverUntilFirstPaint(
                androidx.core.content.ContextCompat.getColor(this, R.color.page_background));

        session = newSession();
        session.open(AquariusRuntime.get(this));
        geckoView.setSession(session);
        session.loadUri(START_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (canGoBack) {
                    session.goBack();
                } else {
                    // Bottom of the history stack: this is the conversation
                    // list, so back means leave. finish() rather than
                    // moveTaskToBack() so the next launch is a fresh load
                    // rather than a stale page.
                    finish();
                }
            }
        });
    }

    /** A session with this app's delegates and settings already attached. */
    private GeckoSession newSession() {
        GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder()
                // Tell the page it is running as an installed app rather than in
                // a browser tab. Gemini ships a web app manifest, so this is a
                // signal it may already be styled for: everything behind a
                // "@media (display-mode: standalone)" rule switches over, and
                // nothing about it is a hack - it is the same thing the page
                // sees when Chrome installs it to the home screen.
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
                // No userAgentOverride and no userAgentMode. The default is
                // byte-identical to Firefox for Android, and that is the entire
                // reason this app can sign in at all: Google blocks browsers
                // that are "embedded in a different application", and every
                // signal it uses to spot one - the "; wv)" token, the
                // "Android WebView" Sec-CH-UA brand, the X-Requested-With
                // header - is a WebView thing that Gecko does not emit.
                // Touching the UA here would be trading a verified-working
                // state for a guess.
                .build());

        s.setProgressDelegate(progressDelegate);
        s.setNavigationDelegate(navigationDelegate);
        s.setPermissionDelegate(permissionDelegate);
        s.setPromptDelegate(promptDelegate);
        s.setContentDelegate(contentDelegate);
        return s;
    }

    @Override
    protected void onDestroy() {
        // Unconditional. The runtime is a process-wide singleton and outlives
        // the activity, but the session is this activity's and holds a content
        // process slot, so anything that destroys the activity has to release
        // it - not just the finishing case. Gating on isChangingConfigurations()
        // instead would leak just as surely: it is true precisely when a new
        // activity is about to open a session of its own.
        if (session != null) {
            geckoView.releaseSession();
            session.close();
        }
        downloads.shutdown();
        super.onDestroy();
    }

    /**
     * Keeps the page out from under the system bars and off the keyboard.
     *
     * Two separate things go wrong without this, and both were reported from
     * the device rather than guessed at.
     *
     * targetSdk 35 and up cannot opt out of edge-to-edge, so the window is laid
     * out behind the status bar. A native app insets its own toolbar and looks
     * fine; a web page has no idea any of this is happening, so Gemini's header
     * - the model picker, the menu, the sign-in button - renders underneath the
     * status bar and cannot be tapped at all. Padding the container moves the
     * page's viewport down instead, which is the only lever available from out
     * here: the page cannot be asked to inset itself.
     *
     * The same enforcement is why android:windowSoftInputMode="adjustResize"
     * stopped resizing anything. The keyboard now arrives as an ime() inset and
     * nothing else, so the bottom padding has to carry it, otherwise the
     * composer stays under the keyboard.
     *
     * The bottom is deliberately NOT padded for the navigation bar - only for
     * the keyboard. Padding it left a black strip along the bottom edge, and
     * Gemini's page does not end in black: it fades into a blue gradient, so the
     * strip showed up as a hard line where the gradient stopped. The page now
     * runs to the very bottom of the screen and the gesture bar floats over it,
     * which is what the native app does. The composer has enough margin of its
     * own to stay clear of the bar.
     *
     * The ime inset already includes the navigation bar when the keyboard is up,
     * so this is not a case where the two need adding or max()ing - taking the
     * keyboard's figure alone lands the viewport exactly on the keyboard's top
     * edge.
     */
    private static void applyWindowInsets(@NonNull android.view.View container) {
        ViewCompat.setOnApplyWindowInsetsListener(container, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, ime.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /**
     * Runs the wave animation over the page until the page has something to
     * show.
     *
     * An AnimatedVectorDrawable in a plain ImageView, not the platform splash:
     * the splash caps its icon animation at a second, and the page takes two to
     * three to paint on this device. This one loops for as long as it is needed.
     */
    private void startLoadingAnimation() {
        android.graphics.drawable.Drawable d = loading.getDrawable();
        if (d instanceof android.graphics.drawable.Animatable) {
            ((android.graphics.drawable.Animatable) d).start();
        }
        loading.postDelayed(this::hideLoading, LOADING_TIMEOUT_MS);
    }

    /**
     * Uncover only once the page has both painted and finished loading.
     *
     * Gemini is a single-page app: the document stops loading well before the
     * interface is on screen, and the first paint is of an empty background. One
     * signal alone leaves a gap; requiring both lands close enough to the
     * interface appearing that nothing blank shows through.
     */
    private void maybeHideLoading() {
        if (firstPaintSeen && loadFinished) {
            hideLoading();
        }
    }

    /** Idempotent: several callers race for it and any of them may win. */
    private void hideLoading() {
        if (loading == null || loading.getVisibility() != android.view.View.VISIBLE) {
            return;
        }
        loading.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    loading.setVisibility(android.view.View.GONE);
                    android.graphics.drawable.Drawable d = loading.getDrawable();
                    if (d instanceof android.graphics.drawable.Animatable) {
                        // The loop never ends on its own; left running it would
                        // keep waking the choreographer behind a hidden view.
                        ((android.graphics.drawable.Animatable) d).stop();
                    }
                })
                .start();
    }

    /**
     * Puts the session back after Gecko's content process goes away.
     *
     * Without this the window keeps showing whatever was on screen when the
     * process died and never updates again - there is no error, no blank, just
     * an app that has quietly stopped responding, and the only way out is to
     * swipe it from Recents. A dead session cannot be reused, so it is closed
     * and a new one is opened in its place.
     */
    private void recoverSession(@NonNull String reason) {
        Log.w(TAG, reason + "; reopening session");
        if (session != null) {
            geckoView.releaseSession();
            session.close();
        }
        session = newSession();
        session.open(AquariusRuntime.get(this));
        geckoView.setSession(session);
        firstPaintSeen = false;
        loadFinished = false;
        session.loadUri(START_URL);
    }

    private void registerLaunchers() {
        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> completeFilePrompt(uri == null ? null : new Uri[]{uri}));

        multiFilePicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> completeFilePrompt(
                        uris == null || uris.isEmpty() ? null : uris.toArray(new Uri[0])));

        permissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants -> {
                    GeckoSession.PermissionDelegate.Callback cb = pendingPermissionCallback;
                    pendingPermissionCallback = null;
                    if (cb == null) {
                        return;
                    }
                    // Gecko has one yes/no for the whole batch, so a partial
                    // grant is a no. It only ever asks for one at a time here
                    // (RECORD_AUDIO, or CAMERA) so this is not lossy in
                    // practice.
                    boolean all = !grants.isEmpty() && !grants.containsValue(Boolean.FALSE);
                    if (all) {
                        cb.grant();
                    } else {
                        cb.reject();
                    }
                });
    }

    private final GeckoSession.ProgressDelegate progressDelegate =
            new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStop(@NonNull GeckoSession s, boolean success) {
                    loadFinished = true;
                    maybeHideLoading();
                }
            };

    // ------------------------------------------------------------ navigation

    private static boolean isAllowed(@Nullable String uriString) {
        if (uriString == null) {
            return false;
        }
        Uri uri = Uri.parse(uriString);
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        // about:, blob:, data: and javascript: are the page talking to itself.
        // Handing those to an ACTION_VIEW intent would at best do nothing and at
        // worst leak page content into another app.
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return true;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private void openExternally(@Nullable String uri) {
        if (uri == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            // Without NEW_TASK the browser would land inside this app's task and
            // the back stack would mix a browser into a single-purpose app.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private final GeckoSession.NavigationDelegate navigationDelegate =
            new GeckoSession.NavigationDelegate() {
                @Override
                public GeckoResult<AllowOrDeny> onLoadRequest(
                        @NonNull GeckoSession s, @NonNull LoadRequest request) {
                    // Redirects are waved through whatever their host.
                    //
                    // A redirect is the continuation of a navigation that was
                    // already allowed, and sign-in is a chain of them. Google
                    // Workspace and school accounts bounce through an identity
                    // provider on the organisation's own domain, so filtering
                    // redirects by host ejects the user to the system browser
                    // mid-login and the sign-in never completes. The allowlist
                    // is a "does this belong in this window" rule, not a
                    // security boundary, and applying it here bought nothing.
                    if (request.isRedirect || isAllowed(request.uri)) {
                        return GeckoResult.allow();
                    }
                    openExternally(request.uri);
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }

                @Override
                public void onCanGoBack(@NonNull GeckoSession s, boolean value) {
                    canGoBack = value;
                }

                @Override
                public GeckoResult<GeckoSession> onNewSession(
                        @NonNull GeckoSession s, @NonNull String uri) {
                    // target="_blank" and window.open(). This app has no second
                    // session to give, and a citation opening in a hidden tab
                    // that never renders would just look broken - so it goes to
                    // the browser. Returning null tells Gecko no session was
                    // created, which cancels the load rather than failing it.
                    openExternally(uri);
                    return null;
                }
            };

    // ----------------------------------------------------------- permissions

    private final GeckoSession.PermissionDelegate permissionDelegate =
            new GeckoSession.PermissionDelegate() {
                @Override
                public void onAndroidPermissionsRequest(
                        @NonNull GeckoSession s,
                        @Nullable String[] permissions,
                        @NonNull Callback callback) {
                    if (permissions == null || permissions.length == 0) {
                        callback.grant();
                        return;
                    }
                    if (pendingPermissionCallback != null) {
                        // A second request while one is in flight would orphan
                        // the first callback and leave Gecko waiting forever.
                        callback.reject();
                        return;
                    }
                    pendingPermissionCallback = callback;
                    permissionRequest.launch(permissions);
                }

                @Override
                public void onMediaPermissionRequest(
                        @NonNull GeckoSession s,
                        @NonNull String uri,
                        @Nullable MediaSource[] video,
                        @Nullable MediaSource[] audio,
                        @NonNull MediaCallback callback) {
                    if (!isAllowed(uri)) {
                        callback.reject();
                        return;
                    }
                    // Which device to use is a question this app has no UI to
                    // ask, and Gecko puts the sensible default first: the front
                    // camera and the primary mic. Voice input on Gemini is a
                    // raw getUserMedia stream the page encodes itself, so this
                    // is the path dictation actually takes - not the Web Speech
                    // API, which Gecko does not implement.
                    MediaSource v = (video != null && video.length > 0) ? video[0] : null;
                    MediaSource a = (audio != null && audio.length > 0) ? audio[0] : null;
                    callback.grant(v, a);
                }

                @Override
                public GeckoResult<Integer> onContentPermissionRequest(
                        @NonNull GeckoSession s, @NonNull ContentPermission perm) {
                    // Notifications, geolocation, persistent storage and the
                    // rest. Nothing here needs them, and a prompt this app
                    // cannot render would hang the page.
                    return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                }
            };

    // ---------------------------------------------------------- file uploads

    private void completeFilePrompt(@Nullable Uri[] uris) {
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = pendingFileResult;
        GeckoSession.PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        pendingFileResult = null;
        pendingFilePrompt = null;
        if (result == null || prompt == null) {
            return;
        }
        if (uris == null || uris.length == 0) {
            result.complete(prompt.dismiss());
            return;
        }
        // confirm() takes a Context because it has to read the content:// Uri
        // through this app's ContentResolver - the SAF grant is ours, not
        // Gecko's, so the file has to be handed over rather than pointed at.
        result.complete(prompt.confirm(MainActivity.this, uris));
    }

    private final GeckoSession.PromptDelegate promptDelegate =
            new GeckoSession.PromptDelegate() {
                @Override
                public GeckoResult<PromptResponse> onFilePrompt(
                        @NonNull GeckoSession s, @NonNull FilePrompt prompt) {
                    if (pendingFileResult != null) {
                        return GeckoResult.fromValue(prompt.dismiss());
                    }
                    String[] mimeTypes = (prompt.mimeTypes == null || prompt.mimeTypes.length == 0)
                            ? new String[]{"*/*"}
                            : prompt.mimeTypes;

                    GeckoResult<PromptResponse> result = new GeckoResult<>();
                    pendingFileResult = result;
                    pendingFilePrompt = prompt;
                    try {
                        if (prompt.type == FilePrompt.Type.MULTIPLE) {
                            multiFilePicker.launch(mimeTypes);
                        } else {
                            filePicker.launch(mimeTypes);
                        }
                    } catch (ActivityNotFoundException e) {
                        pendingFileResult = null;
                        pendingFilePrompt = null;
                        return GeckoResult.fromValue(prompt.dismiss());
                    }
                    return result;
                }
            };

    // ------------------------------------------------------------- downloads

    private final GeckoSession.ContentDelegate contentDelegate =
            new GeckoSession.ContentDelegate() {
                @Override
                public void onFirstContentfulPaint(@NonNull GeckoSession s) {
                    // NOT the moment to uncover. Measured on device: this fires
                    // when Gemini paints its black background, and the interface
                    // itself arrives about 800ms later - so hiding here traded
                    // the animation for 800ms of black screen. It is only useful
                    // as a floor: whatever else happens, the page is at least
                    // alive from here.
                    firstPaintSeen = true;
                    maybeHideLoading();
                }

                @Override
                public void onCrash(@NonNull GeckoSession s) {
                    recoverSession("content process crashed");
                }

                @Override
                public void onKill(@NonNull GeckoSession s) {
                    // Killed for memory rather than crashed - routine when this
                    // app has been in the background a while.
                    recoverSession("content process killed");
                }

                @Override
                public void onExternalResponse(@NonNull GeckoSession s, @NonNull WebResponse response) {
                    // Everything the page cannot render itself arrives here,
                    // including blob: and data: URLs - which is why this does
                    // not go through DownloadManager. DownloadManager only
                    // speaks http(s), and generated images come out of Gemini
                    // as blobs.
                    downloads.execute(() -> saveToDownloads(response));
                }
            };

    private void saveToDownloads(WebResponse response) {
        String name = filenameFor(response);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Aquarius");
        String type = response.headers == null ? null : headerOf(response.headers, "Content-Type");
        if (type != null) {
            // Strip any "; charset=..." - MediaStore wants the bare type.
            int semi = type.indexOf(';');
            values.put(MediaStore.Downloads.MIME_TYPE, semi < 0 ? type.trim() : type.substring(0, semi).trim());
        }
        // IS_PENDING keeps the half-written file out of the gallery and every
        // other MediaStore reader until the bytes are all there.
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            Log.w(TAG, "MediaStore refused an entry for " + name);
            toast(getString(R.string.save_failed));
            return;
        }

        try (InputStream in = response.body; OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) {
                throw new java.io.IOException("no output stream for " + item);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + name, e);
            resolver.delete(item, null, null);
            toast(getString(R.string.save_failed));
            return;
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        toast(getString(R.string.saved_as, name));
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Nullable
    private static String headerOf(@NonNull Map<String, String> headers, @NonNull String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String filenameFor(WebResponse response) {
        String disposition = response.headers == null
                ? null : headerOf(response.headers, "Content-Disposition");
        if (disposition != null) {
            String name = filenameFromDisposition(disposition);
            if (name != null) {
                return name;
            }
        }
        // Fall back to the last path segment. blob: URLs have a UUID there,
        // which is ugly but unique and beats overwriting one file forever.
        String last = Uri.parse(response.uri).getLastPathSegment();
        if (last != null && !last.isEmpty()) {
            return sanitise(last);
        }
        return "aquarius-download";
    }

    @Nullable
    private static String filenameFromDisposition(@NonNull String disposition) {
        // filename*=UTF-8''... wins over plain filename= when both are present,
        // which is the only way a non-ASCII name survives the header.
        int star = disposition.toLowerCase(Locale.ROOT).indexOf("filename*=");
        if (star >= 0) {
            String value = disposition.substring(star + "filename*=".length()).trim();
            int quote = value.indexOf("''");
            if (quote >= 0) {
                value = value.substring(quote + 2);
            }
            value = trimTo(value, ';');
            try {
                return sanitise(java.net.URLDecoder.decode(value, "UTF-8"));
            } catch (Exception ignored) {
                // Fall through to the plain form.
            }
        }
        int plain = disposition.toLowerCase(Locale.ROOT).indexOf("filename=");
        if (plain >= 0) {
            String value = trimTo(disposition.substring(plain + "filename=".length()).trim(), ';');
            value = value.replace("\"", "").trim();
            if (!value.isEmpty()) {
                return sanitise(value);
            }
        }
        return null;
    }

    private static String trimTo(String value, char stop) {
        int at = value.indexOf(stop);
        return at < 0 ? value.trim() : value.substring(0, at).trim();
    }

    /** MediaStore rejects a DISPLAY_NAME containing a path separator. */
    private static String sanitise(String name) {
        String cleaned = name.replaceAll("[/\\\\]", "_").trim();
        return cleaned.isEmpty() ? "aquarius-download" : cleaned;
    }
}
