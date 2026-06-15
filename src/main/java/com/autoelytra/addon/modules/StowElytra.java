package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class StowElytra extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<String> boxName = sg.add(new StringSetting.Builder().name("box-name").description("储存鞘翅的潜影盒名称").defaultValue("鞘翅盒子").build());
    private final Setting<Integer> holdTicks = sg.add(new IntSetting.Builder().name("hold-ticks").description("放置右键按住时长").defaultValue(10).min(5).max(30).build());

    private enum S { FIND, PLACE, OPEN, STOW, CLOSE, MINE, DONE }
    private S s; private int t;
    private int chosenSlot;

    public StowElytra() { super(AddonTemplate.CATEGORY, "stow-elytra", "储存鞘翅到盒子"); }

    @Override public void onActivate() { if (mc.player == null) { toggle(); return; } s = S.FIND; t = 0; jumped = false; chosenSlot = -1; }
    @Override public void onDeactivate() { mc.options.useKey.setPressed(false); mc.options.attackKey.setPressed(false); mc.options.jumpKey.setPressed(false); }

    @EventHandler void tick(TickEvent.Pre e) { if (mc.player == null) return; t++;
        switch (s) { case FIND -> find(); case PLACE -> place(); case OPEN -> open(); case STOW -> stow(); case CLOSE -> close(); case MINE -> mine(); case DONE -> {} } }

    void find() {
        String n = boxName.get(); int best = -1, bestUsed = Integer.MAX_VALUE;
        boolean any = false, allFull = true;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!isSh(st, n)) continue;
            any = true;
            var c = st.get(DataComponentTypes.CONTAINER); if (c == null) continue;
            int used = 0; for (ItemStack s2 : c.iterateNonEmpty()) used++;
            if (used < 27) { allFull = false; if (used < bestUsed) { bestUsed = used; best = i; } }
        }
        if (!any) { playSound("meihezi"); toggle(); return; }
        if (allFull || best < 0) { playSound("manl"); toggle(); return; }
        chosenSlot = best;
        mc.player.getInventory().setSelectedSlot(8);
        if (chosenSlot != 8) { InvUtils.quickSwap().fromId(8).to(chosenSlot); chosenSlot = 8; }
        s = S.PLACE; t = 0;
    }

    void place() {
        if (mc.currentScreen != null && !(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) mc.setScreen(null);
        mc.player.setPitch(-90); mc.options.useKey.setPressed(true);
        if (t >= holdTicks.get()) { mc.options.useKey.setPressed(false); if (mc.crosshairTarget instanceof BlockHitResult bhr) { boxPos = bhr.getBlockPos(); boxFace = bhr.getSide(); } s = S.OPEN; t = 0; }
    }

    void open() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) {
            if (mc.currentScreen != null) mc.setScreen(null);
            mc.player.setPitch(-90); mc.options.useKey.setPressed(true);
            if (t > 30) { mc.options.useKey.setPressed(false); s = S.PLACE; t = 0; }
            return;
        }
        mc.options.useKey.setPressed(false);
        if (t < 10) return;
        s = S.STOW; t = 0;
    }

    void stow() {
        var h = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler;
        if (t % 3 != 0) return; // one per 3 ticks
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.getItem() != Items.ELYTRA) continue;
            if (EnchantmentHelper.hasEnchantments(st)) continue;
            if (st.contains(DataComponentTypes.CUSTOM_NAME)) continue;
            int guiSlot = i < 9 ? 54 + i : 27 + (i - 9);
            mc.interactionManager.clickSlot(h.syncId, guiSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
            return; // one per call
        }
        // All done — no more eligible elytra
        mc.player.closeHandledScreen();
        s = S.CLOSE; t = 0;
    }

    void close() { if (t < 5) return; mc.player.getInventory().setSelectedSlot(1); s = S.MINE; t = 0; }
    private boolean jumped;
    private BlockPos boxPos;
    private net.minecraft.util.math.Direction boxFace;
    void mine() {
        if (!jumped) {
            if (mc.currentScreen != null) mc.setScreen(null);
            boolean boxGone = boxPos != null && mc.world.getBlockState(boxPos).isAir();
            if (boxGone || t >= 60) {
                mc.interactionManager.cancelBlockBreaking();
                mc.options.jumpKey.setPressed(true);
                jumped = true; t = 0; return;
            }
            if (boxPos != null) {
                net.minecraft.util.math.Direction face = boxFace != null ? boxFace : net.minecraft.util.math.Direction.DOWN;
                if (t == 0) mc.interactionManager.attackBlock(boxPos, face);
                else mc.interactionManager.updateBlockBreakingProgress(boxPos, face);
            }
        } else {
            mc.options.jumpKey.setPressed(true);
            if (t >= 40) {
                mc.options.jumpKey.setPressed(false);
                toggle();
                Run r = Modules.get().get(Run.class);
                if (r != null && !r.isActive()) r.toggle();
            }
        }
    }

    boolean isSh(ItemStack s, String n) { return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock && s.getName().getString().contains(n); }

    void playSound(String name) {
        try { mc.player.playSound(net.minecraft.sound.SoundEvent.of(Identifier.of("autoelytra", name)), 1f, 1f); } catch (Exception ignored) {}
    }
}
