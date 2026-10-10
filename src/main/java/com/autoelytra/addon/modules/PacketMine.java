package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import com.autoelytra.addon.mixin.ClientWorldAccessor;
import com.autoelytra.addon.mixin.PlayerInventoryAccessor;
import com.autoelytra.addon.utils.InventoryUtils;
import com.autoelytra.addon.utils.InventoryUtils.MineSwitchMode;
import com.autoelytra.addon.utils.Timer;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.TimerTask;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PacketMine extends Module {
    public static PacketMine INSTANCE;

    public PacketMine() {
        super(AddonTemplate.CATEGORY, "packet-mine",
            "Packet-based block mining with auto-tool-switch and progress rendering.");
        INSTANCE = this;
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> usingPause = sgGeneral.add(new BoolSetting.Builder()
        .name("UsingPause")
        .description("Pause mining while using items")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> onlyMain = sgGeneral.add(new BoolSetting.Builder()
        .name("OnlyMain")
        .description("Only check main hand for pause")
        .defaultValue(true)
        .visible(usingPause::get)
        .build()
    );
    public final Setting<MineSwitchMode> autoSwitch = sgGeneral.add(new EnumSetting.Builder<MineSwitchMode>()
        .name("AutoSwitch")
        .description("Auto-switch to best tool")
        .defaultValue(MineSwitchMode.Silent)
        .build()
    );
    public final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("Range")
        .description("Operation range")
        .defaultValue(6).min(0).sliderMax(12)
        .build()
    );
    public final Setting<Integer> maxBreaks = sgGeneral.add(new IntSetting.Builder()
        .name("TryBreakTime")
        .description("Max break attempts")
        .defaultValue(6).min(0).sliderMax(10)
        .build()
    );
    private final Setting<Boolean> farCancel = sgGeneral.add(new BoolSetting.Builder()
        .name("FarCancel")
        .description("Cancel if too far")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("SwingHand")
        .description("Swing hand animation")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> instantMine = sgGeneral.add(new BoolSetting.Builder()
        .name("InstantMine")
        .description("Instant mine mode")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> instantDelay = sgGeneral.add(new IntSetting.Builder()
        .name("InstantDelay")
        .description("Instant mine delay (ms)")
        .defaultValue(10).min(0).sliderMax(1000)
        .build()
    );
    private final Setting<Boolean> fastBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("FastBypass")
        .description("Fast mine bypass")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> doubleBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("DoubleBreak")
        .description("Double break mode")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> checkGround = sgGeneral.add(new BoolSetting.Builder()
        .name("CheckGround")
        .description("Check if on ground")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> bypassGround = sgGeneral.add(new BoolSetting.Builder()
        .name("BypassGround")
        .description("Air-mine bypass")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> switchDamage = sgGeneral.add(new IntSetting.Builder()
        .name("SwitchDamage")
        .description("Progress threshold for tool switch")
        .defaultValue(95).min(0).sliderMax(100)
        .build()
    );
    private final Setting<Integer> switchTime = sgGeneral.add(new IntSetting.Builder()
        .name("SwitchTime")
        .description("Tool hold time (ms)")
        .defaultValue(100).min(0).sliderMax(1000)
        .build()
    );
    public final Setting<Integer> mineDelay = sgGeneral.add(new IntSetting.Builder()
        .name("MineDelay")
        .description("Mine selection delay (ms)")
        .defaultValue(300).min(0).sliderMax(1000)
        .build()
    );
    private final Setting<Integer> packetDelay = sgGeneral.add(new IntSetting.Builder()
        .name("PacketDelay")
        .description("Bypass packet delay (ms)")
        .defaultValue(0).min(0).sliderMax(1000)
        .build()
    );
    private final Setting<Double> mineDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("Damage")
        .description("Total mining progress multiplier")
        .defaultValue(0.8).sliderMax(2.0)
        .build()
    );
    private final Setting<Double> animationExp = sgRender.add(new DoubleSetting.Builder()
        .name("Animation Exponent")
        .defaultValue(3).range(0, 10).sliderRange(0, 10)
        .build()
    );
    private final Setting<Boolean> renderProgress = sgRender.add(new BoolSetting.Builder()
        .name("RenderProgress")
        .description("Render progress text")
        .defaultValue(true)
        .build()
    );
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("TargetColor")
        .description("Primary text color")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );
    private final Setting<SettingColor> secondColor = sgRender.add(new ColorSetting.Builder()
        .name("SecondColor")
        .description("Secondary text color")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("ShapeMode")
        .defaultValue(ShapeMode.Both)
        .build()
    );
    private final Setting<SettingColor> sideStartColor = sgRender.add(new ColorSetting.Builder()
        .name("SideStart").defaultValue(new SettingColor(255, 255, 255, 0)).build()
    );
    private final Setting<SettingColor> sideEndColor = sgRender.add(new ColorSetting.Builder()
        .name("SideEnd").defaultValue(new SettingColor(255, 255, 255, 50)).build()
    );
    private final Setting<SettingColor> lineStartColor = sgRender.add(new ColorSetting.Builder()
        .name("LineStart").defaultValue(new SettingColor(255, 255, 255, 0)).build()
    );
    private final Setting<SettingColor> lineEndColor = sgRender.add(new ColorSetting.Builder()
        .name("LineEnd").defaultValue(new SettingColor(255, 255, 255, 255)).build()
    );
    private final Setting<SettingColor> secondSideStartColor = sgRender.add(new ColorSetting.Builder()
        .name("SecondSideStart").defaultValue(new SettingColor(255, 255, 255, 0)).build()
    );
    private final Setting<SettingColor> secondSideEndColor = sgRender.add(new ColorSetting.Builder()
        .name("SecondSideEnd").defaultValue(new SettingColor(255, 255, 255, 50)).build()
    );
    private final Setting<SettingColor> secondLineStartColor = sgRender.add(new ColorSetting.Builder()
        .name("SecondLineStart").defaultValue(new SettingColor(255, 255, 255, 0)).build()
    );
    private final Setting<SettingColor> secondLineEndColor = sgRender.add(new ColorSetting.Builder()
        .name("SecondLineEnd").defaultValue(new SettingColor(255, 255, 255, 255)).build()
    );

    // ── State ──────────────────────────────────────────────────────────

    public static BlockPos selfClickPos;
    public static int maxBreaksCount;
    public static int publicProgress, secondPublicProgress;
    public static boolean completed;
    public static BlockPos targetPos, secondPos;
    private static float progress, secondProgress;
    private long lastTime, secondLastTime;
    private static boolean started, secondStarted;
    private double render = 1, secondRender = 1;
    private int oldSlot = -1;
    private final Timer timer = new Timer();
    public final Timer mineTimer = new Timer();
    private final Timer instantTimer = new Timer();
    private boolean hasSwitch, secondHasSwitch;

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        if (hasSwitch) {
            InventoryUtils.switchToSlot(oldSlot);
            hasSwitch = false;
        }
        if (secondHasSwitch) {
            InventoryUtils.switchToSlot(oldSlot);
            secondHasSwitch = false;
        }
    }

    private void reset() {
        maxBreaksCount = 0;
        hasSwitch = false;
        secondHasSwitch = false;
        mineTimer.setMs(999999);
        instantTimer.setMs(999999);
        timer.setMs(999999);
        targetPos = null;
        secondPos = null;
        started = false;
        secondStarted = false;
        publicProgress = 0;
        secondPublicProgress = 0;
        progress = 0;
        secondProgress = 0;
        lastTime = System.currentTimeMillis();
        secondLastTime = System.currentTimeMillis();
        render = 1;
        secondRender = 1;
        completed = false;
        selfClickPos = null;
    }

    // ── Events ─────────────────────────────────────────────────────────

    @EventHandler
    public void onBreak(PacketEvent.Send event) {
        if (event.packet instanceof PlayerActionC2SPacket packet) {
            if (packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) mineTimer.reset();
        }
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!BlockUtils.canBreak(event.blockPos)) return;
        event.cancel();
        if (mineDelay.get() > 0 && !mineTimer.passedMs(mineDelay.get()) && completed) {
            targetPos = null;
            publicProgress = 0;
            started = false;
            progress = 0;
            completed = false;
            return;
        }
        if (!mineTimer.passedMs(mineDelay.get())) return;
        selfClickPos = event.blockPos;
        mine(event.blockPos);
    }

    public void mine(BlockPos pos) {
        maxBreaksCount = 0;
        if (doubleBreak.get()) {
            if (targetPos != null && secondPos == null && !targetPos.equals(pos)) {
                if (completed) {
                    targetPos = pos;
                    secondStarted = false;
                    secondProgress = 0;
                    secondPublicProgress = 0;
                    publicProgress = 0;
                    started = false;
                    progress = 0;
                    completed = false;
                } else {
                    secondPos = targetPos;
                    targetPos = pos;
                    secondStarted = false;
                    secondProgress = 0;
                    secondPublicProgress = 0;
                    started = false;
                }
            } else if (targetPos == null || !targetPos.equals(pos)) {
                publicProgress = 0;
                targetPos = pos;
                started = false;
                progress = 0;
                completed = false;
            }
        } else {
            if (!pos.equals(targetPos)) {
                publicProgress = 0;
                targetPos = pos;
                started = false;
                progress = 0;
                completed = false;
            }
        }
        mineTimer.reset();
    }

    @Override
    public String getInfoString() {
        if (mc.world == null || mc.player == null || targetPos == null) return null;
        double max = getMineTicks(getTool(targetPos));
        if (progress >= max * mineDamage.get()) return "§f[100%]";
        return "§f[" + publicProgress + "%]";
    }

    // ── Render3DEvent ──────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        if ((targetPos == null || completed) && secondPos == null) selfClickPos = null;
        if (publicProgress >= 100) {
            if (!instantMine.get()) targetPos = null;
        }
        if (secondPublicProgress >= 100) secondPos = null;

        if (timer.passedMs(switchTime.get()) && hasSwitch && autoSwitch.get() != MineSwitchMode.None) {
            if (autoSwitch.get() == MineSwitchMode.Delay) InventoryUtils.switchToSlot(oldSlot);
            if (autoSwitch.get() == MineSwitchMode.Silent) InventoryUtils.sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
            hasSwitch = false;
        }
        if (maxBreaksCount >= maxBreaks.get() * 10) {
            maxBreaksCount = 0;
            targetPos = null;
            secondPos = null;
        }

        // ── Second pos (double break) ──
        if (secondPos != null && doubleBreak.get()) {
            if (farCancel.get() && Math.sqrt(mc.player.getEyePos().squaredDistanceTo(secondPos.toCenterPos())) > range.get()) {
                secondPos = null;
                return;
            }
            double secondMax = getMineTicks2(getTool(secondPos));
            double secondDelta = (System.currentTimeMillis() - secondLastTime) / 1000d;
            secondPublicProgress = (int) (secondProgress / (secondMax * mineDamage.get()) * 100);
            secondLastTime = System.currentTimeMillis();
            if (!secondStarted) {
                sendStart(secondPos);
                secondStarted = true;
                secondProgress = 0;
                return;
            }
            double secondDamage = mineDamage.get();
            if (!checkGround.get() || mc.player.isOnGround()) {
                secondProgress += secondDelta * 20;
            } else {
                secondProgress += secondDelta * 4;
            }
            renderSecondAnimation(event, secondDelta, secondDamage);
            if (secondProgress >= secondMax * secondDamage) {
                countMaxBreaks(secondPos);
                sendStopSecond();
            }
        }

        // ── Double break tool switch ──
        if (doubleBreak.get()) {
            if (!usingPause.get() || !checkPause(onlyMain.get())) {
                if ((secondPublicProgress >= switchDamage.get() || publicProgress >= switchDamage.get())
                    && !hasSwitch && secondPos != null) {
                    int bestSlot = getTool(secondPos);
                    if (!hasSwitch) oldSlot = ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
                    if (autoSwitch.get() != MineSwitchMode.None && bestSlot != -1) {
                        if (autoSwitch.get() == MineSwitchMode.Delay) InventoryUtils.switchToSlot(bestSlot);
                        if (autoSwitch.get() == MineSwitchMode.Silent) InventoryUtils.sendPacket(new UpdateSelectedSlotC2SPacket(bestSlot));
                        timer.reset();
                        hasSwitch = true;
                    }
                }
            }
        }

        // ── Primary target ──
        if (targetPos != null) {
            if (farCancel.get() && Math.sqrt(mc.player.getEyePos().squaredDistanceTo(targetPos.toCenterPos())) > range.get()) {
                targetPos = null;
                return;
            }
            double max = getMineTicks(getTool(targetPos));
            publicProgress = (int) (progress / (max * mineDamage.get()) * 100);
            if (progress >= max * mineDamage.get() && completed) {
                countMaxBreaks(targetPos);
            }
            if (instantMine.get() && completed) {
                Color side = getColor(sideStartColor.get(), sideEndColor.get(), 1);
                Color line = getColor(lineStartColor.get(), lineEndColor.get(), 1);
                event.renderer.box(new Box(targetPos), side, line, shapeMode.get(), 0);
                if (!mc.world.isAir(targetPos) && !mc.world.getBlockState(targetPos).isReplaceable()
                    && instantTimer.passedMs(instantDelay.get())) {
                    sendStop();
                    instantTimer.reset();
                }
                return;
            }
            double delta = (System.currentTimeMillis() - lastTime) / 1000d;
            lastTime = System.currentTimeMillis();
            if (!started) {
                sendStart(targetPos);
                return;
            }
            double damage = mineDamage.get();
            if (!checkGround.get() || mc.player.isOnGround()) {
                progress += delta * 20;
            } else {
                progress += delta * 4;
            }
            renderAnimation(event, delta, damage);
            if (progress >= max * damage) {
                if (!instantMine.get()) sendStop();
                completed = true;
                if (!instantMine.get() && (!doubleBreak.get() || secondPos == null)) targetPos = null;
            }
        }
    }

    private void countMaxBreaks(BlockPos pos) {
        if (isAir(pos) || mc.world.getBlockState(pos).isReplaceable()) {
            maxBreaksCount = 0;
            return;
        }
        if (usingPause.get() && checkPause(onlyMain.get())) return;
        maxBreaksCount++;
    }

    // ── Packet sending ─────────────────────────────────────────────────

    private void sendStart(BlockPos pos) {
        sendSequencedPacket(id -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, getClickSide(pos), id));
        if (fastBypass.get()) {
            BlockPos bypassPos = new BlockPos(0, -999, 0);
            sendSequencedPacket(id -> new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, bypassPos, Direction.UP, id));
        }
        if (doubleBreak.get()) {
            long d = packetDelay.get();
            java.util.Timer delayTimer = new java.util.Timer();
            delayTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    mc.execute(() -> sendSequencedPacket(id -> new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, getClickSide(pos), id)));
                    delayTimer.cancel();
                }
            }, d);
        }
        mc.player.swingHand(Hand.MAIN_HAND);
        if (pos.equals(targetPos)) {
            started = true;
            progress = 0;
        } else {
            secondStarted = true;
            secondProgress = 0;
        }
    }

    private void sendStop() {
        if (usingPause.get() && checkPause(onlyMain.get())) return;
        int bestSlot = getTool(targetPos);
        if (!doubleBreak.get() || secondPos == null) {
            if (!hasSwitch) oldSlot = ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
            if (autoSwitch.get() != MineSwitchMode.None && bestSlot != -1) {
                if (autoSwitch.get() == MineSwitchMode.Delay) InventoryUtils.switchToSlot(bestSlot);
                if (autoSwitch.get() == MineSwitchMode.Silent) InventoryUtils.sendPacket(new UpdateSelectedSlotC2SPacket(bestSlot));
                timer.reset();
                hasSwitch = true;
            }
        }
        // 1.21.11: isFallFlying() → isGliding()
        if (bypassGround.get() && !mc.player.isGliding() && targetPos != null
            && !isAir(targetPos) && !mc.player.isOnGround()) {
            // 1.21.11: add horizontalCollision 7th param
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                mc.player.getX(), mc.player.getY() + 1.0e-9, mc.player.getZ(),
                mc.player.getYaw(), mc.player.getPitch(), true, mc.player.horizontalCollision));
            mc.player.onLanding();
        }
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        sendSequencedPacket(id -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, getClickSide(targetPos), id));
    }

    private void sendStopSecond() {
        if (bypassGround.get() && !mc.player.isGliding() && secondPos != null
            && !isAir(secondPos) && !mc.player.isOnGround()) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                mc.player.getX(), mc.player.getY() + 1.0e-9, mc.player.getZ(),
                mc.player.getYaw(), mc.player.getPitch(), true, mc.player.horizontalCollision));
            mc.player.onLanding();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private boolean isAir(BlockPos pos) {
        return mc.world.isAir(pos)
            || (mc.world.getBlockState(pos).getBlock() == Blocks.FIRE && hasCrystal(pos));
    }

    private boolean hasCrystal(BlockPos pos) {
        return !mc.world.getEntitiesByClass(EndCrystalEntity.class,
            new Box(pos), e -> true).isEmpty();
    }

    private float getMineTicks(int slot) {
        if (targetPos == null || mc.world == null || mc.player == null) return 20;
        return calcMineTicks(mc.world.getBlockState(targetPos), targetPos, slot);
    }

    private float getMineTicks2(int slot) {
        if (secondPos == null || mc.world == null || mc.player == null) return 20;
        return calcMineTicks(mc.world.getBlockState(secondPos), secondPos, slot);
    }

    private float calcMineTicks(BlockState state, BlockPos pos, int slot) {
        float hardness = state.getHardness(mc.world, pos);
        if (hardness < 0) return Float.MAX_VALUE;
        if (hardness == 0) return 1;
        ItemStack stack = slot == -1 ? ItemStack.EMPTY : mc.player.getInventory().getStack(slot);
        boolean canHarvest = stack.isSuitableFor(state);
        float speed = stack.getMiningSpeedMultiplier(state);
        int efficiency = InventoryUtils.getEnchantmentLevel(stack, Enchantments.EFFICIENCY);
        if (efficiency > 0 && speed > 1.0f) {
            speed += efficiency * efficiency + 1;
        }
        if (mc.player.hasStatusEffect(StatusEffects.HASTE)) {
            int amp = mc.player.getStatusEffect(StatusEffects.HASTE).getAmplifier();
            speed *= 1.0f + (amp + 1) * 0.2f;
        }
        if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            int amp = mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier();
            speed *= switch (amp) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
        }
        float damage = speed / hardness / (canHarvest ? 30f : 100f);
        if (damage <= 0) return Float.MAX_VALUE;
        return 1f / damage;
    }

    // ── Render ─────────────────────────────────────────────────────────

    private void renderAnimation(Render3DEvent event, double delta, double damage) {
        render = MathHelper.clamp(render + delta * 2, -2, 2);
        double max = getMineTicks(getTool(targetPos));
        double p = 1 - MathHelper.clamp(progress / (max * damage), 0, 1);
        p = Math.pow(p, animationExp.get());
        p = 1 - p;
        double size = p / 2;
        Box box = new Box(
            targetPos.getX() + 0.5 - size, targetPos.getY() + 0.5 - size, targetPos.getZ() + 0.5 - size,
            targetPos.getX() + 0.5 + size, targetPos.getY() + 0.5 + size, targetPos.getZ() + 0.5 + size);
        event.renderer.box(box, getColor(sideStartColor.get(), sideEndColor.get(), p),
            getColor(lineStartColor.get(), lineEndColor.get(), p), shapeMode.get(), 0);
    }

    private void renderSecondAnimation(Render3DEvent event, double delta, double damage) {
        secondRender = MathHelper.clamp(secondRender + delta * 2, -2, 2);
        double max = getMineTicks2(getTool(secondPos));
        double p = 1 - MathHelper.clamp(secondProgress / (max * damage), 0, 1);
        p = Math.pow(p, animationExp.get());
        p = 1 - p;
        double size = p / 2;
        Box box = new Box(
            secondPos.getX() + 0.5 - size, secondPos.getY() + 0.5 - size, secondPos.getZ() + 0.5 - size,
            secondPos.getX() + 0.5 + size, secondPos.getY() + 0.5 + size, secondPos.getZ() + 0.5 + size);
        event.renderer.box(box, getColor(secondSideStartColor.get(), secondSideEndColor.get(), p),
            getColor(secondLineStartColor.get(), secondLineEndColor.get(), p), shapeMode.get(), 0);
    }

    private Color getColor(Color start, Color end, double progress) {
        return new Color(
            lerp(start.r, end.r, progress), lerp(start.g, end.g, progress),
            lerp(start.b, end.b, progress), lerp(start.a, end.a, progress));
    }

    private int lerp(double start, double end, double d) {
        return (int) Math.round(start + (end - start) * d);
    }

    private int getTool(BlockPos pos) {
        int index = -1;
        float best = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                float digSpeed = InventoryUtils.getEnchantmentLevel(stack, Enchantments.EFFICIENCY);
                float destroySpeed = stack.getMiningSpeedMultiplier(mc.world.getBlockState(pos));
                if (digSpeed + destroySpeed > best) {
                    best = digSpeed + destroySpeed;
                    index = i;
                }
            }
        }
        return index;
    }

    public void sendSequencedPacket(SequencedPacketCreator packetCreator) {
        if (mc.getNetworkHandler() == null || mc.world == null) return;
        try (PendingUpdateManager pum =
                ((ClientWorldAccessor) mc.world).invokeGetPendingUpdateManager().incrementSequence()) {
            int i = pum.getSequence();
            mc.getNetworkHandler().sendPacket(packetCreator.predict(i));
        }
    }

    /** Get the best visible face direction to click a block. */
    private Direction getClickSide(BlockPos pos) {
        Direction side = null;
        double range = 100;
        for (Direction i : Direction.values()) {
            if (!canSee(pos, i)) continue;
            double dis = MathHelper.sqrt((float) mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos()));
            if (dis > range) continue;
            side = i;
            range = dis;
        }
        if (side != null) return side;
        side = Direction.UP;
        range = 100;
        for (Direction i : Direction.values()) {
            double dis = MathHelper.sqrt((float) mc.player.getEyePos().squaredDistanceTo(pos.offset(i).toCenterPos()));
            if (dis > range) continue;
            side = i;
            range = dis;
        }
        return side;
    }

    /** True if the given face of the block is reachable by line of sight. */
    private boolean canSee(BlockPos pos, Direction side) {
        Vec3d testVec = pos.toCenterPos().add(
            side.getVector().getX() * 0.5, side.getVector().getY() * 0.5, side.getVector().getZ() * 0.5);
        HitResult result = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(), testVec, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return result == null || result.getType() == HitResult.Type.MISS;
    }

    public boolean checkPause(boolean onlyMain) {
        return mc.options.useKey.isPressed()
            && (!onlyMain || mc.player.getActiveHand() == Hand.MAIN_HAND);
    }
}
