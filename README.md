# Logica

Logica is a technical mod for Hytale that introduces advanced logical circuitry and redstone-like mechanics. Drawing inspiration from Minecraft's Redstone, Logica aims to offer a familiar yet expanded experience for veteran players, adapted to fit Hytale's unique environment.

The mod introduces a variety of components for automation and logic, including wires, gates, sensors, and item transport systems.

**[Download on CurseForge](https://www.curseforge.com/hytale/mods/logica)**

## Features

*   **Core Components:** Copper Wire (with decay), Golden Wire (lossless), Repeaters, Levers, Buttons, and Lamps.
*   **Logic & Automation:** Pistons, Sticky Pistons, and Observers for block update detection.
*   **Item Transport:** Pipes for transferring items and Vacuum Pipes for collecting dropped items.
*   **Crafting:** A dedicated **Engineer's Workbench** for creating all Logica components.

![gif](https://media.forgecdn.net/attachments/1508/616/logicagif-gif.gif)


## Requirements

*   **Hytale** (Game Client/Server)
*   **Java Runtime Environment** (Compatible with your Hytale version)

## Build & Installation

To build the project and deploy it to your Hytale mods folder, use the following PowerShell command:

```powershell
.\gradlew build; if ($LASTEXITCODE -eq 0) { Copy-Item -Path "build\libs\CircuitMod-0.1.1.jar" -Destination "$env:APPDATA\Hytale\UserData\Mods\CircuitMod-0.1.1.jar" -Force; Write-Host "JAR deployed!" }
```

This command acts as a "Hot Reload" script:
1.  Compiles the mod using Gradle.
2.  If the build is successful (`$LASTEXITCODE -eq 0`), it copies the resulting JAR to the Hytale mods directory.
3.  Prints "JAR deployed!" upon success.

## Roadmap & Priorities

The following is a prioritized list of tasks and known issues that act as the current development roadmap.

### Priority 1: Save System Overhaul
Currently, the mod saves circuit data (wires, gate states, etc.) into a separate file rather than embedding it into the world save. This is a temporary workaround that causes synchronization and persistence issues.
*   **Goal:** Refactor the storage logic to use Hytale's native `BlockState` persistence or NBT equivalents.
*   **Impact:** Ensures circuits are saved/loaded correctly with the world chunk data, removing reliance on external file management in `CircuitPlugin`.

### Priority 2: Visual & Aesthetic Updates
The current textures and block models (wires, gates) are placeholders and look primitive.
*   **Goal:** Create a consistent, high-quality visual style that feels native to Hytale.
*   **Req:** Standardize block models and texture resolutions across all components.

### Priority 3: Observer Logic
The `ObserverSystem` does not consistently trigger on all block state changes.
*   **Goal:** Ensure the Observer reliably detects updates from adjacent blocks, including subtle state changes (e.g., crop growth, moisture change).

### Priority 4: Vertical Placement
Components like **Pistons**, **Sticky Pistons**, and **Observers** currently lack full orientation support.
*   **Goal:** Implement logic to allow these blocks to be placed vertically (facing Up/Down).

### Priority 5: Lore-Friendly Energy Sources
We need power sources that fit the Hytale theme.
*   **Goal:** Design and implement energy sources (e.g., magical crystals, wind, or kinetic dynamos) that integrate naturally with the game's lore, rather than generic battery blocks.

### Priority 6: Gate System Rewrite
The current gate implementation is fragmented. References to gate logic exist in both `CircuitSystem.java` and `GateSystem.java`, leading to redundant or conflicting code.
*   **Goal:** Complete rewrite of the logic gate architecture.
*   **Design:** Implement a generic Gate system where every gate instance supports **X Inputs** and **1 Output**. This should be a modular system rather than hardcoded cases for every gate type.

### Priority 7: Pipe UI & Filtering
Pipes currently function as basic item transports without user feedback.
*   **Goal:** Add a UI menu for Pipe blocks.
*   **Features:** Display the item currently inside, show connected inventories, and eventually implement Whitelist/Blacklist filtering.

### Priority 8: Energy Propagation Stability
The `EnergyPropagationSystem.java` (specifically how `recalculateBlock` handles neighbors) is unstable.
*   **Issue:** Placing a button on a block often fails to power the component directly behind that block ("strong power" transmission).
*   **Goal:** Refactor the propagation algorithm to ensure solid blocks correctly transmit power from listeners (buttons/levers) to adjacent circuit components.

### Priority 9: Recipe Balancing
Current crafting recipes are temporary placeholders.
*   **Goal:** Redesign recipes to be balanced for survival gameplay, utilizing appropriate resource tiers.

---

### Additional Notes
*   **Scripts:** The `script/` directory contains Python scripts originally used to generate wire block models. These are likely no longer needed but are preserved for reference.

---

## Developer Note

I have been working on Logica almost since Hytale's launch. This is not the final version; think of it as an "Early Access mod for an Early Access game." Waiting for perfection can delay a project indefinitely, so I decided to release the mod in its current state to gather feedback and improve it alongside the community.

I believe we can turn this into a community-driven project to build the ultimate logic system for Hytale together.

Feedback, bug reports, and contributions are highly appreciated!


| Latest Release | Details |
| :--- | :--- |
| **Version** | `0.1.1` |
| **Date** | Feb 5, 2026 |
| **Changes** | - Added **Light Sensor**<br>- Fixed **Wire connection visuals** |
