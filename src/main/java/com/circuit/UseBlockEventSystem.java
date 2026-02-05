package com.circuit;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS Event System to handle block use events (F key interaction).
 */
public class UseBlockEventSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";

    private final CircuitPlugin plugin;
    private int callCount = 0;

    public UseBlockEventSystem(CircuitPlugin plugin) {
        super(UseBlockEvent.Pre.class);
        this.plugin = plugin;
        // LOGGER.atInfo().log(PREFIX + "UseBlockEventSystem initialized");
    }

    @Override
    public void handleInternal(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event) {
        callCount++;
        // LOGGER.atInfo().log(PREFIX + "handleInternal called! count=" + callCount);
        super.handleInternal(index, archetypeChunk, store, commandBuffer, event);
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event) {

        // LOGGER.atInfo().log(PREFIX + "=== UseBlockEvent.Pre TRIGGERED ===");

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            // LOGGER.atInfo().log(PREFIX + "blockType is NULL");
            return;
        }

        String blockId = blockType.getId();
        Vector3i pos = event.getTargetBlock();
        // LOGGER.atInfo().log(PREFIX + "UseBlockEvent: blockId=" + blockId + " pos=" +
        // pos);

        if (blockId == null) {
            return;
        }

        // Handle Circuit_Lever interaction
        if (blockId.contains("Circuit_Lever")) {
            // State is embedded in blockId after ChangeState runs:
            // *Circuit_Lever_State_Definitions_On or *Circuit_Lever_State_Definitions_Off
            boolean isOn = blockId.contains("_On");
            // Invert the state because this is a Pre event (before toggle)
            plugin.setLeverState(pos, !isOn);

            String stateText = !isOn ? "ON" : "OFF";
            // LOGGER.atInfo().log(PREFIX + ">>> Lever at " + pos + " state updated -> " +
            // stateText);

            // CRITICAL: Update energy network to propagate power changes
            plugin.getEnergySystem().updateNetwork(pos);
        }

        // Handle Circuit_Activator_Block interaction
        if (blockId.contains("Circuit_Activator_Block")) {
            // State is embedded in blockId after ChangeState runs
            boolean isOn = blockId.contains("_On");
            // Invert the state because this is a Pre event (before toggle)
            plugin.setLeverState(pos, !isOn);

            // CRITICAL: Update energy network to propagate power changes
            plugin.getEnergySystem().updateNetwork(pos);
        }

        // Handle Circuit_Button interaction
        if (blockId.contains("Circuit_Button")) {
            // LOGGER.atInfo().log(PREFIX + "=== BUTTON INTERACTION DEBUG START ===");
            // LOGGER.atInfo().log(PREFIX + "Block ID: " + blockId);
            // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

            // Check if button is already pressed (in block ID or via ButtonSystem)
            boolean isPressed = blockId.contains("Pressed") || plugin.getButtonSystem().isButtonPressed(pos);

            if (isPressed) {
                // LOGGER.atInfo().log(PREFIX + "Button at " + pos + " is already pressed,
                // ignoring");
                // LOGGER.atInfo().log(PREFIX + "=== BUTTON INTERACTION DEBUG END (ALREADY
                // PRESSED) ===");
                return;
            }

            // Activate the button
            plugin.getButtonSystem().activateButton(pos);
            // LOGGER.atInfo().log(PREFIX + ">>> Button at " + pos + " ACTIVATED - pulse
            // started");

            // CRITICAL: Update energy network to propagate power changes
            plugin.getEnergySystem().updateNetwork(pos);

            // Get player for feedback
            var playerEntity = archetypeChunk.getComponent(index,
                    com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
            // if (playerEntity != null) {
            // playerEntity.sendMessage(com.hypixel.hytale.server.core.Message.raw("*click*"));
            // }

            // LOGGER.atInfo().log(PREFIX + "=== BUTTON INTERACTION DEBUG END ===");
        }

        // Handle Circuit_Pressure_Plate interaction (use event triggered by
        // collision/trigger block)
        if (blockId.contains("Circuit_Pressure_Plate")) {
            // LOGGER.atInfo().log(PREFIX + "=== PRESSURE PLATE INTERACTION DEBUG START
            // ===");
            // LOGGER.atInfo().log(PREFIX + "Block ID: " + blockId);
            // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

            // Check if plate is already pressed
            boolean isPressed = blockId.contains("Pressed") || plugin.getButtonSystem().isButtonPressed(pos);

            if (isPressed) {
                // LOGGER.atInfo().log(PREFIX + "Pressure plate at " + pos + " is already
                // pressed, ignoring");
                // LOGGER.atInfo().log(PREFIX + "=== PRESSURE PLATE INTERACTION DEBUG END
                // (ALREADY PRESSED) ===");
                return;
            }

            // Activate the pressure plate
            plugin.getButtonSystem().activateButton(pos);
            // LOGGER.atInfo().log(PREFIX + ">>> Pressure Plate at " + pos + " ACTIVATED -
            // pulse started");

            // CRITICAL: Update energy network to propagate power changes
            plugin.getEnergySystem().updateNetwork(pos);

            // LOGGER.atInfo().log(PREFIX + "=== PRESSURE PLATE INTERACTION DEBUG END ===");
        }

        // Handle Circuit_Pipe interaction - Open inventory GUI
        if (blockId.contains("Circuit_Pipe")) {
            // LOGGER.atInfo().log(PREFIX + "=== PIPE INTERACTION DEBUG START ===");
            // LOGGER.atInfo().log(PREFIX + "Block ID: " + blockId);
            // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

            // Get the pipe component first
            PipeComponent pipeComponent = plugin.getPipeSystem().getPipeAt(pos);
            if (pipeComponent == null) {
                LOGGER.atWarning().log(PREFIX + "No pipe component found at " + pos);
                // LOGGER.atInfo().log(PREFIX + "=== PIPE INTERACTION DEBUG END (NO COMPONENT)
                // ===");
                return;
            }

            // LOGGER.atInfo().log(PREFIX + "Pipe component found: " + pipeComponent);
            // LOGGER.atInfo().log(PREFIX + "Output direction: " +
            // pipeComponent.getOutputDirection());

            // Get the player entity
            var playerEntity = archetypeChunk.getComponent(index,
                    com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
            if (playerEntity == null) {
                LOGGER.atWarning().log(PREFIX + "Player entity is null!");
                // LOGGER.atInfo().log(PREFIX + "=== PIPE INTERACTION DEBUG END (NO PLAYER)
                // ===");
                return;
            }

            // LOGGER.atInfo().log(PREFIX + "Player entity found: " + playerEntity);

            // For now, always open GUI (we'll add shift detection later)
            // LOGGER.atInfo().log(PREFIX + ">>> Pipe used at " + pos + " - Opening
            // inventory GUI");

            try {
                // Get pipe buffer
                ItemContainer pipeBuffer = pipeComponent.getBuffer();
                if (pipeBuffer == null) {
                    LOGGER.atWarning().log(PREFIX + "Pipe buffer is null!");
                    playerEntity
                            .sendMessage(com.hypixel.hytale.server.core.Message.raw("Error: Pipe buffer is null"));
                    // LOGGER.atInfo().log(PREFIX + "=== PIPE INTERACTION DEBUG END (NULL BUFFER)
                    // ===");
                    return;
                }

                // LOGGER.atInfo().log(PREFIX + "Pipe buffer found: " + pipeBuffer);
                // LOGGER.atInfo().log(PREFIX + "Buffer capacity: " + pipeBuffer.getCapacity());
                // LOGGER.atInfo().log(PREFIX + "Buffer empty: " + pipeBuffer.isEmpty());

                // Create ContainerWindow with just the ItemContainer
                // LOGGER.atInfo().log(PREFIX + "Creating ContainerWindow...");
                ContainerWindow pipeWindow = new ContainerWindow(pipeBuffer);
                // LOGGER.atInfo().log(PREFIX + "ContainerWindow created: " + pipeWindow);

                // Get WindowManager and open the window
                var windowManager = playerEntity.getWindowManager();
                // LOGGER.atInfo().log(PREFIX + "WindowManager: " + windowManager);

                var entityRef = archetypeChunk.getReferenceTo(index);
                // LOGGER.atInfo().log(PREFIX + "EntityRef: " + entityRef);

                // Open the window
                // LOGGER.atInfo().log(PREFIX + "Calling windowManager.openWindow()...");
                var openedWindow = windowManager.openWindow(entityRef, pipeWindow, store);
                // LOGGER.atInfo().log(PREFIX + "Window opened successfully!");
                // LOGGER.atInfo().log(PREFIX + "Opened window: " + openedWindow);
                // LOGGER.atInfo().log(PREFIX + "Window ID: " + openedWindow.getId());

                // LOGGER.atInfo().log(
                // PREFIX + "Successfully opened pipe inventory GUI (Window ID: " +
                // openedWindow.getId() + ")");

                // Show pipe info in chat
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("Pipe Output Direction: " + pipeComponent.getOutputDirection()));
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("Connections: " + pipeComponent.getConnectionCount() + " directions"));
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("Note: Direction is set when placing the pipe based on your facing direction"));
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("GUI opened successfully! Window ID: " + openedWindow.getId()));

                // LOGGER.atInfo().log(PREFIX + "=== PIPE INTERACTION DEBUG END (SUCCESS) ===");

                // Don't cancel the event - let it proceed normally

            } catch (Exception guiError) {
                // Fallback to chat messages if GUI fails
                LOGGER.atWarning().log(PREFIX + "Failed to open pipe GUI: " + guiError.getMessage());
                LOGGER.atWarning().log(PREFIX + "Exception class: " + guiError.getClass().getName());
                guiError.printStackTrace();

                // Show pipe status in chat as fallback
                playerEntity.sendMessage(
                        com.hypixel.hytale.server.core.Message.raw("Failed to open GUI: " + guiError.getMessage()));
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("Output Direction:" + pipeComponent.getOutputDirection()));

                try {
                    ItemContainer pipeBuffer = pipeComponent.getBuffer();
                    ItemStack bufferItem = pipeBuffer.getItemStack((short) 0);
                    if (bufferItem != null && !bufferItem.isEmpty()) {
                        playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                                .raw("Buffer: " + bufferItem.getItemId() + " x" + bufferItem.getQuantity()));
                    } else {
                        playerEntity.sendMessage(com.hypixel.hytale.server.core.Message.raw("Buffer: Empty"));
                    }
                } catch (Exception e) {
                    playerEntity.sendMessage(com.hypixel.hytale.server.core.Message.raw("Error reading buffer"));
                }

                // Show connections
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("Connections: " + pipeComponent.getConnectionCount() + " directions"));
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message
                        .raw("Note: Direction is set when placing the pipe based on your facing direction"));

                // LOGGER.atInfo().log(PREFIX + "=== PIPE INTERACTION DEBUG END (EXCEPTION)
                // ===");
            }
        }

        // Handle Circuit_Hopper interaction
        if (blockId.contains("Circuit_Hopper")) {
            // LOGGER.atInfo().log(PREFIX + ">>> Hopper used at " + pos);
        }

        // Handle Circuit_Lamp interaction - Manual toggle
        if (blockId.contains("Circuit_Lamp")) {
            // LOGGER.atInfo().log(PREFIX + "=== LAMP INTERACTION DEBUG START ===");
            // LOGGER.atInfo().log(PREFIX + "Block ID: " + blockId);
            // LOGGER.atInfo().log(PREFIX + "Position: " + pos);

            // Check if this lamp is registered in our system
            LampSystem lampSystem = plugin.getLampSystem();
            if (lampSystem == null || !lampSystem.isLampAt(pos)) {
                LOGGER.atWarning().log(PREFIX + "Lamp not registered at " + pos);
                // LOGGER.atInfo().log(PREFIX + "=== LAMP INTERACTION DEBUG END (NOT REGISTERED)
                // ===");
                return;
            }

            // Get current state from block ID
            boolean isCurrentlyOn = blockId.contains("_On") || blockId.contains("State_Definitions_On");
            boolean newState = !isCurrentlyOn; // Toggle

            // LOGGER.atInfo().log(PREFIX + "Current lamp state: " + (isCurrentlyOn ? "ON" :
            // "OFF"));
            // LOGGER.atInfo().log(PREFIX + "New lamp state: " + (newState ? "ON" : "OFF"));

            // Update lamp state manually (override circuit control temporarily)
            lampSystem.updateLampState(pos, newState ? 15 : 0);

            // Get player for feedback
            var playerEntity = archetypeChunk.getComponent(index,
                    com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
            if (playerEntity != null) {
                String message = newState ? "Lamp turned ON" : "Lamp turned OFF";
                playerEntity.sendMessage(com.hypixel.hytale.server.core.Message.raw(message));
            }

            // LOGGER.atInfo().log(PREFIX + ">>> Lamp at " + pos + " manually toggled -> " +
            // (newState ? "ON" : "OFF"));
            // LOGGER.atInfo().log(PREFIX + "=== LAMP INTERACTION DEBUG END ===");
        }

        // Handle Circuit_Repeater interaction - Cycle Delay
        if (blockId.contains("Circuit_Repeater")) {
            // LOGGER.atInfo().log(PREFIX + ">>> Repeater used at " + pos);
            if (plugin.getRepeaterSystem() != null) {
                plugin.getRepeaterSystem().cycleDelay(pos);

                // Get player for feedback
                var playerEntity = archetypeChunk.getComponent(index,
                        com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                if (playerEntity != null) {
                    playerEntity.sendMessage(com.hypixel.hytale.server.core.Message.raw("Delay Cycled"));
                }
            }
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        // LOGGER.atInfo().log(PREFIX + "getQuery() called");
        return Archetype.empty();
    }
}
