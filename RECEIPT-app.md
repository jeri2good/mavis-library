# Podcast Studio subscriptions — Android receipt

Implemented on `podsub-app`; first-green implementation commit: `a3f9cc0`.

## Delivered

- Contract request/response models and `/api/podsub`, `/run-now`, `/runs`, `/today` client using the existing PodcastApi gateway authentication and adult gate. PUT serialization excludes server fields. Today accepts a raw list or episodes/jobs/items envelope because the shared contract does not prescribe an envelope.
- Per-account, retryable one-time migration of followed sites and legacy enabled/time settings. Briefing sites map to Top 1 / Briefing; own-episode sites to Top 1 / Separate; paused/off sites remain Off. Existing saved server settings win over legacy phone settings. The migration marker is written only after a successful PUT. Subsequent follow/rename/unfollow changes sync without removing sites known only to the server.
- New subscriptions default to NotebookLM only, newest, seven days, Top 1 / Briefing and a disabled schedule. Enabling the schedule opens a Material3 TimePicker and requires setting a time.
- Lounge controls for site count/delivery, labeled Move up/down priority buttons, ready-by time/day selection, engine policy, rank, retention, Telegram notifications, Save, Run now, next start and last-run/skipped-site summaries. Rows use weights and wrapping FlowRows.
- Server-sourced Today usage meter, episode status, single play and Play all through the existing player and gateway audio identity URLs.
- One-hour WorkManager downloads with connected-network constraints and no battery requirement, plus sync work on followed-site/account changes. Retention removes expired account-scoped local audio while preserving streaming metadata. Download failures do not prevent streaming or server generation.
- Legacy daily generation schedules are canceled. Persisted DailyBriefing/BriefingCheck workers are inert, and AppContainer no longer exposes the old briefing generation runner. Sites feed reading and manual article/latest-post shortcuts remain.

## Verification

Successful background command from `native-android`:

```sh
JAVA_HOME="$HOME/.local/jdks/jdk-21.0.12.1+1" ANDROID_HOME="$HOME/Android/Sdk" \
  ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'
```

- `/tmp/podsub-build-2.log`: **BUILD SUCCESSFUL**, 3m 1s.
- Exact totals parsed from `native-android/app/build/test-results/testDebugUnitTest/TEST-*.xml`: **66 suites; 354 tests; 0 failures; 0 errors; 0 skipped**.
- `PodsubTest`: 11 tests, all passing, covering contract serialization, defaults/validation, migration mapping/retry/account separation, follow synchronization, retention boundaries, fake HTTP/auth, Today response parsing, Kids/sign-in gates and ViewModel server-limit/dirty-edit state.
- `PodcastSettingsTest`: 6 tests, all passing, including the added fake-audio cleanup test that preserves other accounts, boundary-age files and stream metadata.
- Updated Sites navigation regression passes.
- `node scripts/test-account-transport.mjs`: passed fake fetch/XHR headers, origin isolation, sign-out and subscriber UI gating.
- `git diff --check`: passed before implementation commit.
- The first run assembled the APK but had a stale Sites navigation expectation and two Robolectric native-initialization failures in existing share tests. The expectation was updated; the subsequent full run passed without excluding tests.
- Existing `dist-android` assets were bundled; web source/assets were not regenerated in this Android-only task.

APK: `/home/hawthornes/DevinWorkspaces/wt-podsub-app/native-android/app/build/outputs/apk/debug/app-debug.apk`

SHA-256: `4a164f68bbb44978cbf59bd7a58d16482dabb87f2a9eca6343d7e55f97987335`

## Not verified

- No emulator/device launch, installation, visual 360dp inspection, real playback, or OS background scheduling/Doze verification. Layout uses wrapping controls and weighted rows but still needs Hermes device acceptance.
- No live API integration, real migration, account creation, owner decision, podcast job, quota mutation, Telegram notification, or server scheduler execution. All new HTTP/audio tests use fixtures; the backend is being built separately.
- No live proof of backend Today list contents/envelope or signed-audio delivery for scheduled jobs. Hermes owns integration acceptance.
- No push. No changes to `netlify/`, root `src/`, or root `tests/`.

Rollback: revert `a3f9cc0`; pre-edit native source backup is `/tmp/podsub-backup/src`. Local verification did not migrate any real account or change server state.
