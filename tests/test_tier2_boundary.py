"""
Tier 2: Boundary, Negative, and Error Handling Test Suite.
Verifies robust handling of:
- Empty and whitespace strings
- Oversized payloads and payload limits
- Malformed and invalid JSON bodies
- Missing required fields and unexpected schemas
- Invalid data types
- HTTP 405 Method Not Allowed across all endpoints
- HTTP 404 Unknown endpoints
- Surroundings radius boundaries and clamping ([2, 32])
- Chat query parameter boundaries (since, limit)
- Companion state invariants and unknown actions
- Extreme world coordinates (Minecraft +/-30,000,000 border)
- Fault injection (503 Service Unavailable, 500 Internal Server Error, Offline, Latency)
- Special characters, emojis, HTML/JSON injection, and escapes
"""

from __future__ import annotations

import time
import httpx
import pytest

from tests.mock_server import MockMinecraftState

pytestmark = pytest.mark.asyncio


# ==============================================================================
# Category 1: Empty and Whitespace Strings
# ==============================================================================

async def test_say_empty_string_rejected(async_client: httpx.AsyncClient):
    """POST /api/say with empty string returns 400 Bad Request."""
    res = await async_client.post("/api/say", json={"message": ""})
    assert res.status_code == 400
    assert res.json()["success"] is False
    assert "empty" in res.json()["error"].lower()


async def test_say_whitespace_only_rejected(async_client: httpx.AsyncClient):
    """POST /api/say with whitespace-only string returns 400 Bad Request."""
    res = await async_client.post("/api/say", json={"message": "   \t\n  "})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_command_empty_string_rejected(async_client: httpx.AsyncClient):
    """POST /api/command with empty string returns 400 Bad Request."""
    res = await async_client.post("/api/command", json={"command": ""})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_command_whitespace_only_rejected(async_client: httpx.AsyncClient):
    """POST /api/command with whitespace string returns 400 Bad Request."""
    res = await async_client.post("/api/command", json={"command": "    \t  "})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_companion_empty_action_rejected(async_client: httpx.AsyncClient):
    """POST /api/companion/action with empty action string returns 400 Bad Request."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": ""})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_companion_whitespace_action_rejected(async_client: httpx.AsyncClient):
    """POST /api/companion/action with whitespace action returns 400 Bad Request."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "   "})
    assert res.status_code == 400
    assert res.json()["success"] is False


# ==============================================================================
# Category 2: Payload Size & Boundary Limits
# ==============================================================================

async def test_say_massive_message_handled(async_client: httpx.AsyncClient):
    """POST /api/say with 10KB message is processed without crashing."""
    large_message = "A" * 10240
    res = await async_client.post("/api/say", json={"message": large_message, "sender": "Jarvis"})
    assert res.status_code == 200
    assert res.json()["success"] is True

    # Verify message recorded in chat
    chat_res = await async_client.get("/api/chat?limit=1")
    assert chat_res.json()["messages"][-1]["message"] == large_message


async def test_oversized_payload_rejected(async_client: httpx.AsyncClient):
    """POST with body exceeding 1MB returns 413 Payload Too Large."""
    huge_body = "x" * (1024 * 1024 + 100)
    headers = {"Content-Type": "application/json"}
    res = await async_client.post("/api/say", content=huge_body, headers=headers)
    assert res.status_code == 413
    assert res.json()["success"] is False


async def test_command_long_command_handled(async_client: httpx.AsyncClient):
    """POST /api/command with 4KB command string is safely handled."""
    long_cmd = "say " + ("word " * 800)
    res = await async_client.post("/api/command", json={"command": long_cmd})
    assert res.status_code == 200
    assert res.json()["success"] is True


async def test_companion_spawn_long_name_handled(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn with 256 character name is handled cleanly."""
    long_name = "Companion" + ("X" * 240)
    res = await async_client.post("/api/companion/spawn", json={"name": long_name})
    assert res.status_code == 200
    status = (await async_client.get("/api/companion/status")).json()
    assert status["name"] == long_name


# ==============================================================================
# Category 3: Malformed & Invalid JSON Payloads
# ==============================================================================

async def test_post_say_malformed_json_syntax(async_client: httpx.AsyncClient):
    """POST /api/say with broken JSON syntax returns 400 Malformed JSON."""
    res = await async_client.post(
        "/api/say",
        content='{"message": "broken',
        headers={"Content-Type": "application/json"},
    )
    assert res.status_code == 400
    assert "malformed" in res.json()["error"].lower()


async def test_post_command_malformed_json(async_client: httpx.AsyncClient):
    """POST /api/command with arbitrary non-JSON string returns 400 Malformed JSON."""
    res = await async_client.post(
        "/api/command",
        content="plain text command input",
        headers={"Content-Type": "application/json"},
    )
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_post_spawn_json_array_not_object(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn with JSON array returns 400 (expects JSON object)."""
    res = await async_client.post(
        "/api/companion/spawn",
        content='["item1", "item2"]',
        headers={"Content-Type": "application/json"},
    )
    assert res.status_code == 400
    assert "object" in res.json()["error"].lower()


async def test_post_action_json_primitive_string(async_client: httpx.AsyncClient):
    """POST /api/companion/action with JSON string returns 400."""
    res = await async_client.post(
        "/api/companion/action",
        content='"move_to"',
        headers={"Content-Type": "application/json"},
    )
    assert res.status_code == 400


async def test_post_empty_body_to_post_endpoint(async_client: httpx.AsyncClient):
    """POST /api/say with empty body returns 400."""
    res = await async_client.post(
        "/api/say",
        content="",
        headers={"Content-Type": "application/json"},
    )
    assert res.status_code == 400


# ==============================================================================
# Category 4: Missing Required Parameters
# ==============================================================================

async def test_say_missing_message_key(async_client: httpx.AsyncClient):
    """POST /api/say missing 'message' key returns 400."""
    res = await async_client.post("/api/say", json={"sender": "Jarvis"})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_command_missing_command_key(async_client: httpx.AsyncClient):
    """POST /api/command with empty object returns 400."""
    res = await async_client.post("/api/command", json={})
    assert res.status_code == 400


async def test_companion_move_missing_all_coords(async_client: httpx.AsyncClient):
    """POST /api/companion/action move_to without coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "move_to"})
    assert res.status_code == 400
    assert "missing" in res.json()["error"].lower()


async def test_companion_move_missing_x(async_client: httpx.AsyncClient):
    """POST /api/companion/action move_to missing x coordinate returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "move_to", "y": 64, "z": -200})
    assert res.status_code == 400


async def test_companion_move_missing_y(async_client: httpx.AsyncClient):
    """POST /api/companion/action move_to missing y coordinate returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "move_to", "x": 100, "z": -200})
    assert res.status_code == 400


async def test_companion_move_missing_z(async_client: httpx.AsyncClient):
    """POST /api/companion/action move_to missing z coordinate returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "move_to", "x": 100, "y": 64})
    assert res.status_code == 400


async def test_companion_teleport_missing_coords(async_client: httpx.AsyncClient):
    """POST /api/companion/action teleport missing coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "teleport"})
    assert res.status_code == 400


async def test_companion_follow_missing_player(async_client: httpx.AsyncClient):
    """POST /api/companion/action follow without player returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "follow"})
    assert res.status_code == 400


async def test_companion_break_missing_coords(async_client: httpx.AsyncClient):
    """POST /api/companion/action break_block missing coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "break_block"})
    assert res.status_code == 400


async def test_companion_place_missing_coords(async_client: httpx.AsyncClient):
    """POST /api/companion/action place_block missing coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "place_block", "item": "minecraft:torch"})
    assert res.status_code == 400


async def test_companion_inspect_missing_coords(async_client: httpx.AsyncClient):
    """POST /api/companion/action inspect_container missing coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "inspect_container"})
    assert res.status_code == 400


async def test_companion_attack_missing_entity_id(async_client: httpx.AsyncClient):
    """POST /api/companion/action attack missing entity_id returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "attack"})
    assert res.status_code == 400


# ==============================================================================
# Category 5: Invalid Data Types
# ==============================================================================

async def test_say_message_not_string(async_client: httpx.AsyncClient):
    """POST /api/say with numeric message value returns 400."""
    res = await async_client.post("/api/say", json={"message": 12345})
    assert res.status_code == 400


async def test_command_command_not_string(async_client: httpx.AsyncClient):
    """POST /api/command with list value returns 400."""
    res = await async_client.post("/api/command", json={"command": ["time", "set"]})
    assert res.status_code == 400


async def test_companion_action_not_string(async_client: httpx.AsyncClient):
    """POST /api/companion/action with numeric action returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": 42})
    assert res.status_code == 400


async def test_companion_move_coords_as_invalid_strings(async_client: httpx.AsyncClient):
    """POST /api/companion/action move_to with non-numeric string coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": "alpha", "y": "beta", "z": "gamma"},
    )
    assert res.status_code == 400
    assert "invalid" in res.json()["error"].lower()


async def test_companion_attack_entity_id_not_int(async_client: httpx.AsyncClient):
    """POST /api/companion/action attack with non-integer entity_id returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "attack", "entity_id": "not_an_id"},
    )
    assert res.status_code == 400


async def test_companion_break_coords_not_int(async_client: httpx.AsyncClient):
    """POST /api/companion/action break_block with non-integer coordinates returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "break_block", "x": "foo", "y": "bar", "z": "baz"},
    )
    assert res.status_code == 400


# ==============================================================================
# Category 6: HTTP Method Not Allowed (405) Verification
# ==============================================================================

async def test_method_not_allowed_post_status(async_client: httpx.AsyncClient):
    """POST /api/status returns 405 Method Not Allowed."""
    res = await async_client.post("/api/status", json={})
    assert res.status_code == 405


async def test_method_not_allowed_put_status(async_client: httpx.AsyncClient):
    """PUT /api/status returns 405 Method Not Allowed."""
    res = await async_client.put("/api/status")
    assert res.status_code == 405


async def test_method_not_allowed_delete_status(async_client: httpx.AsyncClient):
    """DELETE /api/status returns 405 Method Not Allowed."""
    res = await async_client.delete("/api/status")
    assert res.status_code == 405


async def test_method_not_allowed_get_say(async_client: httpx.AsyncClient):
    """GET /api/say returns 405 Method Not Allowed."""
    res = await async_client.get("/api/say")
    assert res.status_code == 405


async def test_method_not_allowed_delete_say(async_client: httpx.AsyncClient):
    """DELETE /api/say returns 405 Method Not Allowed."""
    res = await async_client.delete("/api/say")
    assert res.status_code == 405


async def test_method_not_allowed_post_chat(async_client: httpx.AsyncClient):
    """POST /api/chat returns 405 Method Not Allowed."""
    res = await async_client.post("/api/chat", json={})
    assert res.status_code == 405


async def test_method_not_allowed_get_command(async_client: httpx.AsyncClient):
    """GET /api/command returns 405 Method Not Allowed."""
    res = await async_client.get("/api/command")
    assert res.status_code == 405


async def test_method_not_allowed_post_surroundings(async_client: httpx.AsyncClient):
    """POST /api/surroundings returns 405 Method Not Allowed."""
    res = await async_client.post("/api/surroundings", json={})
    assert res.status_code == 405


async def test_method_not_allowed_get_spawn(async_client: httpx.AsyncClient):
    """GET /api/companion/spawn returns 405 Method Not Allowed."""
    res = await async_client.get("/api/companion/spawn")
    assert res.status_code == 405


async def test_method_not_allowed_get_action(async_client: httpx.AsyncClient):
    """GET /api/companion/action returns 405 Method Not Allowed."""
    res = await async_client.get("/api/companion/action")
    assert res.status_code == 405


async def test_method_not_allowed_post_companion_status(async_client: httpx.AsyncClient):
    """POST /api/companion/status returns 405 Method Not Allowed."""
    res = await async_client.post("/api/companion/status", json={})
    assert res.status_code == 405


# ==============================================================================
# Category 7: Unknown Endpoints (404)
# ==============================================================================

async def test_unknown_endpoint_get(async_client: httpx.AsyncClient):
    """GET to nonexistent path returns 404."""
    res = await async_client.get("/api/nonexistent_endpoint")
    assert res.status_code == 404
    assert res.json()["success"] is False


async def test_unknown_endpoint_post(async_client: httpx.AsyncClient):
    """POST to nonexistent path returns 404."""
    res = await async_client.post("/api/nonexistent_endpoint", json={})
    assert res.status_code == 404


async def test_unknown_root_path(async_client: httpx.AsyncClient):
    """GET / returns 404."""
    res = await async_client.get("/")
    assert res.status_code == 404


async def test_unknown_nested_path(async_client: httpx.AsyncClient):
    """GET /api/v2/status returns 404."""
    res = await async_client.get("/api/v2/status")
    assert res.status_code == 404


# ==============================================================================
# Category 8: Surroundings Radius Boundaries & Clamping
# ==============================================================================

async def test_surroundings_radius_zero_clamped_to_min(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=0 clamps radius to minimum safe radius 2."""
    res = await async_client.get("/api/surroundings?radius=0")
    assert res.status_code == 200
    assert res.json()["radius"] == 2


async def test_surroundings_radius_negative_clamped_to_min(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=-10 clamps radius to minimum safe radius 2."""
    res = await async_client.get("/api/surroundings?radius=-10")
    assert res.status_code == 200
    assert res.json()["radius"] == 2


async def test_surroundings_radius_exact_minimum(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=2 maintains radius 2."""
    res = await async_client.get("/api/surroundings?radius=2")
    assert res.status_code == 200
    assert res.json()["radius"] == 2


async def test_surroundings_radius_exact_maximum(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=32 maintains radius 32."""
    res = await async_client.get("/api/surroundings?radius=32")
    assert res.status_code == 200
    assert res.json()["radius"] == 32


async def test_surroundings_radius_oversized_clamped_to_max(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=99999 clamps radius to maximum safe radius 32."""
    res = await async_client.get("/api/surroundings?radius=99999")
    assert res.status_code == 200
    assert res.json()["radius"] == 32


async def test_surroundings_radius_non_numeric_defaults(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=invalid defaults to radius 16."""
    res = await async_client.get("/api/surroundings?radius=invalid")
    assert res.status_code == 200
    assert res.json()["radius"] == 16


async def test_surroundings_nonexistent_player_returns_404(async_client: httpx.AsyncClient):
    """GET /api/surroundings?player=GhostPlayer999 returns 404."""
    res = await async_client.get("/api/surroundings?player=GhostPlayer999")
    assert res.status_code == 404
    assert "not found" in res.json()["error"].lower()


# ==============================================================================
# Category 9: Chat Query Boundaries (since & limit)
# ==============================================================================

async def test_chat_since_negative_returns_all(async_client: httpx.AsyncClient):
    """GET /api/chat?since=-1 returns all recent messages."""
    res = await async_client.get("/api/chat?since=-1")
    assert res.status_code == 200
    assert len(res.json()["messages"]) >= 1


async def test_chat_since_far_future_returns_empty(async_client: httpx.AsyncClient):
    """GET /api/chat?since=999999999999999 returns empty list."""
    res = await async_client.get("/api/chat?since=999999999999999")
    assert res.status_code == 200
    assert res.json()["count"] == 0
    assert res.json()["messages"] == []


async def test_chat_since_non_numeric_defaults(async_client: httpx.AsyncClient):
    """GET /api/chat?since=invalid defaults to since=0 without crashing."""
    res = await async_client.get("/api/chat?since=invalid")
    assert res.status_code == 200
    assert len(res.json()["messages"]) >= 1


async def test_chat_limit_zero_returns_empty(async_client: httpx.AsyncClient):
    """GET /api/chat?limit=0 returns 0 messages."""
    res = await async_client.get("/api/chat?limit=0")
    assert res.status_code == 200
    assert res.json()["count"] == 0


async def test_chat_limit_negative_clamped_to_default(async_client: httpx.AsyncClient):
    """GET /api/chat?limit=-10 clamps to default limit."""
    res = await async_client.get("/api/chat?limit=-10")
    assert res.status_code == 200
    assert isinstance(res.json()["messages"], list)


async def test_chat_limit_huge_returns_up_to_available(async_client: httpx.AsyncClient):
    """GET /api/chat?limit=10000 returns all available messages."""
    res = await async_client.get("/api/chat?limit=10000")
    assert res.status_code == 200
    assert len(res.json()["messages"]) >= 1


# ==============================================================================
# Category 10: Companion State Invariants & Action Failures
# ==============================================================================

async def test_companion_action_before_spawn_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/action before spawn returns 400 Companion not spawned."""
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 100, "y": 64, "z": -200},
    )
    assert res.status_code == 400
    assert "not spawned" in res.json()["error"].lower()


async def test_companion_unknown_action_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/action with unrecognized action returns 400."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/action", json={"action": "fly_to_moon"})
    assert res.status_code == 400
    assert "unknown action" in res.json()["error"].lower()


async def test_companion_despawn_twice_fails(async_client: httpx.AsyncClient):
    """Despawning companion twice returns error on second attempt."""
    await async_client.post("/api/companion/spawn", json={})
    res1 = await async_client.post("/api/companion/despawn")
    assert res1.status_code == 200

    res2 = await async_client.post("/api/companion/despawn")
    assert res2.status_code == 400
    assert res2.json()["success"] is False


async def test_companion_attack_after_despawn_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/action attack after despawn fails."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post("/api/companion/despawn")
    res = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert res.status_code == 400


async def test_companion_move_after_despawn_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/action move after despawn fails."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post("/api/companion/despawn")
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 100, "y": 64, "z": -200},
    )
    assert res.status_code == 400


# ==============================================================================
# Category 11: Extreme Coordinates & World Boundary Limits
# ==============================================================================

async def test_companion_move_extreme_positive_coords(async_client: httpx.AsyncClient):
    """Companion navigation to positive world border (29,999,999) handled."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 29999999.0, "y": 320.0, "z": 29999999.0},
    )
    assert res.status_code == 200
    status = (await async_client.get("/api/companion/status")).json()
    assert status["position"]["x"] == 29999999.0
    assert status["position"]["y"] == 320.0


async def test_companion_move_extreme_negative_coords(async_client: httpx.AsyncClient):
    """Companion navigation to negative world border (-29,999,999) handled."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": -29999999.0, "y": -64.0, "z": -29999999.0},
    )
    assert res.status_code == 200
    status = (await async_client.get("/api/companion/status")).json()
    assert status["position"]["x"] == -29999999.0
    assert status["position"]["y"] == -64.0


async def test_companion_teleport_subzero_y(async_client: httpx.AsyncClient):
    """Companion teleport to bedrock layer (y = -64.0) handled."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "teleport", "x": 100.0, "y": -64.0, "z": -200.0},
    )
    assert res.status_code == 200
    assert res.json()["details"]["position"]["y"] == -64.0


async def test_companion_spawn_extreme_coordinates(async_client: httpx.AsyncClient):
    """Companion spawn at boundary coordinates handled cleanly."""
    res = await async_client.post(
        "/api/companion/spawn",
        json={"name": "BorderJarvis", "x": 29999990.0, "y": 100.0, "z": 29999990.0},
    )
    assert res.status_code == 200
    assert res.json()["position"]["x"] == 29999990.0


# ==============================================================================
# Category 12: Fault Injection & Resilience (503, 500, Offline, Latency)
# ==============================================================================

async def test_server_503_simulation_get(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """When simulate_503 is enabled, GET /api/status returns 503 Service Unavailable."""
    server_state.simulate_503 = True
    try:
        res = await async_client.get("/api/status")
        assert res.status_code == 503
        assert "not ready" in res.json()["error"].lower()
    finally:
        server_state.simulate_503 = False


async def test_server_503_simulation_post(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """When simulate_503 is enabled, POST /api/say returns 503."""
    server_state.simulate_503 = True
    try:
        res = await async_client.post("/api/say", json={"message": "Test during startup"})
        assert res.status_code == 503
    finally:
        server_state.simulate_503 = False


async def test_server_503_recovery(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """Disabling simulate_503 restores normal 200 OK operations immediately."""
    server_state.simulate_503 = True
    res_fail = await async_client.get("/api/status")
    assert res_fail.status_code == 503

    server_state.simulate_503 = False
    res_ok = await async_client.get("/api/status")
    assert res_ok.status_code == 200
    assert res_ok.json()["status"] == "online"


async def test_server_500_simulation(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """When simulate_500 is enabled, requests return 500 Internal Server Error."""
    server_state.simulate_500 = True
    try:
        res = await async_client.get("/api/status")
        assert res.status_code == 500
        assert "internal server error" in res.json()["error"].lower()
    finally:
        server_state.simulate_500 = False


async def test_server_offline_connection_refused(dead_server_url: str):
    """Connecting to closed/dead port raises connection error or timeout."""
    async with httpx.AsyncClient(base_url=dead_server_url, timeout=1.0) as dead_client:
        with pytest.raises((httpx.ConnectError, httpx.ConnectTimeout, httpx.NetworkError)):
            await dead_client.get("/api/status")


async def test_server_simulated_latency(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """Simulated response_delay delays response accordingly without error."""
    server_state.response_delay = 0.05  # 50ms delay
    try:
        start_time = time.perf_counter()
        res = await async_client.get("/api/status")
        elapsed = time.perf_counter() - start_time
        assert res.status_code == 200
        assert elapsed >= 0.045
    finally:
        server_state.response_delay = 0.0


# ==============================================================================
# Category 13: Special Character & Encoding Resilience
# ==============================================================================

async def test_say_html_tags_preserved_as_text(async_client: httpx.AsyncClient):
    """HTML tags (<script>alert(1)</script>) are treated as plain text strings."""
    xss_string = "<script>alert('pwned');</script>"
    res = await async_client.post("/api/say", json={"message": xss_string})
    assert res.status_code == 200
    chat = (await async_client.get("/api/chat?limit=1")).json()
    assert chat["messages"][-1]["message"] == xss_string


async def test_say_json_injection_string_preserved(async_client: httpx.AsyncClient):
    """JSON injection syntax (e.g. {\"admin\": true}) is safely preserved."""
    json_inject = '{"admin": true, "role": "root"}'
    res = await async_client.post("/api/say", json={"message": json_inject})
    assert res.status_code == 200
    chat = (await async_client.get("/api/chat?limit=1")).json()
    assert chat["messages"][-1]["message"] == json_inject


async def test_say_quotes_and_backslashes_escaped(async_client: httpx.AsyncClient):
    """Quotes, escaped characters, and backslashes are preserved verbatim."""
    quote_str = r'Path is C:\Users\Steve\AppData and "quoted string"'
    res = await async_client.post("/api/say", json={"message": quote_str})
    assert res.status_code == 200
    chat = (await async_client.get("/api/chat?limit=1")).json()
    assert chat["messages"][-1]["message"] == quote_str


async def test_say_newlines_and_tabs(async_client: httpx.AsyncClient):
    """Line breaks and tabs within chat strings are supported."""
    multiline = "Line 1\n\tTabbed Line 2\nLine 3"
    res = await async_client.post("/api/say", json={"message": multiline})
    assert res.status_code == 200
    chat = (await async_client.get("/api/chat?limit=1")).json()
    assert chat["messages"][-1]["message"] == multiline


async def test_command_special_characters(async_client: httpx.AsyncClient):
    """Console commands with symbols (!@#$%^&*()_+) execute safely."""
    cmd_special = "say Special: !@#$%^&*()_+-=[]{}|;':\",./<>?"
    res = await async_client.post("/api/command", json={"command": cmd_special})
    assert res.status_code == 200
    assert res.json()["success"] is True
