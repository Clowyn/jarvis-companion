"""
Comprehensive tests for Phase 2:
"Savaş & Hayatta Kalma (Pillar 1: Combat & Physical Self-Defense Overhaul)".

Covers:
1. GET /api/companion/inventory endpoint contract (unspawned vs active).
2. JarvisClient.companion_inventory() async client invocation and Pydantic validation.
3. MCP inventory access via minecraft_companion_inventory and companion_interact(action="inventory").
4. Equipment slots representation (mainhand, offhand, head, chest, legs, feet).
5. 27-slot inventory structure, slot limits, and item properties.
"""

from __future__ import annotations

import sys
import pytest
import pytest_asyncio
import httpx

sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient, JarvisClientError
from jarvis_bridge.models import CompanionInventoryResponse
from jarvis_bridge.mcp_server import (
    get_client,
    set_client,
    minecraft_companion_interact,
    minecraft_companion_inventory,
    companion_inventory_resource,
    minecraft_companion_spawn,
)

pytestmark = pytest.mark.asyncio


@pytest_asyncio.fixture(autouse=True)
async def setup_client_fixture(server_url: str):
    client = JarvisClient(base_url=server_url, timeout=5.0)
    set_client(client)
    yield client
    await client.close()
    set_client(None)


async def test_inventory_endpoint_unspawned_returns_400(async_client: httpx.AsyncClient):
    """GET /api/companion/inventory returns HTTP 400 when companion is not spawned."""
    res = await async_client.get("/api/companion/inventory")
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False
    assert "not spawned" in data["error"].lower()


async def test_inventory_endpoint_after_spawn_returns_27_slots(async_client: httpx.AsyncClient):
    """GET /api/companion/inventory returns 200 with 27-slot inventory and equipment."""
    spawn_res = await async_client.post("/api/companion/spawn", json={"name": "Jarvis"})
    assert spawn_res.status_code == 200

    inv_res = await async_client.get("/api/companion/inventory")
    assert inv_res.status_code == 200
    inv = inv_res.json()
    assert inv["success"] is True
    assert inv["size"] == 27
    assert "slots" in inv
    assert isinstance(inv["slots"], list)
    assert "equipment" in inv
    eq = inv["equipment"]
    assert "mainhand" in eq
    assert "offhand" in eq
    assert "head" in eq
    assert "chest" in eq
    assert "legs" in eq
    assert "feet" in eq


async def test_jarvis_client_companion_inventory_pydantic():
    """JarvisClient.companion_inventory() properly deserializes into CompanionInventoryResponse model."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    inv = await client.companion_inventory()
    assert isinstance(inv, CompanionInventoryResponse)
    assert inv.success is True
    assert inv.size == 27
    assert isinstance(inv.slots, list)
    assert "mainhand" in inv.equipment
    assert "offhand" in inv.equipment


async def test_mcp_companion_inventory_direct_call():
    """minecraft_companion_inventory() MCP function returns valid dict matching schema."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    res = await minecraft_companion_inventory()
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("size") == 27
    assert "slots" in res
    assert "equipment" in res


async def test_mcp_companion_interact_inventory_action():
    """minecraft_companion_interact(action="inventory") dispatches to inventory query."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    res = await minecraft_companion_interact(action="inventory", x=0, y=0, z=0)
    assert isinstance(res, dict)
    assert res.get("success") is True
    assert res.get("size") == 27
    assert "equipment" in res


async def test_mcp_companion_inventory_resource():
    """companion_inventory_resource() returns valid JSON string with 27-slot structure."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    raw_json = await companion_inventory_resource()
    assert isinstance(raw_json, str)
    assert '"size":27' in raw_json or '"size": 27' in raw_json
    assert '"slots"' in raw_json
    assert '"equipment"' in raw_json
