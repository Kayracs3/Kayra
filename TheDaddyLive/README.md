# TheDaddyLive CloudStream provider

- Provider: DaddyLive 24/7
- Site: https://thedaddy.li/24-7-channels.php
- Content type: Live TV
- Channel catalogue: public HTML page (the JSON channels API requires a private key)
- Playback: tries the six documented player folders and delegates embedded players to CloudStream extractors/WebViewResolver

The provider is marked beta until real-device playback is verified. It does not attempt to bypass the site's protected API or player authentication.
