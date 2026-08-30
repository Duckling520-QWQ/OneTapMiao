package com.onetapmiao.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * QQ / 微信 自动追加「喵」与颜文字 —— 无障碍服务（完整四通道版，优化）
 *
 * 写入通道（按序尝试，任一成功即停）：
 *   ① 无障碍 ACTION_SET_TEXT（带复核，识破 QQ 的"假成功"）
 *   ② Shizuku + IME 桥接注入（主力，QQ/微信无法拒绝）
 *   ③ Shizuku + input text 直写（备用，中文场景基本失败）
 *   ④ 剪贴板粘贴（最后兜底）
 *
 * ②③④ 均在后台线程执行：IME 切换耗时较长，不能阻塞无障碍主线程。
 *
 * 本次优化针对两类问题：
 *
 * 占位符 / 提示文本误杀
 *   - 旧代码 `if (!inp.isFocused()) return true;`：事件回调期间 isFocused() 常短暂为 false，
 *     导致几乎每一句正常输入都被判成占位符跳过。已删除。
 *   - 旧代码 `clean.contains("…")`：真实聊天里的省略号极常见（"好吧…"），已删除。
 *   - 黑名单由 contains 改为 equals，且仅在短文本（<=12 字）时生效，长句永不判为占位符。
 *
 * 找不到输入节点
 *   - 旧代码 scheduleProcess() 不保存节点，200ms 后 doProcess() 只能重新查找，
 *     "入口找到了、出口找不到"。改为缓存事件源节点副本，优先复用。
 *   - 查找顺序扩展：事件源自身 → 事件源父链 → 事件源子树 → 根窗口 FOCUS_INPUT → 根窗口递归。
 *   - 节点判定放宽：isEditable / 类名 / 光标位置 / ACTION_SET_TEXT 任一命中即算输入框。
 *   - 找不到节点时按 120ms 重试 3 次，不再一次失败就放弃。
 *
 * 【其他时序加固】
 *   - 去抖加上限：连续打字时处理任务不再被无限推迟（旧代码每次 removeCallbacks 重排）。
 *   - 窗口状态变化不再无条件 resetState：输入框还有内容时改为"立即补处理"，
 *     根治"打完整句直接点发送/切面板 → 这一句永远不被处理"。
 *   - 光标检查：无法获取光标（返回 -1 或 0-0）时放行，只有"明确在中间"才跳过。
 *   - SET_TEXT 复核：解决 QQ 返回 true 但实际未生效的"假成功"，失败自动转剪贴板兜底。
 *   - processing 看门狗、删除标记 TTL、输入框身份变更自动重置。
 */
public class QQAccessibilityService extends AccessibilityService {
    private static final String TAG = "QQCatSvc";

    // ============ 时序参数 ============
    /*
     * [关键] 去抖延迟必须小于节流间隔，否则任务会陷入"排了又被取消"的死循环：
     * 旧值 去抖200 / 节流300 → 每 300ms 的新事件都会 removeCallbacks 掉上一轮
     * 尚未执行的任务再重排，实际只能靠 MAX_PENDING_WAIT 兜底，延迟被顶到 700ms。
     *
     * 现改为 实时去抖80 / 实时节流200，任务都能在下次节流到来前执行完，
     * 实时模式感知延迟从 ~700ms 降到 ~150ms。
     */
    private static final long MIN_PROCESS_INTERVAL = 200;  // 实时模式节流（必须 > REALTIME_DELAY）
    private static final long REALTIME_DELAY = 80;         // [优化] 实时模式去抖
    private static final long STABLE_DELAY = 200;          // 标点模式去抖
    private static final long MAX_PENDING_WAIT = 700;      // 标点模式去抖总预算，超出立即执行
    private static final long PROCESS_TIMEOUT = 5000;      // [优化] processing 看门狗
    private static final long PROCESS_TIMEOUT_INJECT = 15000; // 走 Shizuku 注入时放宽看门狗
    private static final long DELETE_FLAG_TTL = 2000;      // [优化] 删除标记自动过期
    private static final int MAX_NODE_RETRY = 3;           // [优化] 找不到节点的重试次数
    private static final long NODE_RETRY_DELAY = 120;      // [优化] 重试间隔
    private static final int PLACEHOLDER_MAX_LEN = 12;     // [优化] 超过此长度绝不判为占位符
    private static final int DELETE_BURST_MAX = 8;         // 单次删除字符数上限，超出视为整句替换
    private static final boolean TRIGGER_ON_COMMA = true;  // [优化] 逗号/顿号/分号也触发处理
    private static final int VERIFY_SLEEP = 120;           // 写入后复核等待（标点模式）
    private static final int VERIFY_SLEEP_REALTIME = 60;   // [优化] 实时模式复核等待要短，否则输入发涩

    // ============ 写入熔断（防止改写风暴） ============
    private static final long REWRITE_WINDOW = 1500;       // 统计窗口
    private static final int MAX_REWRITE = 4;              // 窗口内最多改写次数
    private static final long COOLDOWN_MS = 3000;          // 超限后的冷静期
    private static final long TREE_DUMP_INTERVAL = 3000;   // 节点树快照的最小间隔

    // ============ 运行时状态 ============
    private CatConfig cachedConfig;
    private String userOriginal = "";
    private String lastSet = "";
    private boolean processing = false;
    private long processingSince = 0L;      // [优化] 看门狗计时
    private long lastWriteTime = 0;
    private long lastProcessTime = 0;
    private long pendingScheduleTime = 0L;  // [优化] 首次排程时间，用于去抖上限
    private long lastDeleteTime = 0L;       // [优化] 删除标记时间戳
    private long processingTimeout = PROCESS_TIMEOUT; // 当前轮次的看门狗预算

    // ---- [修复] 改写熔断 ----
    private int rewriteCount = 0;           // 当前窗口内已改写次数
    private long rewriteWindowStart = 0L;   // 当前统计窗口起点
    private long cooldownUntil = 0L;        // 冷静期截止时刻
    private boolean forceProcessOnce = false; // 点发送时强制处理一次（忽略保守策略）
    private long lastTreeDumpTime = 0L;     // 节点树快照限频
    private int nodeFailStreak = 0;         // [修复] 连续找不到节点的次数，用于退避
    private long nextNodeSearchAt = 0L;     // [修复] 退避：下次允许全树查找的时刻
    private int nodeRetryCount = 0;         // [优化] 节点查找重试计数

    private final List<String> lineEmoticonCache = new ArrayList<>();
    private final List<Boolean> linePunctuationState = new ArrayList<>();
    private boolean isDeleting = false;

    /**
     * [优化-问题6] 缓存事件源输入节点副本。
     * 旧代码 scheduleProcess() 不保存节点，200ms 后只能重新查找，
     * 而这期间输入框节点可能已重建/失焦，导致"入口找到、出口找不到"。
     */
    private AccessibilityNodeInfo pendingInputNode;
    private String pendingInputKey = "";    // [优化] 输入框身份，用于跨窗口自动重置

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable processRunnable = new Runnable() {
        @Override
        public void run() {
            doProcess();
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) {
            return;
        }

        String pkg = e.getPackageName() != null ? e.getPackageName().toString() : "";
        int type = e.getEventType();

        AppLog.init(this);

        CatConfig cfg = CatConfig.load(this);
        if (cfg == null || cfg.targetPackages == null
                || !cfg.targetPackages.contains(pkg) || !cfg.processingEnabled) {
            return;
        }
        this.cachedConfig = cfg;

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged();
            return;
        }

        // [优化] 点发送按钮：立刻补一次处理，根治"打完整句直接发送来不及处理"
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && isSendButton(e)) {
            AppLog.add("Svc", "检测到发送按钮，强制补处理一次");
            // 强制模式：忽略中间编辑保守策略、忽略冷静期，这是最后一次补救机会
            this.forceProcessOnce = true;
            mainHandler.removeCallbacks(processRunnable);
            mainHandler.post(processRunnable);
            return;
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            String mode = cfg.processingMode != null
                    ? cfg.processingMode
                    : CatConfig.MODE_PUNCTUATION;

            // [修复] 持续找不到节点时退避，避免每秒对整棵大树递归好几次
            long now0 = System.currentTimeMillis();
            if (this.nodeFailStreak >= 3 && now0 < this.nextNodeSearchAt) {
                return;
            }

            String eventText = extractEventText(e);

            if (CatConfig.MODE_REALTIME.equals(mode)) {
                long now = System.currentTimeMillis();
                if (now - lastProcessTime >= MIN_PROCESS_INTERVAL) {
                    lastProcessTime = now;
                    AccessibilityNodeInfo node = findFocusedEditableNode(e, eventText);
                    if (node != null) {
                        scheduleProcess(node, true);
                        recycleNode(node);
                    } else {
                        AppLog.add("Svc", "实时模式：未找到输入节点");
                        scheduleBackoff();
                    }
                }
            } else {
                AccessibilityNodeInfo inp = findFocusedEditableNode(e, eventText);
                if (inp != null) {
                    CharSequence cs = inp.getText();
                    if (cs != null) {
                        String raw = cs.toString();
                        if (!raw.trim().isEmpty() && isPunctuationEnding(raw)) {
                            AppLog.add("Svc", "标点/换行触发: " + abbrev(raw));
                            scheduleProcess(inp, false);
                        }
                    }
                    recycleNode(inp);
                } else {
                    AppLog.add("Svc", "标点模式：未找到输入节点（事件源失效）");
                    scheduleBackoff();
                }
            }
        }
    }

    /**
     * [优化] 窗口状态变化处理。
     *
     * 旧代码无条件 removeCallbacks + resetState，问题很大：
     * QQ 切表情面板、候选栏、语音面板、乃至点发送都会发 WINDOW_STATE_CHANGED，
     * 于是"打完整句 → 点发送"时，待处理任务被直接取消，这一句永远不会追加。
     *
     * 新策略：只有当输入框真的空了（消息已发出 / 离开聊天页）才重置；
     * 否则立刻补处理一次。
     */
    private void handleWindowStateChanged() {
        AccessibilityNodeInfo inp = findFocusedEditableNode(null);

        if (inp == null) {
            /*
             * [修复] 不再无条件 resetState。
             * 微信环境下输入框经常"暂时找不到"（节点被混淆 / 面板弹出 / 键盘收起），
             * 每次都重置会把 lastSet、颜文字缓存全部清掉，
             * 导致后续处理丢失上下文、反复重算（日志里这行高频出现就是证据）。
             * 这里只取消待处理任务并释放缓存节点，保留业务状态。
             */
            AppLog.add("Svc", "窗口状态变化：暂时找不到输入框，仅清理待处理任务");
            mainHandler.removeCallbacks(processRunnable);
            recycleNode(pendingInputNode);
            pendingInputNode = null;
            return;
        }

        CharSequence cs = inp.getText();
        String raw = cs == null ? "" : cs.toString();
        recycleNode(inp);

        if (raw.trim().isEmpty()) {
            AppLog.add("Svc", "窗口状态变化：输入框已清空，重置状态");
            mainHandler.removeCallbacks(processRunnable);
            resetState();
        } else {
            AppLog.add("Svc", "窗口状态变化但输入框仍有内容，立即补处理");
            mainHandler.removeCallbacks(processRunnable);
            mainHandler.post(processRunnable);
        }
    }

    /**
     * [优化-问题6] 排程时缓存节点副本。
     *
     * @param node     事件源输入节点（可为 null）
     * @param realtime 是否实时模式。两种模式的去抖策略完全不同：
     *                 - 实时：固定短去抖（80ms）。节流已在事件入口保证最小间隔，
     *                   这里若再用"总预算兜底"反而会把延迟顶到 700ms。
     *                 - 标点：输入过程中会不断重排，需要 MAX_PENDING_WAIT 兜底，
     *                   防止手速快时永远轮不到执行。
     */
    private void scheduleProcess(AccessibilityNodeInfo node, boolean realtime) {
        nodeRetryCount = 0;

        if (node != null) {
            recycleNode(pendingInputNode);
            pendingInputNode = AccessibilityNodeInfo.obtain(node);
            pendingInputKey = buildInputKey(node);
        }

        mainHandler.removeCallbacks(processRunnable);

        if (realtime) {
            mainHandler.postDelayed(processRunnable, REALTIME_DELAY);
            return;
        }

        long now = System.currentTimeMillis();

        if (pendingScheduleTime == 0L) {
            pendingScheduleTime = now;
        }

        // 连续打字时任务会被不断重排，这里用总预算兜底：
        // 距首次排程超过 MAX_PENDING_WAIT 就必须执行，否则手速快时永远轮不到。
        long remained = MAX_PENDING_WAIT - (now - pendingScheduleTime);
        long delay = Math.max(0L, Math.min(STABLE_DELAY, remained));

        mainHandler.postDelayed(processRunnable, delay);
    }

    private void doProcess() {
        long now = System.currentTimeMillis();

        if (this.processing) {
            // [优化] 看门狗：异常情况下处理标记未复位会导致服务永久失效。
            // 走 Shizuku 注入时用更宽松的预算（IME 切换本身可能耗时数秒）。
            if (now - processingSince < processingTimeout) {
                return;
            }
            AppLog.add("Warn", "processing 已持锁 " + (now - processingSince) + "ms，强制解锁");
            this.processing = false;
        }

        // [修复] 冷静期：短时间内改写过于频繁时，暂停处理，避免改写风暴
        if (!this.forceProcessOnce && now < this.cooldownUntil) {
            AppLog.add("Svc", "冷静期中，跳过（剩余 "
                    + (this.cooldownUntil - now) + "ms）");
            this.processing = false;
            return;
        }

        this.processing = true;
        this.processingSince = now;
        this.pendingScheduleTime = 0L;

        boolean force = this.forceProcessOnce;
        this.forceProcessOnce = false;

        AccessibilityNodeInfo inp = resolveInputNode();

        if (inp == null) {
            // [优化-问题6] 找不到节点不再直接放弃，重试若干次
            if (nodeRetryCount < MAX_NODE_RETRY) {
                nodeRetryCount++;
                this.processing = false;
                AppLog.add("Svc", "未找到输入节点，第 " + nodeRetryCount + " 次重试");
                mainHandler.postDelayed(processRunnable, NODE_RETRY_DELAY);
            } else {
                AppLog.add("Svc", "未找到输入节点，已重试 " + MAX_NODE_RETRY + " 次，放弃本轮");
                this.processing = false;
            }
            return;
        }

        nodeRetryCount = 0;

        try {
            processNode(inp, force);
        } catch (Throwable t) {
            AppLog.add("Err", "处理输入节点异常", t);
            this.processing = false;
            recycleNode(inp);
        }
    }

    /**
     * [优化-问题6] 优先复用缓存的事件源节点，只有失效时才回落到根窗口查找。
     */
    private AccessibilityNodeInfo resolveInputNode() {
        AccessibilityNodeInfo cached = pendingInputNode;
        pendingInputNode = null;

        if (isUsableInputNode(cached)) {
            AppLog.add("Svc", "复用缓存的输入节点");
            return cached;
        }
        recycleNode(cached);

        AccessibilityNodeInfo found = findFocusedEditableNode(null);
        if (found != null) {
            AppLog.add("Svc", "回落到根窗口查找，命中输入节点");
        }
        return found;
    }

    private void processNode(AccessibilityNodeInfo inp, boolean force) {
        // 强制模式下忽略中间编辑的保守策略（点发送时的最后一次补救）
        if (force) {
            this.forceProcessOnce = true;
        }
        CharSequence cs = inp.getText();

        if (cs == null || cs.length() == 0) {
            recycleNode(inp);
            finish(false, "");
            resetState();
            return;
        }

        String raw = cs.toString();

        if (raw.trim().isEmpty()) {
            recycleNode(inp);
            finish(false, "");
            resetState();
            return;
        }

        // [优化] 输入框身份变化（切聊天窗口 / 换会话）→ 状态全部作废，避免串扰
        String key = buildInputKey(inp);
        if (!key.isEmpty() && !key.equals(pendingInputKey)) {
            AppLog.add("Svc", "输入框身份变化，重置状态: " + key);
            pendingInputKey = key;
            resetStateKeepFlag();
        }

        // 回显跳过：文本等于上次处理结果
        if (raw.equals(this.lastSet)) {
            AppLog.add("Svc", "文本与上次处理结果相同，跳过");
            recycleNode(inp);
            finish(false, "");
            return;
        }

        // [优化-问题5] 占位符判定
        if (isHintOrPlaceholder(inp, raw)) {
            AppLog.add("Svc", "判定为占位符，跳过: " + abbrev(raw));
            recycleNode(inp);
            finish(false, "");
            return;
        }

        CatConfig cfg = this.cachedConfig;
        if (cfg == null) {
            cfg = CatConfig.load(this);
            this.cachedConfig = cfg;
        }

        if (this.lastSet.isEmpty()) {
            this.lineEmoticonCache.clear();
            this.linePunctuationState.clear();
            this.userOriginal = "";
            this.isDeleting = false;
        }

        // ---- 删除检测 ----
        if (!this.lastSet.isEmpty()
                && this.lastSet.startsWith(raw)
                && raw.length() < this.lastSet.length()) {

            int delta = this.lastSet.length() - raw.length();

            if (delta > DELETE_BURST_MAX) {
                // [优化] 一次删掉一大截，通常是输入法整句替换/重选，按全新输入处理
                AppLog.add("Svc", "一次性删除 " + delta + " 字符，视为全新输入");
                resetStateKeepFlag();
            } else {
                AppLog.add("Svc", "检测到删除操作，跳过本轮");
                this.lastSet = raw;
                this.userOriginal = stripAll(raw, cfg);
                this.isDeleting = true;
                this.lastDeleteTime = System.currentTimeMillis();
                recycleNode(inp);
                finish(false, "");
                return;
            }
        }

        // [优化] 删除标记自动过期，避免 isDeleting 长期为真把后续输入全部吃掉
        if (this.isDeleting) {
            if (System.currentTimeMillis() - this.lastDeleteTime > DELETE_FLAG_TTL) {
                AppLog.add("Svc", "删除标记超时，自动解除");
                this.isDeleting = false;
            } else if (raw.length() <= this.lastSet.length()) {
                this.lastSet = raw;
                this.userOriginal = stripAll(raw, cfg);
                recycleNode(inp);
                finish(false, "");
                return;
            } else {
                this.isDeleting = false;
            }
        }

        /*
         * [重要修复] 区分三种输入形态，旧的二分法会酿成"改写风暴"
         *
         * 旧代码只有两种判断：
         *   raw.startsWith(lastSet)  → 末尾追加，正常处理
         *   其他                     → 一律"全新输入"，清空状态重来
         *
         * 问题：在文本中间删除一个字时，raw 既不是 lastSet 的前缀（末尾追加），
         * lastSet 也不是 raw 的前缀（末尾删除），于是被误判成"全新输入"：
         *   1. resetStateKeepFlag() 清空颜文字缓存
         *   2. 重新随机一个颜文字 → target != raw → 触发写入
         *   3. 写入后光标被 setSelection 拉到末尾，用户的中间编辑被打断
         *   4. 用户再删一个字，重复 1~3
         * 结果就是"每删一个字就刷一个新颜文字，光标一直往末尾跳"。
         *
         * 新逻辑：只有"末尾追加"和"末尾删除"才改写，中间编辑一律保守跳过，
         * 只更新基线，等用户回到末尾继续输入时再处理。
         */
        boolean appendAtEnd = !this.lastSet.isEmpty() && raw.startsWith(this.lastSet);
        boolean deleteAtEnd = !this.lastSet.isEmpty()
                && this.lastSet.startsWith(raw)
                && raw.length() < this.lastSet.length();
        boolean midEdit = !this.lastSet.isEmpty() && !appendAtEnd && !deleteAtEnd;

        if (midEdit && !this.forceProcessOnce) {
            AppLog.add("Svc", "中间编辑，保守跳过（不改写，保留颜文字）: "
                    + abbrev(raw));

            // 只更新基线，不动颜文字缓存，避免重新随机
            this.lastSet = raw;
            this.userOriginal = stripAll(raw, cfg);
            this.isDeleting = false;

            recycleNode(inp);
            finish(false, "");
            return;
        }

        // 与 lastSet 无前缀关系，且不是中间编辑（例如整段替换 / 强制处理）
        if (!this.lastSet.isEmpty() && !raw.startsWith(this.lastSet)) {
            AppLog.add("Svc", "检测到全新输入，重置状态");
            resetStateKeepFlag();
        }

        // ---- [优化] 光标位置检查 ----
        // 旧代码 `selStart < 0 || selEnd < 0` 直接跳过，过于激进：
        // QQ/微信大量场景返回 -1（不提供光标信息），会被全部误杀。
        // 另一极端：返回 0-0 往往同样代表"未知"而非"光标在开头"。
        // 只有明确落在文本中间时才跳过。
        int selStart = inp.getTextSelectionStart();
        int selEnd = inp.getTextSelectionEnd();
        boolean selectionKnown = selStart >= 0 && selEnd >= 0
                && (selStart > 0 || selEnd > 0 || raw.length() == 0);
        boolean cursorAtEnd = selStart == raw.length() && selEnd == raw.length();

        if (selectionKnown && !cursorAtEnd) {
            AppLog.add("Svc", "光标不在末尾，跳过: "
                    + selStart + "-" + selEnd + "/" + raw.length());
            recycleNode(inp);
            finish(false, "");
            return;
        }

        if (selStart < 0 || selEnd < 0) {
            AppLog.add("Svc", "无法获取光标位置，按末尾处理（放行）");
        }

        // ---- 增量输入 ----
        boolean isIncremental = !this.lastSet.isEmpty() && raw.startsWith(this.lastSet);
        String newText = isIncremental ? raw.substring(this.lastSet.length()) : raw;

        if (isIncremental) {
            this.userOriginal += newText;
        } else {
            this.userOriginal = stripAll(raw, cfg);
        }

        if (this.userOriginal.trim().isEmpty()) {
            recycleNode(inp);
            finish(false, "");
            resetState();
            return;
        }

        // ---- 逐行处理 ----
        String[] lines = this.userOriginal.split("\\r?\\n", -1);
        String[] availableEmoticons = cfg.getActiveEmoticons();
        if (availableEmoticons == null || availableEmoticons.length == 0) {
            availableEmoticons = CatConfig.BUILTIN_EMOTICONS;
        }

        while (this.lineEmoticonCache.size() > lines.length) {
            this.lineEmoticonCache.remove(this.lineEmoticonCache.size() - 1);
        }
        while (this.linePunctuationState.size() > lines.length) {
            this.linePunctuationState.remove(this.linePunctuationState.size() - 1);
        }

        StringBuilder targetBuilder = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().isEmpty()) {
                targetBuilder.append(line);
                if (i < lines.length - 1) {
                    targetBuilder.append("\n");
                }
                continue;
            }

            boolean hasPunctuation = isPunctuationEnding(line);
            boolean needNewEmo = false;

            if (i >= this.lineEmoticonCache.size()) {
                needNewEmo = true;
            } else if (i < this.linePunctuationState.size()
                    && this.linePunctuationState.get(i)
                    && !hasPunctuation) {
                needNewEmo = true;
            }

            if (needNewEmo) {
                String newEmo = "";
                if (cfg.enableRandomEmoticon && availableEmoticons.length > 0) {
                    newEmo = availableEmoticons[(int) (Math.random() * availableEmoticons.length)];
                }
                if (i < this.lineEmoticonCache.size()) {
                    this.lineEmoticonCache.set(i, newEmo);
                } else {
                    this.lineEmoticonCache.add(newEmo);
                }
            }

            if (i < this.linePunctuationState.size()) {
                this.linePunctuationState.set(i, hasPunctuation);
            } else {
                this.linePunctuationState.add(hasPunctuation);
            }

            String currentEmo = (i < this.lineEmoticonCache.size())
                    ? this.lineEmoticonCache.get(i)
                    : "";

            CatConfig lineCfg = currentEmo.isEmpty()
                    ? cfg
                    : cloneConfigWithFixedEmoticon(cfg, currentEmo);

            targetBuilder.append(TextProcessor.process(line, lineCfg));

            if (i < lines.length - 1) {
                targetBuilder.append("\n");
            }
        }

        String target = targetBuilder.toString();

        if (target.equals(raw)) {
            this.lastSet = target;
            recycleNode(inp);
            finish(false, "");
            return;
        }

        /*
         * [重要修复] 写入前二次校验光标。
         *
         * 事件进来时光标信息往往还不准（QQ 常返回 0-0 或 -1，只能按"未知"放行），
         * 但经过去抖延迟后，光标位置通常已经稳定。
         * 写之前再确认一次：如果此刻明确发现光标不在末尾，就放弃写入——
         * 否则会把用户正在中间编辑的内容整体重写，光标被拉到末尾。
         */
        if (!this.forceProcessOnce) {
            try {
                inp.refresh();
            } catch (Throwable ignored) {
            }

            int reStart = inp.getTextSelectionStart();
            int reEnd = inp.getTextSelectionEnd();
            CharSequence reText = inp.getText();
            int reLen = reText == null ? 0 : reText.length();

            boolean reKnown = reStart >= 0 && reEnd >= 0
                    && (reStart > 0 || reEnd > 0 || reLen == 0);
            boolean reAtEnd = reStart == reLen && reEnd == reLen;

            if (reKnown && !reAtEnd) {
                AppLog.add("Svc", "写入前复核：光标不在末尾，放弃写入 "
                        + reStart + "-" + reEnd + "/" + reLen);
                recycleNode(inp);
                finish(false, "");
                return;
            }

            // 文本在延迟期间又变了，放弃本轮，等下一次事件
            if (reText != null && !raw.equals(reText.toString())) {
                AppLog.add("Svc", "写入前复核：文本已变化，放弃本轮 -> "
                        + abbrev(reText));
                recycleNode(inp);
                finish(false, "");
                return;
            }
        }

        AppLog.add("Svc", "开始写入: " + abbrev(raw) + " -> " + abbrev(target));

        // 通道①：无障碍 SET_TEXT，同步执行并复核内容
        if (trySetTextVerified(inp, target)) {
            AppLog.add("Svc", "通道① 无障碍写入成功");
            recycleNode(inp);
            finish(true, target);
            return;
        }

        recycleNode(inp);

        /*
         * 通道②③④ 放入后台线程：
         * Shizuku 的 IME 切换耗时可达数秒，绝不能阻塞无障碍主线程，
         * 否则期间所有无障碍事件都会被丢弃。
         */
        startFallbackWrite(raw.length(), target, isShizukuFallbackEnabled());
    }

    private void finish(boolean ok, String target) {
        if (ok) {
            this.lastSet = target;
            this.lastWriteTime = System.currentTimeMillis();
            noteRewrite();
        }
        this.forceProcessOnce = false;
        this.processingTimeout = PROCESS_TIMEOUT;
        this.processing = false;
    }

    /**
     * [修复] 改写计数。短时间内改写次数过多说明逻辑可能在自激
     * （改一次 → 触发事件 → 再改一次），进入冷静期直接停手。
     */
    private void noteRewrite() {
        long now = System.currentTimeMillis();

        if (now - this.rewriteWindowStart > REWRITE_WINDOW) {
            this.rewriteWindowStart = now;
            this.rewriteCount = 0;
        }

        this.rewriteCount++;

        if (this.rewriteCount > MAX_REWRITE) {
            this.cooldownUntil = now + COOLDOWN_MS;
            AppLog.add("Warn", "窗口内改写 " + this.rewriteCount
                    + " 次，判定为改写风暴，进入冷静期 " + COOLDOWN_MS + "ms");
            this.rewriteCount = 0;
        }
    }

    // ==================== 写入通道 ②③④（Shizuku / 剪贴板） ====================

    /**
     * 后台线程依次尝试：IME 桥接注入 → input text 直写 → 剪贴板粘贴。
     *
     * @param deleteCount 需删除的字符数（= 输入框当前内容长度）
     */
    private void startFallbackWrite(
            final int deleteCount,
            final String target,
            final boolean useShizuku
    ) {
        // 注入可能耗时数秒，放宽本轮看门狗，避免被误判为卡死
        this.processingTimeout = PROCESS_TIMEOUT_INJECT;

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;

                if (useShizuku) {
                    AppLog.add("Svc", "进入 Shizuku 通道，待删字符=" + deleteCount);

                    ok = ShizukuInjector.injectViaIme(deleteCount, target);
                    AppLog.add("Svc", "通道② IME注入: " + (ok ? "成功" : "失败"));

                    if (!ok) {
                        ok = ShizukuInjector.injectText(deleteCount, target);
                        AppLog.add("Svc", "通道③ input text: " + (ok ? "成功" : "失败"));
                    }
                } else {
                    AppLog.add("Svc", "Shizuku 未就绪或未启用，跳过②③");
                }

                if (!ok) {
                    ok = pasteToFocusedNode(target);
                    AppLog.add("Svc", "通道④ 剪贴板: " + (ok ? "成功" : "失败/无节点"));
                }

                final boolean result = ok;

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finish(result, target);
                    }
                });
            }
        }, "qqcat-inject-worker").start();
    }

    private boolean isShizukuFallbackEnabled() {
        CatConfig cfg = this.cachedConfig;

        return cfg != null
                && cfg.shizukuFallbackEnabled
                && ShizukuInjector.isReady();
    }

    /** 实时模式下复核等待要短一些，否则每次改写都会阻塞无障碍主线程，输入发涩 */
    private int writeVerifySleep() {
        return isRealtimeMode() ? VERIFY_SLEEP_REALTIME : VERIFY_SLEEP;
    }

    private boolean isRealtimeMode() {
        CatConfig cfg = this.cachedConfig;
        return cfg != null && CatConfig.MODE_REALTIME.equals(cfg.processingMode);
    }

    /** 通道④：重新查找输入节点后粘贴（此前的节点已回收，必须重找） */
    private boolean pasteToFocusedNode(String target) {
        AccessibilityNodeInfo node = null;
        try {
            node = findFocusedEditableNode(null);
            if (node == null) {
                return false;
            }
            return pasteSafe(node, target);
        } catch (Throwable e) {
            AppLog.add("Err", "兜底粘贴异常", e);
            return false;
        } finally {
            recycleNode(node);
        }
    }

    private void resetState() {
        this.processing = false;
        resetStateKeepFlag();
    }

    /** 重置业务状态但保留 processing 标记（由调用方负责收尾） */
    private void resetStateKeepFlag() {
        this.userOriginal = "";
        this.lastSet = "";
        this.lastWriteTime = 0L;
        this.lastProcessTime = 0L;
        this.pendingScheduleTime = 0L;
        this.lineEmoticonCache.clear();
        this.linePunctuationState.clear();
        this.isDeleting = false;
        this.lastDeleteTime = 0L;
        recycleNode(pendingInputNode);
        pendingInputNode = null;
    }

    // ==================== 输入节点查找（问题 6 核心） ====================

    /**
     * 查找顺序：
     * 1. 事件源自身
     * 2. 事件源父链（向上最多 3 层）
     * 3. 事件源子树
     * 4. 根窗口 FOCUS_INPUT
     * 5. 根窗口递归（依赖类名/editable 标记）
     * 6. [新增] 按事件文本反查节点 —— 完全不依赖类名，专治微信节点混淆
     *
     * @param eventText 事件自带的文本，可为空
     */
    private AccessibilityNodeInfo findFocusedEditableNode(AccessibilityEvent event,
                                                          String eventText) {
        if (event != null) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                AccessibilityNodeInfo editable = findInputNearSource(source);
                recycleNode(source);
                if (editable != null) {
                    this.nodeFailStreak = 0;
                    return editable;
                }
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            AppLog.add("Svc", "getRootInActiveWindow 返回 null");
            return null;
        }

        try {
            /*
             * [修复] 确认活动窗口属于目标应用。
             * 日志里出现过在荣耀桌面（com.hihonor.android.launcher）的节点树里
             * 反复查找输入框——事件来源是 QQ，但当前活动窗口是桌面，
             * 于是在完全无关的窗口里白跑全树递归。
             */
            try {
                CharSequence pkgCs = root.getPackageName();
                String rootPkg = pkgCs == null ? "" : pkgCs.toString();
                CatConfig cfg0 = this.cachedConfig;

                if (!rootPkg.isEmpty()
                        && cfg0 != null
                        && cfg0.targetPackages != null
                        && !cfg0.targetPackages.contains(rootPkg)) {
                    AppLog.add("Svc", "活动窗口不属于目标应用，跳过查找: " + rootPkg);
                    return null;
                }
            } catch (Throwable ignored) {
            }

            AccessibilityNodeInfo focused =
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

            if (focused != null) {
                if (isUsableInputNode(focused)) {
                    AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(focused);
                    recycleNode(focused);
                    this.nodeFailStreak = 0;
                    return result;
                }

                /*
                 * 系统已明确报告"这是输入焦点"，但属性校验不通过
                 * （微信可能混淆了 editable / 类名）。此时应该相信系统焦点判定。
                 *
                 * [修复] 但必须有个底线：类名和文本全为空的"空壳节点"不能采用。
                 * 上一版少了这层校验，导致日志里刷屏 40+ 次
                 * "FOCUS_INPUT 属性不可信，仍采用: ? txt=-"。
                 */
                CharSequence fCls = focused.getClassName();
                CharSequence fTxt = focused.getText();
                boolean hasIdentity = (fCls != null && fCls.length() > 0)
                        || (fTxt != null && fTxt.length() > 0);

                if (hasIdentity && !isNonChatHintNode(focused)) {
                    AppLog.add("Svc", "FOCUS_INPUT 属性不可信，仍采用: "
                            + (fCls == null ? "?" : fCls.toString())
                            + " txt=" + (fTxt == null ? "-" : abbrev(fTxt)));

                    AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(focused);
                    recycleNode(focused);
                    this.nodeFailStreak = 0;
                    return result;
                }

                recycleNode(focused);
            }

            AccessibilityNodeInfo editable = findEditableRecursive(root);

            if (editable != null) {
                this.nodeFailStreak = 0;
                return editable;
            }

            /*
             * [关键兜底] 前面全部失败时，用事件文本反查节点。
             *
             * 微信 v8.0.52+ 对第三方无障碍服务做节点混淆，类名 / resourceId 被打乱，
             * isEditable() 也可能不可信，导致 1~5 步全部落空。
             * 但只要 getText() 还能拿到真实内容，就能靠"文本内容"把节点认出来——
             * 这一步完全不看类名，是混淆环境下最可靠的定位方式。
             */
            if (eventText != null && !eventText.isEmpty()) {
                AccessibilityNodeInfo byText = findNodeByText(root, eventText);
                if (byText != null) {
                    AppLog.add("Svc", "通过文本反查命中输入框: " + abbrev(eventText));
                    this.nodeFailStreak = 0;
                    return byText;
                }
            }

            AppLog.add("Svc", "根窗口递归未找到输入节点");
            this.nodeFailStreak++;
            dumpNodeTree(root);
            return null;
        } finally {
            recycleNode(root);
        }
    }

    private AccessibilityNodeInfo findFocusedEditableNode(AccessibilityEvent event) {
        return findFocusedEditableNode(event, null);
    }

    /**
     * 遍历节点树，找出文本内容与目标一致的节点。
     * 优先返回最深的匹配节点（越深越可能是真正的输入框而非容器）。
     */
    private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo root, String target) {
        if (root == null || target == null || target.isEmpty()) {
            return null;
        }

        AccessibilityNodeInfo best = null;
        int bestDepth = -1;

        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        queue.add(new Object[]{root, 0});

        int visited = 0;

        while (!queue.isEmpty() && visited < 150) {
            Object[] item = queue.poll();
            AccessibilityNodeInfo n = (AccessibilityNodeInfo) item[0];
            int depth = (Integer) item[1];
            visited++;

            if (n == null) {
                continue;
            }

            try {
                CharSequence cs = n.getText();
                if (cs != null) {
                    String t = cs.toString();
                    if (t.equals(target) && depth > bestDepth) {
                        if (best != null) {
                            recycleNode(best);
                        }
                        // obtain 副本，与 n 的生命周期解耦
                        best = AccessibilityNodeInfo.obtain(n);
                        bestDepth = depth;
                    }
                }

                if (depth < 8) {
                    int c = n.getChildCount();
                    for (int i = 0; i < c; i++) {
                        AccessibilityNodeInfo child = n.getChild(i);
                        if (child != null) {
                            queue.add(new Object[]{child, depth + 1});
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                /*
                 * 必须回收：Android 对同时存在的 AccessibilityNodeInfo 有数量上限，
                 * 未回收过多会抛 IllegalStateException。
                 * root 由调用方回收，这里只回收子节点。
                 */
                if (depth > 0) {
                    recycleNode(n);
                }
            }
        }

        return best;
    }

    /**
     * [修复] 找不到节点后的退避策略。
     * 连续失败越多，间隔越长（50ms → 100 → 200 → 400，上限 2000ms），
     * 避免在微信这种大节点树上每秒空跑好几次全树递归。
     */
    private void scheduleBackoff() {
        long delay = 50L;
        for (int i = 1; i < this.nodeFailStreak && delay < 2000L; i++) {
            delay *= 2;
        }
        if (delay > 2000L) {
            delay = 2000L;
        }
        this.nextNodeSearchAt = System.currentTimeMillis() + delay;
    }

    /** 从事件里提取文本（TYPE_VIEW_TEXT_CHANGED 通常带变化后的完整文本） */
    private String extractEventText(AccessibilityEvent e) {
        if (e == null) {
            return "";
        }
        try {
            List<CharSequence> list = e.getText();
            if (list == null || list.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (CharSequence c : list) {
                if (c != null) {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * [诊断] 找不到输入节点时，把窗口节点树的关键属性打出来。
     *
     * 微信 v8.0.52+ 会对第三方无障碍服务做节点混淆（类名 / resourceId 被打乱），
     * 导致依赖类名的判定全部失效。这个方法用来一眼看清：
     *   - 类名是正常的 EditText / MMEditText？还是被混淆成了随机串？
     *   - 有没有节点带着我们输入的文字？
     * 输出限频（TREE_DUMP_INTERVAL），避免刷爆日志。
     */
    private void dumpNodeTree(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - this.lastTreeDumpTime < TREE_DUMP_INTERVAL) {
            return;
        }
        this.lastTreeDumpTime = now;

        try {
            /*
             * [修复] 逐行输出，每条日志一行。
             * 之前把整棵树拼成一个带换行的字符串一次性 add，
             * 日志查看器里只显示第一行，根本看不到内容。
             */
            AppLog.add("Tree", "===== 快照开始 =====");
            collectNodeInfo(root, 0, new int[]{0});
            AppLog.add("Tree", "===== 快照结束 =====");
        } catch (Throwable t) {
            AppLog.add("Err", "dump 节点树失败", t);
        }
    }

    /** 逐行输出，每行一条独立日志，避免被查看器截断 */
    private void collectNodeInfo(AccessibilityNodeInfo n, int depth, int[] counter) {
        if (n == null || depth > 6 || counter[0] >= 25) {
            return;
        }

        try {
            CharSequence clsCs = n.getClassName();
            String cls = clsCs == null ? "?" : clsCs.toString();
            String id = n.getViewIdResourceName();
            CharSequence txt = n.getText();
            boolean editable = false;
            int selStart = -9;
            try {
                editable = n.isEditable();
                selStart = n.getTextSelectionStart();
            } catch (Throwable ignored) {
            }

            // 只输出可能有用的节点，减少噪音
            boolean interesting = editable || selStart >= 0
                    || (txt != null && txt.length() > 0)
                    || (id != null && id.length() > 0);

            if (interesting) {
                counter[0]++;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < depth; i++) {
                    sb.append("..");
                }
                sb.append("[d").append(depth).append("] ")
                        .append(cls)
                        .append(" | id=").append(id == null ? "-" : id)
                        .append(" | edit=").append(editable)
                        .append(" | sel=").append(selStart)
                        .append(" | vis=").append(n.isVisibleToUser())
                        .append(" | txt=").append(
                        txt == null ? "-" : abbrev(txt));

                AppLog.add("Tree", sb.toString());
            }

            int count = n.getChildCount();
            for (int i = 0; i < count && counter[0] < 25; i++) {
                collectNodeInfo(n.getChild(i), depth + 1, counter);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 事件源自身 → 父链 → 子树 */
    private AccessibilityNodeInfo findInputNearSource(AccessibilityNodeInfo source) {
        if (source == null) {
            return null;
        }

        if (isUsableInputNode(source)) {
            return AccessibilityNodeInfo.obtain(source);
        }

        // [优化] 事件源往往是输入框的容器/装饰节点，需要向上找
        AccessibilityNodeInfo parent = source.getParent();
        int up = 0;
        while (parent != null && up < 3) {
            if (isUsableInputNode(parent)) {
                AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(parent);
                recycleNode(parent);
                return result;
            }
            AccessibilityNodeInfo next = parent.getParent();
            recycleNode(parent);
            parent = next;
            up++;
        }
        recycleNode(parent);

        return findEditableRecursive(source);
    }

    /**
     * [优化] 节点可用性判定，比单纯 isEditable() 宽松得多。
     * QQ / 微信大量自定义输入控件 isEditable() 返回 false。
     */
    private boolean isUsableInputNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }

        try {
            try {
                node.refresh();
            } catch (Throwable ignored) {
            }

            /*
             * [修复] 放宽可见性判定。
             * 部分 App（含微信某些版本）会把输入框节点标记为 isVisibleToUser=false，
             * 导致常规查找直接落空。这里只保留 isEnabled 硬门槛，
             * 并对"能拿到文本/光标"的节点放行可见性检查。
             */
            boolean hasText = false;
            try {
                CharSequence cs = node.getText();
                hasText = cs != null && cs.length() > 0;
            } catch (Throwable ignored) {
            }

            if (!node.isEnabled()) {
                return false;
            }

            if (!node.isVisibleToUser() && !hasText) {
                return false;
            }

            /*
             * [重要修复] 在节点层面就拒绝非聊天输入框。
             *
             * 日志里 QQ 的搜索框（id=z7z，hint="搜索"）被当成聊天输入框，
             * 还触发了状态重置。当时靠"搜索"二字被占位符判定拦下纯属运气——
             * 一旦在搜索框里输入具体关键词，hint 判定失效，
             * 程序就会把"张三喵 (ฅ^ω^ฅ)"写进搜索框。
             * 所以必须在查找阶段就把它排除掉。
             */
            if (isNonChatHintNode(node)) {
                return false;
            }

            if (isEditableClass(node)) {
                return true;
            }

            // 提供了光标位置，基本可以确认是输入框
            if (node.getTextSelectionStart() >= 0 || node.getTextSelectionEnd() >= 0) {
                return true;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
                if (actions != null) {
                    for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
                        if (action != null
                                && action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * 判断节点是否是"不该碰的输入框"：搜索框、查找框、备注框等。
     *
     * 判据用 hint 文本（比 resource-id 稳定，不随版本变更）。
     * 只在 hint 较短时做包含匹配，避免误伤正常聊天内容。
     */
    private boolean isNonChatHintNode(AccessibilityNodeInfo n) {
        if (n == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }

        try {
            CharSequence hintCs = n.getHintText();
            if (hintCs == null) {
                return false;
            }

            String h = hintCs.toString().trim();
            if (h.isEmpty()) {
                return false;
            }

            // 精确匹配的常见非聊天输入框提示语
            if (h.equals("搜索") || h.equals("查找") || h.equals("搜索聊天记录")
                    || h.equals("请输入关键词") || h.equals("备注")
                    || h.equals("请输入备注") || h.equals("输入昵称")
                    || h.equals("群名称") || h.equals("标签")) {
                return true;
            }

            // 短 hint 才做包含匹配，长文本一律不判，避免误伤
            if (h.length() <= 10) {
                return h.contains("搜索") || h.contains("查找")
                        || h.contains("关键词") || h.contains("备注");
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private boolean isEditableClass(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }

        try {
            if (n.isEditable()) {
                return true;
            }

            CharSequence className = n.getClassName();
            String cls = className != null ? className.toString() : "";
            String lower = cls.toLowerCase();

            return cls.contains("EditText")
                    || cls.contains("MMEditText")
                    || cls.contains("CustomEditText")
                    || cls.contains("TencentEditText")
                    || cls.contains("WXEditText")
                    || cls.contains("InputText")
                    || lower.contains("edit");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private AccessibilityNodeInfo findEditableRecursive(AccessibilityNodeInfo n) {
        if (n == null) {
            return null;
        }

        // 类名匹配后还要排除搜索框等非聊天输入框
        if (isEditableClass(n) && !isNonChatHintNode(n)) {
            return AccessibilityNodeInfo.obtain(n);
        }

        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) {
                continue;
            }
            AccessibilityNodeInfo r = findEditableRecursive(c);
            // 不提前回收 child：部分 Android 版本回收后会使返回的副本失效
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** 输入框身份标识：窗口 + 视图 id + 类名，用于判断是否切换了输入框 */
    private String buildInputKey(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        try {
            String id = n.getViewIdResourceName();
            CharSequence cls = n.getClassName();
            return n.getWindowId() + "#" + (id == null ? "" : id)
                    + "#" + (cls == null ? "" : cls.toString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==================== 文本写入 ====================

    /**
     * [优化] 通道①：ACTION_SET_TEXT + 内容复核。
     *
     * 旧代码只检查 performAction 的返回值，而 QQ 部分版本会返回 true
     * 却并未真正修改内容（"假成功"），于是 lastSet 被错误更新，
     * 之后即使没写上也不会重试。
     *
     * 这里复核失败即返回 false，交给后台线程的②③④兜底。
     */
    private boolean trySetTextVerified(AccessibilityNodeInfo n, String t) {
        if (n == null || t == null) {
            return false;
        }

        try {
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

            Bundle b = new Bundle();
            b.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", t);

            boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);

            if (ok) {
                sleepQuiet(writeVerifySleep());
                try {
                    n.refresh();
                } catch (Throwable ignored) {
                }

                CharSequence cur = n.getText();

                if (t.contentEquals(cur)) {
                    setSelectionToEnd(n, t.length());
                    AppLog.add("Svc", "通道① SET_TEXT 写入成功");
                    return true;
                }

                AppLog.add("Svc", "通道①假成功，实际内容: " + abbrev(cur));
            }
        } catch (Throwable e) {
            AppLog.add("Err", "通道①异常", e);
        }

        return false;
    }

    private boolean pasteSafe(AccessibilityNodeInfo n, String t) {
        ClipboardManager cm;
        try {
            cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        } catch (Throwable e) {
            return false;
        }
        if (cm == null) {
            return false;
        }

        ClipData originalClip = null;
        try {
            originalClip = cm.getPrimaryClip();
            cm.setPrimaryClip(ClipData.newPlainText("cat_temp", t));

            boolean pasteOk = n.performAction(AccessibilityNodeInfo.ACTION_PASTE);

            if (pasteOk) {
                sleepQuiet(writeVerifySleep());
                try {
                    n.refresh();
                } catch (Throwable ignored) {
                }
                CharSequence cur = n.getText();
                pasteOk = t.contentEquals(cur);

                if (pasteOk) {
                    setSelectionToEnd(n, t.length());
                    AppLog.add("Svc", "通道④ 剪贴板粘贴成功");
                } else {
                    AppLog.add("Svc", "通道④粘贴后内容不符: " + abbrev(cur));
                }
            } else {
                AppLog.add("Svc", "通道④ ACTION_PASTE 返回 false");
            }

            restoreClipboard(cm, originalClip);
            return pasteOk;
        } catch (Throwable e) {
            AppLog.add("Err", "剪贴板兜底异常", e);
            restoreClipboard(cm, originalClip);
            return false;
        }
    }

    private void restoreClipboard(final ClipboardManager cm, final ClipData original) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (original != null) {
                        cm.setPrimaryClip(original);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.clearPrimaryClip();
                    }
                } catch (Throwable e) {
                    AppLog.add("Err", "还原剪贴板失败", e);
                }
            }
        }, 100);
    }

    private void setSelectionToEnd(AccessibilityNodeInfo n, int len) {
        try {
            Bundle a = new Bundle();
            a.putInt("ACTION_ARGUMENT_SELECTION_START_INT", len);
            a.putInt("ACTION_ARGUMENT_SELECTION_END_INT", len);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, a);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 占位符判定（问题 5 核心） ====================

    /**
     * [优化-问题5]
     *
     * 旧实现三宗罪：
     *   1. `if (!inp.isFocused()) return true;`
     *      —— 事件回调期间 isFocused() 常短暂返回 false，几乎吃掉全部正常输入。已删除。
     *   2. `clean.contains("…")`
     *      —— 真实聊天里省略号极其常见（"好吧…""我服了…"），三个字就被吞。已删除。
     *   3. 对所有长度的文本用 contains 匹配黑名单
     *      —— 输入"我说点什么好呢"会命中 "说点什么"。改为仅短文本 + equals。
     *
     * 正确思路：占位符只可能是"提示文本本身"，判定依据是"整段文本等于提示语"，
     * 而不是"文本里含有提示语"。
     */
    private boolean isHintOrPlaceholder(AccessibilityNodeInfo inp, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return true;
        }

        String clean = raw.trim();

        // 1. 与控件 hint 完全一致 → 确实是尚未输入的占位提示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                CharSequence hint = inp.getHintText();
                if (hint != null) {
                    String h = hint.toString().trim();
                    if (!h.isEmpty() && clean.equalsIgnoreCase(h)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. 长文本一律不可能是占位符（提示语都很短）
        if (clean.length() > PLACEHOLDER_MAX_LEN) {
            return false;
        }

        // 3. 短文本才做黑名单比对，且用 equals 而非 contains
        //    （补充"搜索"等，作为节点级过滤之外的第二道防线）
        return clean.equals("搜索")
                || clean.equals("查找")
                || clean.equals("搜索聊天记录")
                || clean.equals("发条有爱评论")
                || clean.equals("善语结善缘")
                || clean.equals("爱意随风起")
                || clean.equals("说点什么")
                || clean.equals("说点什么…")
                || clean.equals("说点什么吧")
                || clean.equals("发送消息")
                || clean.equals("留下你的精彩评论")
                || clean.equals("发消息")
                || clean.equals("输入消息")
                || clean.equals("发微信")
                || clean.equals("写评论")
                || clean.equals("请输入内容");
    }

    // ==================== 工具方法 ====================

    /**
     * [优化] 标点触发范围扩展：，、；：也触发。
     * 旧版只认 。！？空格换行~，导致"吃了吗，我刚到家"这类句子永远不触发。
     */
    private boolean isPunctuationEnding(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char last = s.charAt(s.length() - 1);

        boolean base = last == '。' || last == '！' || last == '!' || last == '？'
                || last == '?' || last == ' ' || last == '\n' || last == '\r'
                || last == '~' || last == '～';

        if (base) {
            return true;
        }

        return TRIGGER_ON_COMMA
                && (last == '，' || last == ',' || last == '、'
                || last == '；' || last == ';' || last == '：' || last == ':');
    }

    /** 发送按钮识别，用于在点发送的瞬间补一次处理 */
    private boolean isSendButton(AccessibilityEvent e) {
        AccessibilityNodeInfo src = null;
        try {
            src = e.getSource();
            if (src == null) {
                return false;
            }
            CharSequence desc = src.getContentDescription();
            CharSequence text = src.getText();
            return containsSendWord(desc) || containsSendWord(text);
        } catch (Throwable ignored) {
            return false;
        } finally {
            recycleNode(src);
        }
    }

    private boolean containsSendWord(CharSequence c) {
        if (c == null) {
            return false;
        }
        String s = c.toString().trim();
        if (s.isEmpty() || s.length() > 8) {
            return false;
        }
        return s.equals("发送") || s.equals("发送(S)") || s.equals("发送(S)") || s.contains("发送");
    }

    private CatConfig cloneConfigWithFixedEmoticon(CatConfig src, String fixedEmoticon) {
        CatConfig c = new CatConfig();
        c.enableAppend = src.enableAppend;
        c.appendText = src.appendText;
        c.enableRandomEmoticon = true;
        c.processingMode = src.processingMode;
        c.customEmoticons = new String[]{fixedEmoticon};
        c.rules = src.rules;
        c.targetPackages = src.targetPackages;
        // 若你的 CatConfig 没有这两个字段（老版本），把下面两行删掉即可
        c.processingEnabled = src.processingEnabled;
        c.shizukuFallbackEnabled = src.shizukuFallbackEnabled;
        return c;
    }

    private String stripAll(String text, CatConfig cfg) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        String appendText = (cfg != null && cfg.appendText != null && !cfg.appendText.isEmpty())
                ? cfg.appendText
                : "喵";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean changed;

            do {
                changed = false;
                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    line = trimmed;
                    break;
                }

                boolean removedEmoji = false;

                if (cfg != null && cfg.getActiveEmoticons() != null) {
                    for (String emo : cfg.getActiveEmoticons()) {
                        if (emo != null && !emo.isEmpty() && trimmed.endsWith(emo)) {
                            trimmed = trimmed.substring(0, trimmed.length() - emo.length()).trim();
                            changed = true;
                            removedEmoji = true;
                            break;
                        }
                    }
                }

                if (!removedEmoji && CatConfig.BUILTIN_EMOTICONS != null) {
                    for (String emo : CatConfig.BUILTIN_EMOTICONS) {
                        if (emo != null && !emo.isEmpty() && trimmed.endsWith(emo)) {
                            trimmed = trimmed.substring(0, trimmed.length() - emo.length()).trim();
                            changed = true;
                            removedEmoji = true;
                            break;
                        }
                    }
                }

                if (!appendText.isEmpty()) {
                    while (trimmed.endsWith(appendText)) {
                        trimmed = trimmed.substring(0, trimmed.length() - appendText.length()).trim();
                        changed = true;
                    }
                }

                if (!changed) {
                    String cleaned = trimmed.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9，。！？,.!?\\s]+$", "");
                    if (!cleaned.equals(trimmed)) {
                        trimmed = cleaned.trim();
                        changed = true;
                    }
                }

                line = trimmed;
            } while (changed);

            sb.append(line);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private void recycleNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignored) {
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String abbrev(CharSequence text) {
        if (text == null) {
            return "null";
        }
        String v = text.toString().replace('\n', '↵').replace('\r', '↵');
        return v.length() > 40 ? v.substring(0, 40) + "..." : v;
    }

    @Override
    public void onInterrupt() {
        mainHandler.removeCallbacks(processRunnable);
        resetState();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        // 关键：必须以 XML 配置（getServiceInfo）为基础叠加，
        // 不能 new 一个空对象整体覆盖——那样会丢掉 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，
        // 导致 getRootInActiveWindow() 恒返回 null，全自动加喵完全失效。
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes |=
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_CLICKED   // [优化] 监听发送按钮
                        | AccessibilityEvent.TYPE_VIEW_FOCUSED;  // [优化] 键盘聚焦时补一次查找

        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |=
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;

        info.notificationTimeout = 50;
        setServiceInfo(info);

        AppLog.init(this);
        this.cachedConfig = CatConfig.load(this);
        AppLog.add("Svc", "无障碍服务已连接");
    }
}
