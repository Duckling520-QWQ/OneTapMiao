package com.onetapmiao.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * OneTapMiao 主界面（v1.1.0 单宿主三页版）。
 *
 * 结构：纵向根布局 = 内容区（横向滑动的三个页面）+ 底部固定导航。
 *   ┌─────────────────────┐
 *   │  HorizontalScrollView │  ← 左右滑 = 切换页面
 *   │  ┌────┬────┬────┐    │    每页内部是竖向 ScrollView（上下滑 = 滚内容）
 *   │  │状态│功能│权限│    │
 *   │  └────┴────┴────┘    │
 *   ├─────────────────────┤
 *   │  [状态] [功能] [权限] │  ← 底部固定，永不滚走
 *   └─────────────────────┘
 *
 * 为什么这么设计：
 *   1. 「一滑就到下一界面」——横滑翻页，手势天然支持，不与页面内竖向滚动冲突；
 *   2. 「导航在最下面」——底部固定，内容再长也点得到；
 *   3. 「使用应该先要权限」——启动默认停在权限页，先把权限给全再开始用。
 *
 * 页面顺序：状态 / 功能 / 权限（默认显示权限页）。
 */
public class MainActivity extends Activity {

    /** 页面索引常量 */
    private static final int PAGE_STATUS = 0;
    private static final int PAGE_FEATURE = 1;
    private static final int PAGE_PERMISSION = 2;

    private static final int REQ_OVERLAY = 100;
    private static final int REQ_SHIZUKU = 443;

    /**
     * 构建水印：确认手机跑的是不是最新包。
     * 状态页 →「查看诊断日志」→ 第一行显示它；没有这行说明装的是旧 APK。
     */
    private static final String BUILD_STAMP = "v1.1.0-b11 · 2026-08-30 13:05";

    // ---- 页面切换 ----
    private SnapHScrollView hsv;
    private LinearLayout pagesContainer;
    private MaterialButton[] navButtons = new MaterialButton[3];
    private int screenWidth;

    // ---- 状态页 ----
    private TextView statusText;
    private View serviceDot;
    private MaterialButton toggleButton;
    private MaterialButton floatingWindowButton;
    private boolean isFloatingWindowShown = false;
    /** v1.1.0：状态页 Shizuku 速览状态行（与无障碍同款格式） */
    private View shizukuShortDot;
    private TextView shizukuShortStatus;
    private MaterialButton shizukuShortButton;

    // ---- 功能页 ----
    private MaterialSwitch swAppend;
    private TextInputEditText etAppendText;
    private MaterialSwitch swEmoticon;
    private MaterialButton btnModePunctuation;
    private MaterialButton btnModeRealtime;
    private boolean modeRealtime = false;
    private TextInputEditText etRules;
    private TextInputEditText etCustomEmoticons;
    private TextInputEditText etPackageName;
    private LinearLayout packageListContainer;
    private CatConfig config;

    // ---- 权限页（卡片式，与状态页无障碍卡片同款）----
    private View batteryDot;
    private TextView batteryStatus;
    private MaterialButton batteryButton;
    private View imeDot;
    private TextView imeStatus;
    private MaterialButton imeButton;
    private View notifyDot;
    private TextView notifyStatus;
    private MaterialButton notifyButton;
    private View overlayDot;
    private TextView overlayStatus;
    private MaterialButton overlayButton;
    private TextView shizukuStatus;
    private MaterialButton shizukuAuthButton;
    private boolean notifyPermissionDeniedOnce = false;

    // ---- Shizuku 监听 ----
    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    updateShizukuUi();
                    if (requestCode == REQ_SHIZUKU) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(MainActivity.this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Shizuku 授权被拒绝，将无法静默直写", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            };
    private final Shizuku.OnBinderReceivedListener shizukuBinderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    updateShizukuUi();
                }
            };
    private final Shizuku.OnBinderDeadListener shizukuDeadListener =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    updateShizukuUi();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        config = CatConfig.load(this);
        screenWidth = getResources().getDisplayMetrics().widthPixels;

        // ---- 根布局：内容区（weight=1）+ 底部导航 ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 内容区：横向滑动容器 ----
        hsv = new SnapHScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(true);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        pagesContainer = new LinearLayout(this);
        pagesContainer.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(pagesContainer);

        // 三个页面（每个宽度 = 屏幕宽）
        pagesContainer.addView(buildStatusPage());
        pagesContainer.addView(buildFeaturePage());
        pagesContainer.addView(buildPermissionPage());

        // 松手贴页 + 导航高亮跟随
        hsv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    // 消费抬手事件：不让 HSV 启动惯性 fling。
                    // fling 会和 smoothScrollTo 抢滚动动画，正是「停在两页之间」的根因。
                    int page = Math.round(hsv.getScrollX() / (float) screenWidth);
                    int target = Math.max(PAGE_STATUS, Math.min(PAGE_PERMISSION, page));
                    hsv.smoothScrollTo(target * screenWidth, 0);
                    updateNavHighlight(target);
                    return true;
                }
                return false;
            }
        });

        // 滚动过程中实时同步导航高亮（自定义容器回调 onScrollChanged）
        hsv.setOnScroll(new Runnable() {
            @Override
            public void run() {
                int page = Math.round(hsv.getScrollX() / (float) screenWidth);
                if (page >= PAGE_STATUS && page <= PAGE_PERMISSION) {
                    updateNavHighlight(page);
                }
            }
        });

        root.addView(hsv);

        // ---- 底部固定导航 ----
        root.addView(buildBottomNav());

        setContentView(root);

        // 「使用应该先要权限」：默认停在权限页，先把权限给全
        // postDelayed 等首帧布局完成后再跳，避免「进来卡在中间」
        hsv.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 用容器实测宽度校准页宽：DisplayMetrics.widthPixels 在分屏 / 折叠屏 /
                // 自由窗口下返回的是屏幕宽而不是窗口宽，分页计算会与实际宽度对不上，
                // 表现为「松手停在两页之间」。这里以实测宽为准并重设三页宽度。
                int measured = hsv.getWidth();
                if (measured > 0 && measured != screenWidth) {
                    screenWidth = measured;
                    for (int i = 0; i < pagesContainer.getChildCount(); i++) {
                        View page = pagesContainer.getChildAt(i);
                        ViewGroup.LayoutParams lp = page.getLayoutParams();
                        if (lp != null) {
                            lp.width = screenWidth;
                            page.setLayoutParams(lp);
                        }
                    }
                }
                hsv.scrollTo(PAGE_PERMISSION * screenWidth, 0);
                updateNavHighlight(PAGE_PERMISSION);
            }
        }, 200);
    }

    // ==================================================================
    // 页面构建
    // ==================================================================

    private ScrollView buildStatusPage() {
        ScrollView sv = pageScroll();
        LinearLayout root = pageContent();

        root.addView(pageTitle("OneTapMiao", "控制面板 · 服务与悬浮窗状态一览"));

        // 无障碍服务状态（核心入口）
        MaterialCardView statusCard = UiKit.createCard(this);
        LinearLayout statusLayout = UiKit.cardContent(this);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 8));

        serviceDot = UiKit.dot(this, 0xFF9E9E9E);
        statusRow.addView(serviceDot);
        statusText = new TextView(this);
        statusText.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceTitleMedium);
        statusText.setGravity(Gravity.CENTER);
        statusRow.addView(statusText);
        statusLayout.addView(statusRow);

        toggleButton = UiKit.actionButton(this, "前往开启无障碍服务", UiKit.STYLE_FILLED);
        toggleButton.setOnClickListener(v -> openAccessibilitySettings());
        statusLayout.addView(toggleButton);

        statusCard.addView(statusLayout);
        root.addView(statusCard);

        // Shizuku 状态（与无障碍同一格式，紧贴其下）
        MaterialCardView shzCard = UiKit.createCard(this);
        LinearLayout shzLayout = UiKit.cardContent(this);

        LinearLayout shzRow = new LinearLayout(this);
        shzRow.setOrientation(LinearLayout.HORIZONTAL);
        shzRow.setGravity(Gravity.CENTER);
        shzRow.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 8));

        shizukuShortDot = UiKit.dot(this, 0xFF9E9E9E);
        shzRow.addView(shizukuShortDot);
        shizukuShortStatus = new TextView(this);
        shizukuShortStatus.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleMedium);
        shizukuShortStatus.setGravity(Gravity.CENTER);
        shzRow.addView(shizukuShortStatus);
        shzLayout.addView(shzRow);

        // 点按钮直接弹 Shizuku 授权（未安装时才滑到权限页看说明）
        shizukuShortButton = UiKit.actionButton(this, "前往授权Shizuku服务", UiKit.STYLE_FILLED);
        shizukuShortButton.setOnClickListener(v -> requestShizukuPermission());
        shzLayout.addView(shizukuShortButton);

        shzCard.addView(shzLayout);
        root.addView(shzCard);

        // 悬浮窗开关
        floatingWindowButton = UiKit.actionButton(this, "开启悬浮窗", UiKit.STYLE_TONAL);
        floatingWindowButton.setLayoutParams(UiKit.fullWidthParams(this, UiKit.dp(this, 8)));
        floatingWindowButton.setOnClickListener(v -> toggleFloatingWindow());
        root.addView(floatingWindowButton);

        // 悬浮窗外观入口
        MaterialButton floatSettingsButton = UiKit.actionButton(this,
                "悬浮窗外观设置", UiKit.STYLE_OUTLINED);
        floatSettingsButton.setLayoutParams(UiKit.fullWidthParams(this, UiKit.dp(this, 8)));
        floatSettingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, FloatSettingsActivity.class)));
        root.addView(floatSettingsButton);

        // 诊断日志
        MaterialButton logBtn = UiKit.actionButton(this, "查看诊断日志", UiKit.STYLE_OUTLINED);
        logBtn.setLayoutParams(UiKit.fullWidthParams(this, UiKit.dp(this, 8)));
        logBtn.setOnClickListener(v -> showLogDialog());
        root.addView(logBtn);

        sv.addView(root);
        return sv;
    }

    private ScrollView buildFeaturePage() {
        ScrollView sv = pageScroll();
        LinearLayout root = pageContent();

        root.addView(pageTitle("OneTapMiao", "改写规则 · 所有规则均可自定义"));

        // ---- 处理模式 ----
        MaterialCardView modeCard = UiKit.createCard(this);
        LinearLayout modeLayout = UiKit.cardContent(this);
        modeLayout.addView(UiKit.cardTitle(this, "处理模式"));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        modeRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));

        btnModePunctuation = new MaterialButton(this);
        btnModePunctuation.setText("标点触发");
        btnModePunctuation.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        btnModePunctuation.setMinWidth(0);
        btnModePunctuation.setMinimumWidth(0);
        btnModePunctuation.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        btnModePunctuation.setCornerRadius(UiKit.dp(this, 20));
        btnModePunctuation.setInsetTop(0);
        btnModePunctuation.setInsetBottom(0);
        btnModePunctuation.setOnClickListener(v -> setMode(false));
        modeRow.addView(btnModePunctuation, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(
                UiKit.dp(this, 8), ViewGroup.LayoutParams.MATCH_PARENT));
        modeRow.addView(gap);

        btnModeRealtime = new MaterialButton(this);
        btnModeRealtime.setText("实时处理");
        btnModeRealtime.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        btnModeRealtime.setMinWidth(0);
        btnModeRealtime.setMinimumWidth(0);
        btnModeRealtime.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        btnModeRealtime.setCornerRadius(UiKit.dp(this, 20));
        btnModeRealtime.setInsetTop(0);
        btnModeRealtime.setInsetBottom(0);
        btnModeRealtime.setOnClickListener(v -> setMode(true));
        modeRow.addView(btnModeRealtime, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        modeLayout.addView(modeRow);
        modeRealtime = CatConfig.MODE_REALTIME.equals(config.processingMode);
        applyModeSelection();
        modeLayout.addView(UiKit.hintText(this,
                "标点触发：打字时只在标点处立即处理（推荐，稳定省电）\n"
                        + "实时处理：每输入一个字立即处理（更快，但更容易和输入法打架）"));

        modeCard.addView(modeLayout);
        root.addView(modeCard);

        // ---- 功能开关 ----
        MaterialCardView funcCard = UiKit.createCard(this);
        LinearLayout funcLayout = UiKit.cardContent(this);
        funcLayout.addView(UiKit.cardTitle(this, "功能开关"));

        swAppend = UiKit.addSwitch(funcLayout, this,
                "断句追加", "在句号、叹号等标点分句后追加文本", config.enableAppend);

        TextInputLayout appendLayout = new TextInputLayout(this);
        appendLayout.setHint("追加内容（默认：喵）");
        appendLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        etAppendText = new TextInputEditText(this);
        etAppendText.setText(config.appendText != null ? config.appendText : "喵");
        appendLayout.addView(etAppendText);
        funcLayout.addView(appendLayout, UiKit.fieldParams(this, 4, 8));

        swEmoticon = UiKit.addSwitch(funcLayout, this,
                "句末颜文字", "在消息末尾附加随机颜文字", config.enableRandomEmoticon);

        // 注：「加喵成功振动」开关只在「悬浮窗外观设置」页保留，功能页不再重复放置
        funcCard.addView(funcLayout);
        root.addView(funcCard);

        // ---- 文本替换规则 ----
        MaterialCardView ruleCard = UiKit.createCard(this);
        LinearLayout ruleLayout = UiKit.cardContent(this);
        ruleLayout.addView(UiKit.cardTitle(this, "文本替换规则"));
        ruleLayout.addView(UiKit.hintText(this,
                "每行一条，按顺序应用。格式：原词=替换词（也支持 ＝ 全角等号 / →）\n"
                        + "例：我=本喵 / 你＝主人 / 也支持数字等任意文本"));

        TextInputLayout rulesLayout = new TextInputLayout(this);
        rulesLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        etRules = new TextInputEditText(this);
        etRules.setInputType(131073); // textMultiLine
        etRules.setMinLines(4);
        etRules.setGravity(Gravity.TOP);
        etRules.setText(CatConfig.rulesToString(config.rules));
        rulesLayout.addView(etRules);
        ruleLayout.addView(rulesLayout);

        ruleCard.addView(ruleLayout);
        root.addView(ruleCard);

        // ---- 自定义颜文字 ----
        MaterialCardView emojiCard = UiKit.createCard(this);
        LinearLayout emojiLayout = UiKit.cardContent(this);
        emojiLayout.addView(UiKit.cardTitle(this, "自定义颜文字"));
        emojiLayout.addView(UiKit.hintText(this, "每行一个颜文字，留空则使用内置库（52 个）"));

        TextInputLayout emojiInputLayout = new TextInputLayout(this);
        emojiInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        etCustomEmoticons = new TextInputEditText(this);
        etCustomEmoticons.setInputType(131073);
        etCustomEmoticons.setMinLines(3);
        etCustomEmoticons.setGravity(Gravity.TOP);
        etCustomEmoticons.setText(joinLines(config.customEmoticons));
        emojiInputLayout.addView(etCustomEmoticons);
        emojiLayout.addView(emojiInputLayout);

        emojiCard.addView(emojiLayout);
        root.addView(emojiCard);

        // ---- 目标应用包名 ----
        MaterialCardView pkgCard = UiKit.createCard(this);
        LinearLayout pkgLayout = UiKit.cardContent(this);
        pkgLayout.addView(UiKit.cardTitle(this, "目标应用包名"));
        pkgLayout.addView(UiKit.hintText(this,
                "无障碍服务只在这些应用里生效。输入包名后点添加，长按列表项可删除。\n"
                        + "不要乱添加，谨免封号；部分软件可能无效。"));

        LinearLayout pkgInputRow = new LinearLayout(this);
        pkgInputRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgInputRow.setGravity(Gravity.CENTER_VERTICAL);

        TextInputLayout pkgInputLayout = new TextInputLayout(this);
        pkgInputLayout.setHint("例如 tv.danmaku.bili");
        pkgInputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        pkgInputLayout.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        etPackageName = new TextInputEditText(this);
        etPackageName.setSingleLine(true);
        pkgInputLayout.addView(etPackageName);
        pkgInputRow.addView(pkgInputLayout);

        MaterialButton btnAddPackage = UiKit.actionButton(this, "添加", UiKit.STYLE_TONAL);
        LinearLayout.LayoutParams btnPkgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnPkgLp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        btnAddPackage.setLayoutParams(btnPkgLp);
        btnAddPackage.setOnClickListener(v -> addPackage());
        pkgInputRow.addView(btnAddPackage);

        MaterialButton btnPickApp = UiKit.actionButton(this, "选择", UiKit.STYLE_OUTLINED);
        LinearLayout.LayoutParams btnPickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnPickLp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        btnPickApp.setLayoutParams(btnPickLp);
        btnPickApp.setOnClickListener(v ->
                AppPickerDialog.show(this, config.targetPackages,
                        (pkg, name) -> addPackageInternal(pkg, true)));
        pkgInputRow.addView(btnPickApp);

        pkgLayout.addView(pkgInputRow);

        packageListContainer = new LinearLayout(this);
        packageListContainer.setOrientation(LinearLayout.VERTICAL);
        packageListContainer.setPadding(0, UiKit.dp(this, 4), 0, 0);
        pkgLayout.addView(packageListContainer);

        pkgCard.addView(pkgLayout);
        root.addView(pkgCard);

        // ---- 底部操作 ----
        MaterialButton testBtn = UiKit.actionButton(this, "测试当前配置", UiKit.STYLE_TONAL);
        testBtn.setLayoutParams(UiKit.fullWidthParams(this, UiKit.dp(this, 8)));
        testBtn.setOnClickListener(v -> showTestDialog());
        root.addView(testBtn);

        MaterialButton saveBtn = UiKit.actionButton(this, "保存设置", UiKit.STYLE_FILLED);
        saveBtn.setLayoutParams(UiKit.fullWidthParams(this, UiKit.dp(this, 8)));
        saveBtn.setOnClickListener(v -> saveConfig());
        root.addView(saveBtn);

        sv.addView(root);
        return sv;
    }

    private ScrollView buildPermissionPage() {
        ScrollView sv = pageScroll();
        LinearLayout root = pageContent();

        root.addView(pageTitle("OneTapMiao", "权限授权 · 全部开启后即可使用"));

        // ---- 忽略电池优化（卡片式）----
        batteryDot = UiKit.dot(this, 0xFF9E9E9E);
        batteryStatus = new TextView(this);
        batteryButton = UiKit.actionButton(this, "前往开启", UiKit.STYLE_FILLED);
        root.addView(permCard(batteryDot, batteryStatus,
                "允许后台持续运行。被系统杀掉的话，改写到一半就断了",
                batteryButton, v -> requestIgnoreBatteryOptimization()));

        // ---- 一键加喵（微信）方案说明 ----
        MaterialCardView miaoCard = UiKit.createCard(this);
        LinearLayout miaoLayout = UiKit.cardContent(this);
        miaoLayout.addView(UiKit.cardTitle(this, "一键加喵（微信可用）"));
        miaoLayout.addView(UiKit.hintText(this,
                "微信把无障碍节点树掏空了，读不到输入框内容，所以无法全自动。\n"
                        + "用法：在微信里打好字 → 点悬浮窗的「喵」或通知栏「加喵」→ 自动加喵。\n"
                        + "需要 Shizuku 授权，且要先在系统的输入法管理里启用本应用的隐形输入法。"));
        miaoCard.addView(miaoLayout);
        root.addView(miaoCard);

        // ---- 隐形输入法（卡片式）----
        imeDot = UiKit.dot(this, 0xFF9E9E9E);
        imeStatus = new TextView(this);
        imeButton = UiKit.actionButton(this, "前往开启", UiKit.STYLE_FILLED);
        root.addView(permCard(imeDot, imeStatus,
                "微信读写走输入法通道，未启用则一键加喵不可用",
                imeButton, v -> MiaoInjector.openImeSettings(this)));

        // ---- 通知权限（卡片式）----
        notifyDot = UiKit.dot(this, 0xFF9E9E9E);
        notifyStatus = new TextView(this);
        notifyButton = UiKit.actionButton(this, "前往开启", UiKit.STYLE_FILLED);
        root.addView(permCard(notifyDot, notifyStatus,
                "加喵失败且悬浮窗未开启时，用通知告知失败原因（Android 13+ 需授权）",
                notifyButton, v -> requestNotificationPermission()));

        // ---- 悬浮窗权限（卡片式）----
        overlayDot = UiKit.dot(this, 0xFF9E9E9E);
        overlayStatus = new TextView(this);
        overlayButton = UiKit.actionButton(this, "前往开启", UiKit.STYLE_FILLED);
        root.addView(permCard(overlayDot, overlayStatus,
                "悬浮窗「喵」按钮和结果气泡的显示依赖此权限",
                overlayButton, v -> requestOverlayPermission()));

        // ---- Shizuku 直写兜底 ----
        MaterialCardView shizukuCard = UiKit.createCard(this);
        LinearLayout shizukuLayout = UiKit.cardContent(this);
        shizukuLayout.addView(UiKit.cardTitle(this, "Shizuku服务"));
        shizukuLayout.addView(UiKit.hintText(this,
                "当部分应用（如微信）拒绝无障碍改写时，改用 Shizuku 切换输入法完成写入。"
                        + "全程无弹窗、不动剪贴板。需已安装并启动 Shizuku。"));

        shizukuStatus = new TextView(this);
        shizukuStatus.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium);
        shizukuStatus.setTextColor(UiKit.colorAttr(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        shizukuStatus.setGravity(Gravity.CENTER);
        shizukuStatus.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 8));
        shizukuLayout.addView(shizukuStatus);

        shizukuAuthButton = UiKit.actionButton(this, "前往授权Shizuku服务", UiKit.STYLE_FILLED);
        LinearLayout.LayoutParams shzBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        shzBtnLp.setMargins(0, 0, 0, UiKit.dp(this, 4));
        shizukuAuthButton.setLayoutParams(shzBtnLp);
        shizukuAuthButton.setOnClickListener(v -> requestShizukuPermission());
        shizukuLayout.addView(shizukuAuthButton);

        shizukuCard.addView(shizukuLayout);
        root.addView(shizukuCard);

        sv.addView(root);
        return sv;
    }

    // ==================================================================
    // 页面 / 导航小部件
    // ==================================================================

    /** 页面容器：每个页面是一个竖向 ScrollView，宽 = 屏幕宽 */
    private ScrollView pageScroll() {
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                screenWidth, ViewGroup.LayoutParams.MATCH_PARENT));
        return sv;
    }

    private LinearLayout pageContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 32));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private LinearLayout pageTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceHeadlineMedium);
        tv.setTextColor(UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, UiKit.dp(this, 20), 0, UiKit.dp(this, 4));
        box.addView(tv);

        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium);
            sub.setTextColor(UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnSurfaceVariant));
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, 0, 0, UiKit.dp(this, 20));
            box.addView(sub);
        }
        return box;
    }

    /**
     * 卡片式权限项（与状态页无障碍卡片完全同款）：
     * 状态点 + 居中「名称：状态」+ 说明文字 + 整宽按钮。
     * 四个权限项共用它，保证样式一字不差。
     */
    private MaterialCardView permCard(View dot, TextView status, String desc,
                                      MaterialButton button, View.OnClickListener listener) {
        MaterialCardView card = UiKit.createCard(this);
        LinearLayout layout = UiKit.cardContent(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        row.addView(dot);

        status.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleMedium);
        status.setGravity(Gravity.CENTER);
        row.addView(status);
        layout.addView(row);

        layout.addView(UiKit.hintText(this, desc));

        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        button.setOnClickListener(listener);
        layout.addView(button);

        card.addView(layout);
        return card;
    }

    /** 底部固定导航：状态 / 功能 / 权限 */
    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6),
                UiKit.dp(this, 8), UiKit.dp(this, 10));

        int primary = UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary);
        int onPrimary = UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnPrimary);
        int surface = UiKit.colorAttr(this, com.google.android.material.R.attr.colorSurfaceVariant);
        int onSurface = UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnSurfaceVariant);

        String[] labels = {"状态", "功能", "权限"};
        final int[] pages = {PAGE_STATUS, PAGE_FEATURE, PAGE_PERMISSION};
        for (int i = 0; i < 3; i++) {
            MaterialButton btn = new MaterialButton(this, null, R.attr.catTonalButtonStyle);
            btn.setText(labels[i]);
            btn.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), 0);
            btn.setCornerRadius(UiKit.dp(this, 20));
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            btn.setAllCaps(false);
            btn.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            btn.setBackgroundTintList(ColorStateList.valueOf(surface));
            btn.setTextColor(onSurface);
            final int idx = i;
            btn.setOnClickListener(v -> {
                hsv.smoothScrollTo(pages[idx] * screenWidth, 0);
                updateNavHighlight(pages[idx]);
            });
            navButtons[i] = btn;
            nav.addView(btn);
        }
        return nav;
    }

    /** 导航高亮：当前页主题色，其它页灰色 */
    private void updateNavHighlight(int page) {
        int primary = UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary);
        int onPrimary = UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnPrimary);
        int surface = UiKit.colorAttr(this, com.google.android.material.R.attr.colorSurfaceVariant);
        int onSurface = UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < navButtons.length; i++) {
            if (navButtons[i] == null) {
                continue;
            }
            boolean active = (i == page);
            navButtons[i].setBackgroundTintList(ColorStateList.valueOf(
                    active ? primary : surface));
            navButtons[i].setTextColor(active ? onPrimary : onSurface);
        }
    }

    // ==================================================================
    // 处理模式（分段按钮）
    // ==================================================================

    private void setMode(boolean realtime) {
        if (modeRealtime == realtime) {
            return;
        }
        modeRealtime = realtime;
        applyModeSelection();
    }

    private void applyModeSelection() {
        if (btnModePunctuation == null || btnModeRealtime == null) {
            return;
        }
        int primary = UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary);
        int onPrimary = UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnPrimary);
        int surface = UiKit.colorAttr(this, com.google.android.material.R.attr.colorSurfaceVariant);
        int onSurface = UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnSurfaceVariant);

        btnModePunctuation.setBackgroundTintList(
                ColorStateList.valueOf(modeRealtime ? surface : primary));
        btnModePunctuation.setTextColor(modeRealtime ? onSurface : onPrimary);

        btnModeRealtime.setBackgroundTintList(
                ColorStateList.valueOf(modeRealtime ? primary : surface));
        btnModeRealtime.setTextColor(modeRealtime ? onPrimary : onSurface);
    }

    // ==================================================================
    // 保存 / 测试
    // ==================================================================

    public void saveConfig() {
        try {
            // 不能在这里重新 load：会冲掉调用方刚写进内存的改动。
            // 例：addPackageInternal 刚 add 完包名就调 saveConfig，一 load 新包就丢了，
            // 结果「已添加」提示有了、列表却永远不变。
            // config 已在 onCreate / onResume 同步为磁盘最新值，这里直接用。
            if (config == null) {
                config = CatConfig.load(this);
            }
            config.enableAppend = swAppend == null || swAppend.isChecked();
            String append = etAppendText == null ? "" : etAppendText.getText().toString().trim();
            config.appendText = append.isEmpty() ? "喵" : append;
            config.enableRandomEmoticon = swEmoticon == null || swEmoticon.isChecked();
            config.processingMode = modeRealtime
                    ? CatConfig.MODE_REALTIME
                    : CatConfig.MODE_PUNCTUATION;
            // enableVibrate 不在此处改写：振动开关已移到「悬浮窗外观设置」页，
            // 那里保存的值以磁盘为准，功能页保存时保持原样，避免被覆盖成默认值

            ArrayList<CatConfig.Rule> rules = new ArrayList<>();
            String rulesText = etRules == null ? "" : etRules.getText().toString();
            for (String line : rulesText.split("\n")) {
                CatConfig.Rule r = CatConfig.parseRule(line);
                if (r != null) {
                    rules.add(r);
                }
            }
            config.rules = rules;

            ArrayList<String> list = new ArrayList<>();
            String customText = etCustomEmoticons == null
                    ? "" : etCustomEmoticons.getText().toString().trim();
            if (!customText.isEmpty()) {
                for (String raw : customText.split("\n")) {
                    String t = raw.trim();
                    if (!t.isEmpty()) {
                        list.add(t);
                    }
                }
            }
            config.customEmoticons = list.toArray(new String[0]);
            config.save(this);
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void showTestDialog() {
        try {
            saveConfig();
            CatConfig testCfg = CatConfig.load(this);
            String sample = "今天我很好，你准备好了吗？我们去公园玩吧";
            String processed = TextProcessor.process(sample, testCfg);
            String msg = "断句追加：" + yn(testCfg.enableAppend)
                    + "（" + (testCfg.appendText == null ? "" : testCfg.appendText) + "）"
                    + "\n句末颜文字：" + yn(testCfg.enableRandomEmoticon)
                    + "\n替换规则：" + testCfg.rules.size() + " 条"
                    + "\n自定义颜文字：" + (testCfg.customEmoticons.length > 0
                        ? testCfg.customEmoticons.length + "个" : "使用内置")
                    + "\nShizuku 兜底：" + yn(testCfg.shizukuFallbackEnabled)
                    + "（" + (ShizukuInjector.isReady() ? "已就绪" : "未就绪/未授权") + "）"
                    + "\n\n原始：\n" + sample
                    + "\n\n处理后：\n" + processed;
            showInfoSheet("配置预览", msg, false, null, null);
        } catch (Exception e) {
            Toast.makeText(this, "测试失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String yn(boolean b) {
        return b ? "开" : "关";
    }

    // ==================================================================
    // 目标应用包名列表
    // ==================================================================

    private void updatePackageListUI() {
        if (packageListContainer == null || config == null) {
            return;
        }
        packageListContainer.removeAllViews();
        for (final String pkg : config.targetPackages) {
            TextView tv = new TextView(this);
            tv.setText(pkg);
            tv.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium);
            tv.setTextColor(UiKit.colorAttr(this, com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12),
                    UiKit.dp(this, 12), UiKit.dp(this, 12));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
            tv.setLayoutParams(lp);
            tv.setBackgroundResource(R.drawable.package_item_bg);
            tv.setOnLongClickListener(v -> {
                // 用 MaterialAlertDialogBuilder（而非原生 AlertDialog），保证弹窗与
                // 全应用的 Material 3 风格一致（圆角、字重、按钮排版、颜色体系）
                androidx.appcompat.app.AlertDialog dialog =
                        new MaterialAlertDialogBuilder(MainActivity.this)
                                .setTitle("删除包名")
                                .setMessage("确定要移除 " + pkg + " 吗？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除",
                                        (d, which) -> removePackage(pkg))
                                .show();
                // 破坏性操作按 Material 3 规范用 error 色强调
                android.widget.Button positive =
                        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                if (positive != null) {
                    positive.setTextColor(UiKit.colorAttr(MainActivity.this,
                            com.google.android.material.R.attr.colorError));
                }
                return true;
            });
            packageListContainer.addView(tv);
        }
    }

    private void addPackage() {
        String pkg = etPackageName == null ? "" : etPackageName.getText().toString().trim();
        if (etPackageName != null) {
            etPackageName.setText("");
        }
        addPackageInternal(pkg, false);
    }

    private void addPackageInternal(String pkg, boolean fromPicker) {
        if (config == null) {
            return;
        }
        if (pkg == null || pkg.isEmpty()) {
            Toast.makeText(this, fromPicker ? "未选择应用" : "请输入包名",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!config.targetPackages.contains(pkg)) {
            config.targetPackages.add(pkg);
            saveConfig();
            updatePackageListUI();
            Toast.makeText(this, "已添加：" + pkg, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "该包名已在列表中", Toast.LENGTH_SHORT).show();
        }
    }

    private void removePackage(String pkg) {
        config.targetPackages.remove(pkg);
        saveConfig();
        updatePackageListUI();
        Toast.makeText(this, "已移除：" + pkg, Toast.LENGTH_SHORT).show();
    }

    // ==================================================================
    // 无障碍服务状态
    // ==================================================================

    private void updateServiceStatus() {
        if (statusText == null || toggleButton == null) {
            return;
        }
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            statusText.setText("无障碍服务：已开启");
            statusText.setTextColor(UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary));
            UiKit.setDotColor(serviceDot, UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary));
            toggleButton.setText("服务已开启");
            toggleButton.setEnabled(false);
            toggleButton.setAlpha(0.5f);
        } else {
            statusText.setText("无障碍服务：未开启");
            statusText.setTextColor(UiKit.colorAttr(this, com.google.android.material.R.attr.colorError));
            UiKit.setDotColor(serviceDot, UiKit.colorAttr(this, com.google.android.material.R.attr.colorError));
            toggleButton.setText("前往开启无障碍服务");
            toggleButton.setEnabled(true);
            toggleButton.setAlpha(1.0f);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService("accessibility");
            if (am == null) {
                return false;
            }
            List<android.accessibilityservice.AccessibilityServiceInfo> services =
                    am.getEnabledAccessibilityServiceList(-1);
            for (android.accessibilityservice.AccessibilityServiceInfo info : services) {
                if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                        && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    public void openAccessibilitySettings() {
        try {
            Intent intent = new Intent("android.settings.ACCESSIBILITY_SETTINGS");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================================================================
    // 悬浮窗
    // ==================================================================

    private void toggleFloatingWindow() {
        if (isFloatingWindowShown) {
            stopService(new Intent(this, FloatingWindowService.class));
            isFloatingWindowShown = false;
            floatingWindowButton.setText("开启悬浮窗");
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        startFloatingService();
        isFloatingWindowShown = true;
        floatingWindowButton.setText("关闭悬浮窗");
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
    }

    /**
     * 启动悬浮窗服务：Android 8+ 后台启动必须用 startForegroundService，
     * 用普通 startService 会抛 IllegalStateException（这是已知的闪退来源）。
     */
    private void startFloatingService() {
        Intent it = new Intent(this, FloatingWindowService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it);
            } else {
                startService(it);
            }
        } catch (Throwable t) {
            AppLog.add("Main", "启动悬浮窗服务失败", t);
            Toast.makeText(this, "启动失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            updateOverlayUi();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "未授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                return;
            }
            startFloatingService();
            isFloatingWindowShown = true;
            floatingWindowButton.setText("关闭悬浮窗");
        }
    }

    // ==================================================================
    // 悬浮窗权限
    // ==================================================================

    private void updateOverlayUi() {
        if (overlayStatus == null || overlayButton == null || overlayDot == null) {
            return;
        }
        boolean ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        int color = UiKit.colorAttr(this, ok
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorError);
        overlayStatus.setText(ok ? "悬浮窗权限：已授权" : "悬浮窗权限：未授权");
        overlayStatus.setTextColor(color);
        UiKit.setDotColor(overlayDot, color);
        overlayButton.setText(ok ? "已开启" : "前往开启");
        overlayButton.setEnabled(!ok);
        overlayButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_OVERLAY);
                Toast.makeText(this, "请允许「显示在其他应用上层」", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show();
            }
        } else {
            updateOverlayUi();
        }
    }

    // 注意：状态页 Shizuku 的更新统一走 updateShizukuUi()（下方「Shizuku 状态与授权」区），
    // 这里不再保留第二个更新入口——曾经因为两个方法先后执行，旧文案把新文案覆盖掉了。

    // ==================================================================
    // 忽略电池优化
    // ==================================================================

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Android 6.0 以下无需此设置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isIgnoringBatteryOptimizations()) {
            updateBatteryUi();
            return;
        }
        try {
            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(Uri.parse("package:" + getPackageName()));
            startActivity(it);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent it = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(it);
            Toast.makeText(this, "请在列表中找到本应用，设为「允许」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开电池优化设置页", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBatteryUi() {
        if (batteryStatus == null || batteryButton == null || batteryDot == null) {
            return;
        }
        boolean ok = isIgnoringBatteryOptimizations();
        int color = UiKit.colorAttr(this, ok
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorError);
        batteryStatus.setText(ok ? "忽略电池优化：已忽略" : "忽略电池优化：未忽略");
        batteryStatus.setTextColor(color);
        UiKit.setDotColor(batteryDot, color);
        batteryButton.setText(ok ? "已开启" : "前往开启");
        batteryButton.setEnabled(!ok);
        batteryButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    // ==================================================================
    // 通知权限
    // ==================================================================

    private boolean notificationsEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                return nm == null || nm.areNotificationsEnabled();
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private void updateNotifyUi() {
        if (notifyStatus == null || notifyButton == null || notifyDot == null) {
            return;
        }
        boolean ok = notificationsEnabled();
        int color = UiKit.colorAttr(this, ok
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorError);
        notifyStatus.setText(ok ? "通知权限：已授权" : "通知权限：未授权");
        notifyStatus.setTextColor(color);
        UiKit.setDotColor(notifyDot, color);
        notifyButton.setText(ok ? "已开启" : "前往开启");
        notifyButton.setEnabled(!ok);
        notifyButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notifyPermissionDeniedOnce) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2002);
                return;
            } catch (Throwable ignored) {
            }
        }
        openNotificationSettings();
    }

    private void openNotificationSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开通知设置，请到系统设置里手动开启", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == 2002) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateNotifyUi();
            } else {
                notifyPermissionDeniedOnce = true;
                Toast.makeText(this, "已拒绝。下次点「去开启」会直接跳系统设置", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================================================================
    // 隐形输入法
    // ==================================================================

    private void updateImeUi() {
        if (imeStatus == null || imeButton == null || imeDot == null) {
            return;
        }
        boolean ok;
        try {
            ok = MiaoInjector.isImeEnabled(this);
        } catch (Throwable t) {
            ok = false;
        }
        int color = UiKit.colorAttr(this, ok
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorError);
        imeStatus.setText(ok ? "隐形输入法：已启用" : "隐形输入法：未启用");
        imeStatus.setTextColor(color);
        UiKit.setDotColor(imeDot, color);
        imeButton.setText(ok ? "已开启" : "前往开启");
        imeButton.setEnabled(!ok);
        imeButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    // ==================================================================
    // Shizuku 状态与授权
    // ==================================================================

    private void updateShizukuUi() {
        if (shizukuStatus == null || shizukuAuthButton == null
                || shizukuShortStatus == null || shizukuShortButton == null
                || shizukuShortDot == null) {
            return;
        }
        boolean binderAlive = false;
        boolean granted = false;
        try {
            binderAlive = Shizuku.pingBinder();
            if (binderAlive && !Shizuku.isPreV11()) {
                granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable t) {
            binderAlive = false;
            granted = false;
        }

        int okColor = UiKit.colorAttr(this, com.google.android.material.R.attr.colorPrimary);
        int errColor = UiKit.colorAttr(this, com.google.android.material.R.attr.colorError);

        // ---- 状态页：与无障碍完全同款的两态（已开启 / 未开启）----
        if (granted) {
            shizukuShortStatus.setText("Shizuku服务：已开启");
            shizukuShortStatus.setTextColor(okColor);
            UiKit.setDotColor(shizukuShortDot, okColor);
            shizukuShortButton.setText("Shizuku服务已开启");
            shizukuShortButton.setEnabled(false);
            shizukuShortButton.setAlpha(0.5f);
        } else {
            shizukuShortStatus.setText("Shizuku服务：未开启");
            shizukuShortStatus.setTextColor(errColor);
            UiKit.setDotColor(shizukuShortDot, errColor);
            shizukuShortButton.setText("前往授权Shizuku服务");
            shizukuShortButton.setEnabled(true);
            shizukuShortButton.setAlpha(1.0f);
        }

        // ---- 权限页：措辞与状态页完全一致，括号内补充细节 ----
        if (!binderAlive) {
            shizukuStatus.setText("Shizuku服务：未开启\n（未检测到运行，可选：先安装并启动 Shizuku）");
            shizukuAuthButton.setText("未检测到Shizuku服务");
            shizukuAuthButton.setEnabled(false);
            shizukuAuthButton.setAlpha(0.5f);
        } else if (!granted) {
            shizukuStatus.setText("Shizuku服务：未开启\n（已运行，等待授权）");
            shizukuAuthButton.setText("前往授权Shizuku服务");
            shizukuAuthButton.setEnabled(true);
            shizukuAuthButton.setAlpha(1.0f);
        } else {
            shizukuStatus.setText("Shizuku服务：已开启\n（无障碍改写失败时将自动直写）");
            shizukuAuthButton.setText("Shizuku服务已开启");
            shizukuAuthButton.setEnabled(false);
            shizukuAuthButton.setAlpha(0.5f);
        }
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "未检测到 Shizuku，请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
                // 滑到权限页：那里有完整的安装与授权说明
                hsv.smoothScrollTo(PAGE_PERMISSION * screenWidth, 0);
                updateNavHighlight(PAGE_PERMISSION);
                return;
            }
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过旧，请升级到 v11 及以上", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                updateShizukuUi();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "请在弹窗中允许本应用使用 Shizuku", Toast.LENGTH_SHORT).show();
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku 调用失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================================================================
    // 诊断日志 / 信息面板
    // ==================================================================

    private void showLogDialog() {
        String log = AppLog.dump();

        String body = "构建版本 " + BUILD_STAMP + "\n"
                + AppLog.selfCheck(this)
                + "===== 运行日志（新的在最下面）=====\n"
                + (log.isEmpty()
                    ? "（空）\n\n若自检全部正常但仍无日志，说明无障碍服务没收到任何事件。\n"
                      + "按顺序检查：\n"
                      + "1. 系统设置 → 无障碍 → 本服务已开启\n"
                      + "2. 本 App 内总开关已打开\n"
                      + "3. 目标应用列表里勾选了 QQ / 微信\n"
                      + "4. 回到 QQ 输入框打字（要打出句号/逗号/空格/换行才会触发）"
                    : log);

        showInfoSheet("诊断日志", body, true, "清空", () -> {
            AppLog.clear();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
    }

    private void showInfoSheet(String title, String body, boolean monospace,
                               String extraLabel, Runnable extraClick) {
        float density = getResources().getDisplayMetrics().density;
        int pad = UiKit.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleMedium);
        titleTv.setTextColor(UiKit.colorAttr(this,
                com.google.android.material.R.attr.colorOnSurface));
        root.addView(titleTv);

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextIsSelectable(true);
        if (monospace) {
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(10.5f);
        } else {
            tv.setTextSize(14f);
        }
        tv.setLineSpacing(0f, 1.15f);
        tv.setTextColor(UiKit.colorAttr(this,
                com.google.android.material.R.attr.colorOnSurface));
        tv.setMaxHeight((int) (380 * density));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = UiKit.dp(this, 12);
        root.addView(sv, svLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, UiKit.dp(this, 10), 0, 0);

        BottomSheetDialog sheet = new BottomSheetDialog(this);

        if (extraLabel != null) {
            MaterialButton extraBtn = UiKit.actionButton(this, extraLabel, UiKit.STYLE_TONAL);
            LinearLayout.LayoutParams extraLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            extraBtn.setLayoutParams(extraLp);
            extraBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (extraClick != null) {
                    extraClick.run();
                }
            });
            btnRow.addView(extraBtn);
        }

        MaterialButton copyBtn = UiKit.actionButton(this, "复制全部", UiKit.STYLE_TONAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.leftMargin = UiKit.dp(this, 8);
        copyBtn.setLayoutParams(copyLp);
        copyBtn.setOnClickListener(v -> copyTextToClipboard(body));
        btnRow.addView(copyBtn);

        MaterialButton closeBtn = UiKit.actionButton(this, "关闭", UiKit.STYLE_OUTLINED);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = UiKit.dp(this, 8);
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setOnClickListener(v -> sheet.dismiss());
        btnRow.addView(closeBtn);

        root.addView(btnRow);
        sheet.setContentView(root);
        sheet.show();
    }

    private void copyTextToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("OneTapMiao", text));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String joinLines(String[] arr) {
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (String s : arr) {
                if (s == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateBatteryUi();
        updateNotifyUi();
        updateImeUi();
        updateShizukuUi();
        updateOverlayUi();
        // 悬浮窗按钮文案与实际状态对齐（可能从通知栏/其它入口间接改变）
        isFloatingWindowShown = FloatingWindowService.isShowing();
        floatingWindowButton.setText(isFloatingWindowShown ? "关闭悬浮窗" : "开启悬浮窗");
        // 功能页开关与最新配置同步
        try {
            CatConfig c = CatConfig.load(this);
            config = c;
            if (swAppend != null) {
                swAppend.setChecked(c.enableAppend);
            }
            if (etAppendText != null) {
                etAppendText.setText(c.appendText != null ? c.appendText : "喵");
            }
            if (swEmoticon != null) {
                swEmoticon.setChecked(c.enableRandomEmoticon);
            }
            modeRealtime = CatConfig.MODE_REALTIME.equals(c.processingMode);
            applyModeSelection();
            if (etRules != null) {
                etRules.setText(CatConfig.rulesToString(c.rules));
            }
            if (etCustomEmoticons != null) {
                etCustomEmoticons.setText(joinLines(c.customEmoticons));
            }
            updatePackageListUI();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
            Shizuku.addBinderDeadListener(shizukuDeadListener);
        } catch (Throwable t) {
            AppLog.add("Main", "Shizuku 监听注册失败", t);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
            Shizuku.removeBinderReceivedListener(shizukuBinderListener);
            Shizuku.removeBinderDeadListener(shizukuDeadListener);
        } catch (Throwable ignored) {
        }
    }
}
