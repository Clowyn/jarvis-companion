# Project: Jarvis Minecraft Companion

## Architecture

The Jarvis Minecraft Companion consists of two primary layers connected over HTTP REST on `localhost:25585`:
1. **NeoForge In-Game Mod (Java 21, NeoForge 21.1.251)**:
   - Embedded Java 21 `HttpServer` with Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
   - Thread-safe bridging to Minecraft's tick loop via `ThreadHelper.supplyOnMain` / `MinecraftServer.execute` using `CompletableFuture`.
   - Telemetry & status reporting (`/api/status`), chat broadcasting (`/api/say`), circular chat buffer (`/api/chat`), command execution with captured output (`/api/command`).
   - World perception engine (`/api/surroundings`) with chunk section air skipping and common tag classification.
   - Dual Companion system: `JarvisCompanionEntity extends PathfinderMob` for physical collision, rendering, and `GroundPathNavigation` A* movement; `JarvisFakePlayer` (via `FakePlayerFactory`) for player block breaking/placing and container interactions; exposed via `/api/companion/*`.
2. **External Python Bridge & MCP Server (Python 3.12, MCP 2.x/1.x)**:
   - Async REST client (`jarvis_bridge/client.py`) using `httpx.AsyncClient` with Pydantic v2 schemas.
   - MCP Server (`jarvis_bridge/mcp_server.py`) exposing 10 typed AI tools over stdio.
3. **E2E Testing Track**:
   - Zero-dependency high-fidelity in-memory HTTP mock server (`tests/mock_server.py`) simulating port 25585.
   - 4 tiers of automated test suites (Tiers 1-4) plus Tier 5 adversarial coverage hardening.

```
       ┌────────────────────────────────────────────────────────┐
       │                 AI Agent / Client                     │
       └───────────────────────────┬────────────────────────────┘
                                   │ Stdio (MCP Protocol)
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │         Jarvis Bridge & MCP Server (Python 3.12)       │
       │   - 10 MCP Tools: status, chat, command, surroundings, │
       │     companion (spawn, move, interact, follow, status)  │
       │   - httpx.AsyncClient                                  │
       └───────────────────────────┬────────────────────────────┘
                                   │ HTTP REST (localhost:25585)
                                   ▼
 ┌────────────────────────────────────────────────────────────────────┐
 │              NeoForge 1.21.1 Mod (Jarvis) - JVM 21                │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │ Embedded HttpServer (Virtual Threads) on localhost:25585     │  │
 │  │  /api/status, /api/say, /api/chat, /api/command,             │  │
 │  │  /api/surroundings, /api/companion/*                         │  │
 │  └──────────────────────────────┬───────────────────────────────┘  │
 │                                 │ ThreadHelper (CompletableFuture) │
 │                                 ▼                                  │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │              MinecraftServer Main Tick Thread                │  │
 │  │  - World / Player Telemetry Snapshot                         │  │
 │  │  - CommandSourceStack Execution & Output Capture             │  │
 │  │  - Circular ChatHistory Buffer (AtomicInteger capped)        │  │
 │  │  - Pruned Surroundings Scanner (Tags, BlockEntity, Entities) │  │
 │  │  - JarvisCompanionEntity (PathfinderMob + Navigation)        │  │
 │  │  - JarvisFakePlayer (Block Break / Place / Container)        │  │
 │  └──────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────┘
```

## Feature Inventory

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Mod Lifecycle & Thread Helper | NeoForge mod registration, lifecycle events, `ThreadHelper` for non-blocking main-thread scheduling | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Embedded HTTP Server | Virtual-thread-backed `HttpServer` on port 25585 with clean start/stop | M1 | ORIGINAL_REQUEST §R1 |
| 3 | Server Telemetry API | `GET /api/status` returning world time, server health, player coordinates, health, food, dimension | M1 | ORIGINAL_REQUEST §R1 |
| 4 | Chat Broadcast API | `POST /api/say` safely broadcasting formatted colored chat messages | M1 | ORIGINAL_REQUEST §R1 |
| 5 | Chat History Buffer | `GET /api/chat` returning circular buffer of player messages and `!jarvis` triggers with zero memory leaks | M1 | ORIGINAL_REQUEST §R1 |
| 6 | Server Command API | `POST /api/command` executing commands on main thread, capturing exit code and feedback output | M1 | ORIGINAL_REQUEST §R1 |
| 7 | Surroundings Perception API | `GET /api/surroundings` scanning blocks (ores, chests, workstations) with chunk section pruning and entity scanning | M2 | ORIGINAL_REQUEST §R1 |
| 8 | Companion Entity Body | `JarvisCompanionEntity extends PathfinderMob` for physical presence, rendering, collision, and health | M3 | ORIGINAL_REQUEST §R2 |
| 9 | Companion Navigation | GroundPathNavigation A* navigation (`move_to`, `follow`, `stop`, `teleport`) | M3 | ORIGINAL_REQUEST §R2 |
| 10 | Companion World Actions | `JarvisFakePlayer` delegate for breaking blocks, placing blocks, and container interaction | M3 | ORIGINAL_REQUEST §R2 |
| 11 | Companion REST Endpoints | `POST /api/companion/spawn`, `GET /api/companion/status`, `POST /api/companion/despawn`, `POST /api/companion/action` | M3 | ORIGINAL_REQUEST §R2 |
| 12 | Async Python Bridge Client | `jarvis_bridge/client.py` using `httpx.AsyncClient` with Pydantic v2 schemas for all endpoints | M4 | ORIGINAL_REQUEST §R3 |
| 13 | MCP Server & Tool Definitions | `jarvis_bridge/mcp_server.py` exposing 10 typed AI tools with dual MCP 1.x/2.x compatibility | M4 | ORIGINAL_REQUEST §R3 |
| 14 | Bridge Package & Config | `bridge/pyproject.toml` and CLI entry point running with Python 3.12 | M4 | ORIGINAL_REQUEST §R3 |
| 15 | E2E Mock Server & Infra | In-memory `ThreadingHTTPServer` mock on port 25585 with state tracking and fault injection | E2E-Track | ORIGINAL_REQUEST §Acceptance |
| 16 | Tier 1 Feature Smoke Tests | >=5 test cases per feature verifying endpoints and MCP tools in isolation (>=75 tests) | E2E-Track | ORIGINAL_REQUEST §Acceptance |
| 17 | Tier 2 Boundary Tests | Boundary, negative, and error-handling tests (empty, overflow, invalid coords, offline server) | E2E-Track | ORIGINAL_REQUEST §Acceptance |
| 18 | Tier 3 Concurrency Tests | Concurrent request bursts, race conditions, chat buffer overflow, no CME, thread safety | E2E-Track | ORIGINAL_REQUEST §Acceptance |
| 19 | Tier 4 Real-World Workflows | Multi-step end-to-end scenarios (status -> surroundings -> move -> mine -> store -> say) | E2E-Track | ORIGINAL_REQUEST §Acceptance |
| 20 | Final Verification & Hardening | 100% pass of Tiers 1-4, followed by Tier 5 white-box adversarial coverage hardening | M5 (Final) | ORIGINAL_REQUEST §Acceptance |

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Mod Core, HTTP Server & Basic API | `ThreadHelper`, Virtual-Thread `HttpServer` (25585), `/api/status`, `/api/say`, `/api/chat`, `/api/command`, atomic `ChatHistory` | none | DONE |
| M2 | Surroundings Perception Engine | `GET /api/surroundings` with chunk section air pruning, `Tags.Blocks.ORES`, chests/barrels, item entity detail serialization, 500ms cache | M1 | DONE |
| M3 | Companion Entity & In-Game Actions | `JarvisCompanionEntity`, `JarvisFakePlayer`, path navigation, block break/place, container inspection, `/api/companion/*` | M1, M2 | PLANNED |
| M4 | Python Async Bridge & MCP Server | `jarvis_bridge` package, `httpx` async client, 10 MCP tools, dual v1/v2 compatibility, `pyproject.toml` | M1, M2, M3 | PLANNED |
| E2E | E2E Test Suite Track | `TEST_INFRA.md`, mock server, Tiers 1-4 test suites, `TEST_READY.md` | none (runs parallel) | DONE |
| M5 | Final Milestone: E2E Validation & Hardening | Phase 1: 100% pass of Tiers 1-4; Phase 2: Tier 5 white-box adversarial coverage hardening | M1, M2, M3, M4, E2E | PLANNED |

## Interface Contracts

### 1. HTTP Server API Contracts (`localhost:25585`)

#### `GET /api/status`
- **Response**:
  ```json
  {
    "status": "online",
    "version": "1.21.1",
    "world_time": 12345,
    "day_time": 6000,
    "player_count": 1,
    "players": [
      {
        "name": "Steve",
        "uuid": "...",
        "dimension": "minecraft:overworld",
        "health": 20.0,
        "max_health": 20.0,
        "food_level": 20,
        "position": {"x": 100.5, "y": 64.0, "z": -200.5}
      }
    ]
  }
  ```

#### `POST /api/say`
- **Request**: `{"message": "Hello world", "sender": "Jarvis"}`
- **Response**: `{"success": true, "broadcasted": true}`

#### `GET /api/chat?limit=50&since=0`
- **Response**:
  ```json
  {
    "count": 2,
    "messages": [
      {"id": 1, "player": "Steve", "message": "!jarvis status", "timestamp": 1726740000000, "is_command": true}
    ]
  }
  ```

#### `POST /api/command`
- **Request**: `{"command": "time set day"}`
- **Response**: `{"success": true, "output": ["Set the time to 1000"]}`

#### `GET /api/surroundings?player=Steve&radius=16`
- **Response**:
  ```json
  {
    "origin": {"x": 100.5, "y": 64.0, "z": -200.5},
    "radius": 16,
    "blocks": [
      {"pos": {"x": 102, "y": 60, "z": -198}, "block": "minecraft:diamond_ore", "type": "ore", "distance": 4.5}
    ],
    "entities": [
      {"id": 42, "type": "minecraft:zombie", "category": "monster", "pos": {"x": 98.0, "y": 64.0, "z": -195.0}, "distance": 6.1, "health": 20.0}
    ]
  }
  ```

#### `POST /api/companion/spawn`
- **Request**: `{"name": "Jarvis", "x": 100.5, "y": 64.0, "z": -200.5, "dimension": "minecraft:overworld"}`
- **Response**: `{"success": true, "companion_id": "jarvis-1", "position": {"x": 100.5, "y": 64.0, "z": -200.5}}`

#### `GET /api/companion/status`
- **Response**:
  ```json
  {
    "spawned": true,
    "companion_id": "jarvis-1",
    "name": "Jarvis",
    "dimension": "minecraft:overworld",
    "position": {"x": 100.5, "y": 64.0, "z": -200.5},
    "health": 20.0,
    "state": "idle",
    "target": null
  }
  ```

#### `POST /api/companion/action`
- **Polymorphic Actions**:
  - `{"action": "move_to", "x": 110, "y": 64, "z": -200, "speed": 1.0}`
  - `{"action": "follow", "player": "Steve", "distance": 3.0}`
  - `{"action": "stop"}`
  - `{"action": "teleport", "x": 100, "y": 64, "z": -200}`
  - `{"action": "attack", "entity_id": 42}`
  - `{"action": "break_block", "x": 102, "y": 60, "z": -198}`
  - `{"action": "place_block", "x": 102, "y": 61, "z": -198, "item": "minecraft:torch"}`
  - `{"action": "interact_block", "x": 105, "y": 64, "z": -200}`
  - `{"action": "inspect_container", "x": 105, "y": 64, "z": -200}`
- **Response**: `{"success": true, "action": "...", "status": "executed|navigating|failed", "details": {}}`

### 2. MCP Tools Specification (Python)
1. `minecraft_get_status()` -> Server & player telemetry
2. `minecraft_say(message: str, sender: str = "Jarvis")` -> Broadcast chat message
3. `minecraft_get_chat(limit: int = 50, since: int = 0)` -> Fetch chat history
4. `minecraft_execute_command(command: str)` -> Run server console command
5. `minecraft_get_surroundings(player: Optional[str] = None, radius: int = 16)` -> Perception scan
6. `minecraft_companion_spawn(name: str = "Jarvis", x: Optional[float] = None, y: Optional[float] = None, z: Optional[float] = None)` -> Spawn companion
7. `minecraft_companion_move(x: float, y: float, z: float, speed: float = 1.0)` -> Move companion
8. `minecraft_companion_follow(player: str, distance: float = 3.0)` -> Follow player
9. `minecraft_companion_interact(action: str, x: int, y: int, z: int, item: Optional[str] = None)` -> Block/container interaction
10. `minecraft_companion_status()` -> Query companion state & position

## Code Layout

### Java Mod (`src/main/java/com/alcyone/jarvis/`)
- `JarvisMod.java`: Mod entrypoint, FML lifecycle event handlers, registry setup.
- `util/ThreadHelper.java`: Thread-safe `CompletableFuture` scheduler over `MinecraftServer.execute`.
- `server/JarvisHttpServer.java`: Embedded `HttpServer` with virtual threads, route dispatcher.
- `server/handlers/StatusHandler.java`: `/api/status`
- `server/handlers/SayHandler.java`: `/api/say`
- `server/handlers/ChatHandler.java`: `/api/chat`
- `server/handlers/CommandHandler.java`: `/api/command`
- `server/handlers/SurroundingsHandler.java`: `/api/surroundings`
- `server/handlers/CompanionHandler.java`: `/api/companion/*`
- `chat/ChatHistory.java`: Circular atomic chat history buffer.
- `perception/SurroundingsScanner.java`: Chunk section pruning & tag-based block/entity scanner.
- `entity/JarvisCompanionEntity.java`: `PathfinderMob` companion entity.
- `entity/JarvisFakePlayer.java`: `FakePlayer` delegate for player-grade world mutations.
- `entity/CompanionManager.java`: Lifecycle manager for active companion instance.

### Python Bridge (`bridge/`)
- `pyproject.toml`: Package dependencies (`httpx`, `mcp`, `pydantic`).
- `jarvis_bridge/__init__.py`: Package init.
- `jarvis_bridge/client.py`: Async HTTP client (`httpx.AsyncClient`).
- `jarvis_bridge/models.py`: Pydantic models for all API requests and responses.
- `jarvis_bridge/mcp_server.py`: MCP tool definitions and stdio server runner.

### E2E Test Suite (`tests/`)
- `tests/mock_server.py`: In-memory `ThreadingHTTPServer` mock simulating port 25585.
- `tests/conftest.py`: Pytest fixtures and mock server lifecycle management.
- `tests/test_tier1_smoke.py`: Tier 1 Feature isolation smoke tests.
- `tests/test_tier2_boundary.py`: Tier 2 Boundary, negative, and error handling tests.
- `tests/test_tier3_concurrency.py`: Tier 3 Concurrency, burst load, and thread-safety tests.
- `tests/test_tier4_workflows.py`: Tier 4 Real-world multi-step AI companion workflows.
