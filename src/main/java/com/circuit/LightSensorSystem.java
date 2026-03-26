package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.io.*;
import java.nio.file.*;

/**
 * Light Sensor System - Detects sunlight levels and emits signals based on
 * day/night cycle.
 * 
 * Design:
 * - Checks sunlight level at its position every 100 ticks (5 seconds at 20 TPS)
 * - Uses WorldTimeResource.getSunlightFactor() which changes with time of day
 * - If sunlight factor is above threshold (default: 0.5), emits power signal
 * (15)
 * - If sunlight factor is at or below threshold, no signal (0)
 */
public class LightSensorSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    private static final String SAVE_FILE = "light_sensors.dat";

    // Check interval in game ticks (100 ticks = 5 seconds at 20 TPS)
    private static final int CHECK_INTERVAL_TICKS = 100;

    // Default sunlight factor threshold (0.0-1.0 scale, 0.5 is roughly dawn/dusk)
    private static final double DEFAULT_THRESHOLD = 0.5;

    // Tick interval (20 TPS = 0.05 seconds per tick)
    private static final double TICK_INTERVAL = 0.05;

    private final CircuitPlugin plugin;

    // Track light sensor positions and their thresholds (0.0-1.0)
    private final Map<Vector3i, Double> sensorThresholds = new HashMap<>();

    // Track current powered state for each sensor
    private final Map<Vector3i, Boolean> sensorPowered = new HashMap<>();

    // Accumulated time for tick counting
    private double accumulatedTime = 0.0;

    // Current tick counter
    private int tickCounter = 0;

    public LightSensorSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        accumulatedTime += dt;

        // Process ticks at fixed rate
        while (accumulatedTime >= TICK_INTERVAL) {
            accumulatedTime -= TICK_INTERVAL;
            tickCounter++;

            // Only check light levels every CHECK_INTERVAL_TICKS
            if (tickCounter >= CHECK_INTERVAL_TICKS) {
                tickCounter = 0;
                checkAllSensors(store);
            }
        }
    }

    /**
     * Check sunlight levels for all registered sensors.
     * Uses WorldTimeResource.getSunlightFactor() which varies with day/night cycle.
     */
    private void checkAllSensors(Store<EntityStore> store) {
        if (sensorThresholds.isEmpty()) {
            return;
        }

        World world = null;
        try {
            Universe universe = Universe.get();
            if (universe == null)
                return;
            world = universe.getDefaultWorld();
            if (world == null)
                return;
        } catch (Exception e) {
            return;
        }

        final World finalWorld = world;
        final Store<EntityStore> finalStore = store;

        // Process on world thread for thread safety
        world.execute(() -> {
            try {
                // Get the current sunlight factor from WorldTimeResource (inside world thread)
                double sunlightFactor = getSunlightFactor(finalStore);

                for (Map.Entry<Vector3i, Double> entry : sensorThresholds.entrySet()) {
                    Vector3i pos = entry.getKey();
                    double threshold = entry.getValue();

                    // Check if sunlight is above threshold
                    boolean shouldBePowered = sunlightFactor > threshold;
                    boolean wasPowered = sensorPowered.getOrDefault(pos, false);

                    if (shouldBePowered != wasPowered) {
                        sensorPowered.put(pos, shouldBePowered);
                        updateSensorVisual(pos, shouldBePowered);

                        // Notify energy system to propagate power changes
                        if (plugin.getEnergySystem() != null) {
                            plugin.getEnergySystem().updateNetwork(pos);
                        }

                        // Also directly power adjacent non-wire blocks (lamps, pistons, etc.)
                        powerAdjacentBlocks(pos, shouldBePowered);

                        // Force update of adjacent blocks through energy system
                        forceUpdateAdjacentBlocks(pos);

                        // LOGGER.atInfo().log(PREFIX + "[LightSensor] " + pos + " sunlight=" +
                        // String.format("%.2f", sunlightFactor) +
                        // " threshold=" + String.format("%.2f", threshold) +
                        // " -> " + (shouldBePowered ? "ON" : "OFF"));
                    }
                }
            } catch (Exception e) {
                LOGGER.atWarning().log(PREFIX + "[LightSensor] Check failed: " + e.getMessage());
            }
        });
    }

    /**
     * Get the current sunlight factor from WorldTimeResource.
     * Returns a value between 0.0 (night) and 1.0 (day).
     */
    private double getSunlightFactor(Store<EntityStore> store) {
        try {
            if (store == null) {
                return 0.0;
            }

            ResourceType<EntityStore, WorldTimeResource> timeResourceType = WorldTimeResource.getResourceType();
            WorldTimeResource timeResource = store.getResource(timeResourceType);

            if (timeResource != null) {
                return timeResource.getSunlightFactor();
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[LightSensor] Failed to get sunlight factor: " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * Get the combined light level at a position (legacy method, kept for
     * reference).
     * Combines sky light and block light, returning the maximum.
     */
    private int getLightLevelAt(World world, Vector3i pos) {
        try {
            // Calculate chunk coordinates (right shift by 5 is equivalent to dividing by
            // 32)
            int chunkX = pos.getX() >> 5;
            int chunkZ = pos.getZ() >> 5;
            long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

            WorldChunk chunk = world.getChunkIfLoaded(chunkKey);
            if (chunk == null) {
                return 0;
            }

            BlockChunk blockChunk = chunk.getBlockChunk();
            if (blockChunk == null) {
                return 0;
            }

            // Get local coordinates within chunk (0-31)
            int localX = pos.getX() & 31;
            int localZ = pos.getZ() & 31;
            int y = pos.getY();

            // Get sky light and block light
            byte skyLight = blockChunk.getSkyLight(localX, y, localZ);
            byte blockLight = blockChunk.getBlockLightIntensity(localX, y, localZ);

            // Return the maximum of the two (like Minecraft's combined light level)
            return Math.max(skyLight & 0xFF, blockLight & 0xFF);

        } catch (Exception e) {
            LOGGER.atWarning()
                    .log(PREFIX + "[LightSensor] Failed to get light level at " + pos + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Update the visual state of a light sensor block.
     */
    private void updateSensorVisual(Vector3i pos, boolean powered) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return;

            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            BlockType sensorType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());
            if (sensorType == null || !sensorType.getId().contains("Circuit_Light_Sensor")) {
                return;
            }

            // Get current rotation to preserve it
            int rotationIndex = chunkAccessor.getBlockRotationIndex(pos.getX(), pos.getY(), pos.getZ());

            String targetState = powered ? "On" : "Off";

            try {
                // Method 1: setBlockInteractionState first
                chunkAccessor.setBlockInteractionState(pos, sensorType, targetState);
                // LOGGER.atInfo().log(PREFIX + "[LightSensor] setBlockInteractionState at " +
                // pos + " -> " + targetState);

                // Method 2: Force block replacement to trigger texture update
                chunkAccessor.setBlock(pos.getX(), pos.getY(), pos.getZ(), "Circuit_Light_Sensor", rotationIndex);

                // Method 3: Set state again after replacement
                BlockType newBlockType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());
                if (newBlockType != null) {
                    chunkAccessor.setBlockInteractionState(pos, newBlockType, targetState);
                    // LOGGER.atInfo().log(PREFIX + "[LightSensor] Final state set at " + pos + " ->
                    // " + targetState);
                }

            } catch (Exception e) {
                LOGGER.atWarning().log(PREFIX + "[LightSensor] State update failed: " + e.getMessage());

                // Fallback: Try multiple state names
                try {
                    String[] stateNames = { targetState, "State_Definitions_" + targetState,
                            targetState.toLowerCase() };

                    boolean success = false;
                    for (String stateName : stateNames) {
                        try {
                            chunkAccessor.setBlockInteractionState(pos, sensorType, stateName);
                            // LOGGER.atInfo().log(PREFIX + "[LightSensor] Fallback state set at " + pos + "
                            // -> " + stateName);
                            success = true;
                            break;
                        } catch (Exception e2) {
                            // Try next state name
                            continue;
                        }
                    }

                    if (!success) {
                        LOGGER.atWarning().log(PREFIX + "[LightSensor] Failed to set any state at " + pos);
                    }

                } catch (Exception e2) {
                    LOGGER.atWarning().log(PREFIX + "[LightSensor] Fallback also failed: " + e2.getMessage());
                }
            }

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[LightSensor] Visual update failed: " + e.getMessage());
        }
    }

    // ==================== Registration ====================

    /**
     * Register a light sensor at the given position.
     */
    public void registerSensor(Vector3i position) {
        registerSensor(position, DEFAULT_THRESHOLD);
    }

    /**
     * Register a light sensor at the given position with a custom threshold.
     * 
     * @param threshold Sunlight factor threshold (0.0-1.0)
     */
    public void registerSensor(Vector3i position, double threshold) {
        if (!sensorThresholds.containsKey(position)) {
            sensorThresholds.put(position, threshold);
            sensorPowered.put(position, false);
            // LOGGER.atInfo().log(PREFIX + "[LightSensor] Registered at " + position + "
            // with threshold " + threshold);
        }
    }

    /**
     * Unregister a light sensor at the given position.
     */
    public void unregisterSensor(Vector3i position) {
        if (sensorThresholds.containsKey(position)) {
            sensorThresholds.remove(position);
            sensorPowered.remove(position);
            // LOGGER.atInfo().log(PREFIX + "[LightSensor] Unregistered at " + position);
        }
    }

    /**
     * Check if there's a light sensor at the given position.
     */
    public boolean isSensorAt(Vector3i position) {
        return sensorThresholds.containsKey(position);
    }

    /**
     * Get the current powered state of a light sensor.
     */
    public boolean isSensorPowered(Vector3i position) {
        return sensorPowered.getOrDefault(position, false);
    }

    /**
     * Get the threshold for a light sensor.
     * 
     * @return Sunlight factor threshold (0.0-1.0)
     */
    public double getSensorThreshold(Vector3i position) {
        return sensorThresholds.getOrDefault(position, DEFAULT_THRESHOLD);
    }

    /**
     * Set the threshold for a light sensor.
     * 
     * @param threshold Sunlight factor threshold (0.0-1.0)
     */
    public void setSensorThreshold(Vector3i position, double threshold) {
        if (sensorThresholds.containsKey(position)) {
            sensorThresholds.put(position, Math.max(0.0, Math.min(1.0, threshold)));
            // LOGGER.atInfo().log(PREFIX + "[LightSensor] Threshold at " + position + " set
            // to " + threshold);
        }
    }

    /**
     * Get power output from a light sensor at the given position.
     * Returns 15 if powered, 0 otherwise.
     */
    public int getPowerOutput(Vector3i position) {
        return isSensorPowered(position) ? 15 : 0;
    }

    /**
     * Get all registered sensor positions.
     */
    public Set<Vector3i> getSensorPositions() {
        return new HashSet<>(sensorThresholds.keySet());
    }

    // ==================== Persistence ====================

    /**
     * Save light sensor data to file.
     * Format: x,y,z,threshold
     */
    public void saveSensors(Path dataDirectory) {
        if (sensorThresholds.isEmpty()) {
            return;
        }

        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path filePath = dataDirectory.resolve(SAVE_FILE);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Map.Entry<Vector3i, Double> entry : sensorThresholds.entrySet()) {
                    Vector3i pos = entry.getKey();
                    double threshold = entry.getValue();
                    writer.write(pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + threshold);
                    writer.newLine();
                }
            }

            // LOGGER.atInfo().log(PREFIX + "[LightSensor] Saved " + sensorThresholds.size()
            // + " sensors to " + filePath);

        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "[LightSensor] Failed to save sensors: " + e.getMessage());
        }
    }

    /**
     * Load light sensor data from file.
     * Format: x,y,z,threshold
     */
    public void loadSensors(Path dataDirectory) {
        Path filePath = dataDirectory.resolve(SAVE_FILE);

        if (!Files.exists(filePath)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                String[] parts = line.split(",");
                if (parts.length < 3) {
                    LOGGER.atWarning().log(PREFIX + "[LightSensor] Invalid line in save file: " + line);
                    continue;
                }

                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    double threshold = parts.length >= 4 ? Double.parseDouble(parts[3].trim()) : DEFAULT_THRESHOLD;

                    Vector3i pos = new Vector3i(x, y, z);
                    sensorThresholds.put(pos, threshold);
                    sensorPowered.put(pos, false); // Start unpowered
                    count++;

                } catch (NumberFormatException e) {
                    LOGGER.atWarning().log(PREFIX + "[LightSensor] Invalid coordinates in line: " + line);
                }
            }

            // LOGGER.atInfo().log(PREFIX + "[LightSensor] Loaded " + count + " sensors from
            // " + filePath);

        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "[LightSensor] Failed to load sensors: " + e.getMessage());
        }
    }

    // ==================== Direct Block Powering ====================

    /**
     * Directly power adjacent blocks that aren't part of the wire network.
     * This ensures lamps, pistons, and other circuit blocks get powered.
     */
    private void powerAdjacentBlocks(Vector3i sensorPos, boolean powered) {
        // Check all 6 adjacent positions
        int[][] offsets = {
                { 1, 0, 0 }, { -1, 0, 0 }, // X axis
                { 0, 1, 0 }, { 0, -1, 0 }, // Y axis
                { 0, 0, 1 }, { 0, 0, -1 } // Z axis
        };

        int powerLevel = powered ? 15 : 0;

        for (int[] offset : offsets) {
            Vector3i adjacentPos = new Vector3i(
                    sensorPos.getX() + offset[0],
                    sensorPos.getY() + offset[1],
                    sensorPos.getZ() + offset[2]);

            // First, try to discover and register the block if it's a circuit block
            plugin.tryDiscoverCircuitBlock(adjacentPos);

            // Power lamps
            if (plugin.getLampSystem() != null && plugin.getLampSystem().isLampAt(adjacentPos)) {
                plugin.getLampSystem().updateLampState(adjacentPos, powerLevel);
                // LOGGER.atInfo().log(PREFIX + "[LightSensor] Powered lamp at " + adjacentPos +
                // " with power=" + powerLevel);
            }

            // Power pistons - use the existing isPistonAt method
            if (plugin.getPistonSystem() != null && plugin.getPistonSystem().isPistonAt(adjacentPos)) {
                // Pistons are powered through the energy system, but we can force an update
                // by triggering the energy system to update the network at this position
                if (plugin.getEnergySystem() != null) {
                    plugin.getEnergySystem().updateNetwork(adjacentPos);
                }
                // LOGGER.atInfo().log(PREFIX + "[LightSensor] Triggered piston update at " +
                // adjacentPos + " with power=" + powerLevel);
            }

            // Power doors - use the existing isDoorTracked method
            if (plugin.getDoorSystem() != null && plugin.getDoorSystem().isDoorTracked(adjacentPos)) {
                plugin.getDoorSystem().updateDoorState(adjacentPos, powerLevel);
                // LOGGER.atInfo().log(PREFIX + "[LightSensor] Powered door at " + adjacentPos +
                // " with power=" + powerLevel);
            }

            // Power gates
            if (plugin.getGateSystem() != null && plugin.getGateSystem().isGateAt(adjacentPos)) {
                // Gates need network update to recalculate their inputs
                if (plugin.getEnergySystem() != null) {
                    plugin.getEnergySystem().updateNetwork(adjacentPos);
                }
                // LOGGER.atInfo().log(PREFIX + "[LightSensor] Triggered gate update at " +
                // adjacentPos + " with power=" + powerLevel);
            }

            // Power repeaters
            if (plugin.getRepeaterSystem() != null && plugin.getRepeaterSystem().isRepeaterAt(adjacentPos)) {
                // Repeaters need network update to recalculate their inputs
                if (plugin.getEnergySystem() != null) {
                    plugin.getEnergySystem().updateNetwork(adjacentPos);
                }
                // LOGGER.atInfo().log(PREFIX + "[LightSensor] Triggered repeater update at " +
                // adjacentPos + " with power=" + powerLevel);
            }

            // Also trigger a general network update for this position to ensure
            // any other circuit components get updated
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNetwork(adjacentPos);
            }
        }
    }

    /**
     * Force update of adjacent blocks through the energy system.
     * This ensures that lamps, pistons, and other circuit blocks get properly
     * updated
     * when the light sensor state changes.
     */
    private void forceUpdateAdjacentBlocks(Vector3i sensorPos) {
        // Check all 6 adjacent positions
        int[][] offsets = {
                { 1, 0, 0 }, { -1, 0, 0 }, // X axis
                { 0, 1, 0 }, { 0, -1, 0 }, // Y axis
                { 0, 0, 1 }, { 0, 0, -1 } // Z axis
        };

        for (int[] offset : offsets) {
            Vector3i adjacentPos = new Vector3i(
                    sensorPos.getX() + offset[0],
                    sensorPos.getY() + offset[1],
                    sensorPos.getZ() + offset[2]);

            // First, try to discover and register the block if it's a circuit block
            plugin.tryDiscoverCircuitBlock(adjacentPos);

            // Then force an energy system update for this position
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNetwork(adjacentPos);
            }
        }
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return null;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
