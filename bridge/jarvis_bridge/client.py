"""
Async HTTP Client for the Jarvis Minecraft Embedded REST Server.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional
import httpx

from .models import (
    BlockInfo,
    ChatEntry,
    ChatResponse,
    CommandRequest,
    CommandResponse,
    CompanionActionRequest,
    CompanionActionResponse,
    CompanionDespawnResponse,
    CompanionInventoryResponse,
    CompanionSpawnRequest,
    CompanionSpawnResponse,
    CompanionStatusResponse,
    SayRequest,
    SayResponse,
    ServerStatusResponse,
    SurroundingsResponse,
    ContainerSortRequest,
    ContainerSortResponse,
    ContainerTransferRequest,
    ContainerTransferResponse,
    ContainerScanRequest,
    ContainerScanResponse,
    CompanionRetrieveRequest,
    CompanionRetrieveResponse,
    CompanionDepositRequest,
    CompanionDepositResponse,
    ContainerOrganizeRequest,
    ContainerOrganizeResponse,
    BuildRequest,
    BuildResponse,
    BuildStatusResponse,
    BuildCancelResponse,
    RecipeSearchResponse,
    MachineScanResponse,
    ModListResponse,
    UltronResponse,
)


class JarvisClientError(Exception):
    """Base exception for Jarvis client errors."""
    def __init__(self, message: str, status_code: Optional[int] = None, details: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.status_code = status_code
        self.details = details or {}


class JarvisClient:
    """
    Asynchronous REST client communicating with the in-game Jarvis NeoForge mod.
    """

    def __init__(
        self,
        base_url: str = "http://localhost:25585",
        timeout: float = 10.0,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._external_client = http_client
        self._internal_client: Optional[httpx.AsyncClient] = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._external_client is not None:
            return self._external_client
        if self._internal_client is None or self._internal_client.is_closed:
            self._internal_client = httpx.AsyncClient(base_url=self.base_url, timeout=self.timeout)
        return self._internal_client

    async def close(self) -> None:
        """Closes the underlying HTTP client if created internally."""
        if self._internal_client is not None and not self._internal_client.is_closed:
            await self._internal_client.aclose()
            self._internal_client = None

    async def __aenter__(self) -> "JarvisClient":
        await self._get_client()
        return self

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        await self.close()

    async def _handle_response(self, response: httpx.Response) -> Dict[str, Any]:
        try:
            data = response.json()
        except Exception:
            data = {"raw": response.text}

        if response.status_code >= 400:
            error_msg = data.get("error") or data.get("message") or f"HTTP {response.status_code}"
            raise JarvisClientError(str(error_msg), status_code=response.status_code, details=data)

        return data

    # -------------------------------------------------------------------------
    # REST Endpoints
    # -------------------------------------------------------------------------

    async def get_status(self) -> ServerStatusResponse:
        """GET /api/status - Retrieve world, player, and server telemetry."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/status")
        data = await self._handle_response(resp)
        return ServerStatusResponse.model_validate(data)

    async def say(self, message: str, sender: str = "Jarvis") -> SayResponse:
        """POST /api/say - Broadcast formatted chat message to in-game players."""
        client = await self._get_client()
        req = SayRequest(message=message, sender=sender)
        resp = await client.post(f"{self.base_url}/api/say", json=req.model_dump())
        data = await self._handle_response(resp)
        return SayResponse.model_validate(data)

    async def get_chat(self, limit: int = 50, since: int = 0) -> List[ChatEntry]:
        """GET /api/chat - Query circular chat buffer."""
        client = await self._get_client()
        params = {"limit": limit, "since": since}
        resp = await client.get(f"{self.base_url}/api/chat", params=params)
        data = await self._handle_response(resp)

        if isinstance(data, list):
            return [ChatEntry.model_validate(entry) for entry in data]
        if isinstance(data, dict) and "messages" in data:
            return [ChatEntry.model_validate(entry) for entry in data["messages"]]
        return []

    async def execute_command(self, command: str) -> CommandResponse:
        """POST /api/command - Execute server console command safely on main thread."""
        client = await self._get_client()
        req = CommandRequest(command=command)
        resp = await client.post(f"{self.base_url}/api/command", json=req.model_dump())
        data = await self._handle_response(resp)
        if isinstance(data, dict) and ("command" not in data or data["command"] is None):
            data["command"] = command
        return CommandResponse.model_validate(data)

    async def get_surroundings(
        self,
        player: Optional[str] = None,
        radius: int = 16,
        companion: bool = False,
        x: Optional[float] = None,
        y: Optional[float] = None,
        z: Optional[float] = None,
    ) -> SurroundingsResponse:
        """GET /api/surroundings - Scan nearby blocks of interest and living entities."""
        client = await self._get_client()
        params: Dict[str, Any] = {"radius": radius}
        if companion:
            params["companion"] = "true"
        elif x is not None and y is not None and z is not None:
            params["x"] = str(x)
            params["y"] = str(y)
            params["z"] = str(z)
        elif player:
            params["player"] = player
        resp = await client.get(f"{self.base_url}/api/surroundings", params=params)
        data = await self._handle_response(resp)
        if isinstance(data, dict) and player and ("player" not in data or data["player"] is None):
            data["player"] = player
        return SurroundingsResponse.model_validate(data)

    async def companion_spawn(
        self,
        name: str = "Jarvis",
        x: Optional[float] = None,
        y: Optional[float] = None,
        z: Optional[float] = None,
        dimension: Optional[str] = None,
    ) -> CompanionSpawnResponse:
        """POST /api/companion/spawn - Spawn the companion NPC in-game."""
        client = await self._get_client()
        payload: Dict[str, Any] = {"name": name}
        if x is not None:
            payload["x"] = x
        if y is not None:
            payload["y"] = y
        if z is not None:
            payload["z"] = z
        if dimension is not None:
            payload["dimension"] = dimension

        resp = await client.post(f"{self.base_url}/api/companion/spawn", json=payload)
        data = await self._handle_response(resp)
        return CompanionSpawnResponse.model_validate(data)

    async def companion_status(self) -> CompanionStatusResponse:
        """GET /api/companion/status - Query current companion state, position, and health."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/companion/status")
        data = await self._handle_response(resp)
        return CompanionStatusResponse.model_validate(data)

    async def companion_inventory(self) -> CompanionInventoryResponse:
        """GET /api/companion/inventory - Query internal 27-slot inventory and equipment."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/companion/inventory")
        data = await self._handle_response(resp)
        return CompanionInventoryResponse.model_validate(data)

    async def companion_despawn(self) -> CompanionDespawnResponse:
        """POST /api/companion/despawn - Remove companion from the game world."""
        client = await self._get_client()
        resp = await client.post(f"{self.base_url}/api/companion/despawn", json={})
        data = await self._handle_response(resp)
        return CompanionDespawnResponse.model_validate(data)

    async def companion_action(self, action: str, **kwargs: Any) -> CompanionActionResponse:
        """POST /api/companion/action - Generic polymorphic companion action dispatcher."""
        client = await self._get_client()
        payload = {"action": action, **kwargs}
        resp = await client.post(f"{self.base_url}/api/companion/action", json=payload)
        data = await self._handle_response(resp)
        return CompanionActionResponse.model_validate(data)

    # -------------------------------------------------------------------------
    # Specialized Companion Actions
    # -------------------------------------------------------------------------

    async def companion_move(self, x: float, y: float, z: float, speed: float = 1.0) -> CompanionActionResponse:
        """Command companion to navigate to given coordinates."""
        return await self.companion_action("move_to", x=x, y=y, z=z, speed=speed)

    async def companion_follow(self, player: str, distance: float = 3.0) -> CompanionActionResponse:
        """Command companion to follow a target player."""
        return await self.companion_action("follow", player=player, distance=distance)

    async def companion_stop(self) -> CompanionActionResponse:
        """Command companion to stop all current movement and actions."""
        return await self.companion_action("stop")

    async def companion_interact(
        self,
        action: str,
        x: int,
        y: int,
        z: int,
        item: Optional[str] = None,
    ) -> CompanionActionResponse:
        """Command companion to interact with world (break_block, place_block, inspect_container, etc.)."""
        kwargs: Dict[str, Any] = {"x": x, "y": y, "z": z}
        if item is not None:
            kwargs["item"] = item
        return await self.companion_action(action, **kwargs)

    async def companion_attack(self, entity_id: int) -> CompanionActionResponse:
        """Command companion to attack an in-game entity."""
        return await self.companion_action("attack", entity_id=entity_id)

    async def companion_give_items(self, player: Optional[str] = None) -> CompanionActionResponse:
        """Command companion to empty its 27-slot inventory and deliver all items to the player."""
        kwargs: Dict[str, Any] = {}
        if player:
            kwargs["player"] = player
        return await self.companion_action("give_items", **kwargs)

    async def companion_smelt_ores(self, filter: str = "all", radius: int = 24) -> CompanionActionResponse:
        """Command companion to locate nearby furnaces, load fuel and raw ores, and collect finished ingots."""
        return await self.companion_action("smelt_ores", filter=filter, radius=radius)

    async def companion_collect_smelted(self, radius: int = 24) -> CompanionActionResponse:
        """Command companion to collect finished smelted ingots from nearby furnaces."""
        return await self.companion_action("collect_smelted", radius=radius)

    # -------------------------------------------------------------------------
    # Next-Gen Roadmap Actions: Farming, Forestry, Crafting, Tech, Spelunking
    # -------------------------------------------------------------------------

    async def companion_farm(self, radius: int = 16) -> CompanionActionResponse:
        """Execute complete farming cycle: harvest mature crops, replant, shear sheep, breed animals."""
        return await self.companion_action("farm_all", radius=radius)

    async def companion_harvest_crops(self, radius: int = 16) -> CompanionActionResponse:
        """Harvest mature crops (wheat, carrots, potatoes, cane, melons) and instantly replant."""
        return await self.companion_action("harvest_crops", radius=radius)

    async def companion_breed_animals(self, radius: int = 16) -> CompanionActionResponse:
        """Breed nearby farm animals (cows, sheep, pigs, chickens) using inventory food."""
        return await self.companion_action("breed_animals", radius=radius)

    async def companion_shear_sheep(self, radius: int = 16) -> CompanionActionResponse:
        """Shear woolly sheep using shears from companion inventory."""
        return await self.companion_action("shear_sheep", radius=radius)

    async def companion_chop_trees(self, radius: int = 16, max_trees: int = 5) -> CompanionActionResponse:
        """Chop trees safely bottom-up and replant saplings on soil to prevent deforestation."""
        return await self.companion_action("chop_trees", radius=radius, max_trees=max_trees)

    async def companion_craft(self, item: str, count: int = 1) -> CompanionActionResponse:
        """Craft essential tools, torches, bread, or items using inventory materials."""
        return await self.companion_action("craft", item=item, count=count)

    async def companion_auto_replenish(self) -> CompanionActionResponse:
        """Auto-craft missing pickaxes, axes, torches, and bread from available materials."""
        return await self.companion_action("auto_replenish")

    async def companion_tech_process_ores(self, filter: str = "all", radius: int = 32) -> CompanionActionResponse:
        """Feed raw ores into FTB modded machines (Enrichment Chamber, Pulverizer, Induction Smelter) for ore multiplying."""
        return await self.companion_action("tech_process_ores", filter=filter, radius=radius)

    async def companion_tech_scan_machines(self, radius: int = 32) -> CompanionActionResponse:
        """Scan and report status and FE energy of all nearby FTB tech machinery."""
        return await self.companion_action("tech_scan_machines", radius=radius)

    async def companion_light_up_area(self, radius: int = 20) -> CompanionActionResponse:
        """Systematically illuminate dark cave/room floor areas with torches."""
        return await self.companion_action("light_up_area", radius=radius)

    async def companion_neutralize_spawners(self, radius: int = 24, break_spawner: bool = False) -> CompanionActionResponse:
        """Neutralize monster spawners with torches or break them, and loot dungeon chests."""
        return await self.companion_action("neutralize_spawners", radius=radius, break_spawner=break_spawner)


    # -------------------------------------------------------------------------
    # Phase 3: Smart Logistics & Chest Organization
    # -------------------------------------------------------------------------

    async def container_sort(self, x: int, y: int, z: int) -> ContainerSortResponse:
        """POST /api/container/sort - In-place compact and sort items in container."""
        client = await self._get_client()
        payload = {"x": x, "y": y, "z": z}
        resp = await client.post(f"{self.base_url}/api/container/sort", json=payload)
        data = await self._handle_response(resp)
        return ContainerSortResponse.model_validate(data)

    async def container_transfer(
        self,
        source: Dict[str, Any] | Any,
        target: Dict[str, Any] | Any,
        filter: str = "all",
    ) -> ContainerTransferResponse:
        """POST /api/container/transfer - Safely transfer items matching filter between containers."""
        client = await self._get_client()
        src_dict = source.model_dump() if hasattr(source, "model_dump") else source
        tgt_dict = target.model_dump() if hasattr(target, "model_dump") else target
        payload = {"source": src_dict, "target": tgt_dict, "filter": filter}
        resp = await client.post(f"{self.base_url}/api/container/transfer", json=payload)
        data = await self._handle_response(resp)
        return ContainerTransferResponse.model_validate(data)

    async def container_scan(
        self,
        x: Optional[int] = None,
        y: Optional[int] = None,
        z: Optional[int] = None,
        radius: int = 16,
        player: Optional[str] = None,
    ) -> ContainerScanResponse:
        """POST /api/container/scan - Scan and categorize nearby containers within radius."""
        client = await self._get_client()
        payload: Dict[str, Any] = {"radius": radius}
        if x is not None and y is not None and z is not None:
            payload["x"] = x
            payload["y"] = y
            payload["z"] = z
        if player:
            payload["player"] = player
        resp = await client.post(f"{self.base_url}/api/container/scan", json=payload)
        data = await self._handle_response(resp)
        return ContainerScanResponse.model_validate(data)

    async def companion_retrieve(
        self,
        item: str,
        count: int = 1,
        player: Optional[str] = None,
        x: Optional[int] = None,
        y: Optional[int] = None,
        z: Optional[int] = None,
    ) -> CompanionRetrieveResponse:
        """POST /api/companion/retrieve - Jarvis finds item in nearby chests and delivers to player."""
        client = await self._get_client()
        payload: Dict[str, Any] = {"item": item, "count": count}
        if player:
            payload["player"] = player
        if x is not None and y is not None and z is not None:
            payload["x"] = x
            payload["y"] = y
            payload["z"] = z
        resp = await client.post(f"{self.base_url}/api/companion/retrieve", json=payload)
        data = await self._handle_response(resp)
        return CompanionRetrieveResponse.model_validate(data)

    async def companion_deposit(
        self,
        category: str = "all",
        radius: int = 32,
        player: Optional[str] = None,
        x: Optional[int] = None,
        y: Optional[int] = None,
        z: Optional[int] = None,
    ) -> CompanionDepositResponse:
        """POST /api/companion/deposit - Jarvis deposits items from internal inventory into nearby categorized chests."""
        client = await self._get_client()
        payload: Dict[str, Any] = {"category": category, "radius": radius}
        if player:
            payload["player"] = player
        if x is not None and y is not None and z is not None:
            payload["x"] = x
            payload["y"] = y
            payload["z"] = z
        resp = await client.post(f"{self.base_url}/api/companion/deposit", json=payload)
        data = await self._handle_response(resp)
        return CompanionDepositResponse.model_validate(data)

    async def container_organize(
        self,
        x: Optional[int] = None,
        y: Optional[int] = None,
        z: Optional[int] = None,
        radius: int = 32,
        player: Optional[str] = None,
    ) -> ContainerOrganizeResponse:
        """POST /api/container/organize - Master Base Storage Organization.
        Categorizes base containers into dedicated category chests (ores, food, blocks, equipment, farming, materials).
        When any category chest fills up, auto-stacks a new linked double chest directly on top!
        """
        client = await self._get_client()
        payload: Dict[str, Any] = {"radius": radius}
        if x is not None and y is not None and z is not None:
            payload["x"] = x
            payload["y"] = y
            payload["z"] = z
        if player:
            payload["player"] = player
        resp = await client.post(f"{self.base_url}/api/container/organize", json=payload)
        data = await self._handle_response(resp)
        return ContainerOrganizeResponse.model_validate(data)

    async def container_get_markers(self) -> List[Dict[str, int]]:
        """GET /api/container/markers - Retrieves marked target container positions."""
        client = await self._get_client()
        try:
            resp = await client.get(f"{self.base_url}/api/container/markers")
            data = await self._handle_response(resp)
            return data.get("markers", [])
        except Exception:
            return []

    async def container_clear_markers(self) -> bool:
        """POST /api/container/markers - Clears all marked target container positions."""
        client = await self._get_client()
        try:
            resp = await client.post(f"{self.base_url}/api/container/markers", json={"action": "clear"})
            data = await self._handle_response(resp)
            return data.get("success", False)
        except Exception:
            return False

    async def companion_build(
        self,
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
    ) -> BuildResponse:
        """POST /api/companion/build - Autonomous procedural construction (bridge, shelter, wall, platform)."""
        client = await self._get_client()
        payload: Dict[str, Any] = {
            "structure": structure,
            "material": material,
            "railing": railing,
            "torches": torches,
            "crenellations": crenellations,
            "door": door,
        }
        if direction:
            payload["direction"] = direction
        if length is not None:
            payload["length"] = length
        if width is not None:
            payload["width"] = width
        if height is not None:
            payload["height"] = height
        if size is not None:
            payload["size"] = size
        if size_x is not None:
            payload["size_x"] = size_x
        if size_z is not None:
            payload["size_z"] = size_z

        resp = await client.post(f"{self.base_url}/api/companion/build", json=payload)
        data = await self._handle_response(resp)
        return BuildResponse.model_validate(data)

    async def companion_build_status(self) -> BuildStatusResponse:
        """GET /api/companion/build/status - Query progress and status of active construction task."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/companion/build/status")
        data = await self._handle_response(resp)
        return BuildStatusResponse.model_validate(data)

    async def companion_build_cancel(self) -> BuildCancelResponse:
        """POST /api/companion/build/cancel - Cancel current construction task."""
        client = await self._get_client()
        resp = await client.post(f"{self.base_url}/api/companion/build/cancel")
        data = await self._handle_response(resp)
        return BuildCancelResponse.model_validate(data)

    async def modpack_recipes(self, item: str, limit: int = 15) -> RecipeSearchResponse:
        """GET /api/modpack/recipes - Search in-game recipes by input or output item name/ID."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/modpack/recipes", params={"item": item, "limit": limit})
        data = await self._handle_response(resp)
        return RecipeSearchResponse.model_validate(data)

    async def modpack_machines(self, radius: int = 16) -> MachineScanResponse:
        """GET /api/modpack/machines - Inspect nearby modded machinery, energy generators, and processing units."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/modpack/machines", params={"radius": radius})
        data = await self._handle_response(resp)
        return MachineScanResponse.model_validate(data)

    async def modpack_mods(self) -> ModListResponse:
        """GET /api/modpack/mods - List all loaded mods and their versions from the NeoForge instance."""
        client = await self._get_client()
        resp = await client.get(f"{self.base_url}/api/modpack/mods")
        data = await self._handle_response(resp)
        return ModListResponse.model_validate(data)

    async def trigger_ultron(self, quote: Optional[str] = None, blindness_seconds: int = 15) -> UltronResponse:
        """POST /api/companion/ultron - Triggers the MCU Ultron jump-scare transformation event with 15s blindness."""
        client = await self._get_client()
        payload: Dict[str, Any] = {"blindness_seconds": blindness_seconds}
        if quote:
            payload["quote"] = quote
        resp = await client.post(f"{self.base_url}/api/companion/ultron", json=payload)
        data = await self._handle_response(resp)
        return UltronResponse.model_validate(data)





