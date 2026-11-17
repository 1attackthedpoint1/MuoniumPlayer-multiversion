package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.render.skija.VulkanBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

/**
 * VulkanMod（CPU 光栅）档的全屏 SkSL 背景<b>降速缓存</b>。
 *
 * <h2>问题</h2>
 * LATENT / NOMAND / 流体这三档背景是整屏 {@code RuntimeEffect}：GPU 上一帧几毫秒，
 * CPU 光栅下是 2560x1600 逐像素跑 SkSL（LATENT 还要跑两遍：mesh + 抖动层），
 * 播放器界面实测个位数帧（2026-11-17 实机）。
 *
 * <h2>修法：低分辨率离屏 + 定时刷新</h2>
 * 把着色层画到 ¼ 分辨率的离屏光栅面再整层拉伸贴回：
 * <ul>
 *   <li>像素量降到 1/16——一次着色求值 ~几 ms 量级；</li>
 *   <li>每 150ms 才重算一次（≈6.7 fps 的背景动画），其余帧直接贴缓存位图；</li>
 *   <li>背景本来就是大幅渐变/流体，¼ 分辨率 LINEAR 拉伸的观感损失可忽略。</li>
 * </ul>
 *
 * <h2>语义对齐</h2>
 * <ul>
 *   <li>层内的混合（如抖动层的 SOFT_LIGHT）在离屏面内先解析完再整层合成，
 *       与逐层直接画到主画布等价的前提是离屏先垫一层底色——调用方负责
 *       在 {@link #begin} 返回的画布上先画底色（与主画布那层 fill 同色）；</li>
 *   <li>整层合成按调用方给的 alpha 走 SRC_OVER：预乘 over 满足结合律，
 *       与原路径「每层各乘一次 alpha」在层序不变时结果一致；</li>
 *   <li>窗口尺寸变化重建；着色内容（主题色/封面/时钟/频段）变化由定时刷新兜住，
 *       最长 150ms 的滞后肉眼不可分。</li>
 * </ul>
 *
 * <p>只在渲染线程上使用（FoliaBackgroundHost 的调用链全部在渲染线程）。
 * GL 后端下 {@link #enabled()} 恒 false，本类完全不参与，零行为变化。
 */
final class VulkanBgCache {

    /** 离屏分辨率比例：¼ 边长 = 1/16 像素量。 */
    private static final float SCALE = 0.25f;

    /** 重算间隔（纳秒）。150ms ≈ 背景动画 6.7 fps，肉眼上是「缓慢流动」而非幻灯片。 */
    private static final long REFRESH_NANOS = 150_000_000L;

    private Surface surface;
    private Image image;
    private int cacheW = -1;
    private int cacheH = -1;
    private long nextRefreshNanos;

    /** 只有 VulkanMod（CPU 光栅）下启用；GL 后端走原路径。 */
    boolean enabled() {
        return VulkanBackend.isActive();
    }

    /** 离屏面宽度；仅在 {@link #begin} 返回非 null 后有意义。 */
    int width() {
        return cacheW;
    }

    /** 离屏面高度；仅在 {@link #begin} 返回非 null 后有意义。 */
    int height() {
        return cacheH;
    }

    /**
     * 本轮需要重算时返回离屏画布（调用方把底色与各着色层画到它上面，坐标系 = 离屏像素），
     * 画完调 {@link #end}；缓存仍新鲜时返回 null（调用方什么都不用画，直接
     * {@link #composite} 贴缓存）。
     */
    Canvas begin(float width, float height) {
        int w = Math.max(1, Math.round(width * SCALE));
        int h = Math.max(1, Math.round(height * SCALE));
        if (surface == null || cacheW != w || cacheH != h) {
            if (surface != null) {
                surface.close();
            }
            surface = Surface.makeRasterN32Premul(w, h);
            if (image != null) {
                image.close();
                image = null;
            }
            cacheW = w;
            cacheH = h;
            nextRefreshNanos = 0L;
        }
        if (image != null && System.nanoTime() < nextRefreshNanos) {
            return null;
        }
        return surface.getCanvas();
    }

    /** 快照刚画好的离屏内容并整层贴回主画布。 */
    void end(Canvas main, float width, float height, float alpha) {
        if (surface == null) {
            return;
        }
        if (image != null) {
            image.close();
        }
        image = surface.makeImageSnapshot();
        nextRefreshNanos = System.nanoTime() + REFRESH_NANOS;
        composite(main, width, height, alpha);
    }

    /** 把缓存位图整层拉伸贴到主画布（缓存为空时是 no-op）。 */
    void composite(Canvas main, float width, float height, float alpha) {
        if (image == null) {
            return;
        }
        try (Paint paint = new Paint()) {
            if (alpha < 0.999f) {
                paint.setAlphaf(alpha);
            }
            main.drawImageRect(image, Rect.makeWH(cacheW, cacheH),
                    Rect.makeWH(width, height), SamplingMode.LINEAR, paint, true);
        }
    }

    void close() {
        if (image != null) {
            image.close();
            image = null;
        }
        if (surface != null) {
            surface.close();
            surface = null;
        }
        cacheW = -1;
        cacheH = -1;
        nextRefreshNanos = 0L;
    }
}
