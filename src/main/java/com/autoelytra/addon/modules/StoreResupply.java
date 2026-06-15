package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

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
    private final Setting<Boolean> showUI = sg.add(new BoolSetting.Builder().name("show-ui").description("补给时显示潜影盒界面").defaultValue(false).build());
    private final Setting<Integer> stowDelay = sg.add(new IntSetting.Builder().name("stow-delay").description("补给完成后延迟开StowElytra(tick)").defaultValue(30).min(0).sliderMax(120).build());

    private enum S { CHECK, FIND, PLACE, OPEN, CLOSE, MINE, JUMP, WAIT, REPAIR, DONE }
    private S s; private int t, ts;
    private boolean nFW, nFD, nRP, nTT;
    private int bs;
    private BlockPos boxPos;
    private net.minecraft.util.math.Direction boxFace;

    static final Set<Item> FD = Set.of(Items.GOLDEN_CARROT, Items.BREAD, Items.COOKED_PORKCHOP, Items.COOKED_BEEF);

    public StoreResupply() { super(AddonTemplate.CATEGORY, "store-resupply", "补给"); }

    @Override public void onActivate() {
        if (mc.player == null) { toggle(); return; }
        mc.options.useKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        s = S.CHECK; t = ts = 0; nFW = nFD = nRP = false; bs = -1; boxPos = null;
        info("开始检查物资...");
    }
    @Override public void onDeactivate() {
        mc.options.useKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
    }

    @EventHandler void tick(TickEvent.Pre e) {
        if (mc.player == null) return;
        t++;
        switch (s) {
            case CHECK -> ck(); case FIND -> fd(); case PLACE -> pl(); case OPEN -> op();
            case CLOSE -> cl(); case MINE -> mi(); case JUMP -> jump(); case WAIT -> waitStow(); case REPAIR -> rp(); case DONE -> {}
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
        int tt = 0; for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) tt += mc.player.getInventory().getStack(i).getCount();
        nFW = enFW.get() && fw < minFW.get(); nFD = enFD.get() && fd < minFD.get(); nTT = enTT.get() && tt < minTotem.get();
        nRP = false;
        if (enRP.get()) {
            ItemStack ch = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (ch.getItem() == Items.ELYTRA) {
                int m = ch.getMaxDamage();
                nRP = m > 0 && (1f - (float) ch.getDamage() / m) * 100f < minDur.get();
            }
        }
        if (!nFW && !nFD && !nRP && !nTT) { info("充足"); mc.options.jumpKey.setPressed(true); s = S.WAIT; t = 0; return; }
        info("缺:" + (nFW ? "烟花 " : "") + (nFD ? "食物 " : "") + (nTT ? "图腾 " : "") + (nRP ? "鞘翅" : ""));
        // Only need repair and already have XP bottles → skip box, repair directly
        if (!nFW && !nFD && !nTT && nRP && ci(Items.EXPERIENCE_BOTTLE) > 64) {
            info("仅需修复鞘翅且身上有经验瓶，直接修复");
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
                // Score = total needed items available
                int score = (nFW?fw:0) + (nFD?fd:0) + (nRP?xp:0) + (nTT?tt*64:0);
                if (!foundEnough || score > bestScore) { bestScore = score; best = i; foundEnough = true; }
            } else if (!foundEnough) {
                // No box meets minimum yet — track any that has at least something
                int score = (nFW?fw:0) + (nFD?fd:0) + (nRP?xp:0) + (nTT?tt*64:0);
                if (score > bestScore) { bestScore = score; best = i; }
            }
        }
        if (best < 0) { info("无盒"); toggle(); return; }
        bs = best;
        ItemStack bx = mc.player.getInventory().getStack(bs);
        int fw = shC(bx, Items.FIREWORK_ROCKET), fd = shFC(bx), xp = shC(bx, Items.EXPERIENCE_BOTTLE), tt = shC(bx, Items.TOTEM_OF_UNDYING);
        boolean enough = true;
        if (nFW && fw <= minFW.get()) enough = false;
        if (nFD && fd <= minFD.get()) enough = false;
        if (nRP && xp <= 64) enough = false;
        if (nTT && tt <= 0) enough = false;
        if (!enough) {
            info("物品低于最小物品数量或没有物品，下一次开启末影箱补给");
            toggle(); return;
        }
        info("物品不足但剩余大于最小值，继续补给，下次开启末影箱补齐");
        mc.player.getInventory().setSelectedSlot(8);
        if (bs != 8) { InvUtils.quickSwap().fromId(8).to(bs); bs = 8; }
        s = S.PLACE; t = 0; ts = 0;
    }

    // ── PLACE ──
    void pl() {
        if (mc.currentScreen != null && !(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) mc.setScreen(null);
        mc.player.setPitch(-90); mc.options.useKey.setPressed(true);
        if (t >= 10) { mc.options.useKey.setPressed(false); if (mc.crosshairTarget instanceof BlockHitResult bhr) { boxPos = bhr.getBlockPos(); boxFace = bhr.getSide(); } s = S.OPEN; t = 0; ts = 0; }
    }

    // ── OPEN ──
    void op() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler)) {
            if (mc.currentScreen != null) mc.setScreen(null);
            mc.player.setPitch(-90); mc.options.useKey.setPressed(true);
            if (t > 30) { mc.options.useKey.setPressed(false); s = S.PLACE; t = 0; ts = 0; }
            return;
        }
        mc.options.useKey.setPressed(false);
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
        mc.player.getInventory().setSelectedSlot(1);
        s = S.MINE; t = 0;
    }

    // ── MINE ──
    void mi() {
        if (mc.currentScreen != null) mc.setScreen(null);
        boolean gone = boxPos != null && mc.world.getBlockState(boxPos).isAir();
        if (gone || t >= 60) { mc.interactionManager.cancelBlockBreaking(); fm(); return; }
        if (boxPos != null) {
            net.minecraft.util.math.Direction face = boxFace != null ? boxFace : net.minecraft.util.math.Direction.DOWN;
            if (t == 0) mc.interactionManager.attackBlock(boxPos, face);
            else mc.interactionManager.updateBlockBreakingProgress(boxPos, face);
        }
    }
    void fm() { mc.options.jumpKey.setPressed(true); if (nRP) { s = S.JUMP; t = 0; } else { s = S.WAIT; t = 0; } }
    void jump() { if (t >= 2) { mc.options.jumpKey.setPressed(false); s = S.REPAIR; t = 0; } }
    void waitStow() { if (t >= 2) { mc.options.jumpKey.setPressed(false); } if (t >= stowDelay.get()) { toggle(); StowElytra se = Modules.get().get(StowElytra.class); if (se != null && !se.isActive()) se.toggle(); } }

    // ── REPAIR ──
    void rp() {
        ItemStack ch = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (ch.getItem() != Items.ELYTRA) { info("无鞘翅"); toggle(); return; }
        float pct = ch.getMaxDamage() > 0 ? (1f - (float) ch.getDamage() / ch.getMaxDamage()) * 100f : 100f;
        if (pct >= 98) { info("修好"); mc.options.jumpKey.setPressed(true); s = S.WAIT; t = 0; return; }
        FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
        if (!xp.found()) { info("无经验瓶"); toggle(); return; }
        mc.player.getInventory().setSelectedSlot(7);
        if (xp.slot() != 7) {
            if (xp.isHotbar()) mc.player.getInventory().setSelectedSlot(xp.slot());
            else InvUtils.quickSwap().fromId(7).to(xp.slot());
        }
        mc.player.setPitch(90);
        if (t == 1) mc.player.jump(); // jump at start of repair
        if (t % xpIV.get() == 0) mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
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

    void info(String m) { System.out.println("[StoreResupply] " + m); if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§9[StoreResupply] " + m), false); }
}
