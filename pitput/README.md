# פטפוט של שחף (Pitput) — first Hebrew words, played together

An Android app for a ~2-year-old and his parents. It's built for **co-play**: a parent and the toddler
sit together for 5–10 minutes. The design follows what speech-language therapists recommend:
waiting time, expansion, modelling instead of correcting, and celebrating every attempt.

## Modes (kid side)
- **🔍 מה זה?** Big picture cards (emoji, or your own photo). The word is said aloud in your recorded voice, or by Hebrew TTS as a fallback, plus the animal or vehicle sound.
- **🎤 תורך!** The app asks "מה זה?" and then **waits and listens**. The mic only detects *any vocal attempt*
  (energy-based; no speech recognition, since toddler words aren't recognizable). An attempt gets
  confetti, praise, and the target word modelled right back. Silence: the app models the word and gives him a second chance.
  It prioritises words marked "understands" but not yet "says".
- **☁️ קוקו!** Peekaboo clouds hide 6 words. Good for laughs and for words like "אוי", "הנה", "עוד".
- A session timer (default 10 min) puts the owl to sleep. Only a parent long-press wakes it.

## Parent side (long-press ⚙️ for 1.5 s)
- **Diary**: per-word status (מבין / אומר) with dates, progress toward 50 words, a "moments" log for
  two-word combos, and gentle guidance on when to consult.
- **Words**: record your own voice per word, photograph the *real* object or person, and add custom words
  (dog's name, "במבה"...). Also record "מה זה?" and "כל הכבוד!" in your voice.
- **Sync between phones**: export a backup JSON (diary, recordings, photos) through the share sheet, then import it on
  the other phone. The import merges and never deletes.
- 150+ words with nikud in 8 categories, including "magic" core words (עוד, די, הנה, בי בי…).

## Sounds & personalization
- Real animal, vehicle and household recordings (`assets/sounds/`, ~1 MB, offline) play right after each word.
  Sources and licenses are in `assets/sounds/CREDITS.txt` (MIT, and the Google Sound Library under CC BY 4.0).
  The bicycle bell and the seagull call are synthesized.
- Made for **Shachaf**. His card in the family shows a boy (or his photo), and the app greets him by name when it opens
  (בוקר טוב / שלום / ערב טוב). The family appears by name: אמא סיון, אלמוג (the big sister), גל (the sister). Names, photos and
  voice recordings for each family member are edited in Settings → המשפחה. Photos stay on the phone and never go into the repo.
- Gentle gaming: stars for every card, attempt and reveal; a level every 10 stars unlocks a sticker in "המדבקות שלי".
- Photosensitivity-safe (WCAG 2.3.1): no flashing, no strobing, no full-screen brightness changes. "Calm mode" also turns off
  confetti and background motion.

Everything is offline and stored locally. There are no ads and no tracking.

## Tech
- `src/main/assets/index.html` is the entire app (vanilla JS). You can open it in desktop Chrome to iterate.
- `MainActivity.kt`: a WebView served via `WebViewAssetLoader` (a secure origin, so mic, camera, IndexedDB and MediaRecorder work),
  a bridge to Android `TextToSpeech` (he-IL), runtime permissions, a file chooser, a share sheet, and back-button handling.
- The `signing/pitput-debug.jks` debug key is committed on purpose, so each CI build installs as an update and
  keeps your data.

## Build & install
CI (`.github/workflows/build-pitput.yml`) builds on every push that touches `pitput/`. It commits
`dist/pitput.apk` and publishes the **`pitput-latest`** GitHub release.
On each phone: open the release page, then tap `pitput.apk`, then install (allow "install unknown apps" for the browser).
Hebrew TTS needs "Speech Services by Google" with Hebrew voice data. Your own recordings work regardless.
