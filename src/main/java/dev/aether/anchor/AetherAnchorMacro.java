package dev.aether.anchor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

public class AetherAnchorMacro implements ClientModInitializer {
    private static KeyBinding activateKey;
    private static int stage = 0;
    private static int syncWaitTicks = 0;
    private static BlockPos anchorPos;
    private static BlockHitResult placementHit;
    private static int anchorSlot = -1;
    private static int glowstoneSlot = -1;
    private static int totemSlot = -1;

    @Override
    public void onInitializeClient() {
        activateKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aetheranchor.activate",
                net.minecraft.client.util.InputUtil.Type.MOUSE,
                GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                "category.aetheranchor"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (activateKey.wasPressed()) {
                if (stage == 0) startMacro(client);
            }
            if (stage != 0) tickMacro(client);
        });
    }

    private static void startMacro(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null
                || client.getNetworkHandler() == null) return;
        if (client.world.getRegistryKey().equals(World.NETHER)) return;

        anchorSlot = findHotbarSlot(client, Items.RESPAWN_ANCHOR);
        glowstoneSlot = findHotbarSlot(client, Items.GLOWSTONE);
        totemSlot = findHotbarSlot(client, Items.TOTEM_OF_UNDYING);
        if (anchorSlot < 0 || glowstoneSlot < 0) {
            reset();
            return;
        }

        placementHit = findPlacementHit(client);
        if (placementHit == null) {
            reset();
            return;
        }

        anchorPos = placementHit.getBlockPos().offset(placementHit.getSide()).toImmutable();
        selectHotbarSlot(client, anchorSlot);
        stage = 1;
        syncWaitTicks = 0;
    }

    private static BlockHitResult findPlacementHit(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) return null;

        BlockPos aimedPos = hit.getBlockPos();
        BlockState aimedState = client.world.getBlockState(aimedPos);

        if (aimedState.isOf(Blocks.FIRE) || aimedState.isOf(Blocks.SOUL_FIRE)) {
            return hitOnBlockBelowFire(client, aimedPos);
        }

        BlockPos abovePos = aimedPos.up();
        BlockState aboveState = client.world.getBlockState(abovePos);
        if (aboveState.isOf(Blocks.FIRE) || aboveState.isOf(Blocks.SOUL_FIRE)) {
            BlockHitResult fireHit = hitOnBlockBelowFire(client, abovePos);
            if (fireHit != null) return fireHit;
        }

        BlockPos placePos = aimedPos.offset(hit.getSide());
        BlockState support = client.world.getBlockState(aimedPos);
        BlockState destination = client.world.getBlockState(placePos);
        if (!support.isSideSolidFullSquare(client.world, aimedPos, hit.getSide())
                || !destination.isReplaceable()) return null;
        return hit;
    }

    private static BlockHitResult hitOnBlockBelowFire(MinecraftClient client, BlockPos firePos) {
        BlockPos supportPos = firePos.down();
        BlockState support = client.world.getBlockState(supportPos);
        BlockState fireState = client.world.getBlockState(firePos);
        if (!support.isSideSolidFullSquare(client.world, supportPos, Direction.UP)
                || !fireState.isReplaceable()) return null;
        return new BlockHitResult(
                Vec3d.ofCenter(supportPos).add(0.0, 0.499, 0.0),
                Direction.UP,
                supportPos,
                false
        );
    }

    private static void tickMacro(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null
                || client.getNetworkHandler() == null) {
            reset();
            return;
        }

        if (stage == 1) {
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, placementHit);
            client.player.swingHand(Hand.MAIN_HAND);
            stage = 2;
            syncWaitTicks = 0;
            return;
        }

        if (stage == 2) {
            if (!client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                if (++syncWaitTicks <= 2) return;
                reset();
                return;
            }

            // Send the hotbar packet before the interaction packet. Merely
            // changing selectedSlot does not guarantee the server has seen it yet.
            selectHotbarSlot(client, glowstoneSlot);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit());
            client.player.swingHand(Hand.MAIN_HAND);
            stage = 3;
            syncWaitTicks = 0;
            return;
        }

        if (stage == 3) {
            BlockState state = client.world.getBlockState(anchorPos);
            if (!state.isOf(Blocks.RESPAWN_ANCHOR)
                    || state.get(RespawnAnchorBlock.CHARGES) <= 0) {
                if (++syncWaitTicks <= 2) return;
                reset();
                return;
            }

            // Prefer Totem, otherwise use any slot except Anchor/Glowstone.
            int detonationSlot = totemSlot >= 0 ? totemSlot : findDetonationSlot();
            selectHotbarSlot(client, detonationSlot);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit());
            client.player.swingHand(Hand.MAIN_HAND);
            reset();
        }
    }

    private static void selectHotbarSlot(MinecraftClient client, int slot) {
        if (slot < 0 || slot > 8) return;
        client.player.getInventory().selectedSlot = slot;
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private static BlockHitResult anchorHit() {
        // Aim just inside the upper face so the hit point and face agree.
        return new BlockHitResult(
                Vec3d.ofCenter(anchorPos).add(0.0, 0.49, 0.0),
                Direction.UP,
                anchorPos,
                false
        );
    }

    private static int findDetonationSlot() {
        for (int i = 0; i < 9; i++) {
            if (i != glowstoneSlot && i != anchorSlot) return i;
        }
        return 0;
    }

    private static int findHotbarSlot(MinecraftClient client, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) return i;
        }
        return -1;
    }

    private static void reset() {
        stage = 0;
        syncWaitTicks = 0;
        anchorPos = null;
        placementHit = null;
        anchorSlot = -1;
        glowstoneSlot = -1;
        totemSlot = -1;
    }
}