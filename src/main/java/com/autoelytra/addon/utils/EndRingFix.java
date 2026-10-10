package com.autoelytra.addon.utils;

import com.seedfinding.mcbiome.layer.BiomeLayer;
import com.seedfinding.mcbiome.layer.end.EndHeightLayer;
import com.seedfinding.mcbiome.layer.end.EndSimplexLayer;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mcbiome.source.EndBiomeSource;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcterrain.TerrainGenerator;

/**
 * 末地环 / 虚空环（MC-159283）修复。
 *
 * <p>问题只有一个函数：末地高度基准
 * <pre>EndHeightLayer.getNoiseValueAt():
 *   height = 100.0F - (float)Math.sqrt((float)(x * x + z * z)) * 8.0F;</pre>
 * 其中 {@code x * x + z * z} 用 int 相乘，约 4.6 万格（层坐标系）以外即溢出成负数，
 * 开方得到 NaN → 生物群系退化成"小岛/荒地"、地形变虚空，
 * 于是末地城查找在环区一个都找不到。
 *
 * <p>关键点：光替换 {@code EndBiomeSource.height} 字段没用——
 * {@code build()} 构造时已经把高度层连进了生物群系层链
 * （{@code EndBiomeLayer} 持有的是构造时那个实例）。所以这里要：
 * <ol>
 *   <li>替换 {@code height} 字段</li>
 *   <li>把 {@code full}（生物群系层）的父层换成修复后的高度层</li>
 *   <li>把 {@code simplex} 的父层同样换掉</li>
 * </ol>
 * 层链换掉之后，生物群系判定与地形生成就都会用修复后的高度。
 */
public final class EndRingFix {

    public enum Mode {
        /** 原版 bug 行为（不修复）。 */
        None,
        /** 用 long 算真实距离，消除溢出（Thorium 式）。 */
        LongFix,
        /** 负数开方给 25（社区修复 Mod 式）。 */
        Sqrt25Fix
    }

    /** int 溢出边界：floor(sqrt(2^31-1)) ≈ 46340。 */
    public static final int OVERFLOW_LIMIT = 46340;

    /** 末地城所在位置要求的最低地表高度（挡掉虚空里的浮空城）。 */
    public static final int MIN_CITY_GROUND_Y = 60;

    private EndRingFix() {}

    /** 某个坐标是否落在原版会溢出的区域（层坐标系）。 */
    public static boolean isOverflowZone(int x, int z) {
        return Math.abs((long) x) > OVERFLOW_LIMIT || Math.abs((long) z) > OVERFLOW_LIMIT;
    }

    /** 诊断用：读取生成源里当前生效的高度值。 */
    public static float heightOf(BiomeSource source, int x, int z) {
        return ((EndBiomeSource) source).height.getNoiseValueAt(x, z);
    }

    /**
     * 生成修复过的末地生物群系源。层链会被一并替换，
     * 因此库的生物群系判定、地形生成、末地城生成器内部判定都会用上修复后的高度。
     */
    public static BiomeSource createBiomeSource(Mode mode, MCVersion version, long seed) {
        EndBiomeSource source = (EndBiomeSource) EndBiomeSource.of(Dimension.END, version, seed);
        if (mode == Mode.None) return source;

        EndHeightLayer patched = new PatchedHeightLayer(version, source.simplex, source.height, mode);

        try {
            EndBiomeSource.class.getField("height").set(source, patched);

            // 关键：生物群系层(full = EndBiomeLayer)的父层是构造时那个高度层，
            // 必须换成修复后的，否则生物群系判定仍然用旧公式。
            setParent(source.full, 0, patched);
        } catch (Exception e) {
            throw new IllegalStateException("替换末地高度层失败: " + e, e);
        }
        return source;
    }

    /** 修复过的末地地形生成器（内部使用修复后的生物群系源）。 */
    public static TerrainGenerator createTerrainGenerator(Mode mode, MCVersion version, long seed) {
        return TerrainGenerator.of(Dimension.END, createBiomeSource(mode, version, seed));
    }

    private static void setParent(BiomeLayer layer, int index, BiomeLayer parent) throws Exception {
        var field = BiomeLayer.class.getDeclaredField("parents");
        field.setAccessible(true);
        BiomeLayer[] parents = (BiomeLayer[]) field.get(layer);
        parents[index] = parent;
    }

    /**
     * 只覆盖出问题的那一个函数；缓存与其余层结构保持库的原样。
     */
    private static final class PatchedHeightLayer extends EndHeightLayer {

        private final EndSimplexLayer simplex;
        private final EndHeightLayer vanilla;
        private final Mode mode;

        PatchedHeightLayer(MCVersion version, EndSimplexLayer simplex, EndHeightLayer vanilla, Mode mode) {
            super(version, simplex);
            this.simplex = simplex;
            this.vanilla = vanilla;
            this.mode = mode;
        }

        @Override
        public float getNoiseValueAt(int x, int z) {
            // 没溢出的范围交给库原实现，保证与库逐位一致
            if (!isOverflowZone(x, z)) return vanilla.getNoiseValueAt(x, z);

            int scaledX = x / 2;
            int scaledZ = z / 2;
            int oddX = x % 2;
            int oddZ = z % 2;

            long square = (long) x * (long) x + (long) z * (long) z;
            // 溢出区：修好开方，其余与库源码逐行一致（含 25x25 邻域扫描）
            float height = clampHeight(100.0F - sqrtFixed(square, mode) * 8.0F);

            for (int rx = -12; rx <= 12; ++rx) {
                for (int rz = -12; rz <= 12; ++rz) {
                    int shiftedX = scaledX + rx;
                    int shiftedZ = scaledZ + rz;
                    if ((long) shiftedX * shiftedX + (long) shiftedZ * shiftedZ > 4096L
                        && simplex.get(shiftedX, 0, shiftedZ)) {
                        float elevation = (Math.abs((float) shiftedX) * 3439.0F
                            + Math.abs((float) shiftedZ) * 147.0F) % 13.0F + 9.0F;
                        float smoothX = (float) (oddX - rx * 2);
                        float smoothZ = (float) (oddZ - rz * 2);
                        float noise = clampHeight(100.0F - (float) Math.sqrt(smoothX * smoothX + smoothZ * smoothZ) * elevation);
                        height = Math.max(height, noise);
                    }
                }
            }
            return height;
        }

        @Override
        public float sample(int x, int y, int z) {
            return getNoiseValueAt(x, z);
        }

        /** 溢出处的开方结果：LongFix 用真实距离，Sqrt25Fix 用社区修复的 25。 */
        private static float sqrtFixed(long square, Mode mode) {
            if (mode == Mode.Sqrt25Fix) return 25.0F;
            return (float) Math.sqrt((double) square);
        }

        private static float clampHeight(float value) {
            if (value < -100.0F) return -100.0F;
            return Math.min(value, 80.0F);
        }
    }
}
