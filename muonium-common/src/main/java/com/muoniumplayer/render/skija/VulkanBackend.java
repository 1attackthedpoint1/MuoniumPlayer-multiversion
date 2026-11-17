package com.muoniumplayer.render.skija;

/**
 * 「Minecraft 跑在 VulkanMod 上」的进程级判定，供<b>共享层</b>（muonium-common）使用。
 *
 * <p>VulkanMod 整体替换了 Minecraft 的 {@code GpuDevice} 实现：进程里没有任何 GL 上下文，
 * 一切裸 GL 调用（glGenTextures / glTexImage2D / …）要么拿到 nullptr 要么直接在原生层
 * 段错误（2026-11-17 实机：旧纹理系统在客户端 tick 里 glTexImage2D →
 * EXCEPTION_ACCESS_VIOLATION，hs_err 崩溃）。共享层里所有会碰 GL 的老路径都应当先问
 * 本类再动手。
 *
 * <h2>为什么在共享层再放一份探测，而不是引用各树的 SkijaHost#vulkanRenderer()</h2>
 * muonium-common 被 1.20.1 ~ 26.x 五棵树共同编译，能引用的只有<b>每棵树都有</b>的
 * {@code SkijaHost} API；{@code vulkanRenderer()} 只有 1.21.11 树实现了，共享层一引，
 * 另外四棵树全部编挂。探测本身只是一次 {@code Class.forName}，在这里独立成类、
 * 由各树（和共享层）共用，才是零耦合的做法。
 *
 * <h2>为什么用类存在性而不是加载器 API</h2>
 * 共享源码同时喂 Fabric 与 NeoForge，不能引 {@code FabricLoader}；而 VulkanMod 是
 * Fabric-only，类名就是它的唯一身份。{@code initialize=false} 只探类、不跑静态初始化，
 * 把「VulkanMod 存在但初始化失败」也一并算进来——那种情况下 GL 上下文同样不存在。
 *
 * <p>结果钉住一次：Class.forName 自带同步开销，热路径（每帧的纹理队列、绘制效果）
 * 都会来问。
 */
public final class VulkanBackend {

    private static volatile int decided; // 0=未决, 1=是, 2=否
    private static boolean logged;

    private VulkanBackend() {
    }

    /** Minecraft 是否跑在 VulkanMod 上（无 GL 上下文）。结果钉住，首次判定打一行日志。 */
    public static boolean isActive() {
        int state = decided;
        if (state == 0) {
            synchronized (VulkanBackend.class) {
                if (decided == 0) {
                    boolean active;
                    try {
                        Class.forName("net.vulkanmod.vulkan.Renderer", false,
                                VulkanBackend.class.getClassLoader());
                        active = true;
                    } catch (Throwable t) {
                        active = false;
                    }
                    decided = active ? 1 : 2;
                    if (active && !logged) {
                        logged = true;
                        System.out.println("[MuoniumPlayer/VulkanBackend] "
                                + "检测到 VulkanMod：共享层的 GL 老路径将全部跳过，"
                                + "绘制效果按 CPU 光栅档降级");
                    }
                }
                state = decided;
            }
        }
        return state == 1;
    }
}
