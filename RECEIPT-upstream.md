# LibriVox upstream article receipt

- Commit: `10335d1` on `native` — Fix LibriVox upstream article prefixes with bounded fallback.
- Shared `stripArticle` removes one leading a/an/the only when another word follows, used by matching and upstream prefixes.
- Empty stripped results allow one original-title fallback; fallback and transport retry share at most two calls with existing 5200/3800 ms timeouts. No third wait.
- Nine new mocked endpoint checks cover requested prefixes, article-only titles, one-article removal, successful fallback, no fallback for populated results/no article, and retry/fallback attempt limits.
- `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions`: PASS, 100 checks (53 endpoint, 14 account/sync, 12 group, 7 CORS, 6 report, 8 word builder). Initial sandbox attempt stopped at local mock-server listen EPERM; approved rerun exited 0.
- `node --check` passed for both edited .mjs files; `git diff --check` passed.
- Exactly one commit; no push. Live endpoint/deployment not verified.
- Working tree was clean after commit. This receipt is written afterward as the final action and remains uncommitted.
- Rollback: revert `10335d1`.
