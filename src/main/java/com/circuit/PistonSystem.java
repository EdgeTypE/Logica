package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * Robust Piston Logic Engine that runs every tick.
 * Implements a rigid State Machine approach for piston extension/retraction.
 * 
 * Features:
 * - Push limit of 8 blocks
 * - Proper rotation handling based on placement direction
 * - Sticky piston support for pulling blocks
 * - Safe block movement with validation
 * - State synchronization with visual block states
 */
public class PistonSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-Piston] ";

    // Configuration
    private static final int PUSH_LIMIT = 8;
    private static final String AIR_BLOCK = "Empty"; // Hytale uses "Empty" for air blocks

    // Piston registry: position -> PistonData
    private final Map<Vector3i, PistonData> pistons = new ConcurrentHashMap<>();

    // Reference to main plugin
    private final CircuitPlugin plugin;

    // Debug counter for tick logging
    private int tickCounter = 0;

    public PistonSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
        // LOGGER.atInfo().log(PRPistonSystemEFIX + "PistonSystem initialized with
        // PUSH_LIMIT=" + PUSH_LIMIT);
    }

    /**
     * Data structure to track piston state and properties
     */
    public static class PistonData {
        public final Vector3i position;
        public final Direction facing;
        public final boolean isSticky;
        public final int rotationIndex; // Yaw bilgisini saklıyoruz
        public boolean isExtended;
        public boolean currentPower;

        public PistonData(Vector3i position, Direction facing, boolean isSticky, int rotationIndex) {
            this.position = new Vector3i(position);
            this.facing = facing;
            this.isSticky = isSticky;
            this.rotationIndex = rotationIndex;
            this.isExtended = false;
            this.currentPower = false;
        }

        @Override
        public String toString() {
            return String.format("Piston[pos=%s, facing=%s, sticky=%s, extended=%s, power=%s, rotation=%d]",
                    position, facing, isSticky, isExtended, currentPower, rotationIndex);
        }
    }

    /**
     * Cardinal directions for piston facing
     */
    public enum Direction {
        NORTH(0, 0, -1),
        EAST(1, 0, 0),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        UP(0, 1, 0),
        DOWN(0, -1, 0);

        public final int dx, dy, dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public Vector3i getDirectionVector() {
            return new Vector3i(dx, dy, dz);
        }

        /**
         * Convert rotation index to direction (horizontal only for now)
         */
        public static Direction fromRotationIndex(int rotationIndex) {
            switch (rotationIndex % 4) {
                case 0:
                    return SOUTH;
                case 1:
                    return EAST;
                case 2:
                    return NORTH;
                case 3:
                    return WEST;
                default:
                    return NORTH;
            }
        }
    }

    @Override
    public void tick(float deltaTime, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {

        // Debug: Log tick activity every 60 ticks (3 seconds at 20 TPS)
        // tickCounter++;
        // if (tickCounter % 60 == 0) {
        // LOGGER.atInfo().log(PREFIX + "PistonSystem ticking... Registered pistons: " +
        // pistons.size());
        // for (PistonData piston : pistons.values()) {
        // LOGGER.atInfo().log(PREFIX + " - " + piston);
        // }
        // }

        // Process all registered pistons
        for (PistonData piston : pistons.values()) {
            processPiston(piston);
        }
    }

    /**
     * Process a single piston using State Machine logic
     */
    private void processPiston(PistonData piston) {
        // Get current power state
        boolean newPowerState = isPowered(piston.position);

        // Debug: Log power state changes
        if (newPowerState != piston.currentPower) {
            // LOGGER.atInfo().log(PREFIX + "Power change detected for " + piston + " -> " + newPowerState);
            piston.currentPower = newPowerState;
        }

        // State Machine Logic
        if (piston.currentPower && !piston.isExtended) {
            // Action: EXTEND (Power ON && !Extended)
            // LOGGER.atInfo().log(PREFIX + "Attempting to extend piston: " + piston);
            extendPiston(piston);
        } else if (!piston.currentPower && piston.isExtended) {
            // Action: RETRACT (Power OFF && Extended)
            // LOGGER.atInfo().log(PREFIX + "Attempting to retract piston: " + piston);
            retractPiston(piston);
        }
    }

    /**
     * Extend piston: push blocks and place piston head
     */
    private void extendPiston(PistonData piston) {
        // LOGGER.atInfo().log(PREFIX + "Extending piston: " + piston);

        World world = getWorld();
        if (world == null) {
            LOGGER.atWarning().log(PREFIX + "World is null, cannot extend piston");
            return;
        }

        Vector3i basePos = piston.position;
        Vector3i direction = piston.facing.getDirectionVector();

        // 1. Scan up to PUSH_LIMIT blocks in facing direction
        List<Vector3i> blocksToMove = new ArrayList<>();
        Vector3i scanPos = new Vector3i(basePos.getX() + direction.getX(),
                basePos.getY() + direction.getY(),
                basePos.getZ() + direction.getZ());

        for (int i = 0; i < PUSH_LIMIT; i++) {
            BlockType blockType = getBlockTypeAt(world, scanPos);
            if (blockType == null) {
                LOGGER.atWarning().log(PREFIX + "Cannot get block type at " + scanPos + ", assuming air");
                // Assume air if we can't get block type
                break;
            }

            String blockId = blockType.getId();

            // Check for air (end of push chain)
            if (AIR_BLOCK.equals(blockId) || blockId.contains("air")) {
                // LOGGER.atInfo().log(PREFIX + "Found air at " + scanPos + ", stopping scan");
                break;
            }

            // Check for unmovable blocks
            if (isUnmovableBlock(blockId)) {
                // LOGGER.atInfo().log(PREFIX + "Cannot extend: unmovable block " + blockId + " at " + scanPos);
                return;
            }

            // Add to move list
            blocksToMove.add(new Vector3i(scanPos));
            // LOGGER.atInfo().log(PREFIX + "Added block " + blockId + " at " + scanPos + " to move list");

            // Move to next position
            scanPos.setX(scanPos.getX() + direction.getX());
            scanPos.setY(scanPos.getY() + direction.getY());
            scanPos.setZ(scanPos.getZ() + direction.getZ());
        }

        // 2. Check if we hit the push limit
        if (blocksToMove.size() >= PUSH_LIMIT) {
            BlockType blockType = getBlockTypeAt(world, scanPos);
            if (blockType != null && !AIR_BLOCK.equals(blockType.getId()) && !blockType.getId().contains("air")) {
                // LOGGER.atInfo().log(PREFIX + "Cannot extend: push limit exceeded");
                return;
            }
        }

        // 3. Move blocks (from furthest to closest)
        for (int i = blocksToMove.size() - 1; i >= 0; i--) {
            Vector3i fromPos = blocksToMove.get(i);
            Vector3i toPos = new Vector3i(fromPos.getX() + direction.getX(),
                    fromPos.getY() + direction.getY(),
                    fromPos.getZ() + direction.getZ());

            // Skip moving air blocks - just set source to air
            BlockType sourceBlockType = getBlockTypeAt(world, fromPos);
            if (sourceBlockType != null && AIR_BLOCK.equals(sourceBlockType.getId())) {
                // LOGGER.atInfo().log(PREFIX + "Skipping air block at " + fromPos);
                continue;
            }

            if (!moveBlock(world, fromPos, toPos)) {
                LOGGER.atWarning().log(PREFIX + "Failed to move block from " + fromPos + " to " + toPos);
                return;
            }
        }

        // 4. Place piston head
        Vector3i headPos = new Vector3i(basePos.getX() + direction.getX(),
                basePos.getY() + direction.getY(),
                basePos.getZ() + direction.getZ());

        if (!placePistonHead(world, headPos, piston.facing, piston.rotationIndex)) {
            LOGGER.atWarning().log(PREFIX + "Failed to place piston head at " + headPos);
            return;
        }

        // 5. Update piston base to extended state
        if (!updatePistonBaseState(world, basePos, true, piston.rotationIndex)) {
            LOGGER.atWarning().log(PREFIX + "Failed to update piston base to extended state");
            return;
        }

        // 6. Update internal state
        piston.isExtended = true;
        // LOGGER.atInfo().log(PREFIX + "Successfully extended piston: " + piston);
    }

    /**
     * Retract piston: remove head and optionally pull block (sticky)
     */
    private void retractPiston(PistonData piston) {
        // LOGGER.atInfo().log(PREFIX + "Retracting piston: " + piston);

        World world = getWorld();
        if (world == null) {
            LOGGER.atWarning().log(PREFIX + "World is null, cannot retract piston");
            return;
        }

        Vector3i basePos = piston.position;
        Vector3i direction = piston.facing.getDirectionVector();
        Vector3i headPos = new Vector3i(basePos.getX() + direction.getX(),
                basePos.getY() + direction.getY(),
                basePos.getZ() + direction.getZ());

        // 1. Remove piston head
        if (!setBlockToAir(world, headPos)) {
            LOGGER.atWarning().log(PREFIX + "Failed to remove piston head at " + headPos);
            return;
        }

        // 2. Sticky logic (only if sticky piston)
        if (piston.isSticky) {
            Vector3i pullSource = new Vector3i(headPos.getX() + direction.getX(),
                    headPos.getY() + direction.getY(),
                    headPos.getZ() + direction.getZ());

            BlockType blockType = getBlockTypeAt(world, pullSource);
            if (blockType != null) {
                String blockId = blockType.getId();

                // Check if block can be pulled
                if (!AIR_BLOCK.equals(blockId) && !blockId.contains("air") && !isUnmovableBlock(blockId)) {
                    // Pull the block back to head position
                    if (!moveBlock(world, pullSource, headPos)) {
                        LOGGER.atWarning().log(PREFIX + "Failed to pull block from " + pullSource + " to " + headPos);
                    } else {
                        // LOGGER.atInfo().log(PREFIX + "Successfully pulled block " + blockId + " from " + pullSource
                                // + " to " + headPos);
                    }
                }
            }
        }

        // 3. Update piston base to retracted state with original rotation
        if (!updatePistonBaseState(world, basePos, false, piston.rotationIndex)) {
            LOGGER.atWarning().log(PREFIX + "Failed to update piston base to retracted state");
            return;
        }

        // 4. Update internal state
        piston.isExtended = false;
        // LOGGER.atInfo().log(PREFIX + "Successfully retracted piston: " + piston);
    }

    /**
     * Check if a block is powered (has redstone signal)
     */
    private boolean isPowered(Vector3i position) {
        // Check adjacent positions for power sources
        Vector3i[] adjacentPositions = {
                new Vector3i(position.getX() + 1, position.getY(), position.getZ()),
                new Vector3i(position.getX() - 1, position.getY(), position.getZ()),
                new Vector3i(position.getX(), position.getY() + 1, position.getZ()),
                new Vector3i(position.getX(), position.getY() - 1, position.getZ()),
                new Vector3i(position.getX(), position.getY(), position.getZ() + 1),
                new Vector3i(position.getX(), position.getY(), position.getZ() - 1)
        };

        boolean powered = false;
        // LOGGER.atInfo().log(PREFIX + "Checking power for piston at " + position);

        for (Vector3i adjPos : adjacentPositions) {
            // CRITICAL: Try to discover circuit blocks at adjacent positions
            // This handles save/load scenarios where blocks exist but aren't registered
            plugin.tryDiscoverCircuitBlock(adjPos);

            // Check for powered levers
            boolean leverState = plugin.getLeverState(adjPos);
            if (leverState) {
                // LOGGER.atInfo().log(PREFIX + "Piston at " + position + " powered by lever at
                // " + adjPos);
                powered = true;
            }

            // Check for powered wires
            boolean wireState = plugin.getWireState(adjPos);
            if (wireState) {
                // LOGGER.atInfo().log(PREFIX + "Piston at " + position + " powered by wire at "
                // + adjPos);
                powered = true;
            }

            // Debug: Log all adjacent positions and their states
            if (leverState || wireState) {
                // LOGGER.atInfo().log(PREFIX + "Adjacent " + adjPos + ": lever=" + leverState +
                // ", wire=" + wireState);
            }
        }

        if (!powered) {
            // LOGGER.atInfo().log(PREFIX + "Piston at " + position + " is not powered by
            // any adjacent source");
        }

        return powered;
    }

    /**
     * Check if a block type is unmovable (like bedrock, obsidian equivalent)
     */
    private boolean isUnmovableBlock(String blockId) {
        if (blockId == null)
            return true;

        // Add unmovable block types here
        return blockId.contains("bedrock") ||
                blockId.contains("obsidian") ||
                blockId.contains("Circuit_Piston") || // Pistons can't push pistons
                blockId.contains("barrier");
    }

    /**
     * Move a block from one position to another
     */
    private boolean moveBlock(World world, Vector3i fromPos, Vector3i toPos) {
        try {
            // Get source block data
            BlockType sourceBlockType = getBlockTypeAt(world, fromPos);
            if (sourceBlockType == null) {
                return false;
            }

            // Don't move air blocks
            if (AIR_BLOCK.equals(sourceBlockType.getId())) {
                // LOGGER.atInfo().log(PREFIX + "Skipping move of air block from " + fromPos + " to " + toPos);
                return true;
            }

            // Set destination to source block
            if (!setBlockAt(world, toPos, sourceBlockType)) {
                return false;
            }

            // Set source to air
            if (!setBlockToAir(world, fromPos)) {
                return false;
            }

            // LOGGER.atInfo().log(PREFIX + "Successfully moved block " + sourceBlockType.getId() + " from " + fromPos
                    // + " to " + toPos);
            return true;
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error moving block: " + e.getMessage());
            return false;
        }
    }

    /**
     * Place a piston head block with rotation
     */
    private boolean placePistonHead(World world, Vector3i position, Direction facing, int rotationIndex) {
        try {
            // LOGGER.atInfo().log(PREFIX + "Attempting to place piston head at " + position + " with rotation=" + rotationIndex);
            BlockType headBlockType = BlockType.getAssetMap().getAsset("Circuit_Piston_Head");
            if (headBlockType == null) {
                LOGGER.atWarning().log(PREFIX + "Circuit_Piston_Head block type not found in asset map");
                return false;
            }

            // LOGGER.atInfo().log(PREFIX + "Found Circuit_Piston_Head block type: " + headBlockType.getId());
            return setBlockAt(world, position, headBlockType, rotationIndex);
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error placing piston head: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Update piston base visual state (extended/retracted) with rotation
     */
    private boolean updatePistonBaseState(World world, Vector3i position, boolean extended, int rotationIndex) {
        try {
            // LOGGER.atInfo().log(PREFIX + "Updating piston base state at " + position + " to "
                    // + (extended ? "extended" : "retracted") + " with rotation=" + rotationIndex);

            // Get current block type to determine if sticky
            BlockType currentType = getBlockTypeAt(world, position);
            if (currentType == null) {
                // LOGGER.atWarning().log(PREFIX + "Current block type is null at " + position);
                return false;
            }

            String currentId = currentType.getId();
            // LOGGER.atInfo().log(PREFIX + "Current block type: " + currentId);
            boolean isSticky = currentId.contains("Sticky");

            // Determine target block type
            String targetBlockId;
            if (isSticky) {
                targetBlockId = extended ? "Circuit_Sticky_Piston_Extended" : "Circuit_Sticky_Piston";
            } else {
                targetBlockId = extended ? "Circuit_Pusher_Piston_Extended" : "Circuit_Pusher_Piston";
            }

            // LOGGER.atInfo().log(PREFIX + "Target block type: " + targetBlockId);

            BlockType targetBlockType = BlockType.getAssetMap().getAsset(targetBlockId);
            if (targetBlockType == null) {
                // LOGGER.atWarning().log(PREFIX + "Target block type not found: " +
                // targetBlockId);
                return false;
            }

            // LOGGER.atInfo().log(PREFIX + "Found target block type: " +
            // targetBlockType.getId());
            return setBlockAt(world, position, targetBlockType, rotationIndex);
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error updating piston base state: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Get block type at position using IChunkAccessorSync.getBlockType
     */
    private BlockType getBlockTypeAt(World world, Vector3i position) {
        try {
            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();

            // LOGGER.atInfo().log(PREFIX + "Trying to get block type at " + position);

            // Use the direct getBlockType method
            BlockType blockType = chunkAccessor.getBlockType(x, y, z);

            if (blockType != null) {
                // LOGGER.atInfo().log(PREFIX + "Found block type: " + blockType.getId() + " at
                // " + position);
                return blockType;
            } else {
                // LOGGER.atInfo().log(PREFIX + "Block type is null at " + position + ",
                // assuming air");
                // Return air block type
                BlockType airType = BlockType.getAssetMap().getAsset(AIR_BLOCK);
                if (airType == null) {
                    // LOGGER.atWarning().log(PREFIX + "WARNING: Could not get Empty block type!");
                }
                return airType;
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error getting block type at " + position + ": " + e.getMessage());
            e.printStackTrace();
            // Return air as fallback
            BlockType airType = BlockType.getAssetMap().getAsset(AIR_BLOCK);
            if (airType == null) {
                LOGGER.atWarning().log(PREFIX + "WARNING: Could not get Empty block type as fallback!");
            }
            return airType;
        }
    }

    /**
     * Set block at position using IChunkAccessorSync.setBlock (default rotation 0)
     */
    private boolean setBlockAt(World world, Vector3i position, BlockType blockType) {
        return setBlockAt(world, position, blockType, 0);
    }

    /**
     * Set block at position using WorldChunk.setBlock with rotation support
     */
    private boolean setBlockAt(World world, Vector3i position, BlockType blockType, int rotationIndex) {
        try {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();

            // LOGGER.atInfo().log(PREFIX + "Setting block " + blockType.getId() + " at " + position + " with rotation=" + rotationIndex);

            // Chunk koordinatlarını hesapla
            int chunkX = x >> 5;
            int chunkZ = z >> 5;
            long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

            // WorldChunk al
            com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world.getChunkIfLoaded(chunkKey);
            if (chunk != null) {
                // Local koordinatları hesapla (0-31 aralığında)
                int localX = x & 31;
                int localZ = z & 31;
                
                // setBlock ile rotation bilgisini de geç
                // boolean setBlock(int x, int y, int z, int blockId, BlockType blockType, int rotation, int filler, int flags)
                int blockId = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getBlockIdOrUnknown(blockType.getId(), "PistonSystem");
                chunk.setBlock(localX, y, localZ, blockId, blockType, rotationIndex, 0, 0);
                // LOGGER.atInfo().log(PREFIX + "Successfully set block " + blockType.getId() + " at " + position + " with rotation=" + rotationIndex);
                return true;
            } else {
                // Chunk yüklü değilse fallback olarak IChunkAccessorSync kullan
                LOGGER.atWarning().log(PREFIX + "Chunk not loaded at " + position + ", using fallback method");
                com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
                chunkAccessor.setBlock(x, y, z, blockType.getId());
                return true;
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error setting block at " + position + ": " + e.getMessage());
            e.printStackTrace();

            // Fallback: Try setBlockInteractionState
            try {
                com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
                chunkAccessor.setBlockInteractionState(position, blockType, "Default");
                return true;
            } catch (Exception e2) {
                LOGGER.atWarning().log(PREFIX + "Fallback also failed: " + e2.getMessage());
                e2.printStackTrace();
                return false;
            }
        }
    }

    /**
     * Set block to air
     */
    private boolean setBlockToAir(World world, Vector3i position) {
        BlockType airType = BlockType.getAssetMap().getAsset(AIR_BLOCK);
        if (airType == null) {
            LOGGER.atWarning().log(PREFIX + "Air block type not found");
            return false;
        }
        return setBlockAt(world, position, airType);
    }

    /**
     * Get the default world
     */
    private World getWorld() {
        try {
            return Universe.get().getDefaultWorld();
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error getting world: " + e.getMessage());
            return null;
        }
    }

    // ==================== Public API ====================

    /**
     * Register a piston at the given position
     */
    public void registerPiston(Vector3i position, Direction facing, boolean isSticky) {
        registerPiston(position, facing, isSticky, 0);
    }

    /**
     * Register a piston at the given position with rotation index
     */
    public void registerPiston(Vector3i position, Direction facing, boolean isSticky, int rotationIndex) {
        PistonData piston = new PistonData(position, facing, isSticky, rotationIndex);
        pistons.put(new Vector3i(position), piston);
        // LOGGER.atInfo().log(PREFIX + "Registered piston: " + piston + " with rotationIndex=" + rotationIndex);
    }

    /**
     * Unregister a piston at the given position
     */
    public void unregisterPiston(Vector3i position) {
        PistonData removed = pistons.remove(position);
        if (removed != null) {
            // LOGGER.atInfo().log(PREFIX + "Unregistered piston: " + removed);
        }
    }

    /**
     * Check if there's a piston at the given position
     */
    public boolean isPistonAt(Vector3i position) {
        return pistons.containsKey(position);
    }

    /**
     * Get piston data at position
     */
    public PistonData getPistonAt(Vector3i position) {
        return pistons.get(position);
    }

    /**
     * Get all registered pistons
     */
    public Collection<PistonData> getAllPistons() {
        return new ArrayList<>(pistons.values());
    }

    /**
     * Save piston registrations to file for persistence
     */
    public void savePistons(Path dataDirectory) {
        try {
            Path pistonFile = dataDirectory.resolve("pistons.json");

            // Create JSON data
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"pistons\": [\n");

            boolean first = true;
            for (PistonData piston : pistons.values()) {
                if (!first)
                    json.append(",\n");
                json.append("    {\n");
                json.append("      \"x\": ").append(piston.position.getX()).append(",\n");
                json.append("      \"y\": ").append(piston.position.getY()).append(",\n");
                json.append("      \"z\": ").append(piston.position.getZ()).append(",\n");
                json.append("      \"facing\": \"").append(piston.facing.name()).append("\",\n");
                json.append("      \"sticky\": ").append(piston.isSticky).append(",\n");
                json.append("      \"rotationIndex\": ").append(piston.rotationIndex).append(",\n");
                json.append("      \"extended\": ").append(piston.isExtended).append("\n");
                json.append("    }");
                first = false;
            }

            json.append("\n  ]\n}");

            // Write to file
            Files.createDirectories(dataDirectory);
            Files.write(pistonFile, json.toString().getBytes());

            // LOGGER.atInfo().log(PREFIX + "Saved " + pistons.size() + " pistons to " + pistonFile);

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save pistons: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load piston registrations from file for persistence
     */
    public void loadPistons(Path dataDirectory) {
        try {
            Path pistonFile = dataDirectory.resolve("pistons.json");

            if (!Files.exists(pistonFile)) {
                // LOGGER.atInfo().log(PREFIX + "No piston save file found, starting fresh");
                return;
            }

            String content = Files.readString(pistonFile);
            // LOGGER.atInfo().log(PREFIX + "Loading pistons from " + pistonFile);

            // Simple JSON parsing (improved implementation)
            int loaded = 0;
            String[] lines = content.split("\n");

            Vector3i currentPos = null;
            Direction currentFacing = Direction.NORTH;
            boolean currentSticky = false;
            boolean currentExtended = false;
            int currentRotationIndex = 0;

            for (String line : lines) {
                line = line.trim();

                if (line.contains("\"x\":")) {
                    String numStr = line.replaceAll("[^0-9-]", "");
                    if (!numStr.isEmpty()) {
                        int x = Integer.parseInt(numStr);
                        currentPos = new Vector3i(x, 0, 0);
                    }
                } else if (line.contains("\"y\":") && currentPos != null) {
                    String numStr = line.replaceAll("[^0-9-]", "");
                    if (!numStr.isEmpty()) {
                        int y = Integer.parseInt(numStr);
                        currentPos = new Vector3i(currentPos.getX(), y, currentPos.getZ());
                    }
                } else if (line.contains("\"z\":") && currentPos != null) {
                    String numStr = line.replaceAll("[^0-9-]", "");
                    if (!numStr.isEmpty()) {
                        int z = Integer.parseInt(numStr);
                        currentPos = new Vector3i(currentPos.getX(), currentPos.getY(), z);
                    }
                } else if (line.contains("\"facing\":")) {
                    // Extract facing value between quotes - format: "facing": "WEST"
                    int colonIndex = line.indexOf(":");
                    if (colonIndex != -1) {
                        String valuesPart = line.substring(colonIndex + 1).trim();
                        // Remove quotes and comma if present
                        String facing = valuesPart.replaceAll("[\",]", "").trim();
                        try {
                            currentFacing = Direction.valueOf(facing);
                        } catch (IllegalArgumentException e) {
                            LOGGER.atWarning().log(PREFIX + "Invalid facing direction: '" + facing + "', using NORTH");
                            currentFacing = Direction.NORTH;
                        }
                    }
                } else if (line.contains("\"sticky\":")) {
                    currentSticky = line.contains("true");
                } else if (line.contains("\"rotationIndex\":")) {
                    String numStr = line.replaceAll("[^0-9-]", "");
                    if (!numStr.isEmpty()) {
                        currentRotationIndex = Integer.parseInt(numStr);
                    }
                } else if (line.contains("\"extended\":")) {
                    currentExtended = line.contains("true");

                    // End of piston data - create piston
                    if (currentPos != null) {
                        PistonData piston = new PistonData(currentPos, currentFacing, currentSticky, currentRotationIndex);
                        piston.isExtended = currentExtended;
                        pistons.put(new Vector3i(currentPos), piston);
                        loaded++;

                        // LOGGER.atInfo().log(PREFIX + "Loaded piston: " + piston);
                        
                        // Reset rotationIndex for next piston
                        currentRotationIndex = 0;
                    }
                }
            }

            // LOGGER.atInfo().log(PREFIX + "Loaded " + loaded + " pistons from save file");

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load pistons: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return null; // Run in default ticking group
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any(); // Process all entities (we manage our own state)
    }
}