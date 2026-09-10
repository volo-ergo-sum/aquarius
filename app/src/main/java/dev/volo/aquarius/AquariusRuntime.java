package dev.volo.aquarius;

import android.content.Context;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Holds the one {@link GeckoRuntime} this process is allowed to have.
 *
 * GeckoView starts a content process and loads the engine the first time a
 * runtime is created, and creating a second one throws. The single session in
 * this app opens against this instance.
 *
 * The profile this runtime creates lives at
 * {@code <files>/mozilla/<8-char salt>.default/} and is chosen by Gecko's own
 * C++ code, not by anything on the Java side - there is no API to move it. That
 * is the entire independence story of this app: the cookie jar sits inside this
 * app's UID sandbox, and Gecko never reads AccountManager (the shipped
 * classes.jar contains zero references to android/accounts), so none of the
 * device's Google accounts can leak into it. Verified on 2026-09-11: signing in
 * through Gecko offered no account picker at all and the address had to be
 * typed by hand.
 */
public final class AquariusRuntime {

    private static GeckoRuntime sRuntime;

    private AquariusRuntime() {
    }

    public static synchronized GeckoRuntime get(Context context) {
        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    // Hands login forms to whatever autofill service the user
                    // has set, rather than this app keeping a password store of
                    // its own.
                    .loginAutofillEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(context.getApplicationContext(), settings);
        }
        return sRuntime;
    }
}
