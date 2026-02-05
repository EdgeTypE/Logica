package com.circuit;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.Objects;

/**
 * Wrapper class for Vector3i that provides proper equals() and hashCode() implementation
 * for use as HashMap keys. This fixes the issue where Hytale's Vector3i implementation
 * has object identity/caching problems that cause HashMap lookups to fail for certain
 * coordinate values.
 */
public class CircuitPos {
    private final int x;
    private final int y;
    private final int z;

    public CircuitPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public CircuitPos(Vector3i vector) {
        this.x = vector.getX();
        this.y = vector.getY();
        this.z = vector.getZ();
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

    public Vector3i toVector3i() {
        return new Vector3i(x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CircuitPos that = (CircuitPos) obj;
        return x == that.x && y == that.y && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "CircuitPos{x=" + x + ", y=" + y + ", z=" + z + "}";
    }

    /**
     * Convenience method to create CircuitPos from Vector3i
     */
    public static CircuitPos from(Vector3i vector) {
        return new CircuitPos(vector);
    }

    /**
     * Convenience method to create CircuitPos from coordinates
     */
    public static CircuitPos of(int x, int y, int z) {
        return new CircuitPos(x, y, z);
    }
}