"""Strict offline preflight tests; no network, credentials or OAuth issuer deployed."""
import copy
import unittest
from server.oauth_preflight import validate_provider, public_https

ISSUER = "https://identity.example.org/tenant"
RESOURCE = "https://studyone.example.org/mcp"
METADATA = {
    "issuer": ISSUER,
    "authorization_endpoint": ISSUER + "/authorize",
    "token_endpoint": ISSUER + "/token",
    "jwks_uri": ISSUER + "/keys",
    "code_challenge_methods_supported": ["S256"],
    "response_types_supported": ["code"],
    "grant_types_supported": ["authorization_code", "refresh_token"],
    "scopes_supported": ["openid", "offline_access", "studyone.read"]
}


class ProviderPreflightTests(unittest.TestCase):
    def test_supported_metadata_only_preflight_never_enables_oauth(self):
        report = validate_provider(METADATA, ISSUER, RESOURCE)
        self.assertEqual(report["status"], "PASSED_STATIC_PREFLIGHT")
        self.assertFalse(report["oauthEnabled"])
        self.assertGreaterEqual(len(report["pending"]), 4)

    def test_reject_insecure_private_or_embedded_credentials(self):
        for url in ("http://identity.example.org",
                    "https://127.0.0.1/mcp", "https://10.1.2.3/mcp",
                    "https://localhost/mcp", "https://portal.local/mcp",
                    "https://user:password@id.example.org",
                    "https://id.example.org/path?secret=abc",
                    "https://id.example.org/#fragment"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                public_https(url)

    def test_metadata_endpoint_issuer_and_pkce_are_required(self):
        for key, invalid in (
            ("issuer", "https://untrusted.example.org"),
            ("authorization_endpoint", "http://example.org"),
            ("token_endpoint", "https://localhost/token"),
            ("jwks_uri", "https://192.168.1.5/keys"),
            ("code_challenge_methods_supported", ["plain"]),
            ("response_types_supported", ["token"]),
            ("grant_types_supported", ["authorization_code"]),
            ("scopes_supported", ["openid"])
        ):
            with self.subTest(key=key):
                bad = copy.deepcopy(METADATA)
                bad[key] = invalid
                with self.assertRaises(ValueError):
                    validate_provider(bad, ISSUER, RESOURCE)

    def test_resource_must_be_public_mcp_endpoint(self):
        with self.assertRaises(ValueError):
            validate_provider(METADATA, ISSUER,
                              "https://studyone.example.org/v2/snapshot")


if __name__ == "__main__":
    unittest.main()
