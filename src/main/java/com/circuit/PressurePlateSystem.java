package com.circuit;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player positions and automatically activates/deactivates pressure
 * plates.
 * Based on PFC's PressurePlate implementation with improvements.
 * Checks every 50ms (20 times per second) for responsive detection.
 */
public class PressurePlateSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-PressurePlate] ";

    private final CircuitPlugin plugin;
    private double accumulatedTime = 0.0;
    private static final double CHECK_INTERVAL = 0.05; // Check every 50ms (20Hz)
    private static final long DEBOUNCE_MS = 150L; // Debounce delay before deactivating

    // Track which players are currently on plates
    private final Set<UUID> playersOnPlate = new HashSet<>();
    private final Map<UUID, Vector3i> pressedPlates = new HashMap<>();
    private final Map<UUID, Long> pendingReleases = new HashMap<>();

    // Track active pressure plates
    private final Map<CircuitPos, Long> activePressurePlates = new ConcurrentHashMap<>();

    public PressurePlateSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
        // LOGGER.atInfo().log(PREFIX + "PressurePlateSystem initialized with 50ms check
        // interval");
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        accumulatedTime += dt;

        if (accumulatedTime < CHECK_INTERVAL) {
            return;
        }
        accumulatedTime -= CHECK_INTERVAL;

        // Get all players from Universe and check for pressure plates
        try {
            Universe universe = Universe.get();
            if (universe == null)
                return;

            World world = universe.getDefaultWorld();
            if (world == null)
                return;

            world.execute(() -> {
                try {
                    processPlayers(world);
                } catch (Exception e) {
                    // Silently ignore errors
                }
            });
        } catch (Exception e) {
            // Silently fail - don't spam logs
        }
    }

    private void processPlayers(World world) {
        long currentTime = System.currentTimeMillis();

        // Get all players
        Collection<PlayerRef> players = Universe.get().getPlayers();
        if (players == null || players.isEmpty())
            return;

        Set<UUID> currentlyIntersecting = new HashSet<>();
        Map<UUID, Vector3i> currentPlates = new HashMap<>();
        Map<UUID, BlockType> currentBlockTypes = new HashMap<>();

        // Check each player
        for (PlayerRef playerRef : players) {
            try {
                UUID uuid = playerRef.getUuid();
                Transform transform = playerRef.getTransform();
                if (transform == null)
                    continue;

                // Use wider detection area (0.7 block width total, like PFC)
                double width = 0.35;
                double x = transform.getPosition().getX();
                double y = transform.getPosition().getY();
                double z = transform.getPosition().getZ();

                int minX = (int) Math.floor(x - width);
                int maxX = (int) Math.floor(x + width);
                int minZ = (int) Math.floor(z - width);
                int maxZ = (int) Math.floor(z + width);
                int playerY = (int) Math.floor(y);

                Vector3i foundPlatePos = null;
                BlockType foundBlockType = null;

                // Search area around player's feet
                searchLoop: for (int bx = minX; bx <= maxX; bx++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        for (int by = playerY; by >= playerY - 1; by--) {
                            Vector3i checkPos = new Vector3i(bx, by, bz);
                            BlockType checkBlock = world.getBlockType(checkPos.x, checkPos.y, checkPos.z);

                            if (checkBlock != null) {
                                String checkId = checkBlock.getId();
                                if (checkId != null && checkId.contains("Circuit_Pressure_Plate")) {
                                    foundBlockType = checkBlock;
                                    foundPlatePos = checkPos;
                                    break searchLoop;
                                }
                            }
                        }
                    }
                }

                if (foundPlatePos != null && foundBlockType != null) {
                    currentlyIntersecting.add(uuid);
                    currentPlates.put(uuid, foundPlatePos);
                    currentBlockTypes.put(uuid, foundBlockType);
                }

            } catch (Exception e) {
                // Skip this player
            }
        }

        // Handle newly intersecting players
        for (UUID uuid : currentlyIntersecting) {
            Vector3i newPos = currentPlates.get(uuid);
            BlockType newType = currentBlockTypes.get(uuid);

            // Cancel pending release if player returned
            pendingReleases.remove(uuid);

            if (playersOnPlate.contains(uuid)) {
                // Player was already on a plate - check if they moved to a different one
                Vector3i oldPos = pressedPlates.get(uuid);
                if (oldPos != null && !oldPos.equals(newPos)) {
                    deactivatePlate(oldPos, world);
                    activatePlate(newPos, newType, world);
                    pressedPlates.put(uuid, newPos);
                } else {
                    // Player still on same plate - KEEP ACTIVATING to refresh pulse
                    activatePlate(newPos, newType, world);
                }
            } else {
                // Player just stepped on a plate
                playersOnPlate.add(uuid);
                pressedPlates.put(uuid, newPos);
                activatePlate(newPos, newType, world);
            }
        }

        // Handle players who left plates (with debounce)
        Set<UUID> previouslyOnPlate = new HashSet<>(playersOnPlate);
        previouslyOnPlate.removeAll(currentlyIntersecting);

        for (UUID uuid : previouslyOnPlate) {
            if (!pendingReleases.containsKey(uuid)) {
                pendingReleases.put(uuid, currentTime + DEBOUNCE_MS);
            }
        }

        // Process pending releases
        List<UUID> releasesToProcess = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : pendingReleases.entrySet()) {
            if (!currentlyIntersecting.contains(entry.getKey()) && currentTime >= entry.getValue()) {
                releasesToProcess.add(entry.getKey());
            }
        }

        for (UUID uuid : releasesToProcess) {
            pendingReleases.remove(uuid);
            playersOnPlate.remove(uuid);
            Vector3i platePos = pressedPlates.remove(uuid);
            if (platePos != null) {
                deactivatePlate(platePos, world);
            }
        }
    }

    private void activatePlate(Vector3i pos, BlockType plateType, World world) {
        try {
            // Set block interaction state for animation
            world.setBlockInteractionState(pos, plateType, "Pressed");
        } catch (Exception e) {
            // Silently ignore animation errors
        }

        // Determine plate type
        String blockTypeId = plateType.getId();
        ButtonSystem.PulseType pulseType = blockTypeId != null && blockTypeId.contains("Wood")
                ? ButtonSystem.PulseType.PRESSURE_PLATE_WOOD
                : ButtonSystem.PulseType.PRESSURE_PLATE_STONE;

        // Register and activate in ButtonSystem
        if (plugin.getButtonSystem() != null) {
            if (!plugin.getButtonSystem().isButtonAt(pos)) {
                plugin.getButtonSystem().registerPressurePlate(pos, pulseType);
            }
            plugin.getButtonSystem().activateButton(pos);

            // Update energy network
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNetwork(pos);
            }
        }

        // Track in active plates
        CircuitPos cpos = CircuitPos.from(pos);
        activePressurePlates.put(cpos, System.currentTimeMillis());

        // LOGGER.atInfo().log(PREFIX + "Activated pressure plate at " + pos);
    }

    private void deactivatePlate(Vector3i pos, World world) {
        try {
            // Set block interaction state for animation
            BlockType plateType = world.getBlockType(pos.x, pos.y, pos.z);
            if (plateType != null) {
                world.setBlockInteractionState(pos, plateType, "Unpressed");
            }
        } catch (Exception e) {
            // Silently ignore animation errors
        }

        // Deactivate in ButtonSystem
        if (plugin.getButtonSystem() != null) {
            plugin.getButtonSystem().forcePressurePlateDeactivate(pos);

            // Update energy network
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNetwork(pos);
            }
        }

        // Remove from tracking
        CircuitPos cpos = CircuitPos.from(pos);
        activePressurePlates.remove(cpos);

        // LOGGER.atInfo().log(PREFIX + "Deactivated pressure plate at " + pos);
    }

    /**
     * Register a pressure plate as active.
     */
    public void registerPressurePlate(Vector3i pos) {
        CircuitPos cpos = CircuitPos.from(pos);
        activePressurePlates.put(cpos, System.currentTimeMillis());
        // LOGGER.atInfo().log(PREFIX + "Pressure plate registered at " + pos);
    }

    /**
     * Called when a pressure plate block is broken.
     * Clean up tracking for this plate.
     */
    public void onPressurePlateRemoved(Vector3i pos) {
        CircuitPos platePos = CircuitPos.from(pos);
        activePressurePlates.remove(platePos);

        // Clean up player tracking for this plate
        pressedPlates.values().removeIf(p -> p.equals(pos));

        // LOGGER.atInfo().log(PREFIX + "Pressure plate removed at " + pos);
    }
}
