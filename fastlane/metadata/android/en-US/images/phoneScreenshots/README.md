Screenshots go here, named `1.png`, `2.png`, and so on.

There are none yet, and rather than ship a placeholder: every screenshot
taken during development showed a real signed-in account — a name, a
conversation, thumbnails of the user's own media. None of that belongs in
a public repository, and a screenshot doctored to hide it is worse than
none at all.

What is wanted is two, both from a profile with nothing personal in it:

1. The app on `gemini.google.com`, signed out, showing that the page is
   the whole UI.
2. The same, with `com.google.android.googlequicksearchbox` disabled —
   the claim the README leads with, visible rather than asserted.

To get a signed-out capture without losing a real session, move the
profile aside and put it back afterwards:

    adb shell am force-stop dev.volo.aquarius
    adb shell su -c 'mv /data/data/dev.volo.aquarius/files/mozilla /data/local/tmp/aq'
    # launch, capture
    adb shell am force-stop dev.volo.aquarius
    adb shell su -c 'mv /data/local/tmp/aq /data/data/dev.volo.aquarius/files/mozilla'
    adb shell su -c 'rm -f /data/data/dev.volo.aquarius/files/mozilla/*.default/lock \
        /data/data/dev.volo.aquarius/files/mozilla/*.default/.parentlock'
    adb shell su -c 'chcon -R $(stat -c %C /data/data/dev.volo.aquarius/files) \
        /data/data/dev.volo.aquarius/files/mozilla'

The lock removal and the SELinux relabel are both required. Skip either and
Gecko fails to take the profile lock, the content process never starts, and
the app shows a black window with no error.
