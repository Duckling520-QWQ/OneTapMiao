package com.example.u7e5f3218e9;

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

    public static final String ACTION_APPEND = "com.example.u7e5f3218e9.ACTION_APPEND_MIAO";
    public static final String ACTION_READ = "com.example.u7e5f3218e9.ACTION_READ_INPUT";

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
        }
    }
}
