# E2E Test Infra: Jarvis Minecraft Companion

## Test Philosophy
- Opaque-box, requirement-driven. Tests verify external observable behavior (REST endpoints on port 25585 and Python MCP tools) without relying on internal private state.
- Methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinatorial Testing + Real-World Workload Testing.
- Dual Mode Execution:
  1. Mock Mode: High-fidelity in-memory `ThreadingHTTPServer` (`tests/mock_server.py`) for rapid, deterministic sub-second test runs in CI / local dev.
  2. Live Mode: Running against a live NeoForge server running the built `jarvis-1.0.0.jar`.

## Feature Inventory & Test Mapping

| # | Feature | Requirement | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---|---------|-------------|:------:|:------:|:------:|:------:|
| 1 | Server Telemetry (`/api/status`) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 2 | Chat Broadcast (`/api/say`) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 3 | Chat History (`/api/chat`) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 4 | Command Execution (`/api/command`) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 5 | Surroundings Perception (`/api/surroundings`) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 6 | Companion Spawning (`/api/companion/spawn`) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 7 | Companion Status (`/api/companion/status`) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 8 | Companion Movement (`action: move_to/follow/stop`) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 9 | Companion Block Interaction (`action: break/place`) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 10 | Companion Container (`action: inspect_container`) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 11 | Python Async Bridge Client | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| 12 | MCP Tool Definitions (10 tools) | ORIGINAL_REQUEST §R3 | 10 | 10 | ✓ | ✓ |

## Test Architecture
- Test runner: `pytest` executed via `uv run --python 3.12 --with pytest,pytest-asyncio,httpx,mcp,pydantic pytest tests -v`
- Pass/fail semantics: Exit code 0, 100% test pass rate.
- Directory layout:
  ```
  tests/
  ├── conftest.py               # Fixtures, test server lifecycle
  ├── mock_server.py            # High-fidelity mock server for port 25585
  ├── test_tier1_smoke.py       # Tier 1 Feature coverage tests
  ├── test_tier2_boundary.py    # Tier 2 Boundary & negative tests
  ├── test_tier3_concurrency.py # Tier 3 Concurrency & load tests
  └── test_tier4_workflows.py   # Tier 4 Real-world AI companion workflows
  ```

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | AI Survey & Chat Assistance | Status, Surroundings, Say, Chat | Medium |
| 2 | Companion Mining Expedition | Spawn, Move, Surroundings, Break Block, Status | High |
| 3 | Companion Base Construction | Spawn, Move, Place Block, Status | High |
| 4 | Autonomous Chest Sorter | Move, Inspect Container, Interact Block | High |
| 5 | Emergency Player Escort | Status (low health detection), Say, Companion Follow, Attack | High |
| 6 | Player Chat Command Dispatch (`!jarvis`) | Chat buffer polling, parsing trigger, Command execution, Say | Medium |
| 7 | Full MCP Tool Agent Session | Invoking all 10 MCP tools sequentially in an autonomous agent session | High |
| 8 | Server Reconnect & Resilience | Server restart simulation, client reconnect, state recovery | High |

## Coverage Thresholds
- Tier 1: ≥5 per feature (Total ≥ 65 tests)
- Tier 2: ≥5 per feature (Total ≥ 65 tests)
- Tier 3: Pairwise and concurrency stress (Total ≥ 15 tests)
- Tier 4: ≥8 realistic application scenarios
- Total Target: ≥ 150 automated test cases
