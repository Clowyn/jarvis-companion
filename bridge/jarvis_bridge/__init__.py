"""
Jarvis Minecraft Companion Bridge Package.
"""

from .client import JarvisClient, JarvisClientError
from .models import (
    BlockInfo,
    ChatEntry,
    ChatResponse,
    CommandRequest,
    CommandResponse,
    CompanionActionRequest,
    CompanionActionResponse,
    CompanionDespawnResponse,
    CompanionSpawnRequest,
    CompanionSpawnResponse,
    CompanionStatusResponse,
    EntityInfo,
    PlayerStatus,
    Position,
    SayRequest,
    SayResponse,
    ServerStatusResponse,
    SurroundingsResponse,
)
def __getattr__(name: str):
    if name == "mcp":
        from .mcp_server import mcp
        return mcp
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

__all__ = [
    "JarvisClient",
    "JarvisClientError",
    "mcp",
    "Position",
    "PlayerStatus",
    "ServerStatusResponse",
    "SayRequest",
    "SayResponse",
    "ChatEntry",
    "ChatResponse",
    "CommandRequest",
    "CommandResponse",
    "BlockInfo",
    "EntityInfo",
    "SurroundingsResponse",
    "CompanionSpawnRequest",
    "CompanionSpawnResponse",
    "CompanionStatusResponse",
    "CompanionActionRequest",
    "CompanionActionResponse",
    "CompanionDespawnResponse",
]
