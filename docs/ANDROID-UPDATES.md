# Android builds and updates

The **Android release** workflow (`.github/workflows/android-release.yml`) builds and signs the app on GitHub, so no particular computer is needed to make an installable APK.

- **From `main`:** every change that touches the app is published as a release (an APK plus `update.json`). Installed copies of Mavis read `releases/latest/download/update.json`, offer the newer build, download it, check its SHA-256, and hand it to Android's installer.
- **From `native` and `claude/*` branches:** each push is published as a **test build** (a prerelease). It has a download link for trying unreleased work. Phones never update to a test build on their own.

Builds are numbered from the workflow run: build 101, 102, and so on. Every build made before this workflow had build number 2, so published builds always count as newer. Every build is signed with the same key as the Mavis already on the phones, so each one installs over the last and keeps its data.

## One-time setup: give GitHub the signing key

Do this on the tower, which holds the key: `~/.secure/TrainingBoardKiosk-v4-release.jks`, alias `trainingboard-v4`. You need `adb`, the Android SDK, and the `gh` CLI signed in with access to `jeri2good/mavis-library`. Never print or commit the key or its passwords.

### 1. Confirm it is the key the phones have

With a phone connected:

```sh
adb shell pm path com.mavislibrary.app            # prints package:/data/app/…/base.apk
adb pull /data/app/…/base.apk installed.apk        # the path from the line above
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs installed.apk | grep SHA-256
keytool -list -v -keystore ~/.secure/TrainingBoardKiosk-v4-release.jks -alias trainingboard-v4 | grep SHA256
```

`keytool` asks for the keystore password. The two SHA-256 fingerprints must match; ignore the colons `keytool` adds. If they don't match, find the keystore that signed the phone's copy and use that one below.

### 2. Store the key and its passwords as repository secrets

```sh
base64 -w0 ~/.secure/TrainingBoardKiosk-v4-release.jks | gh secret set MAVIS_SIGNING_KEYSTORE_BASE64 -R jeri2good/mavis-library
gh secret set MAVIS_KEY_ALIAS -R jeri2good/mavis-library --body trainingboard-v4
gh secret set MAVIS_KEYSTORE_PASSWORD -R jeri2good/mavis-library   # prompts; paste the keystore password
gh secret set MAVIS_KEY_PASSWORD -R jeri2good/mavis-library        # prompts; paste the key password (often the same)
```

On macOS, use `base64 -i <file>` instead of `base64 -w0 <file>`.

Secrets are encrypted. Only this repository's workflows can read them, and GitHub hides them in logs. The key file stays in `~/.secure`; keep a private backup of it. Without the key, phones can't take updates unless Mavis is uninstalled and reinstalled. Because the name says this keystore also signs the Training Board Kiosk app, anyone with write access to this repository could in principle use it for that app too.

### 3. Get the updater onto each phone once

The Mavis on phones today predates the updater, so the first new build goes on by hand:

1. On the phone, open https://github.com/jeri2good/mavis-library/releases (or a test build's link) and tap the `.apk`.
2. Open the download. Allow the browser to install apps if Android asks.
3. Tap **Update**.

This installs over the current Mavis and keeps its data. From then on the app updates itself from `main` releases.

## On the phone after that

- Mavis checks for updates shortly after it opens and once a day. When a newer `main` build is out, it shows a **Mavis update ready** notification. Tapping it opens **Settings → App updates**; tap **Update now** there.
- **The first time**, Android asks you to let Mavis install apps:
  1. Tap **Allow**.
  2. Turn on **Allow from this source**.
  3. Come back and tap **Update now** again.
- Android then asks **Update Mavis?**. On Android 12 and later, updates after the first may install without asking.
- **Settings → App updates → Check for updates** checks right away. **All versions** opens the releases page.
- Kids mode never shows update notifications.

## Notes

- The repository is public, so releases are too. The APK contains no keys or passwords: the owner code and sign-in are entered on each phone.
- The workflow can also be run by hand: **Actions → Android release → Run workflow**.
- A phone on a newer build refuses an older one as a downgrade. To put an older or locally made build on it, use `adb install -r -d`.
- The workflow keeps the ten newest releases and the five newest test builds.
