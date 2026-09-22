"""
Comprehensive test suite for Phase 5 (Pillar 4: Modpack Awareness & Knowledge Engine).
Verifies in-game recipe querying, modded machine perception, mod listing, MCP tools, and Turkish NLP triggers.
"""

from __future__ import annotations

import sys
import pytest
import pytest_asyncio

sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient
from jarvis_bridge.mcp_server import (
    set_client,
    minecraft_get_recipes,
    minecraft_get_nearby_machines,
    minecraft_get_installed_mods,
)
from jarvis_bridge.chat_agent import JarvisChatAgent


@pytest_asyncio.fixture
async def active_client(server_url: str):
    """Fixture that provides an active REST client connected to the mock server."""
    client = JarvisClient(base_url=server_url, timeout=5.0)
    yield client
    await client.close()


@pytest.mark.asyncio
async def test_client_recipe_search(active_client: JarvisClient):
    """Verify searching for recipes returns structured inputs, outputs, and mod namespaces."""
    res = await active_client.modpack_recipes("steel")
    assert res.success is True
    assert res.count > 0
    assert any("steel" in r.recipe_id.lower() or "steel" in r.output.item.lower() for r in res.recipes)
    
    first = res.recipes[0]
    assert first.mod == "mekanism"
    assert first.output.item == "mekanism:ingot_steel"
    assert len(first.inputs) >= 2


@pytest.mark.asyncio
async def test_client_machine_scan(active_client: JarvisClient):
    """Verify scanning nearby machines detects modded machines and telemetry (FE energy, inventory)."""
    res = await active_client.modpack_machines(radius=24)
    assert res.success is True
    assert res.count > 0
    
    infuser = next((m for m in res.machines if m.mod == "mekanism"), None)
    assert infuser is not None
    assert infuser.name == "Metallurgic Infuser"
    assert infuser.energy is not None
    assert infuser.energy.stored == 45000
    assert infuser.energy.capacity == 100000
    assert infuser.energy.unit == "FE"


@pytest.mark.asyncio
async def test_client_mod_list(active_client: JarvisClient):
    """Verify retrieving loaded mods returns active Direwolf20 mods and versions."""
    res = await active_client.modpack_mods()
    assert res.success is True
    assert res.total_mods >= 5
    mod_ids = [m.id for m in res.mods]
    assert "mekanism" in mod_ids
    assert "appeng" in mod_ids
    assert "create" in mod_ids
    assert "justdirethings" in mod_ids
    assert "powah" in mod_ids


@pytest.mark.asyncio
async def test_mcp_modpack_tools(active_client: JarvisClient):
    """Verify modpack MCP tools execute cleanly."""
    set_client(active_client)
    try:
        r_res = await minecraft_get_recipes(item="steel")
        assert r_res["success"] is True
        assert len(r_res["recipes"]) > 0

        m_res = await minecraft_get_nearby_machines(radius=16)
        assert m_res["success"] is True
        assert len(m_res["machines"]) > 0

        mod_res = await minecraft_get_installed_mods()
        assert mod_res["success"] is True
        assert mod_res["total_mods"] >= 5
    finally:
        set_client(None)


@pytest.mark.asyncio
async def test_chat_agent_recipe_nlp(active_client: JarvisClient):
    """Verify Turkish NLP triggers for asking about recipes."""
    agent = JarvisChatAgent(client=active_client)

    reply = await agent._handle_prompt("Steve", "Jarvis steel nasil yapilir")
    assert "steel" in reply.lower() or "tarif" in reply.lower() or "mekanism" in reply.lower()


@pytest.mark.asyncio
async def test_chat_agent_machine_scan_nlp(active_client: JarvisClient):
    """Verify Turkish NLP triggers for querying nearby machines and energy status."""
    agent = JarvisChatAgent(client=active_client)

    reply = await agent._handle_prompt("Steve", "Jarvis yakin makineler neler")
    assert "makine" in reply.lower()
    assert "mekanism" in reply.lower() or "infuser" in reply.lower()
