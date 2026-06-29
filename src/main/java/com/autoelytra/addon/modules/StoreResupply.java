package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class StoreResupply extends Module {

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> enFW = sg.add(new BoolSetting.Builder().name("enable-fw").description("烟花补给").defaultValue(true).build());
    private final Setting<Boolean> enFD = sg.add(new BoolSetting.Builder().name("enable-fd").description("食物补给").defaultValue(true).build());
    private final Setting<Boolean> enTT = sg.add(new BoolSetting.Builder().name("enable-tt").description("图腾补给").defaultValue(true).build());
    private final Setting<Boolean> enRP = sg.add(new BoolSetting.Builder().name("enable-rp").description("鞘翅修复").defaultValue(true).build());
    private final Setting<Integer> minFW = sg.add(new IntSetting.Builder().name("min-fw").description("烟花最低").defaultValue(32).min(1).sliderMax(64).visible(enFW::get).build());
    private final Setting<Integer> minFD = sg.add(new IntSetting.Builder().name("min-fd").description("食物最低").defaultValue(32).min(1).sliderMax(64).visible(enFD::get).build());
    private final Setting<Integer> minDur = sg.add(new IntSetting.Builder().name("min-dur").description("鞘翅耐久%").defaultValue(40).min(1).max(100).visible(enRP::get).build());
    private final Setting<Integer> minTotem = sg.add(new IntSetting.Builder().name("min-totem").description("图腾最低").defaultValue(3).min(1).sliderMax(64).visible(enTT::get).build());
    private final Setting<Integer> fwSupply = sg.add(new IntSetting.Builder().name("fw-supply").description("烟花补给组数").defaultValue(3).min(1).max(6).visible(enFW::get).build());
    private final Setting<Integer> fdSupply = sg.add(new IntSetting.Builder().name("fd-supply").description("食物补给组数").defaultValue(3).min(1).max(6).visible(enFD::get).build());
    private final Setting<Integer> xpSupply = sg.add(new IntSetting.Builder().name("xp-supply").description("经验瓶补给组数").defaultValue(3).min(1).max(6).visible(enRP::get).build());
    private final Setting<Integer> totemSupply = sg.add(new IntSetting.Builder().name("totem-supply").description("图腾补给数量").defaultValue(3).min(1).sliderMax(64).visible(enTT::get).build());
    private final Setting<String> name = sg.add(new StringSetting.Builder().name("box-name").description("补给潜影盒名称").defaultValue("补给盒").build());
    private final Setting<Integer> xpIV = sg.add(new IntSetting.Builder().name("xp-delay").description("经验瓶间隔(tick)").defaultValue(10).min(2).sliderMax(40).build());
    private final Setting<Integer> stowDelay = sg.add(new IntSetting.Builder().name("stow-delay").description("补给完成后延迟开StowElytra(tick)").defaultValue(30).min(0).sliderMax(120).build());
    private final Setting<Integer> placeRange = sg.add(new IntSetting.Builder().name("place-range").description("放置潜影盒距离").defaultValue(4).min(1).max(6).build());
    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder().name("rotate").description("旋转后发包放置").defaultValue(true).build());

    private enum S { CHECK, FIND, PLACE, WAIT_BOX, OPEN_BOX, WAIT_OPEN, OPEN, CLOSE, REPAIR, MINE_BOX, PICKUP, DONE }
    private S s; private int t, ts;
    private boolean nFW, nFD, nRP, nTT;
    private int bs;
    private BlockPos placedPos;
    private int placeRetries;
    private int preBoxCount;

    static final Set<Item> FD = Set.of(Items.GOLDEN_CARROT, Items.BREAD, Items.COOKED_PORKCHOP, Items.COOKED_BEEF);

    public StoreResupply() { super(AddonTemplate.CATEGORY, "store-resupply", "补给"); }

    @Override public void onActivate() {
        if (mc.player == null) { toggle(); return; }
        mc.options.useKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        s = S.CHECK; t = ts = 0; nFW = nFD = nRP = nTT = false; bs = -1; placeRetries = 0;
        info("检查物资...");
    }
    @Override public void onDeactivate() {
        mc.options.useKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        placedPos = null;
    }

    @EventHandler void tick(TickEvent.Pre e) {
        if (mc.player == null) return;
        t++;
        switch (s) {
            case CHECK -> ck(); case FIND -> fd(); case PLACE -> pl(); case WAIT_BOX -> wb();
            case OPEN_BOX -> ob(); case WAIT_OPEN -> wo(); case OPEN -> op();
            case CLOSE -> cl(); case REPAIR -> rp(); case MINE_BOX -> mb(); case PICKUP -> pu(); case DONE -> {}
        }
    }

    // ── CHECK ──
    void ck() {
        int fw = 0, fd = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.getItem() == Items.FIREWORK_ROCKET) fw += st.getCount();
            if (FD.contains(st.getItem())) fd += st.getCount();
        }
        int tt = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) tt += mc.player.getInventory().getStack(i).getCount();
        nFW = enFW.get() && fw < minFW.get(); nFD = enFD.get() && fd < minFD.get(); nTT = enTT.get() && tt < minTotem.get();
        nRP = false;
        if (enRP.get()) {
            ItemStack ch = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (ch.getItem() == Items.ELYTRA) {
                int m = ch.getMaxDamage();
                nRP = m > 0 && (1f - (float) ch.getDamage() / m) * 100f < minDur.get();
            }
        }
        if (!nFW && !nFD && !nRP && !nTT) { info("充足，开启StowElytra"); s = S.DONE; StowElytra se = Modules.get().get(StowElytra.class); if (se != null && !se.isActive()) se.toggle(); toggle(); return; }
        info("缺:" + (nFW ? "烟花 " : "") + (nFD ? "食物 " : "") + (nTT ? "图腾 " : "") + (nRP ? "鞘翅" : ""));
        if (!nFW && !nFD && !nTT && nRP && ci(Items.EXPERIENCE_BOTTLE) > 64) {
            info("仅需修复鞘翅，直接修复");
            s = S.REPAIR; t = 0; return;
        }
        s = S.FIND; t = 0;
    }

    // ── FIND ──
    void fd() {
        int best = -1, bestScore = -1; boolean foundEnough = false;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!isSh(st)) continue;
            int fw = shC(st, Items.FIREWORK_ROCKET), fd = shFC(st), xp = shC(st, Items.EXPERIENCE_BOTTLE), tt = shC(st, Items.TOTEM_OF_UNDYING);
            boolean enough = true;
            if (nFW && fw <= minFW.get()) enough = false;
            if (nFD && fd <= minFD.get()) enough = false;
            if (nRP && xp <= 64) enough = false;
            if (nTT && tt <= 0) enough = false;
            if (enough) {
                int score = (nFW?fw:0) + (nFD?fd:0) + (nRP?xp:0) + (nTT?tt*64:0);
                if (!foundEnough || score > bestScore) { bestScore = score; best = i; foundEnough = true; }
            } else if (!foundEnough) {
                int score = (nFW?fw:0) + (nFD?fd:0) + (nRP?xp:0) + (nTT?tt*64:0);
                if (score > bestScore) { bestScore = score; best = i; }
            }
        }
        if (best < 0) { info("无补给盒"); toggle(); return; }
        bs = best;
        ItemStack bx = mc.player.getInventory().getStack(bs);
        int fw = shC(bx, Items.FIREWORK_ROCKET), fd = shFC(bx), xp = shC(bx, Items.EXPERIENCE_BOTTLE), tt = shC(bx, Items.TOTEM_OF_UNDYING);
        boolean enough = true;
        if (nFW && fw <= minFW.get()) enough = false;
        if (nFD && fd <= minFD.get()) enough = false;
        if (nRP && xp <= 64) enough = false;
        if (nTT && tt <= 0) enough = false;
        if (!enough) { info("盒内物品不足，需末影箱补给"); toggle(); return; }
        info("切到补给盒，自动放置");
        mc.player.getInventory().setSelectedSlot(8);
        if (bs != 8) { InvUtils.quickSwap().fromId(8).to(bs); bs = 8; }
        s = S.PLACE; t = 0; pl();
    }

    // ── PLACE ── 自动放置潜影盒（旋转 + 发包）
    void pl() {
        BlockPos pos = findPlacePos();
        if (pos == null) { info("无合适放置位置"); toggle(); return; }
        placedPos = pos;
        BlockPos clickPos = pos.down();
        Vec3d hitVec = new Vec3d(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, clickPos, false);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 100, () -> {
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.getNetworkHandler().sendPacket(
                    new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
            });
        } else {
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.getNetworkHandler().sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
        }

        info("已放置盒子 @" + pos.toShortString());
        s = S.WAIT_BOX; t = 0; placeRetries++;
    }

    // ── WAIT_BOX ── 等待盒子出现，超时 0.5s 后重试
    void wb() {
        if (placedPos == null) { info("放置位置丢失"); toggle(); return; }
        if (!(mc.world.getBlockState(placedPos).getBlock() instanceof net.minecraft.block.ShulkerBoxBlock)) {
            if (t > 10) {
                info("放置超时，重试(" + placeRetries + ")...");
                s = S.PLACE; t = 0; pl(); return;
            }
            return;
        }
        s = S.OPEN_BOX; t = 0; ob();
    }

    // ── OPEN_BOX ── 右键打开盒子（旋转 + 发包，点最近可见面）
    void ob() {
        if (placedPos == null) { info("放置位置丢失"); toggle(); return; }
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            info("检测到盒子打开，开始补给");
            s = S.WAIT_OPEN; t = 0; ts = 0; return;
        }
        if (t > 20) { info("无法打开盒子"); toggle(); return; }

        BlockPos cpos = placedPos;
        HitResult hr = bestFace(cpos);
        Vec3d hitVec = hr.hitVec;
        Direction face = hr.face;

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 100, () -> {
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.getNetworkHandler().sendPacket(
                    new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, new BlockHitResult(hitVec, face, cpos, false), 0));
            });
        } else {
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.getNetworkHandler().sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, new BlockHitResult(hitVec, face, cpos, false), 0));
        }
    }

    // ── WAIT_OPEN ──
    void wo() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            info("检测到盒子打开，开始补给");
            s = S.OPEN; t = 0; ts = 0;
        }
        // No timeout — wait indefinitely for user to open the box
    }

    // ── OPEN ──
    void op() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) {
            info("盒子关闭，继续下一步");
            s = S.CLOSE; t = 0; return;
        }
        if (t > 200) { info("补给超时，强制关闭"); mc.player.closeHandledScreen(); s = S.CLOSE; t = 0; return; }
        if (t < 10) return;
        int tgFW = fwSupply.get() * 64, tgFD = fdSupply.get() * 64, tgXP = xpSupply.get() * 64, tgTT = totemSupply.get();
        if (t % 3 != 0) return;
        switch (ts) {
            case 0 -> { if (nFW && ci(Items.FIREWORK_ROCKET) < tgFW && hs(Items.FIREWORK_ROCKET)) qm(Items.FIREWORK_ROCKET); else ts = 1; }
            case 1 -> { if (nFD && cf() < tgFD && hsf()) qmf(); else ts = 2; }
            case 2 -> { if (nRP && ci(Items.EXPERIENCE_BOTTLE) < tgXP && hs(Items.EXPERIENCE_BOTTLE)) qm(Items.EXPERIENCE_BOTTLE); else ts = 3; }
            case 3 -> { if (nTT && ci(Items.TOTEM_OF_UNDYING) < tgTT && hs(Items.TOTEM_OF_UNDYING)) qm(Items.TOTEM_OF_UNDYING); else ts = 4; }
            case 4 -> { info("完成"); s = S.CLOSE; t = 0; }
        }
    }

    // ── CLOSE ──
    void cl() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) mc.player.closeHandledScreen();
        if (t < 5) return;
        if (nRP) { info("开始修复鞘翅"); s = S.REPAIR; t = 0; return; }
        info("补给完成，清理盒子");
        s = S.MINE_BOX; t = 0; mb();
    }

    // ── REPAIR ──
    void rp() {
        ItemStack ch = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (ch.getItem() != Items.ELYTRA) { info("无鞘翅"); toggle(); return; }
        float pct = ch.getMaxDamage() > 0 ? (1f - (float) ch.getDamage() / ch.getMaxDamage()) * 100f : 100f;
        if (pct >= 98) { info("修好"); s = S.MINE_BOX; t = 0; mb(); return; }
        FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
        if (!xp.found()) { info("无经验瓶"); toggle(); return; }
        mc.player.getInventory().setSelectedSlot(7);
        if (xp.slot() != 7) {
            if (xp.isHotbar()) mc.player.getInventory().setSelectedSlot(xp.slot());
            else InvUtils.quickSwap().fromId(7).to(xp.slot());
        }
        if (t == 1) mc.player.jump();
        if (t % xpIV.get() == 0) {
            Rotations.rotate(mc.player.getYaw(), 90, () ->
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND));
        }
    }

    // ── MINE_BOX ── 用 PacketMine 挖掉放置的盒子
    void mb() {
        if (placedPos == null) { info("跳过挖盒"); s = S.PICKUP; t = 0; pu(); return; }
        if (mc.world.isAir(placedPos) || mc.world.getBlockState(placedPos).isReplaceable()) {
            info("盒子已消失"); s = S.PICKUP; t = 0; pu(); return;
        }
        if (t == 1) {
            if (!PacketMine.INSTANCE.isActive()) PacketMine.INSTANCE.toggle();
            PacketMine.targetPos = null;
            PacketMine.INSTANCE.mine(placedPos);
        }
        if (t > 30 && (mc.world.isAir(placedPos) || mc.world.getBlockState(placedPos).isReplaceable())) {
            info("盒子已挖掉"); s = S.PICKUP; t = 0; pu(); return;
        }
    }

    // ── PICKUP ── Baritone #pickup 寻路捡盒，确认同名盒子数量增加后 #stop
    void pu() {
        if (t == 0) {
            preBoxCount = countBoxByName();
            mc.getNetworkHandler().sendChatMessage("#pickup minecraft:shulker_box minecraft:white_shulker_box minecraft:orange_shulker_box minecraft:magenta_shulker_box minecraft:light_blue_shulker_box minecraft:yellow_shulker_box minecraft:lime_shulker_box minecraft:pink_shulker_box minecraft:gray_shulker_box minecraft:light_gray_shulker_box minecraft:cyan_shulker_box minecraft:purple_shulker_box minecraft:blue_shulker_box minecraft:brown_shulker_box minecraft:green_shulker_box minecraft:red_shulker_box minecraft:black_shulker_box");
        }
        if (t > 20 && countBoxByName() > preBoxCount) {
            info("确认捡到盒子(原" + preBoxCount + "→现" + countBoxByName() + ")");
            done();
        }
        if (t > 100) {
            info("5秒未捡到，重发拾取");
            t = 0; pu(); return;
        }
    }

    int countBoxByName() {
        int n = 0;
        String target = name.get();
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock && s.getName().getString().contains(target))
                n++;
        }
        return n;
    }

    void done() {
        mc.getNetworkHandler().sendChatMessage("#stop");
        info("清理完成，开启 StowElytra");
        StowElytra se = Modules.get().get(StowElytra.class);
        if (se != null && !se.isActive()) se.toggle();
        s = S.DONE; toggle();
    }

    // ── HELPERS ──
    boolean isSh(ItemStack s) { return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock && s.getName().getString().contains(name.get()); }
    int shC(ItemStack sh, Item it) { var c = sh.get(DataComponentTypes.CONTAINER); if (c == null) return 0; int n = 0; for (ItemStack s : c.iterateNonEmpty()) if (s.getItem() == it) n += s.getCount(); return n; }
    int shFC(ItemStack sh) { var c = sh.get(DataComponentTypes.CONTAINER); if (c == null) return 0; int n = 0; for (ItemStack s : c.iterateNonEmpty()) if (FD.contains(s.getItem())) n += s.getCount(); return n; }
    int ci(Item it) { int n = 0; for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).getItem() == it) n += mc.player.getInventory().getStack(i).getCount(); return n; }
    int cf() { int n = 0; for (int i = 0; i < 36; i++) if (FD.contains(mc.player.getInventory().getStack(i).getItem())) n += mc.player.getInventory().getStack(i).getCount(); return n; }
    boolean hs(Item it) { var h = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler; for (int i = 0; i < 27; i++) if (h.getSlot(i).getStack().getItem() == it) return true; return false; }
    boolean hsf() { var h = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler; for (int i = 0; i < 27; i++) if (FD.contains(h.getSlot(i).getStack().getItem())) return true; return false; }
    void qm(Item it) { var h = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler; for (int i = 0; i < 27; i++) if (h.getSlot(i).getStack().getItem() == it) { mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player); return; } }
    void qmf() { var h = (ShulkerBoxScreenHandler) mc.player.currentScreenHandler; for (int i = 0; i < 27; i++) if (FD.contains(h.getSlot(i).getStack().getItem())) { mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player); return; } }

    // ── PLACE HELPERS ──
    private record HitResult(Vec3d hitVec, Direction face) {}

    HitResult bestFace(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Direction best = Direction.UP;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            double cx = pos.getX() + 0.5 + d.getVector().getX() * 0.5;
            double cy = pos.getY() + 0.5 + d.getVector().getY() * 0.5;
            double cz = pos.getZ() + 0.5 + d.getVector().getZ() * 0.5;
            double dist = eye.squaredDistanceTo(cx, cy, cz);
            if (dist < bestDist) { bestDist = dist; best = d; }
        }
        Direction f = best;
        return new HitResult(new Vec3d(
            pos.getX() + 0.5 + f.getVector().getX() * 0.5,
            pos.getY() + 0.5 + f.getVector().getY() * 0.5,
            pos.getZ() + 0.5 + f.getVector().getZ() * 0.5
        ), f);
    }

    BlockPos findPlacePos() {
        int r = placeRange.get();
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
                    // pos must be air/replaceable, below must be solid & can right-click
                    if (!mc.world.isAir(m)) continue;
                    if (belowState.isAir()) continue;
                    if (!belowState.isSolidBlock((BlockView) mc.world, below)) continue;
                    if (isClickable(belowState.getBlock())) continue;
                    BlockState selfState = mc.world.getBlockState(m);
                    if (!selfState.isReplaceable()) continue;
                    // exclude pos on or inside player
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

    void info(String m) { System.out.println("[StoreResupply] " + m); if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§9[StoreResupply] " + m), false); }
}
