package com.circuit;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityUseBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import javax.annotation.Nonnull;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.*;
import java.nio.file.*;

/**
 * Main plugin class for the Circuit Mod.
 * Provides Minecraft redstone-style circuit functionality for Hytale.
 */
public final class CircuitPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    private static CircuitPlugin instance;

    private ComponentType<EntityStore, CircuitComponent> circuitComponentType;
    private ComponentType<EntityStore, PipeComponent> pipeComponentType;

    // Track lever states by position (simple in-memory state for now)
    private final Map<CircuitPos, Boolean> leverStates = new HashMap<>();

    // Track wire positions
    private final Set<Vector3i> wirePositions = new HashSet<>();
    // Track wire types (e.g., "Circuit_Wire", "Circuit_Golden_Wire")
    private final Map<Vector3i, String> wireTypes = new HashMap<>();

    // Track wire states (powered or not)
    private final Map<CircuitPos, Boolean> wireStates = new HashMap<>();

    // Persistence file path - stored in UserData/Mods/CircuitMod_data.txt
    private static final String DATA_FILE_NAME = "CircuitMod_data.txt";
    private Path dataFilePath;

    public CircuitPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        // LOGGER.atInfo().log(PREFIX + "========================================");
        // LOGGER.atInfo().log(PREFIX + "CircuitMod v" +
        // this.getManifest().getVersion().toString());
        // LOGGER.atInfo().log(PREFIX + "========================================");
    }

    private EnergyPropagationSystem energySystem;
    private ObserverSystem observerSystem;
    private PistonSystem pistonSystem;
    private PipeSystem pipeSystem;
    private VacuumSystem vacuumSystem;
    private ButtonSystem buttonSystem;
    private LampSystem lampSystem;
    private RepeaterSystem repeaterSystem;
    private DoorSystem doorSystem;
    private GateSystem gateSystem;
    private PressurePlateSystem pressurePlateSystem;
    private LightSensorSystem lightSensorSystem;
    private FanSystem fanSystem;
    private PoweredRailSystem poweredRailSystem;
    private SwitchRailSystem switchRailSystem;
    private DetectorRailSystem detectorRailSystem;

    @Override
    protected void setup() {
        // LOGGER.atInfo().log(PREFIX + "Initializing...");

        // Register the circuit component
        this.circuitComponentType = this.getEntityStoreRegistry()
                .registerComponent(CircuitComponent.class, CircuitComponent::new);
        // LOGGER.atInfo().log(PREFIX + "CircuitComponent registered");

        // Register the pipe component
        this.pipeComponentType = this.getEntityStoreRegistry()
                .registerComponent(PipeComponent.class, PipeComponent::new);
        // LOGGER.atInfo().log(PREFIX + "PipeComponent registered");

        // Register the circuit system
        this.getEntityStoreRegistry().registerSystem(new CircuitSystem(this.circuitComponentType));
        // LOGGER.atInfo().log(PREFIX + "CircuitSystem registered");

        // Register the UseBlockEventSystem (ECS)
        this.getEntityStoreRegistry().registerSystem(new UseBlockEventSystem(this));
        // LOGGER.atInfo().log(PREFIX + "UseBlockEventSystem registered");

        // Register the EnergyPropagation system
        this.energySystem = new EnergyPropagationSystem(this, this.circuitComponentType);
        this.getEntityStoreRegistry().registerSystem(this.energySystem);
        // LOGGER.atInfo().log(PREFIX + "EnergyPropagationSystem registered");

        // Register the PlaceBlockEvent system (ECS)
        this.getEntityStoreRegistry().registerSystem(new PlaceBlockEventSystem(this));
        // LOGGER.atInfo().log(PREFIX + "PlaceBlockEventSystem registered");

        // Register the BreakBlockEvent system (ECS)
        this.getEntityStoreRegistry().registerSystem(new BreakBlockEventSystem(this));
        // LOGGER.atInfo().log(PREFIX + "BreakBlockEventSystem registered");

        // Register the Observer system
        this.observerSystem = new ObserverSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.observerSystem);
        // LOGGER.atInfo().log(PREFIX + "ObserverSystem registered");

        // Register the Piston system
        this.pistonSystem = new PistonSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.pistonSystem);
        // LOGGER.atInfo().log(PREFIX + "PistonSystem registered");

        // Register the Pipe system
        this.pipeSystem = new PipeSystem(this, this.pipeComponentType);
        this.getEntityStoreRegistry().registerSystem(this.pipeSystem);
        // LOGGER.atInfo().log(PREFIX + "PipeSystem registered");

        // Register the Vacuum system
        this.vacuumSystem = new VacuumSystem(this, this.pipeComponentType);
        this.getEntityStoreRegistry().registerSystem(this.vacuumSystem);
        // LOGGER.atInfo().log(PREFIX + "VacuumSystem registered");

        // Register the Button system
        this.buttonSystem = new ButtonSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.buttonSystem);
        // LOGGER.atInfo().log(PREFIX + "ButtonSystem registered");

        // Register the PressurePlate system
        this.pressurePlateSystem = new PressurePlateSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.pressurePlateSystem);
        // LOGGER.atInfo().log(PREFIX + "PressurePlateSystem registered");

        // Register the Lamp system
        this.lampSystem = new LampSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.lampSystem);
        // LOGGER.atInfo().log(PREFIX + "LampSystem registered");

        // Register the Repeater system
        this.repeaterSystem = new RepeaterSystem(this,
                this.getEntityStoreRegistry().registerComponent(RepeaterComponent.class, RepeaterComponent::new));
        this.getEntityStoreRegistry().registerSystem(this.repeaterSystem);
        // LOGGER.atInfo().log(PREFIX + "RepeaterSystem registered");

        // Register the Door system
        this.doorSystem = new DoorSystem(this);
        // LOGGER.atInfo().log(PREFIX + "DoorSystem registered");

        // Register the Gate system
        this.gateSystem = new GateSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.gateSystem);
        // LOGGER.atInfo().log(PREFIX + "GateSystem registered");

        // Register the Light Sensor system
        this.lightSensorSystem = new LightSensorSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.lightSensorSystem);
        // LOGGER.atInfo().log(PREFIX + "LightSensorSystem registered");

        // Register the Fan system
        this.fanSystem = new FanSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.fanSystem);
        // LOGGER.atInfo().log(PREFIX + "FanSystem registered");

        // Register the Powered Rail system
        this.poweredRailSystem = new PoweredRailSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.poweredRailSystem);
        // LOGGER.atInfo().log(PREFIX + "PoweredRailSystem registered");

        // Register the Switch Rail system
        this.switchRailSystem = new SwitchRailSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.switchRailSystem);
        // LOGGER.atInfo().log(PREFIX + "SwitchRailSystem registered");

        // Register the Detector Rail system
        this.detectorRailSystem = new DetectorRailSystem(this);
        this.getEntityStoreRegistry().registerSystem(this.detectorRailSystem);
        // LOGGER.atInfo().log(PREFIX + "DetectorRailSystem registered");

        // Register the Floating Item system
        FloatingItemSystem floatingItemSystem = new FloatingItemSystem(
                com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType(),
                com.hypixel.hytale.server.core.modules.physics.component.Velocity.getComponentType(),
                com.hypixel.hytale.server.core.modules.entity.item.ItemPhysicsComponent.getComponentType(),
                com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType(),
                com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.getComponentType());
        this.getEntityStoreRegistry().registerSystem(floatingItemSystem);
        // LOGGER.atInfo().log(PREFIX + "FloatingItemSystem registered");

        // Register event handlers
        registerEvents();

        // LOGGER.atInfo().log(PREFIX + "========================================");
        // LOGGER.atInfo().log(PREFIX + "Initialization complete!");
        // LOGGER.atInfo()
        // .log(PREFIX + "Blocks: Wire, Lever, Button, Observer, Hopper, Pistons, Pipes,
        // Vacuum Pipes, Lamps");
        // LOGGER.atInfo().log(PREFIX + "Gates: AND, OR, NOR, XOR, NOT, NAND, XNOR,
        // Buffer");
        // LOGGER.atInfo().log(PREFIX + "========================================");
    }

    private void registerEvents() {
        // NOTE: PlaceBlockEvent is handled by PlaceBlockEventSystem (ECS) - not here
        // NOTE: BreakBlockEvent is handled by BreakBlockEventSystem (ECS) - not here

        // Player ready
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);

        // LivingEntityUseBlockEvent - keyed by block type
        this.getEventRegistry().registerGlobal(LivingEntityUseBlockEvent.class, this::onLivingEntityUseBlock);
        // LOGGER.atInfo().log(PREFIX + "LivingEntityUseBlockEvent registered
        // (global)");

        // Player Interact (Left Click / Attack) to handle pre-break logic
        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent.class, this::onPlayerInteract);
        // LOGGER.atInfo().log(PREFIX + "PlayerInteractEvent registered (global)");

        // LOGGER.atInfo().log(PREFIX + "Event handlers registered");
    }

    // ==================== Event Handlers ====================

    private void onPlayerInteract(com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent event) {
        // Debug action type
        com.hypixel.hytale.protocol.InteractionType action = event.getActionType();
        // LOGGER.atInfo().log(PREFIX + "PlayerInteract: " + action);

        // Detect Left Click / Attack (Pre-Break)
        // Adjust condition based on actual InteractionType Enum values (guessing ATTACK
        // or LEFT_CLICK)
        if (action.toString().contains("ATTACK") || action.toString().contains("LEFT")) {
            Vector3i pos = event.getTargetBlock();
            if (pos != null && wirePositions.contains(pos)) {
                // LOGGER.atInfo().log(PREFIX + "[PreBreak] Wire hit at " + pos + ". Triggering
                // signal check.");
                handleBlockBreak(pos);
            }
        }
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        // LOGGER.atInfo().log(PREFIX + "Player ready: " + player.getDisplayName());
    }

    private void onLivingEntityUseBlock(LivingEntityUseBlockEvent event) {
        String blockType = event.getBlockType();
        // LOGGER.atInfo().log(PREFIX + "DEBUG GLOBAL UseBlock: blockType=" +
        // blockType);

        // Only log for circuit blocks
        if (blockType != null && blockType.contains("Circuit_")) {
            // LOGGER.atInfo().log(PREFIX + "LivingEntityUseBlock: blockType=" + blockType);

            // Handle Circuit_Lever interaction
            if (blockType.contains("Circuit_Lever")) {
                // State is embedded in blockType after ChangeState runs:
                // *Circuit_Lever_State_Definitions_On or *Circuit_Lever_State_Definitions_Off
                boolean isOn = blockType.contains("_On");

                // Try to get position from event - different methods might be available
                Vector3i pos = null;
                try {
                    // Try different possible method names
                    java.lang.reflect.Method getPos = event.getClass().getMethod("getPosition");
                    pos = (Vector3i) getPos.invoke(event);
                } catch (Exception e1) {
                    try {
                        java.lang.reflect.Method getBlockPos = event.getClass().getMethod("getBlockPosition");
                        pos = (Vector3i) getBlockPos.invoke(event);
                    } catch (Exception e2) {
                        try {
                            java.lang.reflect.Method getTarget = event.getClass().getMethod("getTarget");
                            pos = (Vector3i) getTarget.invoke(event);
                        } catch (Exception e3) {
                            LOGGER.atWarning().log(PREFIX + "Could not get position from LivingEntityUseBlockEvent");
                            return;
                        }
                    }
                }

                if (pos != null) {
                    // Update lever state in our system
                    setLeverState(pos, isOn);

                    String stateText = isOn ? "ON" : "OFF";
                    // LOGGER.atInfo().log(PREFIX + ">>> Lever at " + pos + " state updated -> " +
                    // stateText);

                    // CRITICAL: Update energy network to propagate power changes
                    if (energySystem != null) {
                        energySystem.updateNetwork(pos);
                    }
                }
            }

            // Handle Circuit_Activator_Block interaction
            if (blockType.contains("Circuit_Activator_Block")) {
                boolean isOn = blockType.contains("_On");
                Vector3i pos = null;
                try {
                    java.lang.reflect.Method getPos = event.getClass().getMethod("getPosition");
                    pos = (Vector3i) getPos.invoke(event);
                } catch (Exception e) {
                    // Try fallback if needed (omitted for brevity as identical to lever)
                    try {
                        java.lang.reflect.Method getTarget = event.getClass().getMethod("getTarget");
                        pos = (Vector3i) getTarget.invoke(event);
                    } catch (Exception e2) {
                    }
                }

                if (pos != null) {
                    setLeverState(pos, isOn);
                    if (energySystem != null) {
                        energySystem.updateNetwork(pos);
                    }
                }
            }
        }
    }

    private void onBlockPlace(PlaceBlockEvent event) {
        // Logic moved to PlaceBlockEventSystem
    }

    // Called by BreakBlockEventSystem
    public void handleBlockBreak(Vector3i pos) {
        boolean updateNeighbors = false;
        boolean changed = false;

        CircuitPos circuitPos = CircuitPos.from(pos);
        if (leverStates.containsKey(circuitPos)) {
            leverStates.remove(circuitPos);
            // LOGGER.atInfo().log(PREFIX + "Lever removed at " + pos);
            updateNeighbors = true;
            changed = true;
        }

        if (wirePositions.contains(pos)) {
            wirePositions.remove(pos);
            wireStates.remove(circuitPos);
            // LOGGER.atInfo().log(PREFIX + "Wire removed at " + pos);
            updateNeighbors = true;
            changed = true;
        }

        // Handle button removal
        if (buttonSystem != null && buttonSystem.isButtonAt(pos)) {
            buttonSystem.unregisterButton(pos);
            // LOGGER.atInfo().log(PREFIX + "Button removed at " + pos);
            updateNeighbors = true;
            changed = true;
        }

        // Handle pipe removal
        if (pipeSystem != null && pipeSystem.isPipeAt(pos)) {
            pipeSystem.unregisterPipe(pos);
            // LOGGER.atInfo().log(PREFIX + "Pipe removed at " + pos);
            changed = true;
        }

        // Handle vacuum pipe removal
        if (vacuumSystem != null) {
            vacuumSystem.unregisterVacuumPipe(pos);
            // Note: No need to check if it was actually a vacuum pipe, unregister is safe
        }

        // Handle fan removal
        if (fanSystem != null) {
            fanSystem.unregisterFan(pos);
        }

        // Handle powered rail removal
        if (poweredRailSystem != null) {
            poweredRailSystem.unregisterPoweredRail(pos);
        }

        // Handle switch rail removal
        if (switchRailSystem != null) {
            switchRailSystem.unregisterSwitchRail(pos);
        }

        // Handle detector rail removal
        if (detectorRailSystem != null) {
            detectorRailSystem.onDetectorRailRemoved(pos);
        }

        // Handle lamp removal
        if (lampSystem != null && lampSystem.isLampAt(pos)) {
            lampSystem.unregisterLamp(pos);
            // LOGGER.atInfo().log(PREFIX + "Lamp removed at " + pos);
            changed = true;
        }

        // Handle repeater removal
        if (repeaterSystem != null && repeaterSystem.isRepeaterAt(pos)) {
            repeaterSystem.unregisterRepeater(pos);
            // LOGGER.atInfo().log(PREFIX + "Repeater removed at " + pos);
            // Updating neighbors is critical for repeaters as they strongly power blocks
            updateNeighbors = true;
            changed = true;
        }

        // Handle gate removal
        if (gateSystem != null && gateSystem.isGateAt(pos)) {
            gateSystem.unregisterGate(pos);
            // LOGGER.atInfo().log(PREFIX + "Gate removed at " + pos);
            updateNeighbors = true;
            changed = true;
        }

        if (updateNeighbors && energySystem != null) {
            // Use unified break handler for proper network stabilization
            energySystem.handleBreak(pos);
        }
    }

    // ==================== Lever API ====================

    public boolean getLeverState(Vector3i position) {
        return leverStates.getOrDefault(CircuitPos.from(position), false);
    }

    public void setLeverState(Vector3i position, boolean state) {
        leverStates.put(CircuitPos.from(position), state);

        // Track persistence in the common component list
        if (!wirePositions.contains(position)) {
            wirePositions.add(position);
            wireTypes.put(position, "Circuit_Lever");
        }

        if (energySystem != null)
            energySystem.updateBlock(position);
    }

    public Map<Vector3i, Boolean> getLeverStates() {
        Map<Vector3i, Boolean> result = new HashMap<>();
        for (Map.Entry<CircuitPos, Boolean> entry : leverStates.entrySet()) {
            result.put(entry.getKey().toVector3i(), entry.getValue());
        }
        return result;
    }

    // ==================== Wire API ====================

    /**
     * Try to discover a circuit block at the given position.
     * If it exists in the world but not in our registry, register it.
     * This fixes the issue where blocks placed before restart are not tracked.
     */
    public boolean tryDiscoverCircuitBlock(Vector3i position) {
        // Skip if already tracked
        CircuitPos circuitPos = CircuitPos.from(position);
        if (wirePositions.contains(position) || leverStates.containsKey(circuitPos) ||
                (observerSystem != null && observerSystem.isObserver(position))) {
            return wirePositions.contains(position);
        }

        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) {
                LOGGER.atWarning().log(PREFIX + "[Discovery] World is null!");
                return false;
            }

            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();

            // Try to get block type directly first
            BlockType blockType = chunkAccessor.getBlockType(x, y, z);
            String blockId = null;

            if (blockType != null) {
                blockId = blockType.getId();
            } else {
                // Fallback to getState method
                com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunkAccessor.getState(x, y,
                        z, true);

                if (blockState == null) {
                    return false;
                }

                blockType = blockState.getBlockType();
                if (blockType == null) {
                    return false;
                }

                blockId = blockType.getId();
            }

            if (blockId == null) {
                return false;
            }

            // Debug: Log what block we found
            // if (blockId.contains("Circuit")) {
            // LOGGER.atInfo().log(PREFIX + "[Discovery] Found block at " + position + ": "
            // + blockId);
            // }

            // Check if it's a wire block and determine its state
            if (blockId.contains("Circuit_Wire") || blockId.contains("Circuit_Golden_Wire")
                    || blockId.contains("Circuit_Wire_Block")) {
                wirePositions.add(position);

                // CRITICAL: Determine wire state from block ID
                boolean isPowered = blockId.contains("_On") || blockId.contains("State_Definitions_On");
                wireStates.put(CircuitPos.from(position), isPowered);

                // LOGGER.atInfo()
                // .log(PREFIX + "[Discovery] Wire REGISTERED at " + position + " (powered=" +
                // isPowered + ")");
                return true;
            }

            // Check if it's a lever block and determine its state
            if (blockId.contains("Circuit_Lever")) {
                // CRITICAL: Determine lever state from block ID
                boolean isOn = blockId.contains("_On") || blockId.contains("State_Definitions_On");
                leverStates.put(CircuitPos.from(position), isOn);

                // LOGGER.atInfo().log(PREFIX + "[Discovery] Lever REGISTERED at " + position +
                // " (on=" + isOn + ")");
                return false;
            }

            // Check if it's an activator block
            if (blockId.contains("Circuit_Activator_Block")) {
                boolean isOn = blockId.contains("_On") || blockId.contains("State_Definitions_On");
                leverStates.put(CircuitPos.from(position), isOn);
                return false;
            }

            // Check if it's an observer block
            if (blockId.contains("Circuit_Observer") && observerSystem != null) {
                // Default to NORTH - rotation will be handled by the placement event
                // For now, discovery uses default direction
                ObserverSystem.Direction facing = ObserverSystem.Direction.NORTH;
                observerSystem.registerObserver(position, facing);
                // LOGGER.atInfo()
                // .log(PREFIX + "[Discovery] Observer REGISTERED at " + position + " (facing="
                // + facing + ")");
                return false;
            }

            // Check if it's a piston block
            if (blockId.contains("Circuit_Pusher_Piston") || blockId.contains("Circuit_Sticky_Piston")) {
                boolean isSticky = blockId.contains("Sticky");
                boolean isExtended = blockId.contains("Extended");

                // Default facing - we can't easily determine this from save, so use NORTH
                PistonSystem.Direction facing = PistonSystem.Direction.NORTH;

                // Register with piston system if not already registered
                if (pistonSystem != null && !pistonSystem.isPistonAt(position)) {
                    pistonSystem.registerPiston(position, facing, isSticky);

                    // Update extended state if needed
                    PistonSystem.PistonData piston = pistonSystem.getPistonAt(position);
                    if (piston != null) {
                        piston.isExtended = isExtended;
                    }

                    // LOGGER.atInfo().log(PREFIX + "[Discovery] Piston REGISTERED at " + position +
                    // " (sticky=" + isSticky + ", extended=" + isExtended + ")");
                }
                return false;
            }

            // Check if it's a pipe block
            if (blockId.contains("Circuit_Pipe")) {
                // Register with pipe system if not already registered
                if (pipeSystem != null && !pipeSystem.isPipeAt(position)) {
                    PipeComponent pipeComponent = new PipeComponent();
                    // Default direction for discovered pipes (save/load scenario)
                    pipeComponent.setOutputDirection(PipeComponent.Direction.NORTH);
                    pipeSystem.registerPipe(position, pipeComponent);
                    // LOGGER.atInfo()
                    // .log(PREFIX + "[Discovery] Pipe REGISTERED at " + position + " (default
                    // direction: NORTH)");
                }
                return false;
            }

            // Check if it's a vacuum pipe block
            if (blockId.contains("Circuit_Vacuum_Pipe")) {
                // Register with both pipe system and vacuum system if not already registered
                if (pipeSystem != null && !pipeSystem.isPipeAt(position)) {
                    PipeComponent pipeComponent = new PipeComponent();
                    // Default direction for discovered vacuum pipes (save/load scenario)
                    pipeComponent.setOutputDirection(PipeComponent.Direction.NORTH);
                    pipeSystem.registerPipe(position, pipeComponent);

                    // Also register with vacuum system
                    if (vacuumSystem != null) {
                        vacuumSystem.registerVacuumPipe(position, pipeComponent);
                    }

                    // LOGGER.atInfo().log(PREFIX + "[Discovery] Vacuum Pipe REGISTERED at " +
                    // position
                    // + " (default direction: NORTH)");
                }
                return false;
            }

            // Check if it's a button block
            if (blockId.contains("Circuit_Button")) {
                // Register with button system if not already registered
                if (buttonSystem != null && !buttonSystem.isButtonAt(position)) {
                    // Default facing and type for discovered buttons (save/load scenario)
                    ButtonSystem.Direction facing = ButtonSystem.Direction.NORTH;
                    ButtonSystem.PulseType buttonType = blockId.contains("Wood")
                            ? ButtonSystem.PulseType.WOOD_BUTTON
                            : ButtonSystem.PulseType.STONE_BUTTON;
                    buttonSystem.registerButton(position, facing, buttonType);
                    // LOGGER.atInfo()
                    // .log(PREFIX + "[Discovery] Button REGISTERED at " + position + " (default
                    // facing: NORTH)");
                }
                return false;
            }

            // Check if it's a pressure plate block
            if (blockId.contains("Circuit_Pressure_Plate")) {
                // Register with button system if not already registered
                if (buttonSystem != null && !buttonSystem.isButtonAt(position)) {
                    // Pressure plates face UP (sit on blocks)
                    ButtonSystem.Direction facing = ButtonSystem.Direction.UP;
                    ButtonSystem.PulseType plateType = blockId.contains("Wood")
                            ? ButtonSystem.PulseType.PRESSURE_PLATE_WOOD
                            : ButtonSystem.PulseType.PRESSURE_PLATE_STONE;
                    buttonSystem.registerButton(position, facing, plateType);
                    // LOGGER.atInfo().log(PREFIX + "[Discovery] Pressure Plate REGISTERED at " +
                    // position);
                }
                return false;
            }

            // Check if it's a lamp block
            if (blockId.contains("Circuit_Lamp")) {
                // Register with lamp system if not already registered
                if (lampSystem != null && !lampSystem.isLampAt(position)) {
                    lampSystem.registerLamp(position);
                    // LOGGER.atInfo().log(PREFIX + "[Discovery] Lamp REGISTERED at " + position);
                }
                return false;
            }

            // Check if it's a fan block
            if (blockId.contains("Circuit_Fan")) {
                // Register with fan system if not already registered
                if (fanSystem != null && !fanSystem.isFanAt(position)) {
                    PipeComponent fanComponent = new PipeComponent();
                    // Default direction for discovered fans
                    fanComponent.setOutputDirection(PipeComponent.Direction.NORTH);
                    fanSystem.registerFan(position, fanComponent);
                    // LOGGER.atInfo().log(PREFIX + "[Discovery] Fan REGISTERED at " + position);
                }
                return false;
            }

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[Discovery] Error checking block at " + position + ": " + e);
        }

        return false;
    }

    /**
     * Check if position is a wire. First checks registry, then probes world using
     * getState.
     * This handles the case where blocks were placed before restart.
     */
    public boolean isWirePositionOrProbe(Vector3i position) {
        // Already registered
        if (wirePositions.contains(position)) {
            return true;
        }

        // Try to probe the world
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return false;

            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            // Get current block - try getState
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunkAccessor
                    .getState(position.getX(), position.getY(), position.getZ(), true);

            if (blockState != null) {
                BlockType bt = blockState.getBlockType();
                if (bt != null && bt.getId() != null
                        && (bt.getId().contains("Circuit_Wire") || bt.getId().contains("Circuit_Golden_Wire"))) {
                    // Found a wire via getState
                    wirePositions.add(position);
                    wireTypes.put(position, bt.getId());
                    boolean isPowered = bt.getId().contains("_On");
                    wireStates.put(CircuitPos.from(position), isPowered);
                    // LOGGER.atInfo().log(PREFIX + "[Probe] Wire discovered at " + position + " via
                    // getState (Type: "
                    // + bt.getId() + ")");
                    return true;
                }
            }
        } catch (Exception e) {
            // Silently fail - not a wire or can't access
        }

        return false;
    }

    public boolean isWirePosition(Vector3i position) {
        return wirePositions.contains(position);
    }

    public boolean getWireState(Vector3i position) {
        return wireStates.getOrDefault(CircuitPos.from(position), false);
    }

    public void setWireState(Vector3i position, boolean powered) {
        // Auto-register wire if not already registered
        if (!wirePositions.contains(position)) {
            wirePositions.add(position);
            // LOGGER.atInfo().log(PREFIX + "[AutoRegister] Wire at " + position + "
            // registered during state update");
        }

        boolean previousState = wireStates.getOrDefault(CircuitPos.from(position), false);
        wireStates.put(CircuitPos.from(position), powered);

        if (previousState != powered) {
            try {
                // Focus on the default world
                World world = Universe.get().getDefaultWorld();
                if (world != null) {
                    // Use IChunkAccessorSync to handle chunk lookup automatically (supports cubic
                    // chunks/vertical sections)
                    com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

                    int x = position.getX();
                    int y = position.getY();
                    int z = position.getZ();

                    // Retrieve BlockState using global coordinates
                    // 4th arg 'boolean' likely means 'allowLoad' or 'compute'. Passing true.
                    com.hypixel.hytale.server.core.universe.world.meta.BlockState currentState = chunkAccessor
                            .getState(x, y, z, true);

                    if (currentState != null) {
                        String targetStateName = powered ? "On" : "Off";
                        BlockType currentType = currentState.getBlockType();

                        if (currentType != null) {
                            chunkAccessor.setBlockInteractionState(position, currentType, targetStateName);
                            // LOGGER.atInfo().log(PREFIX + "[StateChange] SUCCESS! Wire at " + position + "
                            // changed to "
                            // + targetStateName);
                        } else {
                            LOGGER.atWarning().log(PREFIX + "[StateChange] BlockType is null at " + position);
                        }
                    } else {
                        LOGGER.atWarning().log(PREFIX + "[StateChange] No block state at position " + position
                                + " (Global lookup). Attempting fallback...");

                        // Fallback: Lookup BlockType by name directly
                        try {
                            String targetStateName = powered ? "On" : "Off";
                            // Assuming BlockType follows the standard asset pattern
                            // Try normal wire first, but honestly we should try validation based on what it
                            // WAS.
                            // But we don't know what it WAS if lookup failed.
                            // Default to Circuit_Wire for fallback.
                            // TODO: Add logic to detect if it was Gold Wire.
                            // FIXED: Removed aggressive fallback. If we don't know what it is, don't
                            // corrupt it.
                            /*
                             * BlockType wireType = BlockType.getAssetMap().getAsset("Circuit_Wire");
                             * 
                             * if (wireType != null) {
                             * chunkAccessor.setBlockInteractionState(position, wireType, targetStateName);
                             * // * LOGGER.atInfo().log(PREFIX +
                             * "[StateChange] SUCCESS (Fallback)! Wire at " +
                             * // * position
                             * // * + " changed to " + targetStateName);
                             * } else {
                             * LOGGER.atWarning().log(PREFIX
                             * + "[StateChange] ERROR: Fallback failed: 'Circuit_Wire' asset not found.");
                             * }
                             */
                            LOGGER.atWarning().log(PREFIX
                                    + "[StateChange] ABORTED Fallback to avoid corrupting potential Gold/Other wires at "
                                    + position);
                        } catch (Exception e) {
                            LOGGER.atWarning().log(PREFIX + "[StateChange] ERROR: Fallback error: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                } else {
                    LOGGER.atWarning().log(PREFIX + "[StateChange] Default WORLD is null!");
                }
            } catch (Exception e) {
                LOGGER.atWarning().log(PREFIX + "Failed to update wire visual: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public Set<Vector3i> getWirePositions() {
        return new HashSet<>(wirePositions);
    }

    public void addWirePosition(Vector3i position) {
        addWirePosition(position, "Circuit_Wire");
    }

    public void addWirePosition(Vector3i position, String typeId) {
        wirePositions.add(position);
        wireTypes.put(position, typeId);
        wireStates.put(CircuitPos.from(position), false);
        if (energySystem != null)
            energySystem.updateBlock(position);
    }

    public String getWireType(Vector3i position) {
        return wireTypes.getOrDefault(position, "Circuit_Wire");
    }

    public void removeWirePosition(Vector3i position) {
        wirePositions.remove(position);
        wireTypes.remove(position);
        wireStates.remove(CircuitPos.from(position));
    }

    // ==================== General API ====================

    public ComponentType<EntityStore, CircuitComponent> getCircuitComponentType() {
        return circuitComponentType;
    }

    public EnergyPropagationSystem getEnergySystem() {
        return energySystem;
    }

    public EnergyPropagationSystem getEnergyPropagationSystem() {
        return energySystem;
    }

    public ObserverSystem getObserverSystem() {
        return observerSystem;
    }

    public PistonSystem getPistonSystem() {
        return pistonSystem;
    }

    public PipeSystem getPipeSystem() {
        return pipeSystem;
    }

    public VacuumSystem getVacuumSystem() {
        return vacuumSystem;
    }

    public ButtonSystem getButtonSystem() {
        return buttonSystem;
    }

    public LampSystem getLampSystem() {
        return lampSystem;
    }

    public RepeaterSystem getRepeaterSystem() {
        return repeaterSystem;
    }

    public DoorSystem getDoorSystem() {
        return doorSystem;
    }

    public GateSystem getGateSystem() {
        return gateSystem;
    }

    public PressurePlateSystem getPressurePlateSystem() {
        return pressurePlateSystem;
    }

    public LightSensorSystem getLightSensorSystem() {
        return lightSensorSystem;
    }

    public FanSystem getFanSystem() {
        return fanSystem;
    }

    public PoweredRailSystem getPoweredRailSystem() {
        return poweredRailSystem;
    }

    public SwitchRailSystem getSwitchRailSystem() {
        return switchRailSystem;
    }

    public DetectorRailSystem getDetectorRailSystem() {
        return detectorRailSystem;
    }

    public static CircuitPlugin get() {
        return instance;
    }

    /**
     * Discover existing circuit blocks in the world after restart.
     * This is critical for save/load functionality.
     */
    // ==================== Persistence ====================

    private static final String WIRES_FILE = "wires.dat";

    /**
     * Save wire/component positions to a file.
     * Format: x,y,z,type,state
     */
    public void saveWires() {
        if (wirePositions.isEmpty()) {
            return;
        }

        try {
            Path dataDirectory = getDataDirectory();
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path filePath = dataDirectory.resolve(WIRES_FILE);

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (Vector3i pos : wirePositions) {
                    String type = wireTypes.getOrDefault(pos, "Circuit_Wire");
                    boolean state = false;

                    // Determine state based on type
                    if (type.contains("Circuit_Lever")) {
                        state = getLeverState(pos);
                    } else if (type.contains("Circuit_Wire") || type.contains("Circuit_Golden_Wire")) {
                        // For wires, we can check wireStates
                        state = wireStates.getOrDefault(CircuitPos.from(pos), false);
                    }
                    // Add other components states here if needed

                    writer.write(pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + type + "," + state);
                    writer.newLine();
                }
            }

            // LOGGER.atInfo().log(PREFIX + "[Persistence] Saved " + wirePositions.size() +
            // " components to " + filePath);

        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "[Persistence] Failed to save components: " + e.getMessage());
        }
    }

    /**
     * Load wire/component positions from file.
     */
    public void loadWires() {
        Path filePath = getDataDirectory().resolve(WIRES_FILE);

        if (!Files.exists(filePath)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                String[] parts = line.split(",");
                if (parts.length < 3)
                    continue;

                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());

                    Vector3i pos = new Vector3i(x, y, z);
                    wirePositions.add(pos);

                    String type = "Circuit_Wire";
                    if (parts.length > 3) {
                        type = parts[3].trim();
                    }
                    wireTypes.put(pos, type);

                    boolean state = false;
                    if (parts.length > 4) {
                        state = Boolean.parseBoolean(parts[4].trim());
                    }

                    // Restore state maps
                    if (type.contains("Circuit_Lever")) {
                        leverStates.put(CircuitPos.from(pos), state);
                        // Trigger update to restore power
                        if (energySystem != null)
                            energySystem.updateBlock(pos);
                    } else if (type.contains("Circuit_Wire") || type.contains("Circuit_Golden_Wire")) {
                        wireStates.put(CircuitPos.from(pos), state);
                    }

                    count++;

                } catch (NumberFormatException e) {
                    // Ignore invalid lines
                }
            }

            // LOGGER.atInfo().log(PREFIX + "[Persistence] Loaded " + count + " components
            // from " + filePath);

        } catch (IOException e) {
            LOGGER.atWarning().log(PREFIX + "[Persistence] Failed to load components: " + e.getMessage());
        }
    }

    @Override
    protected void start() {
        // LOGGER.atInfo().log(PREFIX + "Starting CircuitMod...");

        // Load saved observer data
        if (observerSystem != null) {
            observerSystem.loadObservers(getDataDirectory());
        }

        // Load saved piston data
        if (pistonSystem != null) {
            pistonSystem.loadPistons(getDataDirectory());
        }

        // Load saved pipe data
        if (pipeSystem != null) {
            pipeSystem.loadPipes();
        }

        // Load saved vacuum pipe data
        if (vacuumSystem != null) {
            vacuumSystem.loadVacuumPipes();
        }

        // Load saved button data
        if (buttonSystem != null) {
            buttonSystem.loadButtons(getDataDirectory());
        }

        // Load saved lamp data
        if (lampSystem != null) {
            lampSystem.loadLamps(getDataDirectory());
        }

        // Load saved repeater data
        if (repeaterSystem != null) {
            repeaterSystem.loadRepeaters(getDataDirectory());
        }

        // Load saved light sensor data
        if (lightSensorSystem != null) {
            lightSensorSystem.loadSensors(getDataDirectory());
        }

        // Load saved fan data
        if (fanSystem != null) {
            fanSystem.loadFans();
        }

        // Load saved powered rail data
        if (poweredRailSystem != null) {
            poweredRailSystem.loadPoweredRails(getDataDirectory());
        }

        // Load saved switch rail data
        if (switchRailSystem != null) {
            switchRailSystem.loadSwitchRails(getDataDirectory());
        }

        // Load saved detector rail data
        if (detectorRailSystem != null) {
            detectorRailSystem.loadDetectorRails(getDataDirectory());
        }

        // Load saved wire data
        loadWires();

        // CRITICAL: Discover existing circuit blocks after restart
        // discoverExistingCircuitBlocks(); // Deprecated in favor of persistence

        // LOGGER.atInfo().log(PREFIX + "CircuitMod started!");
    }

    @Override
    protected void shutdown() {
        // LOGGER.atInfo().log(PREFIX + "Shutting down CircuitMod...");

        // Save observer data
        if (observerSystem != null) {
            observerSystem.saveObservers(getDataDirectory());
        }

        // Save piston data
        if (pistonSystem != null) {
            pistonSystem.savePistons(getDataDirectory());
        }

        // Save repeater data
        if (repeaterSystem != null) {
            repeaterSystem.saveRepeaters(getDataDirectory());
        }

        // Save pipe data
        if (pipeSystem != null) {
            pipeSystem.savePipes();
        }

        // Save vacuum pipe data
        if (vacuumSystem != null) {
            vacuumSystem.saveVacuumPipes();
        }

        // Save button data
        if (buttonSystem != null) {
            buttonSystem.saveButtons(getDataDirectory());
        }

        // Save lamp data
        if (lampSystem != null) {
            lampSystem.saveLamps(getDataDirectory());
        }

        // Save light sensor data
        if (lightSensorSystem != null) {
            lightSensorSystem.saveSensors(getDataDirectory());
        }

        // Save fan data
        if (fanSystem != null) {
            fanSystem.saveFans();
        }

        // Save powered rail data
        if (poweredRailSystem != null) {
            poweredRailSystem.savePoweredRails(getDataDirectory());
        }

        // Save switch rail data
        if (switchRailSystem != null) {
            switchRailSystem.saveSwitchRails(getDataDirectory());
        }

        // Save detector rail data
        if (detectorRailSystem != null) {
            detectorRailSystem.saveDetectorRails(getDataDirectory());
        }

        // Save wire data
        saveWires();

        // LOGGER.atInfo().log(PREFIX + "CircuitMod shutdown complete!");
    }
}
