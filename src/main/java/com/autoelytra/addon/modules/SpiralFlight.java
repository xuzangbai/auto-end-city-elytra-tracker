package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.math.MathHelper;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class SpiralFlight extends Module {
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgCenter   = settings.createGroup("Center");
    private final SettingGroup sgControl  = settings.createGroup("Control");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");
    private final SettingGroup sgRender   = settings.createGroup("Render");

    private final Setting<Double> ringSpacing = sgGeneral.add(new DoubleSetting.Builder()
        .name("ring-spacing-delta r")
        .description("Radius increment per full turn delta r (blocks)")
        .defaultValue(960.0).min(1.0).sliderMax(8192.0)
        .build()
    );

    private final Setting<Boolean> clockwise = sgGeneral.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Visual clockwise if true; visual counterclockwise if false")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useFixedCenter = sgCenter.add(new BoolSetting.Builder()
        .name("use-fixed-center")
        .description("Use a fixed center point instead of resetting on each activation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> centerX = sgCenter.add(new DoubleSetting.Builder()
        .name("center-x")
        .description("Fixed spiral center X coordinate.")
        .defaultValue(0.0).min(-30_000_000).max(30_000_000)
        .visible(useFixedCenter::get)
        .build()
    );

    private final Setting<Double> centerZ = sgCenter.add(new DoubleSetting.Builder()
        .name("center-z")
        .description("Fixed spiral center Z coordinate.")
        .defaultValue(0.0).min(-30_000_000).max(30_000_000)
        .visible(useFixedCenter::get)
        .build()
    );

    private final Setting<Boolean> snapCenter = sgCenter.add(new BoolSetting.Builder()
        .name("snap-to-player")
        .description("Set center-x / center-z to current player coordinates.")
        .defaultValue(false)
        .visible(useFixedCenter::get)
        .build()
    );

    private final Setting<Double> chordLen = sgControl.add(new DoubleSetting.Builder()
        .name("chord-length-s")
        .description("Target chord length s between samples (blocks)")
        .defaultValue(1.5).min(0.1).sliderMax(10.0)
        .build()
    );

    private final Setting<Double> lookahead = sgControl.add(new DoubleSetting.Builder()
        .name("lookahead-l")
        .description("Pure-pursuit lookahead distance l (blocks)")
        .defaultValue(6.0).min(1.0).sliderMax(32.0)
        .build()
    );

    private final Setting<Double> corridor = sgControl.add(new DoubleSetting.Builder()
        .name("corridor-width-w")
        .description("Half corridor width w; inside it a small lead step is applied")
        .defaultValue(8.0).min(1.0).sliderMax(64.0)
        .build()
    );

    private final Setting<Double> yawRateDegPerTick = sgControl.add(new DoubleSetting.Builder()
        .name("max-yaw-rate-deg-per-tick")
        .description("Max yaw change per tick (deg/tick)")
        .defaultValue(10.0).min(0.0).sliderMax(180.0)
        .build()
    );

    private final Setting<Double> leadStepMul = sgControl.add(new DoubleSetting.Builder()
        .name("lead-step-multiplier")
        .description("Lead step multiplier when inside corridor (0 = no lead)")
        .defaultValue(0.5).min(0.0).sliderMax(3.0)
        .build()
    );

    private final Setting<Integer> nearestSamples = sgAdvanced.add(new IntSetting.Builder()
        .name("nearest-samples")
        .description("Samples for nearest-point search")
        .defaultValue(192).min(32).sliderMax(512)
        .build()
    );

    private final Setting<Double> searchWindow = sgAdvanced.add(new DoubleSetting.Builder()
        .name("search-window-rad")
        .description("Search window around current theta (radians)")
        .defaultValue(0.8).min(0.1).sliderMax(3.0)
        .build()
    );

    private final Setting<Integer> refineIters = sgAdvanced.add(new IntSetting.Builder()
        .name("refine-iters")
        .description("Ternary refinement iterations after sampling")
        .defaultValue(3).min(0).sliderMax(8)
        .build()
    );

    private final Setting<Double> maxAdvanceSteps = sgAdvanced.add(new DoubleSetting.Builder()
        .name("max-advance-steps")
        .description("Max per-tick parameter advance in units of |Δθ_step|")
        .defaultValue(3.0).min(0.5).sliderMax(20.0)
        .build()
    );

    private final Setting<Double> dtMin = sgAdvanced.add(new DoubleSetting.Builder()
        .name("dt-min")
        .description("Minimum |Δθ| clamp (radians)")
        .defaultValue(1e-4).min(1e-6).sliderMax(0.01)
        .build()
    );

    private final Setting<Double> dtMax = sgAdvanced.add(new DoubleSetting.Builder()
        .name("dt-max")
        .description("Maximum |Δθ| clamp (radians)")
        .defaultValue(0.25).min(0.01).sliderMax(1.0)
        .build()
    );

    private final Setting<Boolean> renderTrail = sgRender.add(new BoolSetting.Builder()
        .name("render-trail")
        .description("Render the spiral path as connected cubes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> trailColor = sgRender.add(new ColorSetting.Builder()
        .name("trail-color")
        .description("Color of the spiral trail boxes.")
        .defaultValue(new SettingColor(0, 255, 255, 150))
        .visible(renderTrail::get)
        .build()
    );

    private final Setting<Integer> renderChunks = sgRender.add(new IntSetting.Builder()
        .name("render-chunks")
        .description("Render distance around the player (chunks).")
        .defaultValue(10).min(2).sliderMax(32)
        .visible(renderTrail::get)
        .build()
    );

    private final Setting<Double> trailSpacing = sgRender.add(new DoubleSetting.Builder()
        .name("trail-spacing")
        .description("Arc-length spacing between trail boxes — smaller = denser line.")
        .defaultValue(0.3).min(0.1).sliderMax(2.0)
        .visible(renderTrail::get)
        .build()
    );

    private final Setting<Double> trailWidth = sgRender.add(new DoubleSetting.Builder()
        .name("trail-width")
        .description("Half-width of each trail box (blocks).")
        .defaultValue(0.12).min(0.05).sliderMax(0.5)
        .visible(renderTrail::get)
        .build()
    );

    private final Setting<Integer> trailMaxPoints = sgRender.add(new IntSetting.Builder()
        .name("trail-max-points")
        .description("Hard cap on rendered boxes per frame.")
        .defaultValue(5000).min(500).sliderMax(15000)
        .visible(renderTrail::get)
        .build()
    );

    private enum Phase { SPIRAL, DONE }
    private Phase phase;

    private double thetaOnPath;
    private double centerXrt, centerZrt;

    public SpiralFlight() {
        super(AddonTemplate.CATEGORY, "spiral-flight", "Fly an Archimedean spiral.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { toggle(); return; }
        phase = Phase.SPIRAL;

        if (useFixedCenter.get()) {
            centerXrt = centerX.get();
            centerZrt = centerZ.get();
            // Snap to the nearest point on the spiral
            final double B = ringSpacing.get() / (2.0 * Math.PI);
            final boolean cw = clockwise.get();
            double dx = mc.player.getX() - centerXrt;
            double dz = mc.player.getZ() - centerZrt;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double thetaGuess = cw ? (dist / B) : -(dist / B);
            double[] near = nearestOnCurve(thetaGuess, B, centerXrt, centerZrt,
                mc.player.getX(), mc.player.getZ(), cw,
                Math.PI, nearestSamples.get(), refineIters.get());
            thetaOnPath = near[0];
        } else {
            centerXrt = mc.player.getX();
            centerZrt = mc.player.getZ();
            thetaOnPath = 0.0;
        }
    }

    @Override
    public void onDeactivate() {
        phase = Phase.DONE;
        thetaOnPath = 0.0;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Snap center button ──
        if (snapCenter.get()) {
            snapCenter.set(false);
            centerX.set(mc.player.getX());
            centerZ.set(mc.player.getZ());
            // If active with fixed center, immediately update live state
            if (phase == Phase.SPIRAL && useFixedCenter.get()) {
                centerXrt = mc.player.getX();
                centerZrt = mc.player.getZ();
                final double B = ringSpacing.get() / (2.0 * Math.PI);
                final boolean cw = clockwise.get();
                double dx = mc.player.getX() - centerXrt;
                double dz = mc.player.getZ() - centerZrt;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double thetaGuess = cw ? (dist / B) : -(dist / B);
                double[] near = nearestOnCurve(thetaGuess, B, centerXrt, centerZrt,
                    mc.player.getX(), mc.player.getZ(), cw,
                    Math.PI, nearestSamples.get(), refineIters.get());
                thetaOnPath = near[0];
            }
        }

        if (phase != Phase.SPIRAL) return;

        final double qx = mc.player.getX(), qz = mc.player.getZ();
        final double B  = ringSpacing.get() / (2.0 * Math.PI);
        final boolean cwVisual = clockwise.get();

        final double s  = chordLen.get();
        final double L  = lookahead.get();
        final double w  = corridor.get();
        final double dtMinVal = dtMin.get();
        final double dtMaxVal = dtMax.get();

        double tauHere = cwVisual ? +thetaOnPath : -thetaOnPath;
        double rHere = Math.max(0.0, B * tauHere);
        double dthetaMag = s / Math.max(1e-6, Math.sqrt(rHere * rHere + B * B));
        dthetaMag = clampAbs(dthetaMag, dtMinVal, dtMaxVal);
        double dthetaStep = cwVisual ? +dthetaMag : -dthetaMag;

        double[] nearest = nearestOnCurve(thetaOnPath, B, centerXrt, centerZrt, qx, qz, cwVisual,
                searchWindow.get(), nearestSamples.get(), refineIters.get());
        double thetaNearest = nearest[0];
        double distNearest  = nearest[1];

        double maxAdvance = maxAdvanceSteps.get() * Math.abs(dthetaStep);
        thetaOnPath = advanceMonotonic(thetaOnPath, thetaNearest, maxAdvance, cwVisual);

        double thetaBase = thetaOnPath;
        if (distNearest <= w && leadStepMul.get() > 0.0) {
            thetaBase += (cwVisual ? +1.0 : -1.0) * Math.abs(dthetaStep) * leadStepMul.get();
        }

        final double thetaLook = integrateLookahead(thetaBase, L, B, cwVisual, s, dtMinVal, dtMaxVal);
        final double[] pStar = posPolar(thetaLook, B, centerXrt, centerZrt, cwVisual);
        final double yawTargetDeg = Math.toDegrees(Math.atan2(-(pStar[0] - qx), pStar[1] - qz));
        faceYawSmooth((float) yawTargetDeg, yawRateDegPerTick.get().floatValue());
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderTrail.get() || mc.player == null || phase != Phase.SPIRAL) return;

        final double B = ringSpacing.get() / (2.0 * Math.PI);
        final boolean cw = clockwise.get();
        final double maxDist = renderChunks.get() * 16.0;
        final double maxSq = maxDist * maxDist;
        final double px = mc.player.getX();
        final double pz = mc.player.getZ();
        final double py = mc.player.getY() + 1.0;
        final double step = trailSpacing.get();
        final double hw = trailWidth.get();
        final int cap = trailMaxPoints.get();

        SettingColor c = trailColor.get();

        // ── 1. Walk backward from player to find render start ──
        double theta = thetaOnPath;
        double backArc = 0.0;
        while (backArc < maxDist) {
            double prev = theta;
            double r = Math.abs(B * (cw ? theta : -theta));
            double dt = step / Math.max(1e-6, Math.sqrt(r * r + B * B));
            if (dt > 0.1) dt = 0.1;
            theta += cw ? -dt : dt; // step backward
            // Stop at spiral center
            if ((cw && prev > 0 && theta <= 0) || (!cw && prev < 0 && theta >= 0)) { theta = 0.0; break; }
            if ((cw && theta <= 0) || (!cw && theta >= 0)) { theta = 0.0; break; }
            backArc += step;
        }
        double thetaCur = theta;

        // ── 2. Walk forward, render boxes within range ──
        int count = 0;
        double fwdArc = 0.0;

        while (count < cap && fwdArc < backArc + maxDist + step) {
            double[] p = posPolar(thetaCur, B, centerXrt, centerZrt, cw);
            double dx = p[0] - px, dz = p[1] - pz;
            if (dx * dx + dz * dz <= maxSq) {
                event.renderer.box(
                    p[0] - hw, py - hw, p[1] - hw,
                    p[0] + hw, py + hw, p[1] + hw,
                    c, c, ShapeMode.Sides, 0);
            }
            count++;

            // Arc-length step forward
            double r = Math.abs(B * (cw ? thetaCur : -thetaCur));
            double dt = step / Math.max(1e-6, Math.sqrt(r * r + B * B));
            if (dt > 0.1) dt = 0.1;
            thetaCur += cw ? dt : -dt;
            fwdArc += step;
        }
    }

    private static double clampAbs(double v, double minAbs, double maxAbs) {
        double s = Math.signum(v == 0.0 ? 1.0 : v);
        double a = Math.abs(v);
        if (a < minAbs) a = minAbs;
        if (a > maxAbs) a = maxAbs;
        return s * a;
    }

    private static double[] posPolar(double theta, double B, double xc, double zc, boolean cwVisual) {
        double tau = cwVisual ? +theta : -theta;
        double r = Math.max(0.0, B * tau);
        double x = xc + r * Math.cos(theta);
        double z = zc + r * Math.sin(theta);
        return new double[] { x, z };
    }

    private void faceYawSmooth(float yawTargetDeg, float maxDeltaDeg) {
        float yaw = mc.player.getYaw();
        float diff = wrapDeg(yawTargetDeg - yaw);
        if (maxDeltaDeg <= 0f) {
            mc.player.setYaw(yaw + diff);
            return;
        }
        float step = MathHelper.clamp(diff, -maxDeltaDeg, maxDeltaDeg);
        mc.player.setYaw(yaw + step);
    }

    private static float wrapDeg(double deg) {
        float f = (float) deg;
        while (f <= -180.0f) f += 360.0f;
        while (f > 180.0f) f -= 360.0f;
        return f;
    }

    private static double integrateLookahead(double theta0, double L, double B, boolean cwVisual,
                                             double s, double dtMinVal, double dtMaxVal) {
        double acc = 0.0;
        double theta = theta0;
        int guard = 0;
        while (acc < L && guard++ < 4096) {
            double tau = cwVisual ? +theta : -theta;
            double r = Math.max(0.0, B * tau);
            double dthetaMag = s / Math.max(1e-6, Math.sqrt(r * r + B * B));
            dthetaMag = clampAbs(dthetaMag, dtMinVal, dtMaxVal);
            double dtheta = cwVisual ? +dthetaMag : -dthetaMag;
            acc += s;
            theta += dtheta;
        }
        return theta;
    }

    private double[] nearestOnCurve(double thetaCenter, double B,
                                    double xc, double zc, double qx, double qz, boolean cwVisual,
                                    double windowRad, int samples, int refine) {
        double bestTheta = thetaCenter;
        double bestD2 = Double.MAX_VALUE;

        for (int i = 0; i < samples; i++) {
            double t = thetaCenter + (((double) i) / (samples - 1) - 0.5) * 2.0 * windowRad;
            double d2 = dist2AtTheta(t, B, xc, zc, qx, qz, cwVisual);
            if (d2 < bestD2) { bestD2 = d2; bestTheta = t; }
        }

        double left = bestTheta - windowRad / 6.0;
        double right = bestTheta + windowRad / 6.0;
        for (int k = 0; k < refine; k++) {
            double m1 = left + (right - left) / 3.0;
            double m2 = right - (right - left) / 3.0;
            double d1 = dist2AtTheta(m1, B, xc, zc, qx, qz, cwVisual);
            double d2 = dist2AtTheta(m2, B, xc, zc, qx, qz, cwVisual);
            if (d1 < d2) { right = m2; bestTheta = m1; bestD2 = d1; }
            else { left = m1; bestTheta = m2; bestD2 = d2; }
        }
        return new double[] { bestTheta, Math.sqrt(bestD2) };
    }

    private static double dist2AtTheta(double t, double B,
                                       double xc, double zc, double qx, double qz, boolean cwVisual) {
        double[] p = posPolar(t, B, xc, zc, cwVisual);
        double dx = p[0] - qx, dz = p[1] - qz;
        return dx * dx + dz * dz;
    }

    private static double advanceMonotonic(double current, double target, double maxStep, boolean cwVisual) {
        if (cwVisual) {
            if (target < current) target = current;
            double adv = Math.min(target - current, maxStep);
            if (adv < 0) adv = 0;
            return current + adv;
        } else {
            if (target > current) target = current;
            double adv = Math.min(current - target, maxStep);
            if (adv < 0) adv = 0;
            return current - adv;
        }
    }
}