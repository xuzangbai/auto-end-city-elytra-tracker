package com.autoelytra.addon.utils;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片序列帧播放器。
 * 使用前用 ffmpeg 将视频转为 PNG 帧序列，放入 assets/autoelytra/textures/xuliez/。
 * <pre>
 *   ffmpeg -i video.mp4 -vf "fps=24,scale=1920:1080" frame_%03d.png
 * </pre>
 */
public class FramePlayer {
    /** 总帧数，需要和 xuliez 目录下的帧文件数一致 */
    private static final int TOTAL_FRAMES = 8;
    /** 播放帧率（越大越快，越小越慢） */
    private static final int FPS = 7;

    private static final List<Identifier> frames = new ArrayList<>();
    private static long startTime = -1;
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            String name = "textures/xuliez/sleeping_monica_fixed" + String.format("%d", i) + ".png";
            frames.add(Identifier.of("autoelytra", name));
        }
        initialized = true;
    }

    /** 根据已流逝时间返回当前帧纹理 */
    public static Identifier getCurrentFrame() {
        if (!initialized) init();
        if (startTime < 0) startTime = System.currentTimeMillis();

        long elapsed = System.currentTimeMillis() - startTime;
        int index = (int) ((elapsed * FPS / 1000L) % TOTAL_FRAMES);
        return frames.get(index);
    }

    /** 重置播放进度（每次打开主菜单重新开始播） */
    public static void reset() {
        startTime = -1;
    }

    /**
     * 在当前界面全屏绘制视频背景（带视差和缩放）。
     * @param context  DrawContext
     * @param w        屏幕宽
     * @param h        屏幕高
     * @param mouseX   鼠标 X
     * @param mouseY   鼠标 Y
     * @param parallax 视差幅度（0=关闭）
     * @param zoom     缩放（1.0=原始）
     */
    public static void drawVideoBackground(DrawContext context, int w, int h,
                                            int mouseX, int mouseY,
                                            int parallax, float zoom) {
        int cx = w / 2;
        int cy = h / 2;

        int ox = (int) ((mouseX - cx) / (float) cx * -parallax);
        int oy = (int) ((mouseY - cy) / (float) cy * -parallax);

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate((float) cx, (float) cy);
        matrices.scale(zoom, zoom);
        matrices.translate((float) -cx, (float) -cy);

        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            getCurrentFrame(),
            ox, oy, 0.0f, 0.0f, w, h, w, h
        );

        matrices.popMatrix();
    }
}
