package com.circuit.ui;

import com.circuit.PipeComponent;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Görsel Pipe Diagnostics sayfası.
 */
public class PipeUIPage extends BasicCustomUIPage {
    private final PipeComponent pipe;

    // Renk Kodları (Hex)
    private static final String COLOR_DISCONNECTED = "#333333";
    private static final String COLOR_INPUT = "#0066cc"; // Mavi (Giriş / Çekme)
    private static final String COLOR_OUTPUT = "#ff8800"; // Turuncu (Çıkış / İtme)

    public PipeUIPage(PlayerRef playerRef, PipeComponent pipe) {
        super(playerRef, CustomPageLifetime.CanDismiss);
        this.pipe = pipe;
    }

    @Override
    public void build(UICommandBuilder cmd) {
        // UI dosyasını yükle
        cmd.append("PipeUI.ui");

        // 1. Buffer Görselleştirmesi (ItemSlot güncelleme)
        boolean itemSet = false;
        if (pipe.hasItem()) {
            try {
                ItemStack item = pipe.getBuffer().getItemStack((short) 0);
                if (item != null && !item.isEmpty()) {
                    // ItemSlot'a eşya verisi gönder
                    cmd.set("#BufferSlot.ItemId", item.getItemId());
                    cmd.set("#BufferSlot.Quantity", item.getQuantity());
                    cmd.set("#BufferSlot.Visible", true);
                    itemSet = true;
                }
            } catch (Exception e) {
                // Hata durumunda yoksay
            }
        }

        if (!itemSet) {
            // Eğer eşya yoksa slotu gizle
            cmd.set("#BufferSlot.Visible", false);
        }

        // 2. Bağlantı Matrisi Renklerini Güncelle
        updateDirectionNode(cmd, PipeComponent.Direction.NORTH, "#NodeNorth");
        updateDirectionNode(cmd, PipeComponent.Direction.SOUTH, "#NodeSouth");
        updateDirectionNode(cmd, PipeComponent.Direction.EAST, "#NodeEast");
        updateDirectionNode(cmd, PipeComponent.Direction.WEST, "#NodeWest");
        updateDirectionNode(cmd, PipeComponent.Direction.UP, "#NodeUp");
        updateDirectionNode(cmd, PipeComponent.Direction.DOWN, "#NodeDown");

        // 3. Genel Durum Metni
        String status = pipe.isOnCooldown() ? "Status: TRANSFERRING..." : "Status: IDLE";
        String color = pipe.isOnCooldown() ? "#00ff00" : "#cccccc";
        cmd.set("#StatusText.Text", status);
        cmd.set("#StatusText.Style.TextColor", color);
    }

    /**
     * Belirtilen yön için UI elementinin rengini ayarlar.
     */
    private void updateDirectionNode(UICommandBuilder cmd, PipeComponent.Direction dir, String elementId) {
        String color = COLOR_DISCONNECTED;

        boolean isConnected = false;
        switch (dir) {
            case NORTH:
                isConnected = pipe.isConnectedNorth();
                break;
            case SOUTH:
                isConnected = pipe.isConnectedSouth();
                break;
            case EAST:
                isConnected = pipe.isConnectedEast();
                break;
            case WEST:
                isConnected = pipe.isConnectedWest();
                break;
            case UP:
                isConnected = pipe.isConnectedUp();
                break;
            case DOWN:
                isConnected = pipe.isConnectedDown();
                break;
        }

        if (isConnected) {
            if (pipe.isOutputDirection(dir)) {
                color = COLOR_OUTPUT; // Turuncu: Çıkış yönü
            } else {
                color = COLOR_INPUT; // Mavi: Giriş yönü
            }
        }

        cmd.set(elementId + ".Background", color);
    }
}