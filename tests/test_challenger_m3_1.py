"""
Empirical Challenger 1 Test Suite for Milestone 3 (Companion Entity & In-Game Actions).

Stress and Concurrency Challenge Suite:
1. Rapid spawn/despawn cycling of the companion (sequential loops, duplicate despawn handling, race conditions).
2. Rapid sequential and direct state transitions (unspawned -> spawned/idle -> navigating -> following -> stopped -> despawned).
3. Concurrent burst requests to /api/companion/status while companion actions are dispatched.
4. Re-spawning while already active (verifying position update, target clearing, and state consistency).
"""

from __future__ import annotations

import asyncio
import random
import httpx
import pytest

from tests.mock_server import MockMinecraftState

pytestmark = pytest.mark.asyncio


# ==============================================================================
# Challenge 1: Rapid Spawn and Despawn Cycling
# ==============================================================================

async def test_rapid_spawn_despawn_cycling_sequential(async_client: httpx.AsyncClient):
    """
    Stress-tests 20 rapid sequential cycles of spawn -> status check -> despawn -> status check.
    Verifies that state machine alternates between spawned (idle) and unspawned with zero leaks or drift.
    """
    for cycle in range(20):
        target_x = 100.0 + cycle
        target_y = 64.0 + (cycle % 5)
        target_z = -200.0 - cycle

        # 1. Spawn companion
        spawn_res = await async_client.post(
            "/api/companion/spawn",
            json={"name": f"Jarvis_Cycle_{cycle}", "x": target_x, "y": target_y, "z": target_z},
        )
        assert spawn_res.status_code == 200, f"Spawn failed on cycle {cycle}: {spawn_res.text}"
        spawn_data = spawn_res.json()
        assert spawn_data["success"] is True
        assert spawn_data["companion_id"] == "jarvis-1"
        assert spawn_data["position"]["x"] == target_x
        assert spawn_data["position"]["y"] == target_y
        assert spawn_data["position"]["z"] == target_z

        # 2. Verify status reflects spawned/idle state
        status_res = await async_client.get("/api/companion/status")
        assert status_res.status_code == 200
        st1 = status_res.json()
        assert st1["spawned"] is True
        assert st1["active"] is True
        assert st1["name"] == f"Jarvis_Cycle_{cycle}"
        assert st1["state"] == "idle"
        assert st1["position"]["x"] == target_x
        assert st1["health"] == 20.0

        # 3. Despawn companion
        despawn_res = await async_client.post("/api/companion/despawn")
        assert despawn_res.status_code == 200, f"Despawn failed on cycle {cycle}: {despawn_res.text}"
        despawn_data = despawn_res.json()
        assert despawn_data["success"] is True

        # 4. Verify status reflects unspawned state
        status_unspawned = await async_client.get("/api/companion/status")
        assert status_unspawned.status_code == 200
        st2 = status_unspawned.json()
        assert st2["spawned"] is False
        assert st2["active"] is False
        assert st2["state"] == "unspawned"
        assert st2["position"] is None
        assert st2["name"] is None
        assert st2["companion_id"] is None


async def test_despawn_boundary_and_idempotency_failures(async_client: httpx.AsyncClient):
    """
    Verifies despawn error handling:
    - Despawning when initially unspawned returns 400 Bad Request.
    - Calling despawn twice in succession returns 200 for the first and 400 for the second.
    - Dispatching companion actions while unspawned returns 400 Bad Request.
    """
    # 1. Despawn while unspawned
    res = await async_client.post("/api/companion/despawn")
    assert res.status_code == 400
    assert "not spawned" in res.json().get("message", "").lower()

    # 2. Action while unspawned
    act_res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 105, "y": 64, "z": -200},
    )
    assert act_res.status_code == 400
    assert "not spawned" in act_res.json().get("error", "").lower()

    # 3. Spawn -> double despawn
    spawn_res = await async_client.post("/api/companion/spawn", json={"x": 100, "y": 64, "z": -200})
    assert spawn_res.status_code == 200

    first_despawn = await async_client.post("/api/companion/despawn")
    assert first_despawn.status_code == 200
    assert first_despawn.json()["success"] is True

    second_despawn = await async_client.post("/api/companion/despawn")
    assert second_despawn.status_code == 400
    assert "not spawned" in second_despawn.json().get("message", "").lower()


async def test_concurrent_spawn_and_despawn_interleaving(async_client: httpx.AsyncClient):
    """
    Fires 16 interleaved concurrent spawn and despawn requests simultaneously.
    Verifies that the server does not deadlock, throw 500, or corrupt state,
    and finishes in a self-consistent state.
    """
    async def spawn_task(idx: int):
        return await async_client.post(
            "/api/companion/spawn",
            json={"name": f"Concurrent_{idx}", "x": 100.0 + idx, "y": 64.0, "z": -200.0},
        )

    async def despawn_task():
        return await async_client.post("/api/companion/despawn")

    tasks = []
    for i in range(8):
        tasks.append(spawn_task(i))
        tasks.append(despawn_task())

    # Shuffle to introduce non-deterministic execution ordering
    random.shuffle(tasks)
    responses = await asyncio.gather(*tasks)

    for r in responses:
        # Spawn returns 200. Despawn returns 200 if companion was spawned, or 400 if already despawned.
        assert r.status_code in (200, 400)

    # Server must remain operational and status query must return valid schema
    final_status = await async_client.get("/api/companion/status")
    assert final_status.status_code == 200
    data = final_status.json()
    assert "spawned" in data
    assert "state" in data
    if data["spawned"]:
        assert data["state"] in ("idle", "navigating", "following")
        assert data["position"] is not None
    else:
        assert data["state"] == "unspawned"
        assert data["position"] is None


# ==============================================================================
# Challenge 2: Rapid Sequential State Transitions
# ==============================================================================

async def test_state_machine_full_lifecycle_sequence(async_client: httpx.AsyncClient):
    """
    Validates complete state lifecycle across 5 consecutive iterations:
    unspawned -> spawned (idle) -> navigating -> following -> stopped (idle) -> despawned (unspawned).
    """
    for iteration in range(5):
        # 1. Unspawned state check
        st0 = (await async_client.get("/api/companion/status")).json()
        assert st0["state"] == "unspawned"
        assert st0["spawned"] is False

        # 2. Spawn -> IDLE
        sp = await async_client.post(
            "/api/companion/spawn",
            json={"name": f"StateBot_{iteration}", "x": 100.0, "y": 64.0, "z": -200.0},
        )
        assert sp.status_code == 200
        st1 = (await async_client.get("/api/companion/status")).json()
        assert st1["state"] == "idle"
        assert st1["spawned"] is True
        assert st1["target"] is None

        # 3. move_to -> NAVIGATING
        mv = await async_client.post(
            "/api/companion/action",
            json={"action": "move_to", "x": 120.0, "y": 64.0, "z": -180.0, "speed": 1.2},
        )
        assert mv.status_code == 200
        assert mv.json()["action"] == "move_to"
        st2 = (await async_client.get("/api/companion/status")).json()
        assert st2["state"] == "navigating"
        assert st2["target"]["x"] == 120.0
        assert st2["target"]["y"] == 64.0
        assert st2["target"]["z"] == -180.0

        # 4. follow -> FOLLOWING
        fol = await async_client.post(
            "/api/companion/action",
            json={"action": "follow", "player": "Steve", "distance": 4.0},
        )
        assert fol.status_code == 200
        assert fol.json()["action"] == "follow"
        st3 = (await async_client.get("/api/companion/status")).json()
        assert st3["state"] == "following"
        assert st3["target"]["player"] == "Steve"
        assert st3["target"]["distance"] == 4.0

        # 5. stop -> IDLE
        stp = await async_client.post("/api/companion/action", json={"action": "stop"})
        assert stp.status_code == 200
        assert stp.json()["action"] == "stop"
        st4 = (await async_client.get("/api/companion/status")).json()
        assert st4["state"] == "idle"
        assert st4["target"] is None

        # 6. despawn -> UNSPAWNED
        desp = await async_client.post("/api/companion/despawn")
        assert desp.status_code == 200
        st5 = (await async_client.get("/api/companion/status")).json()
        assert st5["state"] == "unspawned"
        assert st5["spawned"] is False


async def test_direct_state_switching_without_intermediate_stops(async_client: httpx.AsyncClient):
    """
    Stress-tests rapid direct switching between active states:
    idle -> navigating -> following -> navigating -> stop (idle) -> following -> stop (idle).
    Verifies that target descriptors and state enum update atomically without requiring explicit stop calls.
    """
    await async_client.post("/api/companion/spawn", json={"x": 100.0, "y": 64.0, "z": -200.0})

    # Direct 1: idle -> navigating
    r1 = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 110.0, "y": 64.0, "z": -190.0},
    )
    assert r1.status_code == 200
    st1 = (await async_client.get("/api/companion/status")).json()
    assert st1["state"] == "navigating"

    # Direct 2: navigating -> following (no stop in between)
    r2 = await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Alex", "distance": 2.5},
    )
    assert r2.status_code == 200
    st2 = (await async_client.get("/api/companion/status")).json()
    assert st2["state"] == "following"
    assert st2["target"]["player"] == "Alex"

    # Direct 3: following -> navigating (no stop in between)
    r3 = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 105.0, "y": 64.0, "z": -195.0},
    )
    assert r3.status_code == 200
    st3 = (await async_client.get("/api/companion/status")).json()
    assert st3["state"] == "navigating"
    assert st3["target"]["x"] == 105.0

    # Direct 4: navigating -> stop -> idle
    r4 = await async_client.post(
        "/api/companion/action",
        json={"action": "stop"},
    )
    assert r4.status_code == 200
    st4 = (await async_client.get("/api/companion/status")).json()
    assert st4["state"] == "idle"
    assert st4["target"] is None

    # Direct 5: idle -> following
    r5 = await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Steve", "distance": 3.0},
    )
    assert r5.status_code == 200
    st5 = (await async_client.get("/api/companion/status")).json()
    assert st5["state"] == "following"
    assert st5["target"]["player"] == "Steve"

    # Direct 6: following -> stop -> idle
    r6 = await async_client.post("/api/companion/action", json={"action": "stop"})
    assert r6.status_code == 200
    st6 = (await async_client.get("/api/companion/status")).json()
    assert st6["state"] == "idle"
    assert st6["target"] is None


async def test_invalid_parameters_preserve_current_state(async_client: httpx.AsyncClient):
    """
    Verifies that malformed or invalid action dispatches reject cleanly with 400
    and leave the current companion state, position, and target uncorrupted.
    """
    await async_client.post("/api/companion/spawn", json={"x": 100.0, "y": 64.0, "z": -200.0})

    # Put companion into following state
    await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Steve", "distance": 3.0},
    )
    status_before = (await async_client.get("/api/companion/status")).json()
    assert status_before["state"] == "following"

    # 1. Invalid move_to (missing coords)
    bad_move = await async_client.post("/api/companion/action", json={"action": "move_to"})
    assert bad_move.status_code == 400

    # 2. Invalid follow (missing player)
    bad_follow = await async_client.post("/api/companion/action", json={"action": "follow"})
    assert bad_follow.status_code == 400

    # 3. Invalid attack (missing entity_id)
    bad_attack = await async_client.post("/api/companion/action", json={"action": "attack"})
    assert bad_attack.status_code == 400

    # 4. Unknown action name
    bad_action = await async_client.post("/api/companion/action", json={"action": "fly_to_space"})
    assert bad_action.status_code == 400

    # Verify state remains completely unchanged
    status_after = (await async_client.get("/api/companion/status")).json()
    assert status_after["state"] == "following"
    assert status_after["target"]["player"] == "Steve"
    assert status_after["position"] == status_before["position"]


# ==============================================================================
# Challenge 3: Concurrent Burst Status Queries During Active Mutations
# ==============================================================================

async def test_burst_status_polling_during_concurrent_action_mutations(async_client: httpx.AsyncClient):
    """
    Simulates high-frequency telemetry polling while an AI agent continuously executes
    companion actions (movement, block manipulation, combat, container access).
    Fires 75 concurrent status queries while mutating actions run concurrently in background.
    Verifies 100% 200 OK responses with strictly valid schema and non-corrupted state.
    """
    await async_client.post("/api/companion/spawn", json={"x": 100.0, "y": 64.0, "z": -200.0})

    stop_event = asyncio.Event()
    polled_statuses: list[dict] = []

    actions_pool = [
        {"action": "move_to", "x": 105.0, "y": 64.0, "z": -195.0, "speed": 1.0},
        {"action": "place_block", "x": 106, "y": 64, "z": -195, "item": "minecraft:stone"},
        {"action": "interact_block", "x": 106, "y": 64, "z": -195},
        {"action": "break_block", "x": 106, "y": 64, "z": -195},
        {"action": "follow", "player": "Steve", "distance": 3.0},
        {"action": "inspect_container", "x": 105, "y": 64, "z": -200},
        {"action": "attack", "entity_id": 42},
        {"action": "stop"},
    ]

    async def status_reader_worker(worker_id: int):
        for _ in range(25):
            res = await async_client.get("/api/companion/status")
            assert res.status_code == 200, f"Status poll failed in worker {worker_id}"
            data = res.json()

            # Strict schema assertions under concurrent read/write
            assert data["spawned"] is True
            assert data["active"] is True
            assert data["state"] in ("idle", "navigating", "following")
            assert isinstance(data["position"], dict)
            assert "x" in data["position"] and "y" in data["position"] and "z" in data["position"]
            assert data["health"] >= 0.0

            polled_statuses.append(data)
            await asyncio.sleep(0.001)

    async def action_writer_worker(writer_id: int):
        idx = 0
        while not stop_event.is_set():
            act = actions_pool[(writer_id + idx) % len(actions_pool)]
            res = await async_client.post("/api/companion/action", json=act)
            assert res.status_code in (200, 404), f"Action failed: {res.text}"  # 404 if mob 42 is killed
            idx += 1
            await asyncio.sleep(0.005)

    writers = [asyncio.create_task(action_writer_worker(w)) for w in range(3)]
    readers = [asyncio.create_task(status_reader_worker(w)) for w in range(3)]

    # Wait for all 75 status reads to complete while writers are actively mutating
    await asyncio.gather(*readers)
    stop_event.set()
    await asyncio.gather(*writers)

    assert len(polled_statuses) == 75
    # Final check: companion is alive and valid
    final_res = await async_client.get("/api/companion/status")
    assert final_res.status_code == 200
    assert final_res.json()["spawned"] is True


async def test_concurrent_status_burst_with_server_latency(
    async_client: httpx.AsyncClient, server_state: MockMinecraftState
):
    """
    Stress-tests concurrent status queries and companion actions under simulated server latency.
    Ensures threading locks prevent race conditions when tasks block on virtual/thread execution.
    """
    await async_client.post("/api/companion/spawn", json={"x": 100.0, "y": 64.0, "z": -200.0})

    server_state.response_delay = 0.005  # 5ms simulated latency per request
    try:
        async def poll_status():
            res = await async_client.get("/api/companion/status")
            assert res.status_code == 200
            st = res.json()
            assert st["spawned"] is True
            return st

        async def send_action(idx: int):
            target_x = 100.0 + idx
            res = await async_client.post(
                "/api/companion/action",
                json={"action": "move_to", "x": target_x, "y": 64.0, "z": -200.0},
            )
            assert res.status_code == 200
            return res.json()

        tasks = []
        for i in range(15):
            tasks.append(poll_status())
            tasks.append(send_action(i))

        results = await asyncio.gather(*tasks)
        assert len(results) == 30
    finally:
        server_state.response_delay = 0.0


# ==============================================================================
# Challenge 4: Re-Spawning While Already Active
# ==============================================================================

async def test_respawn_while_active_updates_position_and_resets_state(async_client: httpx.AsyncClient):
    """
    Spawns companion, initiates active navigation/following, and re-spawns at a new position
    WITHOUT an intervening despawn.
    Verifies:
    1. Returns HTTP 200 OK with success=true and new position.
    2. Status reflects new position, new name, and resets state from navigating/following to idle.
    3. Old navigation targets are cleanly discarded.
    """
    # 1. Initial spawn at Pos A
    res1 = await async_client.post(
        "/api/companion/spawn",
        json={"name": "JarvisAlpha", "x": 100.5, "y": 64.0, "z": -200.5},
    )
    assert res1.status_code == 200
    st1 = (await async_client.get("/api/companion/status")).json()
    assert st1["name"] == "JarvisAlpha"
    assert st1["position"]["x"] == 100.5

    # 2. Put companion into active navigation towards Pos B
    mv_res = await async_client.post(
        "/api/companion/action",
        json={"action": "move_to", "x": 150.0, "y": 64.0, "z": -150.0, "speed": 1.5},
    )
    assert mv_res.status_code == 200
    st2 = (await async_client.get("/api/companion/status")).json()
    assert st2["state"] == "navigating"
    assert st2["target"] is not None

    # 3. Re-spawn while NAVIGATING at new Pos C with new name
    res2 = await async_client.post(
        "/api/companion/spawn",
        json={"name": "JarvisBeta", "x": 300.0, "y": 72.0, "z": -400.0, "dimension": "minecraft:the_nether"},
    )
    assert res2.status_code == 200
    resp2_data = res2.json()
    assert resp2_data["success"] is True
    assert resp2_data["companion_id"] == "jarvis-1"
    assert resp2_data["position"]["x"] == 300.0
    assert resp2_data["position"]["y"] == 72.0
    assert resp2_data["position"]["z"] == -400.0

    # 4. Status must reflect reset to idle, target cleared, and new coordinates
    st3 = (await async_client.get("/api/companion/status")).json()
    assert st3["spawned"] is True
    assert st3["active"] is True
    assert st3["name"] == "JarvisBeta"
    assert st3["dimension"] == "minecraft:the_nether"
    assert st3["position"]["x"] == 300.0
    assert st3["position"]["y"] == 72.0
    assert st3["position"]["z"] == -400.0
    assert st3["state"] == "idle", "Active respawn must reset navigating state to idle"
    assert st3["target"] is None, "Active respawn must clear previous navigation target"
    assert st3["health"] == 20.0

    # 5. Put companion into FOLLOWING state
    fol_res = await async_client.post(
        "/api/companion/action",
        json={"action": "follow", "player": "Alex", "distance": 2.0},
    )
    assert fol_res.status_code == 200
    st4 = (await async_client.get("/api/companion/status")).json()
    assert st4["state"] == "following"
    assert st4["target"]["player"] == "Alex"

    # 6. Re-spawn while FOLLOWING at Pos D
    res3 = await async_client.post(
        "/api/companion/spawn",
        json={"name": "JarvisGamma", "x": 50.0, "y": 60.0, "z": -50.0},
    )
    assert res3.status_code == 200

    # 7. Status must reflect reset to idle and following target cleared
    st5 = (await async_client.get("/api/companion/status")).json()
    assert st5["name"] == "JarvisGamma"
    assert st5["position"]["x"] == 50.0
    assert st5["state"] == "idle", "Active respawn must reset following state to idle"
    assert st5["target"] is None, "Active respawn must clear following target"


async def test_rapid_active_respawn_stress_loop(async_client: httpx.AsyncClient):
    """
    Performs 15 rapid consecutive active respawns without despawning,
    alternating positions, names, and dimensions.
    Verifies zero memory corruption, stable response times, and consistent state.
    """
    for i in range(15):
        nx = 100.0 + i * 10
        ny = 64.0 + (i % 3)
        nz = -200.0 - i * 10
        name = f"RespawnBot_{i}"

        # If even cycle, engage movement first
        if i % 2 == 1:
            await async_client.post(
                "/api/companion/action",
                json={"action": "move_to", "x": nx + 1, "y": ny, "z": nz + 1},
            )

        resp = await async_client.post(
            "/api/companion/spawn",
            json={"name": name, "x": nx, "y": ny, "z": nz},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert data["position"]["x"] == nx

        st = (await async_client.get("/api/companion/status")).json()
        assert st["spawned"] is True
        assert st["name"] == name
        assert st["state"] == "idle"
        assert st["position"]["x"] == nx
        assert st["position"]["y"] == ny
        assert st["position"]["z"] == nz


async def test_concurrent_active_respawns(async_client: httpx.AsyncClient):
    """
    Fires 10 concurrent POST /api/companion/spawn requests while companion is already active.
    Verifies that all requests return 200 OK and that final companion state matches one of
    the valid candidate payloads without corrupted coordinates or split state.
    """
    # Initial spawn
    await async_client.post("/api/companion/spawn", json={"name": "Initial", "x": 100, "y": 64, "z": -200})

    candidates = [
        {"name": f"ConcurrentSpawn_{i}", "x": 200.0 + i, "y": 64.0, "z": -300.0 - i}
        for i in range(10)
    ]

    async def respawn(cand: dict):
        res = await async_client.post("/api/companion/spawn", json=cand)
        assert res.status_code == 200
        return res.json()

    tasks = [respawn(c) for c in candidates]
    results = await asyncio.gather(*tasks)
    assert len(results) == 10

    # Final status must be valid and consistent
    final_status = (await async_client.get("/api/companion/status")).json()
    assert final_status["spawned"] is True
    assert final_status["active"] is True
    assert final_status["state"] == "idle"

    # Position must match one of the candidate positions
    candidate_positions = {(c["x"], c["y"], c["z"]) for c in candidates}
    actual_pos = (
        final_status["position"]["x"],
        final_status["position"]["y"],
        final_status["position"]["z"],
    )
    assert actual_pos in candidate_positions, f"Actual position {actual_pos} not in candidates"
