package com.autoelytra.addon.utils;

import com.autoelytra.addon.mixin.PlayerInventoryAccessor;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class InventoryUtils {

    public enum MineSwitchMode { None, Silent, Delay }

    /** Send a packet to the server. */
    public static void sendPacket(Packet<?> packet) {
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(packet);
        }
    }

    /** Switch the selected hotbar slot (0-8). */
    public static void switchToSlot(int slot) {
        ((PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(slot);
        sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    /** Get the enchantment level for a given enchantment key on a stack. */
    public static int getEnchantmentLevel(ItemStack stack, RegistryKey<Enchantment> enchantKey) {
        if (stack.isEmpty()) return 0;
        var enchants = stack.getEnchantments();
        if (enchants.isEmpty()) return 0;
        var registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        var entry = registry.getOrThrow(enchantKey);
        return enchants.getLevel(entry);
    }
}
