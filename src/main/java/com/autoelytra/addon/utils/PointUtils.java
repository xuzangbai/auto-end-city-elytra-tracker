package com.autoelytra.addon.utils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Xaero Minimap 路径点操作 —— 通过反射调用 API + 同步写入 waypoints.txt。
 */
public final class PointUtils {

    private PointUtils() {}

    // ── Cached reflection handles ──────────────────────────────────

    private static boolean initTried;
    private static Object cachedWaypointSet;
    private static Method wpGetName, wpGetX, wpGetZ;
    private static Method wpsGetWaypoints, wpsAdd, wpsRemove;
    // Waypoint constructor
    private static java.lang.reflect.Constructor<?> wpCtor;

    private static void init() {
        if (initTried) return;
        initTried = true;
        try {
            // xaero.hud.minimap.BuiltInHudModules
            Class<?> hudMods = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            var minimap = hudMods.getField("MINIMAP").get(null);
            var session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) return;
            var worldMgr = session.getClass().getMethod("getWorldManager").invoke(session);
            if (worldMgr == null) return;
            var world = worldMgr.getClass().getMethod("getCurrentWorld").invoke(worldMgr);
            if (world == null) return;
            cachedWaypointSet = world.getClass().getMethod("getCurrentWaypointSet").invoke(world);

            // xaero.common.minimap.waypoints.Waypoint
            Class<?> wpCls = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            wpGetName = wpCls.getMethod("getName");
            wpGetX = wpCls.getMethod("getX");
            wpGetZ = wpCls.getMethod("getZ");
            wpCtor = wpCls.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);

            // xaero.hud.minimap.waypoint.set.WaypointSet
            Class<?> wpsCls = Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
            wpsGetWaypoints = wpsCls.getMethod("getWaypoints");
            wpsAdd = wpsCls.getMethod("add", wpCls);
            // remove might be: remove(Waypoint) or remove(Object)
            wpsRemove = findRemoveMethod(wpsCls, wpCls);
        } catch (Throwable ignored) {}
    }

    private static Method findRemoveMethod(Class<?> wpsCls, Class<?> wpCls) {
        // WaypointSet might inherit remove from ArrayList or have its own
        for (Method m : wpsCls.getMethods()) {
            if (m.getName().equals("remove") && m.getParameterCount() == 1
                && m.getParameterTypes()[0].isAssignableFrom(wpCls))
                return m;
        }
        return null;
    }

    /** Re-resolve the WaypointSet each call (world may change). */
    private static Object getWaypointSet() {
        try {
            Class<?> hudMods = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            var minimap = hudMods.getField("MINIMAP").get(null);
            var session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) return null;
            var worldMgr = session.getClass().getMethod("getWorldManager").invoke(session);
            if (worldMgr == null) return null;
            var world = worldMgr.getClass().getMethod("getCurrentWorld").invoke(worldMgr);
            if (world == null) return null;
            return world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Public API ──────────────────────────────────────────────────

    public static boolean addToWaypoints(int x, int y, int z, String name) {
        // Always persist to file (independent of Xaero API)
        boolean fileOk = appendToFile(x, y, z, name);

        init();
        if (wpCtor == null || wpsAdd == null) return fileOk;

        try {
            Object wps = getWaypointSet();
            if (wps == null) return fileOk;

            // Dedup
            @SuppressWarnings("unchecked")
            List<Object> wpsList = (List<Object>) wpsGetWaypoints.invoke(wps);
            for (Object wp : wpsList) {
                if ((int) wpGetX.invoke(wp) == x
                    && (int) wpGetZ.invoke(wp) == z
                    && wpGetName.invoke(wp).equals(name))
                    return fileOk;
            }

            Object wp = wpCtor.newInstance(x, y, z, name, "E", 6);
            wpsAdd.invoke(wps, wp);
            return true;
        } catch (Throwable t) {
            return fileOk;
        }
    }

    private static Path getWpFile() {
        String world = detectXaeroWorld();
        if (world == null) return mc.runDirectory.toPath().resolve("waypoints.txt");
        return mc.runDirectory.toPath().resolve("xaero/minimap").resolve(world).resolve("dim%1/waypoints.txt");
    }

    private static String detectXaeroWorld() {
        if (mc == null) return null;
        try { if (mc.getCurrentServerEntry() != null) return "Multiplayer_" + mc.getCurrentServerEntry().address.replace(":", "_"); } catch (Exception ignored) {}
        try { if (mc.getServer() != null) { var sp = mc.getServer().getSaveProperties(); if (sp != null) return sp.getLevelName(); } } catch (Exception ignored) {}
        return null;
    }

    private static boolean appendToFile(int x, int y, int z, String name) {
        Path file = getWpFile();
        try {
            Files.createDirectories(file.getParent());
            String line = String.format(
                "waypoint:%s:E:%d:%d:%d:6:false:0:gui.xaero_default:false:0",
                sanitise(name), x, y, z);
            List<String> lines = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                lines.addAll(Files.readAllLines(file));
            }
            for (String existing : lines) {
                if (existing.trim().equals(line)) return true;
            }
            lines.add(line);
            Files.write(file, lines);
            System.out.println("[PointUtils] " + file.toAbsolutePath() + " + " + line);
            return true;
        } catch (IOException e) {
            System.err.println("[PointUtils] FAIL " + file.toAbsolutePath() + ": " + e);
            return false;
        }
    }

    private static String sanitise(String s) {
        return s.replace(":", "_").replace("\\", "_");
    }

    public static int removeWaypoints(String namePrefix) {
        int removed = removeFromFile(namePrefix);

        init();
        if (wpGetName == null || wpsRemove == null) return removed;

        try {
            Object wps = getWaypointSet();
            if (wps == null) return removed;

            @SuppressWarnings("unchecked")
            List<Object> wpsList = (List<Object>) wpsGetWaypoints.invoke(wps);
            List<Object> toRemove = new ArrayList<>();
            for (Object wp : wpsList) {
                String n = (String) wpGetName.invoke(wp);
                if (n.startsWith(namePrefix)) toRemove.add(wp);
            }
            for (Object wp : toRemove) wpsRemove.invoke(wps, wp);
            return Math.max(removed, toRemove.size());
        } catch (Throwable t) {
            return removed;
        }
    }

    private static int removeFromFile(String namePrefix) {
        Path file = getWpFile();
        if (!Files.isRegularFile(file)) return 0;
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> keep = new ArrayList<>();
            String prefix = "waypoint:" + sanitise(namePrefix);
            for (String line : lines) {
                if (!line.startsWith(prefix)) keep.add(line);
            }
            int removed = lines.size() - keep.size();
            if (removed > 0) {
                if (keep.isEmpty()) Files.deleteIfExists(file);
                else Files.write(file, keep);
            }
            return removed;
        } catch (IOException ignored) { return 0; }
    }
}
