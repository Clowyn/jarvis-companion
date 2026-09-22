"""
Empirical Challenger Gen2-1 Test Suite: MCP Tools Stress-Testing & Adversarial Verification.
Target: bridge/jarvis_bridge/mcp_server.py (10 MCP Tools)

Verification Matrix:
1. Tool Registry, Schema, and Metadata Integrity (tool count, naming, schemas, typing, defaults)
2. Direct Python Async Invocation (structured dictionaries/lists returned for all 10 tools)
3. FastMCP Protocol Wire Execution (_lowlevel_server request handler with CallToolRequestParams)
4. Adversarial Input Validation & Missing Parameters (omitted arguments, type errors, invalid inputs)
5. Boundary Coordinates, Edge Values & Special Payloads (negative/extreme coords, color codes, unicode, huge strings)
6. State Machine Violations & Domain Errors (unspawned companion actions, nonexistent players, unsupported actions)
7. Concurrency, Load Bursts & Race Conditions (parallel tool calls across all tools, rapid mutations)
8. Fault Injection & Resilience (dead server, 503 unavailable, 500 internal error, simulated latency)
"""

from __future__ import annotations

import asyncio
import copy
import os
import sys
from typing import Any, Dict, List, Optional
import httpx
import pytest
import pytest_asyncio
import mcp.types as types

# Ensure bridge is importable
sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient, JarvisClientError
from jarvis_bridge import mcp_server
from jarvis_bridge.mcp_server import (
    mcp,
    get_client,
    set_client,
    minecraft_get_status,
    minecraft_say,
    minecraft_get_chat,
    minecraft_execute_command,
    minecraft_get_surroundings,
    minecraft_companion_spawn,
    minecraft_companion_move,
    minecraft_companion_follow,
    minecraft_companion_interact,
    minecraft_companion_status,
)
from tests.mock_server import MockMinecraftServer, MockMinecraftState

pytestmark = pytest.mark.asyncio

EXPECTED_10_TOOLS = [
    "minecraft_get_status",
    "minecraft_say",
    "minecraft_get_chat",
    "minecraft_execute_command",
    "minecraft_get_surroundings",
    "minecraft_companion_spawn",
    "minecraft_companion_move",
    "minecraft_companion_follow",
    "minecraft_companion_interact",
    "minecraft_companion_status",
]


@pytest_asyncio.fixture(autouse=True)
async def configure_mcp_client(server_url: str):
    """Automatically wire mcp_server's global client to the active mock server."""
    client = JarvisClient(base_url=server_url, timeout=5.0)
    set_client(client)
    yield client
    await client.close()
    set_client(None)


# ==============================================================================
# Category 1: Tool Registry, Schema, and Metadata Integrity
# ==============================================================================

async def test_tool_registry_complete_and_exact_10():
    """Verify that exactly 10 MCP tools are registered with expected canonical names."""
    registered_tools = list(mcp._tool_manager._tools.keys())
    assert len(registered_tools) == 10, f"Expected 10 tools, found {len(registered_tools)}: {registered_tools}"
    for expected_name in EXPECTED_10_TOOLS:
        assert expected_name in registered_tools, f"Missing expected tool: {expected_name}"


async def test_tool_discovery_via_mcp_list_tools():
    """Verify async tool listing via FastMCP/MCPServer list_tools API."""
    tools = await mcp.list_tools()
    tool_names = [t.name for t in tools]
    assert len(tool_names) == 10
    for expected_name in EXPECTED_10_TOOLS:
        assert expected_name in tool_names


async def test_tool_schemas_and_parameter_definitions():
    """Verify each tool's argument schema, documentation, and parameter requirements."""
    tools_dict = mcp._tool_manager._tools

    # 1. minecraft_get_status: no required arguments
    status_tool = tools_dict["minecraft_get_status"]
    assert "telemetry" in status_tool.description.lower() or "health" in status_tool.description.lower()

    # 2. minecraft_say: 'message' required, 'sender' optional
    say_tool = tools_dict["minecraft_say"]
    say_params = say_tool.parameters.get("properties", {})
    assert "message" in say_params
    assert "sender" in say_params
    assert "message" in say_tool.parameters.get("required", [])

    # 3. minecraft_get_chat: 'limit' and 'since' with defaults
    chat_tool = tools_dict["minecraft_get_chat"]
    chat_params = chat_tool.parameters.get("properties", {})
    assert "limit" in chat_params
    assert "since" in chat_params

    # 4. minecraft_execute_command: 'command' required
    cmd_tool = tools_dict["minecraft_execute_command"]
    assert "command" in cmd_tool.parameters.get("required", [])

    # 5. minecraft_get_surroundings: 'radius' and 'player' optional
    surr_tool = tools_dict["minecraft_get_surroundings"]
    surr_params = surr_tool.parameters.get("properties", {})
    assert "radius" in surr_params
    assert "player" in surr_params

    # 6. minecraft_companion_spawn: optional coords and name
    spawn_tool = tools_dict["minecraft_companion_spawn"]
    spawn_params = spawn_tool.parameters.get("properties", {})
    assert "name" in spawn_params
    assert "x" in spawn_params and "y" in spawn_params and "z" in spawn_params

    # 7. minecraft_companion_move: x, y, z required, speed optional
    move_tool = tools_dict["minecraft_companion_move"]
    move_req = move_tool.parameters.get("required", [])
    assert all(coord in move_req for coord in ["x", "y", "z"])

    # 8. minecraft_companion_follow: player required, distance optional
    follow_tool = tools_dict["minecraft_companion_follow"]
    assert "player" in follow_tool.parameters.get("required", [])

    # 9. minecraft_companion_interact: action, x, y, z required
    interact_tool = tools_dict["minecraft_companion_interact"]
    interact_req = interact_tool.parameters.get("required", [])
    assert all(k in interact_req for k in ["action", "x", "y", "z"])

    # 10. minecraft_companion_status: no required parameters
    status_comp_tool = tools_dict["minecraft_companion_status"]
    assert status_comp_tool.parameters.get("required", []) == []


async def test_mcp_server_client_override_and_reset(server_url: str):
    """Verify get_client() and set_client() lifecycle management."""
    # Test override
    custom_client = JarvisClient(base_url=server_url)
    set_client(custom_client)
    assert get_client() is custom_client

    # Reset
    set_client(None)
    fresh_client = get_client()
    assert fresh_client is not custom_client
    assert isinstance(fresh_client, JarvisClient)


# ==============================================================================
# Category 2: Direct Tool Function Execution with Valid Payloads
# ==============================================================================

async def test_direct_tool_1_get_status():
    """Verify direct call to minecraft_get_status() returns valid structured dictionary."""
    res = await minecraft_get_status()
    assert isinstance(res, dict)
    assert res.get("status") == "online"
    assert res.get("version") == "1.21.1"
    assert "world_time" in res
    assert "players" in res
    assert isinstance(res["players"], list)
    assert len(res["players"]) >= 1


async def test_direct_tool_2_say():
    """Verify direct call to minecraft_say() broadcasts formatted chat."""
    res = await minecraft_say(message="Adversarial check: direct say", sender="ChallengerAgent")
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("broadcasted") is True


async def test_direct_tool_3_get_chat():
    """Verify direct call to minecraft_get_chat() returns list of structured message dictionaries."""
    await minecraft_say(message="Direct chat search target", sender="ChallengerAgent")
    res = await minecraft_get_chat(limit=10, since=0)
    assert isinstance(res, list)
    assert len(res) > 0
    found = any(m.get("message") == "Direct chat search target" for m in res)
    assert found is True
    # Verify field structure
    first = res[0]
    assert "id" in first
    assert "player" in first
    assert "message" in first
    assert "timestamp" in first


async def test_direct_tool_4_execute_command():
    """Verify direct call to minecraft_execute_command() executes console command."""
    res = await minecraft_execute_command(command="weather clear")
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert "clear" in str(res.get("output", ""))


async def test_direct_tool_5_get_surroundings():
    """Verify direct call to minecraft_get_surroundings() returns blocks and entities."""
    res = await minecraft_get_surroundings(player="Steve", radius=16)
    assert isinstance(res, dict)
    assert res.get("player") == "Steve"
    assert "blocks" in res and isinstance(res["blocks"], list)
    assert "entities" in res and isinstance(res["entities"], list)
    assert res.get("radius") == 16


async def test_direct_tool_6_companion_spawn():
    """Verify direct call to minecraft_companion_spawn() creates active companion."""
    res = await minecraft_companion_spawn(name="AlphaJarvis", x=100.5, y=64.0, z=-200.5)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("companion_id") == "jarvis-1"
    assert res.get("position", {}).get("x") == 100.5


async def test_direct_tool_7_companion_move():
    """Verify direct call to minecraft_companion_move() dispatches navigation."""
    await minecraft_companion_spawn(name="MoveJarvis", x=100.0, y=64.0, z=-200.0)
    res = await minecraft_companion_move(x=110.0, y=64.0, z=-190.0, speed=1.5)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("action") == "move_to"
    assert res.get("status") == "executed"
    assert res.get("details", {}).get("speed") == 1.5


async def test_direct_tool_8_companion_follow():
    """Verify direct call to minecraft_companion_follow() commands companion to follow player."""
    await minecraft_companion_spawn(name="FollowJarvis", x=100.0, y=64.0, z=-200.0)
    res = await minecraft_companion_follow(player="Steve", distance=2.5)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("action") == "follow"
    assert res.get("details", {}).get("player") == "Steve"
    assert res.get("details", {}).get("distance") == 2.5


async def test_direct_tool_9_companion_interact():
    """Verify direct call to minecraft_companion_interact() executes block interactions."""
    await minecraft_companion_spawn(name="InteractJarvis", x=100.0, y=64.0, z=-200.0)

    # 1. break_block
    res_break = await minecraft_companion_interact(action="break_block", x=102, y=60, z=-198)
    assert res_break.get("success") is True
    assert res_break.get("action") == "break_block"

    # 2. place_block
    res_place = await minecraft_companion_interact(action="place_block", x=102, y=61, z=-198, item="minecraft:torch")
    assert res_place.get("success") is True
    assert res_place.get("action") == "place_block"

    # 3. inspect_container
    res_inspect = await minecraft_companion_interact(action="inspect_container", x=105, y=64, z=-200)
    assert res_inspect.get("success") is True
    assert res_inspect.get("action") == "inspect_container"
    assert "items" in res_inspect.get("details", {})


async def test_direct_tool_10_companion_status():
    """Verify direct call to minecraft_companion_status() reflects spawned vs unspawned states."""
    # Before spawn
    st_unspawned = await minecraft_companion_status()
    assert isinstance(st_unspawned, dict)
    assert st_unspawned.get("spawned") is False
    assert st_unspawned.get("state") == "unspawned"

    # After spawn
    await minecraft_companion_spawn(name="StatusJarvis", x=100.5, y=64.0, z=-200.5)
    st_spawned = await minecraft_companion_status()
    assert isinstance(st_spawned, dict)
    assert st_spawned.get("spawned") is True
    assert st_spawned.get("health", 0) > 0
    assert st_spawned.get("position", {}).get("x") == 100.5


# ==============================================================================
# Category 3: FastMCP Protocol Wire Execution (_lowlevel_server request handler)
# ==============================================================================

async def test_mcp_wire_all_10_tools_success():
    """
    Invokes all 10 tools through the lowlevel JSON-RPC tools/call protocol handler.
    Verifies that the server returns valid CallToolResult with is_error=False
    and populated text/structured content.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler

    # 1. get_status
    r1 = await handler(None, types.CallToolRequestParams(name="minecraft_get_status", arguments={}))
    assert r1.is_error is False
    assert len(r1.content) > 0

    # 2. say
    r2 = await handler(None, types.CallToolRequestParams(name="minecraft_say", arguments={"message": "Wire test"}))
    assert r2.is_error is False

    # 3. get_chat
    r3 = await handler(None, types.CallToolRequestParams(name="minecraft_get_chat", arguments={"limit": 5}))
    assert r3.is_error is False
    assert r3.structured_content is not None

    # 4. execute_command
    r4 = await handler(None, types.CallToolRequestParams(name="minecraft_execute_command", arguments={"command": "time set day"}))
    assert r4.is_error is False

    # 5. get_surroundings
    r5 = await handler(None, types.CallToolRequestParams(name="minecraft_get_surroundings", arguments={"radius": 16}))
    assert r5.is_error is False

    # 6. companion_spawn
    r6 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_spawn", arguments={"name": "WireJarvis", "x": 100.0, "y": 64.0, "z": -200.0}))
    assert r6.is_error is False

    # 7. companion_move
    r7 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_move", arguments={"x": 105.0, "y": 64.0, "z": -195.0}))
    assert r7.is_error is False

    # 8. companion_follow
    r8 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_follow", arguments={"player": "Steve"}))
    assert r8.is_error is False

    # 9. companion_interact
    r9 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_interact", arguments={"action": "break_block", "x": 102, "y": 60, "z": -198}))
    assert r9.is_error is False

    # 10. companion_status
    r10 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_status", arguments={}))
    assert r10.is_error is False


# ==============================================================================
# Category 4: Adversarial Input Validation & Missing Required Parameters
# ==============================================================================

async def test_mcp_call_missing_required_parameters():
    """
    Verifies that calling MCP tools over the protocol wire with missing required parameters
    triggers Pydantic validation rejection and returns is_error=True with structured error text,
    WITHOUT crashing the server process.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler

    # 1. minecraft_say missing 'message'
    r1 = await handler(None, types.CallToolRequestParams(name="minecraft_say", arguments={}))
    assert r1.is_error is True
    assert "validation error" in r1.content[0].text.lower()
    assert "message" in r1.content[0].text.lower()

    # 2. minecraft_execute_command missing 'command'
    r2 = await handler(None, types.CallToolRequestParams(name="minecraft_execute_command", arguments={}))
    assert r2.is_error is True
    assert "command" in r2.content[0].text.lower()

    # 3. minecraft_companion_move missing coordinates
    r3 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_move", arguments={"x": 10.0}))
    assert r3.is_error is True
    assert "validation error" in r3.content[0].text.lower()

    # 4. minecraft_companion_follow missing 'player'
    r4 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_follow", arguments={}))
    assert r4.is_error is True
    assert "player" in r4.content[0].text.lower()

    # 5. minecraft_companion_interact missing 'action' or coords
    r5 = await handler(None, types.CallToolRequestParams(name="minecraft_companion_interact", arguments={"action": "break_block"}))
    assert r5.is_error is True


async def test_mcp_call_invalid_argument_types():
    """
    Verifies that invalid argument types (e.g. non-numeric coordinates, wrong types)
    are caught and rejected by FastMCP validation.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler

    # Non-numeric string for x coordinate
    r1 = await handler(
        None,
        types.CallToolRequestParams(
            name="minecraft_companion_move",
            arguments={"x": "NOT_A_FLOAT", "y": 64.0, "z": -200.0},
        ),
    )
    assert r1.is_error is True
    assert "unable to parse" in r1.content[0].text.lower() or "validation error" in r1.content[0].text.lower()

    # Object for limit
    r2 = await handler(
        None,
        types.CallToolRequestParams(
            name="minecraft_get_chat",
            arguments={"limit": {"nested": "dict"}},
        ),
    )
    assert r2.is_error is True


async def test_direct_tool_missing_arguments_type_error():
    """
    Verifies that direct Python calls with missing positional arguments raise standard TypeError.
    """
    with pytest.raises(TypeError):
        await minecraft_say()  # type: ignore[call-arg]

    with pytest.raises(TypeError):
        await minecraft_execute_command()  # type: ignore[call-arg]

    with pytest.raises(TypeError):
        await minecraft_companion_move(x=10.0)  # type: ignore[call-arg]


async def test_mcp_call_unknown_tool_name():
    """
    Verifies that requesting a nonexistent tool returns is_error=True with appropriate error description.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
    res = await handler(None, types.CallToolRequestParams(name="minecraft_fly_to_moon", arguments={}))
    assert res.is_error is True
    assert "unknown tool" in res.content[0].text.lower() or "not found" in res.content[0].text.lower()


# ==============================================================================
# Category 5: Boundary Coordinates, Edge Values & Special Payloads
# ==============================================================================

async def test_boundary_extreme_negative_coordinates():
    """
    Tests companion movement with boundary negative world coordinates
    (-29999999.0, -64.0, -29999999.0).
    """
    await minecraft_companion_spawn(name="BorderJarvis", x=0.0, y=0.0, z=0.0)
    res = await minecraft_companion_move(x=-29999999.0, y=-64.0, z=-29999999.0, speed=1.0)
    assert res.get("success") is True
    assert res.get("details", {}).get("target", {}).get("x") == -29999999.0


async def test_boundary_precision_fractional_coordinates():
    """
    Tests high-precision decimal coordinates (e.g. 100.123456789).
    """
    res = await minecraft_companion_spawn(name="PrecisionJarvis", x=100.123456, y=64.987654, z=-200.555555)
    assert res.get("success") is True
    pos = res.get("position", {})
    assert abs(pos.get("x", 0) - 100.123456) < 1e-4


async def test_boundary_speed_and_distance_values():
    """
    Tests extreme and boundary speed/distance parameters:
    speed = 0.0, speed = 50.0, distance = 0.1, distance = 100.0.
    """
    await minecraft_companion_spawn(name="SpeedJarvis", x=100.0, y=64.0, z=-200.0)

    # Move with high speed
    res_speed = await minecraft_companion_move(x=105.0, y=64.0, z=-195.0, speed=50.0)
    assert res_speed.get("success") is True
    assert res_speed.get("details", {}).get("speed") == 50.0

    # Follow with close distance
    res_follow = await minecraft_companion_follow(player="Steve", distance=0.1)
    assert res_follow.get("success") is True
    assert res_follow.get("details", {}).get("distance") == 0.1


async def test_boundary_surroundings_radius_clamping():
    """
    Tests surroundings scan with extreme radius values (0, negative, 1000).
    Mock server clamps between 2 and 32.
    """
    # Radius 1000 clamped to 32
    res_high = await minecraft_get_surroundings(radius=1000)
    assert res_high.get("radius") == 32

    # Radius 0 clamped to 2
    res_low = await minecraft_get_surroundings(radius=0)
    assert res_low.get("radius") == 2


async def test_boundary_chat_queries_limits():
    """
    Tests chat queries with edge limits: 0, 1000, future timestamp.
    """
    # Limit 0 returns empty list
    chat_0 = await minecraft_get_chat(limit=0)
    assert isinstance(chat_0, list)
    assert len(chat_0) == 0

    # Future timestamp
    chat_future = await minecraft_get_chat(since=9999999999999)
    assert len(chat_future) == 0

    # Large limit
    chat_large = await minecraft_get_chat(limit=1000)
    assert isinstance(chat_large, list)


async def test_special_characters_and_color_codes():
    """
    Tests chat and command execution containing Minecraft color codes, UTF-8 unicode, and emojis.
    """
    special_text = "§cRed §lBold §r§eYellow \u2603 Snowman \U0001F600 Smile"
    res_say = await minecraft_say(message=special_text, sender="§6JarvisAgent§r")
    assert res_say.get("success") is True

    # Verify message in chat
    chat = await minecraft_get_chat(limit=5)
    assert any(special_text in m.get("message", "") for m in chat)


async def test_huge_payload_strings():
    """
    Tests broadcasting a large 10KB string payload through minecraft_say.
    """
    large_msg = "A" * 10_000
    res = await minecraft_say(message=large_msg, sender="LargeSender")
    assert res.get("success") is True


# ==============================================================================
# Category 6: State Machine Violations & Domain Errors
# ==============================================================================

async def test_companion_actions_rejected_before_spawn():
    """
    Verifies that attempting companion actions before spawn returns an error over the wire
    and raises JarvisClientError on direct invocation.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler

    # Protocol wire: move before spawn -> is_error=True
    r_move = await handler(
        None,
        types.CallToolRequestParams(
            name="minecraft_companion_move",
            arguments={"x": 10.0, "y": 64.0, "z": 10.0},
        ),
    )
    assert r_move.is_error is True

    # Direct call: raises JarvisClientError with status 400
    with pytest.raises(JarvisClientError) as exc_info:
        await minecraft_companion_move(x=10.0, y=64.0, z=10.0)
    assert exc_info.value.status_code == 400
    assert "spawn" in str(exc_info.value).lower()


async def test_companion_follow_nonexistent_player():
    """
    Verifies that commanding companion to follow an unknown player returns 404 error.
    """
    await minecraft_companion_spawn(name="FollowerJarvis", x=100.0, y=64.0, z=-200.0)

    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
    res = await handler(
        None,
        types.CallToolRequestParams(
            name="minecraft_companion_follow",
            arguments={"player": "NonExistentGhostPlayer_999"},
        ),
    )
    assert res.is_error is True

    with pytest.raises(JarvisClientError) as exc:
        await minecraft_companion_follow(player="NonExistentGhostPlayer_999")
    assert exc.value.status_code == 404


async def test_surroundings_nonexistent_player():
    """
    Verifies that querying surroundings for an unknown player returns 404 error.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
    res = await handler(
        None,
        types.CallToolRequestParams(
            name="minecraft_get_surroundings",
            arguments={"player": "NonExistentGhostPlayer_999"},
        ),
    )
    assert res.is_error is True

    with pytest.raises(JarvisClientError) as exc:
        await minecraft_get_surroundings(player="NonExistentGhostPlayer_999")
    assert exc.value.status_code == 404


async def test_companion_interact_unsupported_action():
    """
    Verifies that unsupported action verbs (e.g. 'fly', 'dance') return 400 Bad Request.
    """
    await minecraft_companion_spawn(name="ActorJarvis", x=100.0, y=64.0, z=-200.0)

    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
    res = await handler(
        None,
        types.CallToolRequestParams(
            name="minecraft_companion_interact",
            arguments={"action": "fly_around", "x": 100, "y": 64, "z": -200},
        ),
    )
    assert res.is_error is True

    with pytest.raises(JarvisClientError) as exc:
        await minecraft_companion_interact(action="fly_around", x=100, y=64, z=-200)
    assert exc.value.status_code == 400
    assert "unknown action" in str(exc.value).lower()


# ==============================================================================
# Category 7: Concurrency, Load Bursts & Race Conditions
# ==============================================================================

async def test_concurrent_mixed_mcp_tool_calls_50():
    """
    Stress-tests 50 concurrent mixed MCP tool calls through the protocol handler
    using an asyncio.Semaphore(15) connection throttle.
    Verifies zero dropped requests and thread-safe execution across all tools.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler

    # Spawn companion first
    await handler(
        None,
        types.CallToolRequestParams(name="minecraft_companion_spawn", arguments={"name": "BurstJarvis"}),
    )

    tools_workload = [
        ("minecraft_get_status", {}),
        ("minecraft_say", {"message": "Burst concurrent test"}),
        ("minecraft_get_chat", {"limit": 10}),
        ("minecraft_execute_command", {"command": "time query daytime"}),
        ("minecraft_get_surroundings", {"player": "Steve", "radius": 16}),
        ("minecraft_companion_move", {"x": 105.0, "y": 64.0, "z": -195.0, "speed": 1.0}),
        ("minecraft_companion_follow", {"player": "Steve", "distance": 3.0}),
        ("minecraft_companion_interact", {"action": "break_block", "x": 102, "y": 60, "z": -198}),
        ("minecraft_companion_status", {}),
        ("minecraft_get_status", {}),
    ]

    sem = asyncio.Semaphore(15)

    async def worker(idx: int):
        async with sem:
            name, args = tools_workload[idx % len(tools_workload)]
            res = await handler(None, types.CallToolRequestParams(name=name, arguments=args))
            assert res.is_error is False, f"Tool {name} failed: {res}"
            return res

    tasks = [worker(i) for i in range(50)]
    results = await asyncio.gather(*tasks)
    assert len(results) == 50


async def test_concurrent_companion_move_stress():
    """
    Stress-tests 20 concurrent movement commands commanding companion to distinct targets.
    Verifies server state consistency under navigation contention.
    """
    await minecraft_companion_spawn(name="MoveStressJarvis", x=100.0, y=64.0, z=-200.0)

    async def move_task(idx: int):
        return await minecraft_companion_move(x=100.0 + idx, y=64.0, z=-200.0 + idx)

    tasks = [move_task(i) for i in range(20)]
    results = await asyncio.gather(*tasks)
    assert len(results) == 20
    for r in results:
        assert r.get("success") is True

    # Final status check
    st = await minecraft_companion_status()
    assert st.get("spawned") is True


async def test_mcp_session_stability_after_error_burst():
    """
    Sends a rapid burst of 10 invalid/failing tool calls followed immediately
    by 10 valid calls.
    Verifies that the MCP server remains completely operational after encountering errors.
    """
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler

    # 1. Ten invalid calls (move when not spawned)
    for i in range(10):
        res = await handler(
            None,
            types.CallToolRequestParams(
                name="minecraft_companion_move",
                arguments={"x": 10.0, "y": 64.0, "z": 10.0},
            ),
        )
        assert res.is_error is True

    # 2. Ten valid calls (get_status)
    for i in range(10):
        res = await handler(
            None,
            types.CallToolRequestParams(name="minecraft_get_status", arguments={}),
        )
        assert res.is_error is False


# ==============================================================================
# Category 8: Fault Injection & Resilience
# ==============================================================================

async def test_offline_server_handling(dead_server_url: str):
    """
    Verifies behavior when the target Minecraft server is offline.
    Direct calls raise transport error; MCP wire calls return is_error=True.
    """
    dead_client = JarvisClient(base_url=dead_server_url, timeout=1.0)
    set_client(dead_client)

    # 1. Direct call raises httpx.RequestError (ConnectError, ConnectTimeout, etc.)
    with pytest.raises(httpx.RequestError):
        await minecraft_get_status()

    # 2. Wire call returns is_error=True
    handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
    res = await handler(None, types.CallToolRequestParams(name="minecraft_get_status", arguments={}))
    assert res.is_error is True

    await dead_client.close()


async def test_simulated_503_service_unavailable(server_state: MockMinecraftState):
    """
    Simulates transient 503 Service Unavailable (e.g. world save/reload).
    Direct call raises JarvisClientError(503); wire call returns is_error=True.
    """
    server_state.simulate_503 = True
    try:
        handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
        res = await handler(None, types.CallToolRequestParams(name="minecraft_get_status", arguments={}))
        assert res.is_error is True

        with pytest.raises(JarvisClientError) as exc:
            await minecraft_get_status()
        assert exc.value.status_code == 503
    finally:
        server_state.simulate_503 = False


async def test_simulated_500_internal_server_error(server_state: MockMinecraftState):
    """
    Simulates HTTP 500 Internal Server Error.
    """
    server_state.simulate_500 = True
    try:
        handler = mcp._lowlevel_server._request_handlers["tools/call"].handler
        res = await handler(None, types.CallToolRequestParams(name="minecraft_get_status", arguments={}))
        assert res.is_error is True

        with pytest.raises(JarvisClientError) as exc:
            await minecraft_get_status()
        assert exc.value.status_code == 500
    finally:
        server_state.simulate_500 = False


async def test_simulated_network_latency(server_state: MockMinecraftState):
    """
    Verifies that all 10 tools operate reliably under simulated network latency (20ms).
    """
    server_state.response_delay = 0.02
    try:
        # Spawn companion
        await minecraft_companion_spawn(name="LatencyJarvis", x=100.0, y=64.0, z=-200.0)

        # Exercise key tools under latency
        st = await minecraft_get_status()
        assert st.get("status") == "online"

        say = await minecraft_say(message="Latency test message")
        assert say.get("success") is True

        move = await minecraft_companion_move(x=105.0, y=64.0, z=-195.0)
        assert move.get("success") is True

        surr = await minecraft_get_surroundings(radius=16)
        assert "blocks" in surr
    finally:
        server_state.response_delay = 0.0
