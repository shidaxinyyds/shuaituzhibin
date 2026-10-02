package com.stzb.assistant.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.stzb.assistant.overlay.OverlayWindowManager

/**
 * 商业级游戏常驻悬浮 UI 服务 (FloatOverlayService)
 * 
 * 托管 OverlayWindowManager：
 *   1. 迷你药丸胶囊 (Capsule) 边缘吸附与折叠；
 *   2. 全功能展开式游戏内 HUD 战术控制台 (Dashboard)；
 *   3. 准星全屏地块坐标嗅探拾取器 (Crosshair Picker)。
 */
class FloatOverlayService : Service() {

    private var overlayManager: OverlayWindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 正在启动游戏内常驻战术悬浮 UI 体系...")
        overlayManager = OverlayWindowManager(this)
        isShowing = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "⏹️ 销毁游戏内悬浮 UI 体系。")
        overlayManager?.destroy()
        overlayManager = null
        isShowing = false
    }

    companion object {
        private const val TAG = "FloatOverlayService"

        /**
         * 悬浮胶囊体系是否真的在运行。
         *
         * 主界面曾用一个 Activity 成员变量 `isOverlayShown` 来判断，
         * 但进程被回收或 Activity 重建后该变量必然失真，会出现
         * 「按钮写着可显示、实际已在显示」的状态错乱。这里以服务生命周期
         * 作为唯一事实来源，主界面只读不改。
         */
        @Volatile
        var isShowing: Boolean = false
            private set
    }
}
