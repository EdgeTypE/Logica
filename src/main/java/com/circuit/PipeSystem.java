package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles pipe item transport logic.
 * Pipes can transport items in all 6 directions and automatically connect
 * to any block with an InventoryComponent.
 */
public class PipeSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-Pipe] ";

    // Registry of all pipe positions and their components
    private final Map<Vector3i, PipeComponent> pipes = new ConcurrentHashMap<>();

    // Reference to main plugin
    private final CircuitPlugin plugin;
    private final ComponentType<EntityStore, PipeComponent> pipeComponentType;

    // Debug counter
    private int tickCounter = 0;
    
    // Save/load file path
    private static final String SAVE_FILE = "mods/Circuit_CircuitMod/pipes.json";

    public PipeSystem(CircuitPlugin plugin, ComponentType<EntityStore, PipeComponent> pipeComponentType) {
        this.plugin = plugin;
        this.pipeComponentType = pipeComponentType;
    }

    @Override
    public void tick(float deltaTime, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {

        // Debug logging every 60 ticks (3 seconds)
        tickCounter++;
        if (tickCounter % 60 == 0 && !pipes.isEmpty()) {
            // LOGGER.atInfo().log(PREFIX + "Processing " + pipes.size() + " pipes");
            
            // Log each pipe's status
            for (Map.Entry<Vector3i, PipeComponent> entry : pipes.entrySet()) {
                Vector3i pos = entry.getKey();
                PipeComponent pipe = entry.getValue();
                try {
                    ItemStack item = pipe.getBuffer().getItemStack((short) 0);
                    String itemInfo = (item == null || item.isEmpty()) ? "empty" : item.getItemId() + " x" + item.getQuantity();
                    // LOGGER.atInfo().log(PREFIX + "  Pipe at " + pos + ": " + itemInfo + ", connections=" + pipe.getConnectionCount());
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "  Error reading pipe at " + pos + ": " + e.getMessage());
                }
            }
        }

        // Process all registered pipes
        for (Map.Entry<Vector3i, PipeComponent> entry : pipes.entrySet()) {
            Vector3i position = entry.getKey();
            PipeComponent pipe = entry.getValue();
            
            processPipe(position, pipe, deltaTime);
        }
    }

    /**
     * Process a single pipe's item transport logic
     */
    private void processPipe(Vector3i position, PipeComponent pipe, float deltaTime) {
        // Update cooldown
        if (pipe.isOnCooldown()) {
            pipe.decrementCooldown(deltaTime);
            return;
        }

        // Update connections first
        updatePipeConnections(position, pipe);

        // If pipe has no connections, skip processing
        if (!pipe.hasAnyConnection()) {
            return;
        }

        boolean hadItem = pipe.hasItem();
        
        // Try to pull items if buffer is empty
        if (!pipe.hasItem()) {
            tryPullItem(position, pipe);
        }

        // Try to push items if buffer has items
        if (pipe.hasItem()) {
            tryPushItem(position, pipe);
        }
        
        // Debug: Log state changes
        boolean hasItemNow = pipe.hasItem();
        if (hadItem != hasItemNow) {
            // LOGGER.atInfo().log(PREFIX + "Pipe at " + position + " state changed: hadItem=" + hadItem + " -> hasItem=" + hasItemNow);
        }
    }

    /**
     * Update pipe connections by checking adjacent blocks for inventory components
     */
    private void updatePipeConnections(Vector3i position, PipeComponent pipe) {
        World world = getWorld();
        if (world == null) return;

        // Check all 6 directions
        pipe.setConnectedNorth(hasInventoryAt(world, position.getX(), position.getY(), position.getZ() - 1));
        pipe.setConnectedSouth(hasInventoryAt(world, position.getX(), position.getY(), position.getZ() + 1));
        pipe.setConnectedEast(hasInventoryAt(world, position.getX() + 1, position.getY(), position.getZ()));
        pipe.setConnectedWest(hasInventoryAt(world, position.getX() - 1, position.getY(), position.getZ()));
        pipe.setConnectedUp(hasInventoryAt(world, position.getX(), position.getY() + 1, position.getZ()));
        pipe.setConnectedDown(hasInventoryAt(world, position.getX(), position.getY() - 1, position.getZ()));
    }

    /**
     * Check if a block at the given position has an inventory component
     */
    private boolean hasInventoryAt(World world, int x, int y, int z) {
        try {
            Vector3i pos = new Vector3i(x, y, z);
            
            // Check if it's another pipe
            if (pipes.containsKey(pos)) {
                return true;
            }

            // Try to discover circuit blocks (for save/load compatibility)
            plugin.tryDiscoverCircuitBlock(pos);

            // Check for known circuit blocks with inventories
            if (plugin.isWirePosition(pos)) {
                return false; // Wires don't have inventories
            }

            // Get block state to check for inventory capability
            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = 
                (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
            
            // First try to get the block state directly
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = 
                chunkAccessor.getState(x, y, z, true);
            
            if (blockState != null) {
                // Check if it's an ItemContainerBlockState
                if (blockState instanceof com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState) {
                    return true;
                }
            }

            // Fallback: Check block type for known inventory blocks
            BlockType blockType = chunkAccessor.getBlockType(x, y, z);
            if (blockType != null) {
                String blockId = blockType.getId();
                return isInventoryBlock(blockId);
            }
            
            return false;
            
        } catch (Exception e) {
            // Silently fail - assume no inventory
            return false;
        }
    }

    /**
     * Check if a block ID represents a block that has an inventory
     */
    private boolean isInventoryBlock(String blockId) {
        if (blockId == null) return false;
        
        // Circuit blocks with inventories
        if (blockId.contains("Circuit_Hopper")) return true;
        if (blockId.contains("Circuit_Pipe")) return true;
        
        // Vanilla blocks that typically have inventories
        if (blockId.contains("chest")) return true;
        if (blockId.contains("Chest")) return true;
        if (blockId.contains("hopper")) return true;
        if (blockId.contains("Hopper")) return true;
        if (blockId.contains("furnace")) return true;
        if (blockId.contains("Furnace")) return true;
        if (blockId.contains("barrel")) return true;
        if (blockId.contains("Barrel")) return true;
        if (blockId.contains("dispenser")) return true;
        if (blockId.contains("Dispenser")) return true;
        if (blockId.contains("dropper")) return true;
        if (blockId.contains("Dropper")) return true;
        
        return false;
    }

    /**
     * Try to pull an item from a connected inventory into the pipe's buffer
     * ONLY pulls from INPUT directions (all directions except output direction)
     */
    private void tryPullItem(Vector3i position, PipeComponent pipe) {
        World world = getWorld();
        if (world == null) return;

        // Get all connected directions
        List<PipeComponent.Direction> connectedDirs = getConnectedDirections(pipe);
        if (connectedDirs.isEmpty()) return;

        // CRITICAL: Only pull from INPUT directions (exclude output direction)
        List<PipeComponent.Direction> inputDirections = new ArrayList<>();
        for (PipeComponent.Direction dir : connectedDirs) {
            if (pipe.isInputDirection(dir)) {
                inputDirections.add(dir);
            }
        }

        if (inputDirections.isEmpty()) {
            return; // No input directions available
        }

        // Try each input direction for pulling
        for (PipeComponent.Direction dir : inputDirections) {
            Vector3i sourcePos = new Vector3i(
                position.getX() + dir.dx,
                position.getY() + dir.dy,
                position.getZ() + dir.dz
            );

            ItemContainer sourceInventory = getInventoryAt(world, sourcePos);
            if (sourceInventory == null || sourceInventory.isEmpty()) {
                continue;
            }

            // CRITICAL: Skip if source is the same as our buffer (prevent self-pull)
            if (sourceInventory == pipe.getBuffer()) {
                continue;
            }

            // CRITICAL: Skip if source is another pipe's buffer that's empty
            // This prevents infinite loops between pipes
            PipeComponent sourcePipe = pipes.get(sourcePos);
            if (sourcePipe != null) {
                ItemStack sourceItem;
                try {
                    sourceItem = sourcePipe.getBuffer().getItemStack((short) 0);
                } catch (Exception e) {
                    continue;
                }
                
                // Don't pull from empty pipe buffers
                if (sourceItem == null || sourceItem.isEmpty()) {
                    continue;
                }
                
                // Don't pull if the source pipe is on cooldown (prevents rapid back-and-forth)
                if (sourcePipe.isOnCooldown()) {
                    continue;
                }
            }

            // Try to pull one item from the first non-empty slot
            for (short slot = 0; slot < sourceInventory.getCapacity(); slot++) {
                ItemStack item = sourceInventory.getItemStack(slot);
                if (item.isEmpty()) continue;

                // Create a single-item stack to pull
                ItemStack singleItem = item.withQuantity(1);
                
                // Try to remove from source
                var removeResult = sourceInventory.removeItemStackFromSlot(slot, 1);
                if (removeResult.succeeded()) {
                    // Try to add to pipe buffer
                    ItemStackTransaction addResult = pipe.getBuffer().addItemStack(singleItem);
                    if (addResult.succeeded()) {
                        pipe.startCooldown();
                        // Store the source direction to prevent immediate backflow
                        pipe.setLastPullDirection(dir);
                        // LOGGER.atInfo().log(PREFIX + "Pulled item " + singleItem.getItemId() + " from " + sourcePos + " (" + dir + ") to pipe at " + position);
                        return; // Successfully pulled one item
                    } else {
                        // Failed to add to pipe, return item to source
                        sourceInventory.addItemStack(singleItem);
                        LOGGER.atWarning().log(PREFIX + "Failed to add pulled item to pipe buffer at " + position);
                    }
                }
            }
        }
    }

    /**
     * Try to push an item from the pipe's buffer to a connected inventory
     * ONLY pushes to the OUTPUT direction
     */
    private void tryPushItem(Vector3i position, PipeComponent pipe) {
        World world = getWorld();
        if (world == null) return;

        ItemStack item;
        try {
            item = pipe.getBuffer().getItemStack((short) 0);
            if (item == null || item.isEmpty()) return;
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error getting item from pipe buffer at " + position + ": " + e.getMessage());
            return;
        }

        // CRITICAL: Only push to OUTPUT direction
        PipeComponent.Direction outputDir = pipe.getOutputDirection();
        if (outputDir == null) {
            LOGGER.atWarning().log(PREFIX + "Pipe at " + position + " has no output direction set!");
            return;
        }
        
        // Debug: Log output direction for each push attempt
        if (tickCounter % 60 == 0) { // Log every 3 seconds to avoid spam
            // LOGGER.atInfo().log(PREFIX + "Pipe at " + position + " attempting to push to output direction: " + outputDir);
        }

        // Check if output direction is connected
        if (!isDirectionConnected(pipe, outputDir)) {
            // Output direction is not connected, can't push
            if (tickCounter % 120 == 0) { // Log every 6 seconds to avoid spam
                // LOGGER.atInfo().log(PREFIX + "Pipe at " + position + " output direction " + outputDir + " is not connected");
            }
            return;
        }

        Vector3i targetPos = new Vector3i(
            position.getX() + outputDir.dx,
            position.getY() + outputDir.dy,
            position.getZ() + outputDir.dz
        );

        ItemContainer targetInventory = getInventoryAt(world, targetPos);
        if (targetInventory == null) {
            if (tickCounter % 120 == 0) { // Log every 6 seconds to avoid spam
                // LOGGER.atInfo().log(PREFIX + "No inventory found at output position " + targetPos + " for pipe at " + position);
            }
            return;
        }

        // CRITICAL: Skip if target is the same as our buffer (prevent self-transfer)
        if (targetInventory == pipe.getBuffer()) {
            return;
        }

        // CRITICAL: For pipe-to-pipe transfers, prefer pipes that are empty or have space
        PipeComponent targetPipe = pipes.get(targetPos);
        if (targetPipe != null) {
            try {
                ItemStack targetItem = targetPipe.getBuffer().getItemStack((short) 0);
                // Skip if target pipe is full
                if (targetItem != null && !targetItem.isEmpty()) {
                    if (tickCounter % 120 == 0) { // Log every 6 seconds to avoid spam
                        // LOGGER.atInfo().log(PREFIX + "Target pipe at " + targetPos + " is full, cannot push from " + position);
                    }
                    return;
                }
                // Skip if target pipe is on cooldown
                if (targetPipe.isOnCooldown()) {
                    return;
                }
            } catch (Exception e) {
                return;
            }
        }

        // Try to add item to target inventory
        ItemStackTransaction addResult = targetInventory.addItemStack(item);
        if (addResult.succeeded()) {
            // CRITICAL: Remove from pipe buffer ONLY if transfer succeeded
            var removeResult = pipe.getBuffer().removeItemStackFromSlot((short) 0);
            if (removeResult.succeeded()) {
                pipe.startCooldown();
                // Clear the last pull direction since we successfully pushed
                pipe.setLastPullDirection(null);
                // LOGGER.atInfo().log(PREFIX + "Pushed item " + item.getItemId() + " from pipe at " + position + " to " + targetPos + " (" + outputDir + ")");
                return; // Successfully pushed item
            } else {
                LOGGER.atWarning().log(PREFIX + "Failed to remove item from pipe buffer at " + position + " after successful push");
                // Try to return the item to target (rollback)
                targetInventory.removeItemStack(item);
            }
        } else {
            // Could not push to target
            if (tickCounter % 120 == 0) { // Log every 6 seconds to avoid spam
                // LOGGER.atInfo().log(PREFIX + "Could not push item " + item.getItemId() + " from pipe at " + position + " to " + targetPos + " - target full or invalid");
            }
        }
    }

    /**
     * Check if a specific direction is connected for this pipe
     */
    private boolean isDirectionConnected(PipeComponent pipe, PipeComponent.Direction direction) {
        switch (direction) {
            case NORTH: return pipe.isConnectedNorth();
            case SOUTH: return pipe.isConnectedSouth();
            case EAST: return pipe.isConnectedEast();
            case WEST: return pipe.isConnectedWest();
            case UP: return pipe.isConnectedUp();
            case DOWN: return pipe.isConnectedDown();
            default: return false;
        }
    }

    /**
     * Get list of directions where this pipe is connected
     */
    private List<PipeComponent.Direction> getConnectedDirections(PipeComponent pipe) {
        List<PipeComponent.Direction> directions = new ArrayList<>();
        
        if (pipe.isConnectedNorth()) directions.add(PipeComponent.Direction.NORTH);
        if (pipe.isConnectedSouth()) directions.add(PipeComponent.Direction.SOUTH);
        if (pipe.isConnectedEast()) directions.add(PipeComponent.Direction.EAST);
        if (pipe.isConnectedWest()) directions.add(PipeComponent.Direction.WEST);
        if (pipe.isConnectedUp()) directions.add(PipeComponent.Direction.UP);
        if (pipe.isConnectedDown()) directions.add(PipeComponent.Direction.DOWN);
        
        return directions;
    }

    /**
     * Get the inventory component at a specific position
     */
    private ItemContainer getInventoryAt(World world, Vector3i position) {
        try {
            // Check if it's another pipe
            PipeComponent otherPipe = pipes.get(position);
            if (otherPipe != null) {
                return otherPipe.getBuffer();
            }

            // Get block state to access inventory
            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = 
                (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
            
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = 
                chunkAccessor.getState(position.getX(), position.getY(), position.getZ(), true);
            
            if (blockState != null && blockState instanceof com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState) {
                com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState containerState = 
                    (com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState) blockState;
                return containerState.getItemContainer();
            }

            // Fallback for testing: create mock inventories for known block types
            BlockType blockType = chunkAccessor.getBlockType(position.getX(), position.getY(), position.getZ());
            if (blockType != null && blockType.getId().contains("Circuit_Hopper")) {
                // For testing purposes, create a simple container for hoppers
                // In a real implementation, this would get the actual hopper's inventory
                try {
                    return new SimpleItemContainer((short) 5);
                } catch (Exception e) {
                    // If constructor doesn't work, return null
                    return null;
                }
            }
            
            return null;
            
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the default world
     */
    private World getWorld() {
        try {
            return Universe.get().getDefaultWorld();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Public API ====================

    /**
     * Register a pipe at the given position
     */
    public void registerPipe(Vector3i position, PipeComponent component) {
        pipes.put(new Vector3i(position), component);
        // LOGGER.atInfo().log(PREFIX + "Registered pipe at " + position);
    }

    /**
     * Unregister a pipe at the given position
     */
    public void unregisterPipe(Vector3i position) {
        PipeComponent removed = pipes.remove(position);
        if (removed != null) {
            // LOGGER.atInfo().log(PREFIX + "Unregistered pipe at " + position);
        }
    }

    /**
     * Check if there's a pipe at the given position
     */
    public boolean isPipeAt(Vector3i position) {
        return pipes.containsKey(position);
    }

    /**
     * Get pipe component at position
     */
    public PipeComponent getPipeAt(Vector3i position) {
        return pipes.get(position);
    }

    /**
     * Get all registered pipes
     */
    public Collection<PipeComponent> getAllPipes() {
        return new ArrayList<>(pipes.values());
    }
    
    /**
     * Save pipes to file
     */
    public void savePipes() {
        try {
            // Create JSON array for pipes
            StringBuilder json = new StringBuilder();
            json.append("[\n");
            
            boolean first = true;
            for (Map.Entry<Vector3i, PipeComponent> entry : pipes.entrySet()) {
                if (!first) {
                    json.append(",\n");
                }
                first = false;
                
                Vector3i pos = entry.getKey();
                PipeComponent pipe = entry.getValue();
                
                json.append("  {\n");
                json.append("    \"x\": ").append(pos.getX()).append(",\n");
                json.append("    \"y\": ").append(pos.getY()).append(",\n");
                json.append("    \"z\": ").append(pos.getZ()).append(",\n");
                json.append("    \"outputDirection\": \"").append(pipe.getOutputDirection().name()).append("\"\n");
                json.append("  }");
            }
            
            json.append("\n]");
            
            // Write to file
            java.nio.file.Path saveDir = java.nio.file.Paths.get("mods/Circuit_CircuitMod");
            if (!java.nio.file.Files.exists(saveDir)) {
                java.nio.file.Files.createDirectories(saveDir);
            }
            
            java.nio.file.Path saveFile = java.nio.file.Paths.get(SAVE_FILE);
            java.nio.file.Files.write(saveFile, json.toString().getBytes());
            
            // LOGGER.atInfo().log(PREFIX + "Saved " + pipes.size() + " pipes to " + SAVE_FILE);
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save pipes: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Load pipes from file
     */
    public void loadPipes() {
        try {
            java.nio.file.Path saveFile = java.nio.file.Paths.get(SAVE_FILE);
            if (!java.nio.file.Files.exists(saveFile)) {
                // LOGGER.atInfo().log(PREFIX + "No pipe save file found at " + SAVE_FILE);
                return;
            }
            
            String content = java.nio.file.Files.readString(saveFile);
            
            // Simple JSON parsing (since we control the format)
            content = content.trim();
            if (!content.startsWith("[") || !content.endsWith("]")) {
                LOGGER.atWarning().log(PREFIX + "Invalid pipe save file format");
                return;
            }
            
            // Remove brackets and split by objects
            content = content.substring(1, content.length() - 1).trim();
            if (content.isEmpty()) {
                // LOGGER.atInfo().log(PREFIX + "No pipes to load from save file");
                return;
            }
            
            // Split by },{ pattern
            String[] pipeObjects = content.split("\\},\\s*\\{");
            
            int loadedCount = 0;
            for (String pipeObj : pipeObjects) {
                try {
                    // Clean up the object string
                    pipeObj = pipeObj.trim();
                    if (pipeObj.startsWith("{")) {
                        pipeObj = pipeObj.substring(1);
                    }
                    if (pipeObj.endsWith("}")) {
                        pipeObj = pipeObj.substring(0, pipeObj.length() - 1);
                    }
                    
                    // Parse fields
                    int x = 0, y = 0, z = 0;
                    PipeComponent.Direction outputDirection = PipeComponent.Direction.NORTH;
                    
                    String[] lines = pipeObj.split(",");
                    for (String line : lines) {
                        line = line.trim();
                        if (line.contains("\"x\":")) {
                            x = Integer.parseInt(line.split(":")[1].trim());
                        } else if (line.contains("\"y\":")) {
                            y = Integer.parseInt(line.split(":")[1].trim());
                        } else if (line.contains("\"z\":")) {
                            z = Integer.parseInt(line.split(":")[1].trim());
                        } else if (line.contains("\"outputDirection\":")) {
                            String dirStr = line.split(":")[1].trim().replace("\"", "");
                            outputDirection = PipeComponent.Direction.valueOf(dirStr);
                        }
                    }
                    
                    Vector3i pos = new Vector3i(x, y, z);
                    
                    // Create pipe component with correct output direction
                    PipeComponent pipeComponent = new PipeComponent();
                    pipeComponent.setOutputDirection(outputDirection);
                    
                    // Register the pipe
                    pipes.put(pos, pipeComponent);
                    loadedCount++;
                    
                    // LOGGER.atInfo().log(PREFIX + "Loaded pipe at " + pos + " with output direction: " + outputDirection);
                    
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Failed to parse pipe object: " + pipeObj + " - " + e.getMessage());
                }
            }
            
            // LOGGER.atInfo().log(PREFIX + "Loaded " + loadedCount + " pipes from save file");
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load pipes: " + e.getMessage());
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
        return Query.any(); // We manage our own state
    }
}