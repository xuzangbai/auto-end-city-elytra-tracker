package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WaypointLock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-yaw")
        .description("锁定水平视角到目标路径点")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-pitch")
        .description("锁定垂直视角为水平方向")
        .defaultValue(false)
        .build()
    );

    private Vec3d prevTarget = null;

    public WaypointLock() {
        super(AddonTemplate.CATEGORY, "waypoint-lock",
            "锁定水平视角到WaypointReader路线中的下一个路径点");
    }

    @Override
    public void onActivate() {
        prevTarget = null;
    }

    @Override
    public void onDeactivate() {
        prevTarget = null;
    }

    /** Compute the locked yaw, or null if lock is not active. */
    private Float computeYaw() {
        if (mc.player == null || !lockYaw.get()) return null;

        WaypointReader reader = Modules.get().get(WaypointReader.class);
        if (reader == null || !reader.isActive()) return null;

        List<Vec3d> route = reader.getRoute();
        if (route.isEmpty()) return null;

        Vec3d target = route.get(0);
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        Float yaw = computeYaw();
        if (yaw == null) return;
        mc.player.setYaw(yaw);
        if (lockPitch.get()) mc.player.setPitch(0);
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        Float yaw = computeYaw();
        if (yaw == null) return;

        mc.player.setYaw(yaw);
        if (lockPitch.get()) mc.player.setPitch(0);

        // Notify on target change
        WaypointReader reader = Modules.get().get(WaypointReader.class);
        if (reader != null && reader.isActive()) {
            List<Vec3d> route = reader.getRoute();
            if (!route.isEmpty()) {
                Vec3d target = route.get(0);
                if (prevTarget == null || (int) prevTarget.x != (int) target.x || (int) prevTarget.z != (int) target.z) {
                    double dx = target.x - mc.player.getX();
                    double dz = target.z - mc.player.getZ();
                    info("锁定: " + (int) target.x + "," + (int) target.z
                        + "  距离: " + (int) Math.sqrt(dx * dx + dz * dz) + "m");
                    prevTarget = target;
                }
            }
        }
    }

    private void info(String msg) {
        System.out.println("[WaypointLock] " + msg);
        if (mc != null && mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§6[WaypointLock] " + msg), false);
    }
}
