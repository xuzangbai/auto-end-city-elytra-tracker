package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WaypointReader extends Module {
    private static final Path LUJD_DIR = Paths.get("lujd");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgBeam = settings.createGroup("Beam");

    private final Setting<Boolean> useCustomPath = sgGeneral.add(new BoolSetting.Builder()
        .name("use-custom-path")
        .description("使用自定义文件路径读取")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> customPath = sgGeneral.add(new StringSetting.Builder()
        .name("custom-path")
        .description("自定义路径点文件路径")
        .defaultValue("xaero/minimap/")
        .visible(useCustomPath::get)
        .build()
    );

    private final Setting<Boolean> readXaero = sgGeneral.add(new BoolSetting.Builder()
        .name("read-xaero")
        .description("读取路径点到 lujd/<世界>/modi.json")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearCache = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-cache")
        .description("删除 lujd 全部缓存")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showRoute = sgGeneral.add(new BoolSetting.Builder()
        .name("show-route")
        .description("渲染最近邻路径线")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> renderChunks = sgRender.add(new IntSetting.Builder()
        .name("render-chunks")
        .description("渲染距离(区块)")
        .defaultValue(10).min(1).sliderMax(32)
        .build()
    );

    private final Setting<Integer> arrivalRange = sgGeneral.add(new IntSetting.Builder()
        .name("arrival-range")
        .description("到达此范围(格)内标记路径点为已访问")
        .defaultValue(80).min(10).sliderMax(500)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("路线颜色")
        .defaultValue(new SettingColor(0, 255, 255, 180))
        .build()
    );

    private final Setting<Boolean> showBeam = sgBeam.add(new BoolSetting.Builder()
        .name("show-beam")
        .description("在路径点位置渲染信标光柱")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> beamColor = sgBeam.add(new ColorSetting.Builder()
        .name("beam-color")
        .description("光柱颜色")
        .defaultValue(new SettingColor(255, 200, 50, 120))
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Double> beamWidth = sgBeam.add(new DoubleSetting.Builder()
        .name("beam-width")
        .description("光柱宽度")
        .defaultValue(0.3).min(0.1).max(2.0)
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Double> beamMinY = sgBeam.add(new DoubleSetting.Builder()
        .name("beam-min-y")
        .description("光柱最低Y坐标")
        .defaultValue(-64).min(-128).max(320)
        .visible(showBeam::get)
        .build()
    );

    private final Setting<Double> beamMaxY = sgBeam.add(new DoubleSetting.Builder()
        .name("beam-max-y")
        .description("光柱最高Y坐标")
        .defaultValue(320).min(-128).max(512)
        .visible(showBeam::get)
        .build()
    );

    private final Set<Long> visitedXZ = new HashSet<>();
    private List<Vec3d> route = Collections.emptyList();
    private boolean routeDirty = true;
    private String loadedWorld = null;

    /** Exposed for WaypointLock to read the computed nearest-neighbor route. */
    public List<Vec3d> getRoute() { return route; }

    public WaypointReader() {
        super(AddonTemplate.CATEGORY, "waypoint-reader",
            "Xaero路径点读取 + 最近邻路线渲染");
    }

    @Override
    public void onActivate() {
        try { Files.createDirectories(LUJD_DIR); } catch (IOException ignored) {}
        route = Collections.emptyList();
        routeDirty = true;
        visitedXZ.clear();
        loadedWorld = detectWorld();
        if (loadedWorld != null) loadVisited();
    }

    @Override
    public void onDeactivate() {
        saveVisited();
        visitedXZ.clear();
        route = Collections.emptyList();
        routeDirty = true;
        loadedWorld = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (readXaero.get()) { readXaero.set(false); doRead(); routeDirty = true; }
        if (clearCache.get()) { clearCache.set(false); doClear(); }

        if (!showRoute.get() || mc.player == null) return;

        // Only recalculate when dirty (reached point / read / cleared)
        if (routeDirty) {
            routeDirty = false;
            route = computeRoute();
        }

        // Check if player reached the nearest waypoint
        markVisited();
    }

    private void markVisited() {
        if (route.isEmpty() || mc.player == null) return;
        int range = arrivalRange.get();
        double rangeSq = (double) range * range;
        Vec3d nearest = route.get(0);
        double dx = mc.player.getX() - nearest.x;
        double dz = mc.player.getZ() - nearest.z;
        if (dx * dx + dz * dz <= rangeSq) {
            long key = xzKey((int) nearest.x, (int) nearest.z);
            if (!visitedXZ.contains(key)) {
                visitedXZ.add(key);
                info("已到达路径点 " + (int) nearest.x + "," + (int) nearest.z
                    + " 范围" + range + "内，加入黑名单");
                routeDirty = true;
                saveVisited();
            }
        }
    }

    // ── Read ──────────────────────────────────────────────────────────

    private void doRead() {
        String world = detectWorld();
        Path dimDir;
        if (useCustomPath.get()) {
            dimDir = Paths.get(customPath.get());
        } else {
            if (world == null) { info("未识别世界"); return; }
            dimDir = Paths.get("xaero", "minimap", world, "dim%1");
        }
        if (!Files.isDirectory(dimDir)) { info("无末地文件夹: " + dimDir); return; }
        List<String> entries = new ArrayList<>();
        try {
            for (Path file : (Iterable<Path>) Files.list(dimDir)::iterator) {
                if (!file.getFileName().toString().endsWith(".txt")) continue;
                for (String line : Files.readAllLines(file)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (!line.startsWith("waypoint:")) continue;
                    String[] p = line.split(":");
                    if (p.length < 6) continue;
                    try {
                        int x = Integer.parseInt(p[3]), z = Integer.parseInt(p[5]);
                        entries.add("  {\"name\":\"" + esc(p[1]) + "\",\"x\":" + x + ",\"z\":" + z + "}");
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException e) { error("读取失败"); return; }
        if (entries.isEmpty()) { info("无路径点"); return; }
        try {
            Path dir = LUJD_DIR.resolve(world);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("modi.json"), "[\n" + String.join(",\n", entries) + "\n]");
            info("已保存 " + entries.size() + " 个路径点");
        } catch (IOException e) { error("写入失败"); }
    }

    // ── Clear ─────────────────────────────────────────────────────────

    private void doClear() {
        String world = detectWorld();
        if (world == null) { info("未识别世界"); return; }
        Path worldDir = LUJD_DIR.resolve(world);
        try {
            if (Files.exists(worldDir)) {
                Files.walk(worldDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                route = Collections.emptyList();
                visitedXZ.clear();
                info("已清除 " + world + " 缓存");
            }
        } catch (IOException ignored) {}
    }

    // ── Route ─────────────────────────────────────────────────────────

    private List<Vec3d> computeRoute() {
        String world = detectWorld();
        if (world == null) return Collections.emptyList();
        Path file = LUJD_DIR.resolve(world).resolve("modi.json");
        if (!Files.isRegularFile(file)) return Collections.emptyList();

        List<Vec3d> pts = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (!line.startsWith("{")) continue;
                int xi = line.indexOf("\"x\":"), zi = line.indexOf("\"z\":");
                if (xi < 0 || zi < 0) continue;
                int xe = line.indexOf(",", xi + 4), ze = line.indexOf("}", zi + 4);
                if (xe < 0) xe = line.indexOf("}", xi + 4);
                if (ze < 0) ze = line.length();
                try {
                    int x = Integer.parseInt(line.substring(xi + 4, xe).trim());
                    int z = Integer.parseInt(line.substring(zi + 4, ze).trim());
                    pts.add(new Vec3d(x + 0.5, 0, z + 0.5));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) { return Collections.emptyList(); }
        if (pts.isEmpty()) return Collections.emptyList();

        // Filter out visited waypoints
        List<Vec3d> remaining = new ArrayList<>();
        for (Vec3d pt : pts) {
            if (!visitedXZ.contains(xzKey((int) pt.x, (int) pt.z))) {
                remaining.add(pt);
            }
        }
        if (remaining.isEmpty()) return Collections.emptyList();

        // Nearest-neighbor from player
        List<Vec3d> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        double px = mc.player.getX(), pz = mc.player.getZ();
        while (out.size() < remaining.size()) {
            int best = -1; double bestD = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                if (used.contains(i)) continue;
                double dx, dz;
                if (out.isEmpty()) { dx = remaining.get(i).x - px; dz = remaining.get(i).z - pz; }
                else { Vec3d last = out.get(out.size() - 1); dx = remaining.get(i).x - last.x; dz = remaining.get(i).z - last.z; }
                double d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = i; }
            }
            if (best < 0) break;
            used.add(best); out.add(remaining.get(best));
        }
        return out;
    }

    // ── Render ────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (route.isEmpty()) return;
        double maxDist = renderChunks.get() * 16.0;
        double maxSq = maxDist * maxDist;
        double px = mc.player.getX(), pz = mc.player.getZ();

        // ── Route lines ──────────────────────────────────────────────
        if (showRoute.get()) {
            SettingColor c = lineColor.get();
            double y = mc.player.getY() + 1.0;

            List<Vec3d> renderPts = new ArrayList<>();
            renderPts.add(new Vec3d(px, y, pz));

            // Segment 0: player → first waypoint
            renderPts.add(new Vec3d(route.get(0).x, y, route.get(0).z));

            // Rest: straight at Y=100
            for (int i = 1; i < route.size(); i++) {
                renderPts.add(new Vec3d(route.get(i).x, 100.0, route.get(i).z));
            }
            for (int i = 0; i < renderPts.size() - 1; i++) {
                Vec3d a = renderPts.get(i), b = renderPts.get(i + 1);
                double dax = a.x - px, daz = a.z - pz, dbx = b.x - px, dbz = b.z - pz;
                if (dax * dax + daz * daz > maxSq && dbx * dbx + dbz * dbz > maxSq) continue;

                double dx = b.x - a.x, dz = b.z - a.z;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 0.001) continue;
                double ux = dx / len, uz = dz / len;
                double ex = -uz * 0.12, ez = ux * 0.12;
                double segY = (a.y + b.y) * 0.5;
                double step = 0.1;
                for (double t = 0; t < len; t += step) {
                    double cx = a.x + ux * t, cz = a.z + uz * t;
                    event.renderer.box(
                        cx - ex, segY - 0.12, cz - ez,
                        cx + ex, segY + 0.12, cz + ez,
                        c, c, ShapeMode.Sides, 0);
                }
            }
        }

        // ── Beacon beams ──────────────────────────────────────────────
        if (showBeam.get()) {
            SettingColor bc = beamColor.get();
            double bw = beamWidth.get();
            double minY = beamMinY.get();
            double maxY = beamMaxY.get();
            for (int i = 0; i < route.size(); i++) {
                Vec3d pt = route.get(i);
                double ddx = pt.x - px, ddz = pt.z - pz;
                if (ddx * ddx + ddz * ddz > maxSq) continue;
                event.renderer.box(
                    pt.x - bw, minY, pt.z - bw,
                    pt.x + bw, maxY, pt.z + bw,
                    bc, bc, ShapeMode.Sides, 0);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String detectWorld() {
        if (mc == null) return null;
        try { if (mc.getCurrentServerEntry() != null) return "Multiplayer_" + mc.getCurrentServerEntry().address.replace(":", "_"); } catch (Exception ignored) {}
        try { if (mc.getServer() != null) { var sp = mc.getServer().getSaveProperties(); if (sp != null) return sp.getLevelName(); } } catch (Exception ignored) {}
        return null;
    }

    private void saveVisited() {
        if (visitedXZ.isEmpty()) return;
        String world = loadedWorld != null ? loadedWorld : detectWorld();
        if (world == null) return;
        Path f = LUJD_DIR.resolve(world).resolve("visited.json");
        try {
            List<String> list = new ArrayList<>();
            for (long key : visitedXZ) {
                int x = (int)(key >> 32), z = (int) key;
                list.add("  {\"x\":" + x + ",\"z\":" + z + "}");
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, "[\n" + String.join(",\n", list) + "\n]");
        } catch (IOException ignored) {}
    }

    private void loadVisited() {
        if (loadedWorld == null) return;
        Path f = LUJD_DIR.resolve(loadedWorld).resolve("visited.json");
        if (!Files.isRegularFile(f)) return;
        visitedXZ.clear();
        try {
            for (String line : Files.readAllLines(f)) {
                line = line.trim();
                if (!line.startsWith("{")) continue;
                int xi = line.indexOf("\"x\":"), zi = line.indexOf("\"z\":");
                if (xi < 0 || zi < 0) continue;
                int xe = line.indexOf(",", xi + 4), ze = line.indexOf("}", zi + 4);
                if (xe < 0) xe = line.indexOf("}", xi + 4);
                if (ze < 0) ze = line.length();
                try {
                    int x = Integer.parseInt(line.substring(xi + 4, xe).trim());
                    int z = Integer.parseInt(line.substring(zi + 4, ze).trim());
                    visitedXZ.add(xzKey(x, z));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private static long xzKey(int x, int z) { return (long) x << 32 | (z & 0xFFFFFFFFL); }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    private void info(String msg) {
        System.out.println("[AutoElytra] " + msg);
        if (mc != null && mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§7[AutoElytra] " + msg), false);
    }
    private void error(String msg) {
        System.out.println("[AutoElytra] " + msg);
        if (mc != null && mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§c[AutoElytra] " + msg), false);
    }
}
