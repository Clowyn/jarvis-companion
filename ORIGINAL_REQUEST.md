# Original User Request

## Initial Request — 2026-09-19T10:23:41Z

Build an end-to-end Minecraft NeoForge 1.21.1 companion mod ("Jarvis") and bridge service that enables an external AI agent to interact with players, perceive world surroundings, and execute in-game actions.

Working directory: D:\jarvis-companion
Integrity mode: development

## Requirements

### R1. In-Game NeoForge Mod & Embedded API
- Develop a NeoForge 1.21.1 mod (targeting NeoForge 21.1.251 and Java 21) that runs an embedded HTTP/WebSocket server on `localhost:25585`.
- Provide REST endpoints for:
  - `GET /api/status`: Returns server health, world time, and player telemetry (position, health, food, dimension).
  - `POST /api/say`: Broadcasts colored and formatted messages to in-game chat.
  - `GET /api/chat`: Returns buffered in-game chat messages.
  - `POST /api/command`: Executes in-game server commands safely on the main tick thread.
  - `GET /api/surroundings`: Scans nearby blocks (ores, chests, machines) and living entities around a target player.

### R2. Companion Entity / In-Game Actions
- Implement controllable in-game companion actions (such as spawning a companion entity/FakePlayer, navigation, and block interaction).
- Ensure all world and entity mutations are safely scheduled on Minecraft's main server thread (`MinecraftServer.execute`).

### R3. External Bridge & MCP Server
- Implement an external bridge client (Python) that connects to the in-game API.
- Provide MCP (Model Context Protocol) tool definitions so an AI agent can perceive the game state and issue commands in real time.

## Acceptance Criteria

### Build & Compilation
- [ ] The mod compiles cleanly with `./gradlew build` without compiler or dependency errors, producing a valid `.jar` in `build/libs/`.

### API & Network Functionality
- [ ] The embedded server starts upon world/server load on port 25585.
- [ ] `GET /api/status` returns valid JSON with player coordinates, health, and server time.
- [ ] `POST /api/say` successfully broadcasts messages to in-game chat.
- [ ] `GET /api/chat` records player chat messages (including `!jarvis` triggers) without memory leaks.
- [ ] `GET /api/surroundings` returns a filtered list of blocks of interest and nearby entities.

### Concurrency & Stability
- [ ] All game-modifying actions are dispatched on the main server thread without throwing `ConcurrentModificationException` or freezing the server tick loop.

## Follow-up — 2026-09-19T15:12:31Z

Continue building the Jarvis Minecraft Companion mod and bridge. Milestones 0 (Architecture), 1 (Core Mod & HTTP API), 2 (Surroundings Perception), and E2E Test Track are **already complete** with 212+ passing tests. Resume from where the previous team left off and complete the remaining milestones.

Working directory: D:\jarvis-companion
Integrity mode: development

## Existing Codebase Context

**CRITICAL: Read these files first before writing any code:**
- `D:\jarvis-companion\PROJECT.md` — Full architecture, interface contracts, feature inventory, code layout
- `D:\jarvis-companion\CHECKPOINT.md` — Detailed status of what's done and what remains
- `D:\jarvis-companion\TEST_INFRA.md` — Testing strategy and tier definitions
- All existing Java sources in `src/main/java/com/alcyone/jarvis/` (14 files)
- All existing Python tests in `tests/` (12 files)

**Already working and tested (DO NOT break or rewrite):**
- `JarvisMod.java` — Mod entrypoint with lifecycle events
- `ThreadHelper.java` — CompletableFuture scheduler for main tick thread
- `JarvisHttpServer.java` — Virtual-thread HTTP server on port 25585
- `StatusHandler`, `SayHandler`, `ChatHandler`, `CommandHandler` — Working REST handlers
- `SurroundingsHandler`, `SurroundingsScanner`, `BlockClassifier`, `PerceptionCache` — Perception engine
- `ChatHistory.java` — Atomic circular buffer
- `CompanionHandler.java` — Skeleton exists, needs full implementation
- All existing test files — Must continue to pass

**The project uses:** NeoForge 1.21.1 (21.1.251), Java 21 toolchain (Gradle manages this), `net.neoforged.moddev` Gradle plugin, embedded `com.sun.net.httpserver.HttpServer`.

## Requirements

### R1. Companion Entity & In-Game Actions (Milestone 3)

Implement a controllable in-game companion that an external AI agent can spawn, navigate, and use to interact with the Minecraft world. The companion must have a physical in-game presence (visible to players, has collision, can take damage) and be able to perform player-like actions (break/place blocks, open containers). All world mutations must be safely dispatched on Minecraft's main server thread via the existing `ThreadHelper`.

The companion REST endpoints on the existing HTTP server (`/api/companion/*`) must conform to the interface contracts already defined in `PROJECT.md`:
- `POST /api/companion/spawn` — Spawn the companion at given coordinates
- `GET /api/companion/status` — Query companion state and position
- `POST /api/companion/despawn` — Remove the companion
- `POST /api/companion/action` — Polymorphic action dispatch (move_to, follow, stop, teleport, attack, break_block, place_block, interact_block, inspect_container)

### R2. Python Async Bridge & MCP Server (Milestone 4)

Build an external Python bridge package (`bridge/jarvis_bridge/`) that connects to the in-game HTTP API and exposes 10 MCP (Model Context Protocol) tools so an AI agent can perceive and control the game in real time. The bridge must use async HTTP (`httpx.AsyncClient`), Pydantic v2 for request/response schemas, and provide a stdio-based MCP server runner. The 10 tools are defined in `PROJECT.md` section "MCP Tools Specification".

### R3. Final Build & Test Validation (Milestone 5)

The entire mod must compile cleanly with `./gradlew build` producing a valid `.jar`. All existing tests (Tiers 1-4, challenger tests, stress tests) must continue to pass. New tests must be added for the companion endpoints and MCP tools.

## Acceptance Criteria

### Build & Compilation
- [ ] `./gradlew build` completes without errors and produces a valid JAR in `build/libs/`
- [ ] All pre-existing Java test classes still compile and pass (no regressions)

### Companion Entity (R1)
- [ ] A companion entity spawns at requested coordinates via `POST /api/companion/spawn` and is visible in-game
- [ ] `GET /api/companion/status` returns valid JSON with position, health, state, dimension
- [ ] `POST /api/companion/action` with `move_to` initiates pathfinding navigation
- [ ] `POST /api/companion/action` with `follow` tracks the specified player
- [ ] `POST /api/companion/action` with `break_block`/`place_block` modifies the world safely on the main thread
- [ ] `POST /api/companion/despawn` removes the companion entity cleanly
- [ ] No `ConcurrentModificationException` or server tick freeze under normal operation

### Python Bridge & MCP (R2)
- [ ] `bridge/jarvis_bridge/client.py` provides async methods for all 7 REST endpoints
- [ ] `bridge/jarvis_bridge/mcp_server.py` exposes 10 MCP tools matching the specification in PROJECT.md
- [ ] `bridge/jarvis_bridge/models.py` contains Pydantic v2 models for all request/response schemas
- [ ] `bridge/pyproject.toml` declares correct dependencies (httpx, mcp, pydantic)
- [ ] The MCP server can be started and lists all 10 tools without errors

### Test Coverage (R3)
- [ ] New Python tests cover companion spawn/despawn/action/status endpoints via mock server
- [ ] New Python tests cover all 10 MCP tools via mock server
- [ ] All Tier 1-4 tests pass including new companion and MCP tool tests
- [ ] `pytest tests/` runs clean with 0 failures

## Directive — 2026-09-19T15:44:38Z

USER DIRECTIVE: Once Milestone 3 (Companion Entity & In-Game Actions) verification gate passes and M3 is fully complete, STOP all execution and do NOT proceed to Milestone 4. Leave a clean checkpoint and conclude.

## 2026-09-19T16:11:56Z

Complete comprehensive review and verification of the Jarvis Minecraft NeoForge 1.21.1 Companion Mod and Python Bridge at D:\jarvis-companion.

Working directory: D:\jarvis-companion
Integrity mode: development

## Requirements

### R1. Java Mod Build & Deployment Verification
- Verify `./gradlew.bat build` succeeds without compiler errors.
- Confirm that `build/libs/jarvis-1.0.0.jar` is deployed to:
  `C:\Users\Alcyone\AppData\Local\.ftba\instances\ftb presents direwolf20 121(2)\mods\jarvis-1.0.0.jar`

### R2. Python Async Bridge & MCP Server Verification
- Verify `bridge/jarvis_bridge/` contains `client.py`, `models.py`, `mcp_server.py`.
- Verify all 10 MCP tools (`minecraft_get_status`, `minecraft_say`, `minecraft_get_chat`, `minecraft_execute_command`, `minecraft_get_surroundings`, `minecraft_companion_spawn`, `minecraft_companion_move`, `minecraft_companion_follow`, `minecraft_companion_interact`, `minecraft_companion_status`) are registered and functional.

### R3. Test Suite Verification
- Verify that `tests/test_bridge_client.py` and `tests/test_mcp_server.py` pass cleanly.

## Acceptance Criteria
- [ ] Java source classes compile cleanly with `./gradlew.bat build`.
- [ ] All 10 MCP tools are functional.
- [ ] All tests in `tests/` pass with 0 failures.
- [ ] JAR file is deployed to the user's mod folder.

