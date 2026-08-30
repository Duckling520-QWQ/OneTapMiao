package com.onetapmiao.app;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 加喵成功的振动反馈（v1.1 新增）。
 *
 * 设计要点：
 *   1. 拆成 shouldVibrate（读配置）与 vibrateOnce（真振动）两个静态方法，
 *      测试时可以单独验证「要不要震」的判断逻辑，不用真摸到振动硬件。
 *   2. API 31（Android 12）起 Vibrator 要从 VibratorManager 取，
 *      旧系统直接 getSystemService(Vibrator.class)——两条路都要兼容。
 *   3. 只在「成功」时震 30ms：失败时用户需要读失败原因（视觉），
 *      震动只会添乱；成功时震一下是「送达确认」，和气泡是互补的。
 *   4. 一切异常吞掉：振动是锦上添花，绝不能因为它把加喵主流程弄挂。
 */
public final class VibratorHelper {

    /** 成功振动的时长（毫秒） */
    public static final long OK_VIBRATE_MS = 30;

    private VibratorHelper() {
        // 工具类，禁止实例化
    }

    /**
     * 判断当前是否应该振动：用户开了开关才震。
     *
     * @param ctx 任意 Context（内部转 ApplicationContext 读配置）
     * @return true = 应该振动
     */
    public static boolean shouldVibrate(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            CatConfig config = CatConfig.load(ctx.getApplicationContext());
            return config.enableVibrate;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 执行一次短振动。
     *
     * @param ctx      任意 Context
     * @param ms       时长（毫秒）
     * @return true = 已成功交给系统执行；false = 无振动硬件 / 被系统拒绝 / 异常
     */
    public static boolean vibrateOnce(Context ctx, long ms) {
        if (ctx == null || ms <= 0) {
            return false;
        }
        try {
            Context app = ctx.getApplicationContext();
            Vibrator vib = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm =
                        (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vib = vm.getDefaultVibrator();
                }
            } else {
                vib = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vib == null || !vib.hasVibrator()) {
                return false;
            }
            // API 26 起用 VibrationEffect；旧系统退化到已废弃但可用的 vibrate(long)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(
                        ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                //noinspection deprecation
                vib.vibrate(ms);
            }
            return true;
        } catch (Throwable t) {
            AppLog.add("Vib", "振动失败", t);
            return false;
        }
    }

    /**
     * 组合入口：开关开着才震（加喵成功场景用这个）。
     *
     * @return true = 实际执行了振动
     */
    public static boolean vibrateIfEnabled(Context ctx) {
        if (!shouldVibrate(ctx)) {
            return false;
        }
        return vibrateOnce(ctx, OK_VIBRATE_MS);
    }
}
