package com.circuit;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * System that processes all circuit components every tick.
 * Handles energy propagation, cooldowns, and component-specific logic.
 */
public class CircuitSystem extends EntityTickingSystem<EntityStore> {

    private final ComponentType<EntityStore, CircuitComponent> circuitComponentType;

    public CircuitSystem(ComponentType<EntityStore, CircuitComponent> circuitComponentType) {
        this.circuitComponentType = circuitComponentType;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        CircuitComponent circuit = archetypeChunk.getComponent(index, circuitComponentType);
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        // Handle cooldown
        if (circuit.isOnCooldown()) {
            circuit.decrementCooldown(dt);
        }

        // Process based on circuit type
        switch (circuit.getCircuitType()) {
            case LEVER -> processLever(circuit, store, commandBuffer, ref);
            case WIRE -> processWire(circuit, store, commandBuffer, ref);
            case OBSERVER -> processObserver(circuit, store, commandBuffer, ref);
            case HOPPER -> processHopper(circuit, store, commandBuffer, ref, dt);
            case GATE_AND -> processGateAnd(circuit, store, commandBuffer, ref);
            case GATE_OR -> processGateOr(circuit, store, commandBuffer, ref);
            case GATE_NOR -> processGateNor(circuit, store, commandBuffer, ref);
            case GATE_XOR -> processGateXor(circuit, store, commandBuffer, ref);
            case GATE_NOT -> processGateNot(circuit, store, commandBuffer, ref);
            case GATE_NAND -> processGateNand(circuit, store, commandBuffer, ref);
            case GATE_XNOR -> processGateXnor(circuit, store, commandBuffer, ref);
            case GATE_BUFFER -> processGateBuffer(circuit, store, commandBuffer, ref);
        }
    }

    // ==================== Component Processors ====================

    private void processLever(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // Lever is a source - it outputs power when active
        if (circuit.isActive()) {
            circuit.setEnergyLevel(15);
        } else {
            circuit.setEnergyLevel(0);
        }
    }

    private void processWire(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // Wire propagates energy with decay
        // This is a placeholder - actual implementation needs neighbor checking
        // Energy will be set by neighboring blocks that output to this wire

        // If no input, energy decays to 0
        // Actual propagation will happen in a separate system that handles block
        // positions
    }

    private void processObserver(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // Observer sends a pulse when it detects a block change
        // This is triggered by block events, not by tick
        // Here we just handle the pulse cooldown

        if (circuit.isActive() && !circuit.isOnCooldown()) {
            // Send a 1-tick pulse
            circuit.setEnergyLevel(15);
            circuit.startCooldown();
        } else if (circuit.isOnCooldown()) {
            // Pulse is over, reset
            circuit.setActive(false);
            circuit.setEnergyLevel(0);
        }
    }

    private void processHopper(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref, float dt) {
        // Hopper works when NOT powered (like Minecraft)
        // When powered, it's locked
        if (circuit.getEnergyLevel() == 0) {
            circuit.setActive(true);
            // TODO: Item collection and transfer logic
            // This requires ItemContainer and dropped item detection
        } else {
            circuit.setActive(false); // Locked by power
        }
    }

    // ==================== Logic Gates ====================

    private void processGateAnd(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // AND gate: Output 15 only if ALL inputs > 0
        // Inputs are from sides, output is front
        // TODO: Get inputs from neighboring blocks
        // For now, placeholder logic
    }

    private void processGateOr(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // OR gate: Output 15 if ANY input > 0
    }

    private void processGateNor(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // NOR gate: Output 15 only if ALL inputs == 0
    }

    private void processGateXor(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // XOR gate: Output 15 if exactly one input > 0
    }

    private void processGateNot(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // NOT gate (Inverter): Output 15 if input == 0, else 0
    }

    private void processGateNand(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // NAND gate: Output 0 only if ALL inputs > 0, else 15
    }

    private void processGateXnor(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // XNOR gate: Output 15 if inputs are equal (both 0 or both > 0)
    }

    private void processGateBuffer(CircuitComponent circuit, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        // Buffer gate: Passes through signal with delay (useful for timing)
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        // Run in the default ticking group
        return null;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(this.circuitComponentType);
    }
}
