"""
MCP (Model Context Protocol) Server for the Jarvis Minecraft Companion.
Exposes 10 tools allowing AI agents to perceive, communicate, and act within the Minecraft world.
"""

from __future__ import annotations

import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

from typing import Any, Dict, List, Optional

try:
    from mcp.server.mcpserver import MCPServer
    ServerApp = MCPServer
except (ImportError, ModuleNotFoundError):
    from mcp.server.fastmcp import FastMCP
    ServerApp = FastMCP

from .client import JarvisClient

# Default base URL pointing to the embedded NeoForge HTTP server
DEFAULT_API_URL = os.environ.get("JARVIS_API_URL", "http://localhost:25585")

mcp = ServerApp("jarvis-minecraft")

# Global client reference that can be overridden by tests or custom configuration
_client_instance: Optional[JarvisClient] = None


def get_client() -> JarvisClient:
    global _client_instance
    url = None

    # Check bridge/.last_url and root/.last_url
    bridge_dir = os.path.dirname(os.path.dirname(__file__))
    candidates = [
        os.path.join(bridge_dir, ".last_url"),
        os.path.join(os.path.dirname(bridge_dir), ".last_url")
    ]
    for cand in candidates:
        if os.path.exists(cand):
            try:
                with open(cand, "r", encoding="utf-8") as f:
                    saved = f.read().strip()
                    if saved and saved.startswith("http"):
                        url = saved
                        break
            except Exception:
                pass

    if not url:
        url = os.environ.get("JARVIS_API_URL")
    if not url:
        url = DEFAULT_API_URL

    url = url.rstrip("/")
    if _client_instance is None or _client_instance.base_url != url:
        _client_instance = JarvisClient(base_url=url)

    return _client_instance



def set_client(client: Optional[JarvisClient]) -> None:
    """Override the client instance (useful for test fixtures pointing to mock servers)."""
    global _client_instance
    _client_instance = client


# =============================================================================
# 10 MCP Tool Definitions
# =============================================================================

@mcp.tool()
async def minecraft_get_status() -> Dict[str, Any]:
    """
    Get current server health, in-game world time, and player telemetry (coordinates, health, food level).
    """
    client = get_client()
    res = await client.get_status()
    return res.model_dump()


@mcp.tool()
async def minecraft_say(message: str, sender: str = "Jarvis") -> Dict[str, Any]:
    """
    Broadcast a formatted and colored message to in-game chat for all players to see.
    """
    client = get_client()
    res = await client.say(message=message, sender=sender)
    return res.model_dump()


@mcp.tool()
async def minecraft_get_chat(limit: int = 50, since: int = 0) -> List[Dict[str, Any]]:
    """
    Fetch recent player chat messages and direct commands (!jarvis triggers) from the chat buffer.
    """
    client = get_client()
    res = await client.get_chat(limit=limit, since=since)
    return [entry.model_dump() for entry in res]


@mcp.tool()
async def minecraft_execute_command(command: str) -> Dict[str, Any]:
    """
    Execute a Minecraft console command safely scheduled on the main server thread.
    """
    client = get_client()
    res = await client.execute_command(command=command)
    return res.model_dump()


@mcp.tool()
async def minecraft_get_surroundings(player: Optional[str] = None, radius: int = 16) -> Dict[str, Any]:
    """
    Scan nearby blocks of interest (ores, chests, machines) and living entities around a target player.
    """
    client = get_client()
    res = await client.get_surroundings(player=player, radius=radius)
    return res.model_dump()


@mcp.tool()
async def minecraft_companion_spawn(
    name: str = "Jarvis",
    x: Optional[float] = None,
    y: Optional[float] = None,
    z: Optional[float] = None,
) -> Dict[str, Any]:
    """
    Spawn the physical Jarvis Companion NPC into the Minecraft world at given coordinates or near the first player.
    """
    client = get_client()
    res = await client.companion_spawn(name=name, x=x, y=y, z=z)
    return res.model_dump()


@mcp.tool()
async def minecraft_companion_move(x: float, y: float, z: float, speed: float = 1.0) -> Dict[str, Any]:
    """
    Command the Jarvis Companion NPC to navigate to specific world coordinates using A* pathfinding.
    """
    client = get_client()
    res = await client.companion_move(x=x, y=y, z=z, speed=speed)
    return res.model_dump()


@mcp.tool()
async def minecraft_companion_follow(player: str, distance: float = 3.0) -> Dict[str, Any]:
    """
    Command the Jarvis Companion NPC to follow a player while maintaining a specified distance.
    """
    client = get_client()
    res = await client.companion_follow(player=player, distance=distance)
    return res.model_dump()


@mcp.tool()
async def minecraft_companion_interact(
    action: str,
    x: int,
    y: int,
    z: int,
    item: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Instruct the Jarvis Companion to interact with blocks or containers (e.g. break_block, place_block, interact_block, inspect_container, inventory).
    """
    client = get_client()
    if action.lower() in ("inventory", "get_inventory", "companion_inventory"):
        res = await client.companion_inventory()
        return res.model_dump()
    if action.lower() == "sort_container":
        res = await client.container_sort(x=x, y=y, z=z)
        return res.model_dump()
    res = await client.companion_interact(action=action, x=x, y=y, z=z, item=item)
    return res.model_dump()


@mcp.tool()
async def minecraft_companion_status() -> Dict[str, Any]:
    """
    Query the active Jarvis Companion's real-time state, coordinates, current target, and health.
    """
    client = get_client()
    res = await client.companion_status()
    return res.model_dump()


async def minecraft_container_sort(x: int, y: int, z: int) -> Dict[str, Any]:
    """
    In-place compact and sort items in a container at (x, y, z) using Direwolf20 category hierarchy.
    """
    client = get_client()
    res = await client.container_sort(x=x, y=y, z=z)
    return res.model_dump()


async def minecraft_container_transfer(
    source_x: int,
    source_y: int,
    source_z: int,
    target_x: int,
    target_y: int,
    target_z: int,
    filter: str = "all",
) -> Dict[str, Any]:
    """
    Safely transfer items matching category filter between two containers with zero item voiding.
    """
    client = get_client()
    source = {"x": source_x, "y": source_y, "z": source_z}
    target = {"x": target_x, "y": target_y, "z": target_z}
    res = await client.container_transfer(source=source, target=target, filter=filter)
    return res.model_dump()


async def minecraft_container_scan(
    x: Optional[int] = None,
    y: Optional[int] = None,
    z: Optional[int] = None,
    radius: int = 16,
    player: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Scan nearby containers within radius and categorize each container into dominant category.
    """
    client = get_client()
    res = await client.container_scan(x=x, y=y, z=z, radius=radius, player=player)
    return res.model_dump()


async def minecraft_companion_retrieve(
    item: str,
    count: int = 1,
    player: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Command Jarvis to find up to count of item in nearby containers, put into inventory, and deliver to player.
    """
    client = get_client()
    res = await client.companion_retrieve(item=item, count=count, player=player)
    return res.model_dump()


async def minecraft_companion_deposit(
    category: str = "all",
    radius: int = 32,
    player: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Command Jarvis to deposit matching items from his 27-slot inventory into nearby categorized chests.
    """
    client = get_client()
    res = await client.companion_deposit(category=category, radius=radius, player=player)
    return res.model_dump()


async def minecraft_container_organize(
    x: Optional[int] = None,
    y: Optional[int] = None,
    z: Optional[int] = None,
    radius: int = 32,
    player: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Master storage organization: categorize all base containers into dedicated category chests
    (ores, food, blocks, equipment, farming, materials) and auto-stack new double chests on top when full.
    """
    client = get_client()
    res = await client.container_organize(x=x, y=y, z=z, radius=radius, player=player)
    return res.model_dump()


async def minecraft_companion_inventory() -> Dict[str, Any]:
    """
    Query the active Jarvis Companion's internal 27-slot inventory and current equipment (armor, weapon, shield).
    """
    client = get_client()
    res = await client.companion_inventory()
    return res.model_dump()


@mcp.resource("companion://inventory")
async def companion_inventory_resource() -> str:
    """
    Query the active Jarvis Companion's internal 27-slot inventory and equipped gear as an MCP resource.
    """
    client = get_client()
    res = await client.companion_inventory()
    return res.model_dump_json()


def register_inventory_tool() -> None:
    """Register the companion inventory tool explicitly on the active MCP server instance."""
    mcp.tool()(minecraft_companion_inventory)


def register_storage_tools() -> None:
    """Register the Phase 3 container and logistics tools explicitly on the active MCP server instance."""
    mcp.tool()(minecraft_container_sort)
    mcp.tool()(minecraft_container_transfer)
    mcp.tool()(minecraft_container_scan)
    mcp.tool()(minecraft_companion_retrieve)
    mcp.tool()(minecraft_companion_deposit)
    mcp.tool()(minecraft_container_organize)


async def minecraft_companion_build(
    structure: str,
    material: str = "minecraft:cobblestone",
    direction: Optional[str] = None,
    length: Optional[int] = None,
    width: Optional[int] = None,
    height: Optional[int] = None,
    size: Optional[int] = None,
    size_x: Optional[int] = None,
    size_z: Optional[int] = None,
    railing: bool = True,
    torches: bool = True,
    crenellations: bool = True,
    door: bool = True,
) -> Dict[str, Any]:
    """
    Direct Jarvis to procedurally construct a structure (bridge, shelter, wall, platform).
    Jarvis physically navigates to each coordinate, looks, swings arm, and consumes blocks from his inventory.
    """
    client = get_client()
    res = await client.companion_build(
        structure=structure,
        material=material,
        direction=direction,
        length=length,
        width=width,
        height=height,
        size=size,
        size_x=size_x,
        size_z=size_z,
        railing=railing,
        torches=torches,
        crenellations=crenellations,
        door=door,
    )
    return res.model_dump()


async def minecraft_companion_build_status() -> Dict[str, Any]:
    """
    Query the active construction task's progress, placed blocks, skipped blocks, and status.
    """
    client = get_client()
    res = await client.companion_build_status()
    return res.model_dump()


async def minecraft_companion_build_cancel() -> Dict[str, Any]:
    """
    Cancel the currently running construction task.
    """
    client = get_client()
    res = await client.companion_build_cancel()
    return res.model_dump()


def register_build_tools() -> None:
    """Register Pillar 2 autonomous building tools explicitly on the active MCP server instance."""
    mcp.tool()(minecraft_companion_build)
    mcp.tool()(minecraft_companion_build_status)
    mcp.tool()(minecraft_companion_build_cancel)


async def minecraft_get_recipes(item: str, limit: int = 15) -> Dict[str, Any]:
    """
    Query server-side recipes (crafting, smelting, processing) for vanilla and Direwolf20 mods.
    """
    client = get_client()
    res = await client.modpack_recipes(item=item, limit=limit)
    return res.model_dump()


async def minecraft_get_nearby_machines(radius: int = 16) -> Dict[str, Any]:
    """
    Inspect and scan nearby modded machines, energy generators, fluid tanks, and processing units.
    """
    client = get_client()
    res = await client.modpack_machines(radius=radius)
    return res.model_dump()


async def minecraft_get_installed_mods() -> Dict[str, Any]:
    """
    List all loaded mods, versions, and namespaces from the active FTB Direwolf20 1.21 instance.
    """
    client = get_client()
    res = await client.modpack_mods()
    return res.model_dump()


def register_modpack_tools() -> None:
    """Register Pillar 4 modpack awareness and recipe tools explicitly on the active MCP server instance."""
    mcp.tool()(minecraft_get_recipes)
    mcp.tool()(minecraft_get_nearby_machines)
    mcp.tool()(minecraft_get_installed_mods)


if os.environ.get("JARVIS_ENABLE_INVENTORY_TOOL", "").lower() in ("1", "true", "yes"):
    register_inventory_tool()

if os.environ.get("JARVIS_ENABLE_STORAGE_TOOLS", "").lower() in ("1", "true", "yes"):
    register_storage_tools()

if os.environ.get("JARVIS_ENABLE_BUILD_TOOLS", "").lower() in ("1", "true", "yes"):
    register_build_tools()

if os.environ.get("JARVIS_ENABLE_MODPACK_TOOLS", "").lower() in ("1", "true", "yes"):
    register_modpack_tools()



def main() -> None:
    """Run the MCP server over stdio transport."""
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
