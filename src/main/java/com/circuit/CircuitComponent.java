package com.circuit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/**
 * Component that stores circuit-related data for blocks.
 * All circuit blocks (wires, levers, gates, etc.) share this componen
 * .
 */
public class CircuitComponent implements Component<EntityStore> {

    // Energy level from 0-15, similar to Minecraft redstone
    private int energyLevel;

    // Whether this component is currently active/powered
    private boolean isActive;

    // The type of circuit component
    private CircuitType circuitType;

    // Direction the block is facing (for directional blocks like observer
    // )
    private Direction facing;

    // Upgrade tier for future expansion (e.g., hopper capacity upgrades)
    private int upgradeTier;

    // Cooldown timer for components that need it
    private float cooldownTimer;

    // Maximum cooldown duration
    private float maxCooldown;

    public CircuitComponent() {
        this(CircuitType.WIRE, 0, false, Direction.NORTH, 0);
    }

    public CircuitComponent(CircuitType type, int energyLevel, boolean isActive, Direction facing, int upgradeTier) {
        this.circuitType = type;
        this.energyLevel = energyLevel;
        this.isActive = isActive;
        this.facing = facing;
        this.upgradeTier = upgradeTier;
        this.cooldownTimer = 0f;
        this.maxCooldown = 0.5f; // Default 0.5 second cooldown
    }

    public CircuitComponent(CircuitComponent other) {
        this.circuitType = other.circuitType;
        this.energyLevel = other.energyLevel;
        this.isActive = other.isActive;
        this.facing = other.facing;
        this.upgradeTier = other.upgradeTier;
        this.cooldownTimer = other.cooldownTimer;
        this.maxCooldown = other.maxCooldown;
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return new CircuitComponent(this);
    }

    // Energy Level
    public int getEnergyLevel() {
        return energyLevel;
    }

    public void setEnergyLevel(int level) {
        this.energyLevel = Math.max(0, Math.min(15, level));
    }

    public boolean hasPower() {
        return energyLevel > 0;
    }

    // Active State
    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public void toggle() {
        this.isActive = !this.isActive;
    }

    // Circuit Type
    public CircuitType getCircuitType() {
        return circuitType;
    }

    public void setCircuitType(CircuitType type) {
        this.circuitType = type;
    }

    // Direction
    public Direction getFacing() {
        return facing;
    }

    public void setFacing(Direction facing) {
        this.facing = facing;
    }

    // Upgrade Tier
    public int getUpgradeTier() {
        return upgradeTier;
    }

    public void setUpgradeTier(int tier) {
        this.upgradeTier = Math.max(0, tier);
    }

    // Cooldown
    public float getCooldownTimer() {
        return cooldownTimer;
    }

    public void setCooldownTimer(float timer) {
        this.cooldownTimer = timer;
    }

    public void decrementCooldown(float dt) {
        this.cooldownTimer = Math.max(0, this.cooldownTimer - dt);
    }

    public boolean isOnCooldown() {
        return cooldownTimer > 0;
    }

    public void startCooldown() {
        this.cooldownTimer = this.maxCooldown;
    }

    public float getMaxCooldown() {
        return maxCooldown;
    }

    public void setMaxCooldown(float maxCooldown) {
        this.maxCooldown = maxCooldown;
    }

    /**
     * Get hopper capacity based on upgrade tier.
     * Base: 5 slots, +3 per tier
     */
    public int getHopperCapacity() {
        return 5 + (upgradeTier * 3);
    }

    /**
     * Circuit component types
     */
    public enum CircuitType {
        WIRE,
        LEVER,
        BUTTON,
        PRESSURE_PLATE,
        OBSERVER,
        HOPPER,
        GATE_AND,
        GATE_OR,
        GATE_NOR,
        GATE_XOR,
        GATE_NOT,
        GATE_NAND,
        GATE_XNOR,
        GATE_BUFFER
    }

    /**
     * Directions for facing
     */
    public enum Direction {
        NORTH(0, 0, -1),
        EAST(1, 0, 0),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        UP(0, 1, 0),
        DOWN(0, -1, 0);

        private final int x, y, z;

        Direction(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

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

        public static Direction fromRotation(int rotation) {
            return switch (rotation % 4) {
                case 0 -> NORTH;
                case 1 -> EAST;
                case 2 -> SOUTH;
                case 3 -> WEST;
                default -> NORTH;
            };
        }
    }
}
