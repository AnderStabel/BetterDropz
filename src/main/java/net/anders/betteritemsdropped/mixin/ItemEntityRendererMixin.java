package net.anders.betteritemsdropped.mixin;

import net.anders.betteritemsdropped.util.ItemEntityRotator;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SkullBlock;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.AliasedBlockItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

import static net.minecraft.util.math.RotationAxis.POSITIVE_X;
import static net.minecraft.util.math.RotationAxis.POSITIVE_Y;
import static net.minecraft.util.math.RotationAxis.POSITIVE_Z;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity> {

    @Shadow
    @Final
    private ItemRenderer itemRenderer;

    private final Set<ItemEntity> renderedItems = new HashSet<>();

    public ItemEntityRendererMixin(EntityRendererFactory.Context dispatcher) {
        super(dispatcher);
    }

    @Inject(at = @At("HEAD"), method = "render", cancellable = true)
    private void render(ItemEntity dropped, float f, float partialTicks, MatrixStack matrix, VertexConsumerProvider vertexConsumerProvider, int i, CallbackInfo callback) {
        if (renderedItems.contains(dropped)) {
            // If the item has already been rendered, cancel the rendering process
            callback.cancel();
            return;
        }

        renderedItems.add(dropped);

        ItemStack itemStack = dropped.getStack();

        matrix.push();
        BakedModel bakedModel = this.itemRenderer.getModel(itemStack, dropped.getWorld(), null, 0);
        boolean hasDepthInGui = bakedModel.hasDepth();

        // Helper for manipulating data on the current ItemEntity
        ItemEntityRotator rotator = (ItemEntityRotator) dropped;

        // Certain BlockItems (Grass Block, Jukebox, Dirt, Ladders) are fine being rotated 180 degrees like standard items.
        // Other BlockItems (Carpet, Slab) do not like being rotated and should stay flat.
        // To determine whether a block should be flat or rotated, we check the collision box height.
        // Anything that takes up more than half a block vertically is rotated.
        boolean renderBlockFlat = false;
        if (dropped.getStack().getItem() instanceof BlockItem && !(dropped.getStack().getItem() instanceof AliasedBlockItem)) {
            Block b = ((BlockItem) dropped.getStack().getItem()).getBlock();
            VoxelShape shape = b.getOutlineShape(b.getDefaultState(), dropped.getWorld(), dropped.getBlockPos(), ShapeContext.absent());

            // Only blocks with a collision box of <.5 should be rendered flat
            if (shape.getMax(Direction.Axis.Y) <= .5) {
                renderBlockFlat = true;
            }
        }

        // Make full blocks flush with ground
        Item item = dropped.getStack().getItem();
        if (item instanceof BlockItem && !(item instanceof AliasedBlockItem) && !renderBlockFlat) {
            // make blocks flush with the ground
            matrix.translate(0, -0.06, 0);
        }

        // Give all non-flat items a 90* spin
        if (!renderBlockFlat) {
            matrix.translate(0, 0.185, 0);
            matrix.multiply(POSITIVE_X.rotationDegrees(90));
            matrix.translate(0, -0.185, 0);
        }

        // Item is flying through air
        boolean isAboveWater = dropped.getWorld().getBlockState(dropped.getBlockPos()).getFluidState().getFluid().isIn(FluidTags.WATER);
        if (!dropped.isOnGround() && (!dropped.isSubmergedInWater() && !isAboveWater)) {
            float rotation = ((float) dropped.getItemAge() + partialTicks) / 20.0F + dropped.getHeight(); // calculate rotation based on age and ticks

            // Rotate the item on the Z axis for non-flat items
            if (!renderBlockFlat) {
                // Rotate renderer
                matrix.translate(0, 0.185, 0);
                matrix.multiply(POSITIVE_Z.rotationDegrees(rotation));
                matrix.translate(0, -0.185, 0);

                // Save rotation in entity
                rotator.setRotation(new Vec3d(0, 0, rotation));
            } else {
                // Rotate renderer
                matrix.multiply(POSITIVE_Y.rotationDegrees(rotation));

                // Save rotation in entity
                rotator.setRotation(new Vec3d(0, rotation, 0));

                // Translate down to become flush with floor
                matrix.translate(0, -0.065, 0);
            }

            // Carrots/Potatoes/Redstone/other crops in air need vertical offset
            if (dropped.getStack().getItem() instanceof AliasedBlockItem) {
                matrix.translate(0, 0, 0.195);
            } else if (!(dropped.getStack().getItem() instanceof BlockItem)) {
                // Translate down to become flush with floor
                matrix.translate(0, 0, 0.195);
            }
        } else {
            // Item is on the ground or underwater

            if (renderBlockFlat) {
                // Translate down to become flush with floor
                matrix.translate(0, -0.065, 0);
            } else {
                // Translate down to become flush with floor
                matrix.translate(0, 0, 0.195);
            }
        }

        // special-case soul sand
        if (dropped.getWorld().getBlockState(dropped.getBlockPos()).getBlock().equals(Blocks.SOUL_SAND)) {
            matrix.translate(0, 0, -0.1);
        }

        // special-case skulls
        if (dropped.getStack().getItem() instanceof BlockItem) {
            if (((BlockItem) dropped.getStack().getItem()).getBlock() instanceof SkullBlock) {
                matrix.translate(0, 0.11, 0);
            }
        }

        float scaleX = bakedModel.getTransformation().ground.scale.x();
        float scaleY = bakedModel.getTransformation().ground.scale.y();
        float scaleZ = bakedModel.getTransformation().ground.scale.z();

        float x;
        float y;
        if (!hasDepthInGui) {
            float r = -0.0F * (float) (getRenderCount(itemStack)) * 0.5F * scaleX;
            x = -0.0F * (float) (getRenderCount(itemStack)) * 0.5F * scaleY;
            y = -0.09375F * (float) (getRenderCount(itemStack)) * 0.5F * scaleZ;
            matrix.translate(r, x, y);
        }

        // Render the item
        this.itemRenderer.renderItem(itemStack, ModelTransformationMode.GROUND, false, matrix, vertexConsumerProvider, i, OverlayTexture.DEFAULT_UV, bakedModel);

        matrix.pop();
    }

    private int getRenderCount(ItemStack itemStack) {
        Item item = itemStack.getItem();
        return renderedItems.stream()
                .filter(entity -> entity.getStack().getItem().equals(item))
                .mapToInt(entity -> 1)
                .sum();
    }
}
