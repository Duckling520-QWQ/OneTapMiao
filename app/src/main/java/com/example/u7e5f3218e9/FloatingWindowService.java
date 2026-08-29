package com.example.u7e5f3218e9;

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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
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

    /** 判定为「拖动」而非「点击」的位移阈值（px） */
    private static final int TOUCH_SLOP = 10;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private ImageView iconView;
    private TextView miaoButton;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                stopSelf();
                return;
            }
        }

        // 启动为前台服务（提升优先级）
        startForegroundServiceCompat();

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        iconView = floatingView.findViewById(R.id.floating_icon);
        miaoButton = floatingView.findViewById(R.id.btn_miao);
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

        return builder.build();
    }

    private PendingIntent buildAppendPendingIntent() {
        Intent it = new Intent(this, MiaoActionReceiver.class);
        it.setAction(MiaoActionReceiver.ACTION_APPEND);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 12+ 必须显式声明可变性
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(this, REQ_APPEND, it, flags);
    }

    private void toggleProcessingEnabled() {
        CatConfig config = CatConfig.load(this);
        config.processingEnabled = !config.processingEnabled;
        config.save(this);

        iconView.setAlpha(config.processingEnabled ? 1.0f : 0.4f);
        updateMiaoButtonState(config.processingEnabled);

        String msg = config.processingEnabled ? "已开启文本改写" : "已关闭文本改写";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
