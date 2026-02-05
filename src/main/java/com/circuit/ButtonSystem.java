package com.circuit;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages button and pressure plate pulse logic with timed deactivation.
 * Buttons provide a temporary power pulse when activated.
 */
public class ButtonSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod-Button] ";

    // Tick-based pulse duration (20 ticks = 1 second at 20 TPS)
    private static final int STONE_BUTTON_TICKS = 20; // 1 second
    private static final int WOOD_BUTTON_TICKS = 30; // 1.5 seconds
    private static final int PRESSURE_PLATE_TICKS = 999999; // Very long - pressure plates deactivate via
                                                            // PressurePlateSystem

    private static final double TICK_INTERVAL = 0.05; // 20 TPS = 50ms per tick

    private final CircuitPlugin plugin;
    private final Map<CircuitPos, PulseData> activePulses = new ConcurrentHashMap<>();
    private final Map<CircuitPos, ButtonData> buttons = new ConcurrentHashMap<>();

    private double accumulatedTime = 0.0;

    public enum Direction {
        NORTH, EAST, SOUTH, WEST, UP, DOWN;

        public Direction getOpposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
                case UP -> DOWN;
                case DOWN -> UP;
            };
        }

        public Vector3i getOffset(Vector3i pos) {
            return switch (this) {
                case NORTH -> new Vector3i(pos.getX(), pos.getY(), pos.getZ() - 1);
                case SOUTH -> new Vector3i(pos.getX(), pos.getY(), pos.getZ() + 1);
                case EAST -> new Vector3i(pos.getX() + 1, pos.getY(), pos.getZ());
                case WEST -> new Vector3i(pos.getX() - 1, pos.getY(), pos.getZ());
                case UP -> new Vector3i(pos.getX(), pos.getY() + 1, pos.getZ());
                case DOWN -> new Vector3i(pos.getX(), pos.getY() - 1, pos.getZ());
            };
        }
    }

    public enum PulseType {
        STONE_BUTTON, WOOD_BUTTON, PRESSURE_PLATE_STONE, PRESSURE_PLATE_WOOD
    }

    public static class PulseData {
        public final Vector3i position;
        public final PulseType type;
        public int ticksRemaining;
        public final int powerLevel;

        public PulseData(Vector3i position, PulseType type, int ticksRemaining, int powerLevel) {
            this.position = position;
            this.type = type;
            this.ticksRemaining = ticksRemaining;
            this.powerLevel = powerLevel;
        }
    }

    public static class ButtonData {
        public final Vector3i position;
        public final Direction facing;
        public final Direction attachedTo;
        public final PulseType type;
        public boolean isPressed;

        public ButtonData(Vector3i position, Direction facing, PulseType type) {
            this.position = position;
            this.facing = facing;
            this.attachedTo = facing.getOpposite();
            this.type = type;
            this.isPressed = false;
        }
    }

    public ButtonSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        accumulatedTime += dt;

        while (accumulatedTime >= TICK_INTERVAL) {
            processTick();
            accumulatedTime -= TICK_INTERVAL;
        }
    }

    private void processTick() {
        Iterator<Map.Entry<CircuitPos, PulseData>> iterator = activePulses.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<CircuitPos, PulseData> entry = iterator.next();
            PulseData pulse = entry.getValue();

            pulse.ticksRemaining--;

            if (pulse.ticksRemaining <= 0) {
                // LOGGER.atInfo().log(PREFIX + "Pulse expired at " + pulse.position);
                iterator.remove();
                deactivatePulseSource(pulse.position);
            }
        }
    }

    public void activateButton(Vector3i pos) {
        CircuitPos circuitPos = CircuitPos.from(pos);
        ButtonData button = buttons.get(circuitPos);
        if (button == null) {
            LOGGER.atWarning().log(PREFIX + "Cannot activate - button not registered at " + pos);
            return;
        }

        // For pressure plates: refresh pulse if already active (keep it alive)
        // For regular buttons: do nothing if already pressed
        boolean isPressurePlate = button.type == PulseType.PRESSURE_PLATE_STONE
                || button.type == PulseType.PRESSURE_PLATE_WOOD;

        if (button.isPressed && !isPressurePlate) {
            return; // Already active - do nothing for regular buttons
        }

        button.isPressed = true;

        int duration = switch (button.type) {
            case STONE_BUTTON, PRESSURE_PLATE_STONE -> STONE_BUTTON_TICKS;
            case WOOD_BUTTON, PRESSURE_PLATE_WOOD -> WOOD_BUTTON_TICKS;
        };

        activePulses.put(circuitPos, new PulseData(pos, button.type, duration, 15));
        // LOGGER.atInfo().log(PREFIX + "Button activated at " + pos + " for " +
        // duration + " ticks");

        // Update visual state
        setButtonVisual(pos, true);

        // Trigger energy propagation for the button AND all neighbors of attached block
        if (plugin.getEnergySystem() != null) {
            plugin.getEnergySystem().updateNetwork(pos);

            // CRITICAL: Update neighbors of attached block so they check for strong power
            Vector3i attachedBlock = button.attachedTo.getOffset(pos);
            // LOGGER.atInfo().log(PREFIX + "Button activated - triggering neighbors of
            // attached block " + attachedBlock);

            // Update all 6 neighbors of the attached block
            int[][] neighbors = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
            for (int[] offset : neighbors) {
                Vector3i neighbor = new Vector3i(
                        attachedBlock.getX() + offset[0],
                        attachedBlock.getY() + offset[1],
                        attachedBlock.getZ() + offset[2]);
                plugin.getEnergySystem().updateNetwork(neighbor);
            }
        }
    }

    private void deactivatePulseSource(Vector3i pos) {
        CircuitPos circuitPos = CircuitPos.from(pos);
        ButtonData button = buttons.get(circuitPos);
        if (button == null) {
            return;
        }

        button.isPressed = false;
        // LOGGER.atInfo().log(PREFIX + "Button deactivated at " + pos);

        // Update visual state
        setButtonVisual(pos, false);

        // Trigger energy propagation
        if (plugin.getEnergySystem() != null) {
            plugin.getEnergySystem().updateNetwork(pos);

            // CRITICAL: Update neighbors of attached block
            Vector3i attachedBlock = button.attachedTo.getOffset(pos);
            // LOGGER.atInfo()
            // .log(PREFIX + "Button deactivated - triggering neighbors of attached block "
            // + attachedBlock);

            // Update all 6 neighbors of the attached block
            int[][] neighbors = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
            for (int[] offset : neighbors) {
                Vector3i neighbor = new Vector3i(
                        attachedBlock.getX() + offset[0],
                        attachedBlock.getY() + offset[1],
                        attachedBlock.getZ() + offset[2]);
                plugin.getEnergySystem().updateNetwork(neighbor);
            }
        }
    }

    private void setButtonVisual(Vector3i pos, boolean pressed) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return;

            var chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;
            var blockType = chunkAccessor.getBlockType(pos);

            if (blockType != null) {
                String state = pressed ? "Pressed" : "Unpressed";
                chunkAccessor.setBlockInteractionState(pos, blockType, state);
                // LOGGER.atInfo().log(PREFIX + "Button visual at " + pos + " -> " + state);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update button visual: " + e.getMessage());
        }
    }

    public void registerButton(Vector3i pos, Direction facing, PulseType type) {
        ButtonData button = new ButtonData(pos, facing, type);
        buttons.put(CircuitPos.from(pos), button);
        // LOGGER.atInfo().log(PREFIX + "Button registered: Button[pos=" + pos + ",
        // facing=" + facing +
        // ", attachedTo=" + button.attachedTo + ", type=" + type + ", pressed=false]");
    }

    public void unregisterButton(Vector3i pos) {
        CircuitPos circuitPos = CircuitPos.from(pos);
        activePulses.remove(circuitPos);
        ButtonData removed = buttons.remove(circuitPos);
        if (removed != null) {
            // LOGGER.atInfo().log(PREFIX + "Button unregistered at " + pos);
        }
    }

    public boolean isButtonAt(Vector3i pos) {
        return buttons.containsKey(CircuitPos.from(pos));
    }

    public boolean isButtonPressed(Vector3i pos) {
        ButtonData button = buttons.get(CircuitPos.from(pos));
        return button != null && button.isPressed;
    }

    public int getButtonPower(Vector3i pos) {
        return isButtonPressed(pos) ? 15 : 0;
    }

    /**
     * Get strong power transmitted through a solid block.
     * If a button is attached to the given block and is pressed, returns the power
     * level.
     * Otherwise returns 0.
     * 
     * @param blockPos The position of the solid block to check
     * @return Power level (15) if a pressed button is attached to this block, 0
     *         otherwise
     */
    public int getStrongPowerToBlock(Vector3i blockPos) {
        // LOGGER.atInfo().log(PREFIX + "[StrongPower] Checking block " + blockPos + "
        // (total buttons: " + buttons.size() + ")");
        for (ButtonData button : buttons.values()) {
            Vector3i attachedBlock = button.attachedTo.getOffset(button.position);
            // LOGGER.atInfo().log(PREFIX + "[StrongPower] Button at " + button.position +
            // " facing=" + button.facing + " attachedTo=" + button.attachedTo +
            // " -> attached block=" + attachedBlock + " pressed=" + button.isPressed);

            if (button.isPressed) {
                if (attachedBlock.equals(blockPos)) {
                    // LOGGER.atInfo().log(PREFIX + "[StrongPower] MATCH! Returning 15");
                    return 15; // Button is attached to this block and is pressed
                }
            }
        }
        // LOGGER.atInfo().log(PREFIX + "[StrongPower] No match, returning 0");
        return 0;
    }

    /**
     * Get direct power output from a button.
     * Buttons provide power in the direction they are facing when pressed.
     * 
     * @param buttonPos The position of the button
     * @param targetPos The position to check if it receives power
     * @return Power level (15) if button is pressed and targetPos is in the facing
     *         direction, 0 otherwise
     */
    public int getDirectPowerOutput(Vector3i buttonPos, Vector3i targetPos) {
        ButtonData button = buttons.get(CircuitPos.from(buttonPos));
        if (button == null || !button.isPressed) {
            return 0;
        }

        // Check if targetPos is in the direction the button is facing
        Vector3i facingBlock = button.facing.getOffset(button.position);
        // LOGGER.atInfo().log(PREFIX + "[DirectPower] Button at " + buttonPos + "
        // facing=" + button.facing +
        // " -> facing block=" + facingBlock + " target=" + targetPos + " pressed=" +
        // button.isPressed);

        if (facingBlock.equals(targetPos)) {
            // LOGGER.atInfo().log(PREFIX + "[DirectPower] MATCH! Button provides direct
            // power to " + targetPos);
            return 15;
        }

        return 0;
    }

    public ButtonData getButtonAt(Vector3i pos) {
        return buttons.get(CircuitPos.from(pos));
    }

    public void saveButtons(Path dataDir) {
        try {
            Path saveFile = dataDir.resolve("buttons.txt");
            List<String> lines = new ArrayList<>();

            for (ButtonData button : buttons.values()) {
                String line = button.position.getX() + "," +
                        button.position.getY() + "," +
                        button.position.getZ() + "," +
                        button.facing.name() + "," +
                        button.type.name();
                lines.add(line);
            }

            Files.write(saveFile, lines);
            // LOGGER.atInfo().log(PREFIX + "Saved " + buttons.size() + " buttons to " +
            // saveFile);
        } catch (IOException e) {
            LOGGER.atSevere().log(PREFIX + "Failed to save buttons: " + e.getMessage());
        }
    }

    public void loadButtons(Path dataDir) {
        try {
            Path saveFile = dataDir.resolve("buttons.txt");
            if (!Files.exists(saveFile)) {
                // LOGGER.atInfo().log(PREFIX + "Loaded 0 buttons from " + saveFile);
                return;
            }

            List<String> lines = Files.readAllLines(saveFile);
            buttons.clear();

            for (String line : lines) {
                String[] parts = line.split(",");
                if (parts.length == 5) {
                    Vector3i pos = new Vector3i(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
                    Direction facing = Direction.valueOf(parts[3]);
                    PulseType type = PulseType.valueOf(parts[4]);

                    registerButton(pos, facing, type);
                }
            }

            // LOGGER.atInfo().log(PREFIX + "Loaded " + buttons.size() + " buttons from " +
            // saveFile);
        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "Failed to load buttons: " + e.getMessage());
        }
    }

    /**
     * Register a pressure plate (no facing direction, just DOWN attachment).
     */
    public void registerPressurePlate(Vector3i pos, PulseType type) {
        // Pressure plates are always attached DOWN (to the block below)
        registerButton(pos, Direction.UP, type);
    }

    /**
     * Force deactivate a pressure plate immediately (used when all players leave).
     */
    public void forcePressurePlateDeactivate(Vector3i pos) {
        CircuitPos circuitPos = CircuitPos.from(pos);
        ButtonData button = buttons.get(circuitPos);
        if (button == null) {
            return;
        }

        // Remove active pulse
        activePulses.remove(circuitPos);

        // Deactivate the button
        button.isPressed = false;

        // Update visual state
        setButtonVisual(pos, false);

        // Trigger energy propagation
        if (plugin.getEnergySystem() != null) {
            plugin.getEnergySystem().updateNetwork(pos);

            // Update neighbors of attached block (block below)
            Vector3i attachedBlock = button.attachedTo.getOffset(pos);

            // Update all 6 neighbors of the attached block
            int[][] neighbors = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
            for (int[] offset : neighbors) {
                Vector3i neighbor = new Vector3i(
                        attachedBlock.getX() + offset[0],
                        attachedBlock.getY() + offset[1],
                        attachedBlock.getZ() + offset[2]);
                plugin.getEnergySystem().updateNetwork(neighbor);
            }
        }
    }
}
