package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class StowElytra extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<String> boxName = sg.add(new StringSetting.Builder().name("box-name").description("储存鞘翅的潜影盒名称").defaultValue("鞘翅盒子").build());

    private enum S { FIND, PLACE, WAIT_BOX, OPEN, STOW, CLOSE, MINE, PICKUP, DONE }
    private S s; private int t;
    private int chosenSlot;
    private BlockPos boxPos;
    private int preBoxCount;

    public StowElytra() { super(AddonTemplate.CATEGORY, "stow-elytra", "储存鞘翅到盒子"); }

    @Override public void onActivate() { if (mc.player == null) { toggle(); return; } s = S.FIND; t = 0; chosenSlot = -1; boxPos = null; preBoxCount = 0; }
    @Override public void onDeactivate() { mc.options.useKey.setPressed(false); mc.options.attackKey.setPressed(false); mc.options.jumpKey.setPressed(false); }

    @EventHandler void tick(TickEvent.Pre e) { if (mc.player == null) return; t++;
        switch (s) { case FIND -> find(); case PLACE -> place(); case WAIT_BOX -> waitBox(); case OPEN -> open(); case STOW -> stow(); case CLOSE -> close(); case MINE -> mine(); case PICKUP -> pickup(); case DONE -> {} } }

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
        s = S.PLACE; t = 0; place();
    }

    void place() {
        BlockPos pos = findPlacePos();
        if (pos == null) { playSound("meihezi"); toggle(); return; }
        boxPos = pos;
        BlockPos clickPos = pos.down();
        Vec3d hitVec = new Vec3d(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, clickPos, false);

        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 100, () -> {
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.getNetworkHandler().sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
        });

        s = S.WAIT_BOX; t = 0;
    }

    void waitBox() {
        if (boxPos == null) { playSound("meihezi"); toggle(); return; }
        if (mc.world.getBlockState(boxPos).getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
            s = S.OPEN; t = 0; open(); return;
        }
        if (t > 40) { playSound("meihezi"); toggle(); return; }
    }

    void open() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            if (t < 10) return;
            s = S.STOW; t = 0; return;
        }
        if (t > 20) { playSound("meihezi"); toggle(); return; }

        Vec3d hitVec = new Vec3d(boxPos.getX() + 0.5, boxPos.getY() + 0.5, boxPos.getZ() + 0.5);
        Direction face = bestFace(boxPos);
        BlockHitResult hit = new BlockHitResult(hitVec, face, boxPos, false);

        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 100, () -> {
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.getNetworkHandler().sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
        });
    }

    void stow() {
        var h = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler;
        if (t % 3 != 0) return;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.getItem() != Items.ELYTRA) continue;
            if (st.hasEnchantments()) continue;
            if (st.contains(DataComponentTypes.CUSTOM_NAME)) continue;
            int guiSlot = i < 9 ? 54 + i : 27 + (i - 9);
            mc.interactionManager.clickSlot(h.syncId, guiSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
            return;
        }
        mc.player.closeHandledScreen();
        s = S.CLOSE; t = 0;
    }

    void close() { if (t < 5) return; mc.player.getInventory().setSelectedSlot(1); s = S.MINE; t = 0; mine(); }

    void mine() {
        if (boxPos == null) { s = S.PICKUP; t = 0; pickup(); return; }
        if (mc.world.isAir(boxPos) || mc.world.getBlockState(boxPos).isReplaceable()) {
            info("盒子已挖掉，寻路捡起");
            s = S.PICKUP; t = 0; pickup(); return;
        }
        if (t == 1) {
            if (!PacketMine.INSTANCE.isActive()) PacketMine.INSTANCE.toggle();
            PacketMine.targetPos = null;
            PacketMine.INSTANCE.mine(boxPos);
        }
    }

    void pickup() {
        if (t == 0) {
            preBoxCount = countBoxByName();
            mc.getNetworkHandler().sendChatMessage("#pickup minecraft:shulker_box minecraft:white_shulker_box minecraft:orange_shulker_box minecraft:magenta_shulker_box minecraft:light_blue_shulker_box minecraft:yellow_shulker_box minecraft:lime_shulker_box minecraft:pink_shulker_box minecraft:gray_shulker_box minecraft:light_gray_shulker_box minecraft:cyan_shulker_box minecraft:purple_shulker_box minecraft:blue_shulker_box minecraft:brown_shulker_box minecraft:green_shulker_box minecraft:red_shulker_box minecraft:black_shulker_box");
        }
        if (t > 20 && countBoxByName() > preBoxCount) {
            info("确认捡到盒子(原" + preBoxCount + "→现" + countBoxByName() + ")");
            mc.getNetworkHandler().sendChatMessage("#stop");
            toggle();
            Run r = Modules.get().get(Run.class);
            if (r != null && !r.isActive()) r.toggle();
        }
        if (t > 100) {
            info("5秒未捡到，重发拾取");
            t = 0; pickup(); return;
        }
    }

    int countBoxByName() {
        int n = 0;
        String target = boxName.get();
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock && s.getName().getString().contains(target))
                n++;
        }
        return n;
    }

    void info(String m) { System.out.println("[StowElytra] " + m); if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§9[StowElytra] " + m), false); }

    boolean isSh(ItemStack s, String n) { return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock && s.getName().getString().contains(n); }

    void playSound(String name) {
        try { mc.player.playSound(net.minecraft.sound.SoundEvent.of(Identifier.of("autoelytra", name)), 1f, 1f); } catch (Exception ignored) {}
    }

    BlockPos findPlacePos() {
        int r = 4;
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDist = 999.0;
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -2; y <= 2; y++) {
                    m.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    BlockPos below = m.down();
                    BlockState belowState = mc.world.getBlockState(below);
                    if (!mc.world.isAir(m)) continue;
                    if (belowState.isAir()) continue;
                    if (!belowState.isSolidBlock((BlockView) mc.world, below)) continue;
                    if (isClickable(belowState.getBlock())) continue;
                    BlockState selfState = mc.world.getBlockState(m);
                    if (!selfState.isReplaceable()) continue;
                    Box blockBox = new Box(m);
                    if (mc.player.getBoundingBox().intersects(blockBox)) continue;
                    double d = m.toCenterPos().squaredDistanceTo(mc.player.getEyePos());
                    if (d < 1.0) continue;
                    if (d < bestDist) { bestDist = d; best = m.toImmutable(); }
                }
            }
        }
        return best;
    }

    boolean isClickable(net.minecraft.block.Block b) {
        return b instanceof net.minecraft.block.CraftingTableBlock
            || b instanceof net.minecraft.block.AnvilBlock
            || b instanceof net.minecraft.block.BlockWithEntity
            || b instanceof net.minecraft.block.BedBlock
            || b instanceof net.minecraft.block.FenceGateBlock
            || b instanceof net.minecraft.block.DoorBlock
            || b instanceof net.minecraft.block.NoteBlock
            || b instanceof net.minecraft.block.TrapdoorBlock;
    }

    Direction bestFace(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Direction best = Direction.UP;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            double cx = pos.getX() + 0.5 + d.getVector().getX() * 0.5;
            double cy = pos.getY() + 0.5 + d.getVector().getY() * 0.5;
            double cz = pos.getZ() + 0.5 + d.getVector().getZ() * 0.5;
            double dist = eye.squaredDistanceTo(cx, cy, cz);
            if (dist < bestD) { bestD = dist; best = d; }
        }
        return best;
    }
}
