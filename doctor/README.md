# Kayra Doctor

Kayra Doctor is the three-layer health system for Kayra CloudStream providers.

## Layer 1 - Build

The workflow prepares the CloudStream Gradle plugin, runs `clean make makePluginsJson`, and validates every generated `.cs3` container.

When the `OPENAI_API_KEY` repository secret exists, compiler or HTTP failures can be sent to the repair agent. A repair is limited to one provider Kotlin file per attempt and is retried up to three times.

## Layer 2 - Web health

`doctor/http_health.py` discovers `mainUrl` values from provider Kotlin files, checks the homepage, and can run deeper URLs listed in `doctor/providers.json`.

HTTP 401, 403, 429 and 503 responses are reported as `protected` rather than automatically treating the site as dead.

## Layer 3 - Android smoke

The workflow starts an Android API 35 emulator, downloads the current CloudStream prerelease APK, installs the generated `.cs3` files into CloudStream's local plugin directory, launches CloudStream, and scans logcat for plugin loading failures.

This proves Android integration and plugin loading. Full end-to-end playback needs a known title/episode and provider-specific runtime test data, so those tests are designed as a later extension rather than pretending that a build alone proves playback.

## Automatic repair setup

Add repository secret `OPENAI_API_KEY` under GitHub Settings -> Secrets and variables -> Actions.

Optional repository variables:
- `OPENAI_MODEL`
- `OPENAI_BASE_URL`

The default model is `gpt-6-luna` and the default base URL is `https://api.openai.com/v1`.

Automatic repairs are made on `doctor/auto-fix-<run id>` and proposed as a pull request. They are not written directly to `master`.
