package com.autoelytra.addon.mixin;

import com.autoelytra.addon.utils.FramePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.LogoDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主菜单——视频背景 + 按钮方块覆盖。
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    private static final int PARALLAX = 30;
    private static final float ZOOM = 1.2f;

    /**
     * 按钮参数，按 children() 顺序。
     * 每项 { x, y, 宽, 高 }：x=距左边缘, y=距顶部绝对像素, 宽/高填0=默认
     */
    /**
     * 按钮配置：{ 翻译键关键词, x, y, 宽, 高, 颜色 }
     * 按翻译键匹配，跟 children() 顺序无关。删按钮不影响其他。
     */
    private static final Object[][] BTN = {
        {"singleplayer",   30,  60, 0, 0, 0x80AAAAAA}, // 单人
        {"multiplayer",    30,  90, 0, 0, 0x80AAAAAA}, // 多人
        {"online",         30, 120, 0, 0, 0x80AAAAAA}, // Realms
        {"mod",            30, 150, 0, 0, 0x80AAAAAA}, // 模组
        {"language",       60, 240, 0, 0, 0x80AAAAAA}, // 语言
        {"options",        30, 180, 0, 0, 0x80AAAAAA}, // 选项
        {"quit",           30, 210, 0, 0, 0x80AAAAAA}, // 退出
        {"accessibility",  30, 240, 0, 0, 0x80AAAAAA}, // 无障碍
    };

    /** 提取按钮的匹配文字：优先翻译键，兜底类名 */
    private static String keyOf(ClickableWidget w) {
        if (w instanceof ButtonWidget b) {
            TextContent c = b.getMessage().getContent();
            if (c instanceof TranslatableTextContent t) return t.getKey().toLowerCase();
            String s = b.getMessage().getString().toLowerCase();
            if (!s.isEmpty()) return s;
        }
        return w.getClass().getName().toLowerCase() + " " + w.toString().toLowerCase();
    }

    /** 返回匹配到的配置索引，-1=未匹配 */
    private static int match(ClickableWidget w) {
        String k = keyOf(w);
        for (int i = 0; i < BTN.length; i++) {
            if (k.contains((String) BTN[i][0])) return i;
        }
        return -1;
    }

    // 鼠标拖尾
    private static final int TRAIL_LEN = 12;
    private static final int TRAIL_SIZE = 4;
    private final int[] trailX = new int[TRAIL_LEN];
    private final int[] trailY = new int[TRAIL_LEN];
    private int trailIdx = 0;
    private int trailCount = 0;

    /** 绘制视频背景 + 记录鼠标轨迹 */
    @Inject(method = "render", at = @At("HEAD"))
    private void drawVideo(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        doReposition(); // 提前修正，避免原版按钮闪一下
        var w = MinecraftClient.getInstance().getWindow();
        FramePlayer.drawVideoBackground(context,
            w.getScaledWidth(), w.getScaledHeight(),
            mouseX, mouseY, PARALLAX, ZOOM);

        trailX[trailIdx] = mouseX;
        trailY[trailIdx] = mouseY;
        trailIdx = (trailIdx + 1) % TRAIL_LEN;
        if (trailCount < TRAIL_LEN) trailCount++;
    }

    /** 取消 renderBackground（渐变） */
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void cancelBg(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ci.cancel();
    }

    /** 拦截 Minecraft 大标题 */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/LogoDrawer;draw(Lnet/minecraft/client/gui/DrawContext;IF)V"))
    private void skipLogo(LogoDrawer drawer, DrawContext ctx, int w, float alpha) {}

    @Shadow private net.minecraft.client.gui.screen.SplashTextRenderer splashText;

    /** 关掉 splash */
    @Inject(method = "init", at = @At("TAIL"))
    private void removeSplash(CallbackInfo ci) {
        splashText = null;
    }

    private void doReposition() {
        Screen self = (Screen) (Object) this;
        int smallIdx = 0;
        int h = MinecraftClient.getInstance().getWindow().getScaledHeight();
        for (var child : self.children()) {
            if (!(child instanceof ClickableWidget w)) continue;
            if (!w.visible) continue;

            // 小图标按钮（语言、无障碍）固定在左下角横排
            if (w.getWidth() < 50) {
                w.setY(h - 34);
                w.setX(30 + smallIdx * 24);
                smallIdx++;
                continue;
            }

            int i = match(w);
            if (i >= 0) {
                w.setX((int) BTN[i][1]);
                w.setY((int) BTN[i][2]);
                if ((int) BTN[i][3] > 0) w.setWidth((int) BTN[i][3]);
                if ((int) BTN[i][4] > 0) w.setHeight((int) BTN[i][4]);
            } else {
                w.setX(-1000);
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void drawBlocks(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // 1. 方块盖在原位置
        Screen self = (Screen) (Object) this;
        for (var child : self.children()) {
            if (!(child instanceof ClickableWidget w)) continue;
            if (!w.visible) continue;
            int i = match(w);
            int color = i >= 0 ? (int) BTN[i][5] : 0x80AAAAAA;
            int x = w.getX(), y = w.getY();
            int bw = w.getWidth(), bh = w.getHeight();
            context.fill(x - 2, y - 2, x + bw + 2, y + bh + 2, 0x80666666); // 描边
            context.fill(x, y, x + bw, y + bh, color);                      // 填充
        }

        // 2. 移动按钮（下帧生效）
        doReposition();

        // 3. 鼠标拖尾（十字+菱形点）
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
