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
    private enum Phase { DIVE, PULL_UP }

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
        .defaultValue(200.0).min(64.0).max(1000.0)
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

        // ── Height trigger: switch to ElytraFly Pitch40 ────────────────
        if (!heightReached && mc.player.getY() >= targetHeight.get()) {
            heightReached = true;
            switchToPitch40();
            return;
        }

        double hSpeed = horizontalSpeed();

        switch (phase) {
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
        // Disable this module
        this.toggle();

        // Enable Meteor's built-in ElytraFly with Pitch40 mode
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
