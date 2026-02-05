package com.circuit;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS Event System to handle block break events.
 * Cleans up lever, wire, and observer registrations when broken.
 */
public class BreakBlockEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";

    private final CircuitPlugin plugin;

    public BreakBlockEventSystem(CircuitPlugin plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
        // LOGGER.atInfo().log(PREFIX + "BreakBlockEventSystem initialized");
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {

        Vector3i pos = event.getTargetBlock();
        String blockId = event.getBlockType() != null ? event.getBlockType().getId() : "";

        if (blockId != null && blockId.contains("Circuit_")) {
            // LOGGER.atInfo().log(PREFIX + "Block broken at " + pos + " type=" + blockId);
            plugin.handleBlockBreak(pos);

            // Unregister Observer if it was one
            if (blockId.contains("Circuit_Observer") && plugin.getObserverSystem() != null) {
                plugin.getObserverSystem().unregisterObserver(pos);
            }

            // Unregister Piston if it was one
            if ((blockId.contains("Circuit_Pusher_Piston") || blockId.contains("Circuit_Sticky_Piston"))
                    && plugin.getPistonSystem() != null) {
                plugin.getPistonSystem().unregisterPiston(pos);
            }

            // Unregister Pipe if it was one
            if (blockId.contains("Circuit_Pipe") && plugin.getPipeSystem() != null) {
                plugin.getPipeSystem().unregisterPipe(pos);
            }

            // Unregister Repeater if it was one
            if (blockId.contains("Circuit_Repeater") && plugin.getRepeaterSystem() != null) {
                plugin.getRepeaterSystem().unregisterRepeater(pos);
            }

            // Unregister Pressure Plate if it was one
            if (blockId.contains("Circuit_Pressure_Plate")) {
                if (plugin.getPressurePlateSystem() != null) {
                    plugin.getPressurePlateSystem().onPressurePlateRemoved(pos);
                }
                if (plugin.getButtonSystem() != null) {
                    plugin.getButtonSystem().unregisterButton(pos);
                }
            }

            // Unregister Lamp if it was one
            if (blockId.contains("Circuit_Lamp") && plugin.getLampSystem() != null) {
                plugin.getLampSystem().unregisterLamp(pos);
            }

            // Unregister Light Sensor if it was one
            if (blockId.contains("Circuit_Light_Sensor") && plugin.getLightSensorSystem() != null) {
                plugin.getLightSensorSystem().unregisterSensor(pos);
                // Trigger network update to clear energy from connected wires
                if (plugin.getEnergySystem() != null) {
                    plugin.getEnergySystem().updateNetwork(pos);
                }
            }

            // Unregister Button if it was one
            if (blockId.contains("Circuit_Button") && plugin.getButtonSystem() != null) {
                plugin.getButtonSystem().unregisterButton(pos);
            }
        }

        // Notify Observer system about block change (any block, not just circuit
        // blocks)
        if (plugin.getObserverSystem() != null) {
            plugin.getObserverSystem().onBlockChange(pos);
        }

        // Check if a door or gate was broken and unregister it
        if (plugin.getDoorSystem() != null) {
            if (event.getBlockType() != null && 
                (event.getBlockType().isDoor() || 
                 (blockId != null && (blockId.toLowerCase().contains("gate") || 
                                     blockId.toLowerCase().contains("fence_gate") || 
                                     blockId.toLowerCase().contains("fencegate"))))) {
                plugin.getDoorSystem().unregisterDoor(pos);
                // LOGGER.atInfo().log(PREFIX + "Door/Gate unregistered at " + pos);
            }
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
