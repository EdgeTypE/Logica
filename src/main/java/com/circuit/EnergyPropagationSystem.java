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
 * Energy propagation system using Cellular Automata logic.
 * Each wire block calculates its state based on neighbors.
 * Updates propagate only when state changes.
 */
public class EnergyPropagationSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    private static final int MAX_PROPAGATION_DISTANCE = 15;

    private static final int[][] NEIGHBOR_OFFSETS = {
            { 1, 0, 0 }, { -1, 0, 0 },
            { 0, 1, 0 }, { 0, -1, 0 },
            { 0, 0, 1 }, { 0, 0, -1 }
    };

    private final CircuitPlugin plugin;
    private final ComponentType<EntityStore, CircuitComponent> circuitComponentType;

    // Explicit energy levels map to handle signal strength
    private final Map<CircuitPos, Integer> energyLevels = new HashMap<>();

    public EnergyPropagationSystem(CircuitPlugin plugin,
            ComponentType<EntityStore, CircuitComponent> circuitComponentType) {
        this.plugin = plugin;
        this.circuitComponentType = circuitComponentType;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Event based update - no tick polling
    }

    /**
     * Entry point for network updates.
     * Called when a block is placed, broken, or a lever is toggled.
     */
    public void updateNetwork(Vector3i startPos) {
        // LOGGER.atInfo().log(PREFIX + "[Propagation] Network update triggered at " +
        // startPos);

        // Force visual update on startPos and neighbors to ensure connections are
        // rendered
        updateVisualsAndNeighbors(startPos);

        // Collect all positions that need to be updated
        Set<Vector3i> toProcess = new HashSet<>();
        collectNetwork(startPos, toProcess, new HashSet<>(), 64); // Max 64 blocks deep

        // Special handling for Light Sensors: also include adjacent non-wire blocks
        // (lamps, pistons, etc.)
        LightSensorSystem lightSensorSystem = plugin.getLightSensorSystem();
        if (lightSensorSystem != null && lightSensorSystem.isSensorAt(startPos)) {
            // Add adjacent blocks to processing list so they get updated too
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i adjacentPos = new Vector3i(
                        startPos.getX() + offset[0],
                        startPos.getY() + offset[1],
                        startPos.getZ() + offset[2]);

                BlockResult adjacentInfo = getBlockInfo(adjacentPos);
                if (adjacentInfo.type == BlockTypeEnum.LAMP ||
                        adjacentInfo.type == BlockTypeEnum.GATE ||
                        adjacentInfo.type == BlockTypeEnum.REPEATER) {
                    toProcess.add(adjacentPos);
                }
            }
        }

        // LOGGER.atInfo().log(PREFIX + "[Propagation] Network size: " +
        // toProcess.size());

        // Iterate until stable (no changes in any pass)
        boolean anyChange = true;
        int iterations = 0;
        int maxIterations = 20; // Prevent infinite loops

        while (anyChange && iterations < maxIterations) {
            anyChange = false;
            iterations++;

            for (Vector3i pos : toProcess) {
                boolean changed = recalculateBlock(pos);
                if (changed) {
                    anyChange = true;
                }
            }
        }

        // LOGGER.atInfo().log(PREFIX + "[Propagation] Stabilized after " + iterations +
        // " iterations");

        // Update doors/gates around all processed positions
        updateDoorsInNetwork(toProcess);
    }

    /**
     * Recursively collects all connected circuit blocks starting from a position.
     */
    private void collectNetwork(Vector3i pos, Set<Vector3i> collected, Set<Vector3i> visited, int maxDepth) {
        if (maxDepth <= 0 || visited.contains(pos))
            return;
        visited.add(pos);

        BlockResult blockInfo = getBlockInfo(pos);

        if (blockInfo.type == BlockTypeEnum.WIRE || blockInfo.type == BlockTypeEnum.GOLD_WIRE
                || blockInfo.type == BlockTypeEnum.LEVER ||
                blockInfo.type == BlockTypeEnum.BUTTON || blockInfo.type == BlockTypeEnum.LAMP
                || blockInfo.type == BlockTypeEnum.FAN ||
                blockInfo.type == BlockTypeEnum.REPEATER || blockInfo.type == BlockTypeEnum.GATE ||
                blockInfo.type == BlockTypeEnum.LIGHT_SENSOR ||
                blockInfo.type == BlockTypeEnum.POWERED_RAIL || blockInfo.type == BlockTypeEnum.SWITCH_RAIL ||
                blockInfo.type == BlockTypeEnum.DETECTOR_RAIL) {
            collected.add(pos);

            // Recursively collect neighbors (but lamps, fans, repeaters, gates, and consumer rails don't
            // propagate further in this immediate pass)
            // Gates are like repeaters - they are boundaries that need to check their
            // inputs but don't propagate the network collection further
            // Light sensors ARE power sources, so they NEED to propagate to neighbors
            // Detector rails ARE power sources, so they propagate too
            if (blockInfo.type != BlockTypeEnum.LAMP && blockInfo.type != BlockTypeEnum.FAN
                    && blockInfo.type != BlockTypeEnum.REPEATER
                    && blockInfo.type != BlockTypeEnum.GATE
                    && blockInfo.type != BlockTypeEnum.POWERED_RAIL
                    && blockInfo.type != BlockTypeEnum.SWITCH_RAIL) {
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                            pos.getZ() + offset[2]);
                    collectNetwork(neighbor, collected, visited, maxDepth - 1);
                }
            }
        }
    }

    /**
     * Recalculates the state of a single block based on its neighbors.
     * 
     * @return true if the state changed.
     */
    private boolean recalculateBlock(Vector3i pos) {
        // 1. Identify what is at 'pos' (Lever, Button, Wire, or Air)
        BlockResult blockInfo = getBlockInfo(pos);

        // LOGGER.atInfo().log(PREFIX + "[Debug] Recalc at " + pos + " Type=" +
        // blockInfo.type);

        if (blockInfo.type == BlockTypeEnum.AIR || blockInfo.type == BlockTypeEnum.OTHER) {
            // If it was previously tracked as energy, remove it
            CircuitPos circuitPos = CircuitPos.from(pos);
            if (energyLevels.containsKey(circuitPos)) {
                energyLevels.remove(circuitPos);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Block removed at " + pos);
                return true; // State changed (removed)
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.LAMP) {
            // Lamp: Passive consumer - update based on received power
            int receivedPower = 0;

            // Check all neighbors for power
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                        pos.getZ() + offset[2]);

                int neighborPower = getPowerOutput(neighbor, pos);
                if (neighborPower > receivedPower) {
                    receivedPower = neighborPower;
                }

                // Debug: Log neighbor power for Light Sensors
                LightSensorSystem lightSensorSystem = plugin.getLightSensorSystem();
                if (lightSensorSystem != null && lightSensorSystem.isSensorAt(neighbor)) {
                    // LOGGER.atInfo()
                    // .log(PREFIX + "[Debug] Lamp at " + pos + " checking Light Sensor neighbor at
                    // " + neighbor +
                    // " -> power=" + neighborPower + " (sensor powered="
                    // + lightSensorSystem.isSensorPowered(neighbor) + ")");
                }

                // Also check for strong power through blocks
                int strongPower = getStrongPowerFromBlock(neighbor);
                if (strongPower > receivedPower) {
                    receivedPower = strongPower;
                }
            }

            // Update lamp system
            LampSystem lampSystem = plugin.getLampSystem();
            if (lampSystem != null) {
                lampSystem.updateLampState(pos, receivedPower);
            }

            // Store power level for consistency
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (receivedPower != oldPower) {
                energyLevels.put(circuitPos, receivedPower);
                // LOGGER.atInfo()
                // .log(PREFIX + "[Debug] Lamp power update at " + pos + ": " + oldPower + " ->
                // " + receivedPower);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.FAN) {
            // Fan: Passive consumer - update based on received power
            int receivedPower = 0;

            // Check all neighbors for power
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                        pos.getZ() + offset[2]);

                int neighborPower = getPowerOutput(neighbor, pos);
                if (neighborPower > receivedPower) {
                    receivedPower = neighborPower;
                }

                // Also check for strong power through blocks
                int strongPower = getStrongPowerFromBlock(neighbor);
                if (strongPower > receivedPower) {
                    receivedPower = strongPower;
                }
            }

            // Update fan system
            FanSystem fanSystem = plugin.getFanSystem();
            if (fanSystem != null) {
                fanSystem.setFanPowered(pos, receivedPower);
            }

            // Store power level for consistency
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (receivedPower != oldPower) {
                energyLevels.put(circuitPos, receivedPower);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.REPEATER) {
            // Repeater: Check input state
            if (plugin.getRepeaterSystem() != null) {
                plugin.getRepeaterSystem().checkInput(pos);
            }
            // We return false because the actual change (if any) happens later via
            // scheduleUpdate
            // and will trigger a new propagation from the output side.
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.GATE) {
            // Gate: Update gate state based on inputs
            GateSystem gateSystem = plugin.getGateSystem();
            if (gateSystem != null) {
                // Get old output power for comparison
                int oldOutput = gateSystem.getOutputPower(pos);

                // Update gate state
                gateSystem.updateGateState(pos);

                // Get new output power
                int newOutput = gateSystem.getOutputPower(pos);

                // Store in energy levels for consistency with other components
                CircuitPos circuitPos = CircuitPos.from(pos);
                energyLevels.put(circuitPos, newOutput);

                // Return true if output changed to trigger neighbor updates
                if (oldOutput != newOutput) {
                    // LOGGER.atInfo().log(PREFIX + "[Debug] Gate output changed at " + pos + ": " +
                    // oldOutput + " -> " + newOutput);
                    return true;
                }
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.LEVER) {
            int power = blockInfo.isPowered ? 15 : 0;
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (power != oldPower) {
                energyLevels.put(circuitPos, power);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Lever power update at " + pos + ": " +
                // oldPower + " -> " + power);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.BUTTON) {
            // Button: Get power from ButtonSystem
            ButtonSystem buttonSystem = plugin.getButtonSystem();
            int power = (buttonSystem != null && buttonSystem.isButtonPressed(pos)) ? 15 : 0;
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (power != oldPower) {
                energyLevels.put(circuitPos, power);
                // LOGGER.atInfo()
                // .log(PREFIX + "[Debug] Button power update at " + pos + ": " + oldPower + "
                // -> " + power);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.LIGHT_SENSOR) {
            // Light Sensor: Get power from LightSensorSystem
            LightSensorSystem lightSensorSystem = plugin.getLightSensorSystem();
            int power = (lightSensorSystem != null && lightSensorSystem.isSensorPowered(pos)) ? 15 : 0;
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (power != oldPower) {
                energyLevels.put(circuitPos, power);
                // LOGGER.atInfo()
                // .log(PREFIX + "[Debug] Light Sensor power update at " + pos + ": " + oldPower
                // + " -> " + power);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.POWERED_RAIL) {
            // Powered Rail: Passive consumer - update based on received power
            int receivedPower = 0;
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                        pos.getZ() + offset[2]);
                int neighborPower = getPowerOutput(neighbor, pos);
                if (neighborPower > receivedPower) {
                    receivedPower = neighborPower;
                }
                int strongPower = getStrongPowerFromBlock(neighbor);
                if (strongPower > receivedPower) {
                    receivedPower = strongPower;
                }
            }
            // Update powered rail system
            PoweredRailSystem poweredRailSystem = plugin.getPoweredRailSystem();
            if (poweredRailSystem != null) {
                poweredRailSystem.setPoweredRailState(pos, receivedPower > 0);
            }
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (receivedPower != oldPower) {
                energyLevels.put(circuitPos, receivedPower);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.SWITCH_RAIL) {
            // Switch Rail: Passive consumer - update direction based on received power
            int receivedPower = 0;
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                        pos.getZ() + offset[2]);
                int neighborPower = getPowerOutput(neighbor, pos);
                if (neighborPower > receivedPower) {
                    receivedPower = neighborPower;
                }
                int strongPower = getStrongPowerFromBlock(neighbor);
                if (strongPower > receivedPower) {
                    receivedPower = strongPower;
                }
            }
            // Update switch rail system
            SwitchRailSystem switchRailSystem = plugin.getSwitchRailSystem();
            if (switchRailSystem != null) {
                switchRailSystem.updateSwitchState(pos, receivedPower);
            }
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (receivedPower != oldPower) {
                energyLevels.put(circuitPos, receivedPower);
                return true;
            }
            return false;
        }

        if (blockInfo.type == BlockTypeEnum.DETECTOR_RAIL) {
            // Detector Rail: Power source - get power from DetectorRailSystem
            DetectorRailSystem detectorRailSystem = plugin.getDetectorRailSystem();
            int power = (detectorRailSystem != null && detectorRailSystem.isDetectorRailActive(pos)) ? 15 : 0;
            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, -1);
            if (power != oldPower) {
                energyLevels.put(circuitPos, power);
                return true;
            }
            return false;
        }

        // Split logic for Gold Wire vs Regular Wire
        if (blockInfo.type == BlockTypeEnum.GOLD_WIRE) {
            return recalculateGoldWireNetwork(pos);
        }

        if (blockInfo.type == BlockTypeEnum.WIRE) {
            // Standard Wire Logic (Decays)
            int maxNeighborPower = 0;
            boolean receivedStrongPower = false;

            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                        pos.getZ() + offset[2]);

                // Check for direct power from adjacent circuit blocks
                int neighborPower = getPowerOutput(neighbor, pos);
                if (neighborPower > maxNeighborPower) {
                    maxNeighborPower = neighborPower;
                }

                // Check for strong power (power through solid blocks)
                int strongPower = getStrongPowerFromBlock(neighbor);
                if (strongPower > 0) {
                    receivedStrongPower = true;
                    if (strongPower > maxNeighborPower) {
                        maxNeighborPower = strongPower;
                    }
                }
            }

            int decay = 1;
            int newPower = receivedStrongPower ? maxNeighborPower : Math.max(0, maxNeighborPower - decay);

            CircuitPos circuitPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(circuitPos, 0);

            if (newPower != oldPower) {
                energyLevels.put(circuitPos, newPower);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Wire update at " + pos + ": Power " +
                // oldPower + " -> " + newPower + " (MaxNeighbor=" + maxNeighborPower + ")");

                // Visual Update
                boolean isPowered = newPower > 0;
                setWireVisual(pos, blockInfo.blockType, isPowered);

                // Notify neighbors
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    Vector3i n = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1], pos.getZ() + offset[2]);
                    if (plugin.getRepeaterSystem() != null) {
                        plugin.getRepeaterSystem().checkInput(n);
                    }
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Specialized logic for Gold Wires to prevent infinite power loops.
     * Treats connected Gold Wires as a single network node that requires external
     * power.
     */
    private boolean recalculateGoldWireNetwork(Vector3i startPos) {
        Set<Vector3i> network = new HashSet<>();
        Queue<Vector3i> queue = new LinkedList<>();

        network.add(startPos);
        queue.add(startPos);

        int maxExternalPower = 0;

        // LOGGER.atInfo().log(PREFIX + "[GoldWire] Starting network calculation for " +
        // startPos);

        // 1. Identify Network and Max External Power
        while (!queue.isEmpty()) {
            Vector3i current = queue.poll();

            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(current.getX() + offset[0], current.getY() + offset[1],
                        current.getZ() + offset[2]);

                // Avoid checking nodes already in the network to prevent infinite loops
                if (network.contains(neighbor))
                    continue;

                BlockResult neighborInfo = getBlockInfo(neighbor);

                if (neighborInfo.type == BlockTypeEnum.GOLD_WIRE) {
                    network.add(neighbor);
                    queue.add(neighbor);
                    // LOGGER.atInfo().log(PREFIX + "[GoldWire] Added gold wire to network: " +
                    // neighbor);
                } else {
                    // External Neighbor: Calculate power contribution
                    int power = 0;

                    // Direct power (Buttons, Levers, Repeaters, Observers)
                    int directPower = getPowerOutput(neighbor, current);
                    if (directPower > power)
                        power = directPower;

                    // Strong power from solid blocks
                    int strongPower = getStrongPowerFromBlock(neighbor);
                    if (strongPower > power)
                        power = strongPower;

                    // if (power > 0) {
                    // LOGGER.atInfo().log(PREFIX + "[GoldWire] External power source at " +
                    // neighbor +
                    // " type=" + neighborInfo.type + " directPower=" + directPower +
                    // " strongPower=" + strongPower + " totalPower=" + power);
                    // }

                    // Regular wires provide power with NO decay to Gold Wires (Superconductor
                    // behavior inputs)
                    if (power > maxExternalPower) {
                        maxExternalPower = power;
                    }
                }
            }
        }

        // LOGGER.atInfo()
        // .log(PREFIX + "[GoldWire] Network size: " + network.size() + "
        // maxExternalPower: " + maxExternalPower);

        // 2. Update Network
        boolean startPosChanged = false;

        for (Vector3i pos : network) {
            CircuitPos cPos = CircuitPos.from(pos);
            int oldPower = energyLevels.getOrDefault(cPos, 0);

            if (oldPower != maxExternalPower) {
                energyLevels.put(cPos, maxExternalPower);

                if (pos.equals(startPos)) {
                    startPosChanged = true;
                }

                // LOGGER.atInfo().log(
                // PREFIX + "[GoldWire] Network Update at " + pos + ": " + oldPower + " -> " +
                // maxExternalPower);

                // Visuals and Neighbor Updates
                setWireVisual(pos, null, maxExternalPower > 0);

                // Notify neighbors
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    Vector3i n = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1], pos.getZ() + offset[2]);
                    if (plugin.getRepeaterSystem() != null) {
                        plugin.getRepeaterSystem().checkInput(n);
                    }
                }
            }
        }

        return startPosChanged;
    }

    private int getPowerOutput(Vector3i pos, Vector3i targetPos) {
        // Check if this is a gate
        GateSystem gateSystem = plugin.getGateSystem();
        if (gateSystem != null && gateSystem.isGateAt(pos)) {
            return gateSystem.getPowerOutput(pos, targetPos);
        }

        // Check if this is a powered Observer
        ObserverSystem observerSystem = plugin.getObserverSystem();
        if (observerSystem != null && observerSystem.isObserver(pos)) {
            if (observerSystem.isObserverPowered(pos)) {
                // Check direction
                if (observerSystem.getPowerOutput(pos, targetPos) > 0) {
                    return 15;
                }
            }
            return 0;
        }

        // Check if this is a powered Repeater
        RepeaterSystem repeaterSystem = plugin.getRepeaterSystem();
        if (repeaterSystem != null && repeaterSystem.isRepeaterAt(pos)) {
            return repeaterSystem.getStrongPowerOutput(pos, targetPos);
        }

        // Check if this is a pressed Button - DIRECT POWER OUTPUT
        ButtonSystem buttonSystem = plugin.getButtonSystem();
        if (buttonSystem != null && buttonSystem.isButtonAt(pos)) {
            // Check both general button power and directional power
            int generalPower = buttonSystem.getButtonPower(pos);
            int directionalPower = buttonSystem.getDirectPowerOutput(pos, targetPos);
            int maxPower = Math.max(generalPower, directionalPower);

            // if (maxPower > 0) {
            // LOGGER.atInfo().log(PREFIX + "[PowerOutput] Button at " + pos + " -> target "
            // + targetPos +
            // " generalPower=" + generalPower + " directionalPower=" + directionalPower +
            // " returning=" + maxPower);
            // }

            return maxPower;
        }

        // Check if this is a powered Light Sensor
        LightSensorSystem lightSensorSystem = plugin.getLightSensorSystem();
        if (lightSensorSystem != null && lightSensorSystem.isSensorAt(pos)) {
            return lightSensorSystem.getPowerOutput(pos);
        }

        // Check if this is a powered Detector Rail - power source
        DetectorRailSystem detectorRailSystem = plugin.getDetectorRailSystem();
        if (detectorRailSystem != null && detectorRailSystem.isDetectorRailAt(pos)) {
            return detectorRailSystem.getPowerOutput(pos);
        }

        // Check if this is a Fan - PASSIVE CONSUMER
        FanSystem fanSystem = plugin.getFanSystem();
        if (fanSystem != null && fanSystem.hasFanAt(pos)) {
            return 0;
        }

        // Check if this is a Powered Rail or Switch Rail - PASSIVE CONSUMERS
        PoweredRailSystem poweredRailSystem = plugin.getPoweredRailSystem();
        if (poweredRailSystem != null && poweredRailSystem.isPoweredRailAt(pos)) {
            return 0;
        }
        SwitchRailSystem switchRailSystem = plugin.getSwitchRailSystem();
        if (switchRailSystem != null && switchRailSystem.isSwitchRailAt(pos)) {
            return 0;
        }

        // Lever or Wire power
        return energyLevels.getOrDefault(CircuitPos.from(pos), 0);
    }

    /**
     * Get strong power from a solid block.
     * Strong power is when a power source (like a button) is attached to a block,
     * causing that block to power adjacent wires as if it were a power source
     * itself.
     * 
     * @param blockPos The position of the potential power-carrying block
     * @return The power level (0-15) if the block is receiving strong power, 0
     *         otherwise
     */
    private int getStrongPowerFromBlock(Vector3i blockPos) {
        // Optimization: Quick check if block is Air or Wire, in which case it CANNOT
        // transmit strong power
        // We use getBlockInfo for this.
        BlockResult blockCheck = getBlockInfo(blockPos);
        if (blockCheck.type == BlockTypeEnum.AIR || blockCheck.type == BlockTypeEnum.WIRE
                || blockCheck.type == BlockTypeEnum.GOLD_WIRE
                || blockCheck.type == BlockTypeEnum.LEVER || blockCheck.type == BlockTypeEnum.BUTTON
                || blockCheck.type == BlockTypeEnum.LIGHT_SENSOR
                || blockCheck.type == BlockTypeEnum.POWERED_RAIL || blockCheck.type == BlockTypeEnum.SWITCH_RAIL
                || blockCheck.type == BlockTypeEnum.DETECTOR_RAIL) {
            return 0; // These blocks never transmit strong power from others
        }

        // CRITICAL: Only solid blocks (OTHER type) can transmit strong power
        // If it's not a solid block, return 0 immediately
        if (blockCheck.type != BlockTypeEnum.OTHER) {
            return 0;
        }

        // Check if a button is attached to this block and is pressed
        ButtonSystem buttonSystem = plugin.getButtonSystem();
        if (buttonSystem != null) {
            int strongPower = buttonSystem.getStrongPowerToBlock(blockPos);
            // LOGGER.atInfo().log(PREFIX + "[StrongPowerDebug] Checked block " + blockPos +
            // " Type=" + blockCheck.type
            // + " -> strongPower=" + strongPower);
            if (strongPower > 0) {
                return strongPower;
            }
        } else {
            // LOGGER.atWarning().log(PREFIX + "[StrongPowerDebug] ButtonSystem is NULL!");
        }

        // Check for strong power from Repeaters
        RepeaterSystem repeaterSystem = plugin.getRepeaterSystem();
        if (repeaterSystem != null) {
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i neighbor = new Vector3i(blockPos.getX() + offset[0], blockPos.getY() + offset[1],
                        blockPos.getZ() + offset[2]);
                if (repeaterSystem.isRepeaterAt(neighbor)) {
                    int power = repeaterSystem.getStrongPowerOutput(neighbor, blockPos);
                    if (power > 0)
                        return power;
                }
            }
        }

        // TODO: Add support for other strong power sources:
        // - Redstone torches (on blocks)
        // - etc.

        return 0;
    }

    private void setWireVisual(Vector3i pos, BlockType currentType, boolean powered) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return;

            WireVisualSystem.updateWireVisuals(pos, world, powered, plugin);

            // Legacy/Other checks if needed, but WireVisualSystem handles the block state.

            // LOGGER.atInfo().log(PREFIX + "[VisualUpdate] Wire at " + pos + " -> " +
            // targetState);

            // CRITICAL FIX: Update the plugin's wire state map so PistonSystem can detect
            // it
            plugin.setWireState(pos, powered);
            // LOGGER.atInfo().log(PREFIX + "[StateUpdate] Wire state updated in plugin: " +
            // pos + " -> " + powered);
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Visual update failed: " + e.getMessage());
        }
    }

    // Helper for Block Identification
    private enum BlockTypeEnum {
        WIRE, GOLD_WIRE, LEVER, BUTTON, LAMP, FAN, REPEATER, GATE, LIGHT_SENSOR,
        POWERED_RAIL, SWITCH_RAIL, DETECTOR_RAIL,
        AIR, OTHER
    }

    private static class BlockResult {
        BlockTypeEnum type;
        boolean isPowered;
        BlockType blockType; // For visual updates

        BlockResult(BlockTypeEnum type, boolean isPowered, BlockType blockType) {
            this.type = type;
            this.isPowered = isPowered;
            this.blockType = blockType;
        }
    }

    private BlockResult getBlockInfo(Vector3i pos) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return new BlockResult(BlockTypeEnum.AIR, false, null);

            com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync chunkAccessor = (com.hypixel.hytale.server.core.universe.world.accessor.IChunkAccessorSync) world;

            // Try standard getState first
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunkAccessor
                    .getState(pos.getX(), pos.getY(), pos.getZ(), true);

            BlockType type = null;
            boolean usedReflection = false;

            if (blockState != null) {
                type = blockState.getBlockType();
            } else {
                // Fallback Reflection
                try {
                    java.lang.reflect.Method getBlockTypeParams = chunkAccessor.getClass().getMethod("getBlockType",
                            int.class, int.class, int.class);
                    Object result = getBlockTypeParams.invoke(chunkAccessor, pos.getX(), pos.getY(), pos.getZ());
                    if (result != null && result instanceof BlockType) {
                        type = (BlockType) result;
                        usedReflection = true;
                    }
                } catch (Exception e) {
                    // Reflection detect failed
                }
            }

            if (type == null) {
                // Debug: Log if pure null
                // LOGGER.atInfo().log(PREFIX + "[Debug] Block at " + pos + " is NULL (Refl=" +
                // usedReflection + ")");
            } else {
                String id = type.getId();
                if (id != null) {
                    // LOGGER.atInfo().log(PREFIX + "[Debug] Block at " + pos + " is " + id + "
                    // (Refl=" + usedReflection + ")");

                    if (id.contains("Circuit_Lever")) {
                        // ALWAYS trust plugin state as authoritative (world state can lag)
                        boolean powered = plugin.getLeverState(pos);
                        // LOGGER.atInfo().log(PREFIX + "[Debug] Lever at " + pos + " via ID, powered="
                        // + powered);
                        return new BlockResult(BlockTypeEnum.LEVER, powered, type);
                    }

                    if (id.contains("Circuit_Activator_Block")) {
                        // Activator is effectively a lever block
                        boolean powered = plugin.getLeverState(pos);
                        return new BlockResult(BlockTypeEnum.LEVER, powered, type);
                    }

                    if (id.contains("Circuit_Button")) {
                        // Get button power state from ButtonSystem
                        ButtonSystem buttonSystem = plugin.getButtonSystem();
                        boolean powered = buttonSystem != null && buttonSystem.isButtonPressed(pos);
                        // LOGGER.atInfo().log(PREFIX + "[Debug] Button at " + pos + " via ID, pressed="
                        // + powered);
                        return new BlockResult(BlockTypeEnum.BUTTON, powered, type);
                    }

                    if (id.contains("Circuit_Pressure_Plate")) {
                        // Get pressure plate power state from ButtonSystem (uses same system)
                        ButtonSystem buttonSystem = plugin.getButtonSystem();
                        boolean powered = buttonSystem != null && buttonSystem.isButtonPressed(pos);
                        // LOGGER.atInfo()
                        // .log(PREFIX + "[Debug] Pressure plate at " + pos + " via ID, pressed=" +
                        // powered);
                        return new BlockResult(BlockTypeEnum.BUTTON, powered, type);
                    }

                    if (id.contains("Circuit_Golden_Wire")) {
                        // CRITICAL: Prevent ghost blocks during break events
                        if (!plugin.isWirePosition(pos)) {
                            // LOGGER.atInfo().log(PREFIX + "[Debug] Golden Wire at " + pos
                            // + " not in wire registry, treating as AIR");
                            return new BlockResult(BlockTypeEnum.AIR, false, null);
                        }
                        boolean powered = id.contains("On");
                        // LOGGER.atInfo().log(PREFIX + "[Debug] Detected Golden Wire at " + pos + "
                        // powered=" + powered);
                        return new BlockResult(BlockTypeEnum.GOLD_WIRE, powered, type);
                    }

                    if (id.contains("Circuit_Wire")) {
                        // CRITICAL: Prevent ghost blocks during break events
                        if (!plugin.isWirePosition(pos)) {
                            return new BlockResult(BlockTypeEnum.AIR, false, null);
                        }
                        boolean powered = id.contains("On");
                        return new BlockResult(BlockTypeEnum.WIRE, powered, type);
                    }

                    if (id.contains("Circuit_Lamp")) {
                        boolean lit = id.contains("On");
                        return new BlockResult(BlockTypeEnum.LAMP, lit, type);
                    }

                    if (id.contains("Circuit_Fan")) {
                        return new BlockResult(BlockTypeEnum.FAN, false, type);
                    }

                    if (id.contains("Circuit_Light_Sensor")) {
                        LightSensorSystem lightSensorSystem = plugin.getLightSensorSystem();
                        boolean powered = lightSensorSystem != null && lightSensorSystem.isSensorPowered(pos);
                        return new BlockResult(BlockTypeEnum.LIGHT_SENSOR, powered, type);
                    }

                    if (id.contains("Circuit_Powered_Rail")) {
                        PoweredRailSystem prs = plugin.getPoweredRailSystem();
                        boolean powered = prs != null && prs.isPoweredRailPowered(pos);
                        return new BlockResult(BlockTypeEnum.POWERED_RAIL, powered, type);
                    }

                    if (id.contains("Circuit_Switch_Rail")) {
                        return new BlockResult(BlockTypeEnum.SWITCH_RAIL, false, type);
                    }

                    if (id.contains("Circuit_Detector_Rail")) {
                        DetectorRailSystem drs = plugin.getDetectorRailSystem();
                        boolean active = drs != null && drs.isDetectorRailActive(pos);
                        return new BlockResult(BlockTypeEnum.DETECTOR_RAIL, active, type);
                    }

                    if (id.contains("Circuit_Repeater")) {
                        // Repeater state is complicated (delay, locked, etc) kept in system
                        return new BlockResult(BlockTypeEnum.REPEATER, false, type);
                    }

                    if (id.contains("Circuit_Gate_")) {
                        // Gate: Check if it's powered based on ID
                        boolean powered = id.contains("_On");
                        return new BlockResult(BlockTypeEnum.GATE, powered, type);
                    }
                }
            }

            // If we are here, World Probe failed to find a Circuit block.
            // CHECK REGISTRIES (Fallbacks for newly placed blocks or sync lag)

            // Check if button is registered in ButtonSystem
            ButtonSystem buttonSystem = plugin.getButtonSystem();
            if (buttonSystem != null && buttonSystem.isButtonAt(pos)) {
                // It IS a button according to our registry
                BlockType buttonType = type;
                if (buttonType == null
                        || (buttonType.getId() != null && !buttonType.getId().contains("Circuit_Button"))) {
                    buttonType = BlockType.getAssetMap().getAsset("Circuit_Button");
                }

                boolean pressed = buttonSystem.isButtonPressed(pos);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified BUTTON at
                // " + pos);
                return new BlockResult(BlockTypeEnum.BUTTON, pressed, buttonType);
            }

            if (plugin.getWirePositions().contains(pos)) {
                // It IS a wire according to our registry
                // We need a BlockType for visual updates. Fetch it.
                BlockType wireType = type; // Use what we found if any
                String storedType = plugin.getWireType(pos);

                if (wireType == null || (wireType.getId() != null && !wireType.getId().contains("Circuit_"))) {
                    try {
                        wireType = BlockType.getAssetMap().getAsset(storedType);
                    } catch (Exception e) {
                        LOGGER.atWarning().log(PREFIX + "Failed to load fallback asset for " + storedType);
                    }
                }

                BlockTypeEnum enumType = BlockTypeEnum.WIRE;
                if (storedType.contains("Circuit_Golden_Wire")) {
                    enumType = BlockTypeEnum.GOLD_WIRE;
                }

                // Default to false or check if we have state
                boolean powered = false; // We can't know power from registry mostly, but re-calc will fix it.
                // Actually, we could check wireStates if we exposed it, but re-calc is safer.

                // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified " +
                // enumType + " at " + pos);
                return new BlockResult(enumType, powered, wireType);
            }

            if (plugin.getLeverState(pos) || plugin.getLeverStates().containsKey(pos)) { // Check key existence
                                                                                         // effectively
                // It IS a lever
                BlockType leverType = type;
                if (leverType == null || (leverType.getId() != null && !leverType.getId().contains("Circuit_Lever"))) {
                    leverType = BlockType.getAssetMap().getAsset("Circuit_Lever");
                }

                boolean powered = plugin.getLeverState(pos);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified LEVER at
                // " + pos);
                return new BlockResult(BlockTypeEnum.LEVER, powered, leverType);
            }

            // Check if it's a lamp in the lamp system
            LampSystem lampSystem = plugin.getLampSystem();
            if (lampSystem != null && lampSystem.isLampAt(pos)) {
                // It IS a lamp according to our registry
                BlockType lampType = type;
                if (lampType == null || (lampType.getId() != null && !lampType.getId().contains("Circuit_Lamp"))) {
                    lampType = BlockType.getAssetMap().getAsset("Circuit_Lamp");
                }

                boolean lit = lampSystem.isLampLit(pos);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified LAMP at "
                // + pos);
                return new BlockResult(BlockTypeEnum.LAMP, lit, lampType);
            }

            // Check if it's a fan
            if (plugin.getFanSystem() != null && plugin.getFanSystem().isFanAt(pos)) {
                BlockType fanType = type;
                if (fanType == null || (fanType.getId() != null && !fanType.getId().contains("Circuit_Fan"))) {
                    fanType = BlockType.getAssetMap().getAsset("Circuit_Fan");
                }
                return new BlockResult(BlockTypeEnum.FAN, false, fanType);
            }

            // Check if it's a light sensor
            LightSensorSystem lightSensorSystem = plugin.getLightSensorSystem();
            if (lightSensorSystem != null && lightSensorSystem.isSensorAt(pos)) {
                BlockType sensorType = type;
                if (sensorType == null
                        || (sensorType.getId() != null && !sensorType.getId().contains("Circuit_Light_Sensor"))) {
                    sensorType = BlockType.getAssetMap().getAsset("Circuit_Light_Sensor");
                }

                boolean powered = lightSensorSystem.isSensorPowered(pos);
                // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified
                // LIGHT_SENSOR at " + pos);
                return new BlockResult(BlockTypeEnum.LIGHT_SENSOR, powered, sensorType);
            }

            // Check if it's a repeater
            if (plugin.getRepeaterSystem() != null && plugin.getRepeaterSystem().isRepeaterAt(pos)) {
                BlockType repeaterType = type;
                if (repeaterType == null
                        || (repeaterType.getId() != null && !repeaterType.getId().contains("Circuit_Repeater"))) {
                    repeaterType = BlockType.getAssetMap().getAsset("Circuit_Repeater");
                }
                // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified REPEATER
                // at " + pos);
                return new BlockResult(BlockTypeEnum.REPEATER, false, repeaterType);
            }

            // Check if it's a gate
            GateSystem gateSystem = plugin.getGateSystem();
            if (gateSystem != null && gateSystem.isGateAt(pos)) {
                GateSystem.GateType gateType = gateSystem.getGateType(pos);
                if (gateType != null) {
                    BlockType gateBlockType = type;
                    if (gateBlockType == null
                            || (gateBlockType.getId() != null && !gateBlockType.getId().contains("Circuit_Gate_"))) {
                        gateBlockType = BlockType.getAssetMap().getAsset("Circuit_Gate_" + gateType.name());
                    }
                    // LOGGER.atInfo().log(PREFIX + "[Debug] Registry Override: Identified " +
                    // gateType + " GATE at " + pos);
                    return new BlockResult(BlockTypeEnum.GATE, false, gateBlockType);
                }
            }

            // Fallback for non-circuit blocks (Solid blocks that can transmit strong power)
            if (type != null) {
                String id = type.getId();
                // Treat anything that isn't explicitly "air" as a potential solid block
                if (id != null && !id.toLowerCase().contains("air")) {
                    return new BlockResult(BlockTypeEnum.OTHER, false, type);
                }
            }

            return new BlockResult(BlockTypeEnum.AIR, false, null);

        } catch (

        Exception e) {
            return new BlockResult(BlockTypeEnum.AIR, false, null);
        }
    }

    // Backward Compatibility / Entry Points
    public void updateBlock(Vector3i startPos) {
        updateNetwork(startPos);
    }

    public void removeEnergy(Vector3i pos) {
        energyLevels.remove(CircuitPos.from(pos));
    }

    /**
     * Special handler for when a block is broken.
     * Collects ALL neighbors into a unified network and stabilizes.
     */
    public void handleBreak(Vector3i brokenPos) {
        // LOGGER.atInfo().log(PREFIX + "[Break] Handling break at " + brokenPos);

        // 1. Remove broken block's energy immediately
        energyLevels.remove(CircuitPos.from(brokenPos));

        // Force visual update on neighbors so they lose connection to broken block
        updateVisualsAndNeighbors(brokenPos);

        // 2. Collect ALL neighbors' networks into one big set
        Set<Vector3i> allAffected = new HashSet<>();
        Set<Vector3i> visited = new HashSet<>();
        visited.add(brokenPos); // Don't re-collect broken position

        for (int[] offset : NEIGHBOR_OFFSETS) {
            Vector3i neighbor = new Vector3i(brokenPos.getX() + offset[0], brokenPos.getY() + offset[1],
                    brokenPos.getZ() + offset[2]);
            collectNetwork(neighbor, allAffected, visited, 64);
        }

        // LOGGER.atInfo().log(PREFIX + "[Break] Affected network size: " +
        // allAffected.size());

        // 3. Run stabilization on the combined network
        boolean anyChange = true;
        int iterations = 0;
        int maxIterations = 20;

        while (anyChange && iterations < maxIterations) {
            anyChange = false;
            iterations++;

            for (Vector3i pos : allAffected) {
                boolean changed = recalculateBlock(pos);
                if (changed) {
                    anyChange = true;
                }
            }
        }

        // LOGGER.atInfo().log(PREFIX + "[Break] Stabilized after " + iterations + "
        // iterations");
    }

    public int getEnergyLevel(Vector3i pos) {
        return energyLevels.getOrDefault(CircuitPos.from(pos), 0);
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return null;
    }

    private void updateVisualsAndNeighbors(Vector3i centerPos) {
        // Update center (if it's a wire)
        if (isWire(centerPos)) {
            boolean p = energyLevels.getOrDefault(CircuitPos.from(centerPos), 0) > 0;
            // Need world reference
            try {
                World world = Universe.get().getDefaultWorld();
                WireVisualSystem.updateWireVisuals(centerPos, world, p, plugin);
            } catch (Exception e) {
            }
        }

        // Update neighbors
        for (int[] offset : NEIGHBOR_OFFSETS) {
            Vector3i n = new Vector3i(centerPos.getX() + offset[0], centerPos.getY() + offset[1],
                    centerPos.getZ() + offset[2]);
            if (isWire(n)) {
                boolean p = energyLevels.getOrDefault(CircuitPos.from(n), 0) > 0;
                try {
                    World world = Universe.get().getDefaultWorld();
                    WireVisualSystem.updateWireVisuals(n, world, p, plugin);
                } catch (Exception e) {
                    LOGGER.atWarning().log(PREFIX + "Failed to update neighbor visual at " + n + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Updates all neighbors of a specific block.
     * Useful when a block (like a Repeater) pushes power to a target block,
     * and we need to ensure that target block's neighbors (wires) react.
     */
    public void updateNeighbors(Vector3i pos) {
        for (int[] offset : NEIGHBOR_OFFSETS) {
            Vector3i neighbor = new Vector3i(pos.getX() + offset[0], pos.getY() + offset[1],
                    pos.getZ() + offset[2]);
            updateNetwork(neighbor);
        }

        // Also check for doors/gates that might need updating
        updateDoorsAroundPosition(pos);
    }

    private boolean isWire(Vector3i pos) {
        // Fast check using our registry or energy map implies it is relevant
        // But for visual update, we should check if it's strictly a Circuit_Wire
        // Rely on getBlockInfo or easier: check if it tracked in energyLevels?
        // But newly placed wire might not be in energyLevels yet if simple place.
        // However, updateNetwork is called after placement.
        BlockResult info = getBlockInfo(pos);
        return info.type == BlockTypeEnum.WIRE || info.type == BlockTypeEnum.GOLD_WIRE;
    }

    /**
     * Updates doors and fence gates around a position when power changes.
     * Checks all adjacent blocks for doors/gates and updates their state.
     * 
     * @param centerPos The center position to check around
     */
    private void updateDoorsAroundPosition(Vector3i centerPos) {
        try {
            DoorSystem doorSystem = plugin.getDoorSystem();
            if (doorSystem == null) {
                return;
            }

            // Check all adjacent positions for doors/gates
            for (int[] offset : NEIGHBOR_OFFSETS) {
                Vector3i doorPos = new Vector3i(
                        centerPos.getX() + offset[0],
                        centerPos.getY() + offset[1],
                        centerPos.getZ() + offset[2]);

                // Check if this position has a door/gate
                if (isDoorOrGate(doorPos)) {
                    // Get the power level at the door position
                    int powerLevel = getEnergyLevel(centerPos);

                    // Update the door state
                    doorSystem.updateDoorState(doorPos, powerLevel);
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update doors around " + centerPos + ": " + e.getMessage());
        }
    }

    /**
     * Updates doors and fence gates around all positions in a network.
     * Called after network stabilization to ensure all doors are updated.
     * 
     * @param networkPositions The set of positions in the network
     */
    private void updateDoorsInNetwork(Set<Vector3i> networkPositions) {
        try {
            DoorSystem doorSystem = plugin.getDoorSystem();
            if (doorSystem == null) {
                return;
            }

            Set<Vector3i> checkedDoors = new HashSet<>();

            for (Vector3i circuitPos : networkPositions) {
                // Check all adjacent positions for doors/gates
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    Vector3i doorPos = new Vector3i(
                            circuitPos.getX() + offset[0],
                            circuitPos.getY() + offset[1],
                            circuitPos.getZ() + offset[2]);

                    // Skip if we already checked this door (prevents duplicates)
                    if (checkedDoors.contains(doorPos)) {
                        continue;
                    }
                    checkedDoors.add(doorPos);

                    // Check if this position has a door/gate
                    if (isDoorOrGate(doorPos)) {
                        // Get the maximum power level from adjacent circuit components
                        int maxPower = 0;
                        for (int[] powerOffset : NEIGHBOR_OFFSETS) {
                            Vector3i powerPos = new Vector3i(
                                    doorPos.getX() + powerOffset[0],
                                    doorPos.getY() + powerOffset[1],
                                    doorPos.getZ() + powerOffset[2]);

                            // Check if this position is in our network (has power)
                            if (networkPositions.contains(powerPos)) {
                                int power = getEnergyLevel(powerPos);
                                if (power > maxPower) {
                                    maxPower = power;
                                }
                            } else {
                                // Also check for power from non-network sources
                                int power = getPowerOutput(powerPos, doorPos);
                                if (power > maxPower) {
                                    maxPower = power;
                                }
                            }
                        }

                        // LOGGER.atInfo().log(PREFIX + "[DoorUpdate] Door at " + doorPos + " receiving
                        // power " + maxPower + " from network");

                        // Update the door state (only once per door)
                        doorSystem.updateDoorState(doorPos, maxPower);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log(PREFIX + "Failed to update doors in network: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks if a position contains a door or fence gate.
     * 
     * @param pos The position to check
     * @return true if the position contains a door or gate
     */
    private boolean isDoorOrGate(Vector3i pos) {
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null)
                return false;

            IChunkAccessorSync chunkAccessor = (IChunkAccessorSync) world;
            BlockType blockType = chunkAccessor.getBlockType(pos.getX(), pos.getY(), pos.getZ());

            if (blockType == null) {
                return false;
            }

            // Use the built-in isDoor() method
            if (blockType.isDoor()) {
                return true;
            }

            // Check for fence gates by ID
            String blockId = blockType.getId();
            if (blockId != null) {
                String lowerCaseId = blockId.toLowerCase();
                return lowerCaseId.contains("gate") ||
                        lowerCaseId.contains("fence_gate") ||
                        lowerCaseId.contains("fencegate");
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
