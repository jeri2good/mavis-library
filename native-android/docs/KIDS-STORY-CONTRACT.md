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
  "voices": [ {"id":"host","label":"Cheerful host","default":true}, {"id":"dad","label":"Friendly dad"}, {"id":"grandma","label":"Warm grandma"} ],
  "tiers": [ {"id":"t1","label":"Ages 2-4","minAge":2,"maxAge":4}, {"id":"t2","label":"Ages 5-7","minAge":5,"maxAge":7}, {"id":"t3","label":"Ages 8-10","minAge":8,"maxAge":10} ],
  "stories": [ {"id":"creation","title":"God Makes Everything","ref":"Genesis 1-2","obs":1,
                "cover":"cover-creation.jpg","zip":"story-creation.zip","bytes":123,"sha256":"...","minutes":{"t1":2,"t2":4,"t3":6} } ] }
Cover images are separate small release assets so the shelf loads before any zip.

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
"Stories adapted from Open Bible Stories. The original work by unfoldingWord is available from https://openbiblestories.org (CC BY-SA 4.0). Changes: retold for young children at three reading levels and narrated. Illustrations © Sweet Publishing (www.sweetpublishing.com), CC BY-SA 3.0, revised by Distant Shores Media / Door43. These adapted stories are shared under CC BY-SA 4.0."

## Chunk 1 story ids (OBS number)
creation(1) noah(3) abraham(4/5 promise) joseph(8) red-sea(12) david-goliath(17) daniel(22? use OBS lions = 22 not; pick correct OBS chapter) jonah(18? pick correct) jesus-born(21/23) easter(40-41)
The pipeline must map each to the correct OBS chapter(s) by reading titles, and record them in "obs".
