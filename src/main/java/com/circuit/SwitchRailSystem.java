package com.circuit;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Switch Rails (Makas/Yön Değiştirici) - rails that change
 * the minecart direction based on circuit signal state.
 * 
 * States:
 * - Straight (default): Rail goes straight (North-South direction)
 * - Left: Rail curves to the left (using Corner_Left model)
 * - Right: Rail curves to the right (using Corner_Right model)
 * 
 * When powered by circuit signal:
 * - Power level 0: Straight
 * - Power toggles manually or via signal between Straight/Left/Right
 * 
 * Players can also manually cycle states using the Use interaction.
 */
public class SwitchRailSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-SwitchRail] ";

    private final CircuitPlugin plugin;
    private double accumulatedTime = 0.0;
    private static final double CHECK_INTERVAL = 0.1; // 10Hz check rate

    /**
     * Possible states for a switch rail.
     */
    public enum SwitchState {
        STRAIGHT,  // Default straight rail
        LEFT,      // Curves to the left
        RIGHT;     // Curves to the right

        public SwitchState next() {
            return switch (this) {
                case STRAIGHT -> LEFT;
                case LEFT -> RIGHT;
                case RIGHT -> STRAIGHT;
            };
        }

        public String toBlockState() {
            return switch (this) {
                case STRAIGHT -> "Straight";
                case LEFT -> "Left";
                case RIGHT -> "Right";
            };
        }

        public static SwitchState fromString(String state) {
            if (state == null) return STRAIGHT;
            return switch (state.toLowerCase()) {
                case "left" -> LEFT;
                case "right" -> RIGHT;
                default -> STRAIGHT;
            };
        }
    }

    /**
     * Data for a single switch rail.
     */
    public static class SwitchRailData {
        public final Vector3i position;
        public SwitchState currentState;
        public SwitchState poweredState;    // State to switch to when powered
        public SwitchState unpoweredState;  // State when unpowered
        public boolean wasPowered;          // Previous power state for edge detection
        public int rotationIndex;           // Block placement rotation

        public SwitchRailData(Vector3i position, int rotationIndex) {
            this.position = position;
            this.currentState = SwitchState.STRAIGHT;
            this.poweredState = SwitchState.LEFT;
            this.unpoweredState = SwitchState.STRAIGHT;
            this.wasPowered = false;
            this.rotationIndex = rotationIndex;
        }
    }

    // Registered switch rails
    private final Map<CircuitPos, SwitchRailData> switchRails = new ConcurrentHashMap<>();

    public SwitchRailSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Switch rails are event-driven (power changes trigger state updates)
        // No continuous polling needed
    }

    // ==================== State Management ====================

    /**
     * Update the switch rail state based on power level.
     * Called by EnergyPropagationSystem when power state changes.
     */
    public void updateSwitchState(Vector3i pos, int powerLevel) {
        CircuitPos cpos = CircuitPos.from(pos);
        SwitchRailData data = switchRails.get(cpos);
        if (data == null) return;

        boolean isPowered = powerLevel > 0;

        // Edge detection: Only change state on power state change
        if (isPowered != data.wasPowered) {
            data.wasPowered = isPowered;

            SwitchState newState;
            if (isPowered) {
                newState = data.poweredState;
            } else {
                newState = data.unpoweredState;
            }

            if (newState != data.currentState) {
                data.currentState = newState;
                updateBlockVisual(pos, data.currentState);
            }
        }
    }

    /**
     * Manually cycle the switch rail state (player interaction).
     * Cycles: Straight -> Left -> Right -> Straight
     */
    public void cycleState(Vector3i pos) {
        CircuitPos cpos = CircuitPos.from(pos);
        SwitchRailData data = switchRails.get(cpos);
        if (data == null) return;

        data.currentState = data.currentState.next();

        // Also update the powered/unpowered state mapping
        // When manually switching, the new state becomes the "powered" target
        data.poweredState = data.currentState;

        updateBlockVisual(pos, data.currentState);
    }

    /**
     * Get the current state of a switch rail.
     */
    public SwitchState getState(Vector3i pos) {
        SwitchRailData data = switchRails.get(CircuitPos.from(pos));
        return data != null ? data.currentState : SwitchState.STRAIGHT;
    }

    /**
     * Update the block's visual state in the world.
     */
    private void updateBlockVisual(Vector3i pos, SwitchState state) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;

            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
            BlockType blockType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());
            if (blockType != null) {
                chunkAccessor.setBlockInteractionState(pos, blockType, state.toBlockState());
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update switch rail visual at " + pos + ": " + e.getMessage());
        }
    }

    // ==================== Registration API ====================

    /**
     * Register a switch rail at the given position.
     */
    public void registerSwitchRail(Vector3i pos, int rotationIndex) {
        CircuitPos cpos = CircuitPos.from(pos);
        switchRails.put(cpos, new SwitchRailData(pos, rotationIndex));
    }

    /**
     * Unregister a switch rail at the given position.
     */
    public void unregisterSwitchRail(Vector3i pos) {
        CircuitPos cpos = CircuitPos.from(pos);
        switchRails.remove(cpos);
    }

    /**
     * Check if a switch rail exists at the given position.
     */
    public boolean isSwitchRailAt(Vector3i pos) {
        return switchRails.containsKey(CircuitPos.from(pos));
    }

    /**
     * Get the switch rail data at the given position.
     */
    public SwitchRailData getSwitchRailAt(Vector3i pos) {
        return switchRails.get(CircuitPos.from(pos));
    }

    /**
     * Get the power output of a switch rail (for circuit).
     * Switch rails do not output power - they are consumers.
     */
    public int getPowerOutput(Vector3i pos) {
        return 0;
    }

    // ==================== Persistence ====================

    private static final String SWITCH_RAILS_FILE = "switch_rails.dat";

    public void saveSwitchRails(Path dataDirectory) {
        if (switchRails.isEmpty()) return;

        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path filePath = dataDirectory.resolve(SWITCH_RAILS_FILE);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Map.Entry<CircuitPos, SwitchRailData> entry : switchRails.entrySet()) {
                    SwitchRailData data = entry.getValue();
                    writer.write(data.position.getX() + "," +
                            data.position.getY() + "," +
                            data.position.getZ() + "," +
                            data.currentState.name() + "," +
                            data.poweredState.name() + "," +
                            data.unpoweredState.name() + "," +
                            data.wasPowered + "," +
                            data.rotationIndex);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save switch rails: " + e.getMessage());
        }
    }

    public void loadSwitchRails(Path dataDirectory) {
        Path filePath = dataDirectory.resolve(SWITCH_RAILS_FILE);
        if (!Files.exists(filePath)) return;

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length < 8) continue;

                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    SwitchState current = SwitchState.valueOf(parts[3].trim());
                    SwitchState powered = SwitchState.valueOf(parts[4].trim());
                    SwitchState unpowered = SwitchState.valueOf(parts[5].trim());
                    boolean wasPow = Boolean.parseBoolean(parts[6].trim());
                    int rotation = Integer.parseInt(parts[7].trim());

                    Vector3i pos = new Vector3i(x, y, z);
                    SwitchRailData data = new SwitchRailData(pos, rotation);
                    data.currentState = current;
                    data.poweredState = powered;
                    data.unpoweredState = unpowered;
                    data.wasPowered = wasPow;

                    switchRails.put(CircuitPos.from(pos), data);
                } catch (Exception e) {
                    // Skip invalid lines
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load switch rails: " + e.getMessage());
        }
    }
}
