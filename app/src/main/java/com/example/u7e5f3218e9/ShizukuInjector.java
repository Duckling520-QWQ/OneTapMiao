package com.example.u7e5f3218e9;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 执行 shell 命令完成文本注入。
 *
 * 【本版改动（方案）】
 *   1. 新增 runImeJob(int mode, ...)：一次输入法切换内完成「读 → 处理 → 写」。
 *      这是微信可用的关键——旧接口只能写，原文得靠无障碍读，
 *      而微信的节点树是空的，根本读不到原文。
 *   2. 任务模型换成 InjectorImeService.Job，带 CAS 防重入，
 *      不再靠「把 static 字段置 null」来防重复。
 *   3. 超时分级：只要写 → 6s；要读→处理→写 → 9s（切换输入法本身就要几百 ms）。
 *   4. 切回原输入法加了复核：失败时重试一次，避免用户被卡在我们的隐形键盘上。
 *   5. 原输入法为空（null）时不再硬切，改为记日志，避免把用户输入法搞丢。
 *
 * 【保持不变】
 *   - injectViaIme(int, String) / injectText(int, String) 两个旧公开方法签名不变，
 *     内部改为转调新的 runImeJob，行为等价，老调用方零改动。
 *   - exec / execForOutput / pump 底层命令执行逻辑原样保留。
 */
public final class ShizukuInjector {
    private static final String TAG = "ShizukuInjector";

    // ---- 输入法 ID ----
    // 以前这里写死成 "com.example.u7e5f3218e9/.InjectorImeService"。
    // 一旦 build.gradle 里的 applicationId 和这个常量不一致（改过包名、
    // 或者用别的环境打包时 applicationId 带后缀），ime enable / ime set
    // 就会去操作一个根本不存在的组件，全部失败，而且报错看着还像权限问题。
    // 改成运行时按真实包名拼，没设置过就退回原来的默认值兜底。
    private static final String IME_ID_FALLBACK = "com.example.u7e5f3218e9/.InjectorImeService";
    private static final String IME_CLASS_SIMPLE = "InjectorImeService";
    private static volatile String sImeId = null;

    /**
     * 用真实包名设置输入法 ID。
     * 在 App 启动时调一次即可（MiaoInjector.inspect() 每次都会顺手设置，
     * 所以只要打开过主界面就一定是正确的）。
     */
    public static void setImeId(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            sImeId = ctx.getPackageName() + "/." + IME_CLASS_SIMPLE;
        } catch (Throwable ignored) {
        }
    }

    /** 当前使用的输入法 ID（短名格式 pkg/.Cls） */
    public static String getImeId() {
        return sImeId == null ? IME_ID_FALLBACK : sImeId;
    }

    private static final int KEYCODE_DEL = 67;
    private static final int KEYCODE_MOVE_END = 123;
    private static final int MAX_DELETE = 500;
    private static final int MAX_TEXT_LEN = 1000;
    private static final int BATCH_KEYS = 100;
    private static final int TEXT_CHUNK = 500;

    // ---- 超时时间 ----
    // 关键点：`ime` / `settings` / `input` 这些命令都不是原生二进制，
    // 它们是 /system/bin 下的脚本，最终通过 app_process 启动一个 ART 虚拟机来跑。
    // 冷启动一次 ART 在手机上常常要 1~3 秒，低端机更久，首次执行还要预热缓存。
    // 旧值 IME_SET_TIMEOUT=2000ms 在真机上基本必超时——
    // 这就是「Shizuku 明明连上了，ime 却失败」的主因。
    private static final long CMD_TIMEOUT_DEFAULT = 5000;   // 普通命令
    private static final long IME_SET_TIMEOUT = 5000;       // ime enable / ime set
    private static final long IME_JOB_WAIT = 3000;          // 等 IME 侧读写完成
    private static final long TOTAL_TIMEOUT_WRITE = 15000;  // 只写
    private static final long TOTAL_TIMEOUT_APPEND = 25000; // 读→处理→写（含切过去再切回来）

    private static Method newProcessMethod;

    private ShizukuInjector() {
    }

    // ============== Shizuku 状态诊断 ==============
    //
    // 【为什么要有这一整套】
    //   旧版 isReady() 把四种完全不同的情况全塞成一个 false：
    //     ① Shizuku 服务没在运行（无线调试断开、手机重启后服务没起来、后台被杀）
    //     ② Shizuku 版本太老（pre-v11）
    //     ③ 服务在跑，但本应用确实没被授权
    //     ④ 调用过程抛异常（binder 死了、Shizuku 没装、API 签名变了）
    //   然后上层统一提示「Shizuku 未授权」——等于把「服务没启动」说成「你没给权限」。
    //   你在 Shizuku 里明明勾了允许，App 却一口咬定没授权，就是被这个误导了。
    //   本版把四种情况分开，让提示说人话，并且把细节写进诊断日志。

    /** 就绪 */
    public static final int SHZ_READY = 0;
    /** Shizuku 服务没运行（binder 不通）——注意：这不是没授权 */
    public static final int SHZ_NOT_RUNNING = 1;
    /** Shizuku 版本过旧 */
    public static final int SHZ_PRE_V11 = 2;
    /** 服务在跑，但本应用确实没被授权 */
    public static final int SHZ_NOT_GRANTED = 3;
    /** 调用过程抛异常 */
    public static final int SHZ_EXCEPTION = 4;

    public static final class ShzDiag {
        public final int code;
        /** 一句话状态，给人看 */
        public final String title;
        /** 具体该做什么 */
        public final String action;
        /** 技术细节，写日志用 */
        public final String detail;
        public final Throwable error;

        ShzDiag(int code, String title, String action, String detail, Throwable error) {
            this.code = code;
            this.title = title;
            this.action = action;
            this.detail = detail;
            this.error = error;
        }

        public boolean isReady() {
            return code == SHZ_READY;
        }

        /** Toast 用的一行：状态 + 该怎么办 */
        public String oneLine() {
            if (code == SHZ_READY) {
                return "Shizuku 已就绪";
            }
            return title + "——" + action;
        }

        /** 多行报告，写诊断日志 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("Shizuku 诊断: ").append(title).append(" (code=").append(code).append(")");
            sb.append("\n  该做什么: ").append(action);
            sb.append("\n  技术细节: ").append(detail);
            if (error != null) {
                sb.append("\n  异常: ").append(error.getClass().getName())
                        .append(": ").append(error.getMessage());
            }
            return sb.toString();
        }
    }

    /**
     * 分级诊断。主线程可直接调用（内部只查 binder 和权限，不做命令执行）。
     */
    public static ShzDiag diag() {
        boolean ping;
        try {
            ping = Shizuku.pingBinder();
        } catch (Throwable t) {
            return new ShzDiag(SHZ_EXCEPTION, "Shizuku 调用异常",
                    "确认装的是 Shizuku（不是名字相似的其它 App），重启手机后先启动 Shizuku 再打开本应用",
                    "pingBinder 抛异常", t);
        }
        if (!ping) {
            return new ShzDiag(SHZ_NOT_RUNNING, "Shizuku 服务未运行",
                    "打开 Shizuku App 看到「Shizuku 正在运行」再回来；手机重启 / 无线调试断开后需要重新启动它",
                    "pingBinder=false", null);
        }

        boolean preV11;
        try {
            preV11 = Shizuku.isPreV11();
        } catch (Throwable t) {
            return new ShzDiag(SHZ_EXCEPTION, "Shizuku 版本检查失败",
                    "把 Shizuku 升级到最新版", "isPreV11 抛异常", t);
        }
        if (preV11) {
            return new ShzDiag(SHZ_PRE_V11, "Shizuku 版本过旧",
                    "升级到 v11 及以上", "isPreV11=true", null);
        }

        int granted;
        try {
            granted = Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            return new ShzDiag(SHZ_EXCEPTION, "查询 Shizuku 权限失败",
                    "打开 Shizuku → 已授权应用，删掉本应用的授权记录，再回本页点「授权 Shizuku」",
                    "checkSelfPermission 抛异常", t);
        }
        if (granted != PackageManager.PERMISSION_GRANTED) {
            return new ShzDiag(SHZ_NOT_GRANTED, "Shizuku 未授权本应用",
                    "打开 Shizuku → 已授权应用，确认本应用在列表里；换过打包环境（签名变了）要删掉重授",
                    "checkSelfPermission=" + granted, null);
        }
        return new ShzDiag(SHZ_READY, "Shizuku 已就绪", "无需操作", "PERMISSION_GRANTED", null);
    }

    public static boolean isReady() {
        return diag().isReady();
    }

    /** 上层提示用的准确文案，替代以前那句一律的「Shizuku 未授权」 */
    public static String notReadyMessage() {
        return diag().oneLine();
    }

    /**
     * 带等待的诊断。
     *
     * 【为什么需要】
     *   Shizuku 的 binder 是 Shizuku App **异步推送**给各个应用的，不是你一调就有。
     *   刚开机、刚重启过 Shizuku 服务、刚重装过本应用——这几种情况下，binder 可能要
     *   几百毫秒甚至一两秒才送到。在那之前 pingBinder() 一律是 false，于是就出现
     *   「Shizuku 明明在运行，App 却说没检测到 / 没权限」这种见鬼的现象。
     *
     * 【注意】会阻塞，必须在子线程调用。
     *
     * @param maxWaitMs 最多等多久拿到 binder
     */
    public static ShzDiag diagWait(long maxWaitMs) {
        ShzDiag d = diag();
        // 主线程绝不阻塞——宁可拿到一个可能偏旧的结论，也不能卡住界面
        if (android.os.Looper.getMainLooper().getThread() == Thread.currentThread()) {
            return d;
        }
        // 只有「binder 还没推过来」这一档值得等；没授权、版本旧、异常，等也没用
        if (d.code != SHZ_NOT_RUNNING) {
            return d;
        }
        long start = SystemClock.uptimeMillis();
        long deadline = start + maxWaitMs;
        // 硬保险：循环次数封顶。uptimeMillis 万一不推进（某些 ROM 的省电策略、
        // 或者取到异常时钟源），纯靠时间比较就会变成死循环，把线程永久卡住。
        int maxLoops = (int) Math.max(1, Math.min(100, maxWaitMs / 100 + 1));
        for (int i = 0; i < maxLoops; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            d = diag();
            if (d.code != SHZ_NOT_RUNNING) {
                return d;
            }
            long now = SystemClock.uptimeMillis();
            // now < start 是防时钟回绕；now >= deadline 是正常到点
            if (now < start || now >= deadline) {
                break;
            }
        }
        return d;
    }

    /**
     * 命令通道探针：真的跑一条命令，验证「有权限」之外 newProcess 也通。
     *
     * 存在这样一种情况：权限查着是有的，但反射调用 Shizuku.newProcess 失败
     * （Shizuku 版本差异导致签名对不上），这种失败只会体现在命令执行上，
     * 光看 isReady() 永远看不出来。所以失败排查时跑一次这个。
     *
     * 会阻塞最多 3 秒，必须在子线程调用。
     */
    public static String probe() {
        ShzDiag d = diag();
        if (!d.isReady()) {
            return "未执行命令（" + d.title + "）";
        }
        ExecResult r = execResult(Arrays.asList("ime", "list", "-s"), CMD_TIMEOUT_DEFAULT);
        if (!r.ok) {
            // 把 stdout 和 stderr 全带回来——失败原因八成写在 stderr 里
            return "命令通道不通：ime list -s " + r.describe()
                    + " stdout=[" + r.out + "] stderr=[" + r.err + "]。"
                    + "常见原因是 Shizuku 的 newProcess 反射签名与本代码不符，或 Shizuku 服务权限不完整";
        }
        return "命令通道正常（" + r.costMs + "ms）。系统输入法列表：\n" + r.out;
    }

    /** 一次命令执行的完整结果：成功与否、退出码、标准输出、错误输出、耗时 */
    public static final class ExecResult {
        public final boolean ok;
        /** 退出码；-2 表示执行前就异常了，-1 表示超时或取不到 */
        public final int exitCode;
        public final String out;
        public final String err;
        public final long costMs;
        public final boolean timeout;

        ExecResult(boolean ok, int exitCode, String out, String err, long costMs, boolean timeout) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
            this.costMs = costMs;
            this.timeout = timeout;
        }

        /** 一行摘要 */
        public String describe() {
            if (timeout) {
                return "超时（" + costMs + "ms 未结束）";
            }
            return "exit=" + exitCode + " 耗时=" + costMs + "ms"
                    + (err.isEmpty() ? "" : " err=" + abbrev(err, 200));
        }

        /** 多行完整信息，写诊断日志用 */
        public String full() {
            StringBuilder sb = new StringBuilder();
            sb.append("退出码=").append(exitCode)
                    .append(" 耗时=").append(costMs).append("ms 超时=").append(timeout);
            sb.append("\n    stdout: ").append(out.isEmpty() ? "<空>" : abbrev(out, 500));
            sb.append("\n    stderr: ").append(err.isEmpty() ? "<空>" : abbrev(err, 500));
            return sb.toString();
        }
    }

    /** 一次输入法任务的结果 */
    public static final class ImeResult {
        public boolean ok = false;
        /** 读到的原文（MODE_APPEND / MODE_READ 有值） */
        public String readText = null;
        /** 实际写入的文本 */
        public String writeText = null;
        /** 失败原因（成功时为 null） */
        public String failReason = null;

        public String describe() {
            if (ok) {
                return "成功";
            }
            return (failReason == null) ? "失败（未知原因）" : failReason;
        }
    }

    // ============== 新增：统一 IME 任务入口 ==============

    /**
     * 执行一次输入法任务（读 / 写 / 读改写），带总超时。
     *
     * @param mode         InjectorImeService.MODE_APPEND / MODE_WRITE / MODE_READ
     * @param text         MODE_WRITE 时要写入的文本，其它模式传 null
     * @param deleteBefore MODE_WRITE 时向前删除的字符数，一般传 0
     */
    public static ImeResult runImeJob(final int mode, final String text, final int deleteBefore) {
        ImeResult fail = new ImeResult();

        // runImeJob 一定在子线程，等一下 binder 更稳（刚开机/刚重启 Shizuku 时它是异步送到的）
        ShzDiag shz = diagWait(1500);
        if (!shz.isReady()) {
            // 旧版这里一律写「Shizuku 未授权」，把「服务没启动」也说成「用户没给权限」
            fail.failReason = shz.oneLine();
            AppLog.add(TAG, shz.report());
            return fail;
        }
        if (mode == InjectorImeService.MODE_WRITE) {
            if (text == null || text.length() > MAX_TEXT_LEN) {
                fail.failReason = "待写文本为空或过长（上限 " + MAX_TEXT_LEN + "）";
                return fail;
            }
            if (deleteBefore > MAX_DELETE) {
                fail.failReason = "删除字符数超限";
                return fail;
            }
        }

        final long totalTimeout =
                (mode == InjectorImeService.MODE_APPEND) ? TOTAL_TIMEOUT_APPEND : TOTAL_TIMEOUT_WRITE;

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<ImeResult> future = pool.submit(new Callable<ImeResult>() {
            @Override
            public ImeResult call() {
                return doImeJob(mode, text, deleteBefore);
            }
        });
        try {
            ImeResult r = future.get(totalTimeout, TimeUnit.MILLISECONDS);
            return (r != null) ? r : fail;
        } catch (Throwable t) {
            Log.w(TAG, "IME 任务超时", t);
            fail.failReason = "IME 任务超时（" + totalTimeout + "ms）";
            AppLog.add(TAG, fail.failReason);
            future.cancel(true);
            return fail;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ImeResult doImeJob(int mode, String text, int deleteBefore) {
        ImeResult res = new ImeResult();

        // 1. 记录当前输入法（settings 也是 app_process 命令，别给太短的超时）
        String origIme = execForOutput(
                Arrays.asList("settings", "get", "secure", "default_input_method"),
                CMD_TIMEOUT_DEFAULT);
        AppLog.add(TAG, "原输入法: " + origIme);

        // 2. 装载任务
        InjectorImeService.Job job = new InjectorImeService.Job(mode, text, deleteBefore);
        if (!InjectorImeService.armJob(job)) {
            res.failReason = "上一个输入法任务还没结束，本次取消";
            AppLog.add(TAG, res.failReason);
            return res;
        }

        try {
            // 3. 切换到我们的隐形输入法
            //    ime 命令走 app_process，冷启动慢，超时给 5 秒并且失败重试一次。
            //    只有「超时」才值得重试；退出码非 0 说明命令真跑了但被拒，重试没意义。
            ExecResult rEnable = execResult(
                    Arrays.asList("ime", "enable", getImeId()), IME_SET_TIMEOUT);
            if (!rEnable.ok && rEnable.timeout) {
                AppLog.add(TAG, "ime enable 超时，重试一次\n" + rEnable.full());
                rEnable = execResult(Arrays.asList("ime", "enable", getImeId()), IME_SET_TIMEOUT);
            }
            if (!rEnable.ok) {
                res.failReason = "ime enable 失败——" + rEnable.describe();
                // 权限有了却执行不了，就把 Shizuku 诊断和命令探针一起记下来
                AppLog.add(TAG, res.failReason + "\n" + rEnable.full()
                        + "\n" + diag().report()
                        + "\n  探针: " + probe());
                return res;
            }
            AppLog.add(TAG, "ime enable 成功（" + rEnable.costMs + "ms）");

            ExecResult rSet = execResult(
                    Arrays.asList("ime", "set", getImeId()), IME_SET_TIMEOUT);
            if (!rSet.ok && rSet.timeout) {
                AppLog.add(TAG, "ime set 超时，重试一次\n" + rSet.full());
                rSet = execResult(Arrays.asList("ime", "set", getImeId()), IME_SET_TIMEOUT);
            }
            if (!rSet.ok) {
                res.failReason = "ime set 失败——" + rSet.describe()
                        + "（若提示找不到输入法，检查 res/xml/ime_method.xml 是否配了 subtype）";
                AppLog.add(TAG, res.failReason + "\n" + rSet.full()
                        + "\n  探针: " + probe());
                return res;
            }
            AppLog.add(TAG, "ime set 成功（" + rSet.costMs + "ms）");

            // 4. 等待 IME 侧完成（IME 在它自己的主线程里做读、处理、写）
            boolean finished;
            try {
                finished = job.done.await(IME_JOB_WAIT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }

            res.readText = job.readText;
            res.writeText = job.writeText;
            res.ok = job.ok;
            res.failReason = job.failReason;

            if (!finished) {
                res.ok = false;
                res.failReason = "等待输入法回调超时——请确认光标在输入框里";
                AppLog.add(TAG, res.failReason);
            }
        } finally {
            InjectorImeService.pendingJob = null;
            // 5. 无论成败都切回原输入法，失败再试一次
            restoreIme(origIme);
        }
        return res;
    }

    private static void restoreIme(String origIme) {
        if (origIme == null || origIme.isEmpty() || "null".equals(origIme)) {
            AppLog.add(TAG, "原输入法为空，不执行切回（避免把用户输入法搞丢）");
            return;
        }
        if (exec(Arrays.asList("ime", "set", origIme), IME_SET_TIMEOUT)) {
            return;
        }
        AppLog.add(TAG, "切回原输入法失败，重试一次: " + origIme);
        if (!exec(Arrays.asList("ime", "set", origIme), IME_SET_TIMEOUT)) {
            AppLog.add(TAG, "切回仍然失败！请到「设置 → 系统 → 语言和输入法」手动切回");
        }
    }

    // ============== 旧接口（保留，内部转调新逻辑） ==============

    /**
     * 通过临时切换输入法完成注入（同步，带总超时）。
     *
     * @param deleteCount 光标前需删除的字符数（= 输入框当前内容长度）
     * @param text        要写入的最终文本
     * @deprecated 新代码请用 {@link #runImeJob(int, String, int)}
     */
    public static boolean injectViaIme(final int deleteCount, final String text) {
        ImeResult r = runImeJob(InjectorImeService.MODE_WRITE, text, deleteCount);
        if (r.ok) {
            return true;
        }
        AppLog.add(TAG, "injectViaIme 失败: " + r.describe());
        return false;
    }

    // ============== 模式 B：input 命令直写（备用） ==============

    public static boolean injectText(final int deleteCount, final String text) {
        if (!isReady()) return false;
        if (deleteCount > MAX_DELETE) return false;
        if (text == null || text.length() > MAX_TEXT_LEN) return false;

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> future = pool.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                if (deleteCount > 0 && !typeDelete(deleteCount)) return false;
                return typeText(text);
            }
        });
        try {
            Boolean ok = future.get(TOTAL_TIMEOUT_WRITE, TimeUnit.MILLISECONDS);
            return ok != null && ok;
        } catch (Throwable t) {
            Log.w(TAG, "input 直写超时", t);
            future.cancel(true);
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean typeDelete(int count) {
        boolean first = true;
        while (count > 0) {
            int batch = Math.min(count, BATCH_KEYS);
            List<String> args = new ArrayList<>(batch + 3);
            args.add("input");
            args.add("keyevent");
            if (first) {
                args.add(String.valueOf(KEYCODE_MOVE_END));
                first = false;
            }
            for (int i = 0; i < batch; i++) args.add(String.valueOf(KEYCODE_DEL));
            if (!exec(args, CMD_TIMEOUT_DEFAULT)) return false;
            count -= batch;
        }
        return true;
    }

    private static boolean typeText(String text) {
        if (text == null || text.isEmpty()) return true;
        int off = 0;
        while (off < text.length()) {
            String part = text.substring(off, Math.min(off + TEXT_CHUNK, text.length()));
            if (!exec(Arrays.asList("input", "text", part), CMD_TIMEOUT_DEFAULT)) return false;
            off += part.length();
        }
        return true;
    }

    // ============== 底层命令执行 ==============

    private static void ensureMethod() throws Exception {
        if (newProcessMethod == null) {
            newProcessMethod = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
        }
    }

    private static boolean exec(List<String> args) {
        return exec(args, CMD_TIMEOUT_DEFAULT);
    }

    private static boolean exec(List<String> args, long timeoutMs) {
        return execResult(args, timeoutMs).ok;
    }

    /** 兼容旧调用：成功返回 stdout，失败返回 null（失败详情已由调用方写日志） */
    private static String execForOutput(List<String> args, long timeoutMs) {
        ExecResult r = execResult(args, timeoutMs);
        if (r.ok) {
            return r.out;
        }
        Log.w(TAG, "命令失败: " + args + " | " + r.describe());
        return null;
    }

    /**
     * 执行命令，把 stdout / stderr / 退出码 / 耗时全都带回来。
     *
     * 【为什么重写】
     *   旧实现有两个致命毛病：
     *     1. 只读 stdout，完全不读 stderr。而 `ime` 这类命令失败时，
     *        原因全写在 stderr 里——于是我们只能看到「失败了」，看不到「为什么」。
     *     2. 退出码非 0 时直接 return null，把已经读到的输出一起扔掉。
     *        等于亲手毁掉唯一能看出原因的证据。
     *   现在不管成功失败，输出全带回来，交给上层记日志。
     *
     * 【注意】
     *   不能用 Process.waitFor(long, TimeUnit)——那是 API 26 才有的，
     *   本项目 minSdk 24，在 Android 7.x 上会直接 NoSuchMethodError 崩掉。
     *   所以这里用 exitValue() 轮询 + 循环次数封顶。
     */
    private static ExecResult execResult(List<String> args, long timeoutMs) {
        Process p = null;
        long t0 = SystemClock.uptimeMillis();
        try {
            ensureMethod();
            p = (Process) newProcessMethod.invoke(null, args.toArray(new String[0]), null, null);

            final Process fp = p;
            final StringBuilder outSb = new StringBuilder();
            final StringBuilder errSb = new StringBuilder();

            // 必须两个流同时读：只读 stdout 而 stderr 堆满管道缓冲区的话，进程会卡死
            Thread tOut = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(fp.getInputStream(), outSb);
                }
            }, "shz-stdout");
            Thread tErr = new Thread(new Runnable() {
                @Override
                public void run() {
                    copy(fp.getErrorStream(), errSb);
                }
            }, "shz-stderr");
            tOut.setDaemon(true);
            tErr.setDaemon(true);
            tOut.start();
            tErr.start();

            // 轮询等待进程结束（兼容 API 24，不能用 waitFor(long, TimeUnit)）
            long start = SystemClock.uptimeMillis();
            long deadline = start + timeoutMs;
            int maxLoops = 2000;                       // 硬保险，绝不无限循环
            int sleepMs = Math.max(10, (int) Math.min(50, timeoutMs / 40));
            int code = -1;
            boolean finished = false;
            for (int i = 0; i < maxLoops; i++) {
                try {
                    code = p.exitValue();
                    finished = true;
                    break;
                } catch (IllegalArgumentException ignored) {
                    // 【这里踩过一个坑，务必看清楚】
                    // 标准约定：Process.exitValue() 在进程未退出时抛
                    //   IllegalThreadStateException。
                    // 但 Shizuku 的 Process 实现抛的是它的父类
                    //   IllegalArgumentException("process hasn't exited")。
                    // 而 IllegalThreadStateException 继承自 IllegalArgumentException，
                    // 所以旧代码只 catch 子类，第一次轮询（命令刚启动 4 毫秒）就被
                    // 当成致命异常直接判失败——所有 Shizuku 命令都「秒失败」。
                    // 这里 catch 父类，两种实现都覆盖。
                }
                long now = SystemClock.uptimeMillis();
                if (now < start || now >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!finished) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
            // 给读线程一点时间把剩下的内容收干净
            joinQuietly(tOut, 500);
            joinQuietly(tErr, 500);

            return new ExecResult(finished && code == 0, code,
                    outSb.toString().trim(), errSb.toString().trim(),
                    SystemClock.uptimeMillis() - t0, !finished);
        } catch (Throwable t) {
            return new ExecResult(false, -2, "",
                    "执行异常 " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    SystemClock.uptimeMillis() - t0, false);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
    }

    /** 把多行/超长输出压成一行摘要，避免诊断日志被一段崩溃堆栈刷爆 */
    private static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "...(截断)";
    }

    private static void copy(InputStream in, StringBuilder sb) {
        if (in == null) {
            return;
        }
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
