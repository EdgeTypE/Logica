package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Comprehensive gate system that handles all logic gates with multiple inputs and outputs.
 * Supports directional gates with configurable input/output sides.
 */
public class GateSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";

    private final CircuitPlugin plugin;
    
    // Gate registry - maps position to gate data
    private final Map<Vector3i, GateData> gates = new HashMap<>();
    
    // Direction offsets for neighbor checking
    private static final int[][] NEIGHBOR_OFFSETS = {
            { 1, 0, 0 }, { -1, 0, 0 },  // East, West
            { 0, 1, 0 }, { 0, -1, 0 },  // Up, Down
            { 0, 0, 1 }, { 0, 0, -1 }   // South, North
    };

    public GateSystem(CircuitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Gates are event-driven, no tick processing needed
    }

    /**
     * Registers a gate at the specified position.
     */
    public void registerGate(Vector3i pos, GateType type, CircuitComponent.Direction facing) {
        GateData gateData = new GateData(type, facing);
        gates.put(pos, gateData);
        
        // LOGGER.atInfo().log(PREFIX + "[GateSystem] Registered " + type + " gate at " + pos + " facing " + facing);
        
        // Initial state calculation
        updateGateState(pos);
    }

    /**
     * Unregisters a gate at the specified position.
     */
    public void unregisterGate(Vector3i pos) {
        GateData removed = gates.remove(pos);
        if (removed != null) {
            // LOGGER.atInfo().log(PREFIX + "[GateSystem] Unregistered " + removed.type + " gate at " + pos);
            
            // Update neighbors when gate is removed
            if (plugin.getEnergySystem() != null) {
                plugin.getEnergySystem().updateNeighbors(pos);
            }
        }
    }

    /**
     * Checks if there's a gate at the specified position.
     */
    public boolean isGateAt(Vector3i pos) {
        return gates.containsKey(pos);
    }

    /**
     * Gets the gate type at the specified position.
     */
    public GateType getGateType(Vector3i pos) {
        GateData gate = gates.get(pos);
        return gate != null ? gate.type : null;
    }

    /**
     * Updates the state of a gate based on its inputs.
     * Called when neighboring blocks change.
     */
    public void updateGateState(Vector3i pos) {
        GateData gate = gates.get(pos);
        if (gate == null) return;

        // Get input power levels
        int[] inputs = getGateInputs(pos, gate);
        
        // Calculate output based on gate type
        int newOutput = calculateGateOutput(gate.type, inputs);
        
        // Update output if changed
        if (newOutput != gate.outputPower) {
            gate.outputPower = newOutput;
            gate.lastUpdate = System.currentTimeMillis();
            
            // LOGGER.atInfo().log(PREFIX + "[GateSystem] " + gate.type + " at " + pos + 
                // " inputs=" + Arrays.toString(inputs) + " output=" + newOutput);
            
            // Update visual state
            updateGateVisual(pos, gate);
            
            // Don't call updateNeighbors here - let EnergyPropagationSystem handle it
            // This prevents double updates and infinite loops
        }
    }

    /**
     * Gets the power output from a gate to a specific target position.
     * Used by the energy propagation system.
     */
    public int getPowerOutput(Vector3i gatePos, Vector3i targetPos) {
        GateData gate = gates.get(gatePos);
        if (gate == null) return 0;

        // Check if target is in the output direction
        if (isOutputDirection(gatePos, targetPos, gate)) {
            return gate.outputPower;
        }
        
        return 0;
    }

    /**
     * Gets the current output power of a gate (without direction check).
     * Used for tracking power level changes.
     */
    public int getOutputPower(Vector3i gatePos) {
        GateData gate = gates.get(gatePos);
        return gate != null ? gate.outputPower : 0;
    }

    /**
     * Gets input power levels for a gate based on its type and facing direction.
     */
    private int[] getGateInputs(Vector3i pos, GateData gate) {
        switch (gate.type) {
            case NOT:
                // NOT gate has 1 input from the back
                return new int[] { getInputPower(pos, getBackDirection(gate.facing)) };
                
            case AND:
            case NAND:
            case OR:
            case NOR:
            case XOR:
            case XNOR:
                // 2-input gates: left and right sides
                return new int[] {
                    getInputPower(pos, getLeftDirection(gate.facing)),
                    getInputPower(pos, getRightDirection(gate.facing))
                };
                
            case BUFFER:
                // Buffer has 1 input from the back
                return new int[] { getInputPower(pos, getBackDirection(gate.facing)) };
                
            default:
                return new int[0];
        }
    }

    /**
     * Gets the power level from a specific direction relative to the gate.
     */
    private int getInputPower(Vector3i gatePos, CircuitComponent.Direction inputDir) {
        Vector3i inputPos = new Vector3i(
            gatePos.getX() + inputDir.getX(),
            gatePos.getY() + inputDir.getY(),
            gatePos.getZ() + inputDir.getZ()
        );

        // Get power from energy system
        if (plugin.getEnergySystem() != null) {
            return plugin.getEnergySystem().getEnergyLevel(inputPos);
        }
        
        return 0;
    }

    /**
     * Calculates the output power for a gate based on its type and inputs.
     */
    private int calculateGateOutput(GateType type, int[] inputs) {
        switch (type) {
            case NOT:
                // NOT: output 15 if input is 0, else 0
                return (inputs.length > 0 && inputs[0] == 0) ? 15 : 0;
                
            case AND:
                // AND: output 15 only if ALL inputs > 0
                if (inputs.length < 2) return 0;
                return (inputs[0] > 0 && inputs[1] > 0) ? 15 : 0;
                
            case NAND:
                // NAND: output 0 only if ALL inputs > 0, else 15
                if (inputs.length < 2) return 15;
                return (inputs[0] > 0 && inputs[1] > 0) ? 0 : 15;
                
            case OR:
                // OR: output 15 if ANY input > 0
                if (inputs.length < 2) return 0;
                return (inputs[0] > 0 || inputs[1] > 0) ? 15 : 0;
                
            case NOR:
                // NOR: output 15 only if ALL inputs == 0
                if (inputs.length < 2) return 15;
                return (inputs[0] == 0 && inputs[1] == 0) ? 15 : 0;
                
            case XOR:
                // XOR: output 15 if exactly one input > 0
                if (inputs.length < 2) return 0;
                boolean input1Active = inputs[0] > 0;
                boolean input2Active = inputs[1] > 0;
                return (input1Active != input2Active) ? 15 : 0;
                
            case XNOR:
                // XNOR: output 15 if inputs are equal (both 0 or both > 0)
                if (inputs.length < 2) return 15;
                boolean input1On = inputs[0] > 0;
                boolean input2On = inputs[1] > 0;
                return (input1On == input2On) ? 15 : 0;
                
            case BUFFER:
                // Buffer: passes through signal (with potential delay)
                return (inputs.length > 0 && inputs[0] > 0) ? 15 : 0;
                
            default:
                return 0;
        }
    }

    /**
     * Checks if the target position is in the output direction of the gate.
     */
    private boolean isOutputDirection(Vector3i gatePos, Vector3i targetPos, GateData gate) {
        // Output is always in the front direction
        CircuitComponent.Direction outputDir = gate.facing;
        
        Vector3i expectedOutput = new Vector3i(
            gatePos.getX() + outputDir.getX(),
            gatePos.getY() + outputDir.getY(),
            gatePos.getZ() + outputDir.getZ()
        );
        
        return expectedOutput.equals(targetPos);
    }

    /**
     * Updates the visual state of a gate (powered/unpowered texture).
     */
    private void updateGateVisual(Vector3i pos, GateData gate) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;

            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
            
            // Determine the correct block type based on gate type and power state
            String blockId = getGateBlockId(gate.type, gate.outputPower > 0);
            
            BlockType newBlockType = BlockType.getAssetMap().getAsset(blockId);
            if (newBlockType != null) {
                // Use setBlockInteractionState with proper parameters
                // The second parameter should be the BlockType, third should be the state (can be null for simple blocks)
                chunkAccessor.setBlockInteractionState(pos, newBlockType, "default");
                // LOGGER.atInfo().log(PREFIX + "[GateSystem] Updated visual for " + gate.type + " at " + pos + " to " + blockId);
            } else {
                LOGGER.atWarning().log(PREFIX + "[GateSystem] Could not find BlockType for " + blockId);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[GateSystem] Failed to update visual at " + pos + ": " + e.getMessage());
        }
    }

    /**
     * Gets the appropriate block ID for a gate type and power state.
     */
    private String getGateBlockId(GateType type, boolean powered) {
        String suffix = powered ? "_On" : "";
        return "Circuit_Gate_" + type.name() + suffix;
    }

    // Direction helper methods
    private CircuitComponent.Direction getBackDirection(CircuitComponent.Direction facing) {
        return facing.getOpposite();
    }

    private CircuitComponent.Direction getLeftDirection(CircuitComponent.Direction facing) {
        return switch (facing) {
            case NORTH -> CircuitComponent.Direction.WEST;
            case EAST -> CircuitComponent.Direction.NORTH;
            case SOUTH -> CircuitComponent.Direction.EAST;
            case WEST -> CircuitComponent.Direction.SOUTH;
            default -> CircuitComponent.Direction.WEST; // Default for UP/DOWN
        };
    }

    private CircuitComponent.Direction getRightDirection(CircuitComponent.Direction facing) {
        return switch (facing) {
            case NORTH -> CircuitComponent.Direction.EAST;
            case EAST -> CircuitComponent.Direction.SOUTH;
            case SOUTH -> CircuitComponent.Direction.WEST;
            case WEST -> CircuitComponent.Direction.NORTH;
            default -> CircuitComponent.Direction.EAST; // Default for UP/DOWN
        };
    }

    /**
     * Gate types supported by the system.
     */
    public enum GateType {
        NOT,     // 1 input, 1 output - inverts signal
        AND,     // 2 inputs, 1 output - both inputs must be on
        NAND,    // 2 inputs, 1 output - NOT AND
        OR,      // 2 inputs, 1 output - either input can be on
        NOR,     // 2 inputs, 1 output - NOT OR
        XOR,     // 2 inputs, 1 output - exactly one input must be on
        XNOR,    // 2 inputs, 1 output - NOT XOR
        BUFFER   // 1 input, 1 output - passes signal through (with delay)
    }

    /**
     * Data structure for storing gate information.
     */
    private static class GateData {
        final GateType type;
        final CircuitComponent.Direction facing;
        int outputPower;
        long lastUpdate;

        GateData(GateType type, CircuitComponent.Direction facing) {
            this.type = type;
            this.facing = facing;
            this.outputPower = 0;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return null;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}