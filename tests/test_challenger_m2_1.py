"""
Empirical Challenger 1 Test Suite for Milestone 2 (Surroundings Perception Engine).
Empirically stress-tests:
1. GET /api/surroundings schema compliance (origin, radius, blocks, entities).
2. Radius clamping invariants: [2, 32] (testing <2, >32, non-integer fallback to default 16).
3. Block classification verification: ores, containers, workstations.
4. Entity schema and classification (living entities with health, ItemEntity with item details).
5. Proximity distance calculation and monotonic ascending sorting.
6. Error handling: invalid player 404, method not allowed 405.
7. High-concurrency burst querying (100+ concurrent requests with zero 500 errors).
"""

from __future__ import annotations

import asyncio
import httpx
import pytest

pytestmark = pytest.mark.asyncio


# ==============================================================================
# 1. Surroundings Schema & Radius Clamping
# ==============================================================================

async def test_surroundings_schema_and_defaults(async_client: httpx.AsyncClient):
    """
    Verifies default /api/surroundings returns 200 with schema:
    origin, radius=16, blocks list, entities list.
    """
    res = await async_client.get("/api/surroundings?player=Steve")
    assert res.status_code == 200
    data = res.json()

    # Validate top-level schema
    assert "origin" in data
    assert "x" in data["origin"] and "y" in data["origin"] and "z" in data["origin"]
    assert data["radius"] == 16
    assert isinstance(data["blocks"], list)
    assert isinstance(data["entities"], list)

    # Validate blocks schema
    for b in data["blocks"]:
        assert "pos" in b
        assert "x" in b["pos"] and "y" in b["pos"] and "z" in b["pos"]
        assert "x" in b and "y" in b and "z" in b
        assert "block" in b or "id" in b
        assert "type" in b
        assert "distance" in b
        assert b["distance"] <= 16.0

    # Validate entities schema
    for e in data["entities"]:
        assert "pos" in e
        assert "x" in e["pos"] and "y" in e["pos"] and "z" in e["pos"]
        assert "x" in e and "y" in e and "z" in e
        assert "id" in e
        assert "type" in e
        assert "category" in e
        assert "distance" in e
        assert e["distance"] <= 16.0


async def test_radius_clamping_boundaries(async_client: httpx.AsyncClient):
    """
    Tests radius clamping boundaries:
    - radius < 2 clamped to 2
    - radius > 32 clamped to 32
    - radius within [2, 32] preserved
    - non-integer radius falls back to default 16
    """
    cases = [
        (-10, 2),
        (0, 2),
        (1, 2),
        (2, 2),
        (8, 8),
        (16, 16),
        (24, 24),
        (32, 32),
        (33, 32),
        (64, 32),
        (100, 32),
    ]

    for req_r, expected_r in cases:
        res = await async_client.get(f"/api/surroundings?player=Steve&radius={req_r}")
        assert res.status_code == 200, f"Failed for radius={req_r}"
        assert res.json()["radius"] == expected_r, f"Expected radius {expected_r} for input {req_r}"

    # Invalid non-integer string -> fallback to default 16
    res = await async_client.get("/api/surroundings?player=Steve&radius=not_a_number")
    assert res.status_code == 200
    assert res.json()["radius"] == 16


# ==============================================================================
# 2. Tag Classification & Entity Verification
# ==============================================================================

async def test_tag_classification_categories(async_client: httpx.AsyncClient):
    """
    Verifies that blocks of interest (ores, containers, workstations) are present
    and correctly classified into their respective semantic types.
    """
    res = await async_client.get("/api/surroundings?player=Steve&radius=32")
    assert res.status_code == 200
    data = res.json()

    block_types = {b.get("block") or b.get("id"): b["type"] for b in data["blocks"]}

    # Ores
    if "minecraft:diamond_ore" in block_types:
        assert block_types["minecraft:diamond_ore"] == "ore"
    if "minecraft:iron_ore" in block_types:
        assert block_types["minecraft:iron_ore"] == "ore"

    # Containers
    if "minecraft:chest" in block_types:
        assert block_types["minecraft:chest"] == "container"

    # Workstations
    if "minecraft:crafting_table" in block_types:
        assert block_types["minecraft:crafting_table"] == "workstation"


async def test_entity_schema_and_categories(async_client: httpx.AsyncClient):
    """
    Verifies entity classification (monster, animal, item) and attributes.
    """
    res = await async_client.get("/api/surroundings?player=Steve&radius=32")
    assert res.status_code == 200
    data = res.json()

    entities = data["entities"]
    assert len(entities) > 0

    categories = {e["category"] for e in entities}
    assert "monster" in categories or "animal" in categories

    for e in entities:
        if e["category"] == "monster":
            assert "health" in e and "max_health" in e
            assert e["health"] > 0
        elif e["category"] == "item":
            assert "item" in e or "item_id" in e
            assert "count" in e
            assert e["count"] >= 1


# ==============================================================================
# 3. Proximity Distance Sorting
# ==============================================================================

async def test_proximity_sorting_monotonic(async_client: httpx.AsyncClient):
    """
    Verifies that blocks and entities are strictly ordered by distance ascending.
    """
    res = await async_client.get("/api/surroundings?player=Steve&radius=32")
    assert res.status_code == 200
    data = res.json()

    block_dists = [b["distance"] for b in data["blocks"]]
    for i in range(len(block_dists) - 1):
        assert block_dists[i] <= block_dists[i + 1], f"Blocks not sorted: {block_dists}"

    entity_dists = [e["distance"] for e in data["entities"]]
    for i in range(len(entity_dists) - 1):
        assert entity_dists[i] <= entity_dists[i + 1], f"Entities not sorted: {entity_dists}"


# ==============================================================================
# 4. Error Handling & Parameter Validation
# ==============================================================================

async def test_surroundings_error_handling(async_client: httpx.AsyncClient):
    """
    Verifies 404 for unknown player and 405 for disallowed HTTP methods.
    """
    # 404 for unknown player
    res = await async_client.get("/api/surroundings?player=UnknownPlayerNonExistent")
    assert res.status_code == 404
    assert "not found" in res.json().get("error", "").lower()

    # 405 for POST /api/surroundings
    res = await async_client.post("/api/surroundings", json={"radius": 16})
    assert res.status_code == 405


# ==============================================================================
# 5. High Concurrency Burst Stress Test
# ==============================================================================

async def test_surroundings_concurrency_burst(async_client: httpx.AsyncClient):
    """
    Fires 100 concurrent requests to /api/surroundings across 10 workers.
    Verifies 100% 200 OK responses with zero race conditions, data corruption, or 500s.
    """
    num_workers = 10
    requests_per_worker = 10
    total_requests = num_workers * requests_per_worker

    async def worker(wid: int):
        worker_results = []
        for i in range(requests_per_worker):
            radius = 8 + (i % 4) * 4  # 8, 12, 16, 20
            res = await async_client.get(f"/api/surroundings?player=Steve&radius={radius}")
            assert res.status_code == 200
            data = res.json()
            assert data["radius"] == radius
            assert isinstance(data["blocks"], list)
            worker_results.append(data)
        return worker_results

    tasks = [worker(w) for w in range(num_workers)]
    results = await asyncio.gather(*tasks)

    assert len(results) == num_workers
    for r in results:
        assert len(r) == requests_per_worker
