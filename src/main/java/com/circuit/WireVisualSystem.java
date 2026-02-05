package com.circuit;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.logging.Level;

public class WireVisualSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[WireVisual] ";

    public static void updateWireVisuals(Vector3i pos, World world, boolean isPowered, CircuitPlugin plugin) {
        if (world == null)
            return;

        try {
            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;

            // Collect neighbors (6-way)
            // Order must match generate_wire_assets.py: North, South, East, West, Up, Down
            boolean north = isWire(chunkAccessor, pos.getX(), pos.getY(), pos.getZ() - 1, plugin);
            boolean south = isWire(chunkAccessor, pos.getX(), pos.getY(), pos.getZ() + 1, plugin);
            boolean east = isWire(chunkAccessor, pos.getX() + 1, pos.getY(), pos.getZ(), plugin);
            boolean west = isWire(chunkAccessor, pos.getX() - 1, pos.getY(), pos.getZ(), plugin);
            boolean up = isWire(chunkAccessor, pos.getX(), pos.getY() + 1, pos.getZ(), plugin);
            boolean down = isWire(chunkAccessor, pos.getX(), pos.getY() - 1, pos.getZ(), plugin);

            String connectionStr = (north ? "1" : "0") +
                    (south ? "1" : "0") +
                    (east ? "1" : "0") +
                    (west ? "1" : "0") +
                    (up ? "1" : "0") +
                    (down ? "1" : "0");

            String statePrefix = isPowered ? "On_" : "Off_";
            String fullState = statePrefix + connectionStr;
            BlockType currentType = null;

            // Prioritize Plugin Registry for Wire Type to prevent race conditions
            if (plugin.isWirePosition(pos)) {
                try {
                    String storedType = plugin.getWireType(pos);
                    // Ensure we don't null out if something went wrong with storage
                    if (storedType != null) {
                        currentType = BlockType.getAssetMap().getAsset(storedType);
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Failed to load stored wire asset: " + e.getMessage());
                }
            }

            // Fallback to world state if registry lookup failed or returned nothing
            if (currentType == null) {
                currentType = getCurrentBlockType(chunkAccessor, pos);
            }

            if (currentType != null && currentType.getId() != null && (currentType.getId().contains("Circuit_Wire")
                    || currentType.getId().contains("Circuit_Golden_Wire"))) {

                // Special handling for Circuit_Wire_Block (Solid Block) - Simple On/Off state
                if (currentType.getId().contains("Circuit_Wire_Block")) {
                    String simpleState = isPowered ? "On" : "Off";
                    chunkAccessor.setBlockInteractionState(pos, currentType, simpleState);
                    // LOGGER.atInfo().log(PREFIX + "Set wire block at " + pos + " to " +
                    // simpleState);
                } else {
                    // Standard Wire - Directional State
                    chunkAccessor.setBlockInteractionState(pos, currentType, fullState);
                    // LOGGER.atInfo().log(PREFIX + "Set wire at " + pos + " to " + fullState);
                }
            }

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update wire visuals at " + pos + ": " + e.getMessage());
        }
    }

    private static boolean isWire(IChunkAccessorSync chunkAccessor, int x, int y, int z, CircuitPlugin plugin) {
        Vector3i pos = new Vector3i(x, y, z);
        try {
            // 1. Check Plugin Registries (Authoritative for existence)
            if (plugin != null) {
                if (plugin.getWirePositions().contains(pos)) {
                    // LOGGER.atInfo().log(PREFIX + "[isWire] Found in registry at " + pos);
                    return true;
                }
                if (plugin.getLeverState(pos) || plugin.getLeverStates().containsKey(pos))
                    return true;
                if (plugin.getButtonSystem() != null && plugin.getButtonSystem().isButtonAt(pos))
                    return true;
                if (plugin.getLampSystem() != null && plugin.getLampSystem().isLampAt(pos))
                    return true;
                if (plugin.getObserverSystem() != null && plugin.getObserverSystem().isObserver(pos))
                    return true;
            }

            // 2. Check World State (Visual/ID check)
            BlockType type = null;

            // Try standard getState first
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunkAccessor
                    .getState(x, y, z, true);

            if (blockState != null) {
                type = blockState.getBlockType();
            } else {
                // Fallback Reflection (copied from EnergyPropagationSystem logic)
                try {
                    java.lang.reflect.Method getBlockTypeParams = chunkAccessor.getClass().getMethod("getBlockType",
                            int.class, int.class, int.class);
                    Object result = getBlockTypeParams.invoke(chunkAccessor, x, y, z);
                    if (result instanceof BlockType) {
                        type = (BlockType) result;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            if (type != null && type.getId() != null) {
                String id = type.getId();
                // Connect to all circuit blocks
                boolean match = id.contains("Circuit_Wire") ||
                        id.contains("Circuit_Golden_Wire") ||
                        id.contains("Circuit_Lever") ||
                        id.contains("Circuit_Button") ||
                        id.contains("Circuit_Lamp") ||
                        id.contains("Circuit_Pusher_Piston") ||
                        id.contains("Circuit_Sticky_Piston") ||
                        id.contains("Circuit_Observer") ||
                        id.contains("Circuit_Repeater") ||
                        id.contains("Circuit_Pressure_Plate") ||
                        id.contains("Circuit_Activator") ||
                        id.contains("Circuit_Gate_AND") ||
                        id.contains("Circuit_Gate_Buffer") ||
                        id.contains("Circuit_Gate_NAND") ||
                        id.contains("Circuit_Gate_NOR") ||
                        id.contains("Circuit_Gate_NOT") ||
                        id.contains("Circuit_Gate_OR") ||
                        id.contains("Circuit_Gate_XNOR") ||
                        id.contains("Circuit_Gate_XOR") ||
                        id.contains("Circuit_Hopper") ||
                        id.contains("Circuit_Light_Sensor") ||
                        id.contains("Circuit_Pipe") ||
                        id.contains("Circuit_Vacuum_Pipe");

                if (match) {
                    // LOGGER.atInfo().log(PREFIX + "[isWire] Found match via ID at " + pos + ": " +
                    // id);
                    return true;
                } else {
                    // LOGGER.atInfo().log(PREFIX + "[isWire] Block at " + pos + " is " + id + " (No
                    // match)");
                }
            } else {
                // LOGGER.atInfo().log(PREFIX + "[isWire] Block at " + pos + " is null/air");
            }

        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "[isWire] Exception checking " + pos + ": " + e.getMessage());
        }
        return false;
    }

    private static BlockType getCurrentBlockType(IChunkAccessorSync chunkAccessor, Vector3i pos) {
        // Simplified fetch
        try {
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunkAccessor
                    .getState(pos.getX(), pos.getY(), pos.getZ(), true);
            if (blockState != null)
                return blockState.getBlockType();
            else {
                java.lang.reflect.Method getBlockTypeParams = chunkAccessor.getClass().getMethod("getBlockType",
                        int.class, int.class, int.class);
                Object result = getBlockTypeParams.invoke(chunkAccessor, pos.getX(), pos.getY(), pos.getZ());
                return (BlockType) result;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
