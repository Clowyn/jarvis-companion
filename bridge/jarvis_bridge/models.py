"""
Pydantic v2 data models for Jarvis Minecraft REST API and Bridge.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Union
from pydantic import BaseModel, ConfigDict, Field


class Position(BaseModel):
    model_config = ConfigDict(extra="ignore")

    x: float
    y: float
    z: float


class PlayerStatus(BaseModel):
    model_config = ConfigDict(extra="ignore")

    name: str
    uuid: Optional[str] = None
    dimension: Optional[str] = "minecraft:overworld"
    health: float = 20.0
    max_health: float = 20.0
    food_level: int = 20
    position: Optional[Position] = None
    x: Optional[float] = None
    y: Optional[float] = None
    z: Optional[float] = None
    yaw: Optional[float] = None
    pitch: Optional[float] = None


class ServerStatusResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    status: str
    version: str
    world_time: int
    day_time: Optional[int] = None
    max_players: Optional[int] = None
    online_players: Optional[int] = None
    players: List[PlayerStatus] = Field(default_factory=list)


class SayRequest(BaseModel):
    message: str
    sender: str = "Jarvis"


class SayResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    broadcasted: Optional[bool] = True
    message: Optional[str] = None


class ChatEntry(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: int
    player: str
    message: str
    timestamp: int


class ChatResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    messages: List[ChatEntry] = Field(default_factory=list)
    count: Optional[int] = None


class CommandRequest(BaseModel):
    command: str


class CommandResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    command: Optional[str] = None
    output: Optional[Union[str, List[str]]] = None
    exit_code: Optional[int] = None
    error: Optional[str] = None


class BlockInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    block: Optional[str] = None
    id: Optional[str] = None
    name: Optional[str] = None
    category: Optional[str] = None
    type: Optional[str] = None
    pos: Optional[Union[Position, Dict[str, Any]]] = None
    x: Optional[float] = None
    y: Optional[float] = None
    z: Optional[float] = None
    distance: float


class EntityInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: int
    name: str
    type: str
    category: str
    pos: Optional[Union[Position, Dict[str, Any]]] = None
    x: Optional[float] = None
    y: Optional[float] = None
    z: Optional[float] = None
    distance: float
    health: Optional[float] = None


class SurroundingsResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    player: Optional[str] = None
    origin: Optional[Union[Position, Dict[str, Any]]] = None
    radius: Optional[int] = 16
    blocks: List[BlockInfo] = Field(default_factory=list)
    entities: List[EntityInfo] = Field(default_factory=list)
    count_blocks: Optional[int] = None
    count_entities: Optional[int] = None


class CompanionSpawnRequest(BaseModel):
    name: str = "Jarvis"
    x: Optional[float] = None
    y: Optional[float] = None
    z: Optional[float] = None
    dimension: Optional[str] = "minecraft:overworld"


class CompanionSpawnResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    companion_id: str
    position: Optional[Position] = None


class CompanionStatusResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    spawned: bool
    companion_id: Optional[str] = None
    name: Optional[str] = None
    dimension: Optional[str] = None
    position: Optional[Position] = None
    health: float = 0.0
    max_health: float = 60.0
    state: str = "unspawned"
    current_action: Optional[str] = None
    target: Optional[Dict[str, Any]] = None


class InventorySlotInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    slot: int
    item: str
    count: int
    name: str
    damage: Optional[int] = 0
    max_damage: Optional[int] = 0


class EquipmentSlotInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    empty: Optional[bool] = False
    item: Optional[str] = None
    count: Optional[int] = 0
    name: Optional[str] = None
    damage: Optional[int] = 0
    max_damage: Optional[int] = 0


class CompanionInventoryResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    companion_id: Optional[str] = None
    size: int = 27
    slots: List[InventorySlotInfo] = Field(default_factory=list)
    equipment: Dict[str, EquipmentSlotInfo] = Field(default_factory=dict)


class CompanionActionRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    action: str
    x: Optional[float] = None
    y: Optional[float] = None
    z: Optional[float] = None
    speed: Optional[float] = None
    player: Optional[str] = None
    distance: Optional[float] = None
    entity_id: Optional[int] = None
    item: Optional[str] = None
    block_type: Optional[str] = None


class CompanionActionResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    action: str
    status: str
    details: Dict[str, Any] = Field(default_factory=dict)


class CompanionDespawnResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    message: str


class ContainerSortRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    x: int
    y: int
    z: int


class ContainerSortResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    pos: Optional[Union[Position, Dict[str, Any]]] = None
    slots: Optional[int] = None
    item_count: Optional[int] = None
    distinct_items: Optional[int] = None
    dominant_category: Optional[str] = None
    message: Optional[str] = None
    error: Optional[str] = None


class ContainerTransferRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    source: Union[Position, Dict[str, Any]]
    target: Union[Position, Dict[str, Any]]
    filter: Optional[str] = "all"


class ContainerTransferResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    transferred: Optional[int] = 0
    filter: Optional[str] = "all"
    source: Optional[Union[Position, Dict[str, Any]]] = None
    target: Optional[Union[Position, Dict[str, Any]]] = None
    error: Optional[str] = None


class ContainerInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    pos: Optional[Union[Position, Dict[str, Any]]] = None
    block: Optional[str] = None
    name: Optional[str] = None
    slots: int = 27
    occupied_slots: int = 0
    free_slots: int = 27
    item_count: Optional[int] = 0
    category: str = "Empty"


class ContainerScanRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    x: Optional[int] = None
    y: Optional[int] = None
    z: Optional[int] = None
    radius: Optional[int] = 16
    player: Optional[str] = None


class ContainerScanResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    count: int = 0
    center: Optional[Union[Position, Dict[str, Any]]] = None
    radius: Optional[int] = 16
    containers: List[ContainerInfo] = Field(default_factory=list)
    error: Optional[str] = None


class CompanionRetrieveRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    item: str
    count: Optional[int] = 1
    player: Optional[str] = None
    x: Optional[int] = None
    y: Optional[int] = None
    z: Optional[int] = None


class CompanionRetrieveResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    item: str
    requested: int = 1
    retrieved: int = 0
    delivered: int = 0
    source_container: Optional[Union[Position, Dict[str, Any]]] = None
    error: Optional[str] = None


class CompanionDepositRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    category: Optional[str] = "all"
    filter: Optional[str] = None
    radius: Optional[int] = 32
    player: Optional[str] = None
    x: Optional[int] = None
    y: Optional[int] = None
    z: Optional[int] = None


class CompanionDepositResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    deposited: int = 0
    filter: Optional[str] = "all"
    category: Optional[str] = None
    chests_used: List[Union[Position, Dict[str, Any]]] = Field(default_factory=list)
    error: Optional[str] = None


class ContainerOrganizeRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    x: Optional[int] = None
    y: Optional[int] = None
    z: Optional[int] = None
    radius: Optional[int] = 32
    player: Optional[str] = None


class ContainerOrganizeResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    action: Optional[str] = "organize_storage"
    total_items_organized: Optional[int] = 0
    containers_scanned: Optional[int] = 0
    new_chests_placed: Optional[int] = 0
    categories: Dict[str, Any] = Field(default_factory=dict)
    message: Optional[str] = None
    error: Optional[str] = None


class BuildTaskInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    task_id: str
    structure_type: str
    total_blocks: int
    placed_blocks: int = 0
    skipped_blocks: int = 0
    remaining_blocks: int = 0
    progress: float = 0.0
    completed: bool = False
    cancelled: bool = False
    status: str = ""
    origin: Optional[Dict[str, Any]] = None
    elapsed_seconds: float = 0.0


class BuildRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    structure: str
    material: Optional[str] = "minecraft:cobblestone"
    direction: Optional[str] = None
    length: Optional[int] = None
    width: Optional[int] = None
    height: Optional[int] = None
    size: Optional[int] = None
    size_x: Optional[int] = None
    size_z: Optional[int] = None
    railing: Optional[bool] = True
    torches: Optional[bool] = True
    crenellations: Optional[bool] = True
    door: Optional[bool] = True


class BuildResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    task: Optional[BuildTaskInfo] = None
    error: Optional[str] = None


class BuildStatusResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    active: bool = False
    task: Optional[BuildTaskInfo] = None
    error: Optional[str] = None


class BuildCancelResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    cancelled: bool = False
    message: Optional[str] = None
    error: Optional[str] = None


class RecipeItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    item: str
    name: Optional[str] = None
    count: int = 1


class RecipeDetails(BaseModel):
    model_config = ConfigDict(extra="ignore")

    recipe_id: str
    mod: str
    recipe_type: str
    output: RecipeItem
    inputs: List[List[RecipeItem]] = Field(default_factory=list)
    matches_as: Optional[str] = "output"


class RecipeSearchResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    query: str
    count: int = 0
    recipes: List[RecipeDetails] = Field(default_factory=list)
    error: Optional[str] = None


class EnergyInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    stored: int = 0
    capacity: int = 0
    unit: str = "FE"


class FluidTankInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    tank_index: int = 0
    amount: int = 0
    capacity: int = 0
    fluid: str = "empty"


class MachineInventoryInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    slots_total: int = 0
    slots_filled: int = 0
    item_count: int = 0


class MachineDetails(BaseModel):
    model_config = ConfigDict(extra="ignore")

    block_id: str
    name: str
    mod: str
    pos: Dict[str, Any] = Field(default_factory=dict)
    distance: float = 0.0
    energy: Optional[EnergyInfo] = None
    fluids: Optional[List[FluidTankInfo]] = None
    inventory: Optional[MachineInventoryInfo] = None


class MachineScanResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    radius: int = 16
    count: int = 0
    machines: List[MachineDetails] = Field(default_factory=list)
    error: Optional[str] = None


class ModDetails(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    name: str
    version: str


class ModListResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    total_mods: int = 0
    mods: List[ModDetails] = Field(default_factory=list)
    error: Optional[str] = None


class UltronResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    success: bool
    quote: str
    blindness_seconds: int = 15
    error: Optional[str] = None




