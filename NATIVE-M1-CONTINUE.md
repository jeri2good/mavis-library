# Continue Milestone 1 (previous Devin run was killed by SIGTERM at the first Gradle build)

Re-read `NATIVE-M1-BRIEF.md` and `docs/NATIVE-PLAN.md`. The previous run scaffolded `native-android/` (36 Kotlin files, theme, data layer, repository, WebView host, search, settings) and was killed right as it started the FIRST build. NOTHING in it has been compiled or tested yet. Treat every file as unverified.

Do, in order:
1. Inspect what exists (`git log`, `git status`, `find native-android -name '*.kt'`). Do not start over; fix and finish.
2. Env: `export JAVA_HOME=$HOME/.local/jdks/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME=$HOME/Android/Sdk`. Run `npm run build:android` in the repo root first so `dist-android/` exists.
3. Run Gradle in the BACKGROUND with output to a log file (e.g. `(cd native-android && ./gradlew assembleDebug --no-daemon > /tmp/m1-build.log 2>&1 &)`) and poll the log; never run a build in the foreground for more than ~4 minutes. Fix compile errors until `assembleDebug` succeeds, then run `testDebugUnitTest` and `lintDebug`.
4. Commit after each step that goes green. Append to `PROGRESS.md`.
5. Complete brief tasks 3-9 (emulator smoke is best effort: if no AVD can boot headless within ~10 minutes, record NOT RUN).
6. Write `native-android/RECEIPT.md` last, with real command output summaries, screenshots list, and what is NOT done or NOT run. Never fabricate results.
Stop new work at ~80% of your budget and write the receipt.
