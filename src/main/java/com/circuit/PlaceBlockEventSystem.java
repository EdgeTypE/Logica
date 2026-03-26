package com.circuit;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS Event System to handle block placement events.
 * Tracks lever and wire positions when placed.
 */
public class PlaceBlockEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";

    private final CircuitPlugin plugin;

    public PlaceBlockEventSystem(CircuitPlugin plugin) {
        super(PlaceBlockEvent.class);
        this.plugin = plugin;
        // LOGGER.atInfo().log(PREFIX + "PlaceBlockEventSystem initialized");
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull PlaceBlockEvent event) {

        ItemStack itemInHand = event.getItemInHand();
        Vector3i pos = event.getTargetBlock();

        if (itemInHand != null && !itemInHand.isEmpty()) {
            String itemId = itemInHand.getItemId();
            // LOGGER.atInfo().log(PREFIX + "[PlaceDebug] Block placed at " + pos + "
            // itemId=" + itemId);

            if (itemId != null) {
                if (itemId.contains("Circuit_Lever")) {
                    plugin.setLeverState(pos, false);
                    plugin.getEnergySystem().updateBlock(pos);
                    // LOGGER.atInfo().log(PREFIX + "Lever placed at " + pos + " (OFF)");
                } else if (itemId.contains("Circuit_Wire") || itemId.contains("Circuit_Golden_Wire")) {
                    plugin.addWirePosition(pos, itemId);
                    plugin.getEnergySystem().updateBlock(pos);
                    // LOGGER.atInfo().log(PREFIX + "Wire placed at " + pos + " (Type: " + itemId +
                    // ")");
                } else if (itemId.contains("Circuit_Observer")) {
                    // Determine facing from rotation index
                    ObserverSystem.Direction facing = ObserverSystem.Direction.NORTH; // Default

                    try {
                        // Get rotation from event - it's a RotationTuple with index
                        Object rotation = event.getRotation();
                        // LOGGER.atInfo().log(PREFIX + "[Observer] getRotation() = " + rotation);

                        if (rotation != null) {
                            // Try to get index via reflection
                            int rotationIndex = 0;
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                // Try getIndex()
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    // Parse from toString
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }

                            // LOGGER.atInfo().log(PREFIX + "[Observer] Rotation index: " + rotationIndex);

                            // Convert index to direction
                            // Index 0-3 represents horizontal rotations (90 degree steps)
                            // Fixed: EAST and WEST were swapped
                            switch (rotationIndex % 4) {
                                case 0:
                                    facing = ObserverSystem.Direction.SOUTH;
                                    break;
                                case 1:
                                    facing = ObserverSystem.Direction.EAST;
                                    break; // Was WEST
                                case 2:
                                    facing = ObserverSystem.Direction.NORTH;
                                    break;
                                case 3:
                                    facing = ObserverSystem.Direction.WEST;
                                    break; // Was EAST
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[Observer] Could not determine facing: " + e.getMessage());
                    }

                    plugin.getObserverSystem().registerObserver(pos, facing);
                    // LOGGER.atInfo().log(PREFIX + "Observer placed at " + pos + " facing " +
                    // facing);
                } else if (itemId.contains("Circuit_Pusher_Piston") || itemId.contains("Circuit_Sticky_Piston")) {
                    // Handle piston placement with rotation
                    PistonSystem.Direction facing = PistonSystem.Direction.NORTH; // Default
                    boolean isSticky = itemId.contains("Sticky");

                    try {
                        // Get rotation from event - it's a RotationTuple with index
                        Object rotation = event.getRotation();
                        // LOGGER.atInfo().log(PREFIX + "[Piston] getRotation() = " + rotation);

                        if (rotation != null) {
                            // Try to get index via reflection
                            int rotationIndex = 0;
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                // Try getIndex()
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    // Parse from toString
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }

                            // LOGGER.atInfo().log(PREFIX + "[Piston] Rotation index: " + rotationIndex);

                            // Convert rotation index to piston facing direction
                            facing = PistonSystem.Direction.fromRotationIndex(rotationIndex);

                            // Register piston with the PistonSystem including rotation index
                            plugin.getPistonSystem().registerPiston(pos, facing, isSticky, rotationIndex);
                        } else {
                            // No rotation info, register with default
                            plugin.getPistonSystem().registerPiston(pos, facing, isSticky, 0);
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[Piston] Could not determine facing: " + e.getMessage());
                        // Fallback: register with default rotation
                        plugin.getPistonSystem().registerPiston(pos, facing, isSticky, 0);
                    }

                    // LOGGER.atInfo().log(
                    // PREFIX + "Piston placed at " + pos + " facing " + facing + " (sticky=" +
                    // isSticky + ")");

                    // Debug: Verify registration
                    if (plugin.getPistonSystem().isPistonAt(pos)) {
                        // LOGGER.atInfo().log(PREFIX + "SUCCESS: Piston successfully registered at " +
                        // pos);
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Piston registration failed at " + pos);
                    }
                } else if (itemId.contains("Circuit_Pipe")) {
                    // Handle pipe placement with rotation based on player facing direction
                    PipeComponent.Direction outputDirection = PipeComponent.Direction.NORTH; // Default

                    // LOGGER.atInfo().log(PREFIX + "=== PIPE PLACEMENT DEBUG START ===");
                    // LOGGER.atInfo().log(PREFIX + "Item ID: " + itemId);
                    // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

                    try {
                        // Get rotation from event - it's a RotationTuple with index
                        Object rotation = event.getRotation();
                        // LOGGER.atInfo().log(PREFIX + "[Pipe] getRotation() = " + rotation);
                        // LOGGER.atInfo().log(PREFIX + "[Pipe] Rotation class: "
                        // + (rotation != null ? rotation.getClass().getName() : "null"));

                        if (rotation != null) {
                            // Try to get index via reflection
                            int rotationIndex = 0;
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                // LOGGER.atInfo().log(PREFIX + "[Pipe] Got rotation index via index(): " +
                                // rotationIndex);
                            } catch (Exception e) {
                                // Try getIndex()
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                    // LOGGER.atInfo()
                                    // .log(PREFIX + "[Pipe] Got rotation index via getIndex(): " + rotationIndex);
                                } catch (Exception e2) {
                                    // Parse from toString
                                    String rotStr = rotation.toString();
                                    // LOGGER.atInfo().log(PREFIX + "[Pipe] Parsing from toString: " + rotStr);
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                        // LOGGER.atInfo().log(PREFIX + "[Pipe] Parsed rotation index: " +
                                        // rotationIndex);
                                    }
                                }
                            }

                            // LOGGER.atInfo().log(PREFIX + "[Pipe] Final rotation index: " +
                            // rotationIndex);

                            // Check for pitch information for UP/DOWN detection
                            String rotStr = rotation.toString();
                            boolean lookingUp = rotStr.contains("pitch=Up") || rotStr.contains("pitch=Ninety");
                            boolean lookingDown = rotStr.contains("pitch=Down") || rotStr.contains("pitch=MinusNinety");

                            // LOGGER.atInfo().log(PREFIX + "[Pipe] Rotation string: " + rotStr);
                            // LOGGER.atInfo()
                            // .log(PREFIX + "[Pipe] Looking up: " + lookingUp + ", Looking down: " +
                            // lookingDown);

                            // Convert rotation index to pipe output direction
                            // Priority: UP/DOWN detection first, then horizontal
                            PipeComponent.Direction oldDirection = outputDirection;
                            if (lookingUp) {
                                outputDirection = PipeComponent.Direction.UP;
                                // LOGGER.atInfo().log(PREFIX + "[Pipe] Detected UP direction from pitch");
                            } else if (lookingDown) {
                                outputDirection = PipeComponent.Direction.DOWN;
                                // LOGGER.atInfo().log(PREFIX + "[Pipe] Detected DOWN direction from pitch");
                            } else if (rotationIndex >= 4) {
                                // Vertical rotations (UP/DOWN) - fallback
                                switch (rotationIndex) {
                                    case 4:
                                        outputDirection = PipeComponent.Direction.UP;
                                        break; // Looking up
                                    case 5:
                                        outputDirection = PipeComponent.Direction.DOWN;
                                        break; // Looking down
                                    default:
                                        outputDirection = PipeComponent.Direction.UP;
                                        break; // Fallback
                                }
                                // LOGGER.atInfo().log(
                                // PREFIX + "[Pipe] Detected vertical direction from index: " + rotationIndex);
                            } else {
                                // Horizontal rotations (NESW)
                                switch (rotationIndex % 4) {
                                    case 0:
                                        outputDirection = PipeComponent.Direction.SOUTH;
                                        break; // Player facing South
                                    case 1:
                                        outputDirection = PipeComponent.Direction.EAST;
                                        break; // Player facing East
                                    case 2:
                                        outputDirection = PipeComponent.Direction.NORTH;
                                        break; // Player facing North
                                    case 3:
                                        outputDirection = PipeComponent.Direction.WEST;
                                        break; // Player facing West
                                }
                                // LOGGER.atInfo().log(
                                // PREFIX + "[Pipe] Detected horizontal direction from index: " +
                                // rotationIndex);
                            }
                            // LOGGER.atInfo().log(PREFIX + "[Pipe] Direction mapping: " + oldDirection + "
                            // -> "
                            // + outputDirection + " (index " + rotationIndex + ")");
                        } else {
                            LOGGER.atWarning().log(
                                    PREFIX + "[Pipe] Rotation is null, using default direction: " + outputDirection);
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[Pipe] Could not determine output direction from rotation: "
                                + e.getMessage());
                        e.printStackTrace();
                    }

                    // Create pipe component with determined output direction
                    PipeComponent pipeComponent = new PipeComponent();
                    pipeComponent.setOutputDirection(outputDirection);

                    // LOGGER.atInfo()
                    // .log(PREFIX + "[Pipe] Created pipe component with output direction: " +
                    // outputDirection);

                    plugin.getPipeSystem().registerPipe(pos, pipeComponent);
                    // LOGGER.atInfo().log(PREFIX + "Pipe placed at " + pos + " with output
                    // direction: " + outputDirection
                    // + " (based on player rotation)");

                    // Debug: Verify registration
                    if (plugin.getPipeSystem().isPipeAt(pos)) {
                        PipeComponent registeredPipe = plugin.getPipeSystem().getPipeAt(pos);
                        // LOGGER.atInfo().log(PREFIX + "SUCCESS: Pipe successfully registered at " +
                        // pos);
                        // LOGGER.atInfo().log(
                        // PREFIX + "Registered pipe output direction: " +
                        // registeredPipe.getOutputDirection());
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Pipe registration failed at " + pos);
                    }

                    // LOGGER.atInfo().log(PREFIX + "=== PIPE PLACEMENT DEBUG END ===");
                } else if (itemId.contains("Circuit_Vacuum_Pipe")) {
                    // Handle vacuum pipe placement with rotation based on player facing direction
                    PipeComponent.Direction outputDirection = PipeComponent.Direction.NORTH; // Default

                    // LOGGER.atInfo().log(PREFIX + "=== VACUUM PIPE PLACEMENT DEBUG START ===");
                    // LOGGER.atInfo().log(PREFIX + "Item ID: " + itemId);
                    // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

                    try {
                        // Get rotation from event - it's a RotationTuple with index
                        Object rotation = event.getRotation();
                        // LOGGER.atInfo().log(PREFIX + "[VacuumPipe] getRotation() = " + rotation);

                        if (rotation != null) {
                            // Try to get index via reflection
                            int rotationIndex = 0;
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                // Try getIndex()
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    // Parse from toString
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }

                            // LOGGER.atInfo().log(PREFIX + "[VacuumPipe] Final rotation index: " +
                            // rotationIndex);

                            // Check for pitch information for UP/DOWN detection
                            String rotStr = rotation.toString();
                            boolean lookingUp = rotStr.contains("pitch=Up") || rotStr.contains("pitch=Ninety");
                            boolean lookingDown = rotStr.contains("pitch=Down") || rotStr.contains("pitch=MinusNinety");

                            // Convert rotation index to pipe output direction (same logic as regular pipes)
                            if (lookingUp) {
                                outputDirection = PipeComponent.Direction.UP;
                            } else if (lookingDown) {
                                outputDirection = PipeComponent.Direction.DOWN;
                            } else if (rotationIndex >= 4) {
                                // Vertical rotations (UP/DOWN) - fallback
                                switch (rotationIndex) {
                                    case 4:
                                        outputDirection = PipeComponent.Direction.UP;
                                        break;
                                    case 5:
                                        outputDirection = PipeComponent.Direction.DOWN;
                                        break;
                                    default:
                                        outputDirection = PipeComponent.Direction.UP;
                                        break;
                                }
                            } else {
                                // Horizontal rotations (NESW)
                                switch (rotationIndex % 4) {
                                    case 0:
                                        outputDirection = PipeComponent.Direction.SOUTH;
                                        break;
                                    case 1:
                                        outputDirection = PipeComponent.Direction.EAST;
                                        break;
                                    case 2:
                                        outputDirection = PipeComponent.Direction.NORTH;
                                        break;
                                    case 3:
                                        outputDirection = PipeComponent.Direction.WEST;
                                        break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning()
                                .log(PREFIX + "[VacuumPipe] Could not determine output direction: " + e.getMessage());
                    }

                    // Create pipe component with determined output direction
                    PipeComponent pipeComponent = new PipeComponent();
                    pipeComponent.setOutputDirection(outputDirection);

                    // Register with both pipe system and vacuum system
                    plugin.getPipeSystem().registerPipe(pos, pipeComponent);
                    plugin.getVacuumSystem().registerVacuumPipe(pos, pipeComponent);

                    // LOGGER.atInfo().log(
                    // PREFIX + "Vacuum Pipe placed at " + pos + " with output direction: " +
                    // outputDirection);

                    // Debug: Verify registration
                    if (plugin.getPipeSystem().isPipeAt(pos)) {
                        // LOGGER.atInfo().log(PREFIX + "SUCCESS: Vacuum Pipe successfully registered at
                        // " + pos);
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Vacuum Pipe registration failed at " + pos);
                    }

                    // LOGGER.atInfo().log(PREFIX + "=== VACUUM PIPE PLACEMENT DEBUG END ===");
                } else if (itemId.contains("Circuit_Fan")) {
                    // Handle fan placement with rotation based on player facing direction
                    PipeComponent.Direction outputDirection = PipeComponent.Direction.NORTH; // Default

                    try {
                        // Get rotation from event
                        Object rotation = event.getRotation();

                        if (rotation != null) {
                            // Try to get index via reflection
                            int rotationIndex = 0;
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }

                            // Check for pitch information for UP/DOWN detection
                            String rotStr = rotation.toString();
                            boolean lookingUp = rotStr.contains("pitch=Up") || rotStr.contains("pitch=Ninety");
                            boolean lookingDown = rotStr.contains("pitch=Down") || rotStr.contains("pitch=MinusNinety");

                            // Convert rotation index to fan output direction
                            if (lookingUp) {
                                outputDirection = PipeComponent.Direction.UP;
                            } else if (lookingDown) {
                                outputDirection = PipeComponent.Direction.DOWN;
                            } else if (rotationIndex >= 4) {
                                switch (rotationIndex) {
                                    case 4:
                                        outputDirection = PipeComponent.Direction.UP;
                                        break;
                                    case 5:
                                        outputDirection = PipeComponent.Direction.DOWN;
                                        break;
                                    default:
                                        outputDirection = PipeComponent.Direction.UP;
                                        break;
                                }
                            } else {
                                switch (rotationIndex % 4) {
                                    case 0:
                                        outputDirection = PipeComponent.Direction.SOUTH;
                                        break;
                                    case 1:
                                        outputDirection = PipeComponent.Direction.EAST;
                                        break;
                                    case 2:
                                        outputDirection = PipeComponent.Direction.NORTH;
                                        break;
                                    case 3:
                                        outputDirection = PipeComponent.Direction.WEST;
                                        break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning()
                                .log(PREFIX + "[Fan] Could not determine output direction: " + e.getMessage());
                    }

                    // Create fan component with determined output direction
                    PipeComponent fanComponent = new PipeComponent();
                    fanComponent.setOutputDirection(outputDirection);

                    // Register with fan system
                    plugin.getFanSystem().registerFan(pos, fanComponent);

                    // LOGGER.atInfo().log(PREFIX + "Fan placed at " + pos + " facing " +
                    // outputDirection);
                } else if (itemId.contains("Circuit_Button")) {
                    // Handle button placement with rotation to determine facing direction
                    ButtonSystem.Direction facing = ButtonSystem.Direction.NORTH; // Default
                    ButtonSystem.PulseType buttonType = ButtonSystem.PulseType.STONE_BUTTON; // Default

                    // LOGGER.atInfo().log(PREFIX + "=== BUTTON PLACEMENT DEBUG START ===");
                    // LOGGER.atInfo().log(PREFIX + "Item ID: " + itemId);
                    // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

                    // Determine button type from item ID
                    if (itemId.contains("Wood")) {
                        buttonType = ButtonSystem.PulseType.WOOD_BUTTON;
                    }

                    try {
                        // Get rotation from event - same logic as other blocks
                        Object rotation = event.getRotation();
                        // LOGGER.atInfo().log(PREFIX + "[Button] getRotation() = " + rotation);

                        if (rotation != null) {
                            // Try to get index via reflection
                            int rotationIndex = 0;
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                // Try getIndex()
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    // Parse from toString
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }

                            // LOGGER.atInfo().log(PREFIX + "[Button] Rotation index: " + rotationIndex);

                            // Convert index to direction - button faces the direction player is looking
                            // This determines which block the button is attached to
                            switch (rotationIndex % 4) {
                                case 0:
                                    facing = ButtonSystem.Direction.SOUTH; // Player facing south, button faces south
                                    break;
                                case 1:
                                    facing = ButtonSystem.Direction.EAST; // Player facing east, button faces east
                                    break;
                                case 2:
                                    facing = ButtonSystem.Direction.NORTH; // Player facing north, button faces north
                                    break;
                                case 3:
                                    facing = ButtonSystem.Direction.WEST; // Player facing west, button faces west
                                    break;
                            }

                            // LOGGER.atInfo().log(PREFIX + "[Button] Determined facing: " + facing
                            // + " from rotation index: " + rotationIndex);
                        } else {
                            LOGGER.atWarning().log(PREFIX + "[Button] Rotation is null, using default NORTH");
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[Button] Error determining facing: " + e.getMessage());
                        e.printStackTrace();
                    }

                    // Register button with ButtonSystem
                    plugin.getButtonSystem().registerButton(pos, facing, buttonType);
                    // LOGGER.atInfo().log(
                    // PREFIX + "Button placed at " + pos + " facing " + facing + " (type=" +
                    // buttonType + ")");

                    // Debug: Verify registration
                    if (plugin.getButtonSystem().isButtonAt(pos)) {
                        ButtonSystem.ButtonData button = plugin.getButtonSystem().getButtonAt(pos);
                        // LOGGER.atInfo().log(PREFIX + "SUCCESS: Button successfully registered at " +
                        // pos);
                        // LOGGER.atInfo().log(PREFIX + "Attached block: " +
                        // button.attachedTo.getOffset(button.position));
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Button registration failed at " + pos);
                    }

                    // LOGGER.atInfo().log(PREFIX + "=== BUTTON PLACEMENT DEBUG END ===");
                } else if (itemId.contains("Circuit_Pressure_Plate"))

                {
                    // Handle pressure plate placement
                    // Pressure plates sit on top of blocks, so facing is always DOWN (attached to
                    // block below)
                    ButtonSystem.Direction facing = ButtonSystem.Direction.UP; // Plate faces up
                    ButtonSystem.PulseType plateType = ButtonSystem.PulseType.PRESSURE_PLATE_STONE; // Default

                    // LOGGER.atInfo().log(PREFIX + "=== PRESSURE PLATE PLACEMENT DEBUG START ===");
                    // LOGGER.atInfo().log(PREFIX + "Item ID: " + itemId);
                    // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

                    // Determine plate type from item ID
                    if (itemId.contains("Wood")) {
                        plateType = ButtonSystem.PulseType.PRESSURE_PLATE_WOOD;
                    }

                    // Register pressure plate with ButtonSystem (uses same system as buttons)
                    plugin.getButtonSystem().registerButton(pos, facing, plateType);
                    // LOGGER.atInfo().log(PREFIX + "Pressure plate placed at " + pos + " (type=" +
                    // plateType + ")");

                    // Debug: Verify registration
                    if (plugin.getButtonSystem().isButtonAt(pos)) {
                        ButtonSystem.ButtonData plate = plugin.getButtonSystem().getButtonAt(pos);
                        // LOGGER.atInfo().log(PREFIX + "SUCCESS: Pressure plate successfully registered
                        // at " + pos);
                        // LOGGER.atInfo().log(PREFIX + "Attached block: " +
                        // plate.attachedTo.getOffset(plate.position));
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Pressure plate registration failed at " + pos);
                    }

                    // LOGGER.atInfo().log(PREFIX + "=== PRESSURE PLATE PLACEMENT DEBUG END ===");
                } else if (itemId.contains("Circuit_Lamp")) {
                    // Handle lamp placement
                    // LOGGER.atInfo().log(PREFIX + "=== LAMP PLACEMENT DEBUG START ===");
                    // LOGGER.atInfo().log(PREFIX + "Item ID: " + itemId);
                    // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

                    // Register lamp with LampSystem
                    plugin.getLampSystem().registerLamp(pos);
                    // LOGGER.atInfo().log(PREFIX + "Lamp placed at " + pos);

                    // Trigger energy update to check if it should be lit immediately
                    plugin.getEnergySystem().updateNetwork(pos);

                    // Debug: Verify registration
                    if (plugin.getLampSystem().isLampAt(pos)) {
                        boolean isLit = plugin.getLampSystem().isLampLit(pos);
                        // LOGGER.atInfo().log(
                        // PREFIX + "SUCCESS: Lamp successfully registered at " + pos + " (lit=" + isLit
                        // + ")");
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Lamp registration failed at " + pos);
                    }

                    // LOGGER.atInfo().log(PREFIX + "=== LAMP PLACEMENT DEBUG END ===");
                } else if (itemId.contains("Circuit_Light_Sensor")) {
                    // Handle light sensor placement
                    // Register sensor with LightSensorSystem
                    plugin.getLightSensorSystem().registerSensor(pos);
                    // LOGGER.atInfo().log(PREFIX + "Light Sensor placed at " + pos);

                    // Discover and register adjacent circuit blocks
                    discoverAdjacentCircuitBlocks(pos);

                    // Trigger energy update
                    plugin.getEnergySystem().updateNetwork(pos);

                    // Debug: Verify registration
                    if (plugin.getLightSensorSystem().isSensorAt(pos)) {
                        // LOGGER.atInfo().log(PREFIX + "SUCCESS: Light Sensor registered at " + pos);
                    } else {
                        LOGGER.atWarning().log(PREFIX + "ERROR: Light Sensor registration failed at " + pos);
                    }
                } else if (itemId.contains("Circuit_Repeater")) {
                    // Handle repeater placement with rotation
                    ObserverSystem.Direction facing = ObserverSystem.Direction.NORTH; // Default

                    try {
                        Object rotation = event.getRotation();
                        if (rotation != null) {
                            int rotationIndex = 0;
                            // Reflection to get index
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }

                            // reuse ObserverSystem direction map which works for 6-way
                            switch (rotationIndex % 4) {
                                case 0:
                                    facing = ObserverSystem.Direction.SOUTH;
                                    break;
                                case 1:
                                    facing = ObserverSystem.Direction.EAST;
                                    break;
                                case 2:
                                    facing = ObserverSystem.Direction.NORTH;
                                    break;
                                case 3:
                                    facing = ObserverSystem.Direction.WEST;
                                    break;
                            }
                            // Vertical checks if needed? ObserverSystem logic implies just taking index
                            // roughly.
                            // But for Repeater, let's assume standard horizontal placement mostly,
                            // but if supported by blockymodel/rotation, 6-way is fine.
                            // Actually, I'll copy the Observer logic properly:
                            // Wait, Observer logic in PlaceBlockEventSystem above uses rotationIndex
                            // explicitly?
                            // No, it used a specific check.

                            // Use same logic as Observer for now, assuming UpDownNESW rotation variant
                            // Vertical overrides
                            if (rotationIndex == 4)
                                facing = ObserverSystem.Direction.UP;
                            if (rotationIndex == 5)
                                facing = ObserverSystem.Direction.DOWN;
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[Repeater] Could not determine facing: " + e.getMessage());
                    }

                    plugin.getRepeaterSystem().registerRepeater(pos, facing);
                    // LOGGER.atInfo().log(PREFIX + "Repeater placed at " + pos + " facing " +
                    // facing);
                    // Update connections
                    plugin.getEnergySystem().updateNetwork(pos);
                    // Critical: Update neighbors so wires connect to the new repeater
                    plugin.getEnergySystem().updateNeighbors(pos);
                } else if (itemId.contains("Circuit_Powered_Rail")) {
                    // Handle Powered Rail placement with rotation
                    int rotationIndex = 0;
                    try {
                        Object rotation = event.getRotation();
                        if (rotation != null) {
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[PoweredRail] Could not determine rotation: " + e.getMessage());
                    }

                    plugin.getPoweredRailSystem().registerPoweredRail(pos, rotationIndex);
                    // Trigger energy update to check if it should be powered immediately
                    plugin.getEnergySystem().updateNetwork(pos);

                } else if (itemId.contains("Circuit_Switch_Rail")) {
                    // Handle Switch Rail placement with rotation
                    int rotationIndex = 0;
                    try {
                        Object rotation = event.getRotation();
                        if (rotation != null) {
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[SwitchRail] Could not determine rotation: " + e.getMessage());
                    }

                    plugin.getSwitchRailSystem().registerSwitchRail(pos, rotationIndex);
                    // Trigger energy update to check if it should change state immediately
                    plugin.getEnergySystem().updateNetwork(pos);

                } else if (itemId.contains("Circuit_Detector_Rail")) {
                    // Handle Detector Rail placement with rotation
                    int rotationIndex = 0;
                    try {
                        Object rotation = event.getRotation();
                        if (rotation != null) {
                            try {
                                java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                            } catch (Exception e) {
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e2) {
                                    String rotStr = rotation.toString();
                                    if (rotStr.contains("index=")) {
                                        String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                        indexPart = indexPart.split("[,\\]]")[0].trim();
                                        rotationIndex = Integer.parseInt(indexPart);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "[DetectorRail] Could not determine rotation: " + e.getMessage());
                    }

                    plugin.getDetectorRailSystem().registerDetectorRail(pos, rotationIndex);
                    // Trigger energy update
                    plugin.getEnergySystem().updateNetwork(pos);

                } else if (itemId.contains("Circuit_Gate_")) {
                    // Handle gate placement with rotation
                    CircuitComponent.Direction facing = CircuitComponent.Direction.NORTH; // Default
                    GateSystem.GateType gateType = null;

                    // Determine gate type from item ID
                    if (itemId.contains("Circuit_Gate_NOT")) {
                        gateType = GateSystem.GateType.NOT;
                    } else if (itemId.contains("Circuit_Gate_AND")) {
                        gateType = GateSystem.GateType.AND;
                    } else if (itemId.contains("Circuit_Gate_NAND")) {
                        gateType = GateSystem.GateType.NAND;
                    } else if (itemId.contains("Circuit_Gate_OR")) {
                        gateType = GateSystem.GateType.OR;
                    } else if (itemId.contains("Circuit_Gate_NOR")) {
                        gateType = GateSystem.GateType.NOR;
                    } else if (itemId.contains("Circuit_Gate_XOR")) {
                        gateType = GateSystem.GateType.XOR;
                    } else if (itemId.contains("Circuit_Gate_XNOR")) {
                        gateType = GateSystem.GateType.XNOR;
                    } else if (itemId.contains("Circuit_Gate_Buffer")) {
                        gateType = GateSystem.GateType.BUFFER;
                    }

                    if (gateType != null) {
                        try {
                            Object rotation = event.getRotation();
                            if (rotation != null) {
                                int rotationIndex = 0;
                                // Reflection to get index
                                try {
                                    java.lang.reflect.Method getIndex = rotation.getClass().getMethod("index");
                                    rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                } catch (Exception e) {
                                    try {
                                        java.lang.reflect.Method getIndex = rotation.getClass().getMethod("getIndex");
                                        rotationIndex = ((Number) getIndex.invoke(rotation)).intValue();
                                    } catch (Exception e2) {
                                        String rotStr = rotation.toString();
                                        if (rotStr.contains("index=")) {
                                            String indexPart = rotStr.substring(rotStr.indexOf("index=") + 6);
                                            indexPart = indexPart.split("[,\\]]")[0].trim();
                                            rotationIndex = Integer.parseInt(indexPart);
                                        }
                                    }
                                }

                                // Convert rotation index to facing direction
                                switch (rotationIndex % 4) {
                                    case 0:
                                        facing = CircuitComponent.Direction.SOUTH;
                                        break;
                                    case 1:
                                        facing = CircuitComponent.Direction.EAST;
                                        break;
                                    case 2:
                                        facing = CircuitComponent.Direction.NORTH;
                                        break;
                                    case 3:
                                        facing = CircuitComponent.Direction.WEST;
                                        break;
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.atWarning().log(PREFIX + "[Gate] Could not determine facing: " + e.getMessage());
                        }

                        plugin.getGateSystem().registerGate(pos, gateType, facing);
                        // LOGGER.atInfo().log(PREFIX + gateType + " gate placed at " + pos + " facing "
                        // + facing);

                        // Update connections
                        plugin.getEnergySystem().updateNetwork(pos);
                        plugin.getEnergySystem().updateNeighbors(pos);
                    }
                }
            }
        } else {
            // LOGGER.atInfo().log(PREFIX + "[PlaceDebug] Block placed at " + pos + " but
            // itemInHand is null/empty");
        }

        // Notify Observer system about block change
        if (plugin.getObserverSystem() != null) {
            plugin.getObserverSystem().onBlockChange(pos);
        }

        // Scan for doors/gates around newly placed circuit components
        if (plugin.getDoorSystem() != null && itemInHand != null && !itemInHand.isEmpty()) {
            String itemId = itemInHand.getItemId();
            if (itemId != null && itemId.contains("Circuit_")) {
                // Scan a 5-block radius around the placed circuit component for doors/gates
                plugin.getDoorSystem().scanForDoors(pos, 5);
            }
        }
    }

    /**
     * Discover and register adjacent circuit blocks when a Light Sensor is placed.
     * This ensures that adjacent lamps, pistons, etc. are properly registered
     * and can be powered by the Light Sensor.
     */
    private void discoverAdjacentCircuitBlocks(Vector3i centerPos) {
        // Check all 6 adjacent positions
        int[][] offsets = {
                { 1, 0, 0 }, { -1, 0, 0 }, // X axis
                { 0, 1, 0 }, { 0, -1, 0 }, // Y axis
                { 0, 0, 1 }, { 0, 0, -1 } // Z axis
        };

        for (int[] offset : offsets) {
            Vector3i adjacentPos = new Vector3i(
                    centerPos.getX() + offset[0],
                    centerPos.getY() + offset[1],
                    centerPos.getZ() + offset[2]);

            // Try to discover and register the adjacent block
            plugin.tryDiscoverCircuitBlock(adjacentPos);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
