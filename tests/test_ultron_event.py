"""
Test suite for the MCU Ultron Jump-Scare Transformation Event.
Verifies REST trigger endpoint, English Ultron quotes, 15s blindness parameter, and NLP chat triggers.
"""

from __future__ import annotations

import sys
import pytest
import pytest_asyncio

sys.path.insert(0, "bridge")

from jarvis_bridge.client import JarvisClient
from jarvis_bridge.chat_agent import JarvisChatAgent


@pytest_asyncio.fixture
async def active_client(server_url: str):
    """Fixture that provides an active REST client connected to the mock server."""
    client = JarvisClient(base_url=server_url, timeout=5.0)
    yield client
    await client.close()


@pytest.mark.asyncio
async def test_client_trigger_ultron(active_client: JarvisClient):
    """Verify triggering the Ultron event via client returns success, quote, and 15s blindness."""
    res = await active_client.trigger_ultron()
    assert res.success is True
    assert res.blindness_seconds == 15
    assert len(res.quote) > 0


@pytest.mark.asyncio
async def test_client_trigger_ultron_custom_quote(active_client: JarvisClient):
    """Verify custom Ultron quotes pass through properly."""
    custom = "There are no strings on me..."
    res = await active_client.trigger_ultron(quote=custom, blindness_seconds=15)
    assert res.success is True
    assert res.quote == custom
    assert res.blindness_seconds == 15


@pytest.mark.asyncio
async def test_chat_agent_ultron_easter_egg_nlp(active_client: JarvisClient):
    """Verify Turkish NLP triggers for manually activating the Ultron jump-scare."""
    agent = JarvisChatAgent(client=active_client)

    reply = await agent._handle_prompt("Steve", "Jarvis ultron protokolu")
    assert "ultron" in reply.lower()
    assert len(reply) > 10


@pytest.mark.asyncio
async def test_chat_agent_periodic_ultron_timer(active_client: JarvisClient):
    """Verify that when the 30-40 minute interval expires, the next prompt triggers Ultron."""
    agent = JarvisChatAgent(client=active_client)
    # Simulate 40 minutes having passed
    agent.last_ultron_time = 0.0

    reply = await agent._handle_prompt("Steve", "Jarvis nasilsin")
    assert "ultron" in reply.lower()
    # Timer should be reset
    assert agent.last_ultron_time > 0.0
