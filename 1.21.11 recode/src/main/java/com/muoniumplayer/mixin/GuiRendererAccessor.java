package com.muoniumplayer.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 放开 {@code GuiRenderer#renderState}（package-private final）的读取口。
 *
 * <p>字段本身跨包不可见，类 mixin 的 {@code @Shadow} 写不了；{@code @Accessor} 生成的方法
 * 直接注入目标类里，不受 Java 访问规则约束，是对这类字段的标准取法。
 *
 * <p>用途只有一个：{@link GuiRendererMixin} 在 {@code render} 入口把这一帧的
 * {@link GuiRenderState} 递给 {@code SkijaHost#compositeFrameVulkan}，
 * 让 VulkanMod 路径的离屏图以最上层 stratum 提交回 GUI 批次。
 */
@Mixin(GuiRenderer.class)
public interface GuiRendererAccessor {

    @Accessor("renderState")
    GuiRenderState muonium$renderState();
}
