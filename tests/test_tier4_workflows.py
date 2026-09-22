"""
Tier 4: Real-World AI Companion Workflow Scenarios.
Simulates end-to-end realistic multi-step autonomous agent behaviors in gameplay:
1. AI Survey & Environmental Assessment
2. Autonomous Mining Expedition
3. Base Construction & Lighting
4. Autonomous Chest Sorter & Inventory Audit
5. Emergency Player Escort & Threat Neutralization
6. Player Chat Command Dispatch (!jarvis make it day)
7. Full Autonomous Agent Lifecycle Session
8. Server Resilience, 503 Retry & Graceful Recovery
9. Multi-Ore Deep Cave Prospecting & Prioritization
10. Autonomous Patrol & Guard Circuit
"""

from __future__ import annotations

import asyncio
import httpx
import pytest

from tests.mock_server import MockMinecraftState

pytestmark = pytest.mark.asyncio


# ==============================================================================
# Scenario 1: AI Survey & Environmental Assessment
# ==============================================================================

async def test_workflow_ai_survey_and_chat_assistance(async_client: httpx.AsyncClient):
    """Workflow: Status check -> surroundings scan -> automated report broadcast -> chat delivery."""
    # Step 1: Telemetry check
    status_res = await async_client.get("/api/status")
    assert status_res.status_code == 200
    telemetry = status_res.json()
    player = telemetry["players"][0]
    p_name = player["name"]
    p_pos = player["position"]

    # Step 2: Scan surroundings
    surr_res = await async_client.get(f"/api/surroundings?player={p_name}&radius=16")
    assert surr_res.status_code == 200
    surroundings = surr_res.json()
    ores = [b["block"] for b in surroundings["blocks"] if b.get("type") == "ore"]
    monsters = [e["name"] for e in surroundings["entities"] if e.get("category") == "monster"]

    # Step 3: Formulate report
    report = (
        f"Survey complete for {p_name} at ({p_pos['x']}, {p_pos['y']}, {p_pos['z']}): "
        f"Found {len(ores)} ores ({', '.join(ores)}) and {len(monsters)} threats ({', '.join(monsters)})."
    )

    # Step 4: Broadcast report to in-game chat
    say_res = await async_client.post("/api/say", json={"message": report, "sender": "Jarvis"})
    assert say_res.status_code == 200

    # Step 5: Verify delivery in chat
    chat_res = await async_client.get("/api/chat?limit=5")
    assert chat_res.status_code == 200
    last_msg = chat_res.json()["messages"][-1]
    assert last_msg["player"] == "Jarvis"
    assert report in last_msg["message"]


# ==============================================================================
# Scenario 2: Autonomous Mining Expedition
# ==============================================================================

async def test_workflow_autonomous_mining_expedition(async_client: httpx.AsyncClient):
    """Workflow: Spawn companion -> detect diamond ore -> navigate -> mine ore -> verify removal."""
    # Step 1: Spawn companion near player
    spawn_res = await async_client.post(
        "/api/companion/spawn",
        json={"name": "Jarvis", "x": 100.5, "y": 64.0, "z": -200.5},
    )
    assert spawn_res.status_code == 200

    # Step 2: Scan surroundings to locate diamond ore
    surr = (await async_client.get("/api/surroundings?radius=32")).json()
    diamond_ore = next((b for b in surr["blocks"] if b["block"] == "minecraft:diamond_ore"), None)
    assert diamond_ore is not None
    ore_pos = diamond_ore["pos"]

    # Step 3: Command companion navigation to ore site
    move_res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": ore_pos["x"], "y": ore_pos["y"], "z": ore_pos["z"], "speed": 1.2},
    )
    assert move_res.status_code == 200
    assert move_res.json()["action"] == "move_to"

    # Step 4: Verify companion at ore location
    st = (await async_client.get("/api/companion/status")).json()
    assert st["position"]["x"] == ore_pos["x"]
    assert st["position"]["y"] == ore_pos["y"]
    assert st["position"]["z"] == ore_pos["z"]

    # Step 5: Execute mining action
    mine_res = await async_client.post(
        "/api/companion/action",
        json={"action": "break_block", "x": ore_pos["x"], "y": ore_pos["y"], "z": ore_pos["z"]},
    )
    assert mine_res.status_code == 200
    assert mine_res.json()["details"]["dropped"] == "minecraft:diamond_ore"

    # Step 6: Verify ore no longer present in surroundings
    updated_surr = (await async_client.get("/api/surroundings?radius=32")).json()
    remaining_diamonds = [b for b in updated_surr["blocks"] if b["block"] == "minecraft:diamond_ore"]
    assert len(remaining_diamonds) == 0

    # Step 7: Announce success in chat
    await async_client.post("/api/say", json={"message": "Diamond ore successfully mined!", "sender": "Jarvis"})
    last_chat = (await async_client.get("/api/chat?limit=1")).json()["messages"][-1]
    assert "Diamond ore successfully mined!" in last_chat["message"]


# ==============================================================================
# Scenario 3: Base Construction & Lighting
# ==============================================================================

async def test_workflow_base_construction_and_lighting(async_client: httpx.AsyncClient):
    """Workflow: Companion navigates and places perimeter torches and foundation blocks."""
    # Step 1: Spawn companion
    await async_client.post("/api/companion/spawn", json={"name": "BuilderJarvis"})

    # Foundation blueprint: 4 perimeter points
    blueprint = [
        {"x": 100, "y": 64, "z": -200, "item": "minecraft:torch"},
        {"x": 104, "y": 64, "z": -200, "item": "minecraft:cobblestone"},
        {"x": 104, "y": 64, "z": -196, "item": "minecraft:cobblestone"},
        {"x": 100, "y": 64, "z": -196, "item": "minecraft:torch"},
    ]

    # Step 2: Build blueprint sequentially
    for step in blueprint:
        # Move to location
        await async_client.post(
            "/api/companion/action",
            json={"action": "move_to", "x": step["x"], "y": step["y"], "z": step["z"]},
        )
        # Place block
        place_res = await async_client.post(
            "/api/companion/action",
            json={"action": "place_block", "x": step["x"], "y": step["y"], "z": step["z"], "item": step["item"]},
        )
        assert place_res.status_code == 200

    # Step 3: Verify all 4 blocks in surroundings
    surr = (await async_client.get("/api/surroundings?radius=16")).json()
    placed_blocks = surr["blocks"]
    torch_count = sum(1 for b in placed_blocks if b["block"] == "minecraft:torch")
    cobble_count = sum(1 for b in placed_blocks if b["block"] == "minecraft:cobblestone")
    assert torch_count >= 2
    assert cobble_count >= 2

    # Step 4: Broadcast summary
    await async_client.post(
        "/api/say",
        json={"message": "Base perimeter established with 2 torches and 2 cobblestone blocks.", "sender": "Jarvis"},
    )


# ==============================================================================
# Scenario 4: Autonomous Chest Sorter & Inventory Audit
# ==============================================================================

async def test_workflow_chest_sorter_and_inventory_audit(async_client: httpx.AsyncClient):
    """Workflow: Locate chest -> navigate -> inspect container -> interact -> announce audit."""
    # Step 1: Detect container in surroundings
    surr = (await async_client.get("/api/surroundings?radius=16")).json()
    chest = next((b for b in surr["blocks"] if b["block"] == "minecraft:chest"), None)
    assert chest is not None
    cx, cy, cz = chest["pos"]["x"], chest["pos"]["y"], chest["pos"]["z"]

    # Step 2: Spawn and navigate companion
    await async_client.post("/api/companion/spawn", json={"name": "SorterJarvis"})
    await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": cx, "y": cy, "z": cz},
    )

    # Step 3: Inspect container contents
    inspect_res = await async_client.post(
        "/api/companion/action",
        json={"action": "inspect_container", "x": cx, "y": cy, "z": cz},
    )
    assert inspect_res.status_code == 200
    items = inspect_res.json()["details"]["items"]
    assert len(items) >= 3

    # Step 4: Audit inventory
    inventory_summary = {it["item"]: it["count"] for it in items}
    assert "minecraft:iron_ingot" in inventory_summary
    assert "minecraft:coal" in inventory_summary
    assert "minecraft:diamond" in inventory_summary

    # Step 5: Interact with chest
    interact_res = await async_client.post(
        "/api/companion/action",
        json={"action": "interact_block", "x": cx, "y": cy, "z": cz},
    )
    assert interact_res.status_code == 200

    # Step 6: Broadcast audit
    audit_msg = (
        f"Chest audit at ({cx}, {cy}, {cz}): "
        f"{inventory_summary['minecraft:iron_ingot']} Iron, "
        f"{inventory_summary['minecraft:coal']} Coal, "
        f"{inventory_summary['minecraft:diamond']} Diamonds."
    )
    await async_client.post("/api/say", json={"message": audit_msg, "sender": "Jarvis"})
    chat = (await async_client.get("/api/chat?limit=1")).json()
    assert audit_msg in chat["messages"][-1]["message"]


# ==============================================================================
# Scenario 5: Emergency Player Escort & Threat Neutralization
# ==============================================================================

async def test_workflow_emergency_player_escort_and_combat(async_client: httpx.AsyncClient):
    """Workflow: Detect threat in surroundings -> companion follow player -> engage and defeat monster."""
    # Step 1: Detect monster in surroundings
    surr = (await async_client.get("/api/surroundings?radius=16")).json()
    zombie = next((e for e in surr["entities"] if e["type"] == "minecraft:zombie"), None)
    assert zombie is not None
    zid = zombie["id"]

    # Step 2: Spawn companion and follow player Steve
    await async_client.post("/api/companion/spawn", json={"name": "GuardianJarvis"})
    await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Steve", "distance": 2.0},
    )

    # Step 3: Alert player
    await async_client.post(
        "/api/say",
        json={"message": "WARNING: Hostile Zombie detected! Engaging combat protocol.", "sender": "Jarvis"},
    )

    # Step 4: Engage monster in combat until defeated
    for _ in range(4):
        atk_res = await async_client.post(
            "/api/companion/action",
            json={"action": "attack", "entity_id": zid},
        )
        assert atk_res.status_code == 200

    # Step 5: Verify entity defeated
    updated_surr = (await async_client.get("/api/surroundings?radius=16")).json()
    remaining_monsters = [e for e in updated_surr["entities"] if e["id"] == zid]
    assert len(remaining_monsters) == 0

    # Step 6: Announce area secured
    await async_client.post(
        "/api/say",
        json={"message": "Threat neutralized. Area is secure.", "sender": "Jarvis"},
    )


# ==============================================================================
# Scenario 6: Player Chat Command Dispatch (!jarvis make it day)
# ==============================================================================

async def test_workflow_player_chat_command_dispatch(async_client: httpx.AsyncClient):
    """Workflow: Player types '!jarvis make it day' -> bridge detects -> executes -> confirms."""
    # Step 1: Player speaks in chat
    player_command = "!jarvis make it day"
    await async_client.post("/api/say", json={"message": player_command, "sender": "Steve"})

    # Step 2: Polling loop detects !jarvis command
    chat_res = await async_client.get("/api/chat?limit=5")
    messages = chat_res.json()["messages"]
    cmd_entry = next((m for m in reversed(messages) if m["message"].startswith("!jarvis")), None)
    assert cmd_entry is not None
    assert cmd_entry["is_command"] is True

    # Step 3: Bridge parses intent
    intent = cmd_entry["message"][8:].strip()  # "make it day"
    assert intent == "make it day"

    # Step 4: Map intent to console command and execute
    server_cmd = "time set day"
    cmd_res = await async_client.post("/api/command", json={"command": server_cmd})
    assert cmd_res.status_code == 200
    assert cmd_res.json()["success"] is True

    # Step 5: Confirm world state updated
    status = (await async_client.get("/api/status")).json()
    assert status["day_time"] == 1000

    # Step 6: Companion replies in chat
    reply_msg = f"Executed '{server_cmd}' for {cmd_entry['player']}."
    await async_client.post("/api/say", json={"message": reply_msg, "sender": "Jarvis"})
    last_chat = (await async_client.get("/api/chat?limit=1")).json()["messages"][-1]
    assert reply_msg in last_chat["message"]


# ==============================================================================
# Scenario 7: Full Autonomous Agent Lifecycle Session
# ==============================================================================

async def test_workflow_full_autonomous_agent_lifecycle(async_client: httpx.AsyncClient):
    """Workflow: Complete sequence from server probe to spawn, act, navigate, and despawn."""
    # 1. Telemetry Probe
    status = (await async_client.get("/api/status")).json()
    assert status["status"] == "online"

    # 2. Companion Spawn
    spawn = (await async_client.post("/api/companion/spawn", json={"name": "LifecycleJarvis"})).json()
    assert spawn["success"] is True

    # 3. Status Query
    c_status = (await async_client.get("/api/companion/status")).json()
    assert c_status["spawned"] is True

    # 4. Surroundings Scan
    surroundings = (await async_client.get("/api/surroundings?radius=16")).json()
    assert len(surroundings["blocks"]) > 0

    # 5. Navigation
    move = (await async_client.post("/api/companion/action", json={"action": "move_to", "x": 105, "y": 64, "z": -200})).json()
    assert move["success"] is True

    # 6. Container Inspection
    container = (await async_client.post("/api/companion/action", json={"action": "inspect_container", "x": 105, "y": 64, "z": -200})).json()
    assert len(container["details"]["items"]) > 0

    # 7. Block Placement
    place = (await async_client.post("/api/companion/action", json={"action": "place_block", "x": 106, "y": 64, "z": -200, "item": "minecraft:torch"})).json()
    assert place["success"] is True

    # 8. Follow Player
    follow = (await async_client.post("/api/companion/action", json={"action": "follow", "player": "Steve"})).json()
    assert follow["success"] is True

    # 9. Stop
    stop = (await async_client.post("/api/companion/action", json={"action": "stop"})).json()
    assert stop["success"] is True

    # 10. Despawn
    despawn = (await async_client.post("/api/companion/despawn")).json()
    assert despawn["success"] is True

    final_status = (await async_client.get("/api/companion/status")).json()
    assert final_status["spawned"] is False


# ==============================================================================
# Scenario 8: Server Resilience, 503 Retry & Graceful Recovery
# ==============================================================================

async def test_workflow_server_resilience_and_recovery(
    async_client: httpx.AsyncClient, server_state: MockMinecraftState
):
    """Workflow: Client encounters temporary 503 during world reload, backs off, and resumes."""
    # Step 1: Normal operation
    res1 = await async_client.get("/api/status")
    assert res1.status_code == 200

    # Step 2: Server becomes temporarily unavailable (e.g. NeoForge reload)
    server_state.simulate_503 = True

    # Step 3: Resilient retry loop with exponential backoff
    max_retries = 5
    recovered = False
    delay = 0.01

    for attempt in range(max_retries):
        res = await async_client.get("/api/status")
        if res.status_code == 503:
            # Simulate recovery on 3rd attempt
            if attempt == 2:
                server_state.simulate_503 = False
            await asyncio.sleep(delay)
            delay *= 2
        elif res.status_code == 200:
            recovered = True
            break

    assert recovered is True

    # Step 4: Resume companion operations
    spawn_res = await async_client.post("/api/companion/spawn", json={"name": "ResilientJarvis"})
    assert spawn_res.status_code == 200
    assert spawn_res.json()["success"] is True


# ==============================================================================
# Scenario 9: Multi-Ore Deep Cave Prospecting & Prioritization
# ==============================================================================

async def test_workflow_multi_ore_prospecting_and_prioritization(async_client: httpx.AsyncClient):
    """Workflow: Prospect multiple ores in surroundings, prioritize diamond over iron, mine both."""
    await async_client.post("/api/companion/spawn", json={"name": "ProspectorJarvis"})

    # Step 1: Prospect surroundings
    surr = (await async_client.get("/api/surroundings?radius=32")).json()
    ores = [b for b in surr["blocks"] if b.get("type") == "ore"]
    assert len(ores) >= 2

    # Step 2: Value priority map
    priority = {"minecraft:diamond_ore": 1, "minecraft:iron_ore": 2}
    sorted_ores = sorted(ores, key=lambda b: priority.get(b["block"], 99))

    # Diamond must be prioritized first
    assert sorted_ores[0]["block"] == "minecraft:diamond_ore"
    assert sorted_ores[1]["block"] == "minecraft:iron_ore"

    mined_items = []
    # Step 3: Mine in priority order
    for target_ore in sorted_ores:
        pos = target_ore["pos"]
        # Navigate to ore
        await async_client.post(
            "/api/companion/action",
            json={"action": "move_to", "x": pos["x"], "y": pos["y"], "z": pos["z"]},
        )
        # Mine ore
        mine_res = await async_client.post(
            "/api/companion/action",
            json={"action": "break_block", "x": pos["x"], "y": pos["y"], "z": pos["z"]},
        )
        assert mine_res.status_code == 200
        mined_items.append(mine_res.json()["details"]["dropped"])

    assert "minecraft:diamond_ore" in mined_items
    assert "minecraft:iron_ore" in mined_items

    # Step 4: Verify all mined ores removed from surroundings
    updated_surr = (await async_client.get("/api/surroundings?radius=32")).json()
    remaining_ores = [b for b in updated_surr["blocks"] if b.get("type") == "ore"]
    assert len(remaining_ores) == 0


# ==============================================================================
# Scenario 10: Autonomous Patrol & Guard Circuit
# ==============================================================================

async def test_workflow_autonomous_patrol_circuit(async_client: httpx.AsyncClient):
    """Workflow: Companion executes waypoint patrol circuit, scans at each post, and returns."""
    await async_client.post("/api/companion/spawn", json={"name": "PatrolJarvis", "x": 100, "y": 64, "z": -200})

    waypoints = [
        {"name": "Alpha", "x": 105.0, "y": 64.0, "z": -195.0},
        {"name": "Bravo", "x": 110.0, "y": 64.0, "z": -190.0},
        {"name": "Charlie", "x": 100.0, "y": 64.0, "z": -200.0},  # Return to post
    ]

    cleared_posts = []
    for wp in waypoints:
        # Move to waypoint
        move_res = await async_client.post(
            "/api/companion/action",
            json={"action": "move_to", "x": wp["x"], "y": wp["y"], "z": wp["z"]},
        )
        assert move_res.status_code == 200

        # Scan surroundings at waypoint
        surr_res = await async_client.get("/api/surroundings?radius=8")
        assert surr_res.status_code == 200

        cleared_posts.append(wp["name"])

    assert cleared_posts == ["Alpha", "Bravo", "Charlie"]

    # Final position matches return post
    status = (await async_client.get("/api/companion/status")).json()
    assert status["position"]["x"] == 100.0
    assert status["position"]["z"] == -200.0

    # Announce patrol completion
    await async_client.post(
        "/api/say",
        json={"message": "Patrol circuit complete. All sectors clear.", "sender": "Jarvis"},
    )
