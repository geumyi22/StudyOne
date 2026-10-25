#!/usr/bin/env python3
"""Offline OAuth/OIDC provider metadata preflight; never activates OAuth.

This validates PUBLIC metadata provided by an operator. It does not issue
tokens, bind HTTP endpoints, verify JWTs, authorize users, or deploy a server.
Do not mistake this for ChatGPT/OAuth integration completion.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
from pathlib import Path
from urllib.parse import urlsplit


def public_https(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 2048:
        raise ValueError("INVALID_HTTPS_URL")
    try:
        url = urlsplit(value)
        host = url.hostname
        port = url.port
    except ValueError as error:
        raise ValueError("INVALID_HTTPS_URL") from error
    if (url.scheme != "https" or not host or url.username is not None
            or url.password is not None or url.query or url.fragment
            or host == "localhost" or host.endswith((".localhost", ".local"))
            or host.endswith(".internal") or host.endswith(".test")):
        raise ValueError("PUBLIC_HTTPS_REQUIRED")
    # DNS may resolve to private IPs later. Real deployment must verify DNS,
    # certificates, request audience and trusted OIDC discovery over HTTPS.
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        if not address.is_global:
            raise ValueError("PUBLIC_HTTPS_REQUIRED")
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("INVALID_PORT")
    return value


def validate_provider(metadata, issuer, resource):
    """Check baseline OAuth code+PKCE+refresh capabilities without trusting tokens."""
    public_https(issuer)
    public_https(resource)
    if not resource.endswith("/mcp"):
        raise ValueError("MCP_RESOURCE_PATH_REQUIRED")
    if not isinstance(metadata, dict) or metadata.get("issuer") != issuer:
        raise ValueError("ISSUER_MISMATCH")
    for name in ("authorization_endpoint", "token_endpoint", "jwks_uri"):
        public_https(metadata.get(name))
    supported = metadata.get("code_challenge_methods_supported")
    if not isinstance(supported, list) or "S256" not in supported:
        raise ValueError("PKCE_S256_REQUIRED")
    supported = metadata.get("response_types_supported")
    if not isinstance(supported, list) or "code" not in supported:
        raise ValueError("AUTHORIZATION_CODE_REQUIRED")
    supported = metadata.get("grant_types_supported")
    if not isinstance(supported, list) or not {"authorization_code", "refresh_token"}.issubset(supported):
        raise ValueError("CODE_AND_REFRESH_GRANTS_REQUIRED")
    supported = metadata.get("scopes_supported")
    if not isinstance(supported, list) or "offline_access" not in supported:
        raise ValueError("REFRESH_SCOPE_NOT_ADVERTISED")
    return {
        "status": "PASSED_STATIC_PREFLIGHT",
        "issuer": issuer,
        "mcpResource": resource,
        "oauthEnabled": False,
        "pending": [
            "Configure a real provider and user-consent/login flow",
            "Validate access tokens with issuer, signature/introspection, audience and scopes",
            "Serve RFC9728 protected resource metadata and WWW-Authenticate",
            "Implement CSRF, redirect URI, PKCE state, refresh and revocation",
            "Verify actual ChatGPT connector login and account isolation over public TLS"
        ]
    }


def main():
    parser = argparse.ArgumentParser(
        description="Offline OAuth provider readiness validation, no deployment")
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--resource", required=True)
    parser.add_argument("--metadata", required=True, type=Path)
    args = parser.parse_args()
    raw = args.metadata.read_bytes()
    if len(raw) > 128_000:
        raise ValueError("METADATA_TOO_LARGE")
    result = validate_provider(json.loads(raw), args.issuer, args.resource)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
