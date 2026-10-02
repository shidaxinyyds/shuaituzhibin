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
        // attach() 让坐标中枢能用 application 上下文读取真实窗口尺寸
        // （比 Resources.getSystem() 可靠，且能反映分屏/多窗口边界）。
        com.stzb.assistant.service.CoordinateTransformer.attach(this)
        // 把「映射可逆、画布与屏幕等比」这条不变量在启动时校验一次并落日志，
        // 排查点击偏位时先看这一行。
        com.stzb.assistant.service.CoordinateTransformer.logSelfTest()

        // UI 锚点表（选队标签、识别区域）与地图投影（世界坐标换算）都需要
        // application 上下文来载入用户标定值。两者的默认值都只是折算/推导结果，
        // 真机首次使用应当标定，因此这里把未标定项数如实打出来。
        com.stzb.assistant.service.UiAnchors.attach(this)
        com.stzb.assistant.service.MapProjection.attach(this)
        com.stzb.assistant.service.SceneFingerprint.attach(this)
        // 按键模板库：OCR 不可用时按键定位的唯一依靠（模板匹配不依赖文字识别）
        com.stzb.assistant.service.ButtonTemplateStore.attach(this)
        com.stzb.assistant.service.MapProjection.logSelfTest()
        Log.i(
            "StzbApp",
            com.stzb.assistant.service.UiAnchors.describeAll() +
                "\n  UI 锚点未标定: ${com.stzb.assistant.service.UiAnchors.uncalibratedCount()} 项" +
                "（默认值按 1280x720 折算，真机建议标定）" +
                "\n  " + com.stzb.assistant.service.MapProjection.describeCalibration() +
                "\n  场景指纹: " + com.stzb.assistant.service.SceneFingerprint.describe()
        )

        // 3. 异步后台预热 RapidOCR 引擎与 OpenCV 模板资产
        CoroutineScope(Dispatchers.IO).launch {
            val ocrOk = OcrManager.init(this@App)
            val cvOk = com.stzb.assistant.ocr.OpenCvMatcher.init(this@App)
            // 如实汇报预热结果：这里曾无条件打印「AI 权重已就绪」，
            // 而 assets/models 下其实一个权重文件都没有，属于把失败伪装成成功。
            val modelReport = com.stzb.assistant.ai.assets.ModelAssetManager
                .describeAvailability(this@App)
            Log.i(
                "StzbApp",
                "底层基础设施预热完成: RapidOCR=$ocrOk, OpenCV=$cvOk\n$modelReport"
            )
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
