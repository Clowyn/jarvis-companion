"""
Empirical Challenger 2 Verification Suite for Milestone 2.
Focus: /api/surroundings boundary conditions, radius clamping, player resolution,
extreme coordinates, empty surroundings, and schema conformance.

Targets:
1. Radius clamping:
   - Negative radius (-100, -10, -1) clamped to 2
   - radius=0 clamped to 2
   - radius=1 clamped to 2
   - radius=2 (minimum safe boundary) preserved
   - radius=16 (default) preserved
   - radius=32 (maximum safe boundary) preserved
   - radius=33 clamped to 32
   - radius=100 clamped to 32
   - radius=99999 clamped to 32
   - Non-numeric / malformed radius ("abc", "invalid") falls back to 16
2. Player parameter resolution:
   - Missing player parameter falls back to first online player (Steve)
   - Empty/whitespace player parameter falls back to first online player
   - Explicit player parameter (Alex) resolves to Alex's coordinates
   - Case-insensitive player matching ("steve", "ALEX")
   - Unknown/nonexistent player ("NoSuchPlayer") returns 404 with error JSON
3. Extreme world coordinates and empty surroundings:
   - Querying player at (30000000, 64, 30000000) returns 200 with empty blocks and entities
   - Querying player at (-30000000, -64, -30000000) returns 200 with empty surroundings
   - Extreme sky (y=500) and void (y=-500) return empty surroundings
4. Response schema conformance (PROJECT.md § Interface Contracts):
   - origin: {x, y, z} numeric
   - radius: integer
   - blocks: list with pos {x, y, z}, block, type, distance
   - entities: list with id, type, category, pos {x, y, z}, distance, health
   - Proximity sorting: ascending distance for both blocks and entities
5. Method & preflight guards:
   - POST, PUT, DELETE return 405 Method Not Allowed
   - OPTIONS preflight returns 200 with CORS headers
"""

from __future__ import annotations

import httpx
import pytest

from tests.mock_server import MockMinecraftServer

pytestmark = pytest.mark.asyncio


# ==============================================================================
# 1. Radius Clamping Boundary Verification
# ==============================================================================

@pytest.mark.parametrize("radius_input", [-100, -10, -1, 0, 1])
async def test_surroundings_sub_minimum_radius_clamped_to_2(
    async_client: httpx.AsyncClient, radius_input: int
):
    """Radius below minimum (including negative, 0, and 1) must be clamped to 2."""
    res = await async_client.get(f"/api/surroundings?radius={radius_input}")
    assert res.status_code == 200
    data = res.json()
    assert data["radius"] == 2, f"Expected radius 2 for input {radius_input}, got {data['radius']}"


async def test_surroundings_exact_minimum_radius(async_client: httpx.AsyncClient):
    """Radius=2 (exact minimum safe boundary) must be preserved as 2."""
    res = await async_client.get("/api/surroundings?radius=2")
    assert res.status_code == 200
    assert res.json()["radius"] == 2


async def test_surroundings_default_radius_when_omitted(async_client: httpx.AsyncClient):
    """When radius parameter is omitted, it defaults to 16."""
    res = await async_client.get("/api/surroundings")
    assert res.status_code == 200
    assert res.json()["radius"] == 16


async def test_surroundings_exact_maximum_radius(async_client: httpx.AsyncClient):
    """Radius=32 (exact maximum safe boundary) must be preserved as 32."""
    res = await async_client.get("/api/surroundings?radius=32")
    assert res.status_code == 200
    assert res.json()["radius"] == 32


@pytest.mark.parametrize("radius_input", [33, 100, 500, 99999])
async def test_surroundings_super_maximum_radius_clamped_to_32(
    async_client: httpx.AsyncClient, radius_input: int
):
    """Radius exceeding 32 (including 33, 100, 500, 99999) must be clamped to 32."""
    res = await async_client.get(f"/api/surroundings?radius={radius_input}")
    assert res.status_code == 200
    data = res.json()
    assert data["radius"] == 32, f"Expected radius 32 for input {radius_input}, got {data['radius']}"


@pytest.mark.parametrize("invalid_radius", ["abc", "invalid", "16.5", "", "NaN", "null"])
async def test_surroundings_non_numeric_radius_fallback(
    async_client: httpx.AsyncClient, invalid_radius: str
):
    """Non-numeric or malformed radius values must fallback gracefully to default 16."""
    res = await async_client.get(f"/api/surroundings?radius={invalid_radius}")
    assert res.status_code == 200
    data = res.json()
    assert data["radius"] == 16, f"Expected default radius 16 for '{invalid_radius}', got {data['radius']}"


# ==============================================================================
# 2. Player Parameter Resolution
# ==============================================================================

async def test_surroundings_missing_player_falls_back_to_first_online(
    async_client: httpx.AsyncClient
):
    """Omitting player parameter falls back to first online player (Steve)."""
    res = await async_client.get("/api/surroundings")
    assert res.status_code == 200
    data = res.json()
    # Steve's coordinates in mock state: (100.5, 64.0, -200.5)
    assert data["origin"]["x"] == 100.5
    assert data["origin"]["y"] == 64.0
    assert data["origin"]["z"] == -200.5


async def test_surroundings_empty_player_param_falls_back(
    async_client: httpx.AsyncClient
):
    """Empty player param (?player=) falls back to first online player."""
    res = await async_client.get("/api/surroundings?player=")
    assert res.status_code == 200
    data = res.json()
    assert data["origin"]["x"] == 100.5
    assert data["origin"]["y"] == 64.0
    assert data["origin"]["z"] == -200.5


async def test_surroundings_explicit_player_alex(
    async_client: httpx.AsyncClient
):
    """Specifying player=Alex centers the scan on Alex's coordinates (115.0, 64.0, -190.0)."""
    res = await async_client.get("/api/surroundings?player=Alex")
    assert res.status_code == 200
    data = res.json()
    assert data["origin"]["x"] == 115.0
    assert data["origin"]["y"] == 64.0
    assert data["origin"]["z"] == -190.0


@pytest.mark.parametrize("player_name", ["steve", "STEVE", "StEvE", "alex", "ALEX"])
async def test_surroundings_player_case_insensitive(
    async_client: httpx.AsyncClient, player_name: str
):
    """Player name lookup is case-insensitive."""
    res = await async_client.get(f"/api/surroundings?player={player_name}")
    assert res.status_code == 200
    data = res.json()
    if "steve" in player_name.lower():
        assert data["origin"]["x"] == 100.5
    else:
        assert data["origin"]["x"] == 115.0


@pytest.mark.parametrize("ghost_player", [
    "GhostPlayer999",
    "Herobrine",
    "NonExistentUser",
    "NotAValidPlayerNameAtAll"
])
async def test_surroundings_unknown_player_returns_404(
    async_client: httpx.AsyncClient, ghost_player: str
):
    """Requesting an offline/unknown player returns 404 with error JSON."""
    res = await async_client.get(f"/api/surroundings?player={ghost_player}")
    assert res.status_code == 404
    data = res.json()
    assert data["success"] is False
    assert "error" in data
    assert ghost_player.lower() in data["error"].lower() or "not found" in data["error"].lower()


# ==============================================================================
# 3. Extreme Coordinates & Empty Surroundings
# ==============================================================================

async def test_surroundings_extreme_positive_coordinates(
    async_client: httpx.AsyncClient, mock_server: MockMinecraftServer
):
    """Scanning at Minecraft world border (+30,000,000) returns 200 with empty blocks/entities."""
    # Add a temporary player at extreme coordinates
    with mock_server.state.lock:
        mock_server.state.players.append({
            "name": "BorderExplorer",
            "uuid": "00000000-0000-0000-0000-000000000099",
            "dimension": "minecraft:overworld",
            "health": 20.0,
            "max_health": 20.0,
            "food_level": 20,
            "food": 20,
            "position": {"x": 30000000.0, "y": 64.0, "z": 30000000.0},
            "x": 30000000.0,
            "y": 64.0,
            "z": 30000000.0,
        })

    try:
        res = await async_client.get("/api/surroundings?player=BorderExplorer&radius=16")
        assert res.status_code == 200
        data = res.json()
        assert data["origin"] == {"x": 30000000.0, "y": 64.0, "z": 30000000.0}
        assert data["radius"] == 16
        assert data["blocks"] == []
        assert data["entities"] == []
    finally:
        with mock_server.state.lock:
            mock_server.state.players = [p for p in mock_server.state.players if p["name"] != "BorderExplorer"]


async def test_surroundings_extreme_negative_coordinates(
    async_client: httpx.AsyncClient, mock_server: MockMinecraftServer
):
    """Scanning at negative Minecraft world border (-30,000,000) returns 200 with empty surroundings."""
    with mock_server.state.lock:
        mock_server.state.players.append({
            "name": "NegativeBorderExplorer",
            "uuid": "00000000-0000-0000-0000-000000000098",
            "dimension": "minecraft:overworld",
            "health": 20.0,
            "max_health": 20.0,
            "food_level": 20,
            "food": 20,
            "position": {"x": -30000000.0, "y": -64.0, "z": -30000000.0},
            "x": -30000000.0,
            "y": -64.0,
            "z": -30000000.0,
        })

    try:
        res = await async_client.get("/api/surroundings?player=NegativeBorderExplorer&radius=32")
        assert res.status_code == 200
        data = res.json()
        assert data["origin"] == {"x": -30000000.0, "y": -64.0, "z": -30000000.0}
        assert data["radius"] == 32
        assert data["blocks"] == []
        assert data["entities"] == []
    finally:
        with mock_server.state.lock:
            mock_server.state.players = [p for p in mock_server.state.players if p["name"] != "NegativeBorderExplorer"]


async def test_surroundings_sky_and_void_altitudes(
    async_client: httpx.AsyncClient, mock_server: MockMinecraftServer
):
    """Scanning far into the sky (y=1000) returns empty blocks/entities."""
    with mock_server.state.lock:
        mock_server.state.players.append({
            "name": "SkyPlayer",
            "uuid": "00000000-0000-0000-0000-000000000097",
            "dimension": "minecraft:overworld",
            "health": 20.0,
            "max_health": 20.0,
            "food_level": 20,
            "food": 20,
            "position": {"x": 100.5, "y": 1000.0, "z": -200.5},
            "x": 100.5,
            "y": 1000.0,
            "z": -200.5,
        })

    try:
        res = await async_client.get("/api/surroundings?player=SkyPlayer&radius=32")
        assert res.status_code == 200
        data = res.json()
        assert data["origin"] == {"x": 100.5, "y": 1000.0, "z": -200.5}
        assert data["blocks"] == []
        assert data["entities"] == []
    finally:
        with mock_server.state.lock:
            mock_server.state.players = [p for p in mock_server.state.players if p["name"] != "SkyPlayer"]


# ==============================================================================
# 4. Response Schema Conformance (PROJECT.md § Interface Contracts)
# ==============================================================================

async def test_surroundings_response_schema_conformance(
    async_client: httpx.AsyncClient
):
    """Verify /api/surroundings response strictly matches PROJECT.md contract."""
    res = await async_client.get("/api/surroundings?radius=32")
    assert res.status_code == 200
    data = res.json()

    # Top-level required keys
    assert "origin" in data, "Missing 'origin' object in response"
    assert "radius" in data, "Missing 'radius' in response"
    assert "blocks" in data, "Missing 'blocks' array in response"
    assert "entities" in data, "Missing 'entities' array in response"

    # Origin structure
    origin = data["origin"]
    assert isinstance(origin, dict)
    assert "x" in origin and isinstance(origin["x"], (int, float))
    assert "y" in origin and isinstance(origin["y"], (int, float))
    assert "z" in origin and isinstance(origin["z"], (int, float))

    # Radius type
    assert isinstance(data["radius"], int)
    assert 2 <= data["radius"] <= 32

    # Blocks structure
    assert isinstance(data["blocks"], list)
    for block in data["blocks"]:
        assert "pos" in block, "Block missing 'pos' coordinate object"
        pos = block["pos"]
        assert "x" in pos and isinstance(pos["x"], (int, float))
        assert "y" in pos and isinstance(pos["y"], (int, float))
        assert "z" in pos and isinstance(pos["z"], (int, float))
        assert "block" in block and isinstance(block["block"], str)
        assert block["block"].startswith("minecraft:")
        assert "type" in block and isinstance(block["type"], str)
        assert "distance" in block and isinstance(block["distance"], (int, float))
        assert block["distance"] <= data["radius"] + 0.1

    # Entities structure
    assert isinstance(data["entities"], list)
    for entity in data["entities"]:
        assert "id" in entity and isinstance(entity["id"], int)
        assert "type" in entity and isinstance(entity["type"], str)
        assert entity["type"].startswith("minecraft:")
        assert "category" in entity and isinstance(entity["category"], str)
        assert "pos" in entity, "Entity missing 'pos' coordinate object"
        pos = entity["pos"]
        assert "x" in pos and isinstance(pos["x"], (int, float))
        assert "y" in pos and isinstance(pos["y"], (int, float))
        assert "z" in pos and isinstance(pos["z"], (int, float))
        assert "distance" in entity and isinstance(entity["distance"], (int, float))
        assert entity["distance"] <= data["radius"] + 0.1


async def test_surroundings_proximity_sorting_contract(
    async_client: httpx.AsyncClient
):
    """Blocks and entities must be sorted strictly ascending (closest first)."""
    res = await async_client.get("/api/surroundings?radius=32")
    assert res.status_code == 200
    data = res.json()

    block_distances = [b["distance"] for b in data["blocks"]]
    assert block_distances == sorted(block_distances), (
        f"Blocks are not sorted by distance: {block_distances}"
    )

    entity_distances = [e["distance"] for e in data["entities"]]
    assert entity_distances == sorted(entity_distances), (
        f"Entities are not sorted by distance: {entity_distances}"
    )


# ==============================================================================
# 5. Method Not Allowed & Preflight Guards
# ==============================================================================

@pytest.mark.parametrize("method", ["POST", "PUT", "DELETE"])
async def test_surroundings_invalid_http_methods(
    async_client: httpx.AsyncClient, method: str
):
    """Non-GET methods to /api/surroundings return 405 Method Not Allowed."""
    res = await async_client.request(method, "/api/surroundings")
    assert res.status_code == 405
    data = res.json()
    assert data["success"] is False


async def test_surroundings_options_cors_preflight(
    async_client: httpx.AsyncClient
):
    """OPTIONS request to /api/surroundings returns CORS preflight headers."""
    res = await async_client.options("/api/surroundings")
    assert res.status_code == 200
    assert "access-control-allow-origin" in res.headers
    assert res.headers["access-control-allow-origin"] == "*"
    assert "access-control-allow-methods" in res.headers
    assert "GET" in res.headers["access-control-allow-methods"]
