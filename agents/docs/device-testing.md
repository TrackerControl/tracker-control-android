# Connected-device testing

Read this before any `adb` work. The two rules in AGENTS.md — never destroy
state without asking, and announce the VPN-consent side effect — are restated
here in full because they are the ones that cost a maintainer real data.

## Use the fdroid debug variant

Build and install **fdroid debug**, which installs as
`net.kollnig.missioncontrol.fdroid.test`.

```bash
./gradlew assembleFdroidDebug
adb install -r app/build/outputs/apk/fdroid/debug/TrackerControl-fdroidDebug-latest.apk
```

Not github debug: that installs as `net.kollnig.missioncontrol.test`, which is
also what a maintainer's own day-to-day dev build installs as. Testing against it
overwrites their real preferences, and enabling its VPN takes the consent slot
from whatever they were running. The flavours differ only in the update-check
API, so nothing is lost by using fdroid for device work. (Working on the
update-check itself is the exception — that needs github, and needs asking
first.)

## Never destroy state without asking

`adb uninstall`, `pm clear`, overwriting a preferences file, revoking a
permission — every one of these is irreversible and the device usually belongs to
someone who has real configuration on it. Ask first, naming the package and what
will be lost, and wait for a yes. This applies even when the target looks like a
throwaway you installed yourself: it is one mistaken package name away from
wiping the real install. Update in place with `adb install -r`, which preserves
app data, preferences, VPN consent, and test state, and for cleanup reset only
the specific state your test touched.

One more side effect worth announcing before you trigger it: enabling the VPN
calls `VpnService.prepare()`, which **revokes whatever VPN app currently holds
consent** — the maintainer's own build, or their real VPN. It will need
re-enabling afterwards.

## Driving the app without permission popups

Onboarding, the VPN consent dialog and the runtime permission prompts can all be
pre-satisfied from adb, so an agent never has to tap through them.

```bash
PKG=net.kollnig.missioncontrol.fdroid.test

# 1. Runtime permissions. -g grants everything the manifest declares, so revoke
#    whichever one you are actually testing (checkSelfPermission would lie).
adb install -r -g app/build/outputs/apk/fdroid/debug/TrackerControl-fdroidDebug-latest.apk
adb shell pm revoke $PKG android.permission.ACCESS_LOCAL_NETWORK

# 2. VPN consent. Makes VpnService.prepare() return null, which skips BOTH the
#    system ConfirmDialog and TrackerControl's own explainer before it.
adb shell appops set $PKG ACTIVATE_VPN allow

# 3. Onboarding. ActivityMain checks onboarding_version against
#    ActivityOnboarding.ONBOARDING_VERSION; seeding it lands you on the main
#    screen. This works before the first launch — run-as can create the file.
cat > /tmp/seed.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="onboarding_version" value="2" />
</map>
XML
adb push /tmp/seed.xml /data/local/tmp/seed.xml
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat /data/local/tmp/seed.xml > shared_prefs/${PKG}_preferences.xml'"

adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
```

(Push through `/data/local/tmp` rather than piping a heredoc into `adb shell` —
quoting survives the round trip intact.)

The VPN switch is at roughly `input tap 108 216` on a 1080×2400 screen; from
there the tunnel comes up with no dialogs at all. Verify with
`adb shell dumpsys connectivity | grep -o "VPN:net.kollnig[a-z.]*"`.

## Preferences, once seeded

- **Force-stop before editing.** A running process holds prefs in memory and
  will overwrite your file on exit: `adb shell am force-stop $PKG` first.
- **Merge, never replace.** Rewriting the whole file drops `onboarding_version`
  and drops you back into onboarding. Append inside `</map>` with `sed`, or read
  the file, edit, and write it back whole.
- **`adb uninstall` and `pm clear` wipe all of the above** — the seeded prefs,
  the appop, and the granted permissions — which is a second reason not to reach
  for them, on top of needing to ask first.

To check a notification, read it with
`adb shell dumpsys notification --noredact | grep -A6 '<your title>'` rather than
screenshotting the shade, which captures the user's private notifications.

## Signed release APKs are different

None of the above applies unchanged to a **signed release APK**: it installs as
`net.kollnig.missioncontrol` and is not debuggable, so `run-as` is refused and
onboarding cannot be seeded away — only `appops` and `pm grant` still work. The
release smoke-test procedure lives in
[docs/RELEASING.md](../../docs/RELEASING.md#smoke-test-the-signed-apk).
