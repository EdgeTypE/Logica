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
 * System that manages Circuit Lamps - blocks that emit light when powered.
 * Similar to Minecraft's Redstone Lamp functionality.
 */
public class LampSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    private static final String SAVE_FILE = "lamp_data.json";

    private final CircuitPlugin plugin;
    
    // Track lamp positions and their current lit state
    private final Map<Vector3i, Boolean> lampStates = new HashMap<>();

    public LampSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Lamps are passive - they don't need regular ticking
        // State changes are handled by the energy system
    }

    /**
     * Register a lamp at the given position.
     */
    public void registerLamp(Vector3i position) {
        if (!lampStates.containsKey(position)) {
            lampStates.put(position, false); // Default to off
            // LOGGER.atInfo().log(PREFIX + "[Lamp] Registered lamp at " + position);
        }
    }

    /**
     * Unregister a lamp at the given position.
     */
    public void unregisterLamp(Vector3i position) {
        if (lampStates.containsKey(position)) {
            lampStates.remove(position);
            // LOGGER.atInfo().log(PREFIX + "[Lamp] Unregistered lamp at " + position);
        }
    }

    /**
     * Check if there's a lamp at the given position.
     */
    public boolean isLampAt(Vector3i position) {
        return lampStates.containsKey(position);
    }

    /**
     * Get the current lit state of a lamp.
     */
    public boolean isLampLit(Vector3i position) {
        return lampStates.getOrDefault(position, false);
    }

    /**
     * Update a lamp's state based on power level.
     * Called by the energy propagation system.
     */
    public void updateLampState(Vector3i position, int powerLevel) {
        if (!lampStates.containsKey(position)) {
            return; // Not a registered lamp
        }

        boolean shouldBeLit = powerLevel > 0;
        boolean currentlyLit = lampStates.get(position);

        if (shouldBeLit != currentlyLit) {
            lampStates.put(position, shouldBeLit);
            setLampVisual(position, shouldBeLit);
            // LOGGER.atInfo().log(PREFIX + "[Lamp] State changed at " + position + ": " + 
                              // (shouldBeLit ? "OFF -> ON" : "ON -> OFF") + " (power=" + powerLevel + ")");
        }
    }

    /**
     * Update the visual state of a lamp block with proper lighting support.
     */
    private void setLampVisual(Vector3i position, boolean lit) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;

            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = 
                (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            // Get current block type to verify it's a lamp
            BlockType currentBlockType = chunkAccessor.getBlockType(position.getX(), position.getY(), position.getZ());
            
            if (currentBlockType == null || !currentBlockType.getId().contains("Circuit_Lamp")) {
                LOGGER.atWarning().log(PREFIX + "[Lamp] Not a lamp block at " + position + 
                    " (found: " + (currentBlockType != null ? currentBlockType.getId() : "null") + ")");
                return;
            }

            // Get current rotation to preserve it
            int rotationIndex = chunkAccessor.getBlockRotationIndex(position.getX(), position.getY(), position.getZ());
            
            // Determine target state
            String targetStateName = lit ? "On" : "Off";
            
            try {
                // Method 1: setBlockInteractionState first
                chunkAccessor.setBlockInteractionState(position, currentBlockType, targetStateName);
                // LOGGER.atInfo().log(PREFIX + "[Lamp] setBlockInteractionState at " + position + " -> " + targetStateName);
                
                // Method 2: Force block replacement to trigger lighting recalculation
                // This seems to be necessary for lighting updates
                chunkAccessor.setBlock(position.getX(), position.getY(), position.getZ(), "Circuit_Lamp", rotationIndex);
                
                // Method 3: Set state again after replacement
                BlockType newBlockType = chunkAccessor.getBlockType(position.getX(), position.getY(), position.getZ());
                if (newBlockType != null) {
                    chunkAccessor.setBlockInteractionState(position, newBlockType, targetStateName);
                    // LOGGER.atInfo().log(PREFIX + "[Lamp] Final state set at " + position + " -> " + targetStateName);
                }
                
            } catch (Exception e) {
                LOGGER.atWarning().log(PREFIX + "[Lamp] Update failed: " + e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[Lamp] Visual update failed: " + e.getMessage());
        }
    }

    /**
     * Get all registered lamp positions.
     */
    public Set<Vector3i> getLampPositions() {
        return new HashSet<>(lampStates.keySet());
    }

    /**
     * Save lamp data to file.
     */
    public void saveLamps(Path dataDirectory) {
        try {
            Path saveFile = dataDirectory.resolve(SAVE_FILE);
            
            // Create a simple format: "x,y,z:state"
            List<String> lines = new ArrayList<>();
            for (Map.Entry<Vector3i, Boolean> entry : lampStates.entrySet()) {
                Vector3i pos = entry.getKey();
                boolean lit = entry.getValue();
                lines.add(pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + lit);
            }
            
            Files.write(saveFile, lines);
            // LOGGER.atInfo().log(PREFIX + "[Lamp] Saved " + lampStates.size() + " lamps to " + saveFile);
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[Lamp] Failed to save lamp data: " + e.getMessage());
        }
    }

    /**
     * Load lamp data from file.
     */
    public void loadLamps(Path dataDirectory) {
        try {
            Path saveFile = dataDirectory.resolve(SAVE_FILE);
            
            if (!Files.exists(saveFile)) {
                // LOGGER.atInfo().log(PREFIX + "[Lamp] No saved lamp data found");
                return;
            }
            
            List<String> lines = Files.readAllLines(saveFile);
            int loaded = 0;
            
            for (String line : lines) {
                try {
                    String[] parts = line.split(":");
                    if (parts.length != 2) continue;
                    
                    String[] coords = parts[0].split(",");
                    if (coords.length != 3) continue;
                    
                    int x = Integer.parseInt(coords[0]);
                    int y = Integer.parseInt(coords[1]);
                    int z = Integer.parseInt(coords[2]);
                    boolean lit = Boolean.parseBoolean(parts[1]);
                    
                    Vector3i pos = new Vector3i(x, y, z);
                    lampStates.put(pos, lit);
                    loaded++;
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "[Lamp] Failed to parse line: " + line);
                }
            }
            
            // LOGGER.atInfo().log(PREFIX + "[Lamp] Loaded " + loaded + " lamps from " + saveFile);
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[Lamp] Failed to load lamp data: " + e.getMessage());
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