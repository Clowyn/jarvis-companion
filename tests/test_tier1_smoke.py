"""
Tier 1: Feature Isolation Smoke Tests.
Verifies every endpoint and companion action in isolation under normal operating conditions.
Covers:
- Server Telemetry (GET /api/status)
- Chat Broadcast (POST /api/say)
- Chat History Buffer (GET /api/chat)
- Command Execution (POST /api/command)
- Surroundings Perception (GET /api/surroundings)
- Companion Spawning (POST /api/companion/spawn)
- Companion Status (GET /api/companion/status)
- Companion Despawn (POST /api/companion/despawn)
- Companion Navigation (POST /api/companion/action - move_to, follow, stop, teleport)
- Companion Block Manipulation (POST /api/companion/action - break_block, place_block, interact_block)
- Companion Container Inspection (POST /api/companion/action - inspect_container)
- Companion Combat (POST /api/companion/action - attack)
- Cross-Feature State Consistency
"""

from __future__ import annotations

import pytest
import httpx

pytestmark = pytest.mark.asyncio


# ==============================================================================
# Feature 1: Server Telemetry (/api/status)
# ==============================================================================

async def test_status_returns_200_and_online(async_client: httpx.AsyncClient):
    """GET /api/status returns HTTP 200 with online status and 1.21.1 version."""
    res = await async_client.get("/api/status")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "online"
    assert data["version"] == "1.21.1"


async def test_status_player_telemetry_fields(async_client: httpx.AsyncClient):
    """GET /api/status returns player telemetry with UUID, dimension, health, and hunger."""
    res = await async_client.get("/api/status")
    assert res.status_code == 200
    data = res.json()
    players = data.get("players", [])
    assert len(players) >= 1
    steve = players[0]
    assert steve["name"] == "Steve"
    assert "uuid" in steve
    assert steve["dimension"] == "minecraft:overworld"
    assert steve["health"] == 20.0
    assert steve["max_health"] == 20.0
    assert steve["food_level"] == 20


async def test_status_coordinates_structure(async_client: httpx.AsyncClient):
    """GET /api/status player coordinates contain valid x, y, z numbers."""
    res = await async_client.get("/api/status")
    data = res.json()
    steve = data["players"][0]
    pos = steve["position"]
    assert isinstance(pos["x"], (int, float))
    assert isinstance(pos["y"], (int, float))
    assert isinstance(pos["z"], (int, float))
    assert pos["x"] == 100.5
    assert pos["y"] == 64.0
    assert pos["z"] == -200.5


async def test_status_world_timing_counters(async_client: httpx.AsyncClient):
    """GET /api/status returns non-negative integer world_time and day_time."""
    res = await async_client.get("/api/status")
    data = res.json()
    assert isinstance(data["world_time"], int)
    assert isinstance(data["day_time"], int)
    assert data["world_time"] >= 0
    assert data["day_time"] >= 0


async def test_status_player_counts_consistent(async_client: httpx.AsyncClient):
    """GET /api/status player_count matches length of players array."""
    res = await async_client.get("/api/status")
    data = res.json()
    assert data["player_count"] == len(data["players"])
    assert data["max_players"] >= data["player_count"]


# ==============================================================================
# Feature 2: Chat Broadcasting (/api/say)
# ==============================================================================

async def test_say_success_response(async_client: httpx.AsyncClient):
    """POST /api/say returns 200 with success and broadcasted flags."""
    res = await async_client.post("/api/say", json={"message": "System online", "sender": "Jarvis"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["broadcasted"] is True


async def test_say_appends_to_chat_history(async_client: httpx.AsyncClient):
    """POST /api/say adds the message to the chat history."""
    unique_msg = "Hello Jarvis Companion Alpha 1"
    await async_client.post("/api/say", json={"message": unique_msg, "sender": "Tester"})

    chat_res = await async_client.get("/api/chat")
    assert chat_res.status_code == 200
    messages = chat_res.json()["messages"]
    matching = [m for m in messages if m["message"] == unique_msg]
    assert len(matching) == 1
    assert matching[0]["player"] == "Tester"


async def test_say_default_sender_is_jarvis(async_client: httpx.AsyncClient):
    """POST /api/say defaults sender to 'Jarvis' if omitted."""
    await async_client.post("/api/say", json={"message": "Default sender test"})
    chat_res = await async_client.get("/api/chat")
    messages = chat_res.json()["messages"]
    last_msg = messages[-1]
    assert last_msg["message"] == "Default sender test"
    assert last_msg["player"] == "Jarvis"


async def test_say_formatted_color_codes(async_client: httpx.AsyncClient):
    """POST /api/say supports Minecraft formatting codes (§a, §6)."""
    color_msg = "§aGreen §6Gold §cRed Alert"
    res = await async_client.post("/api/say", json={"message": color_msg, "sender": "Jarvis"})
    assert res.status_code == 200
    chat_res = await async_client.get("/api/chat")
    last_msg = chat_res.json()["messages"][-1]
    assert last_msg["message"] == color_msg


async def test_say_unicode_and_emojis(async_client: httpx.AsyncClient):
    """POST /api/say safely handles UTF-8 characters and emojis."""
    emoji_msg = "Mining diamonds 💎 at level 12! ⚔️"
    res = await async_client.post("/api/say", json={"message": emoji_msg, "sender": "Jarvis"})
    assert res.status_code == 200
    chat_res = await async_client.get("/api/chat")
    last_msg = chat_res.json()["messages"][-1]
    assert last_msg["message"] == emoji_msg


# ==============================================================================
# Feature 3: Chat History Buffer (/api/chat)
# ==============================================================================

async def test_chat_returns_count_and_messages(async_client: httpx.AsyncClient):
    """GET /api/chat returns count integer and messages list."""
    res = await async_client.get("/api/chat")
    assert res.status_code == 200
    data = res.json()
    assert "count" in data
    assert "messages" in data
    assert isinstance(data["messages"], list)
    assert data["count"] == len(data["messages"])


async def test_chat_message_schema(async_client: httpx.AsyncClient):
    """GET /api/chat message entries contain id, player, message, timestamp, is_command."""
    res = await async_client.get("/api/chat")
    data = res.json()
    assert len(data["messages"]) >= 1
    msg = data["messages"][0]
    assert "id" in msg
    assert "player" in msg
    assert "message" in msg
    assert "timestamp" in msg
    assert "is_command" in msg
    assert isinstance(msg["id"], int)
    assert isinstance(msg["timestamp"], int)
    assert isinstance(msg["is_command"], bool)


async def test_chat_filter_since_timestamp(async_client: httpx.AsyncClient):
    """GET /api/chat?since=<ts> filters messages strictly after timestamp."""
    await async_client.post("/api/say", json={"message": "Msg 1"})
    chat1 = (await async_client.get("/api/chat")).json()["messages"]
    mid_timestamp = chat1[-1]["timestamp"]

    await async_client.post("/api/say", json={"message": "Msg 2"})
    chat2 = (await async_client.get(f"/api/chat?since={mid_timestamp}")).json()["messages"]

    assert all(m["timestamp"] > mid_timestamp for m in chat2)
    assert any(m["message"] == "Msg 2" for m in chat2)
    assert not any(m["message"] == "Msg 1" for m in chat2)


async def test_chat_limit_parameter(async_client: httpx.AsyncClient):
    """GET /api/chat?limit=N returns at most N messages."""
    for i in range(10):
        await async_client.post("/api/say", json={"message": f"Burst {i}"})

    res = await async_client.get("/api/chat?limit=3")
    data = res.json()
    assert len(data["messages"]) == 3
    assert data["count"] == 3
    assert data["messages"][-1]["message"] == "Burst 9"


async def test_chat_command_flag_detection(async_client: httpx.AsyncClient):
    """Chat messages beginning with ! or / are marked with is_command=True."""
    await async_client.post("/api/say", json={"message": "!jarvis status", "sender": "Steve"})
    await async_client.post("/api/say", json={"message": "/time set day", "sender": "Steve"})
    await async_client.post("/api/say", json={"message": "Just normal chat", "sender": "Steve"})

    res = await async_client.get("/api/chat?limit=3")
    msgs = res.json()["messages"]
    assert msgs[-3]["is_command"] is True
    assert msgs[-2]["is_command"] is True
    assert msgs[-1]["is_command"] is False


# ==============================================================================
# Feature 4: Server Console Commands (/api/command)
# ==============================================================================

async def test_command_time_set_day(async_client: httpx.AsyncClient):
    """POST /api/command executes 'time set day' and sets day_time to 1000."""
    res = await async_client.post("/api/command", json={"command": "time set day"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "Set the time to 1000" in data["output"][0]

    status_res = await async_client.get("/api/status")
    assert status_res.json()["day_time"] == 1000


async def test_command_time_set_night(async_client: httpx.AsyncClient):
    """POST /api/command executes 'time set night' and sets day_time to 13000."""
    res = await async_client.post("/api/command", json={"command": "time set night"})
    assert res.status_code == 200
    assert res.json()["success"] is True

    status_res = await async_client.get("/api/status")
    assert status_res.json()["day_time"] == 13000


async def test_command_time_query(async_client: httpx.AsyncClient):
    """POST /api/command executes 'time query daytime' and returns output."""
    res = await async_client.post("/api/command", json={"command": "time query daytime"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "The time is" in data["output"][0]


async def test_command_weather_clear(async_client: httpx.AsyncClient):
    """POST /api/command executes 'weather clear' successfully."""
    res = await async_client.post("/api/command", json={"command": "weather clear"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "clear" in data["output"][0]


async def test_command_gamemode_change(async_client: httpx.AsyncClient):
    """POST /api/command executes 'gamemode creative Steve'."""
    res = await async_client.post("/api/command", json={"command": "gamemode creative Steve"})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "Creative Mode" in data["output"][0]


async def test_command_say_broadcasts_to_chat(async_client: httpx.AsyncClient):
    """POST /api/command executes 'say Server restart in 5m' and records to chat."""
    res = await async_client.post("/api/command", json={"command": "say Server restart in 5m"})
    assert res.status_code == 200
    chat_res = await async_client.get("/api/chat")
    last_msg = chat_res.json()["messages"][-1]
    assert last_msg["player"] == "Server"
    assert "Server restart in 5m" in last_msg["message"]


# ==============================================================================
# Feature 5: Surroundings Perception Engine (/api/surroundings)
# ==============================================================================

async def test_surroundings_default_scan(async_client: httpx.AsyncClient):
    """GET /api/surroundings returns blocks and entities arrays."""
    res = await async_client.get("/api/surroundings")
    assert res.status_code == 200
    data = res.json()
    assert "origin" in data
    assert "radius" in data
    assert "blocks" in data
    assert "entities" in data
    assert isinstance(data["blocks"], list)
    assert isinstance(data["entities"], list)


async def test_surroundings_radius_filtering(async_client: httpx.AsyncClient):
    """GET /api/surroundings?radius=16 respects radius filtering."""
    res = await async_client.get("/api/surroundings?radius=16")
    assert res.status_code == 200
    data = res.json()
    assert data["radius"] == 16
    for b in data["blocks"]:
        assert b["distance"] <= 16.0
    for e in data["entities"]:
        assert e["distance"] <= 16.0


async def test_surroundings_block_classification(async_client: httpx.AsyncClient):
    """GET /api/surroundings returns categorized blocks (ores, containers, workstations)."""
    res = await async_client.get("/api/surroundings?radius=32")
    blocks = res.json()["blocks"]
    block_names = [b["block"] for b in blocks]
    assert "minecraft:diamond_ore" in block_names
    assert "minecraft:chest" in block_names
    assert "minecraft:crafting_table" in block_names


async def test_surroundings_entity_classification(async_client: httpx.AsyncClient):
    """GET /api/surroundings returns categorized entities with health and category."""
    res = await async_client.get("/api/surroundings?radius=32")
    entities = res.json()["entities"]
    entity_types = [e["type"] for e in entities]
    assert "minecraft:zombie" in entity_types
    assert "minecraft:creeper" in entity_types
    zombie = next(e for e in entities if e["type"] == "minecraft:zombie")
    assert zombie["category"] == "monster"
    assert zombie["health"] == 20.0


async def test_surroundings_distance_computation(async_client: httpx.AsyncClient):
    """GET /api/surroundings accurately calculates distance and sorts by proximity."""
    res = await async_client.get("/api/surroundings?radius=32")
    blocks = res.json()["blocks"]
    distances = [b["distance"] for b in blocks]
    # Verify sorted ascending
    assert distances == sorted(distances)


async def test_surroundings_custom_player_origin(async_client: httpx.AsyncClient):
    """GET /api/surroundings?player=Alex scans around Alex's coordinates."""
    res = await async_client.get("/api/surroundings?player=Alex&radius=16")
    assert res.status_code == 200
    origin = res.json()["origin"]
    assert origin["x"] == 115.0
    assert origin["y"] == 64.0
    assert origin["z"] == -190.0


# ==============================================================================
# Feature 6: Companion Spawning (/api/companion/spawn)
# ==============================================================================

async def test_companion_spawn_default_location(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn spawns companion at player's location."""
    res = await async_client.post("/api/companion/spawn", json={})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["companion_id"] == "jarvis-1"
    assert data["position"]["x"] == 100.5
    assert data["position"]["y"] == 64.0
    assert data["position"]["z"] == -200.5


async def test_companion_spawn_custom_name(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn with custom name sets companion name."""
    res = await async_client.post("/api/companion/spawn", json={"name": "Friday"})
    assert res.status_code == 200

    status = (await async_client.get("/api/companion/status")).json()
    assert status["name"] == "Friday"


async def test_companion_spawn_explicit_coordinates(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn sets explicit x, y, z coordinates."""
    res = await async_client.post("/api/companion/spawn", json={"x": 200.0, "y": 70.0, "z": -150.0})
    assert res.status_code == 200
    pos = res.json()["position"]
    assert pos["x"] == 200.0
    assert pos["y"] == 70.0
    assert pos["z"] == -150.0


async def test_companion_spawn_dimension(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn in nether sets dimension field."""
    await async_client.post("/api/companion/spawn", json={"dimension": "minecraft:the_nether"})
    status = (await async_client.get("/api/companion/status")).json()
    assert status["dimension"] == "minecraft:the_nether"


async def test_companion_respawn_updates_position(async_client: httpx.AsyncClient):
    """POST /api/companion/spawn again successfully repositions the companion."""
    await async_client.post("/api/companion/spawn", json={"x": 100, "y": 64, "z": 100})
    res = await async_client.post("/api/companion/spawn", json={"x": 300, "y": 64, "z": 300})
    assert res.status_code == 200
    assert res.json()["position"]["x"] == 300.0


# ==============================================================================
# Feature 7: Companion Status (/api/companion/status)
# ==============================================================================

async def test_companion_status_when_unspawned(async_client: httpx.AsyncClient):
    """GET /api/companion/status when not spawned returns spawned=False, state=unspawned."""
    res = await async_client.get("/api/companion/status")
    assert res.status_code == 200
    data = res.json()
    assert data["spawned"] is False
    assert data["state"] == "unspawned"
    assert data["position"] is None


async def test_companion_status_after_spawn(async_client: httpx.AsyncClient):
    """GET /api/companion/status after spawn returns active and idle status."""
    await async_client.post("/api/companion/spawn", json={"name": "Jarvis"})
    res = await async_client.get("/api/companion/status")
    assert res.status_code == 200
    data = res.json()
    assert data["spawned"] is True
    assert data["name"] == "Jarvis"
    assert data["state"] == "idle"
    assert data["health"] == 20.0


async def test_companion_status_health_values(async_client: httpx.AsyncClient):
    """GET /api/companion/status includes health and max_health."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.get("/api/companion/status")
    data = res.json()
    assert data["health"] == 20.0
    assert data["max_health"] == 20.0


async def test_companion_status_coordinates_accuracy(async_client: httpx.AsyncClient):
    """GET /api/companion/status coordinates match spawn target."""
    await async_client.post("/api/companion/spawn", json={"x": 123.4, "y": 72.0, "z": -456.7})
    res = await async_client.get("/api/companion/status")
    pos = res.json()["position"]
    assert pos["x"] == 123.4
    assert pos["y"] == 72.0
    assert pos["z"] == -456.7


async def test_companion_status_target_initially_none(async_client: httpx.AsyncClient):
    """GET /api/companion/status initializes with target=None."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.get("/api/companion/status")
    assert res.json()["target"] is None


# ==============================================================================
# Feature 8: Companion Despawn (/api/companion/despawn)
# ==============================================================================

async def test_companion_despawn_success(async_client: httpx.AsyncClient):
    """POST /api/companion/despawn despawns active companion."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post("/api/companion/despawn")
    assert res.status_code == 200
    assert res.json()["success"] is True


async def test_companion_despawn_clears_status(async_client: httpx.AsyncClient):
    """POST /api/companion/despawn updates GET /api/companion/status to unspawned."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post("/api/companion/despawn")
    status = (await async_client.get("/api/companion/status")).json()
    assert status["spawned"] is False
    assert status["state"] == "unspawned"
    assert status["position"] is None


async def test_companion_despawn_when_not_spawned_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/despawn returns 400 when no companion is spawned."""
    res = await async_client.post("/api/companion/despawn")
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_companion_respawn_after_despawn(async_client: httpx.AsyncClient):
    """Can successfully spawn companion after despawning."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post("/api/companion/despawn")
    res = await async_client.post("/api/companion/spawn", json={"name": "RebornJarvis"})
    assert res.status_code == 200
    assert res.json()["success"] is True


async def test_companion_actions_fail_after_despawn(async_client: httpx.AsyncClient):
    """POST /api/companion/action fails after companion is despawned."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post("/api/companion/despawn")
    res = await async_client.post("/api/companion/action", json={"action": "move_to", "x": 100, "y": 64, "z": -200})
    assert res.status_code == 400
    assert "not spawned" in res.json()["error"].lower()


# ==============================================================================
# Feature 9: Companion Navigation & Movement (/api/companion/action)
# ==============================================================================

async def test_companion_move_to_updates_position(async_client: httpx.AsyncClient):
    """Action move_to updates companion position and status to navigating."""
    await async_client.post("/api/companion/spawn", json={"x": 100, "y": 64, "z": -200})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 110.0, "y": 64.0, "z": -195.0, "speed": 1.2},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["action"] == "move_to"

    status = (await async_client.get("/api/companion/status")).json()
    assert status["position"]["x"] == 110.0
    assert status["position"]["z"] == -195.0
    assert status["state"] == "navigating"


async def test_companion_follow_player(async_client: httpx.AsyncClient):
    """Action follow sets companion state to following target player."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Steve", "distance": 2.5},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["details"]["player"] == "Steve"

    status = (await async_client.get("/api/companion/status")).json()
    assert status["state"] == "following"
    assert status["target"]["player"] == "Steve"


async def test_companion_stop_resets_to_idle(async_client: httpx.AsyncClient):
    """Action stop sets companion state to idle and clears target."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post("/api/companion/action", json={"action": "follow", "player": "Steve"})
    res = await async_client.post("/api/companion/action", json={"action": "stop"})
    assert res.status_code == 200

    status = (await async_client.get("/api/companion/status")).json()
    assert status["state"] == "idle"
    assert status["target"] is None


async def test_companion_teleport_instant(async_client: httpx.AsyncClient):
    """Action teleport instantly repositions companion."""
    await async_client.post("/api/companion/spawn", json={"x": 100, "y": 64, "z": -200})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "teleport", "x": 500.0, "y": 80.0, "z": 500.0},
    )
    assert res.status_code == 200
    assert res.json()["details"]["position"]["x"] == 500.0

    status = (await async_client.get("/api/companion/status")).json()
    assert status["position"]["x"] == 500.0
    assert status["position"]["y"] == 80.0


async def test_companion_move_speed_parameter(async_client: httpx.AsyncClient):
    """Action move_to preserves speed parameter in details."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 102, "y": 64, "z": -198, "speed": 1.5},
    )
    assert res.status_code == 200
    assert res.json()["details"]["speed"] == 1.5


# ==============================================================================
# Feature 10: Companion Block Manipulation (/api/companion/action)
# ==============================================================================

async def test_companion_break_block_removes_from_surroundings(async_client: httpx.AsyncClient):
    """Action break_block removes block from world surroundings."""
    await async_client.post("/api/companion/spawn", json={})
    # Diamond ore at (102, 60, -198)
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "break_block", "x": 102, "y": 60, "z": -198},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["details"]["dropped"] == "minecraft:diamond_ore"

    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    diamond_ores = [b for b in surroundings["blocks"] if b["block"] == "minecraft:diamond_ore"]
    assert len(diamond_ores) == 0


async def test_companion_place_block_torch(async_client: httpx.AsyncClient):
    """Action place_block places a torch and makes it visible in surroundings."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "place_block", "x": 100, "y": 65, "z": -200, "item": "minecraft:torch"},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["details"]["block"] == "minecraft:torch"

    surroundings = (await async_client.get("/api/surroundings?radius=16")).json()
    torches = [b for b in surroundings["blocks"] if b["block"] == "minecraft:torch"]
    assert len(torches) >= 1


async def test_companion_place_block_cobblestone(async_client: httpx.AsyncClient):
    """Action place_block places cobblestone block."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "place_block", "x": 101, "y": 65, "z": -200, "item": "minecraft:cobblestone"},
    )
    assert res.status_code == 200
    assert res.json()["details"]["block"] == "minecraft:cobblestone"


async def test_companion_interact_block(async_client: httpx.AsyncClient):
    """Action interact_block interacts with crafting table or furnace."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "interact_block", "x": 108, "y": 64, "z": -199},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["details"]["result"] == "interacted"


async def test_companion_mine_alias_for_break_block(async_client: httpx.AsyncClient):
    """Action 'mine' functions as an alias for 'break_block'."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "mine", "x": 98, "y": 50, "z": -205},
    )
    assert res.status_code == 200
    assert res.json()["action"] == "break_block"
    assert res.json()["details"]["dropped"] == "minecraft:iron_ore"


# ==============================================================================
# Feature 11: Companion Container Inspection (/api/companion/action)
# ==============================================================================

async def test_companion_inspect_container_contents(async_client: httpx.AsyncClient):
    """Action inspect_container returns inventory items for chest at (105, 64, -200)."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 105, "y": 64, "z": -200},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    items = data["details"]["items"]
    assert len(items) >= 3
    item_names = [it["item"] for it in items]
    assert "minecraft:iron_ingot" in item_names
    assert "minecraft:coal" in item_names
    assert "minecraft:diamond" in item_names


async def test_companion_inspect_container_item_counts(async_client: httpx.AsyncClient):
    """Action inspect_container provides positive item counts and slots."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 105, "y": 64, "z": -200},
    )
    items = res.json()["details"]["items"]
    for item in items:
        assert item["count"] > 0
        assert item["slot"] >= 0


async def test_companion_inspect_empty_container(async_client: httpx.AsyncClient):
    """Action inspect_container returns empty items list for empty/unregistered location."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 999, "y": 64, "z": 999},
    )
    assert res.status_code == 200
    assert res.json()["details"]["items"] == []


async def test_companion_inspect_container_pos_preserved(async_client: httpx.AsyncClient):
    """Action inspect_container returns matching pos in details."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 105, "y": 64, "z": -200},
    )
    pos = res.json()["details"]["pos"]
    assert pos["x"] == 105
    assert pos["y"] == 64
    assert pos["z"] == -200


async def test_companion_inspect_leaves_companion_idle(async_client: httpx.AsyncClient):
    """Action inspect_container leaves companion ready for further tasks."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": 105, "y": 64, "z": -200},
    )
    status = (await async_client.get("/api/companion/status")).json()
    assert status["spawned"] is True


# ==============================================================================
# Feature 12: Companion Combat Action (/api/companion/action)
# ==============================================================================

async def test_companion_attack_entity_deals_damage(async_client: httpx.AsyncClient):
    """Action attack deals damage to target entity (id: 42)."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "attack", "entity_id": 42},
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["details"]["damage"] == 5.0
    assert data["details"]["remaining_health"] == 15.0
    assert data["details"]["defeated"] is False


async def test_companion_attack_defeats_entity(async_client: httpx.AsyncClient):
    """Consecutive attacks reduce health to 0 and defeat the entity."""
    await async_client.post("/api/companion/spawn", json={})
    # Zombie starts at 20 HP, 4 hits of 5.0 = 0 HP
    for _ in range(3):
        await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})

    res = await async_client.post("/api/companion/action", json={"action": "attack", "entity_id": 42})
    assert res.status_code == 200
    assert res.json()["details"]["remaining_health"] == 0.0
    assert res.json()["details"]["defeated"] is True

    # Check surroundings: entity 42 should no longer be present
    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    e_ids = [e["id"] for e in surroundings["entities"]]
    assert 42 not in e_ids


async def test_companion_attack_nonexistent_entity_returns_404(async_client: httpx.AsyncClient):
    """Action attack with nonexistent entity id returns 404."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "attack", "entity_id": 9999},
    )
    assert res.status_code == 404
    assert res.json()["success"] is False


async def test_companion_attack_creeper_target(async_client: httpx.AsyncClient):
    """Action attack against creeper (id: 43)."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "attack", "entity_id": 43},
    )
    assert res.status_code == 200
    assert res.json()["details"]["target_id"] == 43


async def test_companion_attack_cow_target(async_client: httpx.AsyncClient):
    """Action attack against cow (id: 101)."""
    await async_client.post("/api/companion/spawn", json={})
    res = await async_client.post(
        "/api/companion/action",
        json={"action": "attack", "entity_id": 101},
    )
    assert res.status_code == 200
    assert res.json()["details"]["remaining_health"] == 5.0


# ==============================================================================
# Feature 13: Cross-Feature State Consistency
# ==============================================================================

async def test_cross_say_appears_in_chat(async_client: httpx.AsyncClient):
    """POST /api/say message is immediately visible in GET /api/chat."""
    msg = "Cross-feature broadcast verification"
    await async_client.post("/api/say", json={"message": msg, "sender": "Jarvis"})
    chat = (await async_client.get("/api/chat")).json()
    assert any(m["message"] == msg for m in chat["messages"])


async def test_cross_command_day_updates_status(async_client: httpx.AsyncClient):
    """POST /api/command modifies world state reflected in GET /api/status."""
    await async_client.post("/api/command", json={"command": "time set day"})
    status = (await async_client.get("/api/status")).json()
    assert status["day_time"] == 1000


async def test_cross_spawn_and_status_alignment(async_client: httpx.AsyncClient):
    """Companion spawn reflects identical coordinates in companion status."""
    spawn_res = await async_client.post(
        "/api/companion/spawn",
        json={"name": "Jarvis", "x": 150.0, "y": 64.0, "z": -250.0},
    )
    spawn_pos = spawn_res.json()["position"]
    status_pos = (await async_client.get("/api/companion/status")).json()["position"]
    assert spawn_pos == status_pos


async def test_cross_break_block_removes_from_surroundings(async_client: httpx.AsyncClient):
    """Breaking a block removes it from subsequent surroundings perception queries."""
    await async_client.post("/api/companion/spawn", json={})
    # Iron ore at (98, 50, -205)
    await async_client.post("/api/companion/action", json={"action": "break_block", "x": 98, "y": 50, "z": -205})
    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    iron_ores = [b for b in surroundings["blocks"] if b["block"] == "minecraft:iron_ore"]
    assert len(iron_ores) == 0


async def test_cross_place_block_visible_in_surroundings(async_client: httpx.AsyncClient):
    """Placing a block makes it visible in subsequent surroundings perception queries."""
    await async_client.post("/api/companion/spawn", json={})
    await async_client.post(
        "/api/companion/action",
        json={"action": "place_block", "x": 100, "y": 64, "z": -200, "item": "minecraft:glowstone"},
    )
    surroundings = (await async_client.get("/api/surroundings?radius=16")).json()
    glowstones = [b for b in surroundings["blocks"] if b["block"] == "minecraft:glowstone"]
    assert len(glowstones) == 1
