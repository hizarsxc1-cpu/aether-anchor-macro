package dev.aether.anchor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
    private static int waitTicks = 0;
    private static BlockPos anchorPos;
    private static int anchorSlot = -1;
    private static int glowstoneSlot = -1;

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
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        if (client.world.getRegistryKey().equals(World.NETHER)) return;

        anchorSlot = findHotbarSlot(client, Items.RESPAWN_ANCHOR);
        glowstoneSlot = findHotbarSlot(client, Items.GLOWSTONE);
        if (anchorSlot < 0 || glowstoneSlot < 0) return;

        if (!equipTotemIfPresent(client)) {
            // A totem is optional; the macro continues without one.
        }

        if (!(client.crosshairTarget instanceof BlockHitResult hit)) return;
        BlockPos placePos = hit.getBlockPos().offset(hit.getSide());
        BlockState support = client.world.getBlockState(hit.getBlockPos());
        BlockState destination = client.world.getBlockState(placePos);
        if (!support.isSideSolidFullSquare(client.world, hit.getBlockPos(), hit.getSide())
                || !destination.isReplaceable()) return;

        anchorPos = placePos.toImmutable();
        client.player.getInventory().setSelectedSlot(anchorSlot);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        stage = 1;
        waitTicks = 3;
    }

    private static void tickMacro(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            reset();
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (stage == 1) {
            if (!client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                reset();
                return;
            }
            client.player.getInventory().setSelectedSlot(glowstoneSlot);
            BlockHitResult anchorHit = new BlockHitResult(
                    Vec3d.ofCenter(anchorPos), Direction.UP, anchorPos, false
            );
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);
            client.player.swingHand(Hand.MAIN_HAND);
            stage = 2;
            waitTicks = 3;
            return;
        }

        if (stage == 2) {
            // Outside the Nether, interacting with a charged anchor detonates it.
            BlockHitResult anchorHit = new BlockHitResult(
                    Vec3d.ofCenter(anchorPos), Direction.UP, anchorPos, false
            );
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, anchorHit);
            client.player.swingHand(Hand.MAIN_HAND);
            reset();
        }
    }

    private static int findHotbarSlot(MinecraftClient client, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) return i;
        }
        return -1;
    }

    private static boolean equipTotemIfPresent(MinecraftClient client) {
        int totemSlot = findHotbarSlot(client, Items.TOTEM_OF_UNDYING);
        if (totemSlot < 0) return false;

        ItemStack offhand = client.player.getOffHandStack();
        if (offhand.isOf(Items.TOTEM_OF_UNDYING)) return true;

        client.interactionManager.clickSlot(
                client.player.playerScreenHandler.syncId,
                36 + totemSlot,
                40,
                net.minecraft.screen.slot.SlotActionType.SWAP,
                client.player
        );
        return client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private static void reset() {
        stage = 0;
        waitTicks = 0;
        anchorPos = null;
        anchorSlot = -1;
        glowstoneSlot = -1;
    }
}
