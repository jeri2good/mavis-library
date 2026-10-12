# Native Android verification

- Build bundled web assets from the repository root with `npm ci --ignore-scripts --no-audit --no-fund && npm run build:android`; Gradle requires `dist-android/index.html`. This does not require editing web source.
- In `native-android`, run `JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon`.
- Run `node scripts/test-account-transport.mjs` from `native-android` for fake-fetch/XHR verification of the Android-only WebView account transport asset.
- Native secrets use the existing Android Keystore AES-GCM SecureStore over DataStore, not EncryptedSharedPreferences. Account token and user identity are written atomically; recovery codes and passwords are never persisted.
- Podcast Lounge uses BuildConfig.API_BASE and `/api/podcast/*`. Audio media items keep a gateway identity URL; each stream/Range open resolves a fresh signed HTTPS studio URL without forwarding account or owner credentials.
- Account/subscription/podcast tests must use fake HTTP. Do not create live accounts, make live owner decisions, or dispatch live podcast jobs during local verification. Hermes owns live acceptance testing.
