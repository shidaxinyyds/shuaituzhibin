package com.stzb.assistant.service

import android.content.res.Resources
import android.graphics.PointF

/**
 * 工业级全机型全长宽比双向坐标自适应转换中枢
 * 彻底解决 16:9, 18:9, 19.5:9, 20:9, 21:9, 折叠屏、平板及刘海/挖孔屏黑边导致的点击偏位问题！
 *
 * 核心原理 (各向同性等比映射，杜绝黑边畸变)：
 *   1. 锁定虚拟高度为 720；
 *   2. 虚拟宽度根据用户手机真实物理横屏长宽比动态计算：
 *      W_virtual = round(720 * W_real / H_real)，且对齐到 16 的倍数（GPU显存对齐）；
 *   3. 缩放比 scaleX == scaleY == H_real / 720，图像无任何拉伸或黑边产生；
 *   4. 支持“左上角锚定”、“右上角锚定”、“居中锚定”，游戏任何 UI 在任何手机上均精准点中！
 */
object CoordinateTransformer {

    const val BASE_HEIGHT = 720f

    var physicalWidth: Float = 1920f
        private set
    var physicalHeight: Float = 1080f
        private set

    var virtualWidth: Float = 1280f
        private set
    val virtualHeight: Float = BASE_HEIGHT

    var scaleFactor: Float = 1.5f
        private set

    var insetLeft: Float = 0f
        private set
    var insetTop: Float = 0f
        private set

    init {
        refreshMetrics()
    }

    /**
     * 刷新真实物理屏幕尺寸与自适应虚拟分辨率
     */
    fun refreshMetrics() {
        val dm = Resources.getSystem().displayMetrics
        // 横屏状态：宽恒大于高
        physicalWidth = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        physicalHeight = minOf(dm.widthPixels, dm.heightPixels).toFloat()

        // 统一以 720 高度为基准，等比例反推该机型的虚拟宽度
        val rawVirtualW = (BASE_HEIGHT * (physicalWidth / physicalHeight)).toInt()
        // 16 字节对齐，防止硬件图元步长撕裂
        virtualWidth = ((rawVirtualW + 15) and 15.inv()).toFloat()

        // 等比缩放系数
        scaleFactor = physicalHeight / BASE_HEIGHT
    }

    /**
     * 更新安全边距（挖孔屏、状态栏、水滴屏）
     */
    fun updateSafeInsets(left: Float, top: Float) {
        this.insetLeft = left
        this.insetTop = top
    }

    /**
     * 虚拟坐标 -> 真实物理屏幕像素坐标
     */
    fun toReal(virtualX: Float, virtualY: Float): PointF {
        val realX = (virtualX * scaleFactor) + insetLeft
        val realY = (virtualY * scaleFactor) + insetTop
        return PointF(realX, realY)
    }

    /**
     * 真实物理屏幕像素坐标 -> 虚拟坐标
     */
    fun toVirtual(realX: Float, realY: Float): PointF {
        val vX = (realX - insetLeft) / scaleFactor
        val vY = (realY - insetTop) / scaleFactor
        return PointF(vX, vY)
    }

    /**
     * 屏幕锚点自适应计算工具
     * 率土之滨的 UI 在超宽屏（如 20:9）上采用分屏锚定，此方法保证无论屏幕多宽都能精准命中！
     */
    enum class Anchor {
        TOP_LEFT,      // 资源栏、主公头像
        TOP_CENTER,    // 坐标搜索栏、天气
        TOP_RIGHT,     // 地图、提醒、同盟战报
        BOTTOM_LEFT,   // 聊天栏、邮件
        BOTTOM_RIGHT,  // 武将、部队、战法、征兵
        CENTER         // 弹出地块指令菜单（扫荡/出征/屯田）
    }

    /**
     * 根据 UI 锚点计算实际虚拟坐标
     * @param anchor 锚点类型
     * @param offsetX 相对该锚点的 X 偏移（基于标准 1280x720 设计图）
     * @param offsetY 相对该锚点的 Y 偏移
     */
    fun getAnchoredVirtualPoint(anchor: Anchor, offsetX: Float, offsetY: Float): PointF {
        return when (anchor) {
            Anchor.TOP_LEFT -> PointF(offsetX, offsetY)
            Anchor.TOP_CENTER -> PointF((virtualWidth / 2f) + offsetX, offsetY)
            Anchor.TOP_RIGHT -> PointF(virtualWidth - offsetX, offsetY)
            Anchor.BOTTOM_LEFT -> PointF(offsetX, virtualHeight - offsetY)
            Anchor.BOTTOM_RIGHT -> PointF(virtualWidth - offsetX, virtualHeight - offsetY)
            Anchor.CENTER -> PointF((virtualWidth / 2f) + offsetX, (virtualHeight / 2f) + offsetY)
        }
    }
}
