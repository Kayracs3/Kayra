# Kayra Repository Working Rules

These instructions record the maintainer's established preferences and lessons from previous fixes.

## Working style
- Communicate in Turkish.
- For routine fixes, inspect the current source and make the change directly; do not ask for confirmation before every small edit.
- Keep fixes narrow. Preserve unrelated, already-working behavior and avoid refactoring whole providers unless requested.
- Treat the current source on `master` as canonical. Inspect it before editing; do not rely on old snippets or assume a previously tested version is still current.
- Do not claim that runtime playback, subtitles, images, or metadata were tested unless the maintainer has tested them or there is explicit test evidence.

## Build and release workflow
- The maintainer manually tests plugins in CloudStream on Android. **Do not block publishing to the `builds` branch on emulator/runtime tests.** The user performs those tests.
- Still run/check the normal compiler and artifact checks. A failed compilation or missing `.cs3` artifact is not a passing build.
- Always inspect the newest relevant GitHub Actions run after pushing. Report `queued`, `pending`, `in_progress`, `success`, `failure`, and `cancelled` accurately; never call a pending run successful.
- Where practical, put a provider source change and its version bump in one commit to reduce duplicate workflow runs/cancellations.
- Bump the modified plugin's version in its own `build.gradle.kts`; do not bump unrelated providers.
- Runtime/emulator checks are informative and must not be presented as a release gate.

## Provider / package safety
- The provider source package convention is `com.Kayracs3`. Do not reintroduce `neoncs3` package references.
- Be precise about plugin identity: `InatBox` is not `InatTVBox`. Never edit a similarly named provider by assumption.
- Preserve existing provider registration, extractor registration, request headers, cookies, referers, and provider-specific logic unless the fix explicitly requires changing them.
- Do not add AI/API-token dependencies to repair or build plugins. Prefer deterministic local logic and ordinary GitHub Actions checks.

## Common playback pitfalls
- Never pass a JSON catalogue/object to CloudStream as if it were a video URL. For InatBox, catalogue records can contain `diziUrl`; follow the API response (series/season/episode levels) until a playable `chUrl` or a supported external extractor URL is found.
- HLS links should be validated as playlists when practical; an HTTP 200 HTML/JSON body is not an HLS manifest. Respect provider-specific `Referer`, `User-Agent`, cookies, and query parameters. Do not apply a blanket probe or header change that breaks other sources.
- Signed/expiring URLs can require a refresh. Keep existing InatBox HLS proxy/refresh behavior intact unless the issue is specifically in that code path.
- A browser working while CloudStream fails often points to request headers, cookies, redirects, expiring tokens, or an embed/player-specific URL. Investigate those before replacing the site's whole extraction strategy.
- Keep series metadata and episode playback data separate. Episode names can be correct even if the actual playback or poster fields were dropped during serialization.

## Artwork and metadata
- **TRDiziIzle placeholder rule:** never use `https://www.trdiziizle.tv/wp-content/themes/diziplus/images/none.png` (or any file named `none.png`) as a poster, even when it appears in `data-src`. Prefer the real `img[alt*=afis]` artwork from `/wp-content/uploads/...` and inspect both `src` and lazy-load attributes. Run `og:image` and other metadata through the same validation so a placeholder cannot suppress fallback.
- Use the provider's current artwork fields first; handle aliases, escaped slashes/entities, protocol-relative URLs, and relative paths where appropriate.
- Treat empty strings, `null`, and `0` as missing artwork values rather than URLs.
- For episode artwork, prefer episode-specific image fields, then season artwork, then series artwork. Do not overwrite a valid poster with an empty or malformed value.
- Do not guess an image from an unrelated field if it could return a logo, avatar, or banner.
- Normalize every artwork URL through the same provider-specific normalization helper, including `img src`, `srcset`, lazy-load attributes, Open Graph `og:image`, and Twitter card metadata. Do not let one metadata/fallback path bypass normalization.
- When posters fail, inspect the actual URLs and CloudStream/Coil logs before guessing. Distinguish TLS/network errors from decode errors such as invalid image input or a HEIF decoder failure.
- Check the image response's actual content type and format where practical. A site's image optimizer or CDN may return AVIF/HEIC even when the URL looks like a normal image; do not assume that CloudStream can decode it.
- Prefer an explicitly supported output format (WebP, JPEG, or PNG as appropriate) when a proxy/conversion service is needed. Avoid relying on an optimizer that keeps returning an incompatible format. Verify the converted URL/format before calling the issue fixed.
- Audit fallback branches after normalization: a fallback must not reintroduce a raw URL that was deliberately rejected or converted. In particular, do not fall back to a direct object-storage/R2 image URL when that URL is known to fail in CloudStream.
- DiziAsya's poster issue was resolved by normalizing Open Graph artwork, avoiding a raw R2 fallback, and using a proxy URL with an explicit WebP output. Reuse the debugging approach, not blindly the site-specific URL, on other providers.

## Subtitle behavior
- Keep language detection tied to each actual subtitle URL/track; a nearby label can belong to a different URL.
- If a URL is rediscovered later with explicit language metadata, do not let early generic deduplication suppress that better information.
- AsyaWatch currently has a provider-specific requirement to expose only Turkish and English subtitle options. Do not apply that filter globally to other providers.
- Preserve working subtitle URLs and request context; distinguish text subtitles (such as VTT/SRT) from subtitle HLS playlists where relevant.

## Automatic domain updates
- `DomainAutoUpdate.yml` runs on a schedule and can also be started manually. It learns site identity while configured domains are healthy.
- **Never change a working provider domain.** Any valid HTTP 2xx/3xx response is treated as working, including a successful redirect to another hostname; keep the original configured `mainUrl`.
- A domain is eligible for discovery only after the same hard failure is confirmed by two independent requests: HTTP 404/410, a parked-domain page, permanent DNS-not-found, or connection-refused.
- HTTP 401/403/429/451, Cloudflare challenges, 5xx responses, TLS errors and timeouts are protected/transient states. Report them and keep the current domain; do not search for or apply an alternative.
- Candidate discovery and identity verification run only after the current domain is confirmed dead. A candidate must pass identity verification before any source file is changed.
- A confirmed switch updates only that provider's Kotlin host references, increments its plugin version, and dispatches the normal CloudStream compiler.

## Suggested check after every provider change
1. Review the edited Kotlin source and the provider version.
2. Check GitHub Actions for compilation and generated artifacts.
3. Report what changed, the exact Actions status, and what still needs the maintainer's on-device test.
