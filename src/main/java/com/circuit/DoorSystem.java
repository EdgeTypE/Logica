package com.circuit;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles automatic door and fence gate opening/closing based on circuit signals.
 * Doors and fence gates will open when they receive power and close when power is removed.
 */
public class DoorSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] [DoorSystem] ";

    private final CircuitPlugin plugin;
    
    // Track door states to prevent unnecessary updates
    private final Map<Vector3i, Boolean> doorStates = new ConcurrentHashMap<>();
    
    // Track registered doors and fence gates
    private final Map<Vector3i, BlockType> trackedDoors = new ConcurrentHashMap<>();

    public DoorSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
        // LOGGER.atInfo().log(PREFIX + "DoorSystem initialized");
    }

    /**
     * Updates the state of a door or fence gate based on power level.
     * Called by the energy propagation system when power changes.
     * 
     * @param pos The position of the door/gate
     * @param powerLevel The current power level (0-15)
     */
    public void updateDoorState(Vector3i pos, int powerLevel) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) {
                LOGGER.atWarning().log(PREFIX + "World is null, cannot update door at " + pos);
                return;
            }

            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
            BlockType blockType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());
            
            if (blockType == null) {
                // Remove from tracking if block no longer exists
                trackedDoors.remove(pos);
                doorStates.remove(pos);
                return;
            }

            // Check if this is a door or fence gate
            if (!isDoorOrGate(blockType)) {
                return;
            }

            // Track this door/gate
            trackedDoors.put(pos, blockType);

            boolean shouldBeOpen = powerLevel > 0;
            Boolean currentState = doorStates.get(pos);
            
            // Only update if state actually changed (prevents duplicate updates)
            if (currentState == null || currentState != shouldBeOpen) {
                // Find the main door position (like Redstone project does)
                Vector3i mainDoorPos = findMainDoorPosition(world, pos, chunkAccessor);
                
                // Update the main door
                setDoorState(mainDoorPos, blockType, shouldBeOpen, chunkAccessor);
                doorStates.put(mainDoorPos, shouldBeOpen);
                
                // Also update the other half of double doors
                updateDoubleDoorPart(world, mainDoorPos, shouldBeOpen, chunkAccessor);
                
                // Mark the original position as updated too (if different from main)
                if (!mainDoorPos.equals(pos)) {
                    doorStates.put(pos, shouldBeOpen);
                }
                
                String stateText = shouldBeOpen ? "OPEN" : "CLOSED";
                // LOGGER.atInfo().log(PREFIX + "Door/Gate at " + pos + " -> " + stateText + " (power=" + powerLevel + ")");
            } else {
                // State hasn't changed, just log for debugging
                String stateText = shouldBeOpen ? "OPEN" : "CLOSED";
                // LOGGER.atInfo().log(PREFIX + "Door/Gate at " + pos + " already " + stateText + " (power=" + powerLevel + ")");
            }

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update door state at " + pos + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sets the physical state of a door or fence gate using the Redstone project's approach.
     * 
     * @param pos The position of the door/gate
     * @param blockType The block type
     * @param open Whether the door should be open
     * @param chunkAccessor The chunk accessor for world modifications
     */
    private void setDoorState(Vector3i pos, BlockType blockType, boolean open, IChunkAccessorSync chunkAccessor) {
        try {
            // Validate position and block type first
            if (blockType == null || !isDoorOrGate(blockType)) {
                LOGGER.atWarning().log(PREFIX + "Invalid door/gate at " + pos + " - skipping state change");
                return;
            }
            
            // Get current state like Redstone project does
            String currentState = null;
            try {
                currentState = blockType.getStateForBlock(blockType);
            } catch (Exception e) {
                // If getStateForBlock fails, try to get it from the world
                // LOGGER.atInfo().log(PREFIX + "Failed to get state for block type, trying world state: " + e.getMessage());
            }
            
            // Handle null state
            if (currentState == null) {
                // Try to determine state from block ID
                String blockId = blockType.getId();
                if (blockId != null) {
                    if (blockId.contains("Open")) {
                        currentState = blockId.contains("OpenDoorIn") ? "OpenDoorIn" : "OpenDoorOut";
                    } else {
                        currentState = blockId.contains("CloseDoorIn") ? "CloseDoorIn" : "CloseDoorOut";
                    }
                } else {
                    // Default assumption
                    currentState = "CloseDoorOut";
                }
                // LOGGER.atInfo().log(PREFIX + "Determined state from block ID: " + currentState);
            }
            
            boolean isCurrentlyOpen = currentState != null && 
                (currentState.contains("Open") || 
                 currentState.equals("OpenDoorIn") || 
                 currentState.equals("OpenDoorOut"));
            
            // Don't change if already in desired state
            if (open && isCurrentlyOpen) {
                // LOGGER.atInfo().log(PREFIX + "Door at " + pos + " is already open (state: " + currentState + ")");
                return;
            }
            
            if (!open && !isCurrentlyOpen) {
                // LOGGER.atInfo().log(PREFIX + "Door at " + pos + " is already closed (state: " + currentState + ")");
                return;
            }
            
            // Choose target state based on current state (like Redstone project)
            String targetState;
            if (open) {
                targetState = "OpenDoorOut";  // Default to OutDoor when opening
            } else {
                // Close based on current open state
                if ("OpenDoorIn".equals(currentState)) {
                    targetState = "CloseDoorIn";
                } else {
                    targetState = "CloseDoorOut";
                }
            }
            
            // Validate the target state exists for this block type
            if (!isValidStateForBlock(blockType, targetState)) {
                LOGGER.atWarning().log(PREFIX + "Invalid target state " + targetState + " for block " + blockType.getId() + " at " + pos);
                return;
            }
            
            // Try to set the state
            chunkAccessor.setBlockInteractionState(pos, blockType, targetState);
            // LOGGER.atInfo().log(PREFIX + "Set door state at " + pos + ": " + currentState + " -> " + targetState);
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to set door state at " + pos + ": " + e.getMessage());
            
            // Fallback: Try multiple state names like before
            try {
                String[] openStates = {"Open", "Opened", "OpenDoorIn", "OpenDoorOut"};
                String[] closedStates = {"Closed", "Close", "CloseDoorIn", "CloseDoorOut"};
                
                String[] targetStates = open ? openStates : closedStates;
                
                boolean success = false;
                for (String state : targetStates) {
                    try {
                        if (isValidStateForBlock(blockType, state)) {
                            chunkAccessor.setBlockInteractionState(pos, blockType, state);
                            // LOGGER.atInfo().log(PREFIX + "Set door state (fallback) at " + pos + " -> " + state);
                            success = true;
                            break;
                        }
                    } catch (Exception e2) {
                        // Try next state name
                        continue;
                    }
                }
                
                if (!success) {
                    LOGGER.atWarning().log(PREFIX + "Failed to set door state at " + pos + " - tried all state names");
                }
                
            } catch (Exception e2) {
                LOGGER.atWarning().log(PREFIX + "Fallback door state failed at " + pos + ": " + e2.getMessage());
            }
        }
    }

    /**
     * Checks if a state name is valid for a given block type.
     * This helps prevent block corruption by validating states before applying them.
     * 
     * @param blockType The block type
     * @param stateName The state name to check
     * @return true if the state is valid for this block type
     */
    private boolean isValidStateForBlock(BlockType blockType, String stateName) {
        try {
            // This is a simple validation - in a real implementation you might want to
            // check the block's available states more thoroughly
            if (blockType == null || stateName == null) {
                return false;
            }
            
            // Basic validation: door blocks should have door-related states
            String blockId = blockType.getId();
            if (blockId != null && blockId.toLowerCase().contains("door")) {
                return stateName.contains("Door") || stateName.equals("Open") || stateName.equals("Closed");
            }
            
            // For fence gates and other blocks, allow basic open/close states
            return stateName.equals("Open") || stateName.equals("Closed") || 
                   stateName.equals("Opened") || stateName.contains("Door");
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to validate state " + stateName + " for block " + blockType.getId() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a block type is a door or fence gate that should be controlled by circuits.
     * 
     * @param blockType The block type to check
     * @return true if this is a controllable door or gate
     */
    private boolean isDoorOrGate(BlockType blockType) {
        if (blockType == null) {
            return false;
        }

        // Use the built-in isDoor() method
        if (blockType.isDoor()) {
            return true;
        }

        // Check for fence gates by ID (since they might not be marked as doors)
        String blockId = blockType.getId();
        if (blockId != null) {
            String lowerCaseId = blockId.toLowerCase();
            return lowerCaseId.contains("gate") || 
                   lowerCaseId.contains("fence_gate") ||
                   lowerCaseId.contains("fencegate");
        }

        return false;
    }

    /**
     * Registers a door or fence gate for circuit control.
     * Called when a door/gate is placed near circuit components.
     * 
     * @param pos The position of the door/gate
     */
    public void registerDoor(Vector3i pos) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;

            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
            BlockType blockType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());
            
            if (blockType != null && isDoorOrGate(blockType)) {
                trackedDoors.put(pos, blockType);
                // LOGGER.atInfo().log(PREFIX + "Registered door/gate at " + pos + " (type: " + blockType.getId() + ")");
                
                // Check initial power state
                int powerLevel = getPowerLevel(pos);
                updateDoorState(pos, powerLevel);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to register door at " + pos + ": " + e.getMessage());
        }
    }

    /**
     * Unregisters a door or fence gate from circuit control.
     * Called when a door/gate is broken.
     * 
     * @param pos The position of the door/gate
     */
    public void unregisterDoor(Vector3i pos) {
        trackedDoors.remove(pos);
        doorStates.remove(pos);
        // LOGGER.atInfo().log(PREFIX + "Unregistered door/gate at " + pos);
    }

    /**
     * Gets the current power level at a position by checking nearby circuit components.
     * 
     * @param pos The position to check
     * @return The power level (0-15)
     */
    private int getPowerLevel(Vector3i pos) {
        try {
            // Check adjacent blocks for power
            int maxPower = 0;
            
            int[][] offsets = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0},
                {0, 0, 1}, {0, 0, -1}
            };
            
            for (int[] offset : offsets) {
                Vector3i neighbor = new Vector3i(
                    pos.getX() + offset[0],
                    pos.getY() + offset[1],
                    pos.getZ() + offset[2]
                );
                
                // Check if neighbor is a powered circuit component
                int neighborPower = getCircuitPower(neighbor);
                if (neighborPower > maxPower) {
                    maxPower = neighborPower;
                }
            }
            
            return maxPower;
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to get power level at " + pos + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets the power output from a circuit component at the given position.
     * 
     * @param pos The position to check
     * @return The power level (0-15)
     */
    private int getCircuitPower(Vector3i pos) {
        try {
            // Use the energy propagation system to get power level
            EnergyPropagationSystem energySystem = plugin.getEnergyPropagationSystem();
            if (energySystem != null) {
                return energySystem.getEnergyLevel(pos);
            }
            
            // Fallback: Check specific circuit components
            if (plugin.getLeverState(pos)) {
                return 15;
            }
            
            if (plugin.getButtonSystem() != null && plugin.getButtonSystem().isButtonPressed(pos)) {
                return 15;
            }
            
            return 0;
            
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Checks if a door or gate is currently tracked by the system.
     * 
     * @param pos The position to check
     * @return true if the door/gate is tracked
     */
    public boolean isDoorTracked(Vector3i pos) {
        return trackedDoors.containsKey(pos);
    }

    /**
     * Gets the current state of a tracked door/gate.
     * 
     * @param pos The position to check
     * @return true if open, false if closed, null if not tracked
     */
    public Boolean getDoorState(Vector3i pos) {
        return doorStates.get(pos);
    }

    /**
     * Forces an update of all tracked doors/gates.
     * Useful for initialization or after major circuit changes.
     */
    public void updateAllDoors() {
        // LOGGER.atInfo().log(PREFIX + "Updating all tracked doors (" + trackedDoors.size() + " doors)");
        
        for (Vector3i pos : trackedDoors.keySet()) {
            int powerLevel = getPowerLevel(pos);
            updateDoorState(pos, powerLevel);
        }
    }

    /**
     * Scans an area around a position for doors and fence gates to register.
     * Called when circuit components are placed.
     * 
     * @param centerPos The center position to scan around
     * @param radius The radius to scan (default: 5 blocks)
     */
    public void scanForDoors(Vector3i centerPos, int radius) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;

            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
            int doorsFound = 0;
            
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        Vector3i checkPos = new Vector3i(
                            centerPos.getX() + x,
                            centerPos.getY() + y,
                            centerPos.getZ() + z
                        );
                        
                        BlockType blockType = chunkAccessor.getBlockType(
                            checkPos.getX(), checkPos.getY(), checkPos.getZ()
                        );
                        
                        if (blockType != null && isDoorOrGate(blockType) && !isDoorTracked(checkPos)) {
                            registerDoor(checkPos);
                            doorsFound++;
                        }
                    }
                }
            }
            
            if (doorsFound > 0) {
                // LOGGER.atInfo().log(PREFIX + "Found and registered " + doorsFound + " doors/gates near " + centerPos);
            }
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to scan for doors near " + centerPos + ": " + e.getMessage());
        }
    }

    /**
     * Gets the number of tracked doors/gates.
     * 
     * @return The number of tracked doors
     */
    public int getTrackedDoorCount() {
        return trackedDoors.size();
    }

    /**
     * Finds the main door position, handling double doors like the Redstone project.
     * Based on Redstone project's toggleDoor method.
     * 
     * @param world The world
     * @param pos The initial position
     * @param chunkAccessor The chunk accessor
     * @return The main door position
     */
    private Vector3i findMainDoorPosition(World world, Vector3i pos, IChunkAccessorSync chunkAccessor) {
        try {
            // Import ChunkUtil properly
            long chunkIndex = ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
            com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            
            if (chunk == null) {
                return pos;
            }

            int filler = chunk.getFiller(pos.getX(), pos.getY(), pos.getZ());
            if (filler != 0) {
                // This is a filler block, find the main door block
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            
                            int tx = pos.getX() + dx;
                            int ty = pos.getY() + dy;
                            int tz = pos.getZ() + dz;
                            
                            long tIndex = ChunkUtil.indexChunkFromBlock(tx, tz);
                            com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk tChunk = world.getChunkIfInMemory(tIndex);
                            
                            if (tChunk != null) {
                                int tFiller = tChunk.getFiller(tx, ty, tz);
                                if (tFiller == 0) {
                                    BlockType tBt = tChunk.getBlockType(tx, ty, tz);
                                    if (tBt != null && tBt.isDoor()) {
                                        return new Vector3i(tx, ty, tz);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            return pos; // Return original position if no main door found
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to find main door position for " + pos + ": " + e.getMessage());
            return pos;
        }
    }

    /**
     * Updates the other half of a double door.
     * 
     * @param world The world
     * @param mainDoorPos The main door position
     * @param open Whether the door should be open
     * @param chunkAccessor The chunk accessor
     */
    private void updateDoubleDoorPart(World world, Vector3i mainDoorPos, boolean open, IChunkAccessorSync chunkAccessor) {
        try {
            // Look for the other half of the double door in adjacent positions
            int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            
            for (int[] offset : offsets) {
                Vector3i otherPos = new Vector3i(
                    mainDoorPos.getX() + offset[0],
                    mainDoorPos.getY() + offset[1], 
                    mainDoorPos.getZ() + offset[2]
                );
                
                BlockType otherBlockType = chunkAccessor.getBlockType(otherPos.getX(), otherPos.getY(), otherPos.getZ());
                if (otherBlockType != null && otherBlockType.isDoor()) {
                    // Check if this is actually part of a double door by checking if they share filler blocks
                    if (isPartOfSameDoubleDoor(world, mainDoorPos, otherPos)) {
                        // Found the other half, update it too
                        setDoorState(otherPos, otherBlockType, open, chunkAccessor);
                        doorStates.put(otherPos, open);
                        // LOGGER.atInfo().log(PREFIX + "Updated double door part at " + otherPos);
                        break; // Only update one other part
                    }
                }
            }
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update double door part: " + e.getMessage());
        }
    }

    /**
     * Checks if two door positions are part of the same double door.
     * 
     * @param world The world
     * @param pos1 First door position
     * @param pos2 Second door position
     * @return true if they are part of the same double door
     */
    private boolean isPartOfSameDoubleDoor(World world, Vector3i pos1, Vector3i pos2) {
        try {
            // Check if the doors are adjacent
            int dx = Math.abs(pos1.getX() - pos2.getX());
            int dy = Math.abs(pos1.getY() - pos2.getY());
            int dz = Math.abs(pos1.getZ() - pos2.getZ());
            
            // Must be adjacent (distance of 1 in exactly one axis)
            if ((dx == 1 && dy == 0 && dz == 0) || (dx == 0 && dy == 0 && dz == 1)) {
                // Check if there are filler blocks between them or they share the same structure
                long chunkIndex1 = ChunkUtil.indexChunkFromBlock(pos1.getX(), pos1.getZ());
                long chunkIndex2 = ChunkUtil.indexChunkFromBlock(pos2.getX(), pos2.getZ());
                
                com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk1 = world.getChunkIfInMemory(chunkIndex1);
                com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk2 = world.getChunkIfInMemory(chunkIndex2);
                
                if (chunk1 != null && chunk2 != null) {
                    // Check if either has filler blocks (indicating multi-block structure)
                    int filler1 = chunk1.getFiller(pos1.getX(), pos1.getY(), pos1.getZ());
                    int filler2 = chunk2.getFiller(pos2.getX(), pos2.getY(), pos2.getZ());
                    
                    // If either has filler blocks, they might be part of the same structure
                    if (filler1 != 0 || filler2 != 0) {
                        return true;
                    }
                    
                    // Also check if they have the same block type (same door type)
                    BlockType type1 = chunk1.getBlockType(pos1.getX(), pos1.getY(), pos1.getZ());
                    BlockType type2 = chunk2.getBlockType(pos2.getX(), pos2.getY(), pos2.getZ());
                    
                    if (type1 != null && type2 != null && type1.getId().equals(type2.getId())) {
                        return true;
                    }
                }
            }
            
            return false;
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to check double door relationship: " + e.getMessage());
            return false;
        }
    }
}