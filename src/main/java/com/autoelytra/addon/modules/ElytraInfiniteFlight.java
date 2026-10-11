package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Elytra infinite flight — 2-phase speed-based bounce.
 *
 * <ol>
 *   <li><b>Dive +33°</b> — build speed to 40 m/s.</li>
 *   <li><b>Pull-up −49°</b> — convert speed to altitude, then slowly descend
 *       (pitch transitions back to +33° at a slow rate).</li>
 * </ol>
 *
 * <p>When the player reaches {@code target-height}, this module toggles itself off
 * and activates Meteor's built-in ElytraFly in Pitch40 mode for altitude cruising.</p>
 */
public class ElytraInfiniteFlight extends Module {
    private enum Phase { DIVE, PULL_UP, GLIDE }

    // ── Settings ───────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Transition Speed");

    private final Setting<Double> diveAngle = sgGeneral.add(new DoubleSetting.Builder()
        .name("dive-angle")
        .description("Target pitch (positive = down) while diving.")
        .defaultValue(33.0).min(10.0).max(60.0)
        .build()
    );

    private final Setting<Double> diveTargetSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("dive-target-speed")
        .description("Horizontal speed (m/s) to reach before pulling up.")
        .defaultValue(40.0).min(25.0).max(60.0)
        .build()
    );

    private final Setting<Double> pullUpAngle = sgGeneral.add(new DoubleSetting.Builder()
        .name("pull-up-angle")
        .description("Target pitch (negative = up) while pulling up.")
        .defaultValue(-49.0).min(-70.0).max(-20.0)
        .build()
    );

    private final Setting<Double> pullUpStallSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("pull-up-stall-speed")
        .description("Horizontal speed (m/s) floor — when reached, transition back to dive.")
        .defaultValue(18.0).min(8.0).max(30.0)
        .build()
    );

    private final Setting<Double> targetHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-height")
        .description("When the player reaches this Y level, switch to ElytraFly Pitch40 and disable this module.")
        .defaultValue(1250).min(64.0).max(1500.0)
        .build()
    );

    private final Setting<Boolean> enablePitch40 = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-elytrafly-pitch40")
        .description("到达目标高度后：开启=关闭本功能并开启彗星本体的 ElytraFly(Pitch40)；关闭=不碰彗星，本功能继续")
        .defaultValue(false)
        .build()
    );

    // ── Glide mode ─────────────────────────────────────────────────────

    private final Setting<Boolean> glideMode = sgGeneral.add(new BoolSetting.Builder()
        .name("glide-mode")
        .description("滑翔模式：高度达到滑翔起始高度时停止无限飞行，改为固定角度向下滑翔，降到滑翔结束高度后恢复无限飞行")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> glideAngle = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-angle")
        .description("滑翔时的俯仰角（正=向下）")
        .defaultValue(11.0).min(1.0).max(45.0)
        .visible(glideMode::get)
        .build()
    );

    private final Setting<Double> glideStartHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-start-height")
        .description("升到这个高度时开始滑翔")
        .defaultValue(450).min(64.0).max(1500.0)
        .visible(glideMode::get)
        .build()
    );

    private final Setting<Double> glideEndHeight = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide-end-height")
        .description("滑翔降到这个高度时恢复无限飞行")
        .defaultValue(220).min(0.0).max(1500.0)
        .visible(glideMode::get)
        .build()
    );

    // ── Asymmetric smoothing ───────────────────────────────────────────

    private final Setting<Double> diveTransitionSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("dive-transition-speed")
        .description("Pitch change rate when returning to dive (deg/tick). Lower = slower descent.")
        .defaultValue(1.5).min(0.3).max(8.0)
        .build()
    );

    private final Setting<Double> pullUpTransitionSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("pull-up-transition-speed")
        .description("Pitch change rate when snapping up (deg/tick). Higher = snappier pull-up.")
        .defaultValue(7.0).min(1.0).max(30.0)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────

    private Phase phase = Phase.DIVE;
    private float currentPitch;
    private boolean heightReached;

    public ElytraInfiniteFlight() {
        super(AddonTemplate.CATEGORY, "elytra-infinite-flight",
            "Elytra infinite flight → reaches target height → auto-switch to ElytraFly Pitch40.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            phase = Phase.DIVE;
            currentPitch = mc.player.getPitch();
            heightReached = false;
        }
    }

    private double horizontalSpeed() {
        Vec3d v = mc.player.getVelocity();
        return Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
    }

    // ── Tick ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        double y = mc.player.getY();

        // ── Glide mode ─────────────────────────────────────────────────
        // 升到起始高度 → 停止无限飞行，改为固定角度向下滑翔
        // 降到结束高度 → 退出滑翔，恢复无限飞行
        if (glideMode.get()) {
            if (phase != Phase.GLIDE && y >= glideStartHeight.get()) {
                phase = Phase.GLIDE;
            } else if (phase == Phase.GLIDE && y <= glideEndHeight.get()) {
                phase = Phase.DIVE;
                heightReached = false;      // 允许之后再触发一次 Pitch40 切换
            }
        } else if (phase == Phase.GLIDE) {
            phase = Phase.DIVE;              // 关掉滑翔模式时立刻恢复
        }

        // ── Height trigger: switch to ElytraFly Pitch40 ────────────────
        if (!heightReached && y >= targetHeight.get()) {
            heightReached = true;
            switchToPitch40();      // 只做一次；本功能是否继续取决于开关
        }

        double hSpeed = horizontalSpeed();

        switch (phase) {
            case GLIDE -> {
                // 固定角度向下滑翔，不参与提速/拉升循环
                smoothPitch(glideAngle.get().floatValue(), pullUpTransitionSpeed.get().floatValue());
            }
            case DIVE -> {
                smoothPitch(diveAngle.get().floatValue(), diveTransitionSpeed.get().floatValue());
                if (hSpeed >= diveTargetSpeed.get()) {
                    phase = Phase.PULL_UP;
                }
            }
            case PULL_UP -> {
                smoothPitch(pullUpAngle.get().floatValue(), pullUpTransitionSpeed.get().floatValue());
                if (hSpeed <= pullUpStallSpeed.get()) {
                    phase = Phase.DIVE;
                }
            }
        }
    }

    // ── Switch to Meteor ElytraFly Pitch40 ─────────────────────────────

    private void switchToPitch40() {
        // 开关关闭时：什么都不做（不开启彗星 ElytraFly，也不关闭本功能），本功能继续跑
        if (!enablePitch40.get()) {
            if (mc.player != null) {
                mc.player.sendMessage(
                    Text.literal("§a[ElytraInfiniteFlight] 达到预设高度（未开启彗星 ElytraFly，本功能继续）"),
                    false
                );
            }
            return;
        }

        // 开关打开时：关闭本功能，改为开启彗星本体的 ElytraFly（Pitch40 模式）
        this.toggle();

        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        elytraFly.flightMode.set(ElytraFlightModes.Pitch40);
        if (!elytraFly.isActive()) {
            elytraFly.toggle();
        }

        // Chat notification
        if (mc.player != null) {
            mc.player.sendMessage(
                Text.literal("§a[ElytraInfiniteFlight] 达到预设高度，开启彗星高度pitch40高度巡航"),
                false
            );
        }
    }

    // ── Smooth pitch helper ────────────────────────────────────────────

    private void smoothPitch(float target, float speed) {
        float diff = target - currentPitch;
        float step = MathHelper.clamp(diff, -speed, speed);
        currentPitch += step;
        mc.player.setPitch(currentPitch);
    }
}
