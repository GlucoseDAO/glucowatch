# Publishing outside F-Droid

Checked on 2026-09-25 against tag `v0.1.4` (commit `b067525`). Store rules change often, so
re-read the linked source before acting on any line here. The F-Droid rules for this repo are in
`AGENTS.md`. This file covers the other stores and how their requirements affect the F-Droid build.

## Summary

| Store | Installs onto the watch | Status |
|---|---|---|
| Google Play | Yes, from Play on the watch | Blocked by the login on the watch (WO-P6) and by `USE_EXACT_ALARM`. The privacy policy and listing assets are missing. |
| IzzyOnDroid | No. Users install with adb or Wear Installer, as with F-Droid | Technically ready. Read its policy on AI-written code first. |
| Obtainium | No. It installs only on the device it runs on | Works from the GitHub release with an APK filter. Of little use for a watch. |
| Samsung Galaxy Store | Galaxy Watch4 and later: China only | Not an option outside China. |

## Checked in this repo

- Built both modules in a fresh clone, as F-Droid does with a `subdir` recipe:
  `../gradlew assembleRelease` run inside `app/` and inside `watchface/`. Each produced one
  unsigned APK with versionCode 5. `:core:test` passes.
- `apksigcopier compare` confirms that the signed 0.1.4 APKs on the GitHub release match those
  builds. Builds started from the repository root do not match, because they record the commit.
- `fdroid scanner` 2.4.5 finds nothing in the v0.1.4 source (either subdir) or in either APK.
- The signed APKs on GitHub (0.1.2 and 0.1.4) have only a v3 signature, with certificate
  `1c92cbae…51e6d1`. Their signing block holds the v3 entry and verity padding and nothing else,
  so there is no AGP dependency-info block.
- The 0.1.4 face APK has no `classes.dex`. The 0.1.2 face APK has one, so do not upload it to a
  store that validates Watch Face Format.

## Store text and F-Droid's two packages

F-Droid makes a separate checkout for each package. It reads store text from these places, in
sorted path order. For text, `icon.png` and `featureGraphic.png`, the last one read wins:

- `fastlane/metadata/android/<locale>/` at the root, for both packages
- `<subdir>/fastlane/metadata/android/<locale>/`, only for the package whose recipe names that `subdir`
- `metadata/<locale>/` at the root
- Triple-T folders, `*/src/*/play/`
- `metadata/<package>/<locale>/` in fdroiddata, which overrides everything above

Screenshots from all of these are combined. A file with the same name replaces the earlier one,
and files with different names are all kept.

What this means for this repo:

- The face's F-Droid page currently shows the app's text, icon and three screenshots, because
  the only store text is at the root.
- A `watchface/fastlane/` directory would give the face its own text and icon, because
  `watchface/` sorts after `fastlane/`. The app's screenshots would still appear next to the face's.
- Moving the app's text to `app/fastlane/` and the face's to `watchface/fastlane/`, and leaving
  nothing at the root, gives each package only its own text.
  - fdroidserver reads images and changelogs from a subdir only since commit 092e68de
    (2026-09-11). Version 2.4.5 silently drops them.
  - fdroiddata CI runs fdroidserver master. The version that builds the published index is
    unconfirmed, so ask in the merge request before moving the files.
- A root `changelogs/<versionCode>.txt` applies to both packages, because they share versionCodes.
- IzzyOnDroid reads one fastlane location per app, which you set per app, and only its
  `phoneScreenshots`.

F-Droid also reads `images/featureGraphic.png`, `images/wearScreenshots/`, and
`changelogs/<versionCode>.txt` (500 characters at most). Adding them for Play does not break the
F-Droid build.

Keep the Play listing text out of every location above. The current `full_description.txt` gives
adb and F-Droid install steps, which are wrong on Play. Keep the Play text in Play Console, or in
a directory F-Droid does not scan, such as `store/google-play/<locale>/`, and point
`fastlane supply --metadata_path` at it.

## Google Play

### Blockers

**Login on the watch.** Wear OS quality requirement WO-P6 is required for Play submission. It
reads: "Your app must not ask the user to input a username or password directly on the Wear OS
device." Settings asks for the Dexcom Share username and password on the watch.

Google suggests three other ways to sign in:

- Credential Manager, which on Wear OS needs Google Play Services
- OAuth through the phone, which Dexcom Share does not offer
- a token sent over the Data Layer from a phone app, which needs Play Services and a phone app
  this project does not have

So every fix means building a phone app or adding a proprietary dependency. A proprietary
dependency must stay on a branch that is never tagged for F-Droid (see `AGENTS.md`).

Since the phone app (`phone/`, see `docs/phone-link.md`), the watch can copy the login from the
phone, or read everything through the phone, over Bluetooth without Play Services. A Play build
still has to remove the username and password fields on the watch, for example in a Play-only
build type, and the Play listing would then need the phone app to set up Dexcom Share.

**`USE_EXACT_ALARM`.** Play allows this permission only for alarm and timer apps, and for calendar
apps that show event notifications. It also requires a declaration in Play Console. A glucose
display is neither kind of app. `RefreshReceiver` already falls back to `setAndAllowWhileIdle`
when `canScheduleExactAlarms()` returns false. There are two ways to ship on Play:

- Declare `SCHEDULE_EXACT_ALARM` without `maxSdkVersion` in every build. On API 33 and later
  Android does not grant it by default, so the refresh is inexact until the user grants it.
- Keep `USE_EXACT_ALARM` for F-Droid and add a Play-only build type. For example, a
  `googlePlay` build type with `initWith(release)`, whose manifest in `app/src/googlePlay/`
  removes the permission with `tools:node="remove"`. F-Droid runs `assembleRelease`, which does
  not build other build types, so the F-Droid APK does not change.
  - Do not use a product flavor for this. With flavors, `assembleRelease` emits one APK per
    flavor, and the recipes expect exactly one.

### Watch face

- Add `<meta-data android:name="com.google.android.wearable.standalone" android:value="true" />`
  to the face manifest. Google's docs disagree on whether a WFF face needs it, but Google's own
  WFF samples declare it. The change alters the APK, so it goes out in the next version on every
  store.
- The face must contain no code. 0.1.3 meets that.
- Listing icon (WO-G4, required since 2026-07-15): a centered, circular watch face that touches
  the edges of the icon, with no text and no device frame.
- Screenshots (WO-G6): at least one, square (1:1), at least 384×384 (from the Play asset rules),
  showing only the face, with no device frame, mask or added text. A customizable face must show
  more than one variant.
- Each slot defaults to a GlucoWatch complication and falls back to empty. Say in the listing that
  the face needs the GlucoWatch app.

### Listing, both packages

- targetSdk 36 meets the rule in force from 2026-08-31 (Wear OS needs 35 or higher). There is no
  native code, so the 64-bit rule is met too.
- Upload Android App Bundles, built with `./gradlew :app:bundleRelease :watchface:bundleRelease`.
  Sign them with the upload key, not the release key.
- Privacy policy: Play Console needs a public URL (not a PDF, not geofenced), and the app must show
  a link to it or its text. The repo has neither yet. A `PRIVACY.md` and a link in Settings are
  both fine for F-Droid.
- Fill in the Data safety form and the Health apps declaration. Every app on Play must file the
  declaration.
- Both packages declare heart-rate access (`health.READ_HEART_RATE`, and `BODY_SENSORS` up to
  API 35) for the heart rate on the face and the glucose-all tile. Play treats that as health
  data: the Data safety form must list heart rate, read on the watch and not shared. Watch faces
  with body-sensor permissions and no health feature get rejected; here heart rate is shown to
  the user, which is the feature. F-Droid has no rule against it; the permission shows on the
  app's page.
- The description must:
  - say the app is "not a medical device and does not diagnose, treat, cure, or prevent any
    medical condition" (the current text has a shorter disclaimer);
  - tell users to consult a healthcare professional;
  - say it needs a Dexcom CGM with Share turned on.
- Keep "Dexcom" out of the title and the icon, use no Dexcom logo, and say the app is unofficial
  and not affiliated with Dexcom.
- Mention Wear OS and the complications in the description.
- Assets:
  - Icon: 512×512, 32-bit PNG with alpha. The current `icon.png` is 24-bit RGB.
  - Feature graphic: 1024×500, JPEG or 24-bit PNG. There is none yet.
  - Wear OS screenshots: square, with no device frame. The current screenshots are framed
    1080×1920 images and do not qualify.

### Signing

New Play apps default to a Google-generated signing key. Before the first open-testing or
production release, open Play app signing, choose to change the app signing key, and upload the
release key with PEPK. Otherwise the Play install is a different app from the F-Droid one and
cannot update it.

Play adds source-stamp entries to the APKs it serves, so their bytes differ from the GitHub APK.
Only the certificate has to match. After enrollment, the certificate Play shows must be
`1c92cbaecd95fdeef869584f636d02180db22f1e363912d1d096af013351e6d1`.

### Account

A personal developer account created after 2023-11-13 must run a closed test before it gets
production access: at least 12 testers, opted in for 14 days in a row. Expect to do this once for
each of the two packages.

Sources:
[Wear OS app quality](https://developer.android.com/docs/quality-guidelines/wear-app-quality),
[authentication on wearables](https://developer.android.com/training/wearables/apps/auth-wear),
[exact alarm policy](https://support.google.com/googleplay/android-developer/answer/16558241),
[target API](https://support.google.com/googleplay/android-developer/answer/11926878),
[preview assets](https://support.google.com/googleplay/android-developer/answer/9866151),
[icon specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications),
privacy and Data safety ([1](https://support.google.com/googleplay/android-developer/answer/10144311),
[2](https://support.google.com/googleplay/android-developer/answer/10787469)),
health apps ([1](https://support.google.com/googleplay/android-developer/answer/16679511),
[2](https://support.google.com/googleplay/android-developer/answer/14738291)),
[Play app signing](https://support.google.com/googleplay/android-developer/answer/9842756),
[testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465),
[WFF samples](https://github.com/android/wear-os-samples/tree/main/WatchFaceFormat).

## IzzyOnDroid

- It keeps apps that are also on F-Droid, as long as they stay well inside its limits (30 MB per
  app, three versions).
- It takes the APKs attached to the GitHub release for each tag. Each package gets its own
  `ApkMatch` regex. Match the watch app, watch face and optional phone app by their complete
  filename pattern; excluding `watchface` alone also matches the phone APK.
- APKs must be release-signed, not debuggable, and free of the AGP dependency-info block.
  - AGP writes that block only when AGP itself signs the APK. This build leaves the APK unsigned
    and `apksigner` signs it, so the block is absent.
  - If a `signingConfig` is ever added, also set `dependenciesInfo { includeInApk = false }`.
- Declare `NonFreeNet` with the same reason as on F-Droid.
- Its inclusion policy says "Vibe-coded apps will be rejected" and asks that the code be free of
  LLM output. Commits in this repo carry `Co-Authored-By` trailers for Claude and Cursor. Read the
  current policy and decide before applying. Hiding the trailers would break the same policy's
  transparency rule.
- There is no rule against apps that only run on a watch. Telewatch, for example, is listed.

Sources: [FAQ](https://izzyondroid.org/faq/),
[inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/),
[YAML metadata](https://izzyondroid.org/docs/general/YamlMetadata/),
[Fastlane](https://izzyondroid.org/docs/general/Fastlane/),
[APK scans](https://izzyondroid.org/about/security/ApkScans/).

## Obtainium

Obtainium follows the GitHub releases. Add the repo separately for each installed package and
set "Filter APKs by regular expression" to its exact filename pattern:

- Watch app: `^glucowatch-[0-9]+\.[0-9]+\.[0-9]+\.apk$`
- Watch face: `^glucowatch-watchface-[0-9]+\.[0-9]+\.[0-9]+\.apk$`
- Optional phone app: `^glucowatch-phone-[0-9]+\.[0-9]+\.[0-9]+\.apk$`

Obtainium installs only on the device it runs on and cannot push an APK to a paired watch.

## Samsung Galaxy Store

Samsung distributes apps for Galaxy Watch4 and later through Google Play. Watch apps registered in
Samsung's Seller Portal can be sold only in China. Outside China, this store is not an option for
GlucoWatch.

Sources: [Galaxy Watch notice](https://developer.samsung.com/galaxy-watch-tizen/notice.html),
[Seller Portal notice](https://seller.samsungapps.com/notice/getNoticeDetail.as?csNoticeID=0000008252).

## Android developer verification

- From 2026-09-30, apps installed from participating stores (Google Play, Galaxy Store and others)
  in Brazil, Indonesia, Singapore and Thailand must come from a verified developer. The rule goes
  global in 2027.
- Apps on Play must be registered for every form factor. Outside Play, only phones and tablets
  are enforced for now. Sideloading and F-Droid are not covered yet, and adb installs never are.
- Registration covers the developer's identity and, for each package, the package name and the
  SHA-256 of its signing certificate. You prove you hold the key by signing an APK the console
  gives you.
- Every store here ships APKs signed with the one release key, so both package names can be
  registered once with `1c92cbae…51e6d1`. For Play that holds only if the release key was uploaded
  as the app signing key.

Sources: [developer verification](https://developer.android.com/developer-verification),
[FAQ](https://developer.android.com/developer-verification/guides/faq),
[Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console).

## What a tagged commit may contain

| Change | Effect on the F-Droid build |
|---|---|
| `PRIVACY.md`, files in `docs/`, README links or badges for other stores | None. F-Droid does not read the README for store text. |
| Links to other stores in `full_description.txt` | Lint allows them. Images are not allowed (`<img>` is a forbidden tag), so no badges. |
| `featureGraphic.png`, `wearScreenshots/`, `changelogs/` in `fastlane/` | Supported. Anything at the root goes to both packages. |
| Play listing text in `store/google-play/` | Not read. Do not put it under `fastlane/metadata/android/`, `metadata/`, or `src/*/play/`. |
| A Play-only build type that changes only the manifest | None. `assembleRelease` does not build it. |
| A product flavor | `assembleRelease` then builds one APK per flavor, so the recipes need `gradle: [<flavor>]` and another review. Avoid. |
| AAB files from `bundleRelease` | Build output only. `*.aab` is gitignored. |
| `dependenciesInfo { includeInApk = false }` | None on the unsigned APK. |
| gradle-play-publisher (`com.github.triplet.play`) | The scanner does not flag it, but it runs inside F-Droid's build, and F-Droid imports its `src/main/play/` folder as store text. Use `fastlane supply` or Play Console instead. |
| Google Play Services, `com.google.android.play:*`, Firebase, `androidx.wear:wear-remote-interactions`, `com.google.android.wearable:wearable`, Credential Manager's Play Services provider | Flagged as non-free, either in the Gradle files or in the APK. Allowed only on a branch that is never tagged for F-Droid. |

The scanner skips `debugImplementation` and the configurations of flavors it is not building.
Even so, the reviewer reads the whole tree, and with `Binaries` the GitHub APK must be
reproducible from the tag. Keep proprietary code off `main`.
