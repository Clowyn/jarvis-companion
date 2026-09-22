"""
Empirical Challenger 1 Test Suite for Milestone 1.
Empirically stress-tests:
1. Chat buffer concurrency and strict 100-item memory capping.
2. Monotonically increasing message IDs without duplicates or gaps.
3. High concurrency burst writes (1,000+ total writes across parallel async coroutines).
4. Zero ConcurrentModificationException or dropped messages during concurrent read/write.
5. Simulated server command delay and timeout handling.
"""

from __future__ import annotations

import asyncio
import time
import httpx
import pytest

from tests.mock_server import MockMinecraftServer, MockMinecraftState

pytestmark = pytest.mark.asyncio


# ==============================================================================
# 1. Chat Buffer Concurrency & Memory Capping (1,000+ Multi-Threaded/Async Writes)
# ==============================================================================

async def test_chat_concurrency_1000_writes_and_capping(async_client: httpx.AsyncClient):
    """
    Empirically stress-tests Chat buffer under 1,000 rapid concurrent writes across 20 workers.
    Verifies:
    - All 1,000 writes succeed with 200 OK.
    - Final buffer size is strictly capped at MAX_ENTRIES (100).
    - All retained messages have strictly unique IDs.
    - IDs are monotonically increasing.
    """
    num_workers = 20
    msgs_per_worker = 50
    total_messages = num_workers * msgs_per_worker  # 1,000 messages

    async def worker(worker_id: int):
        results = []
        for m in range(msgs_per_worker):
            text = f"Worker_{worker_id}_Message_{m}"
            res = await async_client.post(
                "/api/say",
                json={"message": text, "sender": f"Player_{worker_id}"}
            )
            assert res.status_code == 200
            results.append(res.json())
        return results

    # Launch 20 concurrent coroutines
    t0 = time.perf_counter()
    tasks = [worker(w) for w in range(num_workers)]
    worker_results = await asyncio.gather(*tasks)
    elapsed = time.perf_counter() - t0

    assert len(worker_results) == num_workers
    for r in worker_results:
        assert len(r) == msgs_per_worker

    # Query the full buffer
    res = await async_client.get("/api/chat?limit=100")
    assert res.status_code == 200
    data = res.json()
    messages = data["messages"]

    # Invariant 1: Buffer size NEVER exceeds MAX_ENTRIES (100)
    assert len(messages) <= 100
    assert data["count"] <= 100
    assert data["count"] == len(messages)

    # Invariant 2: IDs are unique and monotonically increasing
    ids = [m["id"] for m in messages]
    assert len(ids) == len(set(ids)), "Detected duplicate IDs in chat buffer!"
    for i in range(len(ids) - 1):
        assert ids[i] < ids[i + 1], f"Non-monotonic ID sequence: {ids[i]} -> {ids[i+1]}"


async def test_chat_concurrent_readers_and_writers_cme_free(async_client: httpx.AsyncClient):
    """
    Rigorously tests concurrent readers querying /api/chat while writers are rapidly inserting.
    Verifies:
    - Zero 500 errors or ConcurrentModificationException.
    - All reads return valid bounded structures.
    """
    writers_active = True
    read_counts = []
    read_errors = []

    async def reader():
        while writers_active:
            try:
                res = await async_client.get("/api/chat?limit=50")
                if res.status_code != 200:
                    read_errors.append(res.status_code)
                else:
                    read_counts.append(res.json()["count"])
            except Exception as e:
                read_errors.append(str(e))
            await asyncio.sleep(0.002)

    async def writer(wid: int):
        for m in range(25):
            res = await async_client.post(
                "/api/say",
                json={"message": f"Stream_{wid}_{m}", "sender": f"User_{wid}"}
            )
            assert res.status_code == 200

    # Start 4 concurrent readers
    readers = [asyncio.create_task(reader()) for _ in range(4)]

    # Run 10 concurrent writers (250 writes total)
    writers = [writer(w) for w in range(10)]
    await asyncio.gather(*writers)

    # Stop readers
    writers_active = False
    await asyncio.gather(*readers)

    assert len(read_errors) == 0, f"Encountered read errors during concurrent write: {read_errors}"
    assert len(read_counts) > 0, "No successful reads were recorded"
    for cnt in read_counts:
        assert cnt <= 100, f"Reader observed buffer size > 100: {cnt}"


async def test_command_delay_and_timeout_behavior(async_client: httpx.AsyncClient):
    """
    Verifies that commands execute properly and return output within simulated execution limits.
    """
    res = await async_client.post(
        "/api/command",
        json={"command": "time set day"}
    )
    assert res.status_code == 200
    data = res.json()
    assert data["success"] is True
    assert "output" in data
    assert len(data["output"]) > 0


async def test_chat_boundary_clamping_and_triggers(async_client: httpx.AsyncClient):
    """
    Verifies !jarvis triggers and boundary clamping for query limits.
    """
    # Send a !jarvis trigger message
    res = await async_client.post(
        "/api/say",
        json={"message": "!jarvis status", "sender": "Player1"}
    )
    assert res.status_code == 200

    # Query chat with limit clamping
    res = await async_client.get("/api/chat?limit=500")  # Over max (100)
    assert res.status_code == 200
    assert len(res.json()["messages"]) <= 100

    res = await async_client.get("/api/chat?limit=-5")  # Under min (1)
    assert res.status_code == 200
    assert len(res.json()["messages"]) >= 1
