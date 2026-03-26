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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
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
 * Manages Detector Rails (Sensör Ray) - rails that emit a circuit signal
 * when a minecart (or player riding a minecart) passes over them.
 * 
 * Based on PressurePlateSystem's entity detection pattern.
 * 
 * Detection works by:
 * 1. Checking all player positions every tick (20Hz)
 * 2. If a player is on or near a detector rail position, activate
 * 3. When the player leaves, deactivate after a short debounce period
 * 
 * The detector rail emits power level 15 when active, 0 when inactive.
 * It acts as a power SOURCE in the circuit network (like a lever/button).
 */
public class DetectorRailSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-DetectorRail] ";

    private final CircuitPlugin plugin;
    private double accumulatedTime = 0.0;
    private static final double CHECK_INTERVAL = 0.05; // 20Hz check rate (same as PressurePlateSystem)
    private static final long DEBOUNCE_MS = 200L;      // Debounce delay before deactivating

    /**
     * Data for a single detector rail.
     */
    public static class DetectorRailData {
        public final Vector3i position;
        public boolean isActive;       // Currently detecting a minecart/player
        public long lastActiveTime;    // Timestamp of last activation (for debounce)
        public int rotationIndex;      // Block placement rotation

        public DetectorRailData(Vector3i position, int rotationIndex) {
            this.position = position;
            this.isActive = false;
            this.lastActiveTime = 0;
            this.rotationIndex = rotationIndex;
        }
    }

    // Registered detector rails
    private final Map<CircuitPos, DetectorRailData> detectorRails = new ConcurrentHashMap<>();

    // Track which players are currently on detector rails
    private final Set<UUID> playersOnDetector = new HashSet<>();
    private final Map<UUID, Vector3i> detectedPositions = new HashMap<>();
    private final Map<UUID, Long> pendingReleases = new HashMap<>();

    public DetectorRailSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (detectorRails.isEmpty()) return;

        accumulatedTime += dt;
        if (accumulatedTime < CHECK_INTERVAL) return;
        accumulatedTime -= CHECK_INTERVAL;

        try {
            Universe universe = Universe.get();
            if (universe == null) return;

            World world = universe.getDefaultWorld();
            if (world == null) return;

            world.execute(() -> {
                try {
                    processDetection(world);
                } catch (Exception e) {
                    // Silently ignore
                }
            });
        } catch (Exception e) {
            // Silently fail
        }
    }

    /**
     * Check all players for those on detector rails.
     * Modeled after PressurePlateSystem.processPlayers().
     */
    private void processDetection(World world) {
        long currentTime = System.currentTimeMillis();

        Collection<PlayerRef> players = Universe.get().getPlayers();
        if (players == null || players.isEmpty()) return;

        Set<UUID> currentlyIntersecting = new HashSet<>();
        Map<UUID, Vector3i> currentDetectors = new HashMap<>();
        Map<UUID, BlockType> currentBlockTypes = new HashMap<>();

        // Check each player
        for (PlayerRef playerRef : players) {
            try {
                UUID uuid = playerRef.getUuid();
                Transform transform = playerRef.getTransform();
                if (transform == null) continue;

                // Use detection area similar to PressurePlateSystem
                double width = 0.35;
                double x = transform.getPosition().getX();
                double y = transform.getPosition().getY();
                double z = transform.getPosition().getZ();

                int minX = (int) Math.floor(x - width);
                int maxX = (int) Math.floor(x + width);
                int minZ = (int) Math.floor(z - width);
                int maxZ = (int) Math.floor(z + width);
                int playerY = (int) Math.floor(y);

                Vector3i foundDetectorPos = null;
                BlockType foundBlockType = null;

                // Search area around player's feet
                searchLoop:
                for (int bx = minX; bx <= maxX; bx++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        for (int by = playerY; by >= playerY - 1; by--) {
                            Vector3i checkPos = new Vector3i(bx, by, bz);
                            CircuitPos circuitPos = CircuitPos.from(checkPos);

                            // Check if this position has a registered detector rail
                            if (detectorRails.containsKey(circuitPos)) {
                                // Also verify the block still exists in the world
                                BlockType checkBlock = world.getBlockType(checkPos.x, checkPos.y, checkPos.z);
                                if (checkBlock != null) {
                                    String checkId = checkBlock.getId();
                                    if (checkId != null && checkId.contains("Circuit_Detector_Rail")) {
                                        foundBlockType = checkBlock;
                                        foundDetectorPos = checkPos;
                                        break searchLoop;
                                    }
                                }
                            }
                        }
                    }
                }

                if (foundDetectorPos != null && foundBlockType != null) {
                    currentlyIntersecting.add(uuid);
                    currentDetectors.put(uuid, foundDetectorPos);
                    currentBlockTypes.put(uuid, foundBlockType);
                }
            } catch (Exception e) {
                // Skip this player
            }
        }

        // Handle newly intersecting players
        for (UUID uuid : currentlyIntersecting) {
            Vector3i newPos = currentDetectors.get(uuid);
            BlockType newType = currentBlockTypes.get(uuid);

            // Cancel pending release
            pendingReleases.remove(uuid);

            if (playersOnDetector.contains(uuid)) {
                // Player already on a detector - check if moved to different one
                Vector3i oldPos = detectedPositions.get(uuid);
                if (oldPos != null && !oldPos.equals(newPos)) {
                    deactivateDetector(oldPos, world);
                    activateDetector(newPos, newType, world);
                    detectedPositions.put(uuid, newPos);
                }
                // If on same detector, keep it active (no action needed)
            } else {
                // Player just stepped on a detector rail
                playersOnDetector.add(uuid);
                detectedPositions.put(uuid, newPos);
                activateDetector(newPos, newType, world);
            }
        }

        // Handle players who left detectors (with debounce)
        Set<UUID> previouslyOnDetector = new HashSet<>(playersOnDetector);
        previouslyOnDetector.removeAll(currentlyIntersecting);

        for (UUID uuid : previouslyOnDetector) {
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
            playersOnDetector.remove(uuid);
            Vector3i detectorPos = detectedPositions.remove(uuid);
            if (detectorPos != null) {
                // Check if another player is still on this detector
                boolean anotherPlayerOnSameDetector = false;
                for (Map.Entry<UUID, Vector3i> entry : detectedPositions.entrySet()) {
                    if (entry.getValue().equals(detectorPos)) {
                        anotherPlayerOnSameDetector = true;
                        break;
                    }
                }

                if (!anotherPlayerOnSameDetector) {
                    deactivateDetector(detectorPos, world);
                }
            }
        }
    }

    /**
     * Activate a detector rail - it's now detecting a minecart/player.
     */
    private void activateDetector(Vector3i pos, BlockType blockType, World world) {
        CircuitPos cpos = CircuitPos.from(pos);
        DetectorRailData data = detectorRails.get(cpos);
        if (data == null) return;

        boolean wasActive = data.isActive;
        data.isActive = true;
        data.lastActiveTime = System.currentTimeMillis();

        // Update block visual state
        try {
            world.setBlockInteractionState(pos, blockType, "Active");
        } catch (Exception e) {
            // Silently ignore visual errors
        }

        // Only trigger circuit update on new activation
        if (!wasActive) {
            // Update energy network - detector rail now emits power
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNetwork(pos);
            }
        }
    }

    /**
     * Deactivate a detector rail - no more minecart/player detected.
     */
    private void deactivateDetector(Vector3i pos, World world) {
        CircuitPos cpos = CircuitPos.from(pos);
        DetectorRailData data = detectorRails.get(cpos);
        if (data == null) return;

        boolean wasActive = data.isActive;
        data.isActive = false;

        // Update block visual state
        try {
            BlockType blockType = world.getBlockType(pos.x, pos.y, pos.z);
            if (blockType != null) {
                world.setBlockInteractionState(pos, blockType, "Inactive");
            }
        } catch (Exception e) {
            // Silently ignore visual errors
        }

        // Only trigger circuit update on deactivation
        if (wasActive) {
            // Update energy network - detector rail stops emitting power
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNetwork(pos);
            }
        }
    }

    // ==================== Registration API ====================

    /**
     * Register a detector rail at the given position.
     */
    public void registerDetectorRail(Vector3i pos, int rotationIndex) {
        CircuitPos cpos = CircuitPos.from(pos);
        detectorRails.put(cpos, new DetectorRailData(pos, rotationIndex));
    }

    /**
     * Unregister a detector rail at the given position.
     */
    public void unregisterDetectorRail(Vector3i pos) {
        CircuitPos cpos = CircuitPos.from(pos);
        detectorRails.remove(cpos);

        // Clean up player tracking
        detectedPositions.values().removeIf(p -> p.equals(pos));
    }

    /**
     * Check if a detector rail exists at the given position.
     */
    public boolean isDetectorRailAt(Vector3i pos) {
        return detectorRails.containsKey(CircuitPos.from(pos));
    }

    /**
     * Get the detector rail data at the given position.
     */
    public DetectorRailData getDetectorRailAt(Vector3i pos) {
        return detectorRails.get(CircuitPos.from(pos));
    }

    /**
     * Check if the detector rail at the given position is currently active.
     */
    public boolean isDetectorRailActive(Vector3i pos) {
        DetectorRailData data = detectorRails.get(CircuitPos.from(pos));
        return data != null && data.isActive;
    }

    /**
     * Get the power output of a detector rail.
     * Active detectors emit power level 15, inactive emit 0.
     * This is used by EnergyPropagationSystem.
     */
    public int getPowerOutput(Vector3i pos) {
        return isDetectorRailActive(pos) ? 15 : 0;
    }

    /**
     * Called when a detector rail is removed.
     */
    public void onDetectorRailRemoved(Vector3i pos) {
        unregisterDetectorRail(pos);

        // Ensure circuit updates when detector is removed
        if (plugin.getEnergySystem() != null) {
            plugin.getEnergySystem().handleBreak(pos);
        }
    }

    // ==================== Persistence ====================

    private static final String DETECTOR_RAILS_FILE = "detector_rails.dat";

    public void saveDetectorRails(Path dataDirectory) {
        if (detectorRails.isEmpty()) return;

        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path filePath = dataDirectory.resolve(DETECTOR_RAILS_FILE);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Map.Entry<CircuitPos, DetectorRailData> entry : detectorRails.entrySet()) {
                    DetectorRailData data = entry.getValue();
                    writer.write(data.position.getX() + "," +
                            data.position.getY() + "," +
                            data.position.getZ() + "," +
                            data.isActive + "," +
                            data.rotationIndex);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save detector rails: " + e.getMessage());
        }
    }

    public void loadDetectorRails(Path dataDirectory) {
        Path filePath = dataDirectory.resolve(DETECTOR_RAILS_FILE);
        if (!Files.exists(filePath)) return;

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length < 5) continue;

                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    boolean active = Boolean.parseBoolean(parts[3].trim());
                    int rotation = Integer.parseInt(parts[4].trim());

                    Vector3i pos = new Vector3i(x, y, z);
                    DetectorRailData data = new DetectorRailData(pos, rotation);
                    data.isActive = active;

                    detectorRails.put(CircuitPos.from(pos), data);
                } catch (NumberFormatException e) {
                    // Skip invalid lines
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load detector rails: " + e.getMessage());
        }
    }
}
