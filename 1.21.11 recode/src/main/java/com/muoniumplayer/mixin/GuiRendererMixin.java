package com.muoniumplayer.mixin;

import com.muoniumplayer.render.skija.SkijaHost;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod 路径的帧末合成入口：挂在 {@code GuiRenderer#render} 的最前面。
 *
 * <h2>为什么挂这里（VulkanMod 专属，GL 后端不进这条路）</h2>
 * 1.21.11 的 GUI 是延迟批处理：本 mod 渲染层的调用点（HUD 回调、{@code Screen#render}、
 * {@code afterRender}）全在「收集」阶段，那时原版 GUI 元素还没入列，在那里贴图会被
 * 随后收集的元素盖住。{@code render} 的入口恰好是分界线：<b>所有元素已入列、
 * 遍历还没开始</b>——此刻 {@code nextStratum()} 追加的层必然画在所有 GUI 之上。
 *
 * <h2>为什么 GL 路径不挂这里</h2>
 * GL 后端下 {@code GuiRenderer#render} 之后，Minecraft 还要
 * {@code RenderTarget#blitToScreen()} 把主帧缓冲整屏 blit 到窗口默认帧缓冲，
 * 会把 GUI 批次里画的东西全部覆盖（实测翻过车，见 SkijaHost 类注释）。所以 GL 路径
 * 保持原来的 {@code FramebufferMixin}（blitToScreen 出口）不动，本 mixin 在
 * {@code SkijaHost#compositeFrameVulkan} 开头就会因 {@code vulkanRenderer()==false}
 * 直接返回，两路互不串线。
 *
 * <h2>为什么 VulkanMod 下只能挂这里</h2>
 * VulkanMod 的 MinecraftMixin {@code @Redirect} 掉了 {@code RenderTarget#blitToScreen()}，
 * {@code FramebufferMixin} 在该环境下永远不会触发；而本 mixin 走的全是 MC 的渲染
 * 抽象层 API（GpuTexture 上传 + GUI 纹理 blit），恰好是 VulkanMod 实现并接管渲染的那
 * 一层，天然兼容。
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void muonium$compositeVulkanFrame(CallbackInfo ci) {
        SkijaHost.compositeFrameVulkan(((GuiRendererAccessor) (Object) this).muonium$renderState());
    }
}
