package com.circuit;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;
import com.hypixel.hytale.server.core.io.handlers.login.AuthenticationPacketHandler;

/**
 * Packet listener to intercept player interactions (F key press).
 */
public class CircuitPacketListener implements PacketWatcher {
    
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PREFIX = "[CircuitMod] ";
    private static final int SYNC_INTERACTION_CHAINS_PACKET_ID = 290;
    
    private final CircuitPlugin plugin;
    
    public CircuitPacketListener(CircuitPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void accept(PacketHandler packetHandler, Packet packet) {
        // Only process SyncInteractionChains packets
        if (packet.getId() != SYNC_INTERACTION_CHAINS_PACKET_ID) {
            return;
        }
        
        SyncInteractionChains interactionChains = (SyncInteractionChains) packet;
        SyncInteractionChain[] updates = interactionChains.updates;
        
        if (updates == null) {
            return;
        }
        
        for (SyncInteractionChain chain : updates) {
            if (chain == null) {
                continue;
            }
            
            InteractionType interactionType = chain.interactionType;
            
            // Check for "Use" interaction (F key)
            if (interactionType == InteractionType.Use) {
                // LOGGER.atInfo().log(PREFIX + "Use interaction detected!");
                // LOGGER.atInfo().log(PREFIX + "  itemInHandId: " + chain.itemInHandId);
                
                // Check if holding a Circuit_Lever
                if (chain.itemInHandId != null && chain.itemInHandId.contains("Circuit_Lever")) {
                    // LOGGER.atInfo().log(PREFIX + "Lever Use detected! Toggling...");
                    // TODO: Get the target block position and toggle lever state
                }
            }
            
            // Also log other interaction types for debugging
            if (interactionType != null) {
                // LOGGER.atInfo().log(PREFIX + "Interaction: type=" + interactionType + " item=" + chain.itemInHandId);
            }
        }
    }
}
