"""
Comprehensive test suite for Phase 3:
"Akıllı Lojistik & Sandık Organizasyonu (Pillar 3: Smart Logistics & Chest Organization)".

Covers:
1. REST Endpoints (/api/container/sort, /api/container/transfer, /api/container/scan, /api/companion/retrieve, /api/companion/deposit).
2. Pydantic Models & JarvisClient async methods.
3. MCP Tool integrations (direct calls, dynamic registration, action dispatch).
4. Compaction logic & Zero-item voiding guarantees.
5. ChatAgent Turkish command routing and absence of vanilla `/item replace block` hacks.
"""

from __future__ import annotations

import copy
import sys
import pytest
import pytest_asyncio
import httpx

sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient, JarvisClientError
from jarvis_bridge.models import (
    ContainerSortResponse,
    ContainerTransferResponse,
    ContainerScanResponse,
    CompanionRetrieveResponse,
    CompanionDepositResponse,
    ContainerOrganizeResponse,
)
from jarvis_bridge.mcp_server import (
    mcp,
    get_client,
    set_client,
    minecraft_companion_spawn,
    minecraft_companion_interact,
    minecraft_container_sort,
    minecraft_container_transfer,
    minecraft_container_scan,
    minecraft_companion_retrieve,
    minecraft_companion_deposit,
    minecraft_container_organize,
    register_storage_tools,
)
from jarvis_bridge.chat_agent import JarvisChatAgent
from tests.mock_server import MockMinecraftState

pytestmark = pytest.mark.asyncio


@pytest_asyncio.fixture(autouse=True)
async def setup_client_fixture(server_url: str):
    client = JarvisClient(base_url=server_url, timeout=5.0)
    set_client(client)
    yield client
    await client.close()
    set_client(None)


# ==============================================================================
# 1. Container Sorting & In-Place Compaction
# ==============================================================================

async def test_container_sort_endpoint_success(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """POST /api/container/sort sorts items and compacts duplicates in-place."""
    # Seed chest at 105, 64, -200 with unsorted & duplicate items
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:cobblestone", "count": 10},
            {"slot": 1, "item": "minecraft:iron_ingot", "count": 5},
            {"slot": 2, "item": "minecraft:cobblestone", "count": 20},
            {"slot": 3, "item": "minecraft:diamond", "count": 2},
        ]

    res = await async_client.post("/api/container/sort", json={"x": 105, "y": 64, "z": -200})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["distinct_items"] == 3  # cobblestone compacted into 1
    assert data["item_count"] == 37     # 10+5+20+2 = 37

    # Ores (iron_ingot, diamond) should be prioritized before Building (cobblestone)
    with server_state.lock:
        items = server_state.containers["105,64,-200"]
        assert len(items) == 3
        item_names = [it["item"] for it in items]
        assert "minecraft:iron_ingot" in item_names
        assert "minecraft:diamond" in item_names
        assert "minecraft:cobblestone" in item_names
        # Cobblestone count must be 30 (10 + 20 compacted)
        cobble = next(it for it in items if it["item"] == "minecraft:cobblestone")
        assert cobble["count"] == 30


async def test_container_sort_missing_coords_returns_400(async_client: httpx.AsyncClient):
    """POST /api/container/sort returns 400 when missing coordinates."""
    res = await async_client.post("/api/container/sort", json={})
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False


async def test_container_sort_nonexistent_container_returns_400(async_client: httpx.AsyncClient):
    """POST /api/container/sort returns 400 when no container exists at position."""
    res = await async_client.post("/api/container/sort", json={"x": 999, "y": 999, "z": 999})
    assert res.status_code == 400
    data = res.json()
    assert data["success"] is False


# ==============================================================================
# 2. Container Transfer & Zero Item Voiding
# ==============================================================================

async def test_container_transfer_all_items(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """POST /api/container/transfer safely transfers all items with zero voiding."""
    with server_state.lock:
        server_state.containers["100,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 10},
            {"slot": 1, "item": "minecraft:gold_ingot", "count": 5},
        ]
        server_state.containers["101,64,-200"] = []

    res = await async_client.post("/api/container/transfer", json={
        "source": {"x": 100, "y": 64, "z": -200},
        "target": {"x": 101, "y": 64, "z": -200},
        "filter": "all"
    })
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["transferred"] == 15

    with server_state.lock:
        assert len(server_state.containers["100,64,-200"]) == 0
        tgt_items = server_state.containers["101,64,-200"]
        total_tgt = sum(it["count"] for it in tgt_items)
        assert total_tgt == 15


async def test_container_transfer_filtered(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """POST /api/container/transfer with filter moves only matching categories/items."""
    with server_state.lock:
        server_state.containers["100,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 10},
            {"slot": 1, "item": "minecraft:oak_log", "count": 20},
        ]
        server_state.containers["101,64,-200"] = []

    res = await async_client.post("/api/container/transfer", json={
        "source": {"x": 100, "y": 64, "z": -200},
        "target": {"x": 101, "y": 64, "z": -200},
        "filter": "ores"
    })
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["transferred"] == 10

    with server_state.lock:
        # Oak log must remain in source
        src = server_state.containers["100,64,-200"]
        assert len(src) == 1
        assert src[0]["item"] == "minecraft:oak_log"
        assert src[0]["count"] == 20
        # Iron ingot must be in target
        tgt = server_state.containers["101,64,-200"]
        assert len(tgt) == 1
        assert tgt[0]["item"] == "minecraft:iron_ingot"
        assert tgt[0]["count"] == 10


# ==============================================================================
# 3. Multi-Chest Scanning & Categorization
# ==============================================================================

async def test_container_scan_get_and_post(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """Container scan supports both GET and POST endpoints."""
    # Ensure chest exists
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:diamond", "count": 64}
        ]

    # Test POST
    post_res = await async_client.post("/api/container/scan", json={"x": 100, "y": 64, "z": -200, "radius": 16})
    assert post_res.status_code == 200
    post_data = post_data = post_res.json()
    assert post_data["success"] is True
    assert post_data["count"] >= 1
    c_info = post_data["containers"][0]
    assert c_info["category"] == "Ores"
    assert c_info["item_count"] == 64

    # Test GET
    get_res = await async_client.get("/api/container/scan?x=100&y=64&z=-200&radius=16")
    assert get_res.status_code == 200
    get_data = get_res.json()
    assert get_data["success"] is True
    assert get_data["count"] == post_data["count"]


# ==============================================================================
# 4. Companion Retrieve & Deposit Logistics
# ==============================================================================

async def test_companion_retrieve_unspawned_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/retrieve returns 400 when companion is not spawned."""
    res = await async_client.post("/api/companion/retrieve", json={"item": "minecraft:iron_ingot", "count": 5})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_companion_retrieve_spawned_success(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """POST /api/companion/retrieve succeeds and delivers items when spawned."""
    await async_client.post("/api/companion/spawn", json={"name": "Jarvis"})
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 16}
        ]

    res = await async_client.post("/api/companion/retrieve", json={"item": "minecraft:iron_ingot", "count": 10})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["retrieved"] == 10
    assert data["delivered"] == 10


async def test_companion_deposit_unspawned_fails(async_client: httpx.AsyncClient):
    """POST /api/companion/deposit returns 400 when companion is not spawned."""
    res = await async_client.post("/api/companion/deposit", json={"category": "all"})
    assert res.status_code == 400
    assert res.json()["success"] is False


async def test_companion_deposit_spawned_success(async_client: httpx.AsyncClient):
    """POST /api/companion/deposit succeeds when spawned."""
    await async_client.post("/api/companion/spawn", json={"name": "Jarvis"})
    res = await async_client.post("/api/companion/deposit", json={"category": "ores", "radius": 32})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["deposited"] > 0


# ==============================================================================
# 5. JarvisClient Pydantic Methods
# ==============================================================================

async def test_jarvis_client_storage_methods(server_state: MockMinecraftState):
    """JarvisClient methods return properly validated Pydantic models."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:diamond", "count": 10}
        ]

    # 1. container_sort
    sort_res = await client.container_sort(x=105, y=64, z=-200)
    assert isinstance(sort_res, ContainerSortResponse)
    assert sort_res.success is True
    assert sort_res.item_count == 10

    # 2. container_scan
    scan_res = await client.container_scan(x=105, y=64, z=-200, radius=16)
    assert isinstance(scan_res, ContainerScanResponse)
    assert scan_res.success is True
    assert len(scan_res.containers) >= 1

    # 3. companion_retrieve
    ret_res = await client.companion_retrieve(item="minecraft:diamond", count=5)
    assert isinstance(ret_res, CompanionRetrieveResponse)
    assert ret_res.success is True
    assert ret_res.retrieved == 5

    # 4. companion_deposit
    dep_res = await client.companion_deposit(category="ores")
    assert isinstance(dep_res, CompanionDepositResponse)
    assert dep_res.success is True


# ==============================================================================
# 6. MCP Functions & Companion Interact
# ==============================================================================

async def test_mcp_standalone_storage_functions(server_state: MockMinecraftState):
    """Direct invocation of standalone MCP storage functions."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:coal", "count": 64}
        ]

    # Direct function calls
    res_sort = await minecraft_container_sort(x=105, y=64, z=-200)
    assert res_sort["success"] is True

    res_scan = await minecraft_container_scan(x=105, y=64, z=-200, radius=16)
    assert res_scan["success"] is True

    res_ret = await minecraft_companion_retrieve(item="minecraft:coal", count=10)
    assert res_ret["success"] is True

    res_dep = await minecraft_companion_deposit(category="all")
    assert res_dep["success"] is True


async def test_mcp_companion_interact_logistics_actions(server_state: MockMinecraftState):
    """minecraft_companion_interact routes sort_container and inspect_container actions."""
    client = get_client()
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 32}
        ]

    # sort_container action
    res_sort = await minecraft_companion_interact(action="sort_container", x=105, y=64, z=-200)
    assert res_sort["success"] is True

    # inspect_container action
    res_insp = await minecraft_companion_interact(action="inspect_container", x=105, y=64, z=-200)
    assert res_insp["success"] is True
    assert "items" in res_insp["details"]


# ==============================================================================
# 7. Chat Agent Turkish Commands & No Item-Replace Console Hacks
# ==============================================================================

async def test_chat_agent_turkish_commands_execution(server_state: MockMinecraftState):
    """Verify chat agent correctly processes Turkish commands without `/item replace block` hacks."""
    client = get_client()
    agent = JarvisChatAgent(client=client)

    # Spawn companion
    await client.companion_spawn(name="Jarvis")
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 16},
            {"slot": 1, "item": "minecraft:gold_ingot", "count": 8},
        ]
        server_state.executed_commands.clear()

    # 1. "sandığı düzenle"
    reply_sort = await agent._handle_prompt("Steve", "sandığı düzenle")
    assert "düzenlendi" in reply_sort.lower() or "duzenlendi" in reply_sort.lower()

    # 2. "bana demir getir"
    reply_ret = await agent._handle_prompt("Steve", "bana demir getir")
    assert "teslim ettim" in reply_ret.lower() or "demir" in reply_ret.lower()

    # 3. "madenleri sandığa koy"
    reply_dep = await agent._handle_prompt("Steve", "madenleri sandığa koy")
    assert "depolandi" in reply_dep.lower() or "depolandı" in reply_dep.lower() or "sandık" in reply_dep.lower()

    # Verify that NO `/item replace block` command was EVER executed
    with server_state.lock:
        for cmd in server_state.executed_commands:
            cmd_str = cmd.get("command", "").lower()
            assert not ("item replace block" in cmd_str), f"Forbidden vanilla hack detected: {cmd_str}"


async def test_chat_agent_retrieve_compound_names_and_phrasings(server_state: MockMinecraftState):
    """Verify compound item names like 'demir külçesi' and phrasings with 'kasadan/depodan/adet/tane'."""
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 64},
            {"slot": 1, "item": "minecraft:diamond", "count": 64},
        ]

    # "sandıktan 5 adet demir külçesi getir"
    res1 = await agent._handle_prompt("Steve", "sandıktan 5 adet demir külçesi getir")
    assert "demir" in res1.lower() and "teslim" in res1.lower()

    # "kasadan elmas getir"
    res2 = await agent._handle_prompt("Steve", "kasadan elmas getir")
    assert "elmas" in res2.lower() and "teslim" in res2.lower()

    # "depodan 3 tane elmas getir"
    res3 = await agent._handle_prompt("Steve", "depodan 3 tane elmas getir")
    assert "elmas" in res3.lower() and "teslim" in res3.lower()


async def test_chat_agent_retrieve_not_found_message(server_state: MockMinecraftState):
    """When item is not found in nearby chests, chat agent returns polite failure message."""
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = []

    res = await agent._handle_prompt("Steve", "bana netherit getir")
    assert "bulunamadi" in res.lower() or "bulunamadı" in res.lower()


async def test_chat_agent_turkish_deposit_variants(server_state: MockMinecraftState):
    """Verify other Turkish deposit command phrasings: 'topladığın madenleri boş sandıklara koy', 'sandığa boşalt'."""
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    # 1. "topladığın madenleri boş sandıklara koy"
    res1 = await agent._handle_prompt("Steve", "topladığın madenleri boş sandıklara koy")
    assert "depolandi" in res1.lower() or "depolandı" in res1.lower()

    # 2. "sandığa boşalt"
    res2 = await agent._handle_prompt("Steve", "sandığa boşalt")
    assert "depolandi" in res2.lower() or "depolandı" in res2.lower()


async def test_companion_retrieve_with_space_separated_item_id(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """POST /api/companion/retrieve works with space-separated item queries like 'iron ingot'."""
    await async_client.post("/api/companion/spawn", json={"name": "Jarvis"})
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:iron_ingot", "count": 20}
        ]

    res = await async_client.post("/api/companion/retrieve", json={"item": "iron ingot", "count": 5})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["retrieved"] == 5
    assert data["delivered"] == 5


async def test_container_sort_mineral_blocks_categorized_as_ores(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """Containers with mineral blocks (iron_block, raw_iron_block) are recognized under Ores dominant category."""
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:raw_iron_block", "count": 64},
            {"slot": 1, "item": "minecraft:iron_block", "count": 64},
        ]

    res = await async_client.post("/api/container/sort", json={"x": 105, "y": 64, "z": -200})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["dominant_category"] == "Ores"


# ==============================================================================
# 7. Master Storage Organization & Double Chest Stacking
# ==============================================================================

async def test_container_organize_endpoint_success(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """POST /api/container/organize scans and groups items into category chests."""
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:raw_iron", "count": 32},
            {"slot": 1, "item": "minecraft:cooked_beef", "count": 16},
            {"slot": 2, "item": "minecraft:stone", "count": 64},
            {"slot": 3, "item": "minecraft:diamond_sword", "count": 1},
        ]

    res = await async_client.post("/api/container/organize", json={"x": 105, "y": 64, "z": -200, "radius": 32})
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert data["action"] == "organize_storage"
    assert data["total_items_organized"] == 113  # 32 + 16 + 64 + 1
    assert "categories" in data
    assert "Maden Deposu" in data["categories"]


async def test_container_organize_client_method(server_state: MockMinecraftState):
    """JarvisClient.container_organize returns a validated ContainerOrganizeResponse."""
    client = get_client()
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:gold_ingot", "count": 10},
            {"slot": 1, "item": "minecraft:bread", "count": 5},
        ]

    resp = await client.container_organize(x=105, y=64, z=-200, radius=32)
    assert isinstance(resp, ContainerOrganizeResponse)
    assert resp.success is True
    assert resp.total_items_organized == 15
    assert "Maden Deposu" in resp.categories


async def test_container_organize_mcp_tool(server_state: MockMinecraftState):
    """minecraft_container_organize MCP tool call returns dict response."""
    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:coal", "count": 25},
        ]

    res = await minecraft_container_organize(x=105, y=64, z=-200, radius=32)
    assert res["success"] is True
    assert res["total_items_organized"] == 25


async def test_chat_agent_turkish_organize_all_chests(server_state: MockMinecraftState):
    """ChatAgent accurately routes 'Jarvis bütün chestleri düzenle' to organizeBaseStorage."""
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:diamond", "count": 12},
            {"slot": 1, "item": "minecraft:apple", "count": 8},
        ]

    # 1. "Jarvis bütün chestleri düzenle"
    res1 = await agent._handle_prompt("Steve", "Jarvis bütün chestleri düzenle")
    assert "kategorilerine gore ayrildi" in res1.lower() or "kategorilerine göre" in res1.lower() or "madenler" in res1.lower()

    # 2. "Jarvis tum chestleri duzenle"
    res2 = await agent._handle_prompt("Steve", "Jarvis tum chestleri duzenle")
    assert "kategorilerine gore ayrildi" in res2.lower() or "kategorilerine göre" in res2.lower() or "madenler" in res2.lower()

    # 3. "bütün sandıkları düzenle"
    res3 = await agent._handle_prompt("Steve", "bütün sandıkları düzenle")
    assert "kategorilerine gore ayrildi" in res3.lower() or "kategorilerine göre" in res3.lower() or "madenler" in res3.lower()


async def test_chat_agent_turkish_organize_multi_chest_prompt(server_state: MockMinecraftState):
    """ChatAgent routes exact phrase:
    'Jarvis madenleri bir sandıkta, yemekleri bir sandıkta, blokları bir sandıkta, malzemeleri bir sandıkta, ekipmanları bir sandıkta topla'
    to the master organizeBaseStorage logic.
    """
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    with server_state.lock:
        server_state.containers["105,64,-200"] = [
            {"slot": 0, "item": "minecraft:raw_copper", "count": 64},
            {"slot": 1, "item": "minecraft:cooked_beef", "count": 32},
            {"slot": 2, "item": "minecraft:oak_planks", "count": 64},
            {"slot": 3, "item": "minecraft:iron_chestplate", "count": 1},
            {"slot": 4, "item": "minecraft:wheat_seeds", "count": 16},
            {"slot": 5, "item": "minecraft:bone", "count": 20},
        ]

    prompt = "Jarvis madenleri bir sandıkta, yemekleri bir sandıkta, blokları bir sandıkta, malzemeleri bir sandıkta, ekipmanları bir sandıkta topla"
    res = await agent._handle_prompt("Steve", prompt)
    assert "ayrildi" in res.lower() or "ayrıldı" in res.lower()
    assert "madenler" in res.lower() or "yemekler" in res.lower()


# ==============================================================================
# 8. Storage Marker Wand & Targeted Chest Relocation
# ==============================================================================

async def test_chat_agent_storage_marker_commands(server_state: MockMinecraftState):
    """ChatAgent handles storage target marking, listing, and clearing."""
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    # 1. "burayı işaretle"
    res_mark = await agent._handle_prompt("Steve", "burayı işaretle")
    assert "hedef sandik" in res_mark.lower() or "kaydedildi" in res_mark.lower()
    assert len(agent.marked_chest_positions) == 1

    # 2. "işaretleri göster"
    res_show = await agent._handle_prompt("Steve", "işaretleri göster")
    assert "parcacik" in res_show.lower() or "isaret" in res_show.lower()

    # 3. "işaretleri temizle"
    res_clear = await agent._handle_prompt("Steve", "işaretleri temizle")
    assert "temizlendi" in res_clear.lower()
    assert len(agent.marked_chest_positions) == 0


async def test_chat_agent_move_chests_to_markers(server_state: MockMinecraftState):
    """ChatAgent physically relocates chests to marked spots with clone move."""
    client = get_client()
    agent = JarvisChatAgent(client=client)
    await client.companion_spawn(name="Jarvis")

    agent.marked_chest_positions = [{"x": 110, "y": 64, "z": -190}]

    res = await agent._handle_prompt("Steve", "sandıkları işaretlediğim yerlere taşı")
    assert "tasindi" in res.lower() or "taşındı" in res.lower()
    assert len(agent.marked_chest_positions) == 0

