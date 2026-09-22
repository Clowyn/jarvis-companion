"""
Comprehensive unit and integration tests for JarvisClient async Python client.
"""

from __future__ import annotations

import sys
import pytest
import pytest_asyncio
import httpx

# Ensure bridge directory is on path
sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient, JarvisClientError
from jarvis_bridge.models import (
    CompanionActionResponse,
    CompanionDespawnResponse,
    CompanionSpawnResponse,
    CompanionStatusResponse,
    ServerStatusResponse,
    SurroundingsResponse,
)


@pytest_asyncio.fixture
async def bridge_client(server_url: str):
    """Fixture providing an initialized JarvisClient pointing to the mock server."""
    client = JarvisClient(base_url=server_url, timeout=5.0)
    yield client
    await client.close()


@pytest.mark.asyncio
async def test_client_get_status(bridge_client: JarvisClient):
    status = await bridge_client.get_status()
    assert isinstance(status, ServerStatusResponse)
    assert status.status == "online"
    assert status.version == "1.21.1"
    assert len(status.players) >= 1
    assert status.players[0].name == "Steve"


@pytest.mark.asyncio
async def test_client_say(bridge_client: JarvisClient):
    res = await bridge_client.say("Hello Minecraft world!", sender="Jarvis")
    assert res.success is True


@pytest.mark.asyncio
async def test_client_get_chat(bridge_client: JarvisClient):
    await bridge_client.say("Test message for chat buffer", sender="Tester")
    chat = await bridge_client.get_chat(limit=10)
    assert isinstance(chat, list)
    assert any("Test message" in entry.message for entry in chat)


@pytest.mark.asyncio
async def test_client_execute_command(bridge_client: JarvisClient):
    res = await bridge_client.execute_command("time set day")
    assert res.success is True
    assert res.command == "time set day"


@pytest.mark.asyncio
async def test_client_get_surroundings(bridge_client: JarvisClient):
    surroundings = await bridge_client.get_surroundings(player="Steve", radius=16)
    assert isinstance(surroundings, SurroundingsResponse)
    assert surroundings.player == "Steve"
    assert isinstance(surroundings.blocks, list)
    assert isinstance(surroundings.entities, list)


@pytest.mark.asyncio
async def test_client_companion_full_lifecycle(bridge_client: JarvisClient):
    # 1. Status before spawn
    status_before = await bridge_client.companion_status()
    assert status_before.spawned is False

    # 2. Spawn companion
    spawn_res = await bridge_client.companion_spawn(name="Jarvis", x=100.5, y=64.0, z=-200.5)
    assert isinstance(spawn_res, CompanionSpawnResponse)
    assert spawn_res.success is True
    assert spawn_res.companion_id == "jarvis-1"

    # 3. Status after spawn
    status_after = await bridge_client.companion_status()
    assert status_after.spawned is True
    assert status_after.health > 0

    # 4. Move to
    move_res = await bridge_client.companion_move(x=110.0, y=64.0, z=-190.0, speed=1.2)
    assert move_res.success is True
    assert move_res.action == "move_to"

    # 5. Follow player
    follow_res = await bridge_client.companion_follow(player="Steve", distance=4.0)
    assert follow_res.success is True
    assert follow_res.action == "follow"

    # 6. Stop
    stop_res = await bridge_client.companion_stop()
    assert stop_res.success is True
    assert stop_res.action == "stop"

    # 7. Interact / Break block
    break_res = await bridge_client.companion_interact("break_block", x=102, y=60, z=-198)
    assert break_res.success is True
    assert break_res.action == "break_block"

    # 8. Interact / Place block
    place_res = await bridge_client.companion_interact("place_block", x=102, y=61, z=-198, item="minecraft:torch")
    assert place_res.success is True

    # 9. Attack mob
    attack_res = await bridge_client.companion_attack(entity_id=42)
    assert attack_res.success is True

    # 10. Despawn
    despawn_res = await bridge_client.companion_despawn()
    assert isinstance(despawn_res, CompanionDespawnResponse)
    assert despawn_res.success is True

    # 11. Status after despawn
    final_status = await bridge_client.companion_status()
    assert final_status.spawned is False


@pytest.mark.asyncio
async def test_client_error_handling(bridge_client: JarvisClient):
    # Companion is not spawned -> action should raise JarvisClientError with status 400
    with pytest.raises(JarvisClientError) as exc_info:
        await bridge_client.companion_move(x=10, y=20, z=30)
    assert exc_info.value.status_code == 400


@pytest.mark.asyncio
async def test_client_context_manager(server_url: str):
    async with JarvisClient(base_url=server_url) as client:
        status = await client.get_status()
        assert status.status == "online"
