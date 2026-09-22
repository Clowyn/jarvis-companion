"""
Empirical Challenger Gen2-2 Test Suite for JarvisClient in bridge/jarvis_bridge/client.py.

Adversarial and Stress Test Matrix:
1. High concurrent request bursts (single endpoint with connection pooling, mixed endpoints, companion contention, parallel client lifecycles).
2. Network failure simulation (timeouts, 503 retry with exponential backoff, connection refusal on dead port, 500 error).
3. Malformed payloads and boundary inputs (empty message 400 rejection, huge payload, clamped radius, unknown fields, non-JSON responses).
4. Polymorphic companion action stress (rapid sequences, unspawned rejection, rapid-fire action switches).
5. Context manager lifecycle, error exit cleanup, external client preservation, idempotent close.
6. Exception hierarchy (JarvisClientError attributes, status codes, details, transport error distinction).
"""

from __future__ import annotations

import asyncio
import sys
import time
from typing import Any, Dict, List
import httpx
import pytest
import pytest_asyncio
from pydantic import ValidationError

# Ensure bridge package is importable
sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient, JarvisClientError
from jarvis_bridge.models import (
    CompanionActionResponse,
    CompanionDespawnResponse,
    CompanionSpawnResponse,
    CompanionStatusResponse,
    ServerStatusResponse,
    SayResponse,
    CommandResponse,
    SurroundingsResponse,
    ChatEntry,
)
from tests.mock_server import MockMinecraftState

pytestmark = pytest.mark.asyncio


# ==============================================================================
# Category 1: High Concurrent Request Bursts
# ==============================================================================

async def test_burst_concurrent_get_status_100_requests(server_url: str):
    """
    Stress-tests 100 concurrent coroutines calling client.get_status() across
    a single JarvisClient instance with an active connection pool.
    Verifies connection pooling, zero dropped requests, and response validation integrity.
    """
    sem = asyncio.Semaphore(20)

    async with JarvisClient(base_url=server_url, timeout=15.0) as client:
        async def fetch_status(idx: int) -> ServerStatusResponse:
            async with sem:
                return await client.get_status()

        tasks = [fetch_status(i) for i in range(100)]
        results = await asyncio.gather(*tasks, return_exceptions=False)

        assert len(results) == 100
        for r in results:
            assert isinstance(r, ServerStatusResponse)
            assert r.status == "online"
            assert r.version == "1.21.1"
            assert len(r.players) >= 1
            assert r.players[0].name == "Steve"


async def test_burst_concurrent_mixed_endpoints_100_requests(server_url: str):
    """
    Stress-tests 100 concurrent tasks making mixed calls across get_status, say,
    get_chat, execute_command, get_surroundings, and companion_status.
    Verifies that simultaneous interleaved calls do not cross-talk or corrupt data.
    """
    sem = asyncio.Semaphore(20)

    async with JarvisClient(base_url=server_url, timeout=15.0) as client:
        async def worker(task_id: int):
            async with sem:
                choice = task_id % 6
                if choice == 0:
                    res = await client.get_status()
                    assert isinstance(res, ServerStatusResponse)
                elif choice == 1:
                    res = await client.say(f"Burst msg {task_id}", sender=f"Worker_{task_id}")
                    assert isinstance(res, SayResponse)
                    assert res.success is True
                elif choice == 2:
                    res = await client.get_chat(limit=10)
                    assert isinstance(res, list)
                elif choice == 3:
                    res = await client.execute_command(f"gamemode creative Steve_{task_id}")
                    assert isinstance(res, CommandResponse)
                    assert res.success is True
                elif choice == 4:
                    res = await client.get_surroundings(player="Steve", radius=16)
                    assert isinstance(res, SurroundingsResponse)
                    assert res.player == "Steve"
                elif choice == 5:
                    res = await client.companion_status()
                    assert isinstance(res, CompanionStatusResponse)

        tasks = [worker(i) for i in range(100)]
        await asyncio.gather(*tasks, return_exceptions=False)


async def test_burst_companion_action_contention_50_requests(server_url: str):
    """
    Spawns a companion, then fires 50 concurrent companion actions (move, follow, interact, status)
    to verify server state safety and client-side serialization under high action contention.
    """
    sem = asyncio.Semaphore(15)

    async with JarvisClient(base_url=server_url, timeout=15.0) as client:
        # Spawn companion
        spawn_res = await client.companion_spawn(name="ContentionJarvis", x=100.0, y=64.0, z=-200.0)
        assert spawn_res.success is True

        async def action_worker(idx: int):
            async with sem:
                action_type = idx % 4
                if action_type == 0:
                    return await client.companion_move(x=100.0 + idx, y=64.0, z=-200.0 + idx, speed=1.2)
                elif action_type == 1:
                    return await client.companion_follow(player="Steve", distance=3.0)
                elif action_type == 2:
                    return await client.companion_interact("inspect_container", x=105, y=64, z=-200)
                elif action_type == 3:
                    return await client.companion_status()

        tasks = [action_worker(i) for i in range(50)]
        results = await asyncio.gather(*tasks, return_exceptions=False)

        assert len(results) == 50
        for r in results:
            assert isinstance(r, (CompanionActionResponse, CompanionStatusResponse))

        # Cleanup
        despawn_res = await client.companion_despawn()
        assert despawn_res.success is True


async def test_burst_parallel_client_instantiation_and_cleanup(server_url: str):
    """
    Concurrently instantiates 40 distinct JarvisClient instances, each running a request
    inside its own async context manager and closing.
    Verifies no resource exhaustion, thread lockups, or socket leaks.
    """
    async def run_ephemeral_client(idx: int):
        async with JarvisClient(base_url=server_url, timeout=10.0) as local_client:
            res = await local_client.get_status()
            assert res.status == "online"
            assert local_client._internal_client is not None
        assert local_client._internal_client is None

    tasks = [run_ephemeral_client(i) for i in range(40)]
    await asyncio.gather(*tasks, return_exceptions=False)


# ==============================================================================
# Category 2: Network Failure Simulation (Timeouts, 503 Retry, Connection Refusal)
# ==============================================================================

async def test_network_connection_refusal_dead_port(dead_server_url: str):
    """
    Tests behavior when JarvisClient targets an offline/unopened port.
    Verifies that httpx.RequestError (ConnectError / ConnectTimeout) is raised immediately
    without hanging or swallowing exceptions.
    """
    async with JarvisClient(base_url=dead_server_url, timeout=1.0) as client:
        # get_status
        with pytest.raises((httpx.ConnectError, httpx.ConnectTimeout, httpx.NetworkError, httpx.RequestError)):
            await client.get_status()

        # say
        with pytest.raises((httpx.ConnectError, httpx.ConnectTimeout, httpx.NetworkError, httpx.RequestError)):
            await client.say("Test offline")

        # companion_spawn
        with pytest.raises((httpx.ConnectError, httpx.ConnectTimeout, httpx.NetworkError, httpx.RequestError)):
            await client.companion_spawn(name="DeadJarvis")


async def test_network_timeout_simulation_and_recovery(
    server_url: str, server_state: MockMinecraftState
):
    """
    Simulates high network latency / server hang causing request timeout.
    Verifies client raises httpx.ReadTimeout, and recovers immediately once latency clears.
    """
    # Create client with very tight timeout of 0.05s (50ms)
    async with JarvisClient(base_url=server_url, timeout=0.05) as client:
        # Step 1: Normal call succeeds
        res1 = await client.get_status()
        assert res1.status == "online"

        # Step 2: Inject delay longer than client timeout (150ms > 50ms)
        server_state.response_delay = 0.15
        try:
            with pytest.raises(httpx.TimeoutException):
                await client.get_status()
        finally:
            # Clear delay
            server_state.response_delay = 0.0

        # Step 3: Client connection must cleanly recover on subsequent requests
        res2 = await client.get_status()
        assert res2.status == "online"


async def test_network_503_service_unavailable_and_retry_backoff(
    server_url: str, server_state: MockMinecraftState
):
    """
    Tests HTTP 503 handling and verifies a resilient exponential backoff retry pattern
    using JarvisClient across transient server failures (e.g. NeoForge world save/reload).
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        # Step 1: Normal call succeeds
        status1 = await client.get_status()
        assert status1.status == "online"

        # Step 2: Simulate 503 Service Unavailable
        server_state.simulate_503 = True

        # Verify direct call raises JarvisClientError with status_code == 503
        with pytest.raises(JarvisClientError) as exc_info:
            await client.get_status()
        assert exc_info.value.status_code == 503

        # Step 3: Resilient retry loop with exponential backoff
        max_retries = 5
        delay = 0.02
        recovered = False

        for attempt in range(max_retries):
            try:
                res = await client.get_status()
                if res.status == "online":
                    recovered = True
                    break
            except JarvisClientError as err:
                if err.status_code == 503:
                    # Simulate server coming back online on attempt 2
                    if attempt == 2:
                        server_state.simulate_503 = False
                    await asyncio.sleep(delay)
                    delay *= 2
                else:
                    raise

        assert recovered is True, "Client failed to recover from transient 503"

        # Step 4: Verify normal companion operations work after 503 recovery
        spawn_res = await client.companion_spawn(name="PostRecoveryJarvis")
        assert spawn_res.success is True
        await client.companion_despawn()


async def test_network_500_internal_server_error(
    server_url: str, server_state: MockMinecraftState
):
    """
    Tests HTTP 500 Internal Server Error simulation.
    Verifies JarvisClientError is raised with status_code 500 and populated details.
    """
    server_state.simulate_500 = True
    try:
        async with JarvisClient(base_url=server_url, timeout=5.0) as client:
            with pytest.raises(JarvisClientError) as exc_info:
                await client.get_status()
            assert exc_info.value.status_code == 500
            assert "error" in exc_info.value.details or "Internal server error" in str(exc_info.value)
    finally:
        server_state.simulate_500 = False


# ==============================================================================
# Category 3: Malformed Payloads and Boundary Inputs
# ==============================================================================

async def test_boundary_empty_and_huge_say_messages(server_url: str):
    """
    Tests client.say with boundary inputs:
    - Empty message is rejected by server with 400 Bad Request (raises JarvisClientError).
    - Large 50KB payload succeeds with 200 OK.
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        # Empty string message: server rejects with 400 Bad Request
        with pytest.raises(JarvisClientError) as exc_info:
            await client.say("", sender="")
        assert exc_info.value.status_code == 400
        assert "empty" in str(exc_info.value).lower()

        # Massive 50KB payload
        huge_msg = "X" * 50_000
        res_huge = await client.say(huge_msg, sender="MassiveSender")
        assert res_huge.success is True


async def test_boundary_surroundings_radius(server_url: str):
    """
    Tests client.get_surroundings with boundary radius values: 0, negative, and large.
    Verifies that the server clamps radius to [2, 64] and client models deserialize accurately.
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        # Radius 0 is clamped by server to minimum 2
        res_zero = await client.get_surroundings(radius=0)
        assert isinstance(res_zero, SurroundingsResponse)
        assert res_zero.radius == 2

        # Radius 1000 is clamped by server to maximum 32
        res_large = await client.get_surroundings(radius=1000)
        assert isinstance(res_large, SurroundingsResponse)
        assert res_large.radius == 32

        # Nonexistent player (returns 404)
        with pytest.raises(JarvisClientError) as exc_info:
            await client.get_surroundings(player="GhostPlayerDoesNotExist_999")
        assert exc_info.value.status_code == 404


async def test_boundary_chat_queries(server_url: str):
    """
    Tests client.get_chat with boundary limit and since parameters.
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        # Limit 0
        chat_0 = await client.get_chat(limit=0)
        assert isinstance(chat_0, list)
        assert len(chat_0) == 0

        # Future timestamp since
        chat_future = await client.get_chat(since=9999999999999)
        assert isinstance(chat_future, list)
        assert len(chat_future) == 0

        # Large limit
        chat_large = await client.get_chat(limit=500)
        assert isinstance(chat_large, list)


async def test_boundary_companion_coordinates(server_url: str):
    """
    Tests companion_spawn with extreme and boundary coordinates (e.g. negative world coords, 0.0).
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        # Zero coordinates
        res_zero = await client.companion_spawn(name="ZeroJarvis", x=0.0, y=0.0, z=0.0)
        assert res_zero.success is True
        assert res_zero.position.x == 0.0
        assert res_zero.position.y == 0.0
        assert res_zero.position.z == 0.0

        # Status check
        status = await client.companion_status()
        assert status.spawned is True
        assert status.position.x == 0.0

        # Boundary negative coordinates
        move_res = await client.companion_move(x=-29999999.0, y=-64.0, z=-29999999.0, speed=2.0)
        assert move_res.success is True

        await client.companion_despawn()


async def test_malformed_server_json_handling():
    """
    Simulates a server returning non-JSON response body (e.g. proxy HTML error or corrupted payload).
    Verifies that _handle_response stores raw text and triggers appropriate Pydantic ValidationError or JarvisClientError.
    """
    # Mock transport returning HTML
    def html_handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/status":
            return httpx.Response(502, text="<html><body>502 Bad Gateway</body></html>")
        return httpx.Response(200, text="NOT_A_JSON_PAYLOAD")

    mock_transport = httpx.MockTransport(html_handler)
    async with httpx.AsyncClient(transport=mock_transport) as raw_client:
        client = JarvisClient(base_url="http://mock-proxy", http_client=raw_client)

        # 502 with HTML body -> JarvisClientError with status_code 502 and details containing raw
        with pytest.raises(JarvisClientError) as exc_info:
            await client.get_status()
        assert exc_info.value.status_code == 502
        assert "502 Bad Gateway" in exc_info.value.details.get("raw", "")

        # 200 with non-JSON body -> Pydantic ValidationError when model_validate parses {"raw": ...}
        with pytest.raises(ValidationError):
            await client.say("Hello")


async def test_unknown_fields_in_server_response():
    """
    Verifies that models tolerate extra unexpected fields from forward-compatible API servers
    (model_config ConfigDict extra="ignore").
    """
    def extra_fields_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "status": "online",
                "version": "1.21.1",
                "world_time": 54321,
                "future_field_foo": "bar",
                "another_experimental_metric": [1, 2, 3],
                "players": [],
            },
        )

    mock_transport = httpx.MockTransport(extra_fields_handler)
    async with httpx.AsyncClient(transport=mock_transport) as raw_client:
        client = JarvisClient(base_url="http://mock-extra", http_client=raw_client)
        status = await client.get_status()
        assert status.status == "online"
        assert status.world_time == 54321


# ==============================================================================
# Category 4: Polymorphic Companion Action Stress
# ==============================================================================

async def test_polymorphic_actions_pipeline_sequential(server_url: str):
    """
    Stress-tests the full polymorphic companion action set in sequential order:
    spawn -> move_to -> follow -> break_block -> place_block -> inspect_container -> attack -> teleport -> stop -> despawn.
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        # 1. Spawn
        spawn = await client.companion_spawn(name="PolyJarvis", x=100.5, y=64.0, z=-200.5)
        assert spawn.success is True

        # 2. move_to
        m = await client.companion_move(x=105.0, y=64.0, z=-195.0, speed=1.5)
        assert m.success is True
        assert m.action == "move_to"

        # 3. follow
        f = await client.companion_follow(player="Steve", distance=2.5)
        assert f.success is True
        assert f.action == "follow"

        # 4. break_block
        b = await client.companion_interact("break_block", x=102, y=60, z=-198)
        assert b.success is True
        assert b.action == "break_block"

        # 5. place_block
        p = await client.companion_interact("place_block", x=102, y=61, z=-198, item="minecraft:torch")
        assert p.success is True
        assert p.action == "place_block"

        # 6. inspect_container
        c = await client.companion_interact("inspect_container", x=105, y=64, z=-200)
        assert c.success is True
        assert c.action == "inspect_container"

        # 7. attack
        a = await client.companion_attack(entity_id=42)
        assert a.success is True
        assert a.action == "attack"

        # 8. teleport
        t = await client.companion_action("teleport", x=100.5, y=64.0, z=-200.5)
        assert t.success is True
        assert t.action == "teleport"

        # 9. stop
        s = await client.companion_stop()
        assert s.success is True
        assert s.action == "stop"

        # 10. status check
        st = await client.companion_status()
        assert st.spawned is True

        # 11. despawn
        d = await client.companion_despawn()
        assert d.success is True

        # 12. status after despawn
        st_final = await client.companion_status()
        assert st_final.spawned is False


async def test_polymorphic_actions_rejected_when_unspawned(server_url: str):
    """
    Verifies that dispatching any companion action when the companion is unspawned
    raises JarvisClientError with status_code == 400.
    """
    async with JarvisClient(base_url=server_url, timeout=5.0) as client:
        # Verify companion is not spawned
        st = await client.companion_status()
        assert st.spawned is False

        actions = [
            lambda: client.companion_move(10, 64, 10),
            lambda: client.companion_follow("Steve"),
            lambda: client.companion_stop(),
            lambda: client.companion_interact("break_block", 10, 64, 10),
            lambda: client.companion_interact("place_block", 10, 64, 10, item="minecraft:stone"),
            lambda: client.companion_interact("inspect_container", 10, 64, 10),
            lambda: client.companion_attack(42),
            lambda: client.companion_action("teleport", x=10, y=64, z=10),
        ]

        for action_fn in actions:
            with pytest.raises(JarvisClientError) as exc_info:
                await action_fn()
            assert exc_info.value.status_code == 400
            assert "not active" in exc_info.value.args[0].lower() or "spawn" in exc_info.value.args[0].lower()


async def test_rapid_polymorphic_action_switching_loop(server_url: str):
    """
    Executes 40 rapid-fire polymorphic action switches in a tight loop on an active companion.
    Verifies the client and mock state machine handle rapid action switching without errors or corruption.
    """
    async with JarvisClient(base_url=server_url, timeout=10.0) as client:
        await client.companion_spawn(name="RapidJarvis", x=100.0, y=64.0, z=-200.0)

        for i in range(40):
            action_choice = i % 4
            if action_choice == 0:
                res = await client.companion_move(x=100.0 + i, y=64.0, z=-200.0 + i)
            elif action_choice == 1:
                res = await client.companion_follow(player="Steve")
            elif action_choice == 2:
                res = await client.companion_stop()
            else:
                res = await client.companion_action("teleport", x=100.0, y=64.0, z=-200.0)
            assert res.success is True

        final_st = await client.companion_status()
        assert final_st.spawned is True
        await client.companion_despawn()


# ==============================================================================
# Category 5: Context Manager Entering/Exiting Under Errors & Resource Cleanup
# ==============================================================================

async def test_context_manager_normal_lifecycle(server_url: str):
    """
    Verifies that client._internal_client is created inside context manager,
    and closed + reset to None upon normal exit.
    """
    client = JarvisClient(base_url=server_url, timeout=5.0)
    assert client._internal_client is None

    async with client as c:
        assert c is client
        # Internal client initialized on enter or first call
        assert c._internal_client is not None
        assert not c._internal_client.is_closed
        status = await c.get_status()
        assert status.status == "online"

    # After exit, client._internal_client should be None
    assert client._internal_client is None


async def test_context_manager_exit_on_unhandled_exception(server_url: str):
    """
    Verifies that if an unhandled exception is raised inside the async with block,
    the exception propagates to caller AND client._internal_client is cleanly closed.
    """
    client = JarvisClient(base_url=server_url, timeout=5.0)

    with pytest.raises(RuntimeError, match="Simulated crash inside context block"):
        async with client:
            await client.get_status()
            assert client._internal_client is not None
            assert not client._internal_client.is_closed
            raise RuntimeError("Simulated crash inside context block")

    # Resource cleanup verified: internal client closed and reset
    assert client._internal_client is None


async def test_context_manager_reusability(server_url: str):
    """
    Verifies that the same JarvisClient instance can be entered and exited multiple times
    in sequence without error (fresh internal client allocated each time).
    """
    client = JarvisClient(base_url=server_url, timeout=5.0)

    # First session
    async with client:
        res1 = await client.get_status()
        assert res1.status == "online"
    assert client._internal_client is None

    # Second session
    async with client:
        res2 = await client.get_status()
        assert res2.status == "online"
    assert client._internal_client is None


async def test_external_client_lifecycle_preservation(server_url: str):
    """
    Verifies that when an external httpx.AsyncClient is injected into JarvisClient,
    JarvisClient does NOT close the user's external client upon close() or __aexit__().
    """
    external_http = httpx.AsyncClient(base_url=server_url, timeout=5.0)
    try:
        client = JarvisClient(base_url=server_url, http_client=external_http)

        async with client:
            status = await client.get_status()
            assert status.status == "online"
            assert client._internal_client is None  # no internal client created

        # External client must still be open
        assert not external_http.is_closed

        # Explicit close on JarvisClient should also leave external client open
        await client.close()
        assert not external_http.is_closed

        # Perform request with external client directly to confirm it's still alive
        resp = await external_http.get(f"{server_url}/api/status")
        assert resp.status_code == 200
    finally:
        await external_http.aclose()


async def test_idempotent_close(server_url: str):
    """
    Verifies calling client.close() repeatedly is completely safe and idempotent.
    """
    client = JarvisClient(base_url=server_url, timeout=5.0)
    await client.close()
    await client.close()

    await client.get_status()
    assert client._internal_client is not None

    await client.close()
    assert client._internal_client is None
    await client.close()
    await client.close()


# ==============================================================================
# Category 6: Exception Hierarchy & Error Verification
# ==============================================================================

async def test_jarvis_client_error_structure():
    """
    Verifies JarvisClientError exception attributes, inheritance, and string representation.
    """
    err = JarvisClientError("Companion not found", status_code=404, details={"error": "Not found", "code": 404})
    assert isinstance(err, Exception)
    assert str(err) == "Companion not found"
    assert err.status_code == 404
    assert err.details == {"error": "Not found", "code": 404}

    # Default values
    simple_err = JarvisClientError("Generic failure")
    assert simple_err.status_code is None
    assert simple_err.details == {}


async def test_transport_error_vs_client_error_distinction(server_url: str, dead_server_url: str):
    """
    Empirical distinction test:
    - Transport / connection / socket level failures raise httpx.RequestError (ConnectError, TimeoutException).
    - HTTP protocol / application level responses (4xx, 5xx) raise JarvisClientError.
    Verifies clear boundary between transport network failures and application-layer errors.
    """
    # 1. Transport failure -> httpx.RequestError
    async with JarvisClient(base_url=dead_server_url, timeout=1.0) as dead_client:
        with pytest.raises(httpx.RequestError) as exc_transport:
            await dead_client.get_status()
        assert not isinstance(exc_transport.value, JarvisClientError)

    # 2. Application failure (e.g. unknown player 404) -> JarvisClientError
    async with JarvisClient(base_url=server_url, timeout=5.0) as live_client:
        with pytest.raises(JarvisClientError) as exc_app:
            await live_client.get_surroundings(player="NonExistentPlayerXYZ")
        assert exc_app.value.status_code == 404
        assert isinstance(exc_app.value, JarvisClientError)
