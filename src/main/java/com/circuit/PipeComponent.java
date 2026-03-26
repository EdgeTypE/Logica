package com.circuit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/**
 * Component for pipe blocks that handles item transport in all 6 directions.
 * Each pipe has a small internal buffer (1 slot) and can connect to any block
 * with an InventoryComponent.
 */
public class PipeComponent implements Component<EntityStore> {

    // Internal buffer for the pipe (1 slot)
    private ItemContainer buffer;

    // Connection states for all 6 directions
    private boolean connectNorth = false;
    private boolean connectSouth = false;
    private boolean connectEast = false;
    private boolean connectWest = false;
    private boolean connectUp = false;
    private boolean connectDown = false;

    // Transfer cooldown to prevent rapid item movement
    private float transferCooldown = 0.5f;
    private static final float TRANSFER_DELAY = 0.25f; // 1 tick at 20 TPS (faster transfer)

    // Track the last direction we pulled from to prevent immediate backflow
    private PipeComponent.Direction lastPullDirection = null;

    // Output direction - the direction this pipe pushes items to
    private PipeComponent.Direction outputDirection = Direction.NORTH; // Default output direction

    public PipeComponent() {
        try {
            this.buffer = new SimpleItemContainer((short) 1);
        } catch (Exception e) {
            // Fallback - this shouldn't happen but just in case
            this.buffer = null;
        }
    }

    public PipeComponent(PipeComponent other) {
        try {
            this.buffer = new SimpleItemContainer((short) 1);
        } catch (Exception e) {
            this.buffer = null;
        }

        // Copy buffer contents if needed
        if (other.buffer != null && !other.buffer.isEmpty()) {
            try {
                this.buffer.addItemStack(other.buffer.getItemStack((short) 0));
            } catch (Exception e) {
                // Ignore copy errors
            }
        }

        this.connectNorth = other.connectNorth;
        this.connectSouth = other.connectSouth;
        this.connectEast = other.connectEast;
        this.connectWest = other.connectWest;
        this.connectUp = other.connectUp;
        this.connectDown = other.connectDown;
        this.transferCooldown = other.transferCooldown;
        this.lastPullDirection = other.lastPullDirection;
        this.outputDirection = other.outputDirection;
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return new PipeComponent(this);
    }

    // Buffer access
    public ItemContainer getBuffer() {
        return buffer;
    }

    public boolean hasItem() {
        try {
            if (buffer == null)
                return false;
            ItemStack item = buffer.getItemStack((short) 0);
            return item != null && !item.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // Connection states
    public boolean isConnectedNorth() {
        return connectNorth;
    }

    public boolean isConnectedSouth() {
        return connectSouth;
    }

    public boolean isConnectedEast() {
        return connectEast;
    }

    public boolean isConnectedWest() {
        return connectWest;
    }

    public boolean isConnectedUp() {
        return connectUp;
    }

    public boolean isConnectedDown() {
        return connectDown;
    }

    public void setConnectedNorth(boolean connected) {
        this.connectNorth = connected;
    }

    public void setConnectedSouth(boolean connected) {
        this.connectSouth = connected;
    }

    public void setConnectedEast(boolean connected) {
        this.connectEast = connected;
    }

    public void setConnectedWest(boolean connected) {
        this.connectWest = connected;
    }

    public void setConnectedUp(boolean connected) {
        this.connectUp = connected;
    }

    public void setConnectedDown(boolean connected) {
        this.connectDown = connected;
    }

    // Cooldown management
    public float getTransferCooldown() {
        return transferCooldown;
    }

    public void setTransferCooldown(float cooldown) {
        this.transferCooldown = cooldown;
    }

    public void decrementCooldown(float deltaTime) {
        this.transferCooldown = Math.max(0f, this.transferCooldown - deltaTime);
    }

    public boolean isOnCooldown() {
        return transferCooldown > 0f;
    }

    public void startCooldown() {
        this.transferCooldown = TRANSFER_DELAY;
    }

    /**
     * Get the number of connections this pipe has
     */
    public int getConnectionCount() {
        int count = 0;
        if (connectNorth)
            count++;
        if (connectSouth)
            count++;
        if (connectEast)
            count++;
        if (connectWest)
            count++;
        if (connectUp)
            count++;
        if (connectDown)
            count++;
        return count;
    }

    /**
     * Check if this pipe is connected in any direction
     */
    public boolean hasAnyConnection() {
        return getConnectionCount() > 0;
    }

    // Last pull direction tracking
    public PipeComponent.Direction getLastPullDirection() {
        return lastPullDirection;
    }

    public void setLastPullDirection(PipeComponent.Direction direction) {
        this.lastPullDirection = direction;
    }

    // Output direction management
    public PipeComponent.Direction getOutputDirection() {
        return outputDirection;
    }

    public void setOutputDirection(PipeComponent.Direction direction) {
        this.outputDirection = direction;
    }

    /**
     * Check if this direction is the output direction
     */
    public boolean isOutputDirection(PipeComponent.Direction direction) {
        return outputDirection != null && outputDirection.equals(direction);
    }

    /**
     * Check if this direction is an input direction (any direction except output)
     */
    public boolean isInputDirection(PipeComponent.Direction direction) {
        return !isOutputDirection(direction);
    }

    /**
     * Direction enum for pipe connections
     */
    public enum Direction {
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        EAST(1, 0, 0),
        WEST(-1, 0, 0),
        UP(0, 1, 0),
        DOWN(0, -1, 0);

        public final int dx, dy, dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
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
    }
}