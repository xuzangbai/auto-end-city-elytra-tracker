package com.autoelytra.addon.modules;

import com.autoelytra.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
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

    // ── Algorithm selection ──────────────────────────────────────────

    public enum Algorithm {
        NearestNeighbor,
        Christofides
    }

    private final Setting<Algorithm> algorithm = sgGeneral.add(new EnumSetting.Builder<Algorithm>()
        .name("algorithm")
        .description("路径计算算法")
        .defaultValue(Algorithm.NearestNeighbor)
        .build()
    );

    // ── Calculate button ─────────────────────────────────────────────

    private final Setting<Boolean> calculateRoute = sgGeneral.add(new BoolSetting.Builder()
        .name("calculate-route")
        .description("点击以使用当前算法计算路径（结果会保留直到下次计算）")
        .defaultValue(false)
        .build()
    );

    // ── Other settings ───────────────────────────────────────────────

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
        .description("渲染路径线（不影响已计算的路径）")
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
    private String loadedWorld = null;
    /** Remembers which algorithm produced the current route for display. */
    private Algorithm activeAlgorithm = null;
    /** Tracks whether visited.json existed on disk; prevents accidental rewrite after user deletes it. */
    private boolean visitedFileExisted;

    /** Exposed for WaypointLock to read the computed route. */
    public List<Vec3d> getRoute() { return route; }

    public WaypointReader() {
        super(AddonTemplate.CATEGORY, "waypoint-reader",
            "Xaero路径点读取 + 路线渲染（支持最近邻 / Christofides 算法）");
    }

    @Override
    public void onActivate() {
        try { Files.createDirectories(LUJD_DIR); } catch (IOException ignored) {}
        loadedWorld = detectWorld();
        if (loadedWorld != null) {
            loadVisited();
            loadRoute();
        }
        if (route == null) route = Collections.emptyList();
        info("已恢复 " + visitedXZ.size() + " 个已访问点, " + route.size() + " 个路径点路线"
            + (activeAlgorithm != null ? " (" + activeAlgorithm.name() + ")" : ""));
    }

    @Override
    public void onDeactivate() {
        saveVisited();
        saveRoute();
        visitedXZ.clear();
        route = Collections.emptyList();
        loadedWorld = null;
        activeAlgorithm = null;
        visitedFileExisted = false;
    }

    /** Lazily load state if it wasn't available during onActivate(). */
    private void ensureStateLoaded() {
        if (loadedWorld != null) return;
        loadedWorld = detectWorld();
        if (loadedWorld != null) {
            loadVisited();
            loadRoute();
            if (route == null) route = Collections.emptyList();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // ── One-shot button actions ──────────────────────────────────
        if (readXaero.get()) { readXaero.set(false); doRead(); }
        if (clearCache.get()) { clearCache.set(false); doClear(); }

        // ── Calculate-route button ──────────────────────────────────
        if (calculateRoute.get()) {
            calculateRoute.set(false);
            doCalculate();
        }

        if (mc.player == null) return;

        // ── Mark visited waypoints (independent of showRoute) ────────
        markVisited();
    }

    /** Called when the user clicks the "Calculate Route" button. */
    private void doCalculate() {
        // Reload visited from disk so external changes take effect
        loadVisited();
        route = computeRoute();
        activeAlgorithm = algorithm.get();
        if (route.isEmpty()) {
            info("未找到路径点，无法计算路线");
        } else {
            info("已使用 " + activeAlgorithm.name() + " 算法计算 " + route.size() + " 个路径点路线"
                + "（已排除 " + visitedXZ.size() + " 个已访问点）");
            saveRoute();
        }
    }

    private void markVisited() {
        ensureStateLoaded();
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
                    + " 范围" + range + "内，加入黑名单（剩余 " + (route.size() - 1) + " 个点）");
                saveVisited();
                // Remove the reached point from the front of the route (no full recalculation)
                if (route.size() > 1) {
                    route = new ArrayList<>(route.subList(1, route.size()));
                } else {
                    route = Collections.emptyList();
                    // All waypoints visited – play completion sound
                    playCompletionSound();
                }
                saveRoute();
            }
        }
    }

    /** Play sound when all waypoints have been reached. */
    private void playCompletionSound() {
        if (mc.player == null) return;
        try {
            mc.player.playSound(SoundEvent.of(Identifier.of("autoelytra", "xunluend")), 1.0f, 1.0f);
            info("所有路径点已到达！");
        } catch (Exception ignored) {}
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
            Path modiPath = dir.resolve("modi.json");
            Files.writeString(modiPath, "[\n" + String.join(",\n", entries) + "\n]");
            // Data changed – clear route (will need recalculation), reload visited from disk
            route = Collections.emptyList();
            activeAlgorithm = null;
            loadVisited();
            info("已保存 " + entries.size() + " 个路径点（已排除 " + visitedXZ.size() + " 个已访问点）");
        } catch (IOException e) { error("写入失败"); }
    }

    // ── Clear ─────────────────────────────────────────────────────────

    private void doClear() {
        String world = detectWorld();
        if (world == null) { info("未识别世界"); return; }
        Path modiFile = LUJD_DIR.resolve(world).resolve("modi.json");
        try {
            if (Files.deleteIfExists(modiFile)) {
                route = Collections.emptyList();
                activeAlgorithm = null;
                info("已清除 " + world + " 的 modi.json（路径点数据）");
            } else {
                info("无 modi.json 可清除");
            }
        } catch (IOException e) {
            error("清除失败: " + e.getMessage());
        }
    }

    // ── Route computation ─────────────────────────────────────────────

    private List<Vec3d> computeRoute() {
        ensureStateLoaded();
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

        // Dispatch to selected algorithm
        switch (algorithm.get()) {
            case Christofides:
                return computeChristofides(remaining);
            case NearestNeighbor:
            default:
                return computeNearestNeighbor(remaining);
        }
    }

    // ── Nearest-Neighbor algorithm ────────────────────────────────────

    private List<Vec3d> computeNearestNeighbor(List<Vec3d> remaining) {
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

    // ── Christofides algorithm (1.5-approximation for metric TSP) ────

    private List<Vec3d> computeChristofides(List<Vec3d> points) {
        int n = points.size();
        if (n == 1) return new ArrayList<>(points);
        if (n == 2) return new ArrayList<>(points);

        // Precompute distance matrix
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = points.get(i).x - points.get(j).x;
                double dz = points.get(i).z - points.get(j).z;
                dist[i][j] = dist[j][i] = Math.sqrt(dx * dx + dz * dz);
            }
        }

        // Step 1: Build Minimum Spanning Tree (Prim's algorithm)
        List<int[]> mstEdges = primMST(dist, n);

        // Step 2: Find odd-degree vertices in MST
        int[] degree = new int[n];
        for (int[] e : mstEdges) { degree[e[0]]++; degree[e[1]]++; }
        List<Integer> oddVertices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (degree[i] % 2 != 0) oddVertices.add(i);
        }

        // Step 3: Minimum-weight perfect matching on odd vertices (greedy)
        List<int[]> matchingEdges = greedyPerfectMatching(oddVertices, dist);

        // Step 4: Build multigraph (MST + matching) adjacency list
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : mstEdges) { adj.get(e[0]).add(e[1]); adj.get(e[1]).add(e[0]); }
        for (int[] e : matchingEdges) { adj.get(e[0]).add(e[1]); adj.get(e[1]).add(e[0]); }

        // Step 5: Find Eulerian circuit (Hierholzer's algorithm)
        List<Integer> circuit = hierholzer(adj, n);

        // Step 6: Shortcut to Hamiltonian path
        boolean[] seen = new boolean[n];
        List<Integer> tour = new ArrayList<>();
        for (int v : circuit) {
            if (!seen[v]) { seen[v] = true; tour.add(v); }
        }

        // Step 7: Rotate tour to start from point nearest to player
        double px = mc.player.getX(), pz = mc.player.getZ();
        int startIdx = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < tour.size(); i++) {
            Vec3d pt = points.get(tour.get(i));
            double d = (pt.x - px) * (pt.x - px) + (pt.z - pz) * (pt.z - pz);
            if (d < bestDist) { bestDist = d; startIdx = i; }
        }

        List<Vec3d> result = new ArrayList<>();
        for (int i = 0; i < tour.size(); i++) {
            result.add(points.get(tour.get((startIdx + i) % tour.size())));
        }
        return result;
    }

    /** Prim's algorithm – returns list of edges as int[2] arrays. */
    private List<int[]> primMST(double[][] dist, int n) {
        boolean[] inTree = new boolean[n];
        double[] minDist = new double[n];
        int[] parent = new int[n];
        Arrays.fill(minDist, Double.MAX_VALUE);
        Arrays.fill(parent, -1);
        minDist[0] = 0;
        List<int[]> edges = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            // Pick vertex with smallest minDist not yet in tree
            int u = -1;
            double best = Double.MAX_VALUE;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && minDist[v] < best) { best = minDist[v]; u = v; }
            }
            if (u < 0) break;
            inTree[u] = true;
            if (parent[u] >= 0) edges.add(new int[]{parent[u], u});

            for (int v = 0; v < n; v++) {
                if (!inTree[v] && dist[u][v] < minDist[v]) {
                    minDist[v] = dist[u][v];
                    parent[v] = u;
                }
            }
        }
        return edges;
    }

    /** Greedy minimum-weight perfect matching on odd-degree vertices. */
    private List<int[]> greedyPerfectMatching(List<Integer> vertices, double[][] dist) {
        List<int[]> matching = new ArrayList<>();
        boolean[] matched = new boolean[dist.length];
        // Build all candidate pairs among odd vertices, sorted by distance
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) {
            for (int j = i + 1; j < vertices.size(); j++) {
                int a = vertices.get(i), b = vertices.get(j);
                pairs.add(new Pair(a, b, dist[a][b]));
            }
        }
        pairs.sort(Comparator.comparingDouble(p -> p.dist));
        for (Pair p : pairs) {
            if (!matched[p.a] && !matched[p.b]) {
                matched[p.a] = matched[p.b] = true;
                matching.add(new int[]{p.a, p.b});
            }
        }
        return matching;
    }

    /** Hierholzer's algorithm for Eulerian circuit. */
    private List<Integer> hierholzer(List<List<Integer>> adj, int n) {
        // Copy adjacency as mutable edge counts
        List<List<Integer>> edgeList = new ArrayList<>();
        for (int i = 0; i < n; i++) edgeList.add(new ArrayList<>(adj.get(i)));

        List<Integer> circuit = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(0);
        while (!stack.isEmpty()) {
            int v = stack.peek();
            if (!edgeList.get(v).isEmpty()) {
                int u = edgeList.get(v).remove(edgeList.get(v).size() - 1);
                edgeList.get(u).remove((Integer) v);
                stack.push(u);
            } else {
                circuit.add(stack.pop());
            }
        }
        // circuit is in reverse order; reverse to get forward traversal
        Collections.reverse(circuit);
        return circuit;
    }

    // ── Helper class for matching ─────────────────────────────────────

    private static class Pair {
        final int a, b;
        final double dist;
        Pair(int a, int b, double dist) { this.a = a; this.b = b; this.dist = dist; }
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
        // If file existed before but is now gone, user deleted it → clear memory instead
        if (visitedFileExisted && !Files.isRegularFile(f)) {
            visitedXZ.clear();
            visitedFileExisted = false;
            return;
        }
        try {
            List<String> list = new ArrayList<>();
            for (long key : visitedXZ) {
                int x = (int)(key >> 32), z = (int) key;
                list.add("  {\"x\":" + x + ",\"z\":" + z + "}");
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, "[\n" + String.join(",\n", list) + "\n]");
            visitedFileExisted = true;
        } catch (IOException ignored) {}
    }

    private void loadVisited() {
        if (loadedWorld == null) return;
        Path f = LUJD_DIR.resolve(loadedWorld).resolve("visited.json");
        if (!Files.isRegularFile(f)) {
            // File was deleted externally → clear memory too
            if (visitedFileExisted) visitedXZ.clear();
            visitedFileExisted = false;
            return;
        }
        visitedFileExisted = true;
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

    /** Save current route to lujd/<world>/route.json so it persists across sessions. */
    private void saveRoute() {
        if (route == null) return;
        String world = loadedWorld != null ? loadedWorld : detectWorld();
        if (world == null) return;
        Path f = LUJD_DIR.resolve(world).resolve("route.json");
        try {
            List<String> list = new ArrayList<>();
            for (Vec3d pt : route) {
                list.add("  {\"x\":" + (int) pt.x + ",\"z\":" + (int) pt.z + "}");
            }
            Files.createDirectories(f.getParent());
            String algo = activeAlgorithm != null ? activeAlgorithm.name() : algorithm.get().name();
            String json = "{\n  \"algorithm\":\"" + algo + "\",\n  \"points\":[\n"
                + String.join(",\n", list) + "\n  ]\n}";
            Files.writeString(f, json);
        } catch (IOException ignored) {}
    }

    /** Load previously saved route from lujd/<world>/route.json. */
    private void loadRoute() {
        if (loadedWorld == null) return;
        Path f = LUJD_DIR.resolve(loadedWorld).resolve("route.json");
        if (!Files.isRegularFile(f)) { route = Collections.emptyList(); return; }
        try {
            String raw = Files.readString(f).trim();
            // Parse algorithm
            int algoIdx = raw.indexOf("\"algorithm\":\"");
            if (algoIdx >= 0) {
                int algoStart = algoIdx + 14;
                int algoEnd = raw.indexOf("\"", algoStart);
                if (algoEnd > algoStart) {
                    try { activeAlgorithm = Algorithm.valueOf(raw.substring(algoStart, algoEnd)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            // Parse points array
            List<Vec3d> pts = new ArrayList<>();
            int arrStart = raw.indexOf("\"points\":[");
            if (arrStart < 0) { route = Collections.emptyList(); return; }
            int brace = raw.indexOf("{", arrStart);
            while (brace >= 0) {
                int endBrace = raw.indexOf("}", brace);
                if (endBrace < 0) break;
                String entry = raw.substring(brace, endBrace + 1);
                int xi = entry.indexOf("\"x\":"), zi = entry.indexOf("\"z\":");
                if (xi >= 0 && zi >= 0) {
                    int xe = entry.indexOf(",", xi + 4);
                    if (xe < 0) xe = entry.indexOf("}", xi + 4);
                    int ze = entry.indexOf("}", zi + 4);
                    if (ze < 0) ze = entry.length();
                    try {
                        int x = Integer.parseInt(entry.substring(xi + 4, xe).trim());
                        int z = Integer.parseInt(entry.substring(zi + 4, ze).trim());
                        pts.add(new Vec3d(x + 0.5, 0, z + 0.5));
                    } catch (NumberFormatException ignored) {}
                }
                brace = raw.indexOf("{", endBrace + 1);
            }
            // Filter out already-visited waypoints
            List<Vec3d> filtered = new ArrayList<>();
            for (Vec3d pt : pts) {
                if (!visitedXZ.contains(xzKey((int) pt.x, (int) pt.z))) {
                    filtered.add(pt);
                }
            }
            route = filtered;
        } catch (IOException e) {
            route = Collections.emptyList();
        }
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
