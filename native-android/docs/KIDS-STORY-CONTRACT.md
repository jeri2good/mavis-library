# Kids Story Pack contract v1 (shared by content pipeline and Android app)

Source: unfoldingWord Open Bible Stories (en_obs, CC BY-SA 4.0), artwork Sweet Publishing (CC BY-SA 3.0),
images at https://cdn.door43.org/obs/jpg/{360px|2160px}/obs-en-SS-FF.jpg. Our retellings are a derivative:
never use the "unfoldingWord" trademark as our title; credits text below is mandatory.

## Hosting
Pack lives as assets on GitHub release tag `kids-content-v1` (PRE-RELEASE, so the app updater's releases/latest ignores it)
in jeri2good/mavis-library. Base URL:
  https://github.com/jeri2good/mavis-library/releases/download/kids-content-v1/
Files:
  catalog.json                       -- list of stories
  story-<id>.zip                     -- one per story: story.json + img/*.jpg + audio/<voice>/<tier>/*.mp3 + music.mp3
App downloads catalog, then a story zip on first open (with progress), unzips to filesDir/kids/<id>/, plays offline after.

## catalog.json
{ "version": 1, "credits": "<CREDITS TEXT>",
  "voices": [ {"id":"host","label":"Cheerful host","default":true}, {"id":"dad","label":"Friendly dad"}, {"id":"grandma","label":"British accent"},
              {"id":"storyteller","label":"Storyteller (recorded)","tiers":["t3"]} ],
  "tiers": [ {"id":"t1","label":"Ages 2-4","minAge":2,"maxAge":4}, {"id":"t2","label":"Ages 5-7","minAge":5,"maxAge":7},
             {"id":"t3","label":"Ages 8-10","minAge":8,"maxAge":10,"defaultVoice":"storyteller"} ],
  "stories": [ {"id":"creation","title":"God Makes Everything","ref":"Genesis 1-2","obs":1,"obsChapters":[1],
                "cover":"cover-creation.jpg","zip":"story-creation.zip","bytes":123,"sha256":"...","minutes":{"t1":2,"t2":4,"t3":6} } ] }
Cover images are separate small release assets so the shelf loads before any zip.

Round 2 additions (backward compatible):
- A voice MAY carry "tiers":[...]: it exists only for those tiers (absent = all tiers). The app offers it only in those tiers.
- A tier MAY carry "defaultVoice": the narrator selected by default for that tier (t3 -> storyteller). Other tiers keep the
  voice with "default":true (host).
- "storyteller" is the recorded unfoldingWord OBS v6 narration, split per frame. Its audio is t3-only and MAY be missing for
  individual pages (frames that failed alignment/QA): page.audio.storyteller is then absent and the app plays host for that page.
- "obs" stays the first OBS chapter (int); "obsChapters" lists every chapter used (e.g. easter [40,41]).

## longlisten.json (separate release asset, audio only, streamed — not zipped)
{ "version":1, "kind":"longlisten", "audioOnly":true, "credits":"...",
  "books":[ {"id":"librivox-1392","librivoxId":1392,"title":"...","author":"...","readers":["..."],"totaltime":"4:46:48",
             "totalSeconds":17208,"cover":"https://archive.org/download/...jpg","librivoxUrl":"...","archiveUrl":"...",
             "license":"Public domain (LibriVox recording)","ageHint":"6+",
             "sections":[ {"title":"...","mp3":"https://archive.org/download/.../..._64kb.mp3","seconds":661,"readers":["..."],
                           "bytes":5291000,"bibleRef":"Genesis 1"   // bibleRef only when derivable from the title
                         } ] } ] }

## story.json (inside zip)
{ "id":"noah","title":"...","ref":"Genesis 6-9","music":"music.mp3",
  "pages":[ { "img":"img/01.jpg",
              "text":{"t1":"...","t2":"...","t3":"..."},
              "audio":{"host":{"t1":"audio/host/t1/01.mp3",...},"dad":{...},"grandma":{...}},
              "words":{"host":{"t1":[[startMs,endMs,"word"],...]}}   // optional, Chunk 2
            } ],
  "question":{"t3":{"q":"What did Noah build?","choices":["A boat","A tower","A house"],"answer":0}} }
- Tier t1 may use a SUBSET of pages: a page with no text for a tier is skipped for that tier.
- Age -> tier: 2-4 t1, 5-7 t2, 8-10 t3. Default age 5, default voice host.
- Every listed audio file exists; MP3 mono 44.1k ~64-96kbps, loudness ~-16 LUFS. music.mp3 is a seamless gentle loop ~60-90 s, ~-26 LUFS (app also ducks it under narration).

## CREDITS TEXT
"Stories adapted from Open Bible Stories. The original work by unfoldingWord is available from https://openbiblestories.org (CC BY-SA 4.0). Changes: retold for young children at three reading levels and narrated. Illustrations © Sweet Publishing (www.sweetpublishing.com), CC BY-SA 3.0, revised by Distant Shores Media / Door43. These adapted stories are shared under CC BY-SA 4.0. Recorded narration by Brad Harrington, unfoldingWord Open Bible Stories audio (CC BY-SA)."

## Chunk 1 story ids (OBS number)
creation(1) noah(3) abraham(4,5 promise) joseph(8) red-sea(12) david-goliath(17, Goliath at frame 03) daniel(no matching OBS story) jonah(no matching OBS story) jesus-born(23) easter(40,41)
The pipeline must map each to the correct OBS chapter(s) by reading titles, and record them in "obs".

Verified against the unpacked 50-chapter en_obs source on 2026-11-02: chapter 22 is The Birth of John; chapter 18 is The Divided Kingdom. Daniel/lions and Jonah are absent, so their `obs` values remain null in source/mapping.json and production is blocked pending an approved source after phase 1. Full title mapping is recorded there. Phase-1 David uses frames 17-01 through 17-08 (early David context); only frame 17-03 depicts Goliath.

## Round 2 story ids (OBS number)
abraham(4,5) joseph(8) red-sea(12) jesus-born(23) easter(40,41) feeding-5000(30) good-samaritan(27).
Lost sheep and Jesus calming the storm are not in en_obs (verified against all 50 v9 chapter files on 2026-11-02);
feeding-5000 and good-samaritan replace daniel and jonah.
