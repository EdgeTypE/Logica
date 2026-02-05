package com.circuit;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Repeater System - Handles logic for Redstone Repeater equivalents.
 *
 * Features:
 * - Directional (Input from back, Output to front)
 * - Diode (1-way)
 * - Delay (1-4 steps, 2 ticks per step)
 * - Locking (TODO: Lock via side input)
 */
public class RepeaterSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    private static final int TICKS_PER_DELAY_STEP = 2; // 1 Redstone Tick = 2 Game Ticks

    private final CircuitPlugin plugin;
    private final ComponentType<EntityStore, RepeaterComponent> repeaterComponentType;

    // Registry: Position -> Repeater Info
    // We store minimal info here, but the Component holds the state in ECS if
    // attached to an entity.
    // However, blocks in Hytale don't always have persistent entities unless we
    // make them.
    // For now, we'll maintain a parallel registry similar to
    // ObserverSystem/ButtonSystem for easy lookup.
    private final Map<Vector3i, RepeaterState> repeaters = new HashMap<>();

    // Scheduled Ticks for Delays
    // Tick -> Map<Pos, TargetState>
    private final Map<Long, Map<Vector3i, Boolean>> scheduledUpdates = new HashMap<>();

    private long currentTick = 0;

    // Helper Class for State
    public static class RepeaterState {
        public ObserverSystem.Direction facing; // Reuse ObserverSystem.Direction for convenience
        public int delay; // 1-4
        public boolean powered;
        public boolean locked;

        public RepeaterState(ObserverSystem.Direction facing, int delay, boolean powered, boolean locked) {
            this.facing = facing;
            this.delay = delay;
            this.powered = powered;
            this.locked = locked;
        }
    }

    public RepeaterSystem(CircuitPlugin plugin, ComponentType<EntityStore, RepeaterComponent> componentType) {
        this.plugin = plugin;
        this.repeaterComponentType = componentType;
    }

    // ==================== Registration ====================

    public void registerRepeater(Vector3i pos, ObserverSystem.Direction facing) {
        // Default state: Delay 1, Unpowered, Unlocked
        repeaters.put(pos, new RepeaterState(facing, 1, false, false));
        // LOGGER.atInfo().log(PREFIX + "[Repeater] Registered at " + pos + " facing " + facing);
    }

    public void unregisterRepeater(Vector3i pos) {
        repeaters.remove(pos);
        // Remove pending schedules
        for (Map<Vector3i, Boolean> updates : scheduledUpdates.values()) {
            updates.remove(pos);
        }
        // LOGGER.atInfo().log(PREFIX + "[Repeater] Unregistered at " + pos);
    }

    public boolean isRepeaterAt(Vector3i pos) {
        return repeaters.containsKey(pos);
    }

    public RepeaterState getRepeaterState(Vector3i pos) {
        return repeaters.get(pos);
    }

    // ==================== Logic ====================

    /**
     * Called by interactions (F key) to cycle delay.
     */
    public void cycleDelay(Vector3i pos) {
        if (!repeaters.containsKey(pos))
            return;

        RepeaterState state = repeaters.get(pos);
        state.delay++;
        if (state.delay > 4)
            state.delay = 1;

        // LOGGER.atInfo().log(PREFIX + "[Repeater] Cycled delay at " + pos + " to " + state.delay);
        updateVisuals(pos);
    }

    /**
     * Called when a neighbor changes or network updates.
     * Checks input power and schedules update if needed.
     */
    public void checkInput(Vector3i repeaterPos) {
        if (!repeaters.containsKey(repeaterPos))
            return;

        RepeaterState state = repeaters.get(repeaterPos);
        if (state.locked)
            return; // Locked repeaters don't change state

        // Calculate input position (Back of the repeater)
        Vector3i inputPos = getInputPos(repeaterPos, state.facing);

        // Check power at input position
        int inputPower = getPowerAt(inputPos);
        boolean shouldBePowered = inputPower > 0;

        // If state needs to change
        if (state.powered != shouldBePowered) {
            // Check if already scheduled
            if (!isUpdateScheduled(repeaterPos)) {
                int delayTicks = state.delay * TICKS_PER_DELAY_STEP;
                scheduleUpdate(repeaterPos, shouldBePowered, delayTicks);
            }
        }
    }

    private boolean isUpdateScheduled(Vector3i pos) {
        // Simple check through upcoming ticks
        // Optimization: limit search depth if needed
        for (Map<Vector3i, Boolean> updates : scheduledUpdates.values()) {
            if (updates.containsKey(pos))
                return true;
        }
        return false;
    }

    private void scheduleUpdate(Vector3i pos, boolean targetState, int delay) {
        long targetTick = currentTick + delay;
        scheduledUpdates.computeIfAbsent(targetTick, k -> new HashMap<>()).put(pos, targetState);
        // LOGGER.atInfo().log(PREFIX + "[Repeater] Scheduled " + pos + " -> " +
        // targetState + " in " + delay + " ticks");
    }

    private void processUpdates() {
        Map<Vector3i, Boolean> updates = scheduledUpdates.remove(currentTick);
        if (updates == null)
            return;

        for (Map.Entry<Vector3i, Boolean> entry : updates.entrySet()) {
            Vector3i pos = entry.getKey();
            boolean targetState = entry.getValue();

            if (!repeaters.containsKey(pos))
                continue; // Removed?

            RepeaterState state = repeaters.get(pos);
            if (state.locked)
                continue; // Check lock again before applying

            if (state.powered != targetState) {
                state.powered = targetState;
                // LOGGER.atInfo().log(PREFIX + "[Repeater] " + pos + " changed to " + (targetState ? "ON" : "OFF"));

                // 1. Update Visuals
                updateVisuals(pos);

                // 2. Propagate Output
                notifyOutput(pos, state.facing);
            }
        }
    }

    private void notifyOutput(Vector3i pos, ObserverSystem.Direction facing) {
        Vector3i outputPos = getOutputPos(pos, facing);
        if (plugin.getEnergySystem() != null) {
            // LOGGER.atInfo().log(PREFIX + "[Repeater] Notifying output at " + outputPos + " (Facing " + facing + ")");
            plugin.getEnergySystem().updateNetwork(outputPos);
            // Also update neighbors of output to ensure wires connect?
            // EnergyPropagationSystem.updateNetwork usually recalculates the block passed.
            // If outputPos is a Wire, it will recalculate.
            // If outputPos is Empty (Air), it does nothing.
            // But if there is a wire adjacent to outputPos?
            // Repeater powers the block AT outputPos.
            // If that block is a Wire, it receives power.
            // If that block is Solid, it receives Strong Power.
            // If Solid, we need to update ITS neighbors (the wires attached to it).
            plugin.getEnergySystem().updateNeighbors(outputPos);
        }
    }

    // ==================== Helpers ====================

    public Vector3i getInputPos(Vector3i repeaterPos, ObserverSystem.Direction facing) {
        // Input is BEHIND the repeater.
        // Facing is where it points TO.
        // So Back = Pos - FacingVector
        Vector3i dir = facing.getVector();
        return new Vector3i(repeaterPos.getX() - dir.x, repeaterPos.getY() - dir.y, repeaterPos.getZ() - dir.z);
    }

    public Vector3i getOutputPos(Vector3i repeaterPos, ObserverSystem.Direction facing) {
        // Output is FRONT of the repeater.
        Vector3i dir = facing.getVector();
        return new Vector3i(repeaterPos.getX() + dir.x, repeaterPos.getY() + dir.y, repeaterPos.getZ() + dir.z);
    }

    /**
     * Gets strong power output from this repeater to a specific position.
     * Repeaters provide strong power to the block they face.
     */
    public int getStrongPowerOutput(Vector3i sourceRepeater, Vector3i targetPos) {
        if (!repeaters.containsKey(sourceRepeater))
            return 0;

        RepeaterState state = repeaters.get(sourceRepeater);
        if (!state.powered)
            return 0;

        // Only provide power to the block in front
        Vector3i outputPos = getOutputPos(sourceRepeater, state.facing);
        if (outputPos.equals(targetPos)) {
            return 15;
        }
        return 0;
    }

    private int getPowerAt(Vector3i pos) {
        if (plugin.getEnergySystem() == null)
            return 0;
        return plugin.getEnergySystem().getEnergyLevel(pos);
    }

    private void updateVisuals(Vector3i pos) {
        if (!repeaters.containsKey(pos))
            return;
        RepeaterState state = repeaters.get(pos);

        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return;

            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            BlockType type = BlockType.getAssetMap().getAsset("Circuit_Repeater");
            if (type == null)
                return;

            // State format: Powered_Delay (e.g., "On_1", "Off_4")
            String powerStr = state.powered ? "On" : "Off";
            String stateStr = powerStr + "_" + state.delay;

            // Apply interaction state
            chunkAccessor.setBlockInteractionState(pos, type, stateStr);

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Repeater visual update failed: " + e.getMessage());
        }
    }

    // ==================== Persistence ====================
    // Similar to ObserverSystem, save/load to file

    private static final String REPEATERS_FILE = "repeaters.dat";

    public void saveRepeaters(Path dataDirectory) {
        if (repeaters.isEmpty())
            return;

        try {
            if (!Files.exists(dataDirectory))
                Files.createDirectories(dataDirectory);
            Path filePath = dataDirectory.resolve(REPEATERS_FILE);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Map.Entry<Vector3i, RepeaterState> entry : repeaters.entrySet()) {
                    Vector3i pos = entry.getKey();
                    RepeaterState s = entry.getValue();
                    // x,y,z,FACING,DELAY,POWERED,LOCKED
                    writer.write(String.format("%d,%d,%d,%s,%d,%b,%b",
                            pos.getX(), pos.getY(), pos.getZ(),
                            s.facing.name(), s.delay, s.powered, s.locked));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to save repeaters: " + e.getMessage());
        }
    }

    public void loadRepeaters(Path dataDirectory) {
        Path filePath = dataDirectory.resolve(REPEATERS_FILE);
        if (!Files.exists(filePath))
            return;

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] p = line.split(",");
                    if (p.length < 7)
                        continue;

                    Vector3i pos = new Vector3i(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    ObserverSystem.Direction facing = ObserverSystem.Direction.fromString(p[3]);
                    int delay = Integer.parseInt(p[4]);
                    boolean powered = Boolean.parseBoolean(p[5]);
                    boolean locked = Boolean.parseBoolean(p[6]);

                    repeaters.put(pos, new RepeaterState(facing, delay, powered, locked));

                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Bad line in repeaters.dat: " + line);
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load repeaters: " + e.getMessage());
        }
    }

    // ==================== ECS ====================

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        currentTick++;
        processUpdates();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
