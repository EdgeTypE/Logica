package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.BlockMaterial;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System that handles fan air current logic.
 * Fans push entities away from the block in the direction they are facing.
 */
public class FanSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-Fan] ";

    // Registry of all fans and their components
    // We reuse PipeComponent for directionality (outputDirection = blowing
    // direction)
    private final Map<Vector3i, PipeComponent> fans = new ConcurrentHashMap<>();

    // Track power state of fans (0-15)
    private final Map<Vector3i, Integer> poweredFans = new ConcurrentHashMap<>();

    // Reference to main plugin
    private final CircuitPlugin plugin;

    // Save file path
    private static final String SAVE_FILE = "mods/Circuit_CircuitMod/fans.json";

    // Fan settings
    private static final double FAN_RANGE = 8.0; // Range of effect in blocks
    private static final double FAN_WIDTH = 1.5; // Width of the air stream (1.5 radius -> 3.0 diameter)
    private static final double FAN_FORCE = 1.5; // Velocity factor (push strength) - Increased for players/mobs
    private static final double FAN_LIFT = 0.3; // Upward force to counteract gravity/friction - Increased
    private static final int TICK_INTERVAL = 1; // Process every tick for smooth physics

    // Animation settings
    private static final int ANIMATION_INTERVAL = 60; // 1 second loop
    private boolean isAnimationPhaseA = true;
    private int tickCounter = 0;

    public FanSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    public void setFanPowered(Vector3i pos, int level) {
        poweredFans.put(pos, level);
    }

    public boolean isFanPowered(Vector3i pos) {
        return poweredFans.getOrDefault(pos, 0) > 0;
    }

    public int getFanPower(Vector3i pos) {
        return poweredFans.getOrDefault(pos, 0);
    }

    public boolean hasFanAt(Vector3i pos) {
        return fans.containsKey(pos);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void tick(float deltaTime, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {

        tickCounter++;

        // Animation toggle logic
        if (tickCounter % ANIMATION_INTERVAL == 0) {
            isAnimationPhaseA = !isAnimationPhaseA;
            updateFanAnimations(store);
        }

        if (tickCounter % TICK_INTERVAL != 0) {
            return;
        }

        // Process all registered fans
        for (Map.Entry<Vector3i, PipeComponent> entry : fans.entrySet()) {
            Vector3i position = entry.getKey();

            // Only process physics if powered
            if (isFanPowered(position)) {
                PipeComponent fan = entry.getValue();
                processFan(position, fan, store);
            }
        }
    }

    private void updateFanAnimations(Store<EntityStore> store) {
        World world = getWorld();
        if (world == null)
            return;

        if (!(world instanceof IChunkAccessorSync))
            return;
        IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;

        for (Vector3i position : fans.keySet()) {
            try {
                if (isFanPowered(position)) {
                    String state = isAnimationPhaseA ? "RunningA" : "RunningB";
                    BlockType currentType = getCurrentBlockType(chunkAccessor, position);
                    if (currentType != null && currentType.getId() != null
                            && currentType.getId().contains("Circuit_Fan")) {
                        chunkAccessor.setBlockInteractionState(position, currentType, state);
                    }
                }
            } catch (Exception e) {
                // Ignore update errors
                // LOGGER.atWarning().log(PREFIX + "Animation update failed at " + position + ":
                // " + e.getMessage());
            }
        }
    }

    private BlockType getCurrentBlockType(IChunkAccessorSync chunkAccessor, Vector3i pos) {
        try {
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunkAccessor
                    .getState(pos.getX(), pos.getY(), pos.getZ(), true);
            if (blockState != null)
                return blockState.getBlockType();
            else {
                java.lang.reflect.Method getBlockTypeParams = chunkAccessor.getClass().getMethod("getBlockType",
                        int.class, int.class, int.class);
                Object result = getBlockTypeParams.invoke(chunkAccessor, pos.getX(), pos.getY(), pos.getZ());
                return (BlockType) result;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Process a single fan's physics logic
     */
    private void processFan(Vector3i position, PipeComponent fan, Store<EntityStore> store) {
        World world = getWorld();
        if (world == null)
            return;

        // Determine blowing direction
        PipeComponent.Direction direction = fan.getOutputDirection();
        Vector3d fanPos = new Vector3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
        Vector3d dirVec = new Vector3d(direction.dx, direction.dy, direction.dz);

        // Calculate bounding box for the air stream
        // Start: Just in front of the fan
        Vector3d start = new Vector3d(
                fanPos.getX() + direction.dx * 0.6,
                fanPos.getY() + direction.dy * 0.6,
                fanPos.getZ() + direction.dz * 0.6);

        // End: FAN_RANGE blocks away
        Vector3d end = new Vector3d(
                start.getX() + direction.dx * FAN_RANGE,
                start.getY() + direction.dy * FAN_RANGE,
                start.getZ() + direction.dz * FAN_RANGE);

        // Create a loose bounding box for the query
        // We only expand width on axes perpendicular to the flow to avoid pushing
        // behind the fan
        double padX = (Math.abs(direction.dx) > 0.1) ? 0.2 : FAN_WIDTH;
        double padY = (Math.abs(direction.dy) > 0.1) ? 0.2 : FAN_WIDTH;
        double padZ = (Math.abs(direction.dz) > 0.1) ? 0.2 : FAN_WIDTH;

        double minX = Math.min(start.getX(), end.getX()) - padX;
        double minY = Math.min(start.getY(), end.getY()) - padY;
        double minZ = Math.min(start.getZ(), end.getZ()) - padZ;
        double maxX = Math.max(start.getX(), end.getX()) + padX;
        double maxY = Math.max(start.getY(), end.getY()) + padY;
        double maxZ = Math.max(start.getZ(), end.getZ()) + padZ;

        Vector3d minBounds = new Vector3d(minX, minY, minZ);
        Vector3d maxBounds = new Vector3d(maxX, maxY, maxZ);

        // Find entities in the box
        List<Ref<EntityStore>> entities = findEntitiesInBounds(store, minBounds, maxBounds);

        for (Ref<EntityStore> entityRef : entities) {
            applyFanForce(entityRef, position, fanPos, dirVec, store);
        }
    }

    /**
     * Find all physical entities within bounds
     */
    private List<Ref<EntityStore>> findEntitiesInBounds(Store<EntityStore> store, Vector3d minBounds,
            Vector3d maxBounds) {
        List<Ref<EntityStore>> entities = new ArrayList<>();

        try {
            // Query for anything with Transform and Velocity
            Query<EntityStore> physicsQuery = Query.and(
                    TransformComponent.getComponentType(),
                    Velocity.getComponentType());

            // Need to cast to avoid ambiguity in some environments, or just use the
            // functional interface directly
            store.forEachChunk(physicsQuery, (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> buffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (transform != null) {
                        Vector3d pos = transform.getPosition();

                        if (pos.getX() >= minBounds.getX() && pos.getX() <= maxBounds.getX() &&
                                pos.getY() >= minBounds.getY() && pos.getY() <= maxBounds.getY() &&
                                pos.getZ() >= minBounds.getZ() && pos.getZ() <= maxBounds.getZ()) {

                            entities.add(chunk.getReferenceTo(i));
                        }
                    }
                }
            });

        } catch (Exception e) {
            // Ignore errors
        }

        return entities;
    }

    /**
     * Apply pushing force to an entity
     */
    private void applyFanForce(Ref<EntityStore> ref, Vector3i fanBlockPos, Vector3d fanOrigin, Vector3d fanDir,
            Store<EntityStore> store) {
        try {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            Velocity velocity = store.getComponent(ref, Velocity.getComponentType());

            if (transform == null || velocity == null)
                return;

            World world = getWorld();
            if (world == null)
                return;

            Vector3d entityPos = transform.getPosition();

            // Calculate distance from fan
            double dist = calculateDistance(fanOrigin, entityPos);

            // Get power level (0-15)
            int powerLevel = getFanPower(fanBlockPos);
            double powerFactor = powerLevel / 15.0;

            // Force falls off with distance and scales with power
            double forceFactor = Math.max(0, 1.0 - (dist / FAN_RANGE)) * FAN_FORCE * powerFactor;

            // Obstruction check (Line of Sight)
            if (world instanceof IChunkAccessorSync) {
                if (!hasLineOfSight((IChunkAccessorSync) world, fanOrigin, entityPos)) {
                    return; // Blocked
                }
            }

            // Apply 'Air Hockey' physics
            // 1. Lift: Counteract gravity/friction slightly to make it slide
            // 2. Push: Move in direction

            // We only apply lift if the entity is close to the ground (relative to fan
            // logic)
            // Or just always apply a small upward force to simulate airflow buoyancy

            double lift = FAN_LIFT;

            // If direction is strictly horizontal, handle Y velocity specially
            if (fanDir.getY() == 0) {
                // Check if we already have upward velocity, if so, clamp it so we don't fly
                // away
                if (velocity.getY() > 0.2) {
                    lift = 0;
                }
            }

            Vector3d force = new Vector3d(
                    fanDir.getX() * forceFactor,
                    fanDir.getY() * forceFactor + lift,
                    fanDir.getZ() * forceFactor);

            // Use addInstruction to force update on clients/mobs
            VelocityConfig config = new VelocityConfig(); // Default config
            velocity.addInstruction(force, config, ChangeVelocityType.Add);

        } catch (Exception e) {
            // Ignore
        }
    }

    private double calculateDistance(Vector3d pos1, Vector3d pos2) {
        double dx = pos1.getX() - pos2.getX();
        double dy = pos1.getY() - pos2.getY();
        double dz = pos1.getZ() - pos2.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean hasLineOfSight(IChunkAccessorSync chunkAccessor, Vector3d start, Vector3d end) {
        Vector3d diff = new Vector3d(end.getX() - start.getX(), end.getY() - start.getY(), end.getZ() - start.getZ());
        double len = calculateDistance(start, end);
        if (len < 0.5)
            return true; // Too close to be blocked

        Vector3d dir = new Vector3d(diff.getX() / len, diff.getY() / len, diff.getZ() / len);

        // Step size 0.5 to check for blocks
        double step = 0.5;
        // Start a bit ahead to avoid self-collision with fan block
        for (double d = 0.6; d < len; d += step) {
            Vector3d pos = new Vector3d(
                    start.getX() + dir.getX() * d,
                    start.getY() + dir.getY() * d,
                    start.getZ() + dir.getZ() * d);
            Vector3i blockPos = new Vector3i(
                    (int) Math.floor(pos.getX()),
                    (int) Math.floor(pos.getY()),
                    (int) Math.floor(pos.getZ()));

            BlockType type = getCurrentBlockType(chunkAccessor, blockPos);
            if (type != null && type.getMaterial() == BlockMaterial.Solid) {
                return false; // Blocked
            }
        }
        return true;
    }

    // Registry methods
    public void registerFan(Vector3i position, PipeComponent component) {
        fans.put(position, component);
    }

    public void unregisterFan(Vector3i position) {
        fans.remove(position);
    }

    public boolean isFanAt(Vector3i position) {
        return fans.containsKey(position);
    }

    private World getWorld() {
        try {
            return Universe.get().getDefaultWorld();
        } catch (Exception e) {
            return null;
        }
    }

    // Save/Load logic (Similar to VacuumSystem)
    public void saveFans() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("[\n");

            boolean first = true;
            for (Map.Entry<Vector3i, PipeComponent> entry : fans.entrySet()) {
                if (!first)
                    json.append(",\n");
                first = false;

                Vector3i pos = entry.getKey();
                PipeComponent fan = entry.getValue();

                json.append("  {\n");
                json.append("    \"x\": ").append(pos.getX()).append(",\n");
                json.append("    \"y\": ").append(pos.getY()).append(",\n");
                json.append("    \"z\": ").append(pos.getZ()).append(",\n");
                json.append("    \"direction\": \"").append(fan.getOutputDirection().name()).append("\"\n");
                json.append("  }");
            }
            json.append("\n]");

            java.nio.file.Path saveDir = java.nio.file.Paths.get("mods/Circuit_CircuitMod");
            if (!java.nio.file.Files.exists(saveDir)) {
                java.nio.file.Files.createDirectories(saveDir);
            }
            java.nio.file.Files.write(java.nio.file.Paths.get(SAVE_FILE), json.toString().getBytes());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadFans() {
        try {
            java.nio.file.Path saveFile = java.nio.file.Paths.get(SAVE_FILE);
            if (!java.nio.file.Files.exists(saveFile))
                return;

            String content = java.nio.file.Files.readString(saveFile);
            content = content.trim();
            if (!content.startsWith("[") || !content.endsWith("]"))
                return;

            content = content.substring(1, content.length() - 1).trim();
            if (content.isEmpty())
                return;

            String[] fanObjects = content.split("\\},\\s*\\{");

            for (String fanObj : fanObjects) {
                try {
                    fanObj = fanObj.trim().replace("{", "").replace("}", "");

                    int x = 0, y = 0, z = 0;
                    PipeComponent.Direction direction = PipeComponent.Direction.NORTH;

                    String[] lines = fanObj.split(",");
                    for (String line : lines) {
                        String[] parts = line.split(":");
                        String key = parts[0].trim().replace("\"", "");
                        String val = parts[1].trim().replace("\"", "");

                        if (key.equals("x"))
                            x = Integer.parseInt(val);
                        else if (key.equals("y"))
                            y = Integer.parseInt(val);
                        else if (key.equals("z"))
                            z = Integer.parseInt(val);
                        else if (key.equals("direction"))
                            direction = PipeComponent.Direction.valueOf(val);
                    }

                    Vector3i pos = new Vector3i(x, y, z);
                    PipeComponent fan = new PipeComponent();
                    fan.setOutputDirection(direction);

                    fans.put(pos, fan);

                } catch (Exception e) {
                    // Ignore malformed
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
