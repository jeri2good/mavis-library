# Android updates

Every change merged to `main` that touches the app is built by the **Android release** workflow (`.github/workflows/android-release.yml`) and published as a GitHub release: an APK plus `update.json`. Installed copies of Mavis read `releases/latest/download/update.json`, offer the newer build, download it, check its SHA-256, and hand it to Android's installer.

Builds are the same debug build phones run today, signed with one shared key. The workflow numbers them build 101, 102, and so on. Local builds stay at build 2, so every published build counts as newer.

## One-time setup

Do steps 1 and 2 on the computer that built the Mavis now on the phones, with `adb` and the Android SDK available.

### 1. Confirm the key matches the phones

With a phone connected:

```sh
adb shell pm path com.mavislibrary.app            # prints package:/data/app/…/base.apk
adb pull /data/app/…/base.apk installed.apk        # the path from the line above
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs installed.apk | grep SHA-256
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```

The two SHA-256 fingerprints must match. `keytool` prints the fingerprint with colons; ignore them when comparing. If they don't match, the phones were built with a different computer's key. Use that computer's `~/.android/debug.keystore` instead.

### 2. Store the key as a GitHub secret

This needs the `gh` CLI, signed in with access to `jeri2good/mavis-library`.

```sh
# Linux
base64 -w0 ~/.android/debug.keystore | gh secret set MAVIS_SIGNING_KEYSTORE_BASE64 -R jeri2good/mavis-library
# macOS
base64 -i ~/.android/debug.keystore | gh secret set MAVIS_SIGNING_KEYSTORE_BASE64 -R jeri2good/mavis-library
```

The standard debug keystore uses the password `android` and the alias `androiddebugkey`, so nothing else is needed. If the keystore uses other values, also set `MAVIS_KEYSTORE_PASSWORD`, `MAVIS_KEY_ALIAS` and `MAVIS_KEY_PASSWORD`.

Keep a private backup of this keystore file. Without it, phones can't take updates unless Mavis is uninstalled and reinstalled. Anyone with the file could sign an app that replaces Mavis, so never commit or share it.

### 3. Publish a build

Merge to `main`, or run it by hand: **Actions → Android release → Run workflow**. The release appears under **Releases** a few minutes later.

### 4. Install that first build by hand on each phone

The Mavis on phones today was built before the updater existed, so it can't update itself yet. On the phone:

1. Open https://github.com/jeri2good/mavis-library/releases/latest and tap the `.apk` file.
2. Open the download. Allow the browser to install apps if Android asks.
3. Tap **Update**.

This installs over the current Mavis and keeps its data. A computer can do the same with `adb install -r mavis-0.7.N.apk`.

## After that

- Mavis checks for updates shortly after it opens and once a day. When a newer build is out, it shows a **Mavis update ready** notification. Tapping it opens **Settings → App updates**; tap **Update now** there.
- **The first time**, Android asks you to let Mavis install apps:
  1. Tap **Allow**.
  2. Turn on **Allow from this source**.
  3. Come back and tap **Update now** again.
- Android then asks **Update Mavis?**. On Android 12 and later, updates after the first may install without asking.
- **Settings → App updates → Check for updates** checks right away. **All versions** opens the releases page.
- Kids mode never shows update notifications.

## Notes

- The repository is public, so the releases are too. The APK contains no keys or passwords: the owner code and sign-in are entered on each phone.
- A phone running a published build refuses a local build (build 2) as a downgrade. To test a local build on it, use `adb install -r -d`.
- The workflow keeps the ten newest releases and deletes older ones.
