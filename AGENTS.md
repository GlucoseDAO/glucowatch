# GlucoWatch

Wear OS app and watch face. The watch reads Dexcom Share or the user's Nightscout itself: there
is no phone app and no project server. License is Apache-2.0 (`LICENSE`). This is not a medical
device.

## Modules

| Module | Id | Role |
|---|---|---|
| `core/` | JVM library | Dexcom Share and Nightscout clients, glucose and treatment models, `GlucosePredictor`, demo data, desktop CLI (`:core:run`) |
| `app/` | `io.github.antonkulaga.glucowatch` | Wear app, minSdk 30. Fetches on a schedule, caches readings, settings, five complications |
| `watchface/` | `io.github.antonkulaga.glucowatch.watchface` | Watch Face Format XML, minSdk 33. No Kotlin sources. Shows the app's complications |

`versionCode` and `versionName` live in both `app/build.gradle.kts` and `watchface/build.gradle.kts`.
Keep the two modules on the same pair. A store that publishes a higher `versionCode` is the only
one that can update that install until the others catch up.

Release builds write empty Dexcom fields into `BuildConfig`. Debug builds compile `.env` in as
defaults. `.env` is gitignored and contains a password. Never commit it, and never put those
values on a release path.

Credentials at runtime stay in the app's private storage on the watch and are sent only to Dexcom
or to the user's Nightscout. Regions: `shareous1.dexcom.com` (outside US), `share2.dexcom.com`
(US), `share.dexcom.jp`.

Nightscout support (API v1 and v3, tokens, what each loop uploads, freshness rules) is described in
`docs/nightscout.md`. Nightscout is free software the user runs, so it is not a second proprietary
service. Keep it read-only.

The cache on the watch is tagged with `Settings.accountKey`. Anything that caches per source must
go through the repository's `cached()`/`store()`, so a fetch that finishes after the user switched
source cannot mix its data into the new one.

Demo data must keep working with no account and no network. That is what makes the proprietary
Share service acceptable to F-Droid (`NonFreeNet`). A build that only works when Share is reachable
fails inclusion.

Forecast is optional. New models implement `GlucosePredictor` in `core/` and are registered on
`Predictors`. Ship the implementation as source. A downloaded or committed binary model
(`.tflite`, `.onnx`, a blob in `libs/`) is a non-free dependency as far as the F-Droid scanner
is concerned.

## Build

Gradle 9.1.0, Android Gradle Plugin 9.0.1. The wrapper runs on Java 17–25. Compilation needs a
JDK 21 `javac` on the machine (`jvmToolchain(21)`). Repositories are only `google()`,
`mavenCentral()`, and `gradlePluginPortal()`, with `FAIL_ON_PROJECT_REPOS`.

F-Droid's build server is already on Java 21. Do not add `sudo`, a JDK download, or
`org.gradle.toolchains.foojay-resolver`. The scanner rejects that plugin by name.

Release minify stays on for both modules. The face has no code; R8 is what drops the Kotlin
stdlib AGP still packages.

Check before a tag. The last two commands are the same builds F-Droid runs:

```bash
./gradlew :core:test
(cd app && ../gradlew assembleRelease)
(cd watchface && ../gradlew assembleRelease)
```

## Watch face slots

The Wear runtime keeps a user's complication providers by the position of each `ComplicationSlot`
in `watchface/src/main/res/raw/watchface.xml`, not by `slotId`. Keep the elements in `slotId`
order and add new slots at the end, or an update swaps providers on every installed face.

## Screenshots

`python3 scripts/screenshots.py` produces round screenshots of the face and the app on an
emulator shaped like a Galaxy Watch5 44 mm (450 × 450 px, density 340), for the scenarios `demo`,
`dexcom`, `nightscout` and `nightscout-replay`. Output goes to `data/output/screenshots/`
(gitignored). When the user asks for screenshots, run it and show `overview.png` and the
`*-app.png` files. `docs/screenshots.md` has the requirements, the scenarios and the pitfalls.
The Dexcom login and `NIGHTSCOUT_URL` come from `.env`. Screenshots with real data are the user's
health data: do not publish them.

## What F-Droid builds

F-Droid compiles a git tag on their server and ships that result. They do not take the GitHub
APK as the app they distribute. The tag's tree is the input: their scanner reads Gradle files,
plugins, and binaries in that commit. The scanner skips `debugImplementation` and the
configurations of flavors it does not build. It still flags an `implementation` line inside an
`if` that never runs. The reviewer reads the whole tree either way. If the coordinate or the file
is in the tagged commit, treat it as part of the pipeline.

The recipe is not in this repo. It is a merge request in `fdroiddata`, one package per request:

- app: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/49882
- watch face: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/49898

`commit` is the full hash of the tag. `Binaries` points at the signed GitHub release.
`AllowedAPKSigningKeys` is the SHA-256 of the release certificate. Store text and screenshots
come from `fastlane/metadata/android/en-US/` here. Bump a tag and they update; do not open a
new metadata request for a routine version. Both packages read that root directory, so the watch
face's page currently shows the app's text and images. The lookup rules and the fix are in
`docs/store-publishing.md`.

Their builder looks for one unsigned APK. With `subdir: app` or `subdir: watchface`, which is
what the reviewer asked for, F-Droid runs Gradle inside that directory. Since 0.1.4, each module
has its own `settings.gradle.kts`, and Gradle uses it when started there. `app/settings.gradle.kts`
includes `../core`. `assembleRelease` then leaves exactly one APK in that module's
`build/outputs/apk/release/`: `glucowatch-app-release-unsigned.apk` or
`glucowatch-watchface-release-unsigned.apk`. A product flavor breaks this, because
`assembleRelease` then emits one APK per flavor.

A build started inside a module is its own root project, so AGP finds no `.git`. It writes
`NO_SUPPORTED_VCS_FOUND` into `META-INF/version-control-info.textproto`. A build started from the
repository root records the commit there instead, and its APK no longer matches F-Droid's. Build
the APKs you sign for a GitHub release from inside each module, and check each signed APK against
a fresh unsigned build with `apksigcopier compare <signed> --unsigned <built>`.

The AGP and Kotlin plugin versions are declared in the root `build.gradle.kts` and in both module
settings files. Change all three together.

## Keep these out of a release tag

Adding any of these to a tagged commit fails the F-Droid build or the inclusion rules:

- Google Play Services, Firebase, Crashlytics, Maps, Play Billing, or any other closed SDK
- A tracking or advertising library
- A JAR, AAR, `.so`, or other binary that is not built from source in this repo
- A Maven repository other than Google's and Maven Central
- A Gradle plugin that downloads toolchains or SDKs
- An updater that downloads an APK from outside the store
- Secrets, the release keystore, or a filled-in `.env`
- A license change away from Apache-2.0

Dexcom Share stays behind the existing `NonFreeNet` declaration. Do not add a second proprietary
service that the app cannot run without.

Code you write yourself is fine on every store. A proprietary SDK is fine only on a commit that
is never tagged for F-Droid. Do not put it on `main` and expect the flavor name to save the
build.

## Other stores

`docs/store-publishing.md` has the status and requirements of each store (checked 2026-09-25),
and a table of what a tagged commit may contain. Read it before adding store listings, assets,
build types, or Play tooling. Play will not accept the app as it is: it asks for a password on the
watch (Wear OS rule WO-P6) and it declares `USE_EXACT_ALARM`.

IzzyOnDroid and Obtainium distribute the GitHub release APK. They do not compile the tag.
IzzyOnDroid may rebuild it later to compare bytes; a mismatch does not by itself reject the app.
Play reviews a bundle you upload and does not compile the repo. Galaxy Store sells apps for Galaxy
Watch4 and later only in China. Everywhere else, Samsung sends watch apps to Play.

One install can move between stores only when every store signs with the same certificate.
That keystore is `~/.config/glucowatch/release.keystore` (password beside it in
`keystore.pass`). Never commit either file. The certificate SHA-256 is
`1c92cbaecd95fdeef869584f636d02180db22f1e363912d1d096af013351e6d1`.

Sign release APKs with `apksigner` from build-tools, `--alignment-preserved`, v3 on, v1 and v4
off. `apksigner` otherwise rewrites zip alignment and F-Droid's signature copy no longer matches
the GitHub APK.

For Play, upload this same key as the app signing key (PEPK) before the first open-testing or
production release. New Play apps default to a Google-generated key, so change the key under Play
app signing first. After that release the Play key is fixed. A Google-generated key makes the Play
install a different app from the F-Droid one. Use a separate upload key for bundles you send
to Play.

After Play enrollment, the certificate they display has to be the SHA-256 above. A different
value means the listings have diverged.
