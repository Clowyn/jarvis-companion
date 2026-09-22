"""
Empirical Challenger 2 Verification Suite for Milestone 1.
Rigorously stress-tests and verifies:
1. 1MB payload limit enforcement (HTTP 413)
2. Malformed JSON handling on /api/say and /api/command (HTTP 400)
3. Unregistered route fallback (HTTP 404)
4. CORS headers on standard methods and OPTIONS preflight
5. Query parameter clamping (chat limit/since, surroundings radius)
6. Response schema conformance to PROJECT.md interface contracts
"""

from __future__ import annotations

import json
import pytest
import httpx


pytestmark = pytest.mark.asyncio


# ==============================================================================
# 1. Payload Limit Enforcement (1MB Boundary)
# ==============================================================================

async def test_payload_under_1mb_accepted(async_client: httpx.AsyncClient):
    """Payload under 1MB is accepted and processed cleanly."""
    safe_size = 500 * 1024  # 500 KB
    safe_body = json.dumps({"message": "x" * (safe_size - 50), "sender": "Challenger"})
    res = await async_client.post(
        "/api/say",
        content=safe_body,
        headers={"Content-Type": "application/json"}
    )
    assert res.status_code == 200
    assert res.json()["success"] is True


async def test_payload_exceeding_1mb_rejected_413(async_client: httpx.AsyncClient):
    """Payload exceeding 1MB (1024*1024 + 1 byte) returns HTTP 413 Payload Too Large."""
    oversized_bytes = (1024 * 1024) + 1  # 1MB + 1 byte exact boundary
    oversized_body = "x" * oversized_bytes
    res = await async_client.post(
        "/api/say",
        content=oversized_body,
        headers={"Content-Type": "application/json"}
    )
    assert res.status_code == 413
    data = res.json()
    assert data["success"] is False
    assert "payload" in data.get("error", "").lower() or res.status_code == 413


async def test_command_oversized_payload_rejected_413(async_client: httpx.AsyncClient):
    """POST /api/command with body exceeding 1MB returns HTTP 413."""
    oversized_bytes = (1024 * 1024) + 50
    oversized_body = "x" * oversized_bytes
    res = await async_client.post(
        "/api/command",
        content=oversized_body,
        headers={"Content-Type": "application/json"}
    )
    assert res.status_code == 413
    assert res.json()["success"] is False


# ==============================================================================
# 2. Malformed JSON Handling on /api/say and /api/command
# ==============================================================================

@pytest.mark.parametrize("endpoint,field", [
    ("/api/say", "message"),
    ("/api/command", "command")
])
async def test_malformed_json_syntax(async_client: httpx.AsyncClient, endpoint: str, field: str):
    """Truncated or syntactically invalid JSON returns HTTP 400."""
    invalid_syntaxes = [
        '{"' + field + '": "unfinished string',
        '{' + field + ': "missing quotes on key"}',
        '{"extra_comma": 1,}',
        '{"unclosed_brace": true',
        'not a json document at all'
    ]
    for bad_json in invalid_syntaxes:
        res = await async_client.post(
            endpoint,
            content=bad_json,
            headers={"Content-Type": "application/json"}
        )
        assert res.status_code == 400, f"Failed for syntax: {bad_json}"
        assert res.json()["success"] is False


@pytest.mark.parametrize("endpoint", ["/api/say", "/api/command"])
async def test_non_object_json_roots(async_client: httpx.AsyncClient, endpoint: str):
    """JSON primitives or arrays passed as root return HTTP 400."""
    non_objects = [
        '["item1", "item2"]',
        '"string_root"',
        '12345',
        'true',
        'null'
    ]
    for root in non_objects:
        res = await async_client.post(
            endpoint,
            content=root,
            headers={"Content-Type": "application/json"}
        )
        assert res.status_code == 400, f"Failed for root: {root}"
        assert res.json()["success"] is False


@pytest.mark.parametrize("endpoint", ["/api/say", "/api/command"])
async def test_empty_or_whitespace_body(async_client: httpx.AsyncClient, endpoint: str):
    """Empty or whitespace-only bodies return HTTP 400."""
    for empty in ["", "   ", "\t\n  \r\n"]:
        res = await async_client.post(
            endpoint,
            content=empty,
            headers={"Content-Type": "application/json"}
        )
        assert res.status_code == 400
        assert res.json()["success"] is False


async def test_say_missing_or_invalid_fields(async_client: httpx.AsyncClient):
    """POST /api/say validates required 'message' field presence and type."""
    # Missing message
    r1 = await async_client.post("/api/say", json={"sender": "Jarvis"})
    assert r1.status_code == 400
    assert r1.json()["success"] is False

    # Null message
    r2 = await async_client.post("/api/say", json={"message": None})
    assert r2.status_code == 400

    # Non-string message
    r3 = await async_client.post("/api/say", json={"message": 9999})
    assert r3.status_code == 400

    # Whitespace message
    r4 = await async_client.post("/api/say", json={"message": "   \n\t  "})
    assert r4.status_code == 400


async def test_command_missing_or_invalid_fields(async_client: httpx.AsyncClient):
    """POST /api/command validates required 'command' field presence and type."""
    # Missing command
    r1 = await async_client.post("/api/command", json={})
    assert r1.status_code == 400
    assert r1.json()["success"] is False

    # Null command
    r2 = await async_client.post("/api/command", json={"command": None})
    assert r2.status_code == 400

    # Non-string command
    r3 = await async_client.post("/api/command", json={"command": ["gamemode", "creative"]})
    assert r3.status_code == 400

    # Whitespace command
    r4 = await async_client.post("/api/command", json={"command": "    "})
    assert r4.status_code == 400


# ==============================================================================
# 3. Unregistered Route Fallback (HTTP 404)
# ==============================================================================

@pytest.mark.parametrize("method,path", [
    ("GET", "/"),
    ("GET", "/api"),
    ("GET", "/api/unknown_endpoint"),
    ("POST", "/api/unknown_endpoint"),
    ("GET", "/api/status/invalid/nested"),
    ("GET", "/favicon.ico"),
    ("POST", "/api/say/extra"),
])
async def test_unregistered_routes_return_404(async_client: httpx.AsyncClient, method: str, path: str):
    """Any unregistered route returns HTTP 404 with structured error JSON."""
    if method == "GET":
        res = await async_client.get(path)
    else:
        res = await async_client.post(path, json={"foo": "bar"})
    assert res.status_code == 404
    data = res.json()
    assert data["success"] is False
    assert "error" in data


# ==============================================================================
# 4. CORS Headers Verification
# ==============================================================================

@pytest.mark.parametrize("endpoint", [
    "/api/status",
    "/api/say",
    "/api/chat",
    "/api/command",
    "/api/surroundings",
    "/api/nonexistent"
])
async def test_cors_preflight_options(async_client: httpx.AsyncClient, endpoint: str):
    """OPTIONS preflight returns HTTP 200 and standard CORS headers on all routes."""
    res = await async_client.options(endpoint)
    assert res.status_code == 200
    headers = res.headers
    assert headers.get("access-control-allow-origin") == "*"
    methods = headers.get("access-control-allow-methods", "")
    assert "GET" in methods and "POST" in methods
    allowed_headers = headers.get("access-control-allow-headers", "").lower()
    assert "content-type" in allowed_headers


@pytest.mark.parametrize("method,endpoint,payload", [
    ("GET", "/api/status", None),
    ("POST", "/api/say", {"message": "CORS check"}),
    ("GET", "/api/chat", None),
    ("POST", "/api/command", {"command": "time query daytime"}),
])
async def test_cors_headers_on_standard_responses(async_client: httpx.AsyncClient, method: str, endpoint: str, payload: dict | None):
    """All successful standard REST endpoints include Access-Control-Allow-Origin: *."""
    if method == "GET":
        res = await async_client.get(endpoint)
    else:
        res = await async_client.post(endpoint, json=payload)
    assert res.status_code == 200
    assert res.headers.get("access-control-allow-origin") == "*"


# ==============================================================================
# 5. Query Parameter Clamping
# ==============================================================================

async def test_chat_query_clamping(async_client: httpx.AsyncClient):
    """GET /api/chat enforces clamping on 'limit' and 'since' parameters."""
    # Seed messages
    for i in range(10):
        await async_client.post("/api/say", json={"message": f"Clamp msg {i}"})

    # Limit = 0 -> returns empty list
    r0 = await async_client.get("/api/chat?limit=0")
    assert r0.status_code == 200
    assert r0.json()["count"] == 0

    # Limit negative -> clamped to default
    r_neg = await async_client.get("/api/chat?limit=-5")
    assert r_neg.status_code == 200
    assert isinstance(r_neg.json()["messages"], list)

    # Limit oversized -> capped at available / 100
    r_huge = await async_client.get("/api/chat?limit=10000")
    assert r_huge.status_code == 200
    assert len(r_huge.json()["messages"]) <= 100

    # Since negative -> treated safely as 0
    r_since_neg = await async_client.get("/api/chat?since=-1000")
    assert r_since_neg.status_code == 200
    assert len(r_since_neg.json()["messages"]) > 0

    # Non-numeric query params fallback gracefully
    r_invalid = await async_client.get("/api/chat?limit=not_a_number&since=invalid")
    assert r_invalid.status_code == 200
    assert isinstance(r_invalid.json()["messages"], list)


async def test_surroundings_radius_clamping(async_client: httpx.AsyncClient):
    """GET /api/surroundings clamps radius to [2, 32] window."""
    # Sub-minimum clamped to 2
    r_zero = await async_client.get("/api/surroundings?radius=0")
    assert r_zero.status_code == 200
    assert r_zero.json()["radius"] == 2

    r_neg = await async_client.get("/api/surroundings?radius=-10")
    assert r_neg.status_code == 200
    assert r_neg.json()["radius"] == 2

    # Super-maximum clamped to 32
    r_huge = await async_client.get("/api/surroundings?radius=500")
    assert r_huge.status_code == 200
    assert r_huge.json()["radius"] == 32

    # Non-numeric defaults to safe default (e.g. 16 or 8)
    r_nan = await async_client.get("/api/surroundings?radius=not_a_number")
    assert r_nan.status_code == 200
    assert 2 <= r_nan.json()["radius"] <= 32


# ==============================================================================
# 6. Response Schema Contracts (PROJECT.md)
# ==============================================================================

async def test_status_response_schema_contract(async_client: httpx.AsyncClient):
    """Verify /api/status response strictly matches PROJECT.md contract."""
    res = await async_client.get("/api/status")
    assert res.status_code == 200
    data = res.json()

    # Required top-level keys
    assert data["status"] == "online"
    assert data["version"] == "1.21.1"
    assert isinstance(data["world_time"], int)
    assert isinstance(data["day_time"], int)
    assert isinstance(data["player_count"], int)
    assert isinstance(data["players"], list)

    # Required player fields
    if len(data["players"]) > 0:
        p = data["players"][0]
        assert isinstance(p["name"], str)
        assert isinstance(p["uuid"], str)
        assert isinstance(p["dimension"], str)
        assert isinstance(p["health"], (int, float))
        assert isinstance(p["max_health"], (int, float))
        assert isinstance(p["food_level"], int)
        assert "position" in p
        assert isinstance(p["position"]["x"], (int, float))
        assert isinstance(p["position"]["y"], (int, float))
        assert isinstance(p["position"]["z"], (int, float))


async def test_say_response_schema_contract(async_client: httpx.AsyncClient):
    """Verify /api/say response strictly matches PROJECT.md contract."""
    res = await async_client.post("/api/say", json={"message": "Contract verification message", "sender": "Jarvis"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["broadcasted"] is True


async def test_chat_response_schema_contract(async_client: httpx.AsyncClient):
    """Verify /api/chat response strictly matches PROJECT.md contract."""
    res = await async_client.get("/api/chat?limit=50&since=0")
    assert res.status_code == 200
    data = res.json()
    assert "count" in data
    assert isinstance(data["count"], int)
    assert "messages" in data
    assert isinstance(data["messages"], list)

    if len(data["messages"]) > 0:
        m = data["messages"][0]
        assert "id" in m
        assert "player" in m
        assert "message" in m
        assert "timestamp" in m
        assert "is_command" in m


async def test_command_response_schema_contract(async_client: httpx.AsyncClient):
    """Verify /api/command response strictly matches PROJECT.md contract."""
    res = await async_client.post("/api/command", json={"command": "time set day"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "output" in data
    assert isinstance(data["output"], list)
