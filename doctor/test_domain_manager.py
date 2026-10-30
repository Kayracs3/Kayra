import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import domain_manager as dm


def html_response(url="https://old.example/", title="SiteMarka Dizi Arsivi"):
    body = (
        f'<html><head><title>{title}</title>'
        '<meta property="og:site_name" content="SiteMarka">'
        '<meta property="og:description" content="Turkiye dizi arsivi"></head>'
        '<body><h1>SiteMarka</h1><p>' + ("Turkiye dizi arsivi " * 20) + '</p></body></html>'
    ).encode()
    return {
        "status": 200, "finalUrl": url, "contentType": "text/html",
        "location": "", "body": body, "error": "",
    }


def test_provider(root: Path):
    module = root / "Demo"
    kt = module / "src/main/kotlin/com/Kayracs3/Demo.kt"
    kt.parent.mkdir(parents=True)
    kt.write_text(
        'class Demo : MainAPI() {\n    override var mainUrl = "https://old.example"\n}\n',
        encoding="utf-8",
    )
    (module / "build.gradle.kts").write_text("version = 2\n", encoding="utf-8")
    return {
        "name": "Demo", "module": "Demo", "url": "https://old.example",
        "source": str(kt),
    }, kt


class DomainManagerTests(unittest.TestCase):
    def test_parse_page_extracts_site_identity_and_canonical(self):
        body = b'''<html><head><title>Ornek Dizi | SiteMarka</title>
        <meta property="og:site_name" content="SiteMarka">
        <meta property="og:description" content="SiteMarka film ve dizi arsivi">
        <link rel="canonical" href="https://new-site.example/">
        </head><body><h1>SiteMarka</h1></body></html>'''
        data = dm.parse_page(body, "https://old-site.example/")
        self.assertEqual(data["title"], "Ornek Dizi | SiteMarka")
        self.assertEqual(data["h1"], "SiteMarka")
        self.assertEqual(data["canonicalUrls"], ["https://new-site.example/"])
        self.assertIn("sitemarka", data["tokens"])

    def test_identity_match_accepts_matching_brand_and_rejects_lookalike(self):
        saved = {"tokens": ["sitemarka", "dizi", "arsivi", "turkiye"]}
        same = {
            "title": "SiteMarka Dizi Arsivi", "h1": "SiteMarka",
            "siteName": "SiteMarka", "description": "Turkiye dizi arsivi",
            "tokens": ["sitemarka", "dizi", "arsivi", "turkiye"],
        }
        impostor = {
            "title": "Baska Site", "h1": "Baska Site", "siteName": "Baska Site",
            "description": "Farkli icerik", "tokens": ["baska", "farkli", "icerik"],
        }
        self.assertTrue(dm.identity_match(saved, same)[0])
        self.assertFalse(dm.identity_match(saved, impostor)[0])

    def test_explicit_identity_markers_are_all_required(self):
        candidate = {
            "title": "Kayra Dizi Platformu", "h1": "Kayra", "siteName": "Kayra",
            "description": "Dizi platformu", "tokens": ["kayra", "dizi", "platformu"],
        }
        self.assertTrue(dm.identity_match(None, candidate, ["Kayra", "Dizi Platformu"])[0])
        self.assertFalse(dm.identity_match(None, candidate, ["Kayra", "Olmayan Marka"])[0])

    def test_normalize_candidate_rejects_same_host(self):
        self.assertIsNone(dm.normalize_candidate("https://www.example.com/new", "https://example.com"))
        self.assertEqual(
            dm.normalize_candidate("new.example.net/path", "https://example.com"),
            "https://new.example.net",
        )

    def test_domain_update_changes_main_url_and_bumps_only_provider_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            gradle = root / "Demo/build.gradle.kts"
            gradle.write_text(
                'version = 7\ncloudstream { iconUrl = "https://old.example/favicon.ico" }\n',
                encoding="utf-8",
            )
            changed = dm.update_module_domain(
                root, provider, "https://old.example", "https://new.example"
            )
            updated_kt = kt.read_text(encoding="utf-8")
            updated_gradle = gradle.read_text(encoding="utf-8")
            self.assertIn('mainUrl = "https://new.example"', updated_kt)
            self.assertIn("version = 8", updated_gradle)
            self.assertIn("https://new.example/favicon.ico", updated_gradle)
            self.assertEqual(len(changed), 2)

    def test_working_200_domain_is_never_changed_even_if_candidates_exist(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            gradle = root / "Demo/build.gradle.kts"
            entry = {
                "identityProfile": {"tokens": ["sitemarka", "dizi", "arsivi", "turkiye"]},
                "candidateDomains": ["https://new.example"],
            }
            live = html_response()
            with patch.object(dm, "fetch_page", return_value=live) as fetch, \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, entry, apply=True)
            self.assertEqual(result["status"], "ok")
            self.assertEqual(fetch.call_count, 1)
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))
            self.assertIn("version = 2", gradle.read_text(encoding="utf-8"))

    def test_working_redirect_is_never_changed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            live_redirect = html_response("https://new.example/")
            with patch.object(dm, "fetch_page", return_value=live_redirect) as fetch, \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "ok-redirected")
            self.assertEqual(fetch.call_count, 1)
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))
            self.assertIn("version = 2", (root / "Demo/build.gradle.kts").read_text(encoding="utf-8"))

    def test_403_protection_does_not_trigger_domain_search(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            blocked = {
                "status": 403, "finalUrl": "https://old.example/",
                "contentType": "text/html", "body": b"Access denied",
                "error": "HTTP Error 403",
            }
            with patch.object(dm, "fetch_page", return_value=blocked) as fetch, \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "protected")
            self.assertEqual(fetch.call_count, 1)
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))

    def test_429_rate_limit_does_not_trigger_domain_search(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            limited = {
                "status": 429, "finalUrl": "https://old.example/",
                "contentType": "text/html", "body": b"Too many requests",
                "error": "HTTP Error 429",
            }
            with patch.object(dm, "fetch_page", return_value=limited), \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "protected")
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))

    def test_timeout_does_not_trigger_domain_search(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            timeout = {
                "status": None, "finalUrl": "https://old.example/",
                "contentType": "", "body": b"",
                "error": "The read operation timed out",
            }
            with patch.object(dm, "fetch_page", return_value=timeout) as fetch, \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "inconclusive")
            self.assertEqual(fetch.call_count, 1)
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))

    def test_single_404_is_not_enough_to_switch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            old_down = {
                "status": 404, "finalUrl": "https://old.example/",
                "contentType": "text/html", "body": b"Not found", "error": "404",
            }
            recovered = html_response()
            with patch.object(dm, "fetch_page", side_effect=[old_down, recovered]) as fetch, \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "inconclusive")
            self.assertEqual(fetch.call_count, 2)
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))

    def test_domain_switch_requires_two_404s_and_verified_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            entry = {
                "identityProfile": {"tokens": ["sitemarka", "dizi", "arsivi", "turkiye"]},
                "candidateDomains": ["https://new.example"],
            }
            old_down = {
                "status": 404, "finalUrl": "https://old.example/",
                "contentType": "text/html", "body": b"Not found", "error": "404",
            }
            new_page = html_response("https://new.example/")
            with patch.object(dm, "fetch_page", side_effect=[old_down, old_down, new_page]) as fetch:
                result = dm.inspect_provider(root, provider, entry, apply=True)
            self.assertEqual(result["status"], "switched")
            self.assertEqual(result["primaryFailureConfirmed"], "http-404")
            self.assertEqual(fetch.call_count, 3)
            self.assertIn('mainUrl = "https://new.example"', kt.read_text(encoding="utf-8"))
            self.assertIn("version = 3", (root / "Demo/build.gradle.kts").read_text(encoding="utf-8"))

    def test_parked_200_page_requires_confirmation_and_does_not_match_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            parked = html_response(title="Parked domain - Domain for sale")
            with patch.object(dm, "fetch_page", side_effect=[parked, parked]) as fetch, \
                 patch.object(dm, "configured_candidates", return_value=[]), \
                 patch.object(dm, "search_candidates", return_value=[]):
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "confirmed-dead-no-replacement")
            self.assertEqual(fetch.call_count, 2)
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))

    def test_two_tls_hostname_mismatches_allow_verified_replacement(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            entry = {
                "identityProfile": {
                    "tokens": ["dizi", "life", "diziler", "film", "filmler", "izleme", "platformu", "turkce"]
                },
                "candidateDomains": ["https://new.example"],
            }
            tls_error = {
                "status": None, "finalUrl": "https://old.example/",
                "contentType": "", "body": b"",
                "error": "[SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed: Hostname mismatch, certificate is not valid for 'old.example' (ERR_CERT_COMMON_NAME_INVALID)",
            }
            candidate_body = (
                '<html><head><title>Dizi.life - Diziler ve Filmler</title>'
                '<meta property="og:site_name" content="Dizi.life">'
                '<meta property="og:description" content="Dizi.life Türkçe dizi ve film izleme platformu">'
                '</head><body><h1>Dizi.life — Türkçe dizi ve film izleme platformu</h1>'
                + ('Dizi diziler film filmler izleme platformu Türkçe yeni bölüm ' * 30)
                + '</body></html>'
            ).encode("utf-8")
            candidate = {
                "status": 200, "finalUrl": "https://new.example/",
                "contentType": "text/html", "body": candidate_body, "error": "",
            }
            with patch.object(dm, "fetch_page", side_effect=[tls_error, tls_error, candidate]) as fetch:
                result = dm.inspect_provider(root, provider, entry, apply=True)
            self.assertEqual(result["status"], "switched")
            self.assertEqual(result["primaryFailureConfirmed"], "tls-hostname-mismatch")
            self.assertEqual(fetch.call_count, 3)
            self.assertIn('mainUrl = "https://new.example"', kt.read_text(encoding="utf-8"))

    def test_single_tls_hostname_mismatch_is_not_enough(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            provider, kt = test_provider(root)
            mismatch = {
                "status": None, "finalUrl": "https://old.example/",
                "contentType": "", "body": b"",
                "error": "Hostname mismatch: certificate is not valid for old.example",
            }
            recovered = html_response()
            with patch.object(dm, "fetch_page", side_effect=[mismatch, recovered]) as fetch, \
                 patch.object(dm, "search_candidates") as search:
                result = dm.inspect_provider(root, provider, {}, apply=True)
            self.assertEqual(result["status"], "inconclusive")
            self.assertEqual(fetch.call_count, 2)
            search.assert_not_called()
            self.assertIn('mainUrl = "https://old.example"', kt.read_text(encoding="utf-8"))

    def test_generic_ssl_issuer_failure_does_not_trigger_switch(self):
        error = {
            "status": None, "finalUrl": "https://old.example/",
            "contentType": "", "body": b"",
            "error": "[SSL: CERTIFICATE_VERIFY_FAILED] unable to get local issuer certificate",
        }
        self.assertIsNone(dm.classify_primary_failure(error))

    def test_search_results_only_nominate_candidates_for_later_identity_check(self):
        provider = {"name": "Demo", "module": "Demo", "url": "https://old.example"}
        profile = {
            "siteName": "SiteMarka", "title": "SiteMarka Dizi Arsivi",
            "tokens": ["sitemarka", "dizi", "arsivi"],
        }
        rss = b'<?xml version="1.0"?><rss><channel><item><title>SiteMarka</title><link>https://new.example/</link></item><item><title>Lookalike</title><link>https://lookalike.example/</link></item></channel></rss>'
        response = {
            "status": 200, "body": rss,
            "finalUrl": "https://www.bing.com/search?format=rss",
            "contentType": "application/rss+xml", "error": "",
        }
        with patch.object(dm, "fetch_page", return_value=response) as fetch:
            candidates = dm.search_candidates(provider, profile, "https://old.example")
        self.assertTrue(any(url == "https://new.example" for url, _ in candidates))
        self.assertTrue(any(url == "https://lookalike.example" for url, _ in candidates))
        self.assertGreaterEqual(fetch.call_count, 1)

    def test_discover_modules_ignores_non_provider_classes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            module = root / "Demo"
            (module / "src/main/kotlin/com/Kayracs3").mkdir(parents=True)
            (module / "build.gradle.kts").write_text("version = 1\n", encoding="utf-8")
            (module / "src/main/kotlin/com/Kayracs3/Demo.kt").write_text(
                'class Demo : MainAPI() {\n    override var mainUrl = "https://demo.example"\n}\n',
                encoding="utf-8",
            )
            self.assertEqual(dm.discover_modules(root)[0]["name"], "Demo")


if __name__ == "__main__":
    unittest.main(verbosity=2)
