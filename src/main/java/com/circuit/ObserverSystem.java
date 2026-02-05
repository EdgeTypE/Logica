package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.io.*;
import java.nio.file.*;

/**
 * Observer System - Detects block changes and emits short pulse signals.
 * 
 * Design:
 * - Observer watches the block in front (InputPos = MyPos + FacingVector)
 * - Observer outputs signal from back (OutputPos = MyPos - FacingVector)
 * - Uses scheduled ticks to prevent instant activation (avoids stack overflow)
 * - Pulse: Detection → 2 tick delay → ON → 2 tick delay → OFF
 */
public class ObserverSystem extends EntityTickingSystem<EntityStore> {
    
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    
    // Tick delay for Observer pulse (in game ticks)
    private static final int PULSE_DELAY_TICKS = 2;
    
    private final CircuitPlugin plugin;
    
    // Observer registry: position -> facing direction
    private final Map<Vector3i, Direction> observerPositions = new HashMap<>();
    
    // Scheduled ticks: tick number -> list of positions to process
    private final Map<Long, Set<Vector3i>> scheduledTicks = new HashMap<>();
    
    // Observer powered state
    private final Map<Vector3i, Boolean> observerPowered = new HashMap<>();
    
    // Current game tick counter
    private long currentTick = 0;
    
    // ==================== Direction Enum ====================
    
    public enum Direction {
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        EAST(1, 0, 0),
        WEST(-1, 0, 0),
        UP(0, 1, 0),
        DOWN(0, -1, 0);
        
        private final int x, y, z;
        
        Direction(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public Vector3i getVector() {
            return new Vector3i(x, y, z);
        }
        
        public Direction getOpposite() {
            switch (this) {
                case NORTH: return SOUTH;
                case SOUTH: return NORTH;
                case EAST: return WEST;
                case WEST: return EAST;
                case UP: return DOWN;
                case DOWN: return UP;
                default: return NORTH;
            }
        }
        
        public static Direction fromString(String name) {
            try {
                return Direction.valueOf(name.toUpperCase());
            } catch (Exception e) {
                return NORTH;
            }
        }
    }
    
    // ==================== Constructor ====================
    
    public ObserverSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }
    
    // ==================== Position Helpers ====================
    
    /**
     * Get the position the Observer is watching (front/eye).
     */
    public Vector3i getInputPos(Vector3i observerPos, Direction facing) {
        Vector3i dir = facing.getVector();
        return new Vector3i(observerPos.getX() + dir.x, observerPos.getY() + dir.y, observerPos.getZ() + dir.z);
    }
    
    /**
     * Get the position where Observer outputs signal (back).
     */
    public Vector3i getOutputPos(Vector3i observerPos, Direction facing) {
        Vector3i dir = facing.getOpposite().getVector();
        return new Vector3i(observerPos.getX() + dir.x, observerPos.getY() + dir.y, observerPos.getZ() + dir.z);
    }
    
    // ==================== Registration ====================
    
    public void registerObserver(Vector3i pos, Direction facing) {
        observerPositions.put(pos, facing);
        observerPowered.put(pos, false);
        // LOGGER.atInfo().log(PREFIX + "[Observer] Registered at " + pos + " facing " + facing);
    }
    
    public void unregisterObserver(Vector3i pos) {
        observerPositions.remove(pos);
        observerPowered.remove(pos);
        // Remove from any scheduled ticks
        for (Set<Vector3i> positions : scheduledTicks.values()) {
            positions.remove(pos);
        }
        // LOGGER.atInfo().log(PREFIX + "[Observer] Unregistered at " + pos);
    }
    
    public boolean isObserver(Vector3i pos) {
        return observerPositions.containsKey(pos);
    }
    
    public boolean isObserverPowered(Vector3i pos) {
        return observerPowered.getOrDefault(pos, false);
    }
    
    public Direction getObserverFacing(Vector3i pos) {
        return observerPositions.get(pos);
    }
    
    public Map<Vector3i, Direction> getObservers() {
        return new HashMap<>(observerPositions);
    }
    
    // ==================== Block Change Detection ====================
    
    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };
    
    /**
     * Called when any block changes in the world.
     * Checks if any Observer is watching this position.
     * Also probes neighbors to discover Observers that may exist in the world but not registered.
     */
    public void onBlockChange(Vector3i changedPos) {
        // First, try to discover Observers in neighboring positions (for persistence after reload)
        for (int[] offset : NEIGHBOR_OFFSETS) {
            Vector3i neighborPos = new Vector3i(
                changedPos.getX() + offset[0],
                changedPos.getY() + offset[1],
                changedPos.getZ() + offset[2]
            );
            
            // If not already registered, try to discover with smart direction
            if (!observerPositions.containsKey(neighborPos)) {
                // Check if there's an Observer at this neighbor position
                if (tryDiscoverObserverWithDirection(neighborPos, changedPos)) {
                    // LOGGER.atInfo().log(PREFIX + "[Observer] Smart discovery at " + neighborPos + " watching " + changedPos);
                }
            }
        }
        
        // Now check all registered Observers (including newly discovered ones)
        for (Map.Entry<Vector3i, Direction> entry : observerPositions.entrySet()) {
            Vector3i observerPos = entry.getKey();
            Direction facing = entry.getValue();
            Vector3i watchedPos = getInputPos(observerPos, facing);
            
            if (watchedPos.equals(changedPos)) {
                // This Observer is watching the changed block!
                // LOGGER.atInfo().log(PREFIX + "[Observer] Detected change at " + changedPos + " (watched by " + observerPos + ")");
                scheduleTick(observerPos, PULSE_DELAY_TICKS);
            }
        }
    }
    
    /**
     * Try to discover an Observer at the given position, calculating its facing direction
     * based on which position it should be watching.
     */
    private boolean tryDiscoverObserverWithDirection(Vector3i observerPos, Vector3i watchedPos) {
        try {
            com.hypixel.hytale.server.core.universe.world.World world = 
                com.hypixel.hytale.server.core.universe.Universe.get().getDefaultWorld();
            if (world == null) return false;
            
            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = 
                (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
            
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = 
                chunkAccessor.getState(observerPos.getX(), observerPos.getY(), observerPos.getZ(), true);
            
            if (blockState == null) return false;
            
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = blockState.getBlockType();
            if (blockType == null) return false;
            
            String blockId = blockType.getId();
            if (blockId == null || !blockId.contains("Circuit_Observer")) return false;
            
            // Calculate facing direction: watchedPos - observerPos = facing vector
            int dx = watchedPos.getX() - observerPos.getX();
            int dy = watchedPos.getY() - observerPos.getY();
            int dz = watchedPos.getZ() - observerPos.getZ();
            
            Direction facing = Direction.NORTH; // Default
            if (dx == 1) facing = Direction.EAST;
            else if (dx == -1) facing = Direction.WEST;
            else if (dy == 1) facing = Direction.UP;
            else if (dy == -1) facing = Direction.DOWN;
            else if (dz == 1) facing = Direction.SOUTH;
            else if (dz == -1) facing = Direction.NORTH;
            
            registerObserver(observerPos, facing);
            return true;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    // ==================== Scheduled Tick System ====================
    
    /**
     * Schedule a tick for an Observer to process later.
     */
    private void scheduleTick(Vector3i pos, int delayTicks) {
        long targetTick = currentTick + delayTicks;
        
        // Check if already scheduled for this tick (prevent duplicate scheduling)
        Set<Vector3i> tickSet = scheduledTicks.get(targetTick);
        if (tickSet != null && tickSet.contains(pos)) {
            return; // Already scheduled
        }
        
        scheduledTicks.computeIfAbsent(targetTick, k -> new HashSet<>()).add(pos);
        // LOGGER.atInfo().log(PREFIX + "[Observer] Scheduled tick for " + pos + " at tick " + targetTick);
    }
    
    /**
     * Process all scheduled ticks for the current game tick.
     */
    private void processScheduledTicks() {
        Set<Vector3i> toProcess = scheduledTicks.remove(currentTick);
        
        if (toProcess == null || toProcess.isEmpty()) {
            return;
        }
        
        for (Vector3i pos : toProcess) {
            if (!observerPositions.containsKey(pos)) {
                continue; // Observer was removed
            }
            
            boolean isPowered = observerPowered.getOrDefault(pos, false);
            
            if (!isPowered) {
                // Turn ON
                observerPowered.put(pos, true);
                // LOGGER.atInfo().log(PREFIX + "[Observer] " + pos + " -> ON (pulse start)");
                
                // Update visual
                updateObserverVisual(pos, true);
                
                // Notify wire network
                notifyOutputWire(pos);
                
                // Schedule turn OFF
                scheduleTick(pos, PULSE_DELAY_TICKS);
            } else {
                // Turn OFF
                observerPowered.put(pos, false);
                // LOGGER.atInfo().log(PREFIX + "[Observer] " + pos + " -> OFF (pulse end)");
                
                // Update visual
                updateObserverVisual(pos, false);
                
                // Notify wire network (power removed)
                notifyOutputWire(pos);
            }
        }
    }
    
    // ==================== Visual Updates ====================
    
    private void updateObserverVisual(Vector3i pos, boolean powered) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;
            
            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = 
                (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
            
            BlockType observerType = BlockType.getAssetMap().getAsset("Circuit_Observer");
            if (observerType == null) return;
            
            String targetState = powered ? "On" : "Off";
            chunkAccessor.setBlockInteractionState(pos, observerType, targetState);
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[Observer] Visual update failed: " + e.getMessage());
        }
    }
    
    // ==================== Wire Network Notification ====================
    
    private void notifyOutputWire(Vector3i observerPos) {
        Direction facing = observerPositions.get(observerPos);
        if (facing == null) return;
        
        Vector3i outputPos = getOutputPos(observerPos, facing);
        
        // Update the wire network starting from output position
        if (plugin.getEnergySystem() != null) {
            plugin.getEnergySystem().updateNetwork(outputPos);
        }
    }
    
    // ==================== Power Query (for EnergyPropagationSystem) ====================
    
    /**
     * Get power output from Observer at the given position.
     * Only outputs power from the back side.
     */
    public int getPowerOutput(Vector3i queryPos, Vector3i fromPos) {
        Direction facing = observerPositions.get(queryPos);
        if (facing == null) return 0;
        
        Vector3i outputPos = getOutputPos(queryPos, facing);
        
        // Only provide power if query is from the output side
        if (outputPos.equals(fromPos) && observerPowered.getOrDefault(queryPos, false)) {
            return 15;
        }
        
        return 0;
    }
    
    // ==================== ECS System Tick ====================
    
    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Increment tick counter
        currentTick++;
        
        // Process scheduled ticks
        processScheduledTicks();
    }
    
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
    
    // ==================== Persistence ====================
    
    private static final String OBSERVERS_FILE = "observers.dat";
    
    /**
     * Save all observer positions and directions to a file.
     * Format: x,y,z,DIRECTION per line
     */
    public void saveObservers(Path dataDirectory) {
        if (observerPositions.isEmpty()) {
            // LOGGER.atInfo().log(PREFIX + "[Observer] No observers to save");
            return;
        }
        
        try {
            // Ensure data directory exists
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            
            Path filePath = dataDirectory.resolve(OBSERVERS_FILE);
            
            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Map.Entry<Vector3i, Direction> entry : observerPositions.entrySet()) {
                    Vector3i pos = entry.getKey();
                    Direction dir = entry.getValue();
                    // Format: x,y,z,DIRECTION
                    writer.write(pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + dir.name());
                    writer.newLine();
                }
            }
            
            // LOGGER.atInfo().log(PREFIX + "[Observer] Saved " + observerPositions.size() + " observers to " + filePath);
            
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "[Observer] Failed to save observers: " + e.getMessage());
        }
    }
    
    /**
     * Load observer positions and directions from file.
     * Format: x,y,z,DIRECTION per line
     */
    public void loadObservers(Path dataDirectory) {
        Path filePath = dataDirectory.resolve(OBSERVERS_FILE);
        
        if (!Files.exists(filePath)) {
            // LOGGER.atInfo().log(PREFIX + "[Observer] No saved observers file found at " + filePath);
            return;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            int count = 0;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split(",");
                if (parts.length != 4) {
                    LOGGER.atWarning().log(PREFIX + "[Observer] Invalid line in save file: " + line);
                    continue;
                }
                
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    String dirString = parts[3].trim();
                    if (dirString.isEmpty()) {
                        LOGGER.atWarning().log(PREFIX + "[Observer] Empty direction in line: " + line);
                        continue;
                    }
                    Direction dir = Direction.fromString(dirString);
                    
                    Vector3i pos = new Vector3i(x, y, z);
                    
                    // Register the observer position and direction
                    observerPositions.put(pos, dir);
                    // Always start unpowered - Observer pulses are short-lived and should not persist across restarts
                    observerPowered.put(pos, false);
                    count++;
                    
                } catch (NumberFormatException e) {
                    LOGGER.atWarning().log(PREFIX + "[Observer] Invalid coordinates in line: " + line);
                }
            }
            
            // LOGGER.atInfo().log(PREFIX + "[Observer] Loaded " + count + " observers from " + filePath);
            
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "[Observer] Failed to load observers: " + e.getMessage());
        }
    }
}
