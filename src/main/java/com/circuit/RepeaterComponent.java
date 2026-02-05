package com.circuit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Component for Circuit Repeater.
 * Stores configuration like delay and locked state.
 */
public class RepeaterComponent implements Component<EntityStore> {

    // Delay in generic "steps" (1-4).
    // Actual tick delay is handled by the System (usually step * 2).
    private int delay = 1;

    // If locked, the repeater maintains its current output regardless of input.
    // (Locked by another repeater/comparator from the side)
    private boolean locked = false;

    public RepeaterComponent() {
    }

    public RepeaterComponent(int delay) {
        this.delay = delay;
    }

    public RepeaterComponent(RepeaterComponent other) {
        this.delay = other.delay;
        this.locked = other.locked;
    }

    @Override
    public Component<EntityStore> clone() {
        return new RepeaterComponent(this);
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        // Clamp between 1 and 4
        this.delay = Math.max(1, Math.min(4, delay));
    }

    public void cycleDelay() {
        delay++;
        if (delay > 4) {
            delay = 1;
        }
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }
}
