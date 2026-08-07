# CS-extension AGENTS.md

## Goal
Maintain and optimize the CS-extension CloudStream3 plugin repo; fix `NoSuchMethodError` crashes from incompatible Jackson/`parseJson` calls; keep all 16 providers working with Indonesian subtitles where available.

## Constraints & Preferences
- Build/deploy via GitHub Actions to branch `builds`.
- Use `tryParseJson` instead of `.parsedSafe()` / bare `parseJson` / `ObjectMapper` / `jacksonObjectMapper` / `Gson().fromJson` (latter calls internal `parseJson` method missing in some CloudStream versions).
- Bump provider version on every change.
- Author format: `listOf("OriginalAuthor", "MWK")` for forked providers; `listOf("MWK")` only when origin unknown.
- `lang` left as-is per provider — not forced to "id".
- `AesHelper` is allowed — used for AES content decryption, not JSON parsing.

## Active Providers (16)
Anichin, Animeku, Cinemax21(deleted), Donghub, Drachin, Dutamovie, Kisskh, Klikxxi, Kuronime, Moviebox, MoviePedia21, NgeFilm21, Nomat, OneTouchTV, Otakudesu, PencuriMovie, TorraStream.

## Provider Versions
| Provider | Version | Type |
|----------|---------|------|
| Anichin | v7 | Anime |
| Animeku | v4 | Anime |
| Donghub | v10 | Anime |
| Drachin | v1 | Asian Drama |
| Dutamovie | v17 | Movie & TV |
| Kisskh | v11 | Movie & TV |
| Klikxxi | v7 | Movie & TV |
| Kuronime | v13 | Anime |
| MoviePedia21 | v4 | Movie & TV |
| Moviebox | v8 | Movie & TV |
| NgeFilm21 | v5 | Movie & TV |
| Nomat | v6 | Movie & TV |
| OneTouchTV | v6 | Movie & TV |
| Otakudesu | v10 | Anime |
| PencuriMovie | v4 | Movie & TV |
| TorraStream | v89 | Movie, TV & Anime |

## Status: ✅ All providers clean
- All 16 providers fixed from `NoSuchMethodError: No virtual method parseJson(...)`.
- Replaced `.parsedSafe<>()` / `parseJson<>` / `ObjectMapper` / `jacksonObjectMapper` / `Gson().fromJson` with `tryParseJson` + `.text`.
- Fixed TorraStream: 6× `100L` timeouts → `30_000L`; magnet tracker cache; `toString()` → `.text` in 3× ani.zip calls + Animetosho; `Gson()` → `tryParseJson`; reversed `getQuality` 360p/480p bug.
- Replaced deprecated `SubtitleFile(` constructor with `newSubtitleFile(` in Donghub & Kisskh.
- AES key + `Servers` data class + `decryptCryptoJS()` (EVP_BytesToKey + AES/CBC/PKCS5Padding) in Kuronime for winie.2.min.js cipher.
- Build/deploy via GitHub Actions → `builds` branch.

## Recent Pushes
- `d936667` Klikxxi v7: add Hexload extractor for server 3 — direct MP4 via AJAX API
- Kisskh v11: improve subtitle language codes & quality inference — add 'land' field, recognize kisskh URL patterns
- Kisskh v10: restructure home page menus to 5 categories (Latest Update, Top Kdrama, Top Cdrama, Anime, Hollywood)
- Otakudesu v10: comprehensive audit — fix IndexOutOfBounds crash, remove hardcoded TMDB API key (security), try-catch Base64 decode, error handling on network requests, URL-encode search, content-based selectors (replaced brittle nth-child), deduplicate regex, withTimeoutOrNull on ani.zip API
- `d748f6a` Klikxxi v5: comprehensive audit fixes — error handling, URL encoding, pagination, episode dedup, regex, CSS constants
- `aaa74a9` Donghub v6: added Odnoklassniki (OKRU) extractor for mirror server support
- Kisskh v8: parallel key fetches, proper error handling, timeouts on all API calls, URL-encoded search, pagination fix, TvType mapping fix, interceptor fix
- Kisskh v9: key caching + retry mechanism, API version constant, quality inference from URL
- `f266985` Stage 3: remove debug println from Kisskh v7, Nomat v5
- `b60b6d6` Stage 2: 10 bug fixes across 6 providers (Cinemax21 v10, Moviebox v6, OneTouchTV v5, Anichin v6, Kuronime v12, TorraStream v88)
- `eda6242` Dutamovie v16: optimize loadLinks — remove double fetch, dupes, wrong selectors
- `006ef77` Dutamovie v15: Embed4meVip + LivePlayerP2P extractors
- `ae6bfcb` Donghub v4: `newSubtitleFile` migration
- `f705839` Moviebox v5: removed `parseJson` import
- `ef9d8d6` Moviebox v4: `tryParseJson` migration
- `1c03403` TorraStream v85: tracker cache + getQuality fix
- `117ffd3` Dutamovie v4: `tryParseJson` migration
- `52b03c9` Kuronime v9: `tryParseJson` migration

## Seoulschool.org Analysis (2025-06)
Analyzed `seoulschool.org/page/kdrama/...` stream page — WordPress Muvipro theme with 8 player servers:

| Server | Domain | Player Tech | Status |
|--------|--------|-------------|--------|
| 1 | abyssplayer.com | Encrypted JW + SoTrym AES-CTR | ❌ Skipped (404 on desktop + heavy obfuscation) |
| 2 | dm21.embed4me.vip | Vidstack SPA + AES `/api/v1/` | ❌ Encrypted API |
| 3 | live.playerp2p.online | Vidstack SPA (#hash) | ❌ Same pattern as embed4me |
| 4 | voe.sx → jessicayeahcatch.com | JW Player encrypted config | ❌ Encrypted JW |
| 5 | minochinos.com | EarnVids file host + reCAPTCHA | ❌ CAPTCHA-protected |
| 6 | hgcloud.to | Lulustream-compatible | ✅ Already supported (Hgcloud) |
| 7 | dm21.upns.live | SPWA Vidstack | ✅ Supported (Dm21upns→VidStack) |
| 8 | veev.to | Lulustream-compatible | ✅ Already supported (Veev) |

### Practical additions
- **Embed4meVip**: extends P2PPlay with `mainUrl = "https://dm21.embed4me.vip"` — tries packed script approach
- **LivePlayerP2P**: extends P2PPlay with `mainUrl = "https://live.playerp2p.online"`
- voe.sx and minochinos.com have strong anti-scraping (JW encrypted / reCAPTCHA) — may need user input

## Key Files
- `SharedModule/` — shared library with common extractors, data models, and utilities
  - `src/main/kotlin/com/mwk/shared/data/` — Shared data classes (GofileModels, OkRuModels, TmdbModels, AniZipModels, KisskhModels)
  - `src/main/kotlin/com/mwk/shared/extractors/` — Shared extractors (Hglink, Ghbrisk, Dhcplay, Jeniusplay, Dingtezuni, Gofile, Odnoklassniki, Lulustream, P2PPlay, StreamHG)
  - `src/main/kotlin/com/mwk/shared/utils/` — Shared utilities (ElementExtensions, UrlUtils, QualityUtils, TmdbUtils)
- `OtakudesuProvider/src/main/kotlin/com/otakudesu/OtakudesuProvider.kt` (v10) — comprehensive audit: IndexOutOfBounds fix, TMDB key removed, Base64 try-catch, runBlocking removed, URL-encoded search, content-based selectors, withTimeoutOrNull on ani.zip.
- `KuronimeProvider/src/main/kotlin/com/kuronime/KuronimeProvider.kt` — AES key `"3&!Z0M,;dZWVIZ=="`, `decryptCryptoJS()`, `tryParseJson`, `src_sd` support, AES/CBC/PKCS5Padding.
- `TorraStreamProvider/src/main/kotlin/com/torrastream/` — `TorraStreamProvider.kt` (v88), magnet tracker cache, getQuality fix, 30s timeouts.
- `MovieboxProvider/src/main/kotlin/com/moviebox/MovieboxProvider.kt` (v6) — `tryParseJson<LoadData>()`, null-safe `maxEp`.
- `Cinemax21Provider/src/main/kotlin/com/cinemax21/Cinemax21ProviderExtractor.kt` (v10) — Mapple subtitle season/episode fix, Elements.toString() fix.
- `AnichinProvider/src/main/kotlin/com/anichin/ExtractorsVidGuard.kt` (v6) — tryParseJson fix, sigDecode bounds check.
- `AnichinProvider/src/main/kotlin/com/anichin/ExtractorsStreamRuby.kt` (v6) — m3u8 null early return.
- `OneTouchTVProvider/src/main/kotlin/com/onetouchtv/OneTouchTVParser.kt` (v5) — default values for data class fields.
- `MoviePedia21Provider/src/main/kotlin/com/moviepedia21/MoviePedia21Provider.kt` (v2) — Nonton Movie dan Serial TV.
- `repo.json` — plugin repo config pointing to `builds` branch.
- `README.md` — provider list.
- `build.gradle.kts` — root dep list (Jackson + Gson still bundled for `@JsonProperty`/`@SerializedName` annotations only).

## Remaining Notes
- Otakudesu v10: comprehensive audit complete — crash fixes, security fix (TMDB key removed), error handling, code quality improvements.
- Kuronime v9 AES streaming fix (manual EVP_BytesToKey decrypt) deployed but untested by user — user should download CI build and test Liar Game E1 streaming.
- If Kuronime still fails, next steps: add debug logging, test `src_sd`/`mirror` paths, or try `animeku.org/player2.php?id=<id>` as loadable URL.
