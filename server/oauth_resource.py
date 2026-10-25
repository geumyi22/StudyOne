"""Optional, fail-closed OAuth 2.0 MCP resource-server gateway (Phase 25).

External authorization server does authorization-code/PKCE and user consent.
This module validates its RS256 access tokens against an operator-pinned *public*
JWKS and explicitly provisioned issuer/subject -> StudyOne account mappings.

No OAuth credentials, public endpoints, identity accounts or tokens are created
on import. Missing PyJWT[crypto], absent operator config, malformed JWKS and
unlinked subjects fail closed. It is NOT an OAuth authorization server.
"""
from __future__ import annotations

import json
from pathlib import Path
import time

try:
    import jwt
except ImportError:
    jwt = None

try:
    from .oauth_preflight import public_https
    from .studyone_sync import ApiProblem
    from .private_sync import uuid_valid
except ImportError:
    from oauth_preflight import public_https
    from studyone_sync import ApiProblem
    from private_sync import uuid_valid

READ_SCOPE = "studyone.read"
PROPOSE_SCOPE = "studyone.propose"
SCOPES = (READ_SCOPE, PROPOSE_SCOPE)
RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource/mcp"
_MAX_JWT = 8192
_PRIVATE_JWK_FIELDS = {"d", "p", "q", "dp", "dq", "qi", "oth", "k"}


class OAuthResourceGateway:
    def __init__(self, registry, issuer, resource, jwks):
        if jwt is None:
            raise RuntimeError("OAuth RS256 verification requires PyJWT[crypto]")
        public_https(issuer)
        public_https(resource)
        if not resource.endswith("/mcp"):
            raise ValueError("MCP_RESOURCE_PATH_REQUIRED")
        if not isinstance(jwks, dict) or set(jwks) != {"keys"}:
            raise ValueError("INVALID_PUBLIC_JWKS")
        keys = jwks["keys"]
        if not isinstance(keys, list) or not 1 <= len(keys) <= 10:
            raise ValueError("INVALID_PUBLIC_JWKS")
        self.public_keys = {}
        for item in keys:
            if (not isinstance(item, dict) or
                    item.get("kty") != "RSA" or
                    item.get("alg", "RS256") != "RS256" or
                    item.get("use", "sig") != "sig" or
                    any(k in item for k in _PRIVATE_JWK_FIELDS)):
                raise ValueError("ONLY_PUBLIC_RS256_SIGNING_KEYS_ALLOWED")
            kid = item.get("kid")
            if (not isinstance(kid, str) or not 1 <= len(kid) <= 100 or
                    kid in self.public_keys or
                    not isinstance(item.get("n"), str) or
                    not isinstance(item.get("e"), str)):
                raise ValueError("INVALID_JWK_KEY_ID")
            try:
                self.public_keys[kid] = jwt.algorithms.RSAAlgorithm.from_jwk(
                    json.dumps(item))
            except (ValueError, TypeError, KeyError) as exc:
                raise ValueError("INVALID_PUBLIC_JWKS") from exc
        self.registry = registry
        self.issuer = issuer
        self.resource = resource
        with self.registry.connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS oauth_account_links (
                    issuer TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    account_id TEXT NOT NULL REFERENCES accounts(id),
                    revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)),
                    PRIMARY KEY(issuer, subject)
                )
            """)

    @classmethod
    def from_config(cls, registry, config_file):
        path = Path(config_file).expanduser().resolve()
        if path.stat().st_size > 32768:
            raise ValueError("OAUTH_CONFIG_TOO_LARGE")
        data = json.loads(path.read_text("utf-8"))
        if not isinstance(data, dict) or set(data) != {"issuer", "resource", "jwksFile"}:
            raise ValueError("INVALID_OAUTH_CONFIG")
        jwks_path = Path(data["jwksFile"])
        if not jwks_path.is_absolute():
            jwks_path = path.parent / jwks_path
        if jwks_path.stat().st_size > 65536:
            raise ValueError("JWKS_TOO_LARGE")
        return cls(registry, data["issuer"], data["resource"],
                   json.loads(jwks_path.read_text("utf-8")))

    @staticmethod
    def _subject(subject):
        if (not isinstance(subject, str) or not 1 <= len(subject) <= 255 or
                subject.strip() != subject or
                any(ord(char) < 33 or ord(char) == 127 for char in subject)):
            raise ValueError("INVALID_SUBJECT")
        return subject

    def link(self, account_id, subject):
        """Operator-only explicit mapping; no auto-link based on email/username."""
        self._subject(subject)
        if not uuid_valid(account_id):
            raise ValueError("INVALID_ACCOUNT")
        with self.registry.connect() as db:
            account = db.execute("SELECT disabled FROM accounts WHERE id=?",
                                 (account_id,)).fetchone()
            if account is None or account["disabled"]:
                raise ValueError("UNKNOWN_OR_DISABLED_ACCOUNT")
            existing = db.execute("""
                SELECT account_id,revoked FROM oauth_account_links
                WHERE issuer=? AND subject=?
            """, (self.issuer, subject)).fetchone()
            if existing is not None and existing["account_id"] != account_id:
                raise ValueError("SUBJECT_ALREADY_BOUND_TO_DIFFERENT_ACCOUNT")
            db.execute("""
                INSERT INTO oauth_account_links (issuer,subject,account_id,revoked)
                VALUES(?,?,?,0)
                ON CONFLICT(issuer,subject) DO UPDATE SET revoked=0
            """, (self.issuer, subject, account_id))

    def unlink(self, subject):
        self._subject(subject)
        with self.registry.connect() as db:
            db.execute("""UPDATE oauth_account_links SET revoked=1
                       WHERE issuer=? AND subject=?""",
                       (self.issuer, subject))

    def authorize(self, raw_token):
        if (not isinstance(raw_token, str) or len(raw_token) > _MAX_JWT or
                raw_token.count(".") != 2):
            raise ApiProblem(401, "UNAUTHORIZED")
        try:
            header = jwt.get_unverified_header(raw_token)
            if (set(header) - {"alg", "typ", "kid"} or
                    header.get("alg") != "RS256" or
                    header.get("kid") not in self.public_keys or
                    header.get("typ") not in (None, "JWT", "at+jwt")):
                raise ApiProblem(401, "UNAUTHORIZED")
            claims = jwt.decode(
                raw_token, key=self.public_keys[header["kid"]],
                algorithms=["RS256"], issuer=self.issuer,
                audience=self.resource, leeway=30,
                options={"require": ["iss", "sub", "aud", "iat", "exp", "nbf"],
                         "verify_signature": True, "verify_aud": True,
                         "verify_iss": True, "verify_exp": True,
                         "verify_iat": True, "verify_nbf": True})
        except ApiProblem:
            raise
        except (jwt.PyJWTError, ValueError, TypeError, KeyError) as exc:
            raise ApiProblem(401, "UNAUTHORIZED") from exc
        issued, expires = claims.get("iat"), claims.get("exp")
        now = time.time()
        if (type(issued) is not int or type(expires) is not int or
                expires <= issued or expires - issued > 3600 or
                issued > now + 30):
            raise ApiProblem(401, "UNAUTHORIZED")
        subject = claims.get("sub")
        if not isinstance(subject, str):
            raise ApiProblem(401, "UNAUTHORIZED")
        try:
            self._subject(subject)
        except ValueError as exc:
            raise ApiProblem(401, "UNAUTHORIZED") from exc
        # JWT must explicitly target only this MCP resource; never accept
        # an ID token, device API token or another resource's access token.
        if claims.get("aud") != self.resource:
            raise ApiProblem(401, "UNAUTHORIZED")
        scopes = claims.get("scope", "")
        if not isinstance(scopes, str):
            raise ApiProblem(403, "INSUFFICIENT_SCOPE")
        scopes = frozenset(scopes.split())
        if READ_SCOPE not in scopes:
            raise ApiProblem(403, "INSUFFICIENT_SCOPE")
        with self.registry.connect() as db:
            row = db.execute("""
                SELECT l.account_id,l.revoked,a.disabled
                FROM oauth_account_links l JOIN accounts a ON a.id=l.account_id
                WHERE l.issuer=? AND l.subject=?
            """, (self.issuer, subject)).fetchone()
        if row is None or row["revoked"] or row["disabled"]:
            raise ApiProblem(403, "ACCOUNT_LINK_REQUIRED")
        return row["account_id"], scopes

    def metadata(self):
        return {"resource": self.resource,
                "authorization_servers": [self.issuer],
                "bearer_methods_supported": ["header"],
                "scopes_supported": list(SCOPES)}

    def challenge(self):
        return ('Bearer realm="StudyOne MCP", resource_metadata="'
                + self.resource.rsplit("/mcp", 1)[0]
                + RESOURCE_METADATA_PATH + '", scope="' + READ_SCOPE + '"')
