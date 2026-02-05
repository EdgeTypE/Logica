package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles vacuum pipe item collection logic.
 * Vacuum pipes pull dropped ItemEntity objects from the world within a specific range.
 */
public class VacuumSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-Vacuum] ";

    // Registry of all vacuum pipe positions and their components
    private final Map<Vector3i, PipeComponent> vacuumPipes = new ConcurrentHashMap<>();

    // Reference to main plugin
    private final CircuitPlugin plugin;
    private final ComponentType<EntityStore, PipeComponent> pipeComponentType;

    // Save file path
    private static final String SAVE_FILE = "mods/Circuit_CircuitMod/vacuum_pipes.json";

    // Vacuum settings
    private static final double VACUUM_RANGE = 4.0; // 4 block radius (8x8x8 area)
    private static final double PICKUP_DISTANCE = 4.0; // Distance for actual pickup (increased from 1.0)
    private static final double ATTRACTION_FORCE = 0.2; // Velocity factor for pulling items
    private static final int TICK_INTERVAL = 20; // Process every 10 ticks for performance

    // Tick counter for interval processing
    private int tickCounter = 0;

    public VacuumSystem(CircuitPlugin plugin, ComponentType<EntityStore, PipeComponent> pipeComponentType) {
        this.plugin = plugin;
        this.pipeComponentType = pipeComponentType;
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Return empty archetype since we manage our own entity queries manually
        return Archetype.empty();
    }

    @Override
    public void tick(float deltaTime, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {

        // Only process every TICK_INTERVAL ticks for performance
        tickCounter++;
        if (tickCounter % TICK_INTERVAL != 0) {
            return;
        }

        // Debug logging every 60 ticks (3 seconds)
        if (tickCounter % 60 == 0 && !vacuumPipes.isEmpty()) {
            // LOGGER.atInfo().log(PREFIX + "Processing " + vacuumPipes.size() + " vacuum pipes");
        }

        // Process all registered vacuum pipes
        for (Map.Entry<Vector3i, PipeComponent> entry : vacuumPipes.entrySet()) {
            Vector3i position = entry.getKey();
            PipeComponent pipe = entry.getValue();
            
            processVacuumPipe(position, pipe, store, buffer, deltaTime);
        }
    }

    /**
     * Process a single vacuum pipe's item collection logic
     */
    private void processVacuumPipe(Vector3i position, PipeComponent pipe, Store<EntityStore> store, 
                                   CommandBuffer<EntityStore> buffer, float deltaTime) {
        
        // LOGGER.atInfo().log(PREFIX + "processVacuumPipe: Processing vacuum pipe at " + position);
        
        // Skip if pipe is on cooldown
        if (pipe.isOnCooldown()) {
            // LOGGER.atInfo().log(PREFIX + "processVacuumPipe: Pipe is on cooldown, skipping");
            pipe.decrementCooldown(deltaTime);
            return;
        }

        // Skip if pipe buffer is full
        if (pipe.hasItem()) {
            // LOGGER.atInfo().log(PREFIX + "processVacuumPipe: Pipe buffer is full, skipping (let PipeSystem handle output)");
            return; // Let the regular PipeSystem handle pushing items out
        }

        // LOGGER.atInfo().log(PREFIX + "processVacuumPipe: Pipe is ready for item collection");

        World world = getWorld();
        if (world == null) {
            LOGGER.atWarning().log(PREFIX + "processVacuumPipe: World is null, aborting");
            return;
        }

        // Create bounding box around the vacuum pipe
        Vector3d pipePos = new Vector3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
        Vector3d minBounds = new Vector3d(
            pipePos.getX() - VACUUM_RANGE,
            pipePos.getY() - VACUUM_RANGE,
            pipePos.getZ() - VACUUM_RANGE
        );
        Vector3d maxBounds = new Vector3d(
            pipePos.getX() + VACUUM_RANGE,
            pipePos.getY() + VACUUM_RANGE,
            pipePos.getZ() + VACUUM_RANGE
        );

        // LOGGER.atInfo().log(PREFIX + "processVacuumPipe: Searching for items in bounds: " + minBounds + " to " + maxBounds);

        // Find all item entities within range
        List<Ref<EntityStore>> itemEntities = findItemEntitiesInBounds(store, minBounds, maxBounds);

        // LOGGER.atInfo().log(PREFIX + "processVacuumPipe: Found " + itemEntities.size() + " item entities to process");

        for (Ref<EntityStore> itemRef : itemEntities) {
            processItemEntity(itemRef, pipePos, pipe, store, buffer);
        }
    }

    /**
     * Find all item entities within the specified bounding box
     * Uses ECS query system to find entities with ItemComponent and TransformComponent
     */
    private List<Ref<EntityStore>> findItemEntitiesInBounds(Store<EntityStore> store, Vector3d minBounds, Vector3d maxBounds) {
        List<Ref<EntityStore>> itemEntities = new ArrayList<>();

        try {
            // LOGGER.atInfo().log(PREFIX + "Searching for item entities in bounds: " + minBounds + " to " + maxBounds);
            
            // Create a query for entities with both ItemComponent and TransformComponent
            Query<EntityStore> itemQuery = Query.and(
                ItemComponent.getComponentType(),
                TransformComponent.getComponentType()
            );
            
            // Iterate through all chunks matching the query
            store.forEachChunk(itemQuery, (chunk, buffer) -> {
                try {
                    // Iterate through entities in this chunk
                    for (int i = 0; i < chunk.size(); i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform != null) {
                            Vector3d entityPos = transform.getPosition();
                            
                            // Check if entity is within bounds
                            if (entityPos.getX() >= minBounds.getX() && entityPos.getX() <= maxBounds.getX() &&
                                entityPos.getY() >= minBounds.getY() && entityPos.getY() <= maxBounds.getY() &&
                                entityPos.getZ() >= minBounds.getZ() && entityPos.getZ() <= maxBounds.getZ()) {
                                
                                Ref<EntityStore> entityRef = chunk.getReferenceTo(i);
                                itemEntities.add(entityRef);
                                
                                // LOGGER.atInfo().log(PREFIX + "Found item entity at: " + entityPos);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Error processing chunk: " + e.getMessage());
                }
            });
            
            // LOGGER.atInfo().log(PREFIX + "Found " + itemEntities.size() + " item entities in bounds");
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error finding item entities: " + e.getMessage());
        }

        return itemEntities;
    }

    /**
     * Process a single item entity for vacuum collection
     */
    private void processItemEntity(Ref<EntityStore> itemRef, Vector3d pipePos, PipeComponent pipe, 
                                   Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        
        // LOGGER.atInfo().log(PREFIX + "processItemEntity: Starting to process item entity");
        
        ItemComponent itemComponent = store.getComponent(itemRef, ItemComponent.getComponentType());
        TransformComponent transform = store.getComponent(itemRef, TransformComponent.getComponentType());
        
        if (itemComponent == null) {
            // LOGGER.atInfo().log(PREFIX + "processItemEntity: ItemComponent is null, skipping");
            return;
        }
        
        if (transform == null) {
            // LOGGER.atInfo().log(PREFIX + "processItemEntity: TransformComponent is null, skipping");
            return;
        }

        // LOGGER.atInfo().log(PREFIX + "processItemEntity: Both components found, checking pickup eligibility");

        // Skip if item can't be picked up yet (has pickup delay)
        if (!itemComponent.canPickUp()) {
            // LOGGER.atInfo().log(PREFIX + "processItemEntity: Item cannot be picked up yet (pickup delay)");
            return;
        }

        // LOGGER.atInfo().log(PREFIX + "processItemEntity: Item can be picked up, calculating distance");

        Vector3d itemPos = transform.getPosition();
        double distance = calculateDistance(pipePos, itemPos);
        
        // LOGGER.atInfo().log(PREFIX + "processItemEntity: Item at distance: " + distance + " (pickup threshold: " + PICKUP_DISTANCE + ")");

        // If close enough, try to pick up the item
        if (distance <= PICKUP_DISTANCE) {
            // LOGGER.atInfo().log(PREFIX + "processItemEntity: Item is close enough for pickup, attempting collection...");
            tryPickupItem(itemRef, itemComponent, pipe, store, buffer);
        } else if (distance <= VACUUM_RANGE) {
            // LOGGER.atInfo().log(PREFIX + "processItemEntity: Item is within vacuum range, applying attraction force");
            // Apply attraction force to pull item towards pipe
            applyAttractionForce(itemRef, itemPos, pipePos, store);
        } else {
            // LOGGER.atInfo().log(PREFIX + "processItemEntity: Item is outside vacuum range (" + distance + " > " + VACUUM_RANGE + ")");
        }
    }

    /**
     * Calculate distance between two Vector3d points
     */
    private double calculateDistance(Vector3d pos1, Vector3d pos2) {
        double dx = pos1.getX() - pos2.getX();
        double dy = pos1.getY() - pos2.getY();
        double dz = pos1.getZ() - pos2.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Try to pick up an item into the vacuum pipe's buffer
     */
    private void tryPickupItem(Ref<EntityStore> itemRef, ItemComponent itemComponent, PipeComponent pipe, 
                               Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        
        // LOGGER.atInfo().log(PREFIX + "tryPickupItem: Starting pickup attempt");
        
        ItemStack itemStack = itemComponent.getItemStack();
        if (itemStack == null) {
            // LOGGER.atInfo().log(PREFIX + "tryPickupItem: ItemStack is null, aborting");
            return;
        }
        
        if (itemStack.isEmpty()) {
            // LOGGER.atInfo().log(PREFIX + "tryPickupItem: ItemStack is empty, aborting");
            return;
        }
        
        // LOGGER.atInfo().log(PREFIX + "tryPickupItem: ItemStack found - " + itemStack.getItemId() + " x" + itemStack.getQuantity());

        // Try to add item to pipe buffer
        ItemContainer pipeBuffer = pipe.getBuffer();
        if (pipeBuffer == null) {
            LOGGER.atWarning().log(PREFIX + "tryPickupItem: Pipe buffer is null, aborting");
            return;
        }
        
        // LOGGER.atInfo().log(PREFIX + "tryPickupItem: Pipe buffer available, attempting to add item");

        try {
            ItemStackTransaction transaction = pipeBuffer.addItemStack(itemStack);
            // LOGGER.atInfo().log(PREFIX + "tryPickupItem: Transaction created, checking success...");
            
            if (transaction.succeeded()) {
                // LOGGER.atInfo().log(PREFIX + "tryPickupItem: Transaction succeeded! Removing item entity...");
                
                // Successfully added to pipe buffer, remove the item entity
                try {
                    // Use the correct removeEntity method with RemoveReason
                    buffer.removeEntity(itemRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
                    
                    // LOGGER.atInfo().log(PREFIX + "Vacuum pipe collected: " + itemStack.getItemId() + " x" + itemStack.getQuantity());
                    
                    // Set cooldown to prevent immediate processing
                    pipe.startCooldown();
                    
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Error removing item entity: " + e.getMessage());
                    e.printStackTrace();
                    // If we can't remove the entity, at least try to remove the item from buffer to prevent duplication
                    try {
                        pipeBuffer.removeItemStackFromSlot((short) 0);
                        // LOGGER.atInfo().log(PREFIX + "Successfully reverted buffer after entity removal failure");
                    } catch (Exception e2) {
                        LOGGER.atWarning().log(PREFIX + "Error reverting buffer: " + e2.getMessage());
                        e2.printStackTrace();
                    }
                }
            } else {
                // LOGGER.atInfo().log(PREFIX + "tryPickupItem: Transaction failed - buffer might be full or incompatible item");
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "tryPickupItem: Exception during transaction: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Apply attraction force to pull item towards the vacuum pipe
     */
    private void applyAttractionForce(Ref<EntityStore> itemRef, Vector3d itemPos, Vector3d pipePos, Store<EntityStore> store) {
        try {
            Velocity velocity = store.getComponent(itemRef, Velocity.getComponentType());
            if (velocity == null) return;

            // Calculate direction vector from item to pipe
            Vector3d direction = new Vector3d(
                pipePos.getX() - itemPos.getX(),
                pipePos.getY() - itemPos.getY(),
                pipePos.getZ() - itemPos.getZ()
            );

            // Normalize and apply attraction force
            double length = calculateDistance(pipePos, itemPos);
            if (length > 0) {
                direction = new Vector3d(
                    direction.getX() / length * ATTRACTION_FORCE,
                    direction.getY() / length * ATTRACTION_FORCE,
                    direction.getZ() / length * ATTRACTION_FORCE
                );

                // Try to apply velocity using addForce method (simpler approach)
                velocity.addForce(direction.getX(), direction.getY(), direction.getZ());
                
                // LOGGER.atInfo().log(PREFIX + "Applied attraction force to item: " + direction);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Error applying attraction force: " + e.getMessage());
        }
    }

    /**
     * Register a vacuum pipe at the specified position
     */
    public void registerVacuumPipe(Vector3i position, PipeComponent pipeComponent) {
        vacuumPipes.put(position, pipeComponent);
        // LOGGER.atInfo().log(PREFIX + "Registered vacuum pipe at " + position);
    }

    /**
     * Unregister a vacuum pipe at the specified position
     */
    public void unregisterVacuumPipe(Vector3i position) {
        PipeComponent removed = vacuumPipes.remove(position);
        if (removed != null) {
            // LOGGER.atInfo().log(PREFIX + "Unregistered vacuum pipe at " + position);
        }
    }

    /**
     * Get the world from the Universe
     */
    private World getWorld() {
        try {
            return Universe.get().getDefaultWorld();
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to get world: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get all registered vacuum pipes (for debugging/management)
     */
    public Map<Vector3i, PipeComponent> getVacuumPipes() {
        return new HashMap<>(vacuumPipes);
    }

    /**
     * Clear all registered vacuum pipes
     */
    public void clearAll() {
        vacuumPipes.clear();
        // LOGGER.atInfo().log(PREFIX + "Cleared all vacuum pipes");
    }

    /**
     * Save vacuum pipes to file
     */
    public void saveVacuumPipes() {
        try {
            // Create JSON array for vacuum pipes
            StringBuilder json = new StringBuilder();
            json.append("[\n");
            
            boolean first = true;
            for (Map.Entry<Vector3i, PipeComponent> entry : vacuumPipes.entrySet()) {
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
            
            // LOGGER.atInfo().log(PREFIX + "Saved " + vacuumPipes.size() + " vacuum pipes to " + SAVE_FILE);
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save vacuum pipes: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load vacuum pipes from file
     */
    public void loadVacuumPipes() {
        try {
            java.nio.file.Path saveFile = java.nio.file.Paths.get(SAVE_FILE);
            if (!java.nio.file.Files.exists(saveFile)) {
                // LOGGER.atInfo().log(PREFIX + "No vacuum pipe save file found at " + SAVE_FILE);
                return;
            }
            
            String content = java.nio.file.Files.readString(saveFile);
            
            // Simple JSON parsing (since we control the format)
            content = content.trim();
            if (!content.startsWith("[") || !content.endsWith("]")) {
                LOGGER.atWarning().log(PREFIX + "Invalid vacuum pipe save file format");
                return;
            }
            
            // Remove brackets and split by objects
            content = content.substring(1, content.length() - 1).trim();
            if (content.isEmpty()) {
                // LOGGER.atInfo().log(PREFIX + "No vacuum pipes to load from save file");
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
                    
                    // Register the vacuum pipe in VacuumSystem
                    vacuumPipes.put(pos, pipeComponent);
                    
                    // Also register in PipeSystem (vacuum pipes are also regular pipes)
                    if (plugin.getPipeSystem() != null) {
                        plugin.getPipeSystem().registerPipe(pos, pipeComponent);
                    }
                    
                    loadedCount++;
                    
                    // LOGGER.atInfo().log(PREFIX + "Loaded vacuum pipe at " + pos + " with output direction: " + outputDirection);
                    
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Failed to parse vacuum pipe object: " + pipeObj + " - " + e.getMessage());
                }
            }
            
            // LOGGER.atInfo().log(PREFIX + "Loaded " + loadedCount + " vacuum pipes from save file");
            
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load vacuum pipes: " + e.getMessage());
            e.printStackTrace();
        }
    }
}