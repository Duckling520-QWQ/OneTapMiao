package com.onetapmiao.app;

import com.onetapmiao.app.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮窗服务。
 *
 * 【本版改动（方案）】
 *   1. 悬浮窗从「单个图标」改成「图标 + 喵按钮」的横向胶囊：
 *        点左边图标 = 切换总开关（和以前一样）
 *        点右边「喵」= 一键加喵（新增，微信靠它触发）
 *        整个胶囊任意位置拖动 = 移动悬浮窗（和以前一样）
 *   2. 通知栏加了「加喵」按钮，不用悬浮窗权限也能触发。
 *      —— 需要配合新增的 MiaoActionReceiver 和 AndroidManifest 里的 receiver 声明。
 *   3. 一键加喵走 MiaoInjector（输入法通道），不再依赖无障碍节点树，
 *      所以对微信有效。
 *   4. FLAG_NOT_FOCUSABLE 保持不变并且明确注释——这个 flag 决定点了悬浮窗
 *      之后输入框会不会失焦。失焦的话输入法通道就拿不到 InputConnection 了。
 */
public class FloatingWindowService extends Service {
    private static final String CHANNEL_ID = "floating_window_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int REQ_APPEND = 1001;
    /** v1.1：通知栏「暂停/恢复改写」按钮的 requestCode */
    private static final int REQ_TOGGLE = 1002;

    /** 判定为「拖动」而非「点击」的位移阈值（px） */
    private static final int TOUCH_SLOP = 10;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private ImageView iconView;
    private TextView miaoButton;
    /** v1.1.1：改写状态圆点（绿=改写中 / 灰=已暂停），与用户透明度解耦 */
    private View statusDot;

    /** 悬浮窗图标基准尺寸（dp，与 floating_window.xml 一致） */
    private static final int ICON_BASE_DP = 56;
    /** 喵按钮基准尺寸（dp） */
    private static final int BTN_BASE_DP = 40;
    /** 喵文字基准字号（sp） */
    private static final int BTN_TEXT_BASE_SP = 18;

    // ---- 结果气泡 ----
    // 为什么需要：Android 10 起，后台应用弹出的 Toast 会被系统直接拦截。
    // 你在微信里点「喵」时，本应用处于后台，Toast 根本不显示——
    // 回到自己的 App 才看得到，就是这个原因。
    // 悬浮窗走的是 SYSTEM_ALERT_WINDOW，不受这个限制，
    // 而且你点「喵」时手指和视线本来就在这附近，反馈更直接。
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile FloatingWindowService sInstance = null;
    // 成功和失败用不同停留时长：
    //   成功——你看一眼输入框就知道加上没有，气泡只是确认，1.2 秒足够，
    //         再久反而挡视线、碍事
    //   失败——得把原因读完（有些提示很长，比如「没读到输入框——请先点一下
    //         输入框让光标在里面，再点喵」），2 秒根本读不完，给 3.5 秒
    private static final long BUBBLE_DURATION_OK = 1200;
    private static final long BUBBLE_DURATION_FAIL = 3500;
    private View bubbleView;
    private final Runnable hideBubbleRunnable = new Runnable() {
        @Override
        public void run() {
            removeBubble();
        }
    };

    /** 供外部（MiaoInjector）调用：在悬浮窗下方弹一条结果提示 */
    public static void showResult(final String text, final boolean ok) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                FloatingWindowService s = sInstance;
                if (s != null) {
                    s.showBubbleInternal(text, ok);
                }
            }
        });
    }

    /** 悬浮窗当前是否活着（决定要不要走 Toast / 通知兜底） */
    public static boolean isShowing() {
        return sInstance != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 检查悬浮窗权限
        // 注意：外层是用 startForegroundService 启动本服务的，Android 8+ 要求服务在
        // 5 秒内调用 startForeground，否则系统判定启动失败并直接抛异常。
        // 所以即便这里要立刻退出，也必须先把前台通知顶上再收尾停止。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            startForegroundServiceCompat();
            stopForeground(true);
            stopSelf();
            return;
        }

        // 启动为前台服务（提升优先级）
        startForegroundServiceCompat();

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        iconView = floatingView.findViewById(R.id.floating_icon);
        miaoButton = floatingView.findViewById(R.id.btn_miao);
        statusDot = floatingView.findViewById(R.id.status_dot);
        iconView.setImageResource(R.mipmap.ic_launcher);

        // 圆形裁剪
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            iconView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            iconView.setClipToOutline(true);
        }

        // 根据配置设置初始透明度
        CatConfig config = CatConfig.load(this);
        iconView.setAlpha(config.processingEnabled ? 1.0f : 0.4f);
        updateMiaoButtonState(config.processingEnabled);
        updateStatusDot(config.processingEnabled);

        // 悬浮窗参数
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                // NOT_FOCUSABLE 是关键：点了悬浮窗不会让输入框失焦，
                // 否则后面切换输入法时拿不到 InputConnection，一键加喵直接失败。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 100;

        // v1.1：位置记忆——上次拖到哪里，这次就在哪里出现
        CatConfig appearanceCfg = CatConfig.load(this);
        if (appearanceCfg.floatX != CatConfig.FLOAT_POS_UNSET) {
            params.x = appearanceCfg.floatX;
        }
        if (appearanceCfg.floatY != CatConfig.FLOAT_POS_UNSET) {
            params.y = appearanceCfg.floatY;
        }
        applyAppearance(appearanceCfg);

        windowManager.addView(floatingView, params);

        // 触摸监听（区分点击与拖动，并按落点区分「切开关」和「加喵」）
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP) {
                            isClick = false;
                        }
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        try {
                            windowManager.updateViewLayout(floatingView, params);
                        } catch (Throwable ignored) {
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            handleTap(event.getX());
                        } else {
                            // v1.1：拖动结束——贴边吸附（可选）+ 位置记忆
                            onDragFinished();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        isClick = false;
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * 处理点击。
     *
     * @param x 相对于悬浮窗自身的横坐标
     */
    private void handleTap(float x) {
        // 落点在图标右边缘之后 → 点在「喵」按钮上
        if (miaoButton != null && iconView != null && x >= iconView.getRight()) {
            doAppendNow();
        } else {
            toggleProcessingEnabled();
        }
    }

    /**
     * v1.1：拖动结束后的收尾。
     *
     *   1. 贴边吸附（可在悬浮窗外观设置里关闭）：悬浮窗中心落在屏幕左半
     *      就吸到左边缘，否则吸到右边缘——避免悬浮球飘在屏幕正中挡字。
     *   2. 位置记忆：把最终坐标写进配置，下次启动原地出现。
     */
    private void onDragFinished() {
        try {
            CatConfig cfg = CatConfig.load(this);
            if (cfg.floatSnap && floatingView != null) {
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int viewW = floatingView.getWidth();
                params.x = snapX(screenW, viewW, params.x);
                try {
                    windowManager.updateViewLayout(floatingView, params);
                } catch (Throwable ignored) {
                }
            }
            cfg.floatX = params.x;
            cfg.floatY = params.y;
            cfg.save(this);
            AppLog.add("Float", "悬浮窗位置已保存: " + params.x + "," + params.y);
        } catch (Throwable t) {
            AppLog.add("Float", "保存悬浮窗位置失败", t);
        }
    }

    /**
     * v1.1：贴边吸附算法（纯函数，便于测试）。
     *
     * 悬浮窗中心落在屏幕左半 → 吸到左边缘（x=0）；
     * 落在右半 → 吸到右边缘（x=屏幕宽-悬浮窗宽，不小于 0）。
     *
     * @param screenWidth 屏幕宽度（px）
     * @param viewWidth   悬浮窗宽度（px，<=0 时按 1 处理避免除零）
     * @param currentX    当前 x 坐标（px）
     * @return 吸附后的 x 坐标（px）
     */
    static int snapX(int screenWidth, int viewWidth, int currentX) {
        if (viewWidth <= 0) {
            viewWidth = 1;
        }
        int centerX = currentX + viewWidth / 2;
        return (centerX < screenWidth / 2)
                ? 0
                : Math.max(0, screenWidth - viewWidth);
    }

    /**
     * v1.1：通知栏「暂停/恢复」按钮文案（纯函数，便于测试）。
     */
    static String notificationActionLabel(boolean processingEnabled) {
        return processingEnabled ? "暂停改写" : "恢复改写";
    }

    /**
     * v1.1：把外观配置（缩放 / 透明度）应用到悬浮窗视图。
     *
     * 注意与开关状态的关系：iconView 的 alpha（1.0 / 0.4）表达「改写开/关」，
     * floatingView 的整体 alpha 是用户设置的透明度——两层是相乘关系，
     * 暂停时会比平时更淡一点，正好符合「变弱了」的直觉。
     */
    private void applyAppearance(CatConfig cfg) {
        if (floatingView == null || cfg == null) {
            return;
        }
        float size = CatConfig.clampFloatSize(cfg.floatSize);

        // v1.1.1：不再用 setScaleX/Y「视觉放大」——那只是把画放大，
        // 布局尺寸（点击区域 / 圆形裁剪 / 贴边宽度）还是原尺寸，
        // 放大后图标会被自己的圆形裁剪框切掉、点击也点不准。
        // 改为「真实等比缩放子元素」：图标/按钮/文字一起按倍率变大，
        // 根布局 wrap_content 会跟着撑大，窗口真实变大，一切坐标计算都正确。
        if (iconView != null) {
            ViewGroup.LayoutParams ilp = iconView.getLayoutParams();
            ilp.width = Math.max(1, Math.round(dp(ICON_BASE_DP) * size));
            ilp.height = Math.max(1, Math.round(dp(ICON_BASE_DP) * size));
            iconView.setLayoutParams(ilp);
        }
        if (miaoButton != null) {
            ViewGroup.LayoutParams mlp = miaoButton.getLayoutParams();
            mlp.width = Math.max(1, Math.round(dp(BTN_BASE_DP) * size));
            mlp.height = Math.max(1, Math.round(dp(BTN_BASE_DP) * size));
            miaoButton.setLayoutParams(mlp);
            miaoButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Math.max(1, sp(BTN_TEXT_BASE_SP) * size));
        }

        // 整窗透明度（用户设置；与开关状态的 iconView alpha 是相乘关系）
        floatingView.setAlpha(CatConfig.clampFloatAlpha(cfg.floatAlpha) / 100f);

        // 触发重排，并让 WindowManager 按新的 wrap_content 尺寸更新窗口
        try {
            floatingView.requestLayout();
            if (windowManager != null && params != null) {
                windowManager.updateViewLayout(floatingView, params);
            }
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int sp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().scaledDensity);
    }

    /**
     * v1.1.1：刷新改写状态圆点颜色——绿=改写中，灰=已暂停。
     * 用颜色而不是透明度表达开关状态，用户把透明度调低也一眼可辨。
     */
    private void updateStatusDot(boolean enabled) {
        if (statusDot == null) {
            return;
        }
        UiKit.setDotColor(statusDot, enabled ? 0xFF2E7D32 : 0xFF9E9E9E);
    }

    /**
     * v1.1：设置页修改外观后调用——重新读配置并应用，悬浮窗无需重建。
     */
    public static void applyAppearanceFromSettings(final Context app) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                FloatingWindowService s = sInstance;
                if (s == null) {
                    return;
                }
                s.applyAppearance(CatConfig.load(
                        app.getApplicationContext()));
            }
        });
    }

    /**
     * v1.1：设置页点「恢复默认位置」后调用——
     * 悬浮窗活着就立即飞回默认位置，不用重开悬浮窗。
     */
    public static void resetPositionFromSettings(final Context app) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                FloatingWindowService s = sInstance;
                if (s == null || s.params == null) {
                    return;
                }
                s.params.x = 50;
                s.params.y = 100;
                try {
                    s.windowManager.updateViewLayout(s.floatingView, s.params);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * v1.1：通知栏（或其它入口）切换改写开关后，同步三处状态：
     *   图标透明度 / 常驻通知按钮文案 / 气泡提示。
     */
    public static void onProcessingToggled(final Context app, final boolean enabled) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                FloatingWindowService s = sInstance;
                if (s == null) {
                    return;
                }
                if (s.iconView != null) {
                    s.iconView.setAlpha(enabled ? 1.0f : 0.4f);
                }
                s.updateMiaoButtonState(enabled);
                s.updateStatusDot(enabled);
                s.refreshNotification();
                s.showResult(enabled ? "已恢复文本改写" : "已暂停文本改写", enabled);
            }
        });
    }

    /** 重建并发出常驻通知（按钮文案会按当前开关状态变化） */
    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable t) {
            AppLog.add("Float", "刷新常驻通知失败", t);
        }
    }

    /** 一键加喵（输入法通道，微信/QQ 通用） */
    private void doAppendNow() {
        if (!ShizukuInjector.isReady()) {
            // 这两句以前用 Toast，但人在微信里时本应用处于后台，Toast 会被系统拦掉，
            // 等于什么都没提示。改用悬浮窗气泡。
            showResult("一键加喵需要 Shizuku 授权（QQ 全自动不受影响）", false);
            return;
        }
        showResult("加喵中…", true);
        MiaoInjector.appendNow(this, new MiaoInjector.Callback() {
            @Override
            public void onDone(boolean ok, String msg) {
                // MiaoInjector 内部已经 Toast 过了，这里只更新按钮视觉状态
                updateMiaoButtonState(true);
            }
        });
    }

    /** 在悬浮窗下方弹出一条结果提示（替代被系统拦截的后台 Toast） */
    private void showBubbleInternal(String text, boolean ok) {
        if (windowManager == null || floatingView == null) {
            return;
        }
        removeBubble();
        try {
            float density = getResources().getDisplayMetrics().density;
            int pad = (int) (10 * density);

            TextView tv = new TextView(this);
            tv.setText(text == null ? "" : text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(pad + 6, pad, pad + 6, pad);
            tv.setMaxWidth((int) (240 * density));

            GradientDrawable gd = new GradientDrawable();
            gd.setColor(ok ? 0xE62E7D32 : 0xE6B3261E);
            gd.setCornerRadius(pad * 1.8f);
            tv.setBackgroundDrawable(gd);

            int flag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                flag = WindowManager.LayoutParams.TYPE_PHONE;
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    flag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = (params != null) ? params.x : 0;
            lp.y = ((params != null) ? params.y : 0) + (int) (64 * density);

            windowManager.addView(tv, lp);
            bubbleView = tv;

            tv.setAlpha(0f);
            tv.animate().alpha(1f).setDuration(150).start();

            MAIN_HANDLER.removeCallbacks(hideBubbleRunnable);
            MAIN_HANDLER.postDelayed(hideBubbleRunnable,
                    ok ? BUBBLE_DURATION_OK : BUBBLE_DURATION_FAIL);
        } catch (Throwable t) {
            bubbleView = null;
            AppLog.add("Float", "结果气泡显示失败", t);
        }
    }

    private void removeBubble() {
        if (bubbleView != null && windowManager != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Throwable ignored) {
            }
            bubbleView = null;
        }
    }

    private void updateMiaoButtonState(boolean enabled) {
        if (miaoButton == null) {
            return;
        }
        miaoButton.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private void startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须指定前台服务类型
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            // Android 7.1 及以下
            startForeground(NOTIFICATION_ID, new Notification());
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription("用于保持悬浮窗运行");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder
                .setContentTitle("OneTapMiao")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true);

        // 通知栏「加喵」按钮：没有悬浮窗权限时也能触发
        try {
            PendingIntent pi = buildAppendPendingIntent();
            if (pi != null) {
                builder.addAction(R.mipmap.ic_launcher, "加喵", pi);
            }
        } catch (Throwable t) {
            AppLog.add("Float", "通知栏加喵按钮创建失败", t);
        }

        // v1.1：通知栏「暂停/恢复改写」按钮——临时想正常打字，不用回主界面
        try {
            PendingIntent ti = buildTogglePendingIntent();
            if (ti != null) {
                boolean enabled = CatConfig.load(this).processingEnabled;
                builder.addAction(R.mipmap.ic_launcher,
                        notificationActionLabel(enabled), ti);
            }
        } catch (Throwable t) {
            AppLog.add("Float", "通知栏暂停按钮创建失败", t);
        }

        return builder.build();
    }

    private PendingIntent buildAppendPendingIntent() {
        return buildReceiverPendingIntent(
                MiaoActionReceiver.ACTION_APPEND, REQ_APPEND);
    }

    /** v1.1：暂停/恢复改写按钮的 PendingIntent */
    private PendingIntent buildTogglePendingIntent() {
        return buildReceiverPendingIntent(
                MiaoActionReceiver.ACTION_TOGGLE_PROCESSING, REQ_TOGGLE);
    }

    /** 构造发往 MiaoActionReceiver 的 PendingIntent（Android 12+ 需声明可变性） */
    private PendingIntent buildReceiverPendingIntent(String action, int reqCode) {
        Intent it = new Intent(this, MiaoActionReceiver.class);
        it.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 12+ 必须显式声明可变性
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, reqCode, it, flags);
    }

    private void toggleProcessingEnabled() {
        CatConfig config = CatConfig.load(this);
        config.processingEnabled = !config.processingEnabled;
        config.save(this);

        iconView.setAlpha(config.processingEnabled ? 1.0f : 0.4f);
        updateMiaoButtonState(config.processingEnabled);
        updateStatusDot(config.processingEnabled);

        String msg = config.processingEnabled ? "已开启文本改写" : "已关闭文本改写";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        // v1.1：悬浮窗上切换也要同步通知栏按钮文案
        refreshNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 支持外部显式触发（备用入口）
        if (intent != null && MiaoActionReceiver.ACTION_APPEND.equals(intent.getAction())) {
            doAppendNow();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        MAIN_HANDLER.removeCallbacks(hideBubbleRunnable);
        removeBubble();
        sInstance = null;
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Throwable ignored) {
            }
        }
        stopForeground(true);
    }
}
