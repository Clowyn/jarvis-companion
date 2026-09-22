# Jarvis Minecraft Companion 🤖🎮

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.251-orange.svg)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.12-blue.svg)](https://python.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An intelligent, autonomous AI companion mod and bridge for **Minecraft 1.21.1 (NeoForge)** powered by Large Language Models (Google Gemini), Model Context Protocol (MCP), and an embedded virtual-thread REST API.

---

## 🌟 Key Features

### 1. In-Game Autonomous Companion Entity
- **Physical Companion Entity**: Custom `PathfinderMob` with 3D player-model rendering, real-time pathfinding navigation (`GroundPathNavigation`), combat assist, and player following.
- **Virtual Actions & FakePlayer**: Interacts with containers, places/breaks blocks, smelts, crafts, and farms via a thread-safe `JarvisFakePlayer`.

### 2. Smart Storage & Logistics Engine
- **NeoForge Capability Native**: Full `IItemHandler` integration supporting Vanilla Double Chests, Sophisticated Storage, Functional Storage, and Iron Chests.
- **Smart Category Sorting**: Automatically classifies items into Ores/Minerals, Building Blocks, Equipment/Tools, Crops/Farming, Mob Loot/Materials, Tech/Machinery, Magic/Alchemy, and Food.
- **Sign Recognition**: Reads wall/standing signs on single and double chests, reserving chests for specific categories.
- **Full Storage Block Support**: Minerals, crops, mob drops, and food blocks (e.g. Iron Block, Coal Block, Hay Block, Bone Block) automatically route to their parent categories.

### 3. Modpack & Tech Automation (FTB Direwolf20 1.21)
- Deep awareness and recipe searching for major mod ecosystems:
  - **Create Mod**: Kinetic network monitoring, Stress Units (SU), RPM speed/rotation detection.
  - **Mekanism, AE2, Refined Storage, Powah, JustDireThings, Industrial Foregoing, EnderIO, Thermal**.
- **Spelunking & Ore Guidance**: Finds nearby veins, evaluates threat levels, and guides the player.
- **Lumberjack & Agriculture**: Automates tree harvesting, replanting, and crop farming.

### 4. Dual Communication & Perception Layer
- **Embedded HTTP REST Server**: Runs inside Minecraft on port `25585` using Java 21 Virtual Threads (`newVirtualThreadPerTaskExecutor`).
- **Cloudflare Tunnel Support**: Exposes the REST API securely through Cloudflare tunnels for remote AI control.
- **MCP Server (Model Context Protocol)**: Exposes 10 typed AI tools (`minecraft_get_status`, `minecraft_say`, `minecraft_get_chat`, `minecraft_execute_command`, `minecraft_get_surroundings`, `minecraft_companion_spawn`, `minecraft_companion_move`, `minecraft_companion_follow`, `minecraft_companion_interact`, `minecraft_companion_status`).
- **Python Bridge Agent**: Real-time two-way in-game chat listener with conversational memory and personality.

---

## 🏗️ Architecture

```
       ┌────────────────────────────────────────────────────────┐
       │             AI Agent (Gemini / Claude / MCP)          │
       └───────────────────────────┬────────────────────────────┘
                                   │ Stdio (MCP Protocol)
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │         Jarvis Bridge & MCP Server (Python 3.12)       │
       │   - 10 Typed MCP Tools                                 │
       │   - In-game Chat Agent & Memory                        │
       │   - httpx.AsyncClient / REST API client                │
       └───────────────────────────┬────────────────────────────┘
                                   │ HTTP REST (localhost:25585 or Tunnel)
                                   ▼
 ┌────────────────────────────────────────────────────────────────────┐
 │              NeoForge 1.21.1 Mod (Jarvis) - Java 21               │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │ Embedded HttpServer (Virtual Threads) on localhost:25585     │  │
 │  │  /api/status, /api/say, /api/chat, /api/command,             │  │
 │  │  /api/surroundings, /api/companion/*, /api/container/*       │  │
 │  └──────────────────────────────┬───────────────────────────────┘  │
 │                                 │ ThreadHelper (CompletableFuture) │
 │                                 ▼                                  │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │              MinecraftServer Main Tick Thread                │  │
 │  │  - World / Player Telemetry Snapshot                         │  │
 │  │  - CommandSourceStack Execution & Output Capture             │  │
 │  │  - StorageManager (Capability IItemHandler)                  │  │
 │  │  - JarvisCompanionEntity (PathfinderMob + Navigation)        │  │
 │  │  - JarvisFakePlayer (Block / Container Interaction)          │  │
 │  └──────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites
- **Java Development Kit (JDK) 21**
- **Minecraft 1.21.1** with **NeoForge 21.1.251+**
- **Python 3.12+** with `uv` or `pip`

### 1. Building the Minecraft Mod
```bash
# Clone the repository
git clone https://github.com/Clowyn/jarvis-companion.git
cd jarvis-companion

# Build the mod jar
./gradlew build
```
The compiled mod jar will be in `build/libs/jarvis-1.0.0.jar`. Place it into your Minecraft `mods` folder.

### 2. Setting Up the Python Bridge
```bash
cd bridge

# Copy the example environment file and add your GEMINI_API_KEY
cp .env.example .env

# Install dependencies using uv or pip
uv sync
# OR: pip install -e .

# Run the live in-game chat agent
python -m jarvis_bridge.chat_agent
```

### 3. Running MCP Server
To register Jarvis as an MCP tool server for Claude Desktop or Antigravity:
```json
{
  "mcpServers": {
    "jarvis-minecraft": {
      "command": "python",
      "args": ["-m", "jarvis_bridge.mcp_server"],
      "cwd": "/path/to/jarvis-companion/bridge"
    }
  }
}
```

---

## 🧪 Testing

The repository includes a comprehensive 5-tier test harness for both Java and Python layers:
- **Java Unit & Contract Tests**:
  ```bash
  ./gradlew test
  ```
- **Python E2E & Mock REST Tests**:
  ```bash
  pytest
  ```

---

## 📄 License

This project is licensed under the MIT License.
