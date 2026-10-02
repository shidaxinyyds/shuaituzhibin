package com.stzb.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {

    companion object {
        const val CHANNEL_ID = "stzb_assistant_channel"
        const val CHANNEL_NAME = "率土管家服务通道"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("StzbApp", "率土全能管家应用启动，初始化核心基础设施...")

        // 1. 创建前台服务通知渠道 (适配 Android 8.0+)
        createNotificationChannel()

        // 2. 初始化知识库系统与全机型自适应坐标系统
        com.stzb.assistant.knowledge.KnowledgeBaseManager.init(this)
        com.stzb.assistant.service.CoordinateTransformer.refreshMetrics()

        // 3. 异步后台预热 RapidOCR 模型、OpenCV 模板资产与端侧 AI 权重
        CoroutineScope(Dispatchers.IO).launch {
            val ocrOk = OcrManager.init(this@App)
            val cvOk = com.stzb.assistant.ocr.OpenCvMatcher.init(this@App)
            com.stzb.assistant.ai.assets.ModelAssetManager.preloadAllBuiltinModels(this@App)
            Log.i("StzbApp", "底层基础设施预热完成: RapidOCR=$ocrOk, OpenCV=$cvOk, AI 权重已就绪")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持率土全能管家在后台稳定运行并执行巡航任务"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
