package com.onetapmiao.app;

import com.onetapmiao.app.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 一键加喵的统一入口 + 隐形输入法的状态管理。
 *
 * 【本版改动】
 *   上一版的 isImeEnabled() 只做了一件事：读 Settings.Secure.ENABLED_INPUT_METHODS，
 *   然后硬编码判断字符串里有没有 "com.onetapmiao.app/.InjectorImeService"。
 *   这个判断太脆，任何一个环节对不上就永远是「未启用」，而且你完全看不出是哪一环断了：
 *     - 包名写死，一旦改 applicationId 就失效；
 *     - 只认短名格式（pkg/.Cls），不认完全限定名（pkg/pkg.Cls）；
 *     - 只问了「已启用」这一个设置，没问「系统到底有没有把本应用识别成输入法」——
 *       而这才是「系统设置页里根本找不到我们的输入法」的真正原因（多半是缺 subtype）。
 *
 *   本版换成三层体检（inspect）：
 *     第 1 层  installed：系统输入法列表里有没有我们（manifest 声明是否正确）
 *     第 2 层  enabled  ：InputMethodManager 的已启用列表里有没有我们（权威）
 *     第 3 层  settings ：Settings.Secure 兜底，且按 ":" 切段比对，兼容两种类名格式
 *   三层结果会写进诊断日志，导出诊断就能一眼看出到底卡在哪。
 *
 *   另外新增：
 *     - enableImeNow()  ：有 Shizuku 时直接 `ime enable`，不用你手动去设置页翻
 *     - openImeSettings()：改成「先试着自动开，开不了再跳设置页」
 *     - dumpImeState()  ：一段人能读的状态文本，方便贴给我排查
 *
 * 【调用方】
 *   - FloatingWindowService 的「喵」按钮
 *   - 通知栏的「加喵」按钮（MiaoActionReceiver）
 *   - MainActivity 的状态卡片（isImeEnabled / openImeSettings，签名未变，MainActivity 不用改）
 *
 * 本类所有方法都可以在任意线程调用，Toast 和回调会自动切回主线程。
 */
public final class MiaoInjector {

    private MiaoInjector() {
    }

    public interface Callback {
        /**
         * @param ok  是否成功
         * @param msg 给用户的提示文案（已准备好，可直接 Toast）
         */
        void onDone(boolean ok, String msg);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "Miao";
    private static final String IME_CLASS_SIMPLE = "InjectorImeService";

    /** 日志节流：状态没变化就不重复刷诊断日志 */
    private static volatile String lastStateSignature = null;
    /** Settings.Secure 被系统禁读时只提示一次，避免刷屏 */
    private static volatile boolean settingsBlockedLogged = false;

    // =====================================================================
    // 一、一键加喵（读 → 剥离 → 加喵 → 写回）
    // =====================================================================

    /**
     * 异步执行「读 → 剥离 → 加喵 → 写回」。
     * 内部自己开线程，调用方不用管线程。
     */
    public static void appendNow(final Context ctx, final Callback cb) {
        if (ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        AppLog.init(app);
        ShizukuInjector.setImeId(app);

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String msg;

                try {
                    CatConfig cfg = CatConfig.load(app);
                    if (cfg != null && !cfg.processingEnabled) {
                        // 总开关关着（悬浮窗图标是半透明的），点「喵」不该静默失败
                        ok = false;
                        msg = "总开关已关闭——先点悬浮窗左边的图标开启";
                        AppLog.add(TAG, msg);
                    } else if (!ShizukuInjector.isReady()) {
                        // 旧版这里写死「未授权」，把「Shizuku 服务没启动」也说成用户没给权限。
                        // 现在用 diagWait() 的真实原因：先给 binder 1.5 秒推过来的机会，
                        // 再下定论。Toast 给结论，日志给细节。
                        ShizukuInjector.ShzDiag d = ShizukuInjector.diagWait(1500);
                        ok = false;
                        msg = d.title + "：" + d.action;
                        AppLog.add(TAG, "一键加喵前置检查不通过\n" + d.report());
                    } else {
                        ShizukuInjector.ImeResult r = ShizukuInjector.runImeJob(
                                InjectorImeService.MODE_APPEND, null, 0);
                        ok = r.ok;
                        msg = buildMessage(app, r);
                        AppLog.add(TAG, "一键加喵 " + (ok ? "成功" : "失败")
                                + "｜" + r.describe()
                                + "｜读=" + MiaoText.abbrev(r.readText, 30)
                                + "｜写=" + MiaoText.abbrev(r.writeText, 30));
                    }
                } catch (Throwable t) {
                    ok = false;
                    msg = "一键加喵异常：" + t.getClass().getSimpleName();
                    AppLog.add(TAG, msg, t);
                }

                postResultLong(app, msg, ok);
                postCallback(cb, ok, msg);
            }
        }, "miao-append").start();
    }

    /** 只读不写，用于排查「到底能不能读到微信输入框的内容」 */
    public static void readOnly(final Context ctx, final Callback cb) {
        if (ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        AppLog.init(app);
        ShizukuInjector.setImeId(app);

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String msg;
                try {
                    if (!ShizukuInjector.isReady()) {
                        ok = false;
                        msg = ShizukuInjector.diagWait(1500).title;
                    } else {
                        ShizukuInjector.ImeResult r = ShizukuInjector.runImeJob(
                                InjectorImeService.MODE_READ, null, 0);
                        ok = r.ok;
                        msg = r.ok ? ("读到：" + MiaoText.abbrev(r.readText, 50))
                                : ("读取失败：" + r.describe());
                    }
                } catch (Throwable t) {
                    ok = false;
                    msg = "读取异常：" + t.getClass().getSimpleName();
                    AppLog.add(TAG, msg, t);
                }
                postResult(app, msg, ok);
                postCallback(cb, ok, msg);
            }
        }, "miao-read").start();
    }

    private static String buildMessage(Context ctx, ShizukuInjector.ImeResult r) {
        if (r.ok) {
            return "已加喵 ✓";
        }
        String reason = r.describe();
        if (reason != null && reason.contains("输入框")) {
            return "没读到输入框——请先点一下输入框让光标在里面，再点「喵」";
        }
        if (reason != null && reason.contains("Shizuku")) {
            // 旧版不管真实原因，只要含「Shizuku」就一律回「未授权」，
            // 等于把「服务没启动」栽赃给用户。reason 现在本身就是 diag().oneLine()，直接透传。
            return reason;
        }
        return "加喵失败：" + reason;
    }

    // =====================================================================
    // 二、隐形输入法状态体检
    // =====================================================================

    /** 输入法状态的完整快照 */
    public static final class ImeState {
        /** 系统把所有已安装的输入法列出来，里面有没有我们（＝ manifest 声明是否被识别） */
        public boolean installed;
        /** 用户在系统设置里勾选启用了没有（权威源：InputMethodManager） */
        public boolean enabled;
        /** 当前默认输入法是不是我们（启用 ≠ 选中，一键加喵不需要默认，仅供显示） */
        public boolean selected;
        /** 我们从系统那边拿到的 id（短名格式 pkg/.Cls） */
        public String id;
        /** Settings.Secure.ENABLED_INPUT_METHODS 原文 */
        public String enabledSetting;
        /** Settings.Secure.DEFAULT_INPUT_METHOD 原文 */
        public String defaultSetting;
        /** 系统认为已安装的所有输入法 id（诊断用） */
        public final List<String> installedIds = new ArrayList<String>();
        /** 系统认为已启用的所有输入法 id（诊断用） */
        public final List<String> enabledIds = new ArrayList<String>();

        /** 一行结论，用于 UI 和 Toast */
        public String summary() {
            if (!installed) {
                return "系统未识别本应用为输入法（检查 AndroidManifest 与 res/xml/ime_method.xml）";
            }
            if (!enabled) {
                return "未启用";
            }
            return selected ? "已启用（且为当前输入法）" : "已启用";
        }

        /** 多行体检报告，用于诊断日志 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("IME 体检：").append(summary());
            sb.append("\n  目标 id      : ").append(id);
            sb.append("\n  installed    : ").append(installed).append(" ").append(installedIds);
            sb.append("\n  enabled(IMM) : ").append(enabled).append(" ").append(enabledIds);
            sb.append("\n  selected     : ").append(selected);
            sb.append("\n  enabled 设置  : ").append(enabledSetting);
            sb.append("\n  default 设置  : ").append(defaultSetting);
            return sb.toString();
        }
    }

    /**
     * 全面体检。三层判定，任何一层失败都能在报告里看出来。
     * 结果会写进诊断日志（状态无变化时不重复写）。
     */
    public static ImeState inspect(Context ctx) {
        ImeState st = new ImeState();
        if (ctx == null) {
            return st;
        }
        Context app = ctx.getApplicationContext();
        String pkg = app.getPackageName();
        // 顺手把 ShizukuInjector 那边要用的输入法 ID 校正成真实包名，
        // 免得 applicationId 和写死的常量不一致时 ime enable / ime set 全部打空
        ShizukuInjector.setImeId(app);
        st.id = ShizukuInjector.getImeId();

        // ---- 第 1、2 层：问 InputMethodManager ----
        try {
            Object svc = app.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (svc instanceof InputMethodManager) {
                InputMethodManager imm = (InputMethodManager) svc;

                List<InputMethodInfo> all = imm.getInputMethodList();
                if (all != null) {
                    for (InputMethodInfo info : all) {
                        String id = info.getId();
                        st.installedIds.add(id);
                        if (matchIme(id, pkg)) {
                            st.installed = true;
                        }
                    }
                }

                List<InputMethodInfo> on = imm.getEnabledInputMethodList();
                if (on != null) {
                    for (InputMethodInfo info : on) {
                        String id = info.getId();
                        st.enabledIds.add(id);
                        if (matchIme(id, pkg)) {
                            st.enabled = true;
                            // 走到这里说明系统已经认了，installed 必然也成立
                            st.installed = true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.add(TAG, "InputMethodManager 查询失败", t);
        }

        // ---- 第 3 层：Settings.Secure 兜底 ----
        try {
            st.enabledSetting = Settings.Secure.getString(
                    app.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
            st.defaultSetting = Settings.Secure.getString(
                    app.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (SecurityException e) {
            // Android 14（targetSdk 34）起，ENABLED_INPUT_METHODS 和
            // DEFAULT_INPUT_METHOD 不再对第三方应用可读，读了就抛 SecurityException。
            // 这是系统的预期行为，不是故障：输入法判定以 InputMethodManager 为准，
            // Settings 这一层本来只是兜底。所以只记一次，别每次刷新都刷两行日志。
            if (!settingsBlockedLogged) {
                settingsBlockedLogged = true;
                AppLog.add(TAG, "Settings.Secure 的输入法字段在本系统不可读"
                        + "（Android 14+ 对 targetSdk 34 的限制），已改用 InputMethodManager 判定，属正常情况");
            }
        } catch (Throwable t) {
            AppLog.add(TAG, "Settings.Secure 查询失败", t);
        }

        // IMM 说没启用时，再信一次设置原文（个别 ROM 的 IMM 列表刷新有延迟）
        if (!st.enabled && st.enabledSetting != null && !st.enabledSetting.isEmpty()) {
            String[] parts = st.enabledSetting.split(":");
            for (String p : parts) {
                if (matchIme(p.trim(), pkg)) {
                    st.enabled = true;
                    st.installed = true;
                    break;
                }
            }
        }
        st.selected = st.defaultSetting != null && matchIme(st.defaultSetting.trim(), pkg);

        logStateOnce(app, st);
        return st;
    }

    /**
     * id 匹配：同时兼容
     *   com.onetapmiao.app/.InjectorImeService        （短名，系统存储用这个）
     *   com.onetapmiao.app/com.onetapmiao.app.InjectorImeService （完全限定名）
     */
    private static boolean matchIme(String id, String pkg) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (id.equals(pkg + "/." + IME_CLASS_SIMPLE)) {
            return true;
        }
        if (id.equals(pkg + "/" + pkg + "." + IME_CLASS_SIMPLE)) {
            return true;
        }
        return id.startsWith(pkg + "/") && id.endsWith("." + IME_CLASS_SIMPLE);
    }

    /** 状态有变化才落盘，避免 onResume 反复刷新把诊断日志刷爆 */
    private static void logStateOnce(Context app, ImeState st) {
        String sig = st.installed + "|" + st.enabled + "|" + st.selected
                + "|" + st.enabledSetting + "|" + st.defaultSetting;
        if (sig.equals(lastStateSignature)) {
            return;
        }
        lastStateSignature = sig;
        AppLog.init(app);
        AppLog.add(TAG, st.report());
    }

    /**
     * 我们的隐形输入法是否已在系统里启用。
     * 签名和上一版一致，MainActivity 不用改。
     */
    public static boolean isImeEnabled(Context ctx) {
        return inspect(ctx).enabled;
    }

    /** 是否已被选为当前默认输入法 */
    public static boolean isImeSelected(Context ctx) {
        return inspect(ctx).selected;
    }

    /** 一段可以直接贴给别人排查的状态文本 */
    public static String dumpImeState(Context ctx) {
        ImeState st = inspect(ctx);
        StringBuilder sb = new StringBuilder(st.report());
        ShizukuInjector.ShzDiag sd = ShizukuInjector.diag();
        sb.append("\n  Shizuku 状态 : ").append(sd.title);
        sb.append("\n  Shizuku 处理 : ").append(sd.action);
        sb.append("\n  Shizuku 细节 : ").append(sd.detail);
        return sb.toString();
    }

    // =====================================================================
    // 三、启用输入法
    // =====================================================================

    /**
     * 用 Shizuku 直接 `ime enable`，不用手动去设置页翻。
     * 只在 Shizuku 已授权时有效；没授权就返回 false，让调用方去跳设置页。
     */
    public static void enableImeNow(final Context ctx, final Callback cb) {
        if (ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        AppLog.init(app);
        ShizukuInjector.setImeId(app);

        final ImeState before = inspect(app);
        if (before.enabled) {
            postCallback(cb, true, "隐形输入法已启用");
            return;
        }
        if (!before.installed) {
            String msg = "系统没把本应用识别为输入法，先在设置页找找；找不到就是 res/xml/ime_method.xml 没配 subtype";
            AppLog.add(TAG, msg + "\n" + before.report());
            postCallback(cb, false, msg);
            return;
        }
        // 注意：Shizuku 检查放在子线程里做，因为它可能要等 binder 推过来（最多 1.5 秒），
        // 在主线程查会拿到偏旧的结论，然后又变成「明明在运行却说没权限」
        new Thread(new Runnable() {
            @Override
            public void run() {
                ShizukuInjector.ShzDiag shz = ShizukuInjector.diagWait(1500);
                if (!shz.isReady()) {
                    AppLog.add(TAG, "自动启用输入法前置检查不通过\n" + shz.report());
                    postResultLong(app, shz.title + "：" + shz.action, false);
                    postCallback(cb, false, shz.title + "——" + shz.action);
                    return;
                }
                doEnable(app, before, cb);
            }
        }, "miao-ime-check").start();
    }

    private static void doEnable(final Context app, final ImeState before, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String msg;
                try {
                    // 清掉节流标记，强制把这次结果写进日志
                    lastStateSignature = null;

                    String out = shizukuExec(Arrays.asList("ime", "enable", before.id), 3000);
                    AppLog.add(TAG, "ime enable 输出: " + out);

                    // 以复查为准：命令输出成功不代表真的勾上了
                    ImeState after = inspect(app);
                    ok = after.enabled;
                    msg = ok ? "隐形输入法已启用 ✓"
                            : "自动启用失败，请手动在系统设置里勾选（" + out + "）";
                    if (!ok) {
                        AppLog.add(TAG, "ime enable 后复查仍未启用\n" + after.report());
                    }
                } catch (Throwable t) {
                    msg = "自动启用异常：" + t.getClass().getSimpleName();
                    AppLog.add(TAG, msg, t);
                }
                postResult(app, msg, ok);
                postCallback(cb, ok, msg);
            }
        }, "miao-ime-enable").start();
    }

    /**
     * 跳系统输入法设置页。
     * 本版改为：先试着用 Shizuku 自动启用，成功就不跳转（少点好几步）；
     * 失败或没有 Shizuku 才真的跳设置页。
     * 签名和上一版一致，MainActivity 不用改。
     */
    public static void openImeSettings(final Context ctx) {
        if (ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();

        // 已经启用了就不用折腾，直接把设置页打开让用户自己核对
        if (isImeEnabled(app)) {
            startImeSettingsActivity(app);
            return;
        }

        enableImeNow(app, new Callback() {
            @Override
            public void onDone(boolean ok, String msg) {
                if (!ok) {
                    // 跳设置页的同时，把 Shizuku 的分级诊断 + 命令探针写进诊断日志。
                    // 这样你导出日志就能看到：到底是服务没运行、没授权，还是命令通道不通。
                    logShizukuDiagnostics(app);
                    startImeSettingsActivity(app);
                }
            }
        });
    }

    /**
     * 把 Shizuku 的分级诊断 + 命令通道探针写进诊断日志。
     * 探针要跑一条真实命令（最多 3 秒），所以放子线程。
     */
    public static void logShizukuDiagnostics(final Context ctx) {
        if (ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        AppLog.init(app);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ShizukuInjector.ShzDiag d = ShizukuInjector.diag();
                    AppLog.add(TAG, "Shizuku 主动诊断\n" + d.report()
                            + "\n  探针结果: " + ShizukuInjector.probe());
                } catch (Throwable t) {
                    AppLog.add(TAG, "Shizuku 诊断异常", t);
                }
            }
        }, "miao-shz-diag").start();
    }

    /** 真正拉起系统输入法设置页，带两级降级 */
    private static void startImeSettingsActivity(Context ctx) {
        try {
            Intent it = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return;
        } catch (Throwable t) {
            AppLog.add(TAG, "ACTION_INPUT_METHOD_SETTINGS 拉起失败", t);
        }
        try {
            Intent it = new Intent("android.settings.INPUT_METHOD_SETTINGS");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            AppLog.add(TAG, "跳转输入法设置页失败", t);
            try {
                Toast.makeText(ctx, "无法打开输入法设置页，请手动进系统设置 → 系统 → 语言和输入法",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // 四、Shizuku 命令执行（本文件自包含，不依赖 ShizukuInjector 的私有方法）
    // =====================================================================

    private static Method newProcessMethod;

    /** 执行一条命令，返回合并后的输出；失败返回错误信息字符串 */
    private static String shizukuExec(List<String> args, long timeoutMs) {
        try {
            if (newProcessMethod == null) {
                newProcessMethod = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);
            }
            final Process p = (Process) newProcessMethod.invoke(
                    null, (Object) args.toArray(new String[0]), null, null);
            if (p == null) {
                return "<进程为空>";
            }

            final StringBuilder sb = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        java.io.InputStream in = p.getInputStream();
                        while ((n = in.read(buf)) > 0) {
                            sb.append(new String(buf, 0, n, "UTF-8"));
                        }
                        in = p.getErrorStream();
                        while ((n = in.read(buf)) > 0) {
                            sb.append(new String(buf, 0, n, "UTF-8"));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            }, "ime-enable-reader");
            reader.start();

            boolean done;
            try {
                done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = false;
            }
            int code;
            try {
                code = p.exitValue();
            } catch (IllegalArgumentException e) {
                // 进程尚未退出：标准实现抛 IllegalThreadStateException，
                // Shizuku 的 Process 则抛其父类 IllegalArgumentException。
                // 这里抓父类，两种都覆盖到，否则超时会被误报成「执行异常」。
                code = done ? -1 : -2;   // -2 = 超时
            }
            String out = sb.toString().trim();
            if (out.isEmpty()) {
                out = "<无输出>";
            }
            return out + "（exit=" + code + "）";
        } catch (Throwable t) {
            return "<执行异常 " + t.getClass().getSimpleName() + ": " + t.getMessage() + ">";
        }
    }

    // =====================================================================
    // 五、主线程工具
    // =====================================================================

    /**
     * 把结果告诉用户。
     *
     * 【为什么不能只用 Toast】
     *   Android 10 起，后台应用弹出的 Toast 会被系统直接拦截。你在微信里点「喵」
     *   时本应用处于后台，Toast 根本不显示——只有回到自己的 App 才看得到。
     *
     *   所以这里走了三层反馈，互为兜底：
     *     1. 悬浮窗气泡：走 SYSTEM_ALERT_WINDOW，不受后台限制，
     *        而且点「喵」时手指和视线就在悬浮窗上，是最直接的反馈
     *     2. Toast：应用在前台时正常显示，和气泡不冲突
     *     3. 通知栏：仅失败且悬浮窗没开时启用，确保失败原因一定看得见
     */
    private static void postResult(final Context app, final String msg, final boolean ok) {
        postResultInternal(app, msg, ok, false);
    }

    private static void postResultLong(final Context app, final String msg, final boolean ok) {
        postResultInternal(app, msg, ok, true);
    }

    private static void postResultInternal(final Context app, final String msg,
                                           final boolean ok, final boolean longToast) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                // 1. 悬浮窗气泡
                try {
                    FloatingWindowService.showResult(msg, ok);
                } catch (Throwable ignored) {
                }
                // v1.1：成功时轻微振动一下（用户可在设置里关掉；失败不震，
                //       因为失败要专心读原因，震动只会添乱）
                if (ok) {
                    try {
                        VibratorHelper.vibrateIfEnabled(app);
                    } catch (Throwable ignored) {
                    }
                }
                // 2. Toast（前台时可见）
                try {
                    Toast.makeText(app, msg,
                            longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
                // 3. 失败且没有悬浮窗时，用通知栏补救——失败原因必须让人看见
                if (!ok && !FloatingWindowService.isShowing()) {
                    notifyResult(app, msg, ok);
                }
            }
        });
    }

    private static final int RESULT_NOTIFICATION_ID = 2001;
    private static final String RESULT_CHANNEL_ID = "miao_result";

    private static void notifyResult(Context app, String msg, boolean ok) {
        try {
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        RESULT_CHANNEL_ID, "加喵结果", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(app, RESULT_CHANNEL_ID);
            } else {
                b = new Notification.Builder(app);
            }
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(ok ? "加喵成功" : "加喵失败")
                    .setContentText(msg)
                    .setAutoCancel(true)
                    .setDefaults(0);
            nm.notify(RESULT_NOTIFICATION_ID, b.build());
        } catch (Throwable t) {
            AppLog.add(TAG, "结果通知发送失败", t);
        }
    }

    private static void postCallback(final Callback cb, final boolean ok, final String msg) {
        if (cb == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onDone(ok, msg);
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
