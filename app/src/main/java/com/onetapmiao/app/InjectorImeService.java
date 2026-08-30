package com.onetapmiao.app;

import com.onetapmiao.app.R;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 隐形注入输入法：不显示任何键盘界面，只在被 Shizuku 切换激活的一瞬间，
 * 通过 InputConnection 读写当前聚焦输入框的内容，然后立刻通知外部切回原输入法。
 *
 * 【本版改动（方案：让微信可用）】
 *   1. 新增「读」能力：getExtractedText 读全文，失败则用
 *      getTextBeforeCursor + getTextAfterCursor 拼接兜底。
 *      —— 这是微信能用的关键：无障碍那条路在微信拿到的是一棵被掏空的空树，
 *         但 InputConnection 是宿主 App 主动创建并交给输入法的，封了用户就打不了字。
 *   2. 新增 MODE_APPEND：一次输入法切换内完成「读 → 剥离 → 加喵 → 写回」。
 *      （旧逻辑只写不读，要靠无障碍先把原文算好传进来，微信做不到）
 *   3. 写入改为三级降级并带复核：
 *        ① setSelection(0, len) + commitText   —— 最标准的整段替换
 *        ② performContextMenuAction(selectAll) + commitText —— 不依赖长度
 *        ③ deleteSurroundingText + commitText  —— 最后兜底
 *      每一级写完都用 getExtractedText 复核一遍，不符就换下一级。
 *      比原来固定 deleteSurroundingText(deleteCount) 稳得多——原来那种
 *      一旦外部算错了 deleteCount（微信场景根本算不出来），就会删多或删少。
 *   4. 任务模型从「散落的 static 字段」改成 Job 对象 + CAS 防重入。
 *      onStartInput / onStartInputView 在一次切换里可能被调多次，
 *      旧写法靠把 pendingText 置 null 来防重复，并发下不可靠。
 *
 * 【保持不变】
 *   - 旧的 pendingText / pendingDeleteBefore / doneLatch / lastResult 字段全部保留，
 *     旧调用方（无障碍兜底通道）不用改就能继续编译运行。
 *   - onCreateInputView 仍是 1px 透明视图，避免键盘闪现。
 */
public class InjectorImeService extends InputMethodService {

    // ============ 任务模式 ============

    /** 读 → 剥离 → 加喵 → 写回（一键加喵主路径，微信靠它） */
    public static final int MODE_APPEND = 1;
    /** 只写：把外部给的文本覆盖写入（旧行为，无障碍兜底通道用） */
    public static final int MODE_WRITE = 2;
    /** 只读：把输入框全文取回来，不改动（调试用） */
    public static final int MODE_READ = 3;

    private static final String TAG = "IME";

    private static final int MAX_CHARS = 8000;
    private static final int DELETE_SPAN = 5000;
    private static final long VERIFY_SLEEP = 70L;

    // ============ 新任务模型 ============

    public static volatile Job pendingJob = null;

    /** 防重入：一次切换只消费一个 Job */
    private static final AtomicBoolean JOB_CONSUMED = new AtomicBoolean(false);

    /** 一次「读 → 处理 → 写」的完整任务 */
    public static final class Job {
        public final int mode;
        /** MODE_WRITE 时要写入的文本 */
        public final String text;
        /** MODE_WRITE 时向前删除的字符数（兼容旧逻辑，一般传 0，靠覆写通道解决） */
        public final int deleteBefore;
        /** IME 侧完成后 countDown */
        public final CountDownLatch done = new CountDownLatch(1);

        public volatile String readText = null;
        public volatile String writeText = null;
        public volatile boolean ok = false;
        public volatile String failReason = null;

        public Job(int mode, String text, int deleteBefore) {
            this.mode = mode;
            this.text = text;
            this.deleteBefore = deleteBefore;
        }
    }

    /**
     * 装载任务。由 ShizukuInjector 在切输入法之前调用。
     *
     * @return false 表示上一个任务还没被消费，本次拒绝（防止并发打串）
     */
    public static boolean armJob(Job job) {
        if (job == null) {
            return false;
        }
        if (pendingJob != null) {
            return false;
        }
        JOB_CONSUMED.set(false);
        pendingJob = job;
        return true;
    }

    // ============ 旧字段（保留兼容，不建议新代码使用） ============

    /** @deprecated 改用 {@link Job} */
    public static volatile String pendingText = null;
    /** @deprecated 改用 {@link Job} */
    public static volatile int pendingDeleteBefore = 0;
    /** @deprecated 改用 {@link Job#done} */
    public static volatile CountDownLatch doneLatch = null;
    /** @deprecated 改用 {@link Job#ok} */
    public static volatile boolean lastResult = false;

    // ============ 生命周期 ============

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            AppLog.init(getApplicationContext());
        } catch (Throwable t) {
            // 日志初始化失败不能影响输入法
        }
    }

    @Override
    public View onCreateInputView() {
        // 透明 1px 视图，避免键盘界面闪现
        View v = new View(this);
        v.setMinimumHeight(1);
        v.setAlpha(0f);
        return v;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // 横屏也不要全屏输入界面
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // 真正的读写放在 onStartInputView —— 那里 InputConnection 一定已就绪。
        // 这里什么都不做，避免旧版在 onStartInput 里抢跑拿不到 ic。
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);

        Job job = pendingJob;
        if (job != null) {
            if (JOB_CONSUMED.compareAndSet(false, true)) {
                pendingJob = null;
                runJob(job);
            }
            return;
        }

        // 兼容旧调用方（无障碍兜底通道可能还在用旧字段）
        legacyInject();
    }

    // ============ 核心：读 → 处理 → 写 ============

    private void runJob(Job job) {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                job.failReason = "拿不到 InputConnection（当前没有聚焦的输入框）";
                AppLog.add(TAG, job.failReason);
                return;
            }

            // ---- 读 ----
            String raw = readAll(ic);
            job.readText = raw;
            AppLog.add(TAG, "读到原文: " + MiaoText.abbrev(raw, 60));

            if (job.mode == MODE_READ) {
                job.ok = (raw != null);
                if (!job.ok) {
                    job.failReason = "读取失败";
                }
                return;
            }

            // ---- 处理 ----
            if (job.mode == MODE_APPEND) {
                if (raw == null || raw.trim().isEmpty()) {
                    job.failReason = "输入框是空的";
                    AppLog.add(TAG, job.failReason);
                    return;
                }

                CatConfig cfg = CatConfig.load(getApplicationContext());
                String original = MiaoText.stripAll(raw, cfg);
                if (original.trim().isEmpty()) {
                    // 整段都是追加物（例如连点两次且用户没输入正文），用原文兜底避免清空
                    original = raw.trim();
                }
                String result = TextProcessor.process(original, cfg);
                job.writeText = result;

                AppLog.add(TAG, "处理: 原文 " + raw.length() + " 字 → 剥离 "
                        + original.length() + " 字 → 结果 " + result.length() + " 字");
            } else {
                job.writeText = job.text;
            }

            // ---- 写 ----
            job.ok = overwrite(ic, job.writeText);
            if (!job.ok) {
                job.failReason = "三条写入通道都失败";
                AppLog.add(TAG, job.failReason);
            }
        } catch (Throwable t) {
            job.failReason = "异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
            AppLog.add(TAG, "runJob 异常", t);
        } finally {
            job.done.countDown();
            try {
                requestHideSelf(0);
            } catch (Throwable ignored) {
            }
        }
    }

    // ============ 读 ============

    /**
     * 读输入框全文。
     * 通道 1：getExtractedText（一次拿全文，ADB Keyboard 同款做法）
     * 通道 2：getTextBeforeCursor + getTextAfterCursor 拼接（部分 App 不实现 ExtractedText）
     */
    private String readAll(InputConnection ic) {
        if (ic == null) {
            return null;
        }

        try {
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.hintMaxChars = MAX_CHARS;
            req.hintMaxLines = 1;
            req.flags = 0;
            ExtractedText et = ic.getExtractedText(req, 0);
            if (et != null && et.text != null) {
                return et.text.toString();
            }
            AppLog.add(TAG, "getExtractedText 返回空，改用前后拼接");
        } catch (Throwable t) {
            AppLog.add(TAG, "getExtractedText 异常，改用前后拼接", t);
        }

        try {
            CharSequence before = ic.getTextBeforeCursor(MAX_CHARS, 0);
            CharSequence after = ic.getTextAfterCursor(MAX_CHARS, 0);
            String s = (before == null ? "" : before.toString())
                    + (after == null ? "" : after.toString());
            return s;
        } catch (Throwable t) {
            AppLog.add(TAG, "前后拼接读取失败", t);
            return null;
        }
    }

    // ============ 写 ============

    /**
     * 整段覆写输入框内容，三级降级 + 每级复核。
     */
    private boolean overwrite(InputConnection ic, String text) {
        if (ic == null || text == null) {
            return false;
        }

        // 通道 ①：setSelection 全选 → commitText 替换选区（最标准）
        try {
            String cur = readAll(ic);
            int len = (cur == null) ? 0 : cur.length();
            ic.beginBatchEdit();
            boolean selOk = (len <= 0) || ic.setSelection(0, len);
            boolean commitOk = ic.commitText(text, 1);
            ic.endBatchEdit();
            if (selOk && commitOk && verify(ic, text)) {
                AppLog.add(TAG, "写入成功（通道① setSelection 覆写）");
                return true;
            }
            AppLog.add(TAG, "通道① 未通过校验，换通道②");
        } catch (Throwable t) {
            AppLog.add(TAG, "通道① 异常，换通道②", t);
        }

        // 通道 ②：selectAll → commitText（不依赖长度，微信/QQ 都吃这套）
        try {
            ic.beginBatchEdit();
            ic.performContextMenuAction(android.R.id.selectAll);
            boolean commitOk = ic.commitText(text, 1);
            ic.endBatchEdit();
            if (commitOk && verify(ic, text)) {
                AppLog.add(TAG, "写入成功（通道② selectAll 覆写）");
                return true;
            }
            AppLog.add(TAG, "通道② 未通过校验，换通道③");
        } catch (Throwable t) {
            AppLog.add(TAG, "通道② 异常，换通道③", t);
        }

        // 通道 ③：前后大范围删除 → commitText（最后兜底）
        try {
            ic.beginBatchEdit();
            ic.deleteSurroundingText(DELETE_SPAN, DELETE_SPAN);
            boolean commitOk = ic.commitText(text, 1);
            ic.endBatchEdit();
            if (commitOk && verify(ic, text)) {
                AppLog.add(TAG, "写入成功（通道③ delete 覆写）");
                return true;
            }
            AppLog.add(TAG, "通道③ 未通过校验");
        } catch (Throwable t) {
            AppLog.add(TAG, "通道③ 异常", t);
        }

        return false;
    }

    /**
     * 复核写入结果。
     * 注意：如果 App 读不回内容（不支持 ExtractedText），按乐观通过处理——
     * 否则所有不支持的 App 都会被误判为失败。
     */
    private boolean verify(InputConnection ic, String expect) {
        sleepQuiet(VERIFY_SLEEP);
        String now = readAll(ic);
        if (now == null) {
            AppLog.add(TAG, "校验跳过：读不回内容，按成功处理");
            return true;
        }
        boolean eq = now.trim().equals(expect.trim());
        if (!eq) {
            AppLog.add(TAG, "校验不符｜期望=" + MiaoText.abbrev(expect, 40)
                    + "｜实际=" + MiaoText.abbrev(now, 40));
        }
        return eq;
    }

    // ============ 旧接口兼容 ============

    private void legacyInject() {
        final String text = pendingText;
        final int del = pendingDeleteBefore;
        final CountDownLatch latch = doneLatch;
        if (text == null || latch == null) {
            return;
        }

        pendingText = null;
        pendingDeleteBefore = 0;

        boolean ok = false;
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.beginBatchEdit();
                boolean delOk = del <= 0 || ic.deleteSurroundingText(del, 0);
                boolean commitOk = ic.commitText(text, 1);
                ic.endBatchEdit();
                ok = delOk && commitOk;
            }
        } catch (Throwable t) {
            AppLog.add(TAG, "旧接口注入异常", t);
        } finally {
            lastResult = ok;
            latch.countDown();
            try {
                requestHideSelf(0);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
