package com.circuit;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemPhysicsComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

public class FloatingItemSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, ItemPhysicsComponent> itemPhysicsType;
    private final Query<EntityStore> query;

    public FloatingItemSystem(ComponentType<EntityStore, TransformComponent> transformType,
            ComponentType<EntityStore, Velocity> velocityType,
            ComponentType<EntityStore, ItemPhysicsComponent> itemType, ComponentType<EntityStore, BoundingBox> boxType,
            ComponentType<EntityStore, ItemComponent> itemStack) {
        this.transformType = transformType;
        this.itemPhysicsType = itemType;
        this.query = Query.and(transformType, velocityType, boxType, itemStack);
    }

    @Nonnull
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    public int IsInFluid(@Nonnull Store<EntityStore> store, int x, int y, int z) {
        World world = ((EntityStore) store.getExternalData()).getWorld();
        int fluidId = 0;
        ChunkStore chunkStore = world.getChunkStore();
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        Ref<ChunkStore> columnRef = chunkStore.getChunkReference(chunkIndex);
        if (columnRef != null && columnRef.isValid()) {
            ChunkColumn column = (ChunkColumn) chunkStore.getStore().getComponent(columnRef,
                    ChunkColumn.getComponentType());
            if (column != null) {
                int sectionY = y >> 5; // Divide by 32
                Ref<ChunkStore>[] sections = column.getSections();
                if (sectionY >= 0 && sectionY < sections.length) {
                    Ref<ChunkStore> sectionRef = sections[sectionY];
                    if (sectionRef != null && sectionRef.isValid()) {
                        FluidSection fluidSection = (FluidSection) chunkStore.getStore().getComponent(sectionRef,
                                FluidSection.getComponentType());
                        if (fluidSection != null) {
                            fluidId = fluidSection.getFluidId(x & 31, y & 31, z & 31);
                        }
                    }
                }
            }
        }

        return fluidId;
    }

    public boolean IsInWater(int fluidId) {
        boolean inWater = false;
        if (fluidId != 0) {
            Fluid fluid = (Fluid) Fluid.getAssetMap().getAsset(fluidId);
            if (fluid != null && !Objects.equals(fluid.getId(), "Empty")) {
                inWater = true;
            }
        }

        return inWater;
    }

    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> buffer) {
        TransformComponent transform = (TransformComponent) chunk.getComponent(index, this.transformType);

        Ref<EntityStore> entityRef = chunk.getReferenceTo(index);
        if (transform != null) {
            boolean hasNativePhysics = chunk.getComponent(index, this.itemPhysicsType) != null;
            Vector3d pos = transform.getPosition();
            double unfilteredY = pos.getY();
            int x = (int) Math.floor(pos.getX());
            int y = (int) Math.floor(unfilteredY);
            int z = (int) Math.floor(pos.getZ());
            int fluidId = this.IsInFluid(store, x, y, z);
            int blockAbove = this.IsInFluid(store, x, y + 1, z);
            boolean inWater = this.IsInWater(fluidId);
            boolean blockAboveIsInWater = this.IsInWater(blockAbove);

            double timeVal = System.currentTimeMillis() / 1000.0;

            if (inWater) {
                if (hasNativePhysics) {
                    buffer.removeComponent(entityRef, this.itemPhysicsType);
                }

                if (!blockAboveIsInWater && !(unfilteredY < (double) y + 0.5D)) {
                    // Floating on surface
                    transform.setPosition(
                            new Vector3d(pos.getX(), pos.getY() + (dt * 0.5 * Math.sin(timeVal * 3.0)), pos.getZ()));
                } else {
                    // Underwater, float up
                    transform.setPosition(new Vector3d(pos.getX(), pos.getY() + (dt * 3.0), pos.getZ()));
                }
            } else if (!hasNativePhysics) {
                // Not in water, but physics removed? Restore physics
                buffer.addComponent(entityRef, this.itemPhysicsType, new ItemPhysicsComponent());
            }

        }
    }
}
