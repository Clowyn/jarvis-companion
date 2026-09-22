"""
Comprehensive test suite for Phase 4 (Pillar 2: Autonomous Building Engine).
Verifies REST endpoints, client methods, MCP tools, and NLP chat triggers for procedural construction.
"""

from __future__ import annotations

import sys
import pytest
import pytest_asyncio

sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient, JarvisClientError
from jarvis_bridge.mcp_server import (
    set_client,
    minecraft_companion_build,
    minecraft_companion_build_status,
    minecraft_companion_build_cancel,
)
from jarvis_bridge.chat_agent import JarvisChatAgent


@pytest_asyncio.fixture
async def spawned_client(server_url: str):
    """Fixture that initializes a client and ensures the companion is spawned in the mock world."""
    client = JarvisClient(base_url=server_url, timeout=5.0)
    await client.companion_spawn(name="Jarvis", x=100.0, y=64.0, z=-200.0)
    yield client
    await client.close()


@pytest.mark.asyncio
async def test_client_companion_build_bridge(spawned_client: JarvisClient):
    """Verify companion_build successfully starts a procedural bridge task."""
    resp = await spawned_client.companion_build(
        structure="bridge",
        length=15,
        width=3,
        material="minecraft:cobblestone",
        railing=True,
        torches=True,
    )
    assert resp.success is True
    assert resp.task is not None
    assert resp.task.structure_type == "bridge"
    assert resp.task.total_blocks > 0
    assert resp.task.completed is False


@pytest.mark.asyncio
async def test_client_companion_build_shelter(spawned_client: JarvisClient):
    """Verify companion_build creates an emergency shelter task."""
    resp = await spawned_client.companion_build(
        structure="shelter",
        size=5,
        height=3,
        material="minecraft:oak_planks",
        door=True,
        torches=True,
    )
    assert resp.success is True
    assert resp.task is not None
    assert resp.task.structure_type == "shelter"


@pytest.mark.asyncio
async def test_client_companion_build_status_and_cancel(spawned_client: JarvisClient):
    """Verify checking build status and cancelling an active task."""
    # Start task
    await spawned_client.companion_build(structure="wall", length=9, height=3)

    # Check status
    st_resp = await spawned_client.companion_build_status()
    assert st_resp.success is True
    assert st_resp.active is True
    assert st_resp.task is not None
    assert st_resp.task.structure_type == "wall"

    # Cancel task
    c_resp = await spawned_client.companion_build_cancel()
    assert c_resp.success is True
    assert c_resp.cancelled is True

    # Status after cancellation
    st_resp2 = await spawned_client.companion_build_status()
    assert st_resp2.active is False


@pytest.mark.asyncio
async def test_mcp_building_tools(spawned_client: JarvisClient):
    """Verify building MCP tools execute properly against the active client."""
    set_client(spawned_client)
    try:
        build_res = await minecraft_companion_build(
            structure="platform",
            size_x=5,
            size_z=5,
            material="minecraft:stone",
        )
        assert build_res["success"] is True
        assert build_res["task"]["structure_type"] == "platform"

        status_res = await minecraft_companion_build_status()
        assert status_res["success"] is True
        assert status_res["active"] is True

        cancel_res = await minecraft_companion_build_cancel()
        assert cancel_res["success"] is True
        assert cancel_res["cancelled"] is True
    finally:
        set_client(None)


@pytest.mark.asyncio
async def test_chat_agent_bridge_nlp(spawned_client: JarvisClient):
    """Verify Turkish NLP triggers for building a bridge."""
    agent = JarvisChatAgent(client=spawned_client)

    reply = await agent._handle_prompt("Steve", "Jarvis 15 blok kopru yap")
    assert "kopru insaatina basliyorum" in reply.lower() or "köprü" in reply.lower()
    assert "15 blok" in reply.lower()


@pytest.mark.asyncio
async def test_chat_agent_shelter_nlp(spawned_client: JarvisClient):
    """Verify Turkish NLP triggers for building an emergency shelter."""
    agent = JarvisChatAgent(client=spawned_client)

    reply = await agent._handle_prompt("Steve", "Jarvis acil bir siginak yap")
    assert "siginagi" in reply.lower() or "sığınağı" in reply.lower() or "barinak" in reply.lower()


@pytest.mark.asyncio
async def test_chat_agent_wall_and_cancel_nlp(spawned_client: JarvisClient):
    """Verify Turkish NLP triggers for defensive walls and construction cancellation."""
    agent = JarvisChatAgent(client=spawned_client)

    # Build wall
    reply_wall = await agent._handle_prompt("Steve", "Jarvis savunma barikati kur")
    assert "barikati oruyorum" in reply_wall.lower() or "barikat" in reply_wall.lower()

    # Query status
    reply_status = await agent._handle_prompt("Steve", "Jarvis insaat durumu nedir")
    assert "devam ediyor" in reply_status.lower()

    # Cancel build
    reply_cancel = await agent._handle_prompt("Steve", "Jarvis insaati durdur")
    assert "durduruldu" in reply_cancel.lower() or "iptal" in reply_cancel.lower()
