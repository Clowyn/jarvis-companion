"""
Comprehensive test suite for the 10 Jarvis MCP tools.
"""

from __future__ import annotations

import sys
import pytest
import pytest_asyncio

# Ensure bridge directory is on path
sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient
from jarvis_bridge.mcp_server import (
    mcp,
    minecraft_companion_follow,
    minecraft_companion_interact,
    minecraft_companion_move,
    minecraft_companion_spawn,
    minecraft_companion_status,
    minecraft_execute_command,
    minecraft_get_chat,
    minecraft_get_status,
    minecraft_get_surroundings,
    minecraft_say,
    set_client,
)


@pytest_asyncio.fixture(autouse=True)
async def setup_mcp_client(server_url: str):
    """Point the MCP server module to the active mock server."""
    client = JarvisClient(base_url=server_url, timeout=5.0)
    set_client(client)
    yield client
    await client.close()
    set_client(None)


def test_mcp_server_metadata_and_tool_count():
    """Verify that MCP server registers exactly all 10 tools."""
    tools = list(mcp._tool_manager._tools.keys())
    expected = [
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
    for tool_name in expected:
        assert tool_name in tools, f"Missing tool: {tool_name}"
    assert len(tools) == 10


@pytest.mark.asyncio
async def test_mcp_tool_1_get_status():
    res = await minecraft_get_status()
    assert isinstance(res, dict)
    assert res.get("status") == "online"
    assert "players" in res
    assert len(res["players"]) > 0


@pytest.mark.asyncio
async def test_mcp_tool_2_say():
    res = await minecraft_say(message="Testing MCP say tool", sender="JarvisAgent")
    assert isinstance(res, dict)
    assert res.get("success") is True


@pytest.mark.asyncio
async def test_mcp_tool_3_get_chat():
    await minecraft_say(message="Chat history entry", sender="JarvisAgent")
    res = await minecraft_get_chat(limit=5)
    assert isinstance(res, list)
    assert any("Chat history entry" in msg.get("message", "") for msg in res)


@pytest.mark.asyncio
async def test_mcp_tool_4_execute_command():
    res = await minecraft_execute_command(command="weather clear")
    assert isinstance(res, dict)
    assert res.get("success") is True


@pytest.mark.asyncio
async def test_mcp_tool_5_get_surroundings():
    res = await minecraft_get_surroundings(player="Steve", radius=16)
    assert isinstance(res, dict)
    assert res.get("player") == "Steve"
    assert "blocks" in res
    assert "entities" in res


@pytest.mark.asyncio
async def test_mcp_tool_6_companion_spawn():
    res = await minecraft_companion_spawn(name="Jarvis", x=100.5, y=64.0, z=-200.5)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("companion_id") == "jarvis-1"


@pytest.mark.asyncio
async def test_mcp_tool_7_companion_move():
    # Must spawn first
    await minecraft_companion_spawn(name="Jarvis", x=100.5, y=64.0, z=-200.5)
    res = await minecraft_companion_move(x=105.0, y=64.0, z=-195.0, speed=1.1)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("action") == "move_to"


@pytest.mark.asyncio
async def test_mcp_tool_8_companion_follow():
    await minecraft_companion_spawn(name="Jarvis", x=100.5, y=64.0, z=-200.5)
    res = await minecraft_companion_follow(player="Steve", distance=3.5)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("action") == "follow"


@pytest.mark.asyncio
async def test_mcp_tool_9_companion_interact():
    await minecraft_companion_spawn(name="Jarvis", x=100.5, y=64.0, z=-200.5)
    res = await minecraft_companion_interact(action="break_block", x=102, y=60, z=-198)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("action") == "break_block"


@pytest.mark.asyncio
async def test_mcp_tool_10_companion_status():
    # Before spawn
    res_before = await minecraft_companion_status()
    assert res_before.get("spawned") is False

    # Spawn
    await minecraft_companion_spawn(name="Jarvis", x=100.5, y=64.0, z=-200.5)

    # After spawn
    res_after = await minecraft_companion_status()
    assert res_after.get("spawned") is True
    assert res_after.get("health", 0) > 0
