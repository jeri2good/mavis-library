# Android builds and updates

The **Android release** workflow (`.github/workflows/android-release.yml`) builds and signs the app on GitHub, so no particular computer is needed to make an installable APK.

- **From `main`:** every change that touches the app is published as a release (an APK plus `update.json`). Installed copies of Mavis read `releases/latest/download/update.json`, offer the newer build, download it, check its SHA-256, and hand it to Android's installer.
- **From `native` and `claude/*` branches:** each push is published as a **test build** (a prerelease) with a download link. Phones never update to a test build on their own.

Builds are numbered from the workflow run: build 101, 102, and so on. Every one is signed with the same key, so each installs over the last and keeps its data.

## The signing key

- **The key:** PKCS#12, alias `mavis`, RSA 4096, valid until 2056, encrypted with AES-256 under a 32-character random password. It is **not** in the repository, because the repository is public.
- **Where it lives:** two repository secrets, which only this repository's workflows can read and GitHub hides in logs:
  - `MAVIS_SIGNING_KEYSTORE_BASE64`: the key file as base64 text;
  - `MAVIS_KEYSTORE_PASSWORD`: its password.
- **Backup:** J keeps both values (the key text and the password) in his password manager or a private folder. GitHub won't show a secret again once saved, so that backup is the only way to sign builds anywhere else. Never commit, print, or share either value.
- **Fingerprint** (SHA-256 of the certificate): `8c754baa9acd605f4a469533e3a6564a455fb00fd9af91468cdba1e4b8a8afb9`. The workflow refuses to publish an APK signed with anything else.

This key replaced the tower's `TrainingBoardKiosk-v4` key on 2026-10-20. Copies of Mavis signed with the old key can't update to new builds: uninstall them once, then install a new build.

### One-time setup (done once by J)

1. Open https://github.com/jeri2good/mavis-library/settings/secrets/actions/new
2. **Name** `MAVIS_SIGNING_KEYSTORE_BASE64`; paste the whole key text as **Secret**; click **Add secret**.
3. Open the same link again. **Name** `MAVIS_KEYSTORE_PASSWORD`; paste the password as **Secret**; click **Add secret**.

Until both exist, branch test builds skip with a warning and a `main` release fails with a message naming the missing secret.

### Signing a build elsewhere

Decode the key text to a file (`base64 -d key.txt > mavis-release.p12`). Then set:
- `MAVIS_KEYSTORE` to that file's path;
- `MAVIS_KEYSTORE_PASSWORD` and `MAVIS_KEY_PASSWORD` both to the password;
- `MAVIS_KEY_ALIAS=mavis`.

Build with `./gradlew assembleDebug -PmavisVersionCode=<n>`. Without these, local builds are build 2, signed with that machine's own debug key, and phones refuse them as updates.

## Moving a phone to the new key (once per phone)

1. **Uninstall the old Mavis.** This deletes what was stored only on that phone: downloaded books and audio, imported books, the app's own shelf, followed sites, and settings. Shelf, progress, notes, quotes, word lists and podcasts in the account come back after signing in.
2. **Install a new build:** the APK sent in chat, or open https://github.com/jeri2good/mavis-library/releases on the phone and tap the newest `.apk`. Allow the browser to install apps if Android asks.
3. **Open Mavis and sign in** under Settings → Account. Re-enter the owner code if this phone used one.

## Updates after that

- Mavis checks for updates shortly after it opens and once a day. When a newer `main` build is out, it shows a **Mavis update ready** notification. Tapping it opens **Settings → App updates**; tap **Update now** there.
- **The first time**, Android asks you to let Mavis install apps:
  1. Tap **Allow**.
  2. Turn on **Allow from this source**.
  3. Come back and tap **Update now** again.
- Android then asks **Update Mavis?**. On Android 12 and later, updates after the first may install without asking.
- **Settings → App updates → Check for updates** checks right away. **All versions** opens the releases page.
- Kids mode never shows update notifications.

## Notes

- The repository is public, so releases are too. The APK contains no passwords: the owner code and sign-in are entered on each phone.
- The workflow can also be run by hand: **Actions → Android release → Run workflow**.
- A phone on a newer build refuses an older one as a downgrade. To put an older build on it, use `adb install -r -d`.
- The workflow keeps the ten newest releases and the five newest test builds.
