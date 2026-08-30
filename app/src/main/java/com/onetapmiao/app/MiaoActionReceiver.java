package com.onetapmiao.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知栏「加喵」按钮的接收器。
 *
 * 需要在 AndroidManifest.xml 里声明（本包已附完整 Manifest）。
 *
 * 存在的意义：
 *   一键加喵（输入法通道）最快的触发方式不是悬浮窗，而是通知栏——
 *   下拉通知栏 → 点「加喵」，全程不用离开微信，也不用悬浮窗权限。
 *   悬浮窗在部分国产 ROM 上会被拦截或需要额外授权，通知栏按钮更通用。
 *
 * 注意：onReceive 跑在主线程，所以真正的活全部丢给 MiaoInjector，
 *       它内部自己开线程，这里不会卡住广播。
 */
public class MiaoActionReceiver extends BroadcastReceiver {

    public static final String ACTION_APPEND = "com.onetapmiao.app.ACTION_APPEND_MIAO";
    public static final String ACTION_READ = "com.onetapmiao.app.ACTION_READ_INPUT";
    /** v1.1：通知栏「暂停/恢复改写」按钮（切换 processingEnabled 总开关） */
    public static final String ACTION_TOGGLE_PROCESSING = "com.onetapmiao.app.ACTION_TOGGLE_PROCESSING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        final Context app = context.getApplicationContext();
        AppLog.init(app);

        if (ACTION_APPEND.equals(action)) {
            MiaoInjector.appendNow(app, null);
        } else if (ACTION_READ.equals(action)) {
            MiaoInjector.readOnly(app, null);
        } else if (ACTION_TOGGLE_PROCESSING.equals(action)) {
            toggleProcessing(app);
        }
    }

    /**
     * v1.1：切换改写总开关并刷新常驻通知按钮文案。
     *
     * 与悬浮窗图标点击共用同一个存储键（processing_enabled），
     * 所以从哪里切换，主界面 / 悬浮窗 / 通知栏三处状态都一致。
     */
    private void toggleProcessing(Context app) {
        try {
            CatConfig config = CatConfig.load(app);
            config.processingEnabled = !config.processingEnabled;
            config.save(app);

            // 悬浮窗活着的话：刷新图标状态 + 常驻通知按钮文案
            FloatingWindowService.onProcessingToggled(app, config.processingEnabled);

            AppLog.add("Recv", config.processingEnabled
                    ? "通知栏：恢复文本改写" : "通知栏：暂停文本改写");
        } catch (Throwable t) {
            AppLog.add("Recv", "切换改写开关失败", t);
        }
    }
}
