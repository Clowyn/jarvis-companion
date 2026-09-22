"""
Pytest configuration and shared fixtures for Jarvis Minecraft Companion E2E test suites.
Provides mock server lifecycle management, base URLs, state resetting, and async HTTP clients.
"""

from __future__ import annotations

import asyncio
import socket
from typing import AsyncGenerator, Generator

import httpx
import pytest
import pytest_asyncio

from tests.mock_server import MockMinecraftServer, MockMinecraftState


def get_free_port() -> int:
    """Find an available ephemeral port on localhost."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        s.listen(1)
        port = s.getsockname()[1]
    return port


@pytest.fixture(scope="session")
def session_mock_server() -> Generator[MockMinecraftServer, None, None]:
    """Session-scoped MockMinecraftServer running in background thread."""
    server = MockMinecraftServer(host="127.0.0.1", port=0)
    server.start()
    yield server
    server.stop()


@pytest.fixture(autouse=True)
def auto_reset_state(session_mock_server: MockMinecraftServer) -> Generator[None, None, None]:
    """Ensure clean state before and after every single test."""
    session_mock_server.state.reset()
    yield
    session_mock_server.state.reset()


@pytest.fixture
def mock_server(session_mock_server: MockMinecraftServer) -> MockMinecraftServer:
    """Convenience fixture for the running session mock server."""
    return session_mock_server


@pytest.fixture
def server_state(session_mock_server: MockMinecraftServer) -> MockMinecraftState:
    """Direct reference to the mock server's mutable state."""
    return session_mock_server.state


@pytest.fixture
def server_url(session_mock_server: MockMinecraftServer) -> str:
    """Base URL of the mock HTTP server (e.g. http://127.0.0.1:59123)."""
    return session_mock_server.base_url


@pytest_asyncio.fixture
async def async_client(server_url: str) -> AsyncGenerator[httpx.AsyncClient, None]:
    """Async HTTP client pre-configured with base URL and reasonable timeout."""
    async with httpx.AsyncClient(base_url=server_url, timeout=10.0) as client:
        yield client



@pytest.fixture
def sync_client(server_url: str) -> Generator[httpx.Client, None, None]:
    """Synchronous HTTP client for sync test cases."""
    with httpx.Client(base_url=server_url, timeout=10.0) as client:
        yield client


@pytest.fixture
def dead_server_url() -> str:
    """URL of an unopened / dead port for offline network tests."""
    port = get_free_port()
    return f"http://127.0.0.1:{port}"


@pytest.fixture
def fresh_server() -> Generator[MockMinecraftServer, None, None]:
    """Creates an isolated, non-session server instance for restart / isolated tests."""
    server = MockMinecraftServer(host="127.0.0.1", port=0)
    server.start()
    yield server
    server.stop()
