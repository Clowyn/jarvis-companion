import asyncio
import math
import sys
import unittest
from unittest.mock import AsyncMock, MagicMock

from jarvis_bridge.chat_agent import JarvisChatAgent, sanitize_for_minecraft
from jarvis_bridge.models import BlockInfo, SurroundingsResponse, ServerStatusResponse, CompanionStatusResponse, Position, PlayerStatus


class TestBaseProtectionAndPhysicalMining(unittest.TestCase):
    def setUp(self):
        self.agent = JarvisChatAgent(api_url="http://localhost:25585")
        self.agent.client = AsyncMock()

    def test_no_teleport_in_mining_or_chest(self):
        with open("bridge/jarvis_bridge/chat_agent.py", "r", encoding="utf-8") as f:
            code = f.read()

        # Extract _mine_ores method
        mine_ores_code = code.split("async def _mine_ores(")[1].split("async def _mine_tunnel(")[0]
        self.assertNotIn('companion_action("teleport"', mine_ores_code, "Teleport fallback must be removed from _mine_ores!")

        # Extract _deposit_to_chest method
        deposit_code = code.split("async def _deposit_to_chest(")[1].split("async def _eat_food(")[0]
        self.assertNotIn('companion_action("teleport"', deposit_code, "Teleport fallback must be removed from _deposit_to_chest!")

    def test_detect_base_zone_with_waypoint(self):
        async def run():
            self.agent.memory = {
                "waypoints": {
                    "ev": {"name": "Ev", "x": 100, "y": 64, "z": 200}
                }
            }
            self.agent.client.get_surroundings = AsyncMock(return_value=SurroundingsResponse(blocks=[]))

            base_center, anchors, radius, is_inside = await self.agent._detect_base_zone("AlcyoneDX")
            self.assertEqual(base_center, (100, 64, 200))
            self.assertEqual(radius, 20.0)

            # Inside base
            self.assertTrue(is_inside(105, 64, 205))   # ~7 blocks away
            self.assertTrue(is_inside(100, 60, 200))   # under floor (y=60)
            self.assertTrue(is_inside(100, 75, 200))   # inside 2nd floor (y=75)

            # Outside base
            self.assertFalse(is_inside(130, 64, 200))  # 30 blocks away
            self.assertFalse(is_inside(100, 30, 200))  # deep underground (y=30)

        asyncio.run(run())

    def test_detect_base_zone_with_workstations(self):
        async def run():
            self.agent.memory = {"waypoints": {}}
            mock_blocks = [
                BlockInfo(x=50, y=65, z=50, block="minecraft:red_bed", category="workstation", distance=1.0),
                BlockInfo(x=52, y=65, z=51, block="minecraft:crafting_table", category="workstation", distance=2.0),
                BlockInfo(x=51, y=65, z=49, block="minecraft:chest", category="container", distance=2.5),
            ]
            self.agent.client.get_surroundings = AsyncMock(return_value=SurroundingsResponse(blocks=mock_blocks))

            base_center, anchors, radius, is_inside = await self.agent._detect_base_zone("AlcyoneDX")
            self.assertEqual(base_center, (50, 65, 50))  # Bed pos takes priority
            self.assertEqual(len(anchors), 3)

            # Ore right under the bed (y=63)
            self.assertTrue(is_inside(50, 63, 50))
            # Ore outside house (80, 65, 50) -> 30 blocks away
            self.assertFalse(is_inside(80, 65, 50))

        asyncio.run(run())

    def test_mine_ores_house_protection_and_exit(self):
        async def run():
            self.agent.memory = {
                "waypoints": {
                    "ev": {"name": "Ev", "x": 100, "y": 64, "z": 100}
                }
            }
            # Player and companion inside house
            self.agent.client.get_status = AsyncMock(return_value=ServerStatusResponse(
                status="ok",
                version="1.21.1",
                world_time=1000,
                players=[PlayerStatus(name="AlcyoneDX", x=100.0, y=64.0, z=100.0, health=20.0, food_level=20)]
            ))
            self.agent.client.companion_status = AsyncMock(return_value=CompanionStatusResponse(
                spawned=True,
                position=Position(x=101.0, y=64.0, z=101.0)
            ))

            # Surroundings contains:
            # 1. Coal ore under the house floor (101, 62, 101) -> MUST BE PROTECTED!
            # 2. Iron ore outside the house (130, 64, 100) -> SAFE TO MINE!
            mock_blocks = [
                BlockInfo(x=101, y=62, z=101, block="minecraft:coal_ore", category="ore", distance=3.0),
                BlockInfo(x=130, y=64, z=100, block="minecraft:iron_ore", category="ore", distance=30.0),
            ]
            self.agent.client.get_surroundings = AsyncMock(return_value=SurroundingsResponse(blocks=mock_blocks))
            self.agent.client.companion_move = AsyncMock()
            self.agent.client.companion_interact = AsyncMock(return_value=MagicMock(success=True))
            self.agent.client.companion_follow = AsyncMock()
            self.agent.client.execute_command = AsyncMock()
            self.agent._say = AsyncMock()

            reply = await self.agent._mine_ores("AlcyoneDX", "1 demir kaz")

            # Verify:
            # 1. Jarvis announced exiting the house or started mining
            self.assertTrue(any("evin disina" in call.args[0].lower() for call in self.agent._say.call_args_list))
            # 2. Jarvis moved to exterior exit point (> 20 blocks from house)
            first_move_call = self.agent.client.companion_move.call_args_list[0]
            move_x = first_move_call.args[0]
            move_z = first_move_call.args[2]
            dist_from_house = math.hypot(move_x - 100, move_z - 100)
            self.assertGreaterEqual(dist_from_house, 23.0, "Exit target must be outside base perimeter!")

            # 3. Only iron ore at (130, 64, 100) was broken, NOT the coal under floor
            interact_calls = self.agent.client.companion_interact.call_args_list
            broken_positions = [(c.kwargs["x"], c.kwargs["y"], c.kwargs["z"]) for c in interact_calls if c.args[0] == "break_block"]
            self.assertIn((130, 64, 100), broken_positions)
            self.assertNotIn((101, 62, 101), broken_positions, "House floor ore was mined! Must be protected!")

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
