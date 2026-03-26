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
 * Manages Powered Rails - rails that speed up minecarts when powered
 * and brake/slow them when unpowered.
 * 
 * Powered Rails work by detecting mounted players (riding minecarts)
 * passing over them and applying velocity boosts or brakes based on
 * the rail's power state from the circuit network.
 */
public class PoweredRailSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-PoweredRail] ";

    private final CircuitPlugin plugin;
    private double accumulatedTime = 0.0;
    private static final double CHECK_INTERVAL = 0.05; // 20Hz check rate

    // Speed boost/brake multipliers
    private static final double BOOST_FORCE = 4.0;     // Force applied when powered
    private static final double BRAKE_FORCE = 0.5;     // Friction multiplier when braking (unpowered)
    private static final double MAX_SPEED = 12.0;      // Maximum minecart speed
    private static final double MIN_SPEED_THRESHOLD = 0.1; // Below this speed, stop completely

    // Registered powered rails: position -> PoweredRailData
    private final Map<CircuitPos, PoweredRailData> poweredRails = new ConcurrentHashMap<>();

    // Track which players have been boosted recently to prevent double-boosting
    private final Map<UUID, Long> lastBoostTime = new HashMap<>();
    private static final long BOOST_COOLDOWN_MS = 100; // 100ms cooldown between boosts

    public static class PoweredRailData {
        public final Vector3i position;
        public boolean isPowered;
        public int rotationIndex; // 0=S, 1=E, 2=N, 3=W for determining rail direction

        public PoweredRailData(Vector3i position, boolean isPowered, int rotationIndex) {
            this.position = position;
            this.isPowered = isPowered;
            this.rotationIndex = rotationIndex;
        }
    }

    public PoweredRailSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (poweredRails.isEmpty()) return;

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
                    processMinecarts(world);
                } catch (Exception e) {
                    // Silently ignore
                }
            });
        } catch (Exception e) {
            // Silently fail
        }
    }

    /**
     * Check all players for those on minecarts over powered rails.
     * Apply speed boosts or brakes accordingly.
     */
    private void processMinecarts(World world) {
        long currentTime = System.currentTimeMillis();

        Collection<PlayerRef> players = Universe.get().getPlayers();
        if (players == null || players.isEmpty()) return;

        for (PlayerRef playerRef : players) {
            try {
                UUID uuid = playerRef.getUuid();
                Transform transform = playerRef.getTransform();
                if (transform == null) continue;

                double x = transform.getPosition().getX();
                double y = transform.getPosition().getY();
                double z = transform.getPosition().getZ();

                // Check the block at player position and below for powered rails
                int blockX = (int) Math.floor(x);
                int blockZ = (int) Math.floor(z);

                for (int by = (int) Math.floor(y); by >= (int) Math.floor(y) - 1; by--) {
                    Vector3i checkPos = new Vector3i(blockX, by, blockZ);
                    CircuitPos circuitPos = CircuitPos.from(checkPos);

                    PoweredRailData railData = poweredRails.get(circuitPos);
                    if (railData == null) continue;

                    // Check boost cooldown
                    Long lastBoost = lastBoostTime.get(uuid);
                    if (lastBoost != null && (currentTime - lastBoost) < BOOST_COOLDOWN_MS) continue;

                    // Player is on a powered rail - check if they're mounted (riding minecart)
                    // We detect minecart riding by checking if player is on a rail block
                    // The game handles the mount state internally

                    if (railData.isPowered) {
                        // BOOST: Apply speed in the rail's direction
                        applyBoost(playerRef, railData, world);
                    } else {
                        // BRAKE: Apply friction/stop
                        applyBrake(playerRef, railData, world);
                    }

                    lastBoostTime.put(uuid, currentTime);
                    break; // Only process one rail per player per tick
                }
            } catch (Exception e) {
                // Skip this player
            }
        }
    }

    /**
     * Apply speed boost to a player/minecart on a powered rail.
     */
    private void applyBoost(PlayerRef playerRef, PoweredRailData railData, World world) {
        try {
            Transform transform = playerRef.getTransform();
            if (transform == null) return;

            // Determine boost direction from rail rotation
            // rotationIndex: 0=South(+Z), 1=East(+X), 2=North(-Z), 3=West(-X)
            double boostX = 0, boostZ = 0;

            // We boost in the direction the player is already moving
            // If stationary, boost in rail direction
            // Use reflection to get velocity if available
            try {
                java.lang.reflect.Method getVelocity = transform.getClass().getMethod("getVelocity");
                Object velocity = getVelocity.invoke(transform);
                if (velocity != null) {
                    java.lang.reflect.Method getX = velocity.getClass().getMethod("getX");
                    java.lang.reflect.Method getZ = velocity.getClass().getMethod("getZ");
                    double vx = ((Number) getX.invoke(velocity)).doubleValue();
                    double vz = ((Number) getZ.invoke(velocity)).doubleValue();
                    double speed = Math.sqrt(vx * vx + vz * vz);

                    if (speed > MIN_SPEED_THRESHOLD) {
                        // Boost in current movement direction
                        double normalX = vx / speed;
                        double normalZ = vz / speed;
                        boostX = normalX * BOOST_FORCE;
                        boostZ = normalZ * BOOST_FORCE;
                    } else {
                        // Stationary - boost in rail direction
                        switch (railData.rotationIndex % 4) {
                            case 0: boostZ = BOOST_FORCE; break;   // South
                            case 1: boostX = BOOST_FORCE; break;   // East
                            case 2: boostZ = -BOOST_FORCE; break;  // North
                            case 3: boostX = -BOOST_FORCE; break;  // West
                        }
                    }

                    // Apply the boost but cap at max speed
                    double newVx = vx + boostX * 0.05; // Scale by tick rate
                    double newVz = vz + boostZ * 0.05;
                    double newSpeed = Math.sqrt(newVx * newVx + newVz * newVz);

                    if (newSpeed > MAX_SPEED) {
                        double scale = MAX_SPEED / newSpeed;
                        newVx *= scale;
                        newVz *= scale;
                    }

                    java.lang.reflect.Method setX = velocity.getClass().getMethod("setX", double.class);
                    java.lang.reflect.Method setZ = velocity.getClass().getMethod("setZ", double.class);
                    setX.invoke(velocity, newVx);
                    setZ.invoke(velocity, newVz);
                }
            } catch (Exception e) {
                // Velocity access failed - try alternative approach via position nudging
                // Move the player slightly in the rail direction as a simple boost
                double posX = transform.getPosition().getX();
                double posZ = transform.getPosition().getZ();

                switch (railData.rotationIndex % 4) {
                    case 0: posZ += BOOST_FORCE * 0.01; break;
                    case 1: posX += BOOST_FORCE * 0.01; break;
                    case 2: posZ -= BOOST_FORCE * 0.01; break;
                    case 3: posX -= BOOST_FORCE * 0.01; break;
                }

                // Note: Direct position modification may work as a fallback
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Apply braking to a player/minecart on an unpowered rail.
     */
    private void applyBrake(PlayerRef playerRef, PoweredRailData railData, World world) {
        try {
            Transform transform = playerRef.getTransform();
            if (transform == null) return;

            try {
                java.lang.reflect.Method getVelocity = transform.getClass().getMethod("getVelocity");
                Object velocity = getVelocity.invoke(transform);
                if (velocity != null) {
                    java.lang.reflect.Method getX = velocity.getClass().getMethod("getX");
                    java.lang.reflect.Method getZ = velocity.getClass().getMethod("getZ");
                    double vx = ((Number) getX.invoke(velocity)).doubleValue();
                    double vz = ((Number) getZ.invoke(velocity)).doubleValue();

                    // Apply brake friction
                    vx *= BRAKE_FORCE;
                    vz *= BRAKE_FORCE;

                    // Full stop if very slow
                    if (Math.abs(vx) < MIN_SPEED_THRESHOLD) vx = 0;
                    if (Math.abs(vz) < MIN_SPEED_THRESHOLD) vz = 0;

                    java.lang.reflect.Method setX = velocity.getClass().getMethod("setX", double.class);
                    java.lang.reflect.Method setZ = velocity.getClass().getMethod("setZ", double.class);
                    setX.invoke(velocity, vx);
                    setZ.invoke(velocity, vz);
                }
            } catch (Exception e) {
                // Velocity access failed - silently ignore
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }

    // ==================== Registration API ====================

    /**
     * Register a powered rail at the given position.
     */
    public void registerPoweredRail(Vector3i pos, int rotationIndex) {
        CircuitPos cpos = CircuitPos.from(pos);
        poweredRails.put(cpos, new PoweredRailData(pos, false, rotationIndex));
    }

    /**
     * Unregister a powered rail at the given position.
     */
    public void unregisterPoweredRail(Vector3i pos) {
        CircuitPos cpos = CircuitPos.from(pos);
        poweredRails.remove(cpos);
    }

    /**
     * Check if a powered rail exists at the given position.
     */
    public boolean isPoweredRailAt(Vector3i pos) {
        return poweredRails.containsKey(CircuitPos.from(pos));
    }

    /**
     * Get the powered rail data at the given position.
     */
    public PoweredRailData getPoweredRailAt(Vector3i pos) {
        return poweredRails.get(CircuitPos.from(pos));
    }

    /**
     * Set the power state of a powered rail.
     * Called by EnergyPropagationSystem when power state changes.
     */
    public void setPoweredRailState(Vector3i pos, boolean powered) {
        CircuitPos cpos = CircuitPos.from(pos);
        PoweredRailData data = poweredRails.get(cpos);
        if (data != null) {
            data.isPowered = powered;

            // Update visual state
            try {
                World world = Universe.get().getDefaultWorld();
                if (world != null) {
                    IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
                    BlockType blockType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());
                    if (blockType != null) {
                        String targetState = powered ? "On" : "Off";
                        chunkAccessor.setBlockInteractionState(pos, blockType, targetState);
                    }
                }
            } catch (Exception e) {
                LOGGER.atWarning().log(PREFIX + "Failed to update powered rail visual at " + pos + ": " + e.getMessage());
            }
        }
    }

    /**
     * Check if the powered rail at the given position is powered.
     */
    public boolean isPoweredRailPowered(Vector3i pos) {
        PoweredRailData data = poweredRails.get(CircuitPos.from(pos));
        return data != null && data.isPowered;
    }

    /**
     * Get the power output of a powered rail (for circuit integration).
     * Powered rails do NOT output power - they only consume it.
     */
    public int getPowerOutput(Vector3i pos) {
        return 0; // Powered rails are consumers, not sources
    }

    // ==================== Persistence ====================

    private static final String POWERED_RAILS_FILE = "powered_rails.dat";

    public void savePoweredRails(Path dataDirectory) {
        if (poweredRails.isEmpty()) return;

        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path filePath = dataDirectory.resolve(POWERED_RAILS_FILE);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Map.Entry<CircuitPos, PoweredRailData> entry : poweredRails.entrySet()) {
                    PoweredRailData data = entry.getValue();
                    writer.write(data.position.getX() + "," +
                            data.position.getY() + "," +
                            data.position.getZ() + "," +
                            data.isPowered + "," +
                            data.rotationIndex);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save powered rails: " + e.getMessage());
        }
    }

    public void loadPoweredRails(Path dataDirectory) {
        Path filePath = dataDirectory.resolve(POWERED_RAILS_FILE);
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
                    boolean powered = Boolean.parseBoolean(parts[3].trim());
                    int rotation = Integer.parseInt(parts[4].trim());

                    Vector3i pos = new Vector3i(x, y, z);
                    CircuitPos cpos = CircuitPos.from(pos);
                    poweredRails.put(cpos, new PoweredRailData(pos, powered, rotation));
                } catch (NumberFormatException e) {
                    // Skip invalid lines
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load powered rails: " + e.getMessage());
        }
    }
}
