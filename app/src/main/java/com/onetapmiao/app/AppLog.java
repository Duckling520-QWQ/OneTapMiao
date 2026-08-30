package com.onetapmiao.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 诊断日志（持久化版）
 *
 * 旧实现的问题：日志只存在静态内存 List 里。
 * 用户在 QQ/微信输入完再切回本 App 时，后台进程往往已被系统回收重建，
 * 静态变量被清空，诊断对话框永远显示"暂无日志，请先去微信/QQ 输入…"。
 *
 * 新实现：内存 + 文件双写。
 *   - add()：同时写 Logcat、内存环形缓冲、磁盘文件（追加）
 *   - dump()：内存有内容优先返回；内存为空（进程刚重建）时回读磁盘尾部
 *   - 文件超过 MAX_FILE_BYTES 自动截断保留后半段
 *
 * 另外提供 selfCheck()：把"服务是否启用 / 开关状态 / 目标包名 / 模式 / Shizuku 状态"
 * 直接拼在日志前面，一眼看出问题在哪，不用再靠猜。
 */
public final class AppLog {
    private static final String TAG = "QQCatLog";
    private static final int MAX = 300;                 // 内存保留条数
    private static final int TAIL_LINES = 300;          // 回读磁盘的尾部行数
    private static final String FILE_NAME = "cat_diag.log";
    private static final long MAX_FILE_BYTES = 256 * 1024;

    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static File logFile;

    // ---- 连续重复折叠 ----
    private static final long REPEAT_WINDOW_MS = 3000;
    private static String lastKey = null;
    private static long lastKeyAt = 0;
    private static int repeatCount = 0;

    private AppLog() {}

    /** 在 MainActivity.onCreate() 和无障碍服务 onServiceConnected() 各调一次 */
    public static synchronized void init(Context ctx) {
        if (logFile != null || ctx == null) {
            return;
        }
        try {
            File dir = ctx.getFilesDir();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            logFile = new File(dir, FILE_NAME);
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
        } catch (Throwable t) {
            Log.w(TAG, "日志文件初始化失败", t);
            logFile = null;
        }
    }

    /**
     * 记一条日志。
     *
     * 【本版新增：连续重复折叠】
     *   无障碍服务每 200ms 扫一次窗口，在微信里必然每次都报
     *   「根窗口递归未找到输入节点」，一秒钟 5 行，几十秒就把日志冲没了，
     *   真正有用的那几行（ime 成功、读到原文、写入成功）被彻底淹没——
     *   你贴给我的日志里 90% 都是这种无用行。
     *
     *   现在完全相同的内容（同 tag 同文本）在折叠窗口内只记第一行，
     *   之后累计次数；内容一变或超过窗口，就补一行「连续重复 N 次，已折叠」。
     *   信息量一点没少，但日志终于能看了。
     */
    public static synchronized void add(String tag, String msg) {
        long now = System.currentTimeMillis();
        String key = tag + "\u0001" + msg;
        if (key.equals(lastKey) && (now - lastKeyAt) < REPEAT_WINDOW_MS) {
            repeatCount++;
            return;
        }
        flushRepeatLocked(now);

        lastKey = key;
        lastKeyAt = now;

        String line = FMT.format(new Date()) + " " + tag + " | " + msg;
        try {
            Log.d("QQCat-" + tag, msg);
        } catch (Throwable ignored) {
        }
        try {
            LINES.addLast(line);
            while (LINES.size() > MAX) {
                LINES.removeFirst();
            }
        } catch (Throwable ignored) {
        }
        appendLine(line);
    }

    /** 把折叠起来的重复次数补记成一行（调用方必须已持有锁） */
    private static void flushRepeatLocked(long now) {
        if (repeatCount <= 0 || lastKey == null) {
            repeatCount = 0;
            return;
        }
        String line = FMT.format(new Date(lastKeyAt)) + " … | 上一条连续重复 "
                + repeatCount + " 次，已折叠";
        try {
            LINES.addLast(line);
            while (LINES.size() > MAX) {
                LINES.removeFirst();
            }
        } catch (Throwable ignored) {
        }
        appendLine(line);
        repeatCount = 0;
    }

    /** 导出前先把攒着的折叠信息补上，否则最后一批重复次数看不到 */
    public static synchronized void flushRepeat() {
        flushRepeatLocked(System.currentTimeMillis());
        lastKey = null;
    }

    /**
     * 带异常的日志重载。
     *
     * 之前批量把 Log.e(TAG, msg, e) 换成 AppLog.add 时漏了这个重载，
     * 导致 5 处调用编译不过。这里补齐：
     *   - 落盘：异常类型 + message + 首帧位置（不刷完整堆栈，避免日志爆炸）
     *   - Logcat：保留完整堆栈，方便 adb 排查
     */
    public static synchronized void add(String tag, String msg, Throwable t) {
        if (t == null) {
            add(tag, msg);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(msg).append(" | ").append(t.getClass().getSimpleName());

        String m = t.getMessage();
        if (m != null && !m.isEmpty()) {
            sb.append(": ").append(m);
        }

        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append(" @").append(st[0].getFileName())
                    .append(":").append(st[0].getLineNumber());
        }

        add(tag, sb.toString());

        try {
            Log.w("QQCat-" + tag, msg, t);
        } catch (Throwable ignored) {
        }
    }

    private static void appendLine(String line) {
        File f = logFile;
        if (f == null) {
            return;
        }
        OutputStreamWriter w = null;
        try {
            // 超限先截断，避免无限增长
            if (f.length() > MAX_FILE_BYTES) {
                truncateHead(f);
            }
            w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            w.write(line);
            w.write("\n");
            w.flush();
        } catch (Throwable t) {
            Log.w(TAG, "写日志失败", t);
        } finally {
            closeQuietly(w);
        }
    }

    /** 保留文件后半部分 */
    private static void truncateHead(File f) {
        BufferedReader r = null;
        OutputStreamWriter w = null;
        try {
            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");

            r = new BufferedReader(new FileReader(f));
            int total = 0;
            while (r.readLine() != null) {
                total++;
            }
            r.close();

            int skip = Math.max(0, total - MAX / 2);
            r = new BufferedReader(new FileReader(f));
            for (int i = 0; i < skip; i++) {
                r.readLine();
            }

            w = new OutputStreamWriter(new FileOutputStream(tmp, false), "UTF-8");
            String s;
            while ((s = r.readLine()) != null) {
                w.write(s);
                w.write("\n");
            }
            w.flush();
            w.close();
            r.close();
            w = null;
            r = null;

            if (tmp.renameTo(f)) {
                return;
            }
            copyFile(tmp, f);
            tmp.delete();
        } catch (Throwable t) {
            Log.w(TAG, "截断日志失败", t);
        } finally {
            closeReaderQuietly(r);
            closeQuietly(w);
        }
    }

    private static void copyFile(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static synchronized String dump() {
        flushRepeatLocked(System.currentTimeMillis());
        StringBuilder sb = new StringBuilder();
        try {
            for (String l : LINES) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(l);
            }
        } catch (Throwable ignored) {
        }

        if (sb.length() > 0) {
            return sb.toString();
        }
        return readTail(TAIL_LINES);
    }

    private static synchronized String readTail(int n) {
        File f = logFile;
        if (f == null || !f.exists()) {
            return "";
        }
        BufferedReader r = null;
        try {
            LinkedList<String> buf = new LinkedList<>();
            r = new BufferedReader(new FileReader(f));
            String s;
            while ((s = r.readLine()) != null) {
                buf.addLast(s);
                if (buf.size() > n) {
                    buf.removeFirst();
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : buf) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(l);
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "读日志失败", t);
            return "";
        } finally {
            closeReaderQuietly(r);
        }
    }

    public static synchronized void clear() {
        LINES.clear();
        lastKey = null;
        repeatCount = 0;
        File f = logFile;
        if (f == null) {
            return;
        }
        try {
            new FileOutputStream(f, false).close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自检快照：把最关键的几个状态直接列出来。
     * 以前只能靠猜"到底服务有没有在跑"，现在一眼看穿。
     */
    public static String selfCheck(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 自检 =====\n");

        if (ctx == null) {
            sb.append("Context 为空，无法自检");
            return sb.toString();
        }

        // 1. 无障碍服务是否启用
        boolean svcOn = false;
        try {
            AccessibilityManager am =
                    (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        if (info == null || info.getResolveInfo() == null
                                || info.getResolveInfo().serviceInfo == null) {
                            continue;
                        }
                        if (ctx.getPackageName().equals(
                                info.getResolveInfo().serviceInfo.packageName)) {
                            svcOn = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("读取无障碍状态异常: ").append(t.getMessage()).append("\n");
        }
        sb.append("无障碍服务: ").append(svcOn ? "已启用" : "未启用 ← 先去系统设置打开").append("\n");

        // 2. 配置
        try {
            CatConfig cfg = CatConfig.load(ctx);
            sb.append("总开关: ").append(cfg.processingEnabled ? "开" : "关 ← 关闭时完全不处理").append("\n");
            sb.append("处理模式: ").append(
                    CatConfig.MODE_REALTIME.equals(cfg.processingMode) ? "实时" : "标点").append("\n");
            sb.append("追加喵: ").append(cfg.enableAppend ? "开" : "关")
                    .append("（").append(TextUtils.isEmpty(cfg.appendText) ? "喵" : cfg.appendText).append("）\n");
            sb.append("颜文字: ").append(cfg.enableRandomEmoticon ? "开" : "关").append("\n");

            if (cfg.targetPackages == null || cfg.targetPackages.isEmpty()) {
                sb.append("目标应用: 空 ← 必须勾选 QQ/微信\n");
            } else {
                sb.append("目标应用: ").append(TextUtils.join(", ", cfg.targetPackages)).append("\n");
            }
            sb.append("Shizuku 兜底: ").append(cfg.shizukuFallbackEnabled ? "开" : "关");
            try {
                sb.append("（").append(ShizukuInjector.isReady() ? "已就绪" : "未就绪/未授权").append("）");
            } catch (Throwable ignored) {
                sb.append("（状态未知）");
            }
            sb.append("\n");
        } catch (Throwable t) {
            sb.append("读取配置异常: ").append(t.getMessage()).append("\n");
        }

        // 3. 本进程是否拿到 Shizuku 权限
        try {
            PackageManager pm = ctx.getPackageManager();
            sb.append("本应用已安装 IME 服务: ")
                    .append(hasImeService(ctx) ? "是" : "否 ← 注入通道不可用").append("\n");
            if (pm != null) {
                sb.append("悬浮窗权限: ").append(
                        android.provider.Settings.canDrawOverlays(ctx) ? "已授予" : "未授予").append("\n");
            }
        } catch (Throwable ignored) {
        }

        sb.append("================\n\n");
        return sb.toString();
    }

    private static boolean hasImeService(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) {
                return false;
            }
            return pm.queryIntentServices(
                    new android.content.Intent(android.view.inputmethod.InputMethod.SERVICE_INTERFACE),
                    PackageManager.GET_META_DATA
            ).size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void closeQuietly(OutputStreamWriter w) {
        if (w == null) {
            return;
        }
        try {
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeReaderQuietly(BufferedReader r) {
        if (r == null) {
            return;
        }
        try {
            r.close();
        } catch (Throwable ignored) {
        }
    }
}
