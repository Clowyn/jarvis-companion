"""
Empirical Challenger M3-2 Verification Suite for Milestone 3.
Target: In-Game Companion Action Validation, World Mutation & Boundary Testing.

Empirically tests and exercises:
1. All 9 polymorphic companion action types:
   - move_to (coordinates, speed, status updating, target reflection)
   - follow (player tracking, distance parameter, following state)
   - stop (halts movement, resets to idle state, clears target)
   - teleport (instant relocation, position update)
   - attack (damage application, health depletion, target defeat, entity removal)
   - break_block & mine alias (removes block, validates drop, updates surroundings)
   - place_block & place alias (places block in world, updates surroundings)
   - interact_block & use alias (interaction with blocks/workstations)
   - inspect_container (inspects items in container slots, empty coordinate query)
2. Boundary and negative input handling (HTTP 400 Bad Request):
   - Unspawned action execution for all 9 actions
   - Unspawned despawn attempts
   - Missing / null / empty / non-string action parameters
   - Missing required coordinates/parameters per action type
   - Invalid / malformed coordinate data types (non-numeric, strings, structures)
   - Invalid entity ID types for attack
3. Entity lookup boundaries (HTTP 404 Not Found):
   - Attack against non-existent entity IDs (positive, negative, zero)
   - Attack against previously defeated / removed entity
4. HTTP method restrictions (HTTP 405 Method Not Allowed):
   - GET /api/companion/spawn
   - GET /api/companion/action
   - GET /api/companion/despawn
   - POST /api/companion/status
   - PUT, DELETE, PATCH on companion endpoints
5. Complex action chaining and lifecycle state machine transitions:
   - Full 9-action lifecycle without restart
   - Spawn -> Mutate -> Despawn -> Reject -> Re-spawn recovery
"""

from __future__ import annotations

import pytest
import httpx


pytestmark = pytest.mark.asyncio


# ==============================================================================
# 1. All 9 Companion Action Types Empirically Exercised (Positive Tests)
# ==============================================================================

async def test_action_1_move_to_success(async_client: httpx.AsyncClient):
    """move_to: Valid coordinates and speed initiate navigation and update state."""
    # 1. Spawn companion
    spawn_res = await async_client.post("/api/companion/spawn", json={"x": 100.5, "y": 64.0, "z": -200.5})
    assert spawn_res.status_code == 200

    # 2. Execute move_to
    target_x, target_y, target_z, speed = 120.5, 65.0, -180.0, 1.25
    action_res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": target_x, "y": target_y, "z": target_z, "speed": speed},
    )
    assert action_res.status_code == 200
    data = action_res.json()
    assert data["success"] is True
    assert data["action"] == "move_to"
    assert data["status"] == "executed"
    assert data["details"]["target"]["x"] == target_x
    assert data["details"]["target"]["y"] == target_y
    assert data["details"]["target"]["z"] == target_z
    assert data["details"]["speed"] == speed

    # 3. Verify status reflects navigation state and targets
    status_res = await async_client.get("/api/companion/status")
    assert status_res.status_code == 200
    status = status_res.json()
    assert status["spawned"] is True
    assert status["state"] == "navigating"
    assert status["target_x"] == target_x
    assert status["target_y"] == target_y
    assert status["target_z"] == target_z


async def test_action_2_follow_success(async_client: httpx.AsyncClient):
    """follow: Valid player name and distance update companion state to following."""
    await async_client.post("/api/companion/spawn", json={})

    action_res = await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Steve", "distance": 4.5},
    )
    assert action_res.status_code == 200
    data = action_res.json()
    assert data["success"] is True
    assert data["action"] == "follow"
    assert data["status"] == "executed"
    assert data["details"]["player"] == "Steve"
    assert data["details"]["distance"] == 4.5

    status = (await async_client.get("/api/companion/status")).json()
    assert status["state"] == "following"
    assert status["target"]["player"] == "Steve"
    assert status["target"]["distance"] == 4.5


async def test_action_3_stop_success(async_client: httpx.AsyncClient):
    """stop: Stops current navigation or following, returning state to idle."""
    await async_client.post("/api/companion/spawn", json={})
    # Start moving first
    await async_client.post("/api/companion/action", json={"action": "move_to", "x": 150.0, "y": 64.0, "z": -150.0})

    # Now issue stop
    action_res = await async_client.post("/api/companion/action", json={"action": "stop"})
    assert action_res.status_code == 200
    data = action_res.json()
    assert data["success"] is True
    assert data["action"] == "stop"
    assert data["status"] == "executed"

    status = (await async_client.get("/api/companion/status")).json()
    assert status["state"] == "idle"
    assert status["target"] is None


async def test_action_4_teleport_success(async_client: httpx.AsyncClient):
    """teleport: Instantly updates coordinates and resets state to idle."""
    await async_client.post("/api/companion/spawn", json={"x": 100.0, "y": 64.0, "z": -200.0})

    tx, ty, tz = 500.0, 80.0, -600.0
    action_res = await async_client.post(
        "/api/companion/action",
        json={"action": "teleport", "x": tx, "y": ty, "z": tz},
    )
    assert action_res.status_code == 200
    data = action_res.json()
    assert data["success"] is True
    assert data["action"] == "teleport"
    assert data["status"] == "executed"
    assert data["details"]["position"]["x"] == tx
    assert data["details"]["position"]["y"] == ty
    assert data["details"]["position"]["z"] == tz

    status = (await async_client.get("/api/companion/status")).json()
    assert status["x"] == tx
    assert status["y"] == ty
    assert status["z"] == tz
    assert status["state"] == "idle"


async def test_action_5_attack_success_and_defeat(async_client: httpx.AsyncClient):
    """attack: Deals damage, updates remaining health, and confirms defeat upon zero health."""
    await async_client.post("/api/companion/spawn", json={})

    # Target: Zombie (ID 42, starting health 20.0, damage per hit = 5.0)
    # Hit 1
    hit1 = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert hit1.status_code == 200
    d1 = hit1.json()
    assert d1["success"] is True
    assert d1["details"]["target_id"] == 42
    assert d1["details"]["damage"] == 5.0
    assert d1["details"]["remaining_health"] == 15.0
    assert d1["details"]["defeated"] is False

    # Hit 2
    hit2 = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert hit2.status_code == 200
    assert hit2.json()["details"]["remaining_health"] == 10.0

    # Hit 3
    hit3 = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert hit3.status_code == 200
    assert hit3.json()["details"]["remaining_health"] == 5.0

    # Hit 4 (Defeat)
    hit4 = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert hit4.status_code == 200
    d4 = hit4.json()
    assert d4["details"]["remaining_health"] == 0.0
    assert d4["details"]["defeated"] is True

    # Check surroundings to confirm entity 42 is removed
    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    ids = [e["id"] for e in surroundings["entities"]]
    assert 42 not in ids


async def test_action_6_break_block_and_mine_alias(async_client: httpx.AsyncClient):
    """break_block: Removes block from world, yields drops, and supports 'mine' alias."""
    await async_client.post("/api/companion/spawn", json={})

    # 1. break_block on diamond ore at (102, 60, -198)
    res1 = await async_client.post(
        "/api/companion/action",
        json={"action": "break_block", "x": 102, "y": 60, "z": -198},
    )
    assert res1.status_code == 200
    d1 = res1.json()
    assert d1["success"] is True
    assert d1["action"] == "break_block"
    assert d1["details"]["dropped"] == "minecraft:diamond_ore"
    assert d1["details"]["pos"] == {"x": 102, "y": 60, "z": -198}

    # Verify diamond ore is gone from surroundings
    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    ore_blocks = [b for b in surroundings["blocks"] if b.get("pos") == {"x": 102, "y": 60, "z": -198}]
    assert len(ore_blocks) == 0

    # 2. 'mine' alias on iron ore at (98, 50, -205)
    res2 = await async_client.post(
        "/api/companion/action",
        json={"action": "mine", "x": 98, "y": 50, "z": -205},
    )
    assert res2.status_code == 200
    d2 = res2.json()
    assert d2["success"] is True
    assert d2["action"] == "break_block"
    assert d2["details"]["dropped"] == "minecraft:iron_ore"


async def test_action_7_place_block_and_place_alias(async_client: httpx.AsyncClient):
    """place_block: Places block into the world and supports 'place' alias."""
    await async_client.post("/api/companion/spawn", json={})

    # 1. place_block torch at (105, 65, -200)
    res1 = await async_client.post(
        "/api/companion/action",
        json={"action": "place_block", "x": 105, "y": 65, "z": -200, "item": "minecraft:torch"},
    )
    assert res1.status_code == 200
    d1 = res1.json()
    assert d1["success"] is True
    assert d1["action"] == "place_block"
    assert d1["details"]["block"] == "minecraft:torch"
    assert d1["details"]["pos"] == {"x": 105, "y": 65, "z": -200}

    # Verify block exists in surroundings
    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    placed_blocks = [b for b in surroundings["blocks"] if b.get("pos") == {"x": 105, "y": 65, "z": -200}]
    assert len(placed_blocks) >= 1
    assert placed_blocks[0]["block"] == "minecraft:torch"

    # 2. 'place' alias with cobblestone at (106, 65, -200)
    res2 = await async_client.post(
        "/api/companion/action",
        json={"action": "place", "x": 106, "y": 65, "z": -200, "item": "minecraft:cobblestone"},
    )
    assert res2.status_code == 200
    d2 = res2.json()
    assert d2["success"] is True
    assert d2["action"] == "place_block"
    assert d2["details"]["block"] == "minecraft:cobblestone"


async def test_action_8_interact_block_and_use_alias(async_client: httpx.AsyncClient):
    """interact_block: Successfully interacts with block and supports 'use' alias."""
    await async_client.post("/api/companion/spawn", json={})

    # 1. interact_block at crafting table (108, 64, -199)
    res1 = await async_client.post(
        "/api/companion/action",
        json={"action": "interact_block", "x": 108, "y": 64, "z": -199},
    )
    assert res1.status_code == 200
    d1 = res1.json()
    assert d1["success"] is True
    assert d1["action"] == "interact_block"
    assert d1["details"]["result"] == "interacted"
    assert d1["details"]["pos"] == {"x": 108, "y": 64, "z": -199}

    # 2. 'use' alias at furnace (101, 64, -200)
    res2 = await async_client.post(
        "/api/companion/action",
        json={"action": "use", "x": 101, "y": 64, "z": -200},
    )
    assert res2.status_code == 200
    d2 = res2.json()
    assert d2["success"] is True
    assert d2["action"] == "interact_block"
    assert d2["details"]["result"] == "interacted"


async def test_action_9_inspect_container_full_and_empty(async_client: httpx.AsyncClient):
    """inspect_container: Returns item inventory for valid chest and empty list for non-container."""
    await async_client.post("/api/companion/spawn", json={})

    # 1. Inspect chest at (105, 64, -200)
    res1 = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 105, "y": 64, "z": -200},
    )
    assert res1.status_code == 200
    d1 = res1.json()
    assert d1["success"] is True
    assert d1["action"] == "inspect_container"
    items = d1["details"]["items"]
    assert len(items) == 3
    assert items[0] == {"slot": 0, "item": "minecraft:iron_ingot", "count": 16}
    assert items[1] == {"slot": 1, "item": "minecraft:coal", "count": 32}
    assert items[2] == {"slot": 2, "item": "minecraft:diamond", "count": 3}

    # 2. Inspect coordinate without container
    res2 = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 0, "y": 0, "z": 0},
    )
    assert res2.status_code == 200
    d2 = res2.json()
    assert d2["success"] is True
    assert d2["details"]["items"] == []


# ==============================================================================
# 2. Boundary and Negative Cases (HTTP 400 Bad Request)
# ==============================================================================

@pytest.mark.parametrize("action_name,payload", [
    ("move_to", {"action": "move_to", "x": 100, "y": 64, "z": -200}),
    ("follow", {"action": "follow", "player": "Steve"}),
    ("stop", {"action": "stop"}),
    ("teleport", {"action": "teleport", "x": 100, "y": 64, "z": -200}),
    ("attack", {"action": "attack", "entity_id": 42}),
    ("break_block", {"action": "break_block", "x": 100, "y": 64, "z": -200}),
    ("place_block", {"action": "place_block", "x": 100, "y": 64, "z": -200, "item": "minecraft:torch"}),
    ("interact_block", {"action": "interact_block", "x": 100, "y": 64, "z": -200}),
    ("inspect_container", {"action": "inspect_container", "x": 100, "y": 64, "z": -200}),
])
async def test_unspawned_actions_rejected_400(async_client: httpx.AsyncClient, action_name: str, payload: dict):
    """Calling any companion action when companion is not spawned returns HTTP 400 Bad Request."""
    res = await async_client.post("/api/companion/action", json=payload)
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False
    assert "not spawned" in data.get("error", "").lower() or "not spawned" in data.get("message", "").lower()


async def test_unspawned_despawn_rejected_400(async_client: httpx.AsyncClient):
    """Calling despawn when companion is not spawned returns HTTP 400 Bad Request."""
    res = await async_client.post("/api/companion/despawn")
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False
    assert "not spawned" in data.get("message", "").lower() or "not spawned" in data.get("error", "").lower()


@pytest.mark.parametrize("invalid_action_payload", [
    {},
    {"action": None},
    {"action": ""},
    {"action": "   "},
    {"action": 12345},
    {"action": ["move_to"]},
    {"action": {"name": "move_to"}},
    {"action": "unknown_action_xyz"},
])
async def test_invalid_or_missing_action_field_rejected_400(async_client: httpx.AsyncClient, invalid_action_payload: dict):
    """Missing, null, non-string, or unknown action names return HTTP 400 Bad Request."""
    await async_client.post("/api/companion/spawn", json={})

    res = await async_client.post("/api/companion/action", json=invalid_action_payload)
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False


@pytest.mark.parametrize("action_payload", [
    # move_to missing coordinates
    {"action": "move_to"},
    {"action": "move_to", "y": 64, "z": -200},
    {"action": "move_to", "x": 100, "z": -200},
    {"action": "move_to", "x": 100, "y": 64},
    # follow missing player
    {"action": "follow"},
    {"action": "follow", "player": None},
    {"action": "follow", "player": ""},
    # teleport missing coordinates
    {"action": "teleport"},
    {"action": "teleport", "x": 100, "y": 64},
    # break_block missing coordinates
    {"action": "break_block"},
    {"action": "break_block", "x": 100, "z": -200},
    # place_block missing coordinates
    {"action": "place_block"},
    {"action": "place_block", "y": 64, "z": -200},
    # interact_block missing coordinates
    {"action": "interact_block"},
    {"action": "interact_block", "x": 100, "y": 64},
    # inspect_container missing coordinates
    {"action": "inspect_container"},
    {"action": "inspect_container", "x": 100, "y": 64},
    # attack missing entity_id
    {"action": "attack"},
    {"action": "attack", "entity_id": None},
])
async def test_missing_required_parameters_rejected_400(async_client: httpx.AsyncClient, action_payload: dict):
    """Missing mandatory parameters for any action return HTTP 400 Bad Request."""
    await async_client.post("/api/companion/spawn", json={})

    res = await async_client.post("/api/companion/action", json=action_payload)
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False
    assert "missing" in data.get("error", "").lower() or "missing" in data.get("message", "").lower()


@pytest.mark.parametrize("invalid_type_payload", [
    # move_to non-numeric coordinates
    {"action": "move_to", "x": "not_a_number", "y": 64, "z": -200},
    {"action": "move_to", "x": 100, "y": "invalid", "z": -200},
    {"action": "move_to", "x": 100, "y": 64, "z": "bad_z_val"},
    {"action": "move_to", "x": [100], "y": 64, "z": -200},
    # teleport non-numeric coordinates
    {"action": "teleport", "x": "abc", "y": 64, "z": -200},
    {"action": "teleport", "x": 100, "y": {"y": 64}, "z": -200},
    # break_block non-integer coordinates
    {"action": "break_block", "x": "not_an_int", "y": 64, "z": -200},
    {"action": "break_block", "x": 100, "y": "abc", "z": -200},
    # place_block non-integer coordinates
    {"action": "place_block", "x": "bad", "y": 64, "z": -200},
    # interact_block non-integer coordinates
    {"action": "interact_block", "x": 100, "y": "bad", "z": -200},
    # inspect_container non-integer coordinates
    {"action": "inspect_container", "x": 100, "y": 64, "z": "bad"},
    # attack non-integer entity_id
    {"action": "attack", "entity_id": "zombie_entity"},
    {"action": "attack", "entity_id": [42]},
    {"action": "attack", "entity_id": {"id": 42}},
])
async def test_invalid_coordinate_or_parameter_types_rejected_400(async_client: httpx.AsyncClient, invalid_type_payload: dict):
    """Invalid parameter types (strings where numbers expected, objects, arrays) return HTTP 400."""
    await async_client.post("/api/companion/spawn", json={})

    res = await async_client.post("/api/companion/action", json=invalid_type_payload)
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False
    assert "invalid" in data.get("error", "").lower() or "invalid" in data.get("message", "").lower()


# ==============================================================================
# 3. Entity Lookup Boundaries (HTTP 404 Not Found)
# ==============================================================================

@pytest.mark.parametrize("nonexistent_eid", [99999, 1234567, -1, -42, 0])
async def test_attack_nonexistent_entity_returns_404(async_client: httpx.AsyncClient, nonexistent_eid: int):
    """Attacking a non-existent entity ID returns HTTP 404 Not Found."""
    await async_client.post("/api/companion/spawn", json={})

    res = await async_client.post(
        "/api/companion/action",
        json={"action": "attack", "entity_id": nonexistent_eid},
    )
    assert res.status_code == 404
    data = res.json()
    assert data["success"] is False
    assert "not found" in data.get("error", "").lower() or "not found" in data.get("message", "").lower()


async def test_attack_already_defeated_entity_returns_404(async_client: httpx.AsyncClient):
    """Attacking an entity that has already died/been defeated and removed returns HTTP 404."""
    await async_client.post("/api/companion/spawn", json={})

    # Entity 43 (Creeper, 20 HP)
    # Defeat with 4 strikes of 5.0 damage
    for _ in range(4):
        hit = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 43})
        assert hit.status_code == 200

    # 5th strike on the defeated entity must now return 404
    post_mortem_hit = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 43})
    assert post_mortem_hit.status_code == 404
    data = post_mortem_hit.json()
    assert data["success"] is False
    assert "not found" in data.get("error", "").lower()


@pytest.mark.parametrize("nonexistent_player", ["GhostPlayer999", "NoSuchPlayer", "Herobrine"])
async def test_follow_nonexistent_player_returns_404(async_client: httpx.AsyncClient, nonexistent_player: str):
    """Action follow with offline/nonexistent player returns HTTP 404 Not Found."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": nonexistent_player},
    )
    assert res.status_code == 404
    data = res.json()
    assert data["success"] is False
    assert "not found" in data.get("error", "").lower() or "not found" in data.get("message", "").lower()


# ==============================================================================
# 4. HTTP Method Restrictions (HTTP 405 Method Not Allowed)
# ==============================================================================

@pytest.mark.parametrize("method,endpoint", [
    ("GET", "/api/companion/spawn"),
    ("GET", "/api/companion/action"),
    ("GET", "/api/companion/despawn"),
    ("POST", "/api/companion/status"),
    ("PUT", "/api/companion/spawn"),
    ("PUT", "/api/companion/action"),
    ("PUT", "/api/companion/status"),
    ("PUT", "/api/companion/despawn"),
    ("DELETE", "/api/companion/spawn"),
    ("DELETE", "/api/companion/action"),
    ("DELETE", "/api/companion/status"),
    ("DELETE", "/api/companion/despawn"),
    ("PATCH", "/api/companion/spawn"),
    ("PATCH", "/api/companion/action"),
    ("PATCH", "/api/companion/status"),
    ("PATCH", "/api/companion/despawn"),
])
async def test_disallowed_http_methods_rejected_405(async_client: httpx.AsyncClient, method: str, endpoint: str):
    """Disallowed HTTP verbs on companion endpoints return HTTP 405 Method Not Allowed."""
    res = await async_client.request(method, endpoint)
    assert res.status_code == 405
    data = res.json()
    assert data["success"] is False
    assert "method not allowed" in data.get("error", "").lower() or "method not allowed" in data.get("message", "").lower()


# ==============================================================================
# 5. Complex Chaining, Transitions & Extreme Boundaries
# ==============================================================================

async def test_full_9_action_sequence_without_restart(async_client: httpx.AsyncClient):
    """Companion seamlessly completes all 9 actions in a continuous realistic sequence."""
    # 1. Spawn
    spawn_res = await async_client.post("/api/companion/spawn", json={"name": "Jarvis-Challenger", "x": 100.0, "y": 64.0, "z": -200.0})
    assert spawn_res.status_code == 200

    # 2. Action 1: Move to location
    act1 = await async_client.post("/api/companion/action", json={"action": "move_to", "x": 105.0, "y": 64.0, "z": -200.0})
    assert act1.status_code == 200
    assert act1.json()["action"] == "move_to"

    # 3. Action 2: Inspect chest at current position
    act2 = await async_client.post("/api/companion/action", json={"action": "inspect_container", "x": 105, "y": 64, "z": -200})
    assert act2.status_code == 200
    assert len(act2.json()["details"]["items"]) >= 3

    # 4. Action 3: Follow player
    act3 = await async_client.post("/api/companion/action", json={"action": "follow", "player": "Steve", "distance": 2.5})
    assert act3.status_code == 200
    assert act3.json()["action"] == "follow"

    # 5. Action 4: Stop
    act4 = await async_client.post("/api/companion/action", json={"action": "stop"})
    assert act4.status_code == 200
    assert act4.json()["action"] == "stop"

    # 6. Action 5: Interact with workstation
    act5 = await async_client.post("/api/companion/action", json={"action": "interact_block", "x": 108, "y": 64, "z": -199})
    assert act5.status_code == 200
    assert act5.json()["details"]["result"] == "interacted"

    # 7. Action 6: Break ore
    act6 = await async_client.post("/api/companion/action", json={"action": "break_block", "x": 102, "y": 60, "z": -198})
    assert act6.status_code == 200
    assert act6.json()["details"]["dropped"] == "minecraft:diamond_ore"

    # 8. Action 7: Place torch
    act7 = await async_client.post("/api/companion/action", json={"action": "place_block", "x": 102, "y": 61, "z": -198, "item": "minecraft:torch"})
    assert act7.status_code == 200
    assert act7.json()["details"]["block"] == "minecraft:torch"

    # 9. Action 8: Attack entity
    act8 = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert act8.status_code == 200
    assert act8.json()["details"]["damage"] == 5.0

    # 10. Action 9: Teleport home
    act9 = await async_client.post("/api/companion/action", json={"action": "teleport", "x": 100.0, "y": 64.0, "z": -200.0})
    assert act9.status_code == 200
    assert act9.json()["details"]["position"]["x"] == 100.0

    # 11. Clean despawn
    despawn_res = await async_client.post("/api/companion/despawn")
    assert despawn_res.status_code == 200
    assert despawn_res.json()["success"] is True

    # 12. Confirm unspawned status
    status = (await async_client.get("/api/companion/status")).json()
    assert status["spawned"] is False


async def test_spawn_despawn_respawn_lifecycle_integrity(async_client: httpx.AsyncClient):
    """Verifies that companion can be spawned, manipulated, despawned, rejects actions, and re-spawned cleanly."""
    # Spawn 1
    s1 = await async_client.post("/api/companion/spawn", json={"name": "Alpha"})
    assert s1.status_code == 200

    # Action succeeds
    a1 = await async_client.post("/api/companion/action", json={"action": "stop"})
    assert a1.status_code == 200

    # Despawn
    d1 = await async_client.post("/api/companion/despawn")
    assert d1.status_code == 200

    # Action fails (400)
    a2 = await async_client.post("/api/companion/action", json={"action": "stop"})
    assert a2.status_code == 400

    # Double despawn fails (400)
    d2 = await async_client.post("/api/companion/despawn")
    assert d2.status_code == 400

    # Respawn with new configuration
    s2 = await async_client.post("/api/companion/spawn", json={"name": "Beta", "x": 300.0, "y": 70.0, "z": -300.0})
    assert s2.status_code == 200

    # Action succeeds again
    a3 = await async_client.post("/api/companion/action", json={"action": "teleport", "x": 310.0, "y": 70.0, "z": -310.0})
    assert a3.status_code == 200

    status = (await async_client.get("/api/companion/status")).json()
    assert status["spawned"] is True
    assert status["name"] == "Beta"
    assert status["x"] == 310.0


async def test_extreme_numerical_coordinates(async_client: httpx.AsyncClient):
    """move_to and teleport accept extreme world coordinate values without crashing or integer overflow."""
    await async_client.post("/api/companion/spawn", json={})

    # Extreme world boundary (near 30 million blocks)
    extreme_x = 29999999.5
    extreme_y = 319.0
    extreme_z = -29999999.5

    # Teleport to extreme coords
    tp_res = await async_client.post(
        "/api/companion/action",
        json={"action": "teleport", "x": extreme_x, "y": extreme_y, "z": extreme_z},
    )
    assert tp_res.status_code == 200
    details = tp_res.json()["details"]["position"]
    assert details["x"] == extreme_x
    assert details["y"] == extreme_y
    assert details["z"] == extreme_z

    status = (await async_client.get("/api/companion/status")).json()
    assert status["x"] == extreme_x
    assert status["y"] == extreme_y
    assert status["z"] == extreme_z
