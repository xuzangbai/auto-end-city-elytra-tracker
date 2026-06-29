package com.autoelytra.addon.mixin;

import com.autoelytra.addon.utils.FramePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 菜单界面（非游戏内）视频背景 + 鼠标拖尾。
 */
@Mixin(Screen.class)
public class ScreenBackgroundMixin {

    private static final int PARALLAX = 30;
    private static final float ZOOM = 1.2f;

    // 拖尾
    private static final int TRAIL_LEN = 12;
    private static final int TRAIL_SIZE = 4;
    private final int[] trailX = new int[TRAIL_LEN];
    private final int[] trailY = new int[TRAIL_LEN];
    private int trailIdx = 0;
    private int trailCount = 0;

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void replaceBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().world != null) return;
        if (ci.isCancelled()) return;
        ci.cancel();

        var w = MinecraftClient.getInstance().getWindow();
        FramePlayer.drawVideoBackground(context,
            w.getScaledWidth(), w.getScaledHeight(),
            mouseX, mouseY, PARALLAX, ZOOM);

        trailX[trailIdx] = mouseX;
        trailY[trailIdx] = mouseY;
        trailIdx = (trailIdx + 1) % TRAIL_LEN;
        if (trailCount < TRAIL_LEN) trailCount++;
    }

    @Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
    private void replacePanorama(DrawContext context, float delta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().world != null) return;
        ci.cancel();
        if ((Object) this instanceof TitleScreen) return; // 主食视差走 drawVideo
        var w = MinecraftClient.getInstance().getWindow();
        FramePlayer.drawVideoBackground(context,
            w.getScaledWidth(), w.getScaledHeight(),
            0, 0, 0, ZOOM); // 过渡界面无视差
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void drawTrail(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().world != null) return;
        for (int j = 0; j < trailCount; j++) {
            int idx = (trailIdx - 1 - j + TRAIL_LEN) % TRAIL_LEN;
            int alpha = 0x80 - (j * 0x80 / TRAIL_LEN);
            if (alpha <= 0) continue;
            int s = TRAIL_SIZE * (TRAIL_LEN - j) / TRAIL_LEN;
            if (s < 1) s = 1;
            int shape = (trailX[idx] + trailY[idx]) % 2;
            int c = (alpha << 24) | (shape == 0 ? 0xFFC0CB : 0x00FFFF);
            if (shape == 0) {
                int t = s / 2;
                context.fill(trailX[idx] - t, trailY[idx] - s, trailX[idx] + t, trailY[idx] + s, c);
                context.fill(trailX[idx] - s, trailY[idx] - t, trailX[idx] + s, trailY[idx] + t, c);
            } else {
                context.fill(trailX[idx], trailY[idx] - s, trailX[idx] + 1, trailY[idx] + s, c);
                context.fill(trailX[idx] - s, trailY[idx], trailX[idx] + s, trailY[idx] + 1, c);
                context.fill(trailX[idx] - s/2, trailY[idx] - s/2, trailX[idx] + s/2, trailY[idx] + s/2, c);
            }
        }
    }
}
