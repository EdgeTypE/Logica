# Contributing to Logica

First off, thanks for taking the time to contribute to Logica!

Logica (Circuit Mod) is an open-source mod for Hytale that brings complex redstone-like circuitry, automation, and logic systems to the game. We believe in building this together with the community.

## Getting Started

### Prerequisites
*   **Java JDK**: Ensure you have a Java development kit compatible with Hytale.
*   **Hytale Client/Server**: You need the Hytale client or server to run the mod.

### Building the Project
The project uses Gradle for dependency management and building.

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/EdgeTypE/Logica.git
    cd Logica
    ```

2.  **Build with Gradle:**
    On Windows:
    ```powershell
    .\gradlew build
    ```
    On macOS/Linux:
    ```bash
    ./gradlew build
    ```

3.  **Deploy:**
    The built JAR file will be located in `build/libs/`. Copy this JAR to your Hytale `mods` directory.

## Project Structure

The codebase is organized into several key systems located in `src/main/java/com/circuit/`. Here is a quick map to help you navigate:

### Core Systems
*   **`CircuitPlugin.java`**: The main entry point. Handles initialization, event registration, and global state (wires, levers).
*   **`CircuitSystem.java`**: Intended for central circuit ticking logic.
*   **`CircuitComponent.java`**: The primary ECS component holding state data (energy level, active state) for circuit blocks.
*   **`CircuitPos.java`**: A custom wrapper for Vector3i to ensure safe HashMap keys.

### Logic & Propagation
*   **`EnergyPropagationSystem.java`**: Handles the recursive propagation of power through wires and blocks.
*   **`GateSystem.java`**: Manages logic gates (AND, OR, NAND, etc.).
*   **`RepeaterSystem.java`**: Handles repeater delays and locking.

### Devices
*   **`PistonSystem.java`**: Logic for Pistons and Sticky Pistons (push/pull mechanics).
*   **`ObserverSystem.java`**: Handles block update detection.
*   **`ButtonSystem.java` / `LeverSystem`**: Input devices.
*   **`LampSystem.java`**: Visual feedback blocks.

### Pipes & Automation
*   **`PipeSystem.java`**: Handles Item Transport pipes (push/pull logic).
*   **`VacuumSystem.java`**: Handles Vacuum pipes that collect dropped items.
*   **`FloatingItemSystem.java`**: Custom physics for items in pipes/water.

## Development Guidelines

### Code Style
*   We use standard Java naming conventions (CamelCase).
*   Please keep code readable and well-commented.
*   Avoid "magic numbers" and hardcoded strings where possible; use constants.

### Architecture Notes
*   **Hybrid ECS**: We use a mix of Hytale's Entity Component System and traditional object caching. Be mindful of where state is stored.
*   **Performance**: Avoid O(N) operations in `tick()` methods. If adding a new system, consider if it needs to run every tick or can be event-driven.

## How to Contribute

1.  **Fork the Project**: Create your own fork on GitHub.
2.  **Create a Branch**: `git checkout -b feature/AmazingNewFeature`
3.  **Commit your Changes**: `git commit -m 'Add some AmazingNewFeature'`
4.  **Push to the Branch**: `git push origin feature/AmazingNewFeature`
5.  **Open a Pull Request**: Submit your PR for review.

## Reporting Bugs

Found a bug? Please open an issue on our [Issue Tracker](https://github.com/EdgeTypE/Logica/issues).
Include:
*   A clear description of the bug.
*   Steps to reproduce.
*   Screenshots or GIFs if applicable using the mod.
*   Logs if available.

## License

This project is licensed under the Apache License 2.0 - see the `LICENSE` file for details.
