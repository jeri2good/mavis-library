# LibriVox leading article receipt

Commit: b6de93c — Fix LibriVox leading article title matching (branch native).

Changed files:
- netlify/functions/librivox.mjs: remove one leading a/an/the from both normalized comparison titles when another word follows. Exact equality, subtitle rule, author check, and existing matches export retained.
- tests/functions/functions.test.mjs: nine pure regression checks covering requested cases, articles on both sides, and article-only titles. No network calls in added tests; upstream fetch remains mocked.
- PROGRESS.md: implementation and validation evidence.
- RECEIPT-article.md: written last, after the single commit; intentionally uncommitted.

Validation:
- env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions: exit 0, 91 passed (44 endpoint, 14 account/sync, 12 group, 7 CORS, 6 report, 8 word builder).
- Initial sandbox attempt passed 44 endpoint checks then failed to bind the local mock Blobs server with EPERM. Approved rerun passed the full suite.
- node --check passed for both edited .mjs files.
- git diff --check passed before commit; working tree clean immediately after commit, before this receipt.

Not verified:
- Live LibriVox endpoint version count, deployment, and device behavior.
- Upstream search query is unchanged; these tests establish matches() behavior only.

Rollback: revert b6de93c and remove this receipt. Pre-edit backups: /tmp/librivox-article-backup/.
