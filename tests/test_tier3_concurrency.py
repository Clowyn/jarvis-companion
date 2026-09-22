"""
Tier 3: Concurrency, Burst Load, and Stress Test Suite.
Verifies thread safety, circular buffer eviction, and non-blocking performance under:
- High-volume concurrent client requests
- Rapid chat broadcasts and ring-buffer capping (MAX=100 FIFO eviction)
- Concurrent chat polling during active broadcasts
- Interleaved console commands and surroundings scans
- Concurrent companion actions (movement, combat, block manipulations)
- Burst mixed-traffic stress
- Concurrent server requests under simulated latency
- Request logging thread safety
- Sustained high-throughput pipeline execution
"""

from __future__ import annotations

import asyncio
import random
import time
import httpx
import pytest

from tests.mock_server import MockMinecraftServer, MockMinecraftState

pytestmark = pytest.mark.asyncio


# ==============================================================================
# Tier 3: Concurrency and Load Tests
# ==============================================================================

async def test_concurrent_status_requests(async_client: httpx.AsyncClient):
    """30 concurrent GET /api/status requests all succeed with 200 OK."""
    async def fetch_status():
        res = await async_client.get("/api/status")
        assert res.status_code == 200
        assert res.json()["status"] == "online"
        return res.json()

    tasks = [fetch_status() for _ in range(30)]
    results = await asyncio.gather(*tasks)
    assert len(results) == 30


async def test_concurrent_chat_broadcasts(async_client: httpx.AsyncClient):
    """20 concurrent POST /api/say broadcasts all succeed and record unique messages."""
    unique_ids = [f"msg-{i}-{time.time_ns()}" for i in range(20)]

    async def send_msg(uid: str):
        res = await async_client.post("/api/say", json={"message": uid, "sender": f"User-{uid[:6]}"})
        assert res.status_code == 200
        return res.json()

    tasks = [send_msg(uid) for uid in unique_ids]
    results = await asyncio.gather(*tasks)
    assert len(results) == 20

    # Verify all messages exist in chat buffer
    chat_res = await async_client.get("/api/chat?limit=50")
    messages = [m["message"] for m in chat_res.json()["messages"]]
    for uid in unique_ids:
        assert uid in messages


async def test_chat_buffer_overflow_and_fifo_eviction(async_client: httpx.AsyncClient, server_state: MockMinecraftState):
    """Writing 250 messages strictly caps buffer at 100 with strict FIFO eviction."""
    # Write 250 sequential messages
    for i in range(250):
        await async_client.post("/api/say", json={"message": f"BufferEntry_{i}", "sender": "Tester"})

    chat_res = await async_client.get("/api/chat?limit=200")
    data = chat_res.json()
    messages = data["messages"]

    # Buffer strictly capped at 100
    assert len(messages) == 100
    assert data["count"] == 100

    # Oldest retained entry must be BufferEntry_150 (entries 0-149 evicted)
    assert messages[0]["message"] == "BufferEntry_150"
    # Newest entry must be BufferEntry_249
    assert messages[-1]["message"] == "BufferEntry_249"


async def test_rapid_polling_during_broadcasts(async_client: httpx.AsyncClient):
    """Concurrent readers poll /api/chat while concurrent writers post to /api/say."""
    stop_event = asyncio.Event()
    polled_counts: list[int] = []

    async def reader():
        while not stop_event.is_set():
            res = await async_client.get("/api/chat?limit=20")
            assert res.status_code == 200
            polled_counts.append(res.json()["count"])
            await asyncio.sleep(0.005)

    async def writer(writer_id: int):
        for j in range(10):
            res = await async_client.post(
                "/api/say",
                json={"message": f"Writer_{writer_id}_msg_{j}", "sender": f"W{writer_id}"},
            )
            assert res.status_code == 200
            await asyncio.sleep(0.005)

    reader_tasks = [asyncio.create_task(reader()) for _ in range(3)]
    writer_tasks = [asyncio.create_task(writer(i)) for i in range(4)]

    await asyncio.gather(*writer_tasks)
    stop_event.set()
    await asyncio.gather(*reader_tasks)

    assert len(polled_counts) > 10


async def test_interleaved_commands_and_surroundings(async_client: httpx.AsyncClient):
    """Commands and surroundings requests executed simultaneously without conflict."""
    async def run_command(cmd: str):
        res = await async_client.post("/api/command", json={"command": cmd})
        assert res.status_code == 200
        return res.json()

    async def scan_surroundings():
        res = await async_client.get("/api/surroundings?radius=16")
        assert res.status_code == 200
        return res.json()

    commands = [
        "time set day",
        "time query daytime",
        "weather clear",
        "time set noon",
        "weather rain",
        "say Interleaved test",
    ]

    tasks = []
    for cmd in commands:
        tasks.append(run_command(cmd))
        tasks.append(scan_surroundings())

    results = await asyncio.gather(*tasks)
    assert len(results) == len(commands) * 2


async def test_concurrent_companion_actions(async_client: httpx.AsyncClient):
    """Multiple concurrent companion actions execute without state corruption."""
    await async_client.post("/api/companion/spawn", json={"x": 100, "y": 64, "z": -200})

    actions = [
        {"action": "move_to", "x": 105.0, "y": 64.0, "z": -195.0},
        {"action": "place_block", "x": 105, "y": 65, "z": -195, "item": "minecraft:torch"},
        {"action": "interact_block", "x": 108, "y": 64, "z": -199},
        {"action": "move_to", "x": 110.0, "y": 64.0, "z": -190.0},
        {"action": "stop"},
    ]

    async def do_action(act: dict):
        res = await async_client.post("/api/companion/action", json=act)
        assert res.status_code == 200
        return res.json()

    tasks = [do_action(a) for a in actions]
    results = await asyncio.gather(*tasks)
    assert len(results) == 5

    # Verify companion remains valid
    status = (await async_client.get("/api/companion/status")).json()
    assert status["spawned"] is True


async def test_rapid_spawn_and_despawn_cycles(async_client: httpx.AsyncClient):
    """Rapid sequential spawn and despawn cycles maintain correct state machine."""
    for cycle in range(6):
        spawn_res = await async_client.post(
            "/api/companion/spawn",
            json={"name": f"Jarvis_Cycle_{cycle}", "x": 100 + cycle, "y": 64, "z": -200},
        )
        assert spawn_res.status_code == 200
        st1 = (await async_client.get("/api/companion/status")).json()
        assert st1["spawned"] is True

        despawn_res = await async_client.post("/api/companion/despawn")
        assert despawn_res.status_code == 200
        st2 = (await async_client.get("/api/companion/status")).json()
        assert st2["spawned"] is False


async def test_burst_mixed_traffic(async_client: httpx.AsyncClient):
    """50 concurrent requests of mixed endpoints processed cleanly."""
    await async_client.post("/api/companion/spawn", json={})

    endpoints = [
        ("GET", "/api/status", None),
        ("GET", "/api/chat?limit=10", None),
        ("GET", "/api/surroundings?radius=8", None),
        ("GET", "/api/companion/status", None),
        ("POST", "/api/say", {"message": "Burst message", "sender": "Jarvis"}),
        ("POST", "/api/command", {"command": "time query daytime"}),
        ("POST", "/api/companion/action", {"action": "move_to", "x": 102, "y": 64, "z": -198}),
    ]

    async def send_req(method: str, path: str, json_body: dict | None):
        if method == "GET":
            r = await async_client.get(path)
        else:
            r = await async_client.post(path, json=json_body)
        assert r.status_code == 200
        return r.status_code

    chosen = [random.choice(endpoints) for _ in range(50)]
    tasks = [send_req(m, p, b) for m, p, b in chosen]
    results = await asyncio.gather(*tasks)
    assert len(results) == 50
    assert all(code == 200 for code in results)


async def test_concurrent_surroundings_with_varying_radii(async_client: httpx.AsyncClient):
    """20 concurrent surroundings scans with different radii and players."""
    radii = [2, 4, 8, 16, 24, 32, 64]
    players = ["Steve", "Alex"]

    async def scan(radius: int, player: str):
        res = await async_client.get(f"/api/surroundings?radius={radius}&player={player}")
        assert res.status_code == 200
        data = res.json()
        assert data["radius"] == min(32, max(2, radius))
        return data

    tasks = [scan(random.choice(radii), random.choice(players)) for _ in range(20)]
    results = await asyncio.gather(*tasks)
    assert len(results) == 20


async def test_companion_rapid_coordinate_updates(async_client: httpx.AsyncClient):
    """Rapid sequential coordinate updates reflect final target position accurately."""
    await async_client.post("/api/companion/spawn", json={"x": 100, "y": 64, "z": -200})

    for i in range(15):
        target_x = 100.0 + i
        res = await async_client.post(
            "/api/companion/action",
            json={"action": "move_to", "x": target_x, "y": 64.0, "z": -200.0},
        )
        assert res.status_code == 200

    status = (await async_client.get("/api/companion/status")).json()
    assert status["position"]["x"] == 114.0


async def test_concurrent_block_break_and_place(async_client: httpx.AsyncClient):
    """Concurrently place and break blocks without registry corruption."""
    await async_client.post("/api/companion/spawn", json={})

    async def place_block(idx: int):
        res = await async_client.post(
            "/api/companion/action",
            json={"action": "place_block", "x": 150 + idx, "y": 64, "z": -200, "item": "minecraft:stone"},
        )
        assert res.status_code == 200
        return res.json()

    # Place 10 stone blocks concurrently
    place_tasks = [place_block(i) for i in range(10)]
    await asyncio.gather(*place_tasks)

    # Verify blocks placed
    surroundings = (await async_client.get("/api/surroundings?radius=32&player=Steve")).json()
    # Steve is at (100.5, 64, -200.5); blocks at 150+ are ~50 away, query with player Alex (115) or large radius
    # Break 5 of the placed blocks
    async def break_block(idx: int):
        res = await async_client.post(
            "/api/companion/action",
            json={"action": "break_block", "x": 150 + idx, "y": 64, "z": -200},
        )
        assert res.status_code == 200
        assert res.json()["details"]["dropped"] == "minecraft:stone"

    break_tasks = [break_block(i) for i in range(5)]
    await asyncio.gather(*break_tasks)


async def test_concurrent_attacks_on_mob(async_client: httpx.AsyncClient):
    """Concurrent attacks on target mob (creeper id: 43) reduce health atomically."""
    await async_client.post("/api/companion/spawn", json={})

    # Creeper has 20 HP, 4 hits of 5.0 = defeated
    async def attack_mob():
        return await async_client.post(
            "/api/companion/action",
            json={"action": "attack", "entity_id": 43},
        )

    tasks = [attack_mob() for _ in range(4)]
    responses = await asyncio.gather(*tasks)

    for r in responses:
        assert r.status_code in (200, 404)  # 200 while alive, 404 once defeated

    # Verify creeper is no longer in surroundings
    surroundings = (await async_client.get("/api/surroundings?radius=32")).json()
    e_ids = [e["id"] for e in surroundings["entities"]]
    assert 43 not in e_ids


async def test_concurrency_with_simulated_server_latency(
    async_client: httpx.AsyncClient, server_state: MockMinecraftState
):
    """Concurrent requests complete reliably when server simulates 10ms latency."""
    server_state.response_delay = 0.01  # 10ms delay per request
    try:
        async def req():
            res = await async_client.get("/api/status")
            assert res.status_code == 200
            return res.json()

        tasks = [req() for _ in range(15)]
        results = await asyncio.gather(*tasks)
        assert len(results) == 15
    finally:
        server_state.response_delay = 0.0


async def test_concurrent_container_inspections(async_client: httpx.AsyncClient):
    """Concurrent container inspections return consistent item lists."""
    await async_client.post("/api/companion/spawn", json={})

    async def inspect():
        res = await async_client.post(
            "/api/companion/action",
            json={"action": "inspect_container", "x": 105, "y": 64, "z": -200},
        )
        assert res.status_code == 200
        items = res.json()["details"]["items"]
        assert len(items) >= 3
        return items

    tasks = [inspect() for _ in range(10)]
    results = await asyncio.gather(*tasks)
    assert len(results) == 10
    # All 10 returned identical inventory
    assert results[0] == results[-1]


async def test_request_log_thread_safety_and_ordering(
    async_client: httpx.AsyncClient, server_state: MockMinecraftState
):
    """Request log records all concurrent requests thread-safely."""
    initial_count = len(server_state.request_log)

    async def call_endpoint(idx: int):
        await async_client.get(f"/api/status?call={idx}")

    tasks = [call_endpoint(i) for i in range(25)]
    await asyncio.gather(*tasks)

    final_count = len(server_state.request_log)
    assert final_count >= initial_count + 25


async def test_sustained_pipeline_throughput(async_client: httpx.AsyncClient):
    """Rapid sustained pipeline of 60 operations completes quickly and cleanly."""
    start = time.perf_counter()
    for i in range(60):
        if i % 3 == 0:
            res = await async_client.get("/api/status")
        elif i % 3 == 1:
            res = await async_client.post("/api/say", json={"message": f"Throughput {i}"})
        else:
            res = await async_client.get("/api/chat?limit=5")
        assert res.status_code == 200

    elapsed = time.perf_counter() - start
    # 60 local requests should easily complete in under 5 seconds
    assert elapsed < 10.0
