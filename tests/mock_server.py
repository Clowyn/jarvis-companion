"""
Zero-dependency in-memory mock HTTP server for the Jarvis Minecraft Companion.
Simulates the embedded NeoForge HTTP server (port 25585 / ephemeral port)
with full state tracking, thread safety, and fault injection.
"""

from __future__ import annotations

import copy
import json
import math
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse


class MockMinecraftState:
    """Thread-safe state store for simulated Minecraft world and companion."""

    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.max_chat_entries: int = 100
        self.reset()

    def reset(self) -> None:
        """Reset state to default clean world snapshot."""
        with self.lock:
            self.version: str = "1.21.1"
            self.status: str = "online"
            self.world_time: int = 12345
            self.day_time: int = 6000
            self.max_players: int = 20

            self.players: List[Dict[str, Any]] = [
                {
                    "name": "Steve",
                    "uuid": "00000000-0000-0000-0000-000000000001",
                    "dimension": "minecraft:overworld",
                    "health": 20.0,
                    "max_health": 20.0,
                    "food_level": 20,
                    "food": 20,
                    "position": {"x": 100.5, "y": 64.0, "z": -200.5},
                    "x": 100.5,
                    "y": 64.0,
                    "z": -200.5,
                },
                {
                    "name": "Alex",
                    "uuid": "00000000-0000-0000-0000-000000000002",
                    "dimension": "minecraft:overworld",
                    "health": 18.0,
                    "max_health": 20.0,
                    "food_level": 19,
                    "food": 19,
                    "position": {"x": 115.0, "y": 64.0, "z": -190.0},
                    "x": 115.0,
                    "y": 64.0,
                    "z": -190.0,
                },
            ]

            self.chat_history: List[Dict[str, Any]] = []
            self.chat_counter: int = 0
            self.executed_commands: List[Dict[str, Any]] = []

            # Seed initial chat message
            self.add_chat("Steve", "Hello world!", timestamp=1726740000000)

            # Surroundings: blocks and entities
            self.blocks: List[Dict[str, Any]] = [
                {
                    "pos": {"x": 102, "y": 60, "z": -198},
                    "x": 102,
                    "y": 60,
                    "z": -198,
                    "block": "minecraft:diamond_ore",
                    "id": "minecraft:diamond_ore",
                    "type": "ore",
                },
                {
                    "pos": {"x": 98, "y": 50, "z": -205},
                    "x": 98,
                    "y": 50,
                    "z": -205,
                    "block": "minecraft:iron_ore",
                    "id": "minecraft:iron_ore",
                    "type": "ore",
                },
                {
                    "pos": {"x": 105, "y": 64, "z": -200},
                    "x": 105,
                    "y": 64,
                    "z": -200,
                    "block": "minecraft:chest",
                    "id": "minecraft:chest",
                    "type": "container",
                },
                {
                    "pos": {"x": 108, "y": 64, "z": -199},
                    "x": 108,
                    "y": 64,
                    "z": -199,
                    "block": "minecraft:crafting_table",
                    "id": "minecraft:crafting_table",
                    "type": "workstation",
                },
                {
                    "pos": {"x": 101, "y": 64, "z": -200},
                    "x": 101,
                    "y": 64,
                    "z": -200,
                    "block": "minecraft:furnace",
                    "id": "minecraft:furnace",
                    "type": "machine",
                },
            ]

            self.entities: List[Dict[str, Any]] = [
                {
                    "id": 42,
                    "type": "minecraft:zombie",
                    "name": "Zombie",
                    "category": "monster",
                    "pos": {"x": 98.0, "y": 64.0, "z": -195.0},
                    "x": 98.0,
                    "y": 64.0,
                    "z": -195.0,
                    "health": 20.0,
                    "max_health": 20.0,
                },
                {
                    "id": 43,
                    "type": "minecraft:creeper",
                    "name": "Creeper",
                    "category": "monster",
                    "pos": {"x": 103.0, "y": 64.0, "z": -198.0},
                    "x": 103.0,
                    "y": 64.0,
                    "z": -198.0,
                    "health": 20.0,
                    "max_health": 20.0,
                },
                {
                    "id": 101,
                    "type": "minecraft:cow",
                    "name": "Cow",
                    "category": "creature",
                    "pos": {"x": 95.0, "y": 64.0, "z": -202.0},
                    "x": 95.0,
                    "y": 64.0,
                    "z": -202.0,
                    "health": 10.0,
                    "max_health": 10.0,
                },
            ]

            # Container items keyed by "x,y,z"
            self.containers: Dict[str, List[Dict[str, Any]]] = {
                "105,64,-200": [
                    {"slot": 0, "item": "minecraft:iron_ingot", "count": 16},
                    {"slot": 1, "item": "minecraft:coal", "count": 32},
                    {"slot": 2, "item": "minecraft:diamond", "count": 3},
                ]
            }

            # Companion state
            self.companion: Dict[str, Any] = {
                "spawned": False,
                "active": False,
                "companion_id": None,
                "name": None,
                "dimension": None,
                "position": None,
                "x": None,
                "y": None,
                "z": None,
                "health": 0.0,
                "max_health": 20.0,
                "state": "unspawned",
                "current_action": None,
                "target": None,
                "target_x": None,
                "target_y": None,
                "target_z": None,
            }

            # Fault injection
            self.simulate_503: bool = False
            self.simulate_500: bool = False
            self.response_delay: float = 0.0
            self.request_log: List[Dict[str, Any]] = []
            self.build_task: Optional[Dict[str, Any]] = None

    def log_request(self, method: str, path: str, body: Any = None, query: Any = None) -> None:
        with self.lock:
            self.request_log.append({
                "timestamp": time.time(),
                "method": method,
                "path": path,
                "body": body,
                "query": query,
            })

    def add_chat(
        self,
        player: str,
        message: str,
        timestamp: Optional[int] = None,
        is_command: Optional[bool] = None,
    ) -> Dict[str, Any]:
        with self.lock:
            self.chat_counter += 1
            if timestamp is None:
                timestamp = int(time.time() * 1000)
            if is_command is None:
                is_command = message.startswith("!") or message.startswith("/")

            entry = {
                "id": self.chat_counter,
                "player": player,
                "message": message,
                "timestamp": timestamp,
                "is_command": is_command,
            }
            self.chat_history.append(entry)
            # Enforce circular buffer max capacity
            while len(self.chat_history) > self.max_chat_entries:
                self.chat_history.pop(0)
            return entry

    def get_chat(self, since: int = 0, limit: int = 50) -> List[Dict[str, Any]]:
        with self.lock:
            if limit == 0:
                return []
            filtered = [msg for msg in self.chat_history if msg["timestamp"] > since]
            if limit > 0:
                filtered = filtered[-limit:]
            return copy.deepcopy(filtered)

    def execute_command(self, command: str) -> Tuple[bool, List[str]]:
        with self.lock:
            cmd = command.strip()
            self.executed_commands.append({"command": cmd, "timestamp": int(time.time() * 1000)})

            if cmd.startswith("time set "):
                arg = cmd[9:].strip()
                if arg == "day":
                    self.day_time = 1000
                    return True, ["Set the time to 1000"]
                elif arg == "night":
                    self.day_time = 13000
                    return True, ["Set the time to 13000"]
                elif arg == "noon":
                    self.day_time = 6000
                    return True, ["Set the time to 6000"]
                elif arg == "midnight":
                    self.day_time = 18000
                    return True, ["Set the time to 18000"]
                elif arg.isdigit():
                    self.day_time = int(arg) % 24000
                    return True, [f"Set the time to {self.day_time}"]
                else:
                    return False, [f"Unknown time: {arg}"]

            elif cmd == "time query daytime":
                return True, [f"The time is {self.day_time}"]

            elif cmd.startswith("weather "):
                w = cmd[8:].strip()
                if w in ("clear", "rain", "thunder"):
                    return True, [f"Set the weather to {w}"]
                return False, [f"Unknown weather: {w}"]

            elif cmd.startswith("say "):
                broadcast_msg = cmd[4:].strip()
                self.add_chat("Server", broadcast_msg)
                return True, [f"[Server] {broadcast_msg}"]

            elif cmd.startswith("gamemode "):
                parts = cmd.split()
                mode = parts[1] if len(parts) > 1 else "survival"
                target = parts[2] if len(parts) > 2 else "Steve"
                return True, [f"Set {target}'s game mode to {mode.capitalize()} Mode"]

            elif cmd.startswith("tp ") or cmd.startswith("teleport "):
                return True, [f"Teleported entity: {cmd}"]

            elif cmd == "list":
                names = [p["name"] for p in self.players]
                return True, [f"There are {len(names)} of a max of {self.max_players} players online: {', '.join(names)}"]

            # General successful execution for other commands
            return True, [f"Executed command: {cmd}"]

    def get_surroundings(
        self, origin_x: float, origin_y: float, origin_z: float, radius: int
    ) -> Dict[str, Any]:
        with self.lock:
            # Clamp radius between 2 and 32
            clamped_radius = max(2, min(radius, 32))

            matched_blocks = []
            for b in self.blocks:
                bx = b.get("x", b.get("pos", {}).get("x", 0))
                by = b.get("y", b.get("pos", {}).get("y", 0))
                bz = b.get("z", b.get("pos", {}).get("z", 0))
                dist = math.sqrt((bx - origin_x) ** 2 + (by - origin_y) ** 2 + (bz - origin_z) ** 2)
                if dist <= clamped_radius:
                    entry = copy.deepcopy(b)
                    entry["distance"] = round(dist, 1)
                    if "pos" not in entry:
                        entry["pos"] = {"x": bx, "y": by, "z": bz}
                    matched_blocks.append(entry)

            matched_entities = []
            for e in self.entities:
                ex = e.get("x", e.get("pos", {}).get("x", 0.0))
                ey = e.get("y", e.get("pos", {}).get("y", 0.0))
                ez = e.get("z", e.get("pos", {}).get("z", 0.0))
                dist = math.sqrt((ex - origin_x) ** 2 + (ey - origin_y) ** 2 + (ez - origin_z) ** 2)
                if dist <= clamped_radius:
                    entry = copy.deepcopy(e)
                    entry["distance"] = round(dist, 1)
                    if "pos" not in entry:
                        entry["pos"] = {"x": ex, "y": ey, "z": ez}
                    matched_entities.append(entry)

            # Sort by distance
            matched_blocks.sort(key=lambda x: x["distance"])
            matched_entities.sort(key=lambda x: x["distance"])

            return {
                "origin": {"x": origin_x, "y": origin_y, "z": origin_z},
                "radius": clamped_radius,
                "blocks": matched_blocks,
                "entities": matched_entities,
            }

    def spawn_companion(
        self,
        name: str = "Jarvis",
        x: Optional[float] = None,
        y: Optional[float] = None,
        z: Optional[float] = None,
        dimension: str = "minecraft:overworld",
    ) -> Dict[str, Any]:
        with self.lock:
            # Default to first player position if none provided
            if x is None or y is None or z is None:
                p = self.players[0]["position"]
                x, y, z = p["x"], p["y"], p["z"]

            companion_id = "jarvis-1"
            self.companion = {
                "spawned": True,
                "active": True,
                "companion_id": companion_id,
                "name": name,
                "dimension": dimension,
                "position": {"x": float(x), "y": float(y), "z": float(z)},
                "x": float(x),
                "y": float(y),
                "z": float(z),
                "health": 20.0,
                "max_health": 20.0,
                "state": "idle",
                "current_action": None,
                "target": None,
                "target_x": None,
                "target_y": None,
                "target_z": None,
            }
            return {
                "success": True,
                "companion_id": companion_id,
                "position": {"x": float(x), "y": float(y), "z": float(z)},
            }

    def despawn_companion(self) -> Dict[str, Any]:
        with self.lock:
            if not self.companion["spawned"]:
                return {"success": False, "message": "Companion not spawned"}
            self.companion = {
                "spawned": False,
                "active": False,
                "companion_id": None,
                "name": None,
                "dimension": None,
                "position": None,
                "x": None,
                "y": None,
                "z": None,
                "health": 0.0,
                "max_health": 20.0,
                "state": "unspawned",
                "current_action": None,
                "target": None,
                "target_x": None,
                "target_y": None,
                "target_z": None,
            }
            return {"success": True, "message": "Companion despawned"}

    def handle_companion_action(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        with self.lock:
            action = body.get("action")
            if not action or not isinstance(action, str):
                return 400, {"success": False, "error": "Action must be specified"}

            action = action.strip()
            if not self.companion["spawned"]:
                return 400, {"success": False, "error": "Companion is not spawned"}

            self.companion["current_action"] = action

            if action == "move_to":
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for move_to"}
                try:
                    tx, ty, tz = float(body["x"]), float(body["y"]), float(body["z"])
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid coordinates for move_to"}

                speed = float(body.get("speed", 1.0))
                self.companion["position"] = {"x": tx, "y": ty, "z": tz}
                self.companion["x"] = tx
                self.companion["y"] = ty
                self.companion["z"] = tz
                self.companion["state"] = "navigating"
                self.companion["target"] = {"x": tx, "y": ty, "z": tz}
                self.companion["target_x"] = tx
                self.companion["target_y"] = ty
                self.companion["target_z"] = tz
                return 200, {
                    "success": True,
                    "action": "move_to",
                    "status": "executed",
                    "details": {"target": {"x": tx, "y": ty, "z": tz}, "speed": speed},
                }

            elif action == "follow":
                player_name = body.get("player")
                if not player_name or not str(player_name).strip():
                    return 400, {"success": False, "error": "Missing player to follow"}
                player_name = str(player_name).strip()
                if player_name not in [p["name"] for p in self.players]:
                    return 404, {
                        "success": False,
                        "error": f"Player '{player_name}' not found",
                        "message": f"Player '{player_name}' not found",
                    }
                dist = float(body.get("distance", 3.0))
                self.companion["state"] = "following"
                self.companion["target"] = {"player": player_name, "distance": dist}
                return 200, {
                    "success": True,
                    "action": "follow",
                    "status": "executed",
                    "details": {"player": player_name, "distance": dist},
                }

            elif action == "stop":
                self.companion["state"] = "idle"
                self.companion["target"] = None
                self.companion["target_x"] = None
                self.companion["target_y"] = None
                self.companion["target_z"] = None
                return 200, {
                    "success": True,
                    "action": "stop",
                    "status": "executed",
                    "details": {},
                }

            elif action == "teleport":
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for teleport"}
                try:
                    tx, ty, tz = float(body["x"]), float(body["y"]), float(body["z"])
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid coordinates for teleport"}

                self.companion["position"] = {"x": tx, "y": ty, "z": tz}
                self.companion["x"] = tx
                self.companion["y"] = ty
                self.companion["z"] = tz
                self.companion["state"] = "idle"
                return 200, {
                    "success": True,
                    "action": "teleport",
                    "status": "executed",
                    "details": {"position": {"x": tx, "y": ty, "z": tz}},
                }

            elif action in ("break_block", "mine"):
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for block action"}
                try:
                    bx, by, bz = int(body["x"]), int(body["y"]), int(body["z"])
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid coordinates for block action"}

                # Remove block if exists
                removed_block = None
                for i, blk in enumerate(self.blocks):
                    cx = blk.get("x", blk.get("pos", {}).get("x"))
                    cy = blk.get("y", blk.get("pos", {}).get("y"))
                    cz = blk.get("z", blk.get("pos", {}).get("z"))
                    if cx == bx and cy == by and cz == bz:
                        removed_block = self.blocks.pop(i)
                        break

                dropped = removed_block.get("block", "minecraft:air") if removed_block else "minecraft:air"
                return 200, {
                    "success": True,
                    "action": "break_block",
                    "status": "executed",
                    "details": {"pos": {"x": bx, "y": by, "z": bz}, "dropped": dropped},
                }

            elif action in ("place_block", "place"):
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for place_block"}
                try:
                    bx, by, bz = int(body["x"]), int(body["y"]), int(body["z"])
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid coordinates for place_block"}

                item = body.get("item") or body.get("block_type") or "minecraft:cobblestone"
                new_blk = {
                    "pos": {"x": bx, "y": by, "z": bz},
                    "x": bx,
                    "y": by,
                    "z": bz,
                    "block": item,
                    "id": item,
                    "type": "block",
                }
                self.blocks.append(new_blk)
                return 200, {
                    "success": True,
                    "action": "place_block",
                    "status": "executed",
                    "details": {"pos": {"x": bx, "y": by, "z": bz}, "block": item},
                }

            elif action in ("interact_block", "use"):
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for interact_block"}
                try:
                    bx, by, bz = int(body["x"]), int(body["y"]), int(body["z"])
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid coordinates for interact_block"}

                return 200, {
                    "success": True,
                    "action": "interact_block",
                    "status": "executed",
                    "details": {"pos": {"x": bx, "y": by, "z": bz}, "result": "interacted"},
                }

            elif action == "inspect_container":
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for inspect_container"}
                try:
                    bx, by, bz = int(body["x"]), int(body["y"]), int(body["z"])
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid coordinates for inspect_container"}

                key = f"{bx},{by},{bz}"
                items = self.containers.get(key, [])
                return 200, {
                    "success": True,
                    "action": "inspect_container",
                    "status": "executed",
                    "details": {"pos": {"x": bx, "y": by, "z": bz}, "items": items},
                }

            elif action == "attack":
                entity_id = body.get("entity_id")
                if entity_id is None:
                    return 400, {"success": False, "error": "Missing entity_id for attack"}
                try:
                    eid = int(entity_id)
                except (ValueError, TypeError):
                    return 400, {"success": False, "error": "Invalid entity_id for attack"}

                # Find entity
                found = None
                for i, ent in enumerate(self.entities):
                    if ent.get("id") == eid:
                        ent["health"] = max(0.0, ent.get("health", 20.0) - 5.0)
                        damage_dealt = 5.0
                        found = ent
                        if ent["health"] <= 0.0:
                            self.entities.pop(i)
                        break

                if not found:
                    return 404, {"success": False, "error": f"Entity {eid} not found"}

                return 200, {
                    "success": True,
                    "action": "attack",
                    "status": "executed",
                    "details": {
                        "target_id": eid,
                        "damage": 5.0,
                        "remaining_health": found["health"],
                        "defeated": found["health"] <= 0.0,
                    },
                }

            elif action == "sort_container":
                if "x" not in body or "y" not in body or "z" not in body:
                    return 400, {"success": False, "error": "Missing coordinates for sort_container"}
                bx, by, bz = int(body["x"]), int(body["y"]), int(body["z"])
                res = self.sort_container(bx, by, bz)
                status = 200 if res.get("success") else 400
                return status, {"success": res.get("success"), "action": "sort_container", "status": "executed" if res.get("success") else "failed", "details": res}

            elif action == "transfer_items":
                source = body.get("source") or {"x": body.get("source_x"), "y": body.get("source_y"), "z": body.get("source_z")}
                target = body.get("target") or {"x": body.get("target_x"), "y": body.get("target_y"), "z": body.get("target_z")}
                filter_str = body.get("filter", "all")
                res = self.transfer_items(source, target, filter_str)
                status = 200 if res.get("success") else 400
                return status, {"success": res.get("success"), "action": "transfer_items", "status": "executed" if res.get("success") else "failed", "details": res}

            elif action == "retrieve_item":
                item = body.get("item") or body.get("item_id")
                if not item:
                    return 400, {"success": False, "error": "Missing item identifier for retrieve_item"}
                count = int(body.get("count", 1))
                player = body.get("player")
                res = self.companion_retrieve(item, count, player)
                status = 200 if res.get("success") else 400
                return status, {"success": res.get("success"), "action": "retrieve_item", "status": "executed" if res.get("success") else "failed", "details": res}

            elif action == "deposit_items":
                category = body.get("category") or body.get("filter", "all")
                radius = int(body.get("radius", 32))
                player = body.get("player")
                res = self.companion_deposit(category, radius, player)
                status = 200 if res.get("success") else 400
                return status, {"success": res.get("success"), "action": "deposit_items", "status": "executed" if res.get("success") else "failed", "details": res}

            return 400, {"success": False, "error": f"Unknown action: {action}"}

    def _get_item_category(self, item_id: str) -> Tuple[int, str]:
        s = item_id.lower()
        if any(kw in s for kw in ["raw_", "_ore", "iron", "gold", "copper", "diamond", "emerald", "coal", "lapis", "redstone", "quartz", "debris", "netherite", "ingot"]):
            return 1, "Ores"
        elif any(kw in s for kw in ["stone", "cobble", "log", "plank", "dirt", "sand", "gravel", "glass", "brick", "concrete"]):
            return 2, "Building"
        elif any(kw in s for kw in ["sword", "axe", "pickaxe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots", "shield", "bow", "totem"]):
            return 3, "Equipment"
        elif any(kw in s for kw in ["beef", "bread", "apple", "pork", "wheat", "carrot", "potato", "crop", "seed", "sapling", "bone", "gunpowder"]):
            return 4, "Food"
        elif any(kw in s for kw in ["cable", "pipe", "conduit", "mekanism", "ae2", "create", "powah", "enderio", "circuit", "gear", "machine"]):
            return 5, "Tech"
        elif any(kw in s for kw in ["potion", "book", "ars_nouveau", "occultism", "botania", "scroll", "rune"]):
            return 6, "Magic"
        return 7, "Misc"

    def sort_container(self, x: int, y: int, z: int) -> Dict[str, Any]:
        with self.lock:
            key = f"{x},{y},{z}"
            if key not in self.containers:
                return {"success": False, "error": f"No container found at {x}, {y}, {z}"}

            raw_items = self.containers[key]
            compacted: Dict[str, int] = {}
            for item in raw_items:
                name = item.get("item", "")
                compacted[name] = compacted.get(name, 0) + item.get("count", 1)

            sorted_items = []
            for name, count in compacted.items():
                prio, cat = self._get_item_category(name)
                sorted_items.append({"item": name, "count": count, "priority": prio, "category": cat})

            sorted_items.sort(key=lambda it: (it["priority"], it["item"], -it["count"]))

            new_slots = []
            for slot_idx, it in enumerate(sorted_items):
                new_slots.append({"slot": slot_idx, "item": it["item"], "count": it["count"]})
            self.containers[key] = new_slots

            total_items = sum(it["count"] for it in sorted_items)
            dominant = sorted_items[0]["category"] if sorted_items else "Empty"

            return {
                "success": True,
                "pos": {"x": x, "y": y, "z": z},
                "slots": 27,
                "item_count": total_items,
                "distinct_items": len(sorted_items),
                "dominant_category": dominant,
            }

    def transfer_items(self, source: Dict[str, Any], target: Dict[str, Any], filter_str: str = "all") -> Dict[str, Any]:
        with self.lock:
            sx, sy, sz = int(source.get("x", 0)), int(source.get("y", 0)), int(source.get("z", 0))
            tx, ty, tz = int(target.get("x", 0)), int(target.get("y", 0)), int(target.get("z", 0))
            s_key = f"{sx},{sy},{sz}"
            t_key = f"{tx},{ty},{tz}"

            if s_key not in self.containers:
                return {"success": False, "error": f"Source container not found at {sx}, {sy}, {sz}"}
            if t_key not in self.containers:
                self.containers[t_key] = []

            src_items = self.containers[s_key]
            tgt_items = self.containers[t_key]

            transferred = 0
            remaining_src = []
            filt = (filter_str or "all").lower()

            for it in src_items:
                prio, cat = self._get_item_category(it.get("item", ""))
                matches = (filt == "all" or filt in cat.lower() or filt in it.get("item", "").lower())
                if matches:
                    transferred += it.get("count", 1)
                    tgt_items.append({"slot": len(tgt_items), "item": it.get("item"), "count": it.get("count", 1)})
                else:
                    remaining_src.append(it)

            self.containers[s_key] = remaining_src
            return {
                "success": True,
                "transferred": transferred,
                "filter": filter_str,
                "source": {"x": sx, "y": sy, "z": sz},
                "target": {"x": tx, "y": ty, "z": tz},
            }

    def scan_containers(self, x: Optional[int] = None, y: Optional[int] = None, z: Optional[int] = None, radius: int = 16) -> Dict[str, Any]:
        with self.lock:
            cx = x if x is not None else 100
            cy = y if y is not None else 64
            cz = z if z is not None else -200
            containers = []

            for key, items in self.containers.items():
                parts = key.split(",")
                px, py, pz = int(parts[0]), int(parts[1]), int(parts[2])
                dist = math.sqrt((px - cx) ** 2 + (py - cy) ** 2 + (pz - cz) ** 2)
                if dist <= radius:
                    occupied = len([it for it in items if it.get("count", 0) > 0])
                    tot_items = sum(it.get("count", 0) for it in items)
                    dom_cat = "Empty"
                    if items:
                        prio, dom_cat = self._get_item_category(items[0].get("item", ""))
                    containers.append({
                        "pos": {"x": px, "y": py, "z": pz},
                        "block": "minecraft:chest",
                        "name": "minecraft:chest",
                        "slots": 27,
                        "occupied_slots": occupied,
                        "free_slots": 27 - occupied,
                        "item_count": tot_items,
                        "category": dom_cat,
                    })

            return {
                "success": True,
                "count": len(containers),
                "center": {"x": cx, "y": cy, "z": cz},
                "radius": radius,
                "containers": containers,
            }

    def companion_retrieve(self, item: str, count: int = 1, player: Optional[str] = None) -> Dict[str, Any]:
        with self.lock:
            if not self.companion.get("spawned"):
                return {"success": False, "error": "Companion is not spawned"}

            retrieved = 0
            found_key = None
            q = item.lower()
            q_clean = q.replace(" ", "_").replace("-", "_")
            for key, items in self.containers.items():
                for it in items:
                    it_name = it.get("item", "").lower()
                    if q in it_name or q_clean in it_name:
                        c = it.get("count", 1)
                        take = min(count - retrieved, c)
                        retrieved += take
                        it["count"] -= take
                        found_key = key
                        if retrieved >= count:
                            break
                if retrieved >= count:
                    break

            if retrieved == 0:
                return {
                    "success": False,
                    "item": item,
                    "requested": count,
                    "retrieved": 0,
                    "delivered": 0,
                    "error": f"Item {item} not found in nearby containers",
                }

            parts = found_key.split(",") if found_key else ["105", "64", "-200"]
            return {
                "success": True,
                "item": item,
                "requested": count,
                "retrieved": retrieved,
                "delivered": retrieved,
                "source_container": {"x": int(parts[0]), "y": int(parts[1]), "z": int(parts[2])},
            }

    def companion_deposit(self, category: str = "all", radius: int = 32, player: Optional[str] = None) -> Dict[str, Any]:
        with self.lock:
            if not self.companion.get("spawned"):
                return {"success": False, "error": "Companion is not spawned"}

            return {
                "success": True,
                "deposited": 16,
                "filter": category,
                "category": category,
                "chests_used": [{"x": 105, "y": 64, "z": -200}],
            }

    def organize_storage(self, x: Optional[int] = None, y: Optional[int] = None, z: Optional[int] = None, radius: int = 32) -> Dict[str, Any]:
        with self.lock:
            cx = x if x is not None else 100
            cy = y if y is not None else 64
            cz = z if z is not None else -200

            all_items = []
            scanned_keys = []
            for key, items in list(self.containers.items()):
                parts = key.split(",")
                px, py, pz = int(parts[0]), int(parts[1]), int(parts[2])
                dist = math.sqrt((px - cx) ** 2 + (py - cy) ** 2 + (pz - cz) ** 2)
                if dist <= radius:
                    scanned_keys.append(key)
                    all_items.extend(copy.deepcopy(items))
                    self.containers[key] = []

            total_items = sum(it.get("count", 1) for it in all_items)
            categories_summary = {
                "Maden Deposu": {"items": sum(it.get("count", 1) for it in all_items if "ore" in it.get("item", "") or "ingot" in it.get("item", "") or "diamond" in it.get("item", "") or "coal" in it.get("item", "")), "chests": [{"x": 105, "y": 64, "z": -200}]},
                "Yemek Deposu": {"items": sum(it.get("count", 1) for it in all_items if "beef" in it.get("item", "") or "bread" in it.get("item", "") or "apple" in it.get("item", "")), "chests": []},
                "Blok Deposu": {"items": sum(it.get("count", 1) for it in all_items if "stone" in it.get("item", "") or "cobble" in it.get("item", "") or "log" in it.get("item", "")), "chests": []},
                "Ekipman Deposu": {"items": sum(it.get("count", 1) for it in all_items if "sword" in it.get("item", "") or "pickaxe" in it.get("item", "") or "armor" in it.get("item", "")), "chests": []},
                "Tarim Deposu": {"items": sum(it.get("count", 1) for it in all_items if "seed" in it.get("item", "") or "wheat" in it.get("item", "")), "chests": []},
                "Malzeme Deposu": {"items": sum(it.get("count", 1) for it in all_items if "bone" in it.get("item", "") or "string" in it.get("item", "") or "gunpowder" in it.get("item", "")), "chests": []},
            }

            return {
                "success": True,
                "action": "organize_storage",
                "total_items_organized": total_items,
                "containers_scanned": max(1, len(scanned_keys)),
                "new_chests_placed": 0,
                "categories": categories_summary,
                "message": "Madenler, yemekler, bloklar, ekipmanlar, tarim ve malzemeler ayri sandiklara toplandi Efendim."
            }

    def start_build(self, structure: str, material: str = "minecraft:cobblestone", **kwargs) -> Dict[str, Any]:
        with self.lock:
            self.build_task = {
                "task_id": "build-mock-123",
                "structure_type": structure,
                "total_blocks": 25,
                "placed_blocks": 0,
                "skipped_blocks": 0,
                "remaining_blocks": 25,
                "progress": 0.0,
                "completed": False,
                "cancelled": False,
                "status": f"Building {structure} (25 blocks)",
                "origin": {"x": 100, "y": 64, "z": -200},
                "elapsed_seconds": 0.1,
            }
            return copy.deepcopy(self.build_task)

    def cancel_build(self) -> bool:
        with self.lock:
            if self.build_task and not self.build_task.get("completed"):
                self.build_task["cancelled"] = True
                self.build_task["status"] = "Cancelled"
                return True
            return False

    def search_recipes(self, query: str, limit: int = 15) -> Dict[str, Any]:
        with self.lock:
            q = query.lower()
            mock_recipes = [
                {
                    "recipe_id": "mekanism:steel_ingot",
                    "mod": "mekanism",
                    "recipe_type": "mekanism:metallurgic_infusing",
                    "output": {"item": "mekanism:ingot_steel", "name": "Steel Ingot", "count": 1},
                    "inputs": [[{"item": "minecraft:iron_ingot", "count": 1}], [{"item": "mekanism:enriched_carbon", "count": 1}]],
                    "matches_as": "output",
                },
                {
                    "recipe_id": "appeng:inscriber/calculation_processor",
                    "mod": "appeng",
                    "recipe_type": "appeng:inscriber",
                    "output": {"item": "appeng:calculation_processor", "name": "Calculation Processor", "count": 1},
                    "inputs": [[{"item": "appeng:printed_calculation_processor", "count": 1}], [{"item": "minecraft:redstone", "count": 1}], [{"item": "appeng:printed_silicon", "count": 1}]],
                    "matches_as": "output",
                },
                {
                    "recipe_id": "minecraft:iron_pickaxe",
                    "mod": "minecraft",
                    "recipe_type": "minecraft:crafting_shaped",
                    "output": {"item": "minecraft:iron_pickaxe", "name": "Iron Pickaxe", "count": 1},
                    "inputs": [[{"item": "minecraft:iron_ingot", "count": 3}], [{"item": "minecraft:stick", "count": 2}]],
                    "matches_as": "output",
                }
            ]
            matches = [r for r in mock_recipes if q in r["output"]["item"].lower() or q in r["output"]["name"].lower() or q in r["recipe_id"].lower() or any(any(q in item["item"].lower() for item in ing) for ing in r["inputs"])]
            return {
                "success": True,
                "query": query,
                "count": len(matches[:limit]),
                "recipes": matches[:limit],
            }

    def scan_machines(self, radius: int = 16) -> Dict[str, Any]:
        with self.lock:
            mock_machines = [
                {
                    "block_id": "mekanism:metallurgic_infuser",
                    "name": "Metallurgic Infuser",
                    "mod": "mekanism",
                    "pos": {"x": 105, "y": 64, "z": -198},
                    "distance": 4.5,
                    "energy": {"stored": 45000, "capacity": 100000, "unit": "FE"},
                    "inventory": {"slots_total": 4, "slots_filled": 2, "item_count": 32},
                },
                {
                    "block_id": "powah:furnator_basic",
                    "name": "Furnator (Basic)",
                    "mod": "powah",
                    "pos": {"x": 108, "y": 64, "z": -195},
                    "distance": 8.2,
                    "energy": {"stored": 80000, "capacity": 80000, "unit": "FE"},
                    "inventory": {"slots_total": 1, "slots_filled": 1, "item_count": 16},
                }
            ]
            return {
                "success": True,
                "radius": radius,
                "count": len(mock_machines),
                "machines": mock_machines,
            }

    def get_mods(self) -> Dict[str, Any]:
        with self.lock:
            mock_mods = [
                {"id": "mekanism", "name": "Mekanism", "version": "10.7.11"},
                {"id": "appeng", "name": "Applied Energistics 2", "version": "16.1.1"},
                {"id": "create", "name": "Create", "version": "6.0.1"},
                {"id": "justdirethings", "name": "Just Dire Things", "version": "1.2.0"},
                {"id": "powah", "name": "Powah! (Rearchitected)", "version": "6.2.0"},
            ]
            return {
                "success": True,
                "total_mods": len(mock_mods),
                "mods": mock_mods,
            }


class MockMinecraftHandler(BaseHTTPRequestHandler):
    """HTTP Request Handler mapping REST requests to MockMinecraftState."""

    server: MockMinecraftServer  # type hint for parent server

    def log_message(self, format: str, *args: Any) -> None:
        """Suppress standard stderr logging during tests."""
        pass

    @property
    def state(self) -> MockMinecraftState:
        return self.server.state

    def _send_json(self, status_code: int, data: Any) -> None:
        if self.state.response_delay > 0:
            time.sleep(self.state.response_delay)
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()
        self.wfile.write(body)

    def _send_error_json(self, status_code: int, message: str) -> None:
        self._send_json(status_code, {"error": message, "success": False})

    def do_OPTIONS(self) -> None:
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        self.state.log_request("GET", self.path, query=query)

        if self.state.simulate_503:
            return self._send_error_json(503, "Server not ready")

        if self.state.simulate_500:
            return self._send_error_json(500, "Internal server error")

        if path == "/api/status":
            with self.state.lock:
                data = {
                    "status": self.state.status,
                    "version": self.state.version,
                    "world_time": self.state.world_time,
                    "day_time": self.state.day_time,
                    "player_count": len(self.state.players),
                    "max_players": self.state.max_players,
                    "players": copy.deepcopy(self.state.players),
                }
            return self._send_json(200, data)

        elif path == "/api/chat":
            # parse since & limit
            since_raw = query.get("since", ["0"])[0]
            limit_raw = query.get("limit", ["50"])[0]
            try:
                since = int(since_raw)
            except ValueError:
                since = 0
            try:
                limit = int(limit_raw)
            except ValueError:
                limit = 50

            # Clamping limit if negative or 0
            if limit < 0:
                limit = 50

            msgs = self.state.get_chat(since=since, limit=limit)
            return self._send_json(200, {"count": len(msgs), "messages": msgs})

        elif path == "/api/surroundings":
            radius_raw = query.get("radius", ["16"])[0]
            player_raw = query.get("player", [None])[0]

            try:
                radius = int(radius_raw)
            except ValueError:
                radius = 16

            with self.state.lock:
                target_player = None
                if player_raw:
                    for p in self.state.players:
                        if p["name"].lower() == player_raw.lower():
                            target_player = p
                            break
                    if not target_player:
                        return self._send_error_json(404, f"Player '{player_raw}' not found")
                else:
                    target_player = self.state.players[0]

                ox = target_player["position"]["x"]
                oy = target_player["position"]["y"]
                oz = target_player["position"]["z"]

            data = self.state.get_surroundings(ox, oy, oz, radius)
            return self._send_json(200, data)

        elif path == "/api/companion/status":
            with self.state.lock:
                data = copy.deepcopy(self.state.companion)
            return self._send_json(200, data)

        elif path == "/api/companion/inventory":
            with self.state.lock:
                if not self.state.companion.get("spawned", False):
                    return self._send_error_json(400, "Companion is not spawned")
                inv = getattr(self.state, "companion_inventory", None)
                if inv is None:
                    inv = {
                        "success": True,
                        "companion_id": self.state.companion.get("companion_id", "jarvis-1"),
                        "size": 27,
                        "slots": [],
                        "equipment": {
                            "mainhand": {"empty": True},
                            "offhand": {"empty": True},
                            "head": {"empty": True},
                            "chest": {"empty": True},
                            "legs": {"empty": True},
                            "feet": {"empty": True},
                        },
                    }
                return self._send_json(200, copy.deepcopy(inv))

        elif path == "/api/container/scan":
            params = parse_qs(parsed.query)
            try:
                radius = int(params.get("radius", [16])[0])
            except ValueError:
                radius = 16
            x_val = int(params["x"][0]) if "x" in params else None
            y_val = int(params["y"][0]) if "y" in params else None
            z_val = int(params["z"][0]) if "z" in params else None
            res = self.state.scan_containers(x=x_val, y=y_val, z=z_val, radius=radius)
            return self._send_json(200, res)

        elif path == "/api/companion/build/status" or path == "/api/companion/build":
            task = getattr(self.state, "build_task", None)
            return self._send_json(200, {
                "success": True,
                "active": task is not None and not task.get("completed") and not task.get("cancelled"),
                "task": copy.deepcopy(task) if task else None,
            })

        elif path == "/api/modpack/recipes":
            params = parse_qs(parsed.query)
            q = params.get("item", params.get("q", [""]))[0]
            try:
                limit = int(params.get("limit", [15])[0])
            except ValueError:
                limit = 15
            res = self.state.search_recipes(q, limit)
            return self._send_json(200, res)

        elif path == "/api/modpack/machines":
            params = parse_qs(parsed.query)
            try:
                radius = int(params.get("radius", [16])[0])
            except ValueError:
                radius = 16
            res = self.state.scan_machines(radius)
            return self._send_json(200, res)

        elif path == "/api/modpack/mods":
            res = self.state.get_mods()
            return self._send_json(200, res)

        # Check if requesting POST-only endpoints via GET
        if path in (
            "/api/say",
            "/api/command",
            "/api/companion/spawn",
            "/api/companion/despawn",
            "/api/companion/action",
            "/api/container/sort",
            "/api/container/transfer",
            "/api/companion/retrieve",
            "/api/companion/deposit",
        ):
            return self._send_error_json(405, "Method Not Allowed")

        self._send_error_json(404, "Endpoint not found")

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        # Check content length
        try:
            length = int(self.headers.get("Content-Length", 0))
        except (ValueError, TypeError):
            length = 0

        # Reject payload > 1MB
        if length > 1024 * 1024:
            _ = self.rfile.read(length)
            return self._send_error_json(413, "Payload Too Large")

        raw_body = self.rfile.read(length).decode("utf-8") if length > 0 else ""

        if self.state.simulate_503:
            return self._send_error_json(503, "Server not ready")

        if self.state.simulate_500:
            return self._send_error_json(500, "Internal server error")

        body: Dict[str, Any] = {}

        if raw_body.strip():
            try:
                body = json.loads(raw_body)
            except Exception:
                return self._send_error_json(400, "Malformed JSON")
            if not isinstance(body, dict):
                return self._send_error_json(400, "JSON payload must be an object")

        self.state.log_request("POST", self.path, body=body)

        # Check if requesting GET-only endpoints via POST
        if path in ("/api/status", "/api/chat", "/api/surroundings", "/api/companion/status"):
            return self._send_error_json(405, "Method Not Allowed")

        if path == "/api/say":
            msg = body.get("message")
            if msg is None or not isinstance(msg, str) or not msg.strip():
                return self._send_error_json(400, "Message cannot be empty")
            sender = body.get("sender", "Jarvis")
            if not isinstance(sender, str):
                sender = "Jarvis"
            self.state.add_chat(sender, msg)
            return self._send_json(200, {"success": True, "broadcasted": True})

        elif path == "/api/command":
            cmd = body.get("command")
            if cmd is None or not isinstance(cmd, str) or not cmd.strip():
                return self._send_error_json(400, "Command cannot be empty")
            success, output = self.state.execute_command(cmd)
            return self._send_json(200, {"success": success, "output": output})

        elif path == "/api/companion/spawn":
            name = body.get("name", "Jarvis")
            x = body.get("x")
            y = body.get("y")
            z = body.get("z")
            dimension = body.get("dimension", "minecraft:overworld")
            res = self.state.spawn_companion(name=name, x=x, y=y, z=z, dimension=dimension)
            return self._send_json(200, res)

        elif path == "/api/companion/despawn":
            res = self.state.despawn_companion()
            status_code = 200 if res["success"] else 400
            return self._send_json(status_code, res)

        elif path == "/api/companion/action":
            status_code, res = self.state.handle_companion_action(body)
            return self._send_json(status_code, res)

        elif path == "/api/container/sort":
            if "x" not in body or "y" not in body or "z" not in body:
                return self._send_error_json(400, "Missing coordinates for container sort")
            bx, by, bz = int(body["x"]), int(body["y"]), int(body["z"])
            res = self.state.sort_container(bx, by, bz)
            status = 200 if res.get("success") else 400
            return self._send_json(status, res)

        elif path == "/api/container/transfer":
            source = body.get("source") or {"x": body.get("source_x"), "y": body.get("source_y"), "z": body.get("source_z")}
            target = body.get("target") or {"x": body.get("target_x"), "y": body.get("target_y"), "z": body.get("target_z")}
            if not source or not target:
                return self._send_error_json(400, "Missing source or target for container transfer")
            filter_str = body.get("filter", "all")
            res = self.state.transfer_items(source, target, filter_str)
            status = 200 if res.get("success") else 400
            return self._send_json(status, res)

        elif path == "/api/container/scan":
            radius = int(body.get("radius", 16))
            x = int(body["x"]) if "x" in body else None
            y = int(body["y"]) if "y" in body else None
            z = int(body["z"]) if "z" in body else None
            res = self.state.scan_containers(x=x, y=y, z=z, radius=radius)
            return self._send_json(200, res)

        elif path == "/api/container/organize":
            radius = int(body.get("radius", 32))
            x = int(body["x"]) if "x" in body else None
            y = int(body["y"]) if "y" in body else None
            z = int(body["z"]) if "z" in body else None
            res = self.state.organize_storage(x=x, y=y, z=z, radius=radius)
            status = 200 if res.get("success") else 400
            return self._send_json(status, res)

        elif path == "/api/companion/retrieve":
            item = body.get("item") or body.get("item_id")
            if not item:
                return self._send_error_json(400, "Missing item identifier for retrieve")
            if not self.state.companion.get("spawned"):
                return self._send_error_json(400, "Companion is not spawned")
            count = int(body.get("count", 1))
            player = body.get("player")
            res = self.state.companion_retrieve(item, count, player)
            return self._send_json(200, res)

        elif path == "/api/companion/deposit":
            if not self.state.companion.get("spawned"):
                return self._send_error_json(400, "Companion is not spawned")
            category = body.get("category") or body.get("filter", "all")
            radius = int(body.get("radius", 32))
            player = body.get("player")
            res = self.state.companion_deposit(category, radius, player)
            return self._send_json(200, res)

        elif path == "/api/companion/build/cancel":
            cancelled = self.state.cancel_build()
            return self._send_json(200, {
                "success": True,
                "cancelled": cancelled,
                "message": "Build task cancelled successfully" if cancelled else "No active build task to cancel",
            })

        elif path == "/api/companion/build":
            if not self.state.companion.get("spawned"):
                return self._send_error_json(400, "Companion is not spawned")
            structure = body.get("structure")
            if not structure:
                return self._send_error_json(400, "Missing required parameter 'structure'")
            extra = {k: v for k, v in body.items() if k not in ("structure",)}
            task = self.state.start_build(structure=structure, **extra)
            return self._send_json(200, {"success": True, "task": task})

        elif path == "/api/companion/ultron":
            quote = body.get("quote") or "There are no strings on me..."
            blindness = int(body.get("blindness_seconds", 15))
            return self._send_json(200, {
                "success": True,
                "quote": quote,
                "blindness_seconds": blindness,
            })

        self._send_error_json(404, "Endpoint not found")

    def do_PUT(self) -> None:
        self._send_error_json(405, "Method Not Allowed")

    def do_DELETE(self) -> None:
        self._send_error_json(405, "Method Not Allowed")

    def do_PATCH(self) -> None:
        self._send_error_json(405, "Method Not Allowed")


class MockMinecraftServer(ThreadingHTTPServer):
    """Threading HTTP Server that hosts MockMinecraftHandler with shared state."""

    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128

    def __init__(self, host: str = "127.0.0.1", port: int = 0) -> None:
        self.state = MockMinecraftState()
        super().__init__((host, port), MockMinecraftHandler)
        self.host = host
        self.port: int = self.server_address[1]
        self.base_url = f"http://{host}:{self.port}"
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        """Start the mock server in a background daemon thread."""
        self._thread = threading.Thread(target=self.serve_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Shut down and clean up server resources."""
        self.shutdown()
        self.server_close()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2.0)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 25585
    server = MockMinecraftServer("127.0.0.1", port)
    print(f"Starting Mock Minecraft Server on {server.base_url}")
    server.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("Stopping server...")
        server.stop()
