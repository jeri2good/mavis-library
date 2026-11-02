# BRIEF-086-WEB — Bible toolbar clarity (Codex)

Files: `src/views/bible.js` (toolbar ~lines 88-101), `src/lib/icons.js` (or wherever `icon()` lives),
`src/styles/app.css`. Web only. Do not touch `native-android/` (another worker is there).

Reference screenshot: J's phone shows the toolbar as unlabeled icons: [book] [search] [headphones] [mic]
[My Bible outline pill] [spark] [library]. Users can't tell what they do.

1. **Read aloud button** (`data-act="listen"`): new icon = a face in profile with a speech bubble / sound waves
   from the mouth ("speaking head"). Label "Read". aria-label "Read this chapter aloud".
2. **Bible lesson button** (`data-act="podcast"`): new icon = studio microphone with a round pop filter in front.
   Label "Lesson". aria-label "Make a Bible lesson podcast".
3. **Strong's button** (`data-act="strongs"`): icon that reads as "original language" (e.g. stacked "א" and "Ω"
   glyph drawn as SVG paths, not font text). Label "Original". When on: filled/accent background state
   (aria-pressed already exists — style it). On every toggle show the existing `toast()`:
   on → "Original words on — tap a word for its Hebrew or Greek"; off → "Original words off".
4. **Labels**: every toolbar icon gets a small text label under it (11–12px, muted): Original, Search, Read,
   Lesson, Ask, Study. Keep 48px min touch targets; the row may scroll horizontally on narrow phones — no wrapping
   into two cramped lines; make sure all fit or scroll cleanly at 360px width.
5. **My Bible**: make it the one filled button: accent/gold filled pill, contrasting text, icon kept. It should
   look like the primary tab of the toolbar. Check contrast ≥ 4.5:1 in light AND dark themes.
6. AI spark button: unchanged behaviour (only gets the "Ask" label).
7. Icons: SVG, same stroke width/style as existing icon set, `currentColor`.

Verify: `npm run build:android`; `node --test` each file in `tests/unit/` (run them one file at a time);
add a small unit/DOM test if a test harness exists for views, otherwise none. Render check: start
`npm run dev -- --host 127.0.0.1 --port 5175 --strictPort` and screenshot `#/bible/jas/4` at 390×844 and
360×780 (light + dark) with headless Chrome into `design/brief-086/web-shots/`.
Commit with `BRIEF-086 web:` prefix and `[skip netlify]`. Don't push. Write `design/brief-086/RECEIPT-WEB.md`. Stop.
