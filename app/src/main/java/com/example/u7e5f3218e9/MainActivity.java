package com.example.u7e5f3218e9;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 443;
    private static final int REQ_OVERLAY = 100;

    private MaterialSwitch swAppend;
    private MaterialSwitch swEmoticon;
    private MaterialSwitch swShizuku;
    private CatConfig config;
    private TextInputEditText etAppendText;
    private TextInputEditText etCustomEmoticons;
    private TextInputEditText etRules;
    private TextView statusText;
    private View serviceDot;
    private MaterialButton toggleButton;
    private MaterialButton floatingWindowButton;
    private TextInputEditText etPackageName;
    private LinearLayout packageListContainer;
    private TextView shizukuStatus;
    private MaterialButton shizukuAuthButton;
    private boolean isFloatingWindowShown = false;

    // 处理模式：用按钮组维护一个 boolean，取代原来两个 CheckBox 互相 setChecked(false)
    private MaterialButton btnModePunctuation;
    private MaterialButton btnModeRealtime;
    private boolean modeRealtime = false;

    // 运行保障状态
    private TextView batteryStatus;
    private MaterialButton batteryButton;
    private TextView imeStatus;
    private MaterialButton imeButton;
    private TextView notifyStatus;
    private MaterialButton notifyButton;
    /** 用户在权限弹窗里拒绝过一次后，再点「去开启」就直接跳系统设置（继续弹会被系统拒） */
    private boolean notifyPermissionDeniedOnce = false;

    // Shizuku 授权结果监听
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
    // Shizuku 服务上线/掉线监听
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

        // 初始化诊断日志（必须尽早，否则服务写日志时文件还没准备好）
        AppLog.init(this);

        try {
            this.config = CatConfig.load(this);
        } catch (Exception e) {
            this.config = new CatConfig();
        }

        buildUI();

        // 注册 Shizuku 监听（Sticky 版本：若 binder 已存在会立刻回调一次）
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
            Shizuku.addBinderDeadListener(shizukuDeadListener);
        } catch (Throwable ignored) {
        }
        updateShizukuUi();
        updateBatteryUi();
        updateImeUi();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
            Shizuku.removeBinderReceivedListener(shizukuBinderListener);
            Shizuku.removeBinderDeadListener(shizukuDeadListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ==================================================================
    // 界面构建
    // ==================================================================

    private void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 顶部标题 ----------
        TextView title = new TextView(this);
        title.setText("OneTapMiao");
        title.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceHeadlineMedium);
        title.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("控制面板 · 所有规则均可自定义");
        subtitle.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium);
        subtitle.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        // ---------- 1. 无障碍服务状态 ----------
        MaterialCardView statusCard = createCard();
        LinearLayout statusLayout = cardContent();

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setPadding(0, dp(4), 0, dp(8));

        serviceDot = dot(0xFF9E9E9E);
        statusRow.addView(serviceDot);

        statusText = new TextView(this);
        statusText.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceTitleMedium);
        statusText.setGravity(Gravity.CENTER);
        statusRow.addView(statusText);
        statusLayout.addView(statusRow);

        toggleButton = actionButton("前往开启无障碍服务", STYLE_FILLED);
        toggleButton.setOnClickListener(v -> openAccessibilitySettings());
        statusLayout.addView(toggleButton);

        statusCard.addView(statusLayout);
        root.addView(statusCard);

        // ---------- 2. 运行保障（电池优化） ----------
        MaterialCardView guardCard = createCard();
        LinearLayout guardLayout = cardContent();
        guardLayout.addView(cardTitle("运行保障"));
        guardLayout.addView(hintText("后台被系统杀掉的话，改写到一半就断了。建议允许本应用后台运行。"));

        LinearLayout batteryRow = buildStatusRow(
                "忽略电池优化",
                "允许后台持续运行，避免服务被系统回收",
                batteryStatus = new TextView(this),
                batteryButton = actionButton("去设置", STYLE_TONAL));
        batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimization());
        guardLayout.addView(batteryRow);

        guardCard.addView(guardLayout);
        root.addView(guardCard);

        // ---------- 3. 一键加喵（微信） ----------
        MaterialCardView miaoCard = createCard();
        LinearLayout miaoLayout = cardContent();
        miaoLayout.addView(cardTitle("一键加喵（微信可用）"));
        miaoLayout.addView(hintText(
                "微信把无障碍节点树掏空了，读不到输入框内容，所以无法全自动。\n"
                        + "用法：在微信里打好字 → 点悬浮窗的「喵」或通知栏「加喵」→ 自动加喵。\n"
                        + "需要 Shizuku 授权，且要先在系统的输入法管理里启用本应用的隐形输入法。"));

        LinearLayout imeRow = buildStatusRow(
                "隐形输入法",
                "微信读写走输入法通道，未启用则一键加喵不可用",
                imeStatus = new TextView(this),
                imeButton = actionButton("前往启用", STYLE_TONAL));
        imeButton.setOnClickListener(v -> MiaoInjector.openImeSettings(this));
        miaoLayout.addView(imeRow);

        // 通知权限：加喵失败且悬浮窗没开时，通知栏是最后的告知渠道。
        // Android 13（targetSdk 33）起 POST_NOTIFICATIONS 变成运行时权限，
        // 不请求的话通知一条都发不出来——失败原因用户就永远看不见了。
        LinearLayout notifyRow = buildStatusRow(
                "通知权限",
                "加喵失败且悬浮窗未开启时，用通知告知失败原因（Android 13+ 需授权）",
                notifyStatus = new TextView(this),
                notifyButton = actionButton("去开启", STYLE_TONAL));
        notifyButton.setOnClickListener(v -> requestNotificationPermission());
        miaoLayout.addView(notifyRow);

        miaoCard.addView(miaoLayout);
        root.addView(miaoCard);

        // ---------- 4. 处理模式 ----------
        MaterialCardView modeCard = createCard();
        LinearLayout modeLayout = cardContent();
        modeLayout.addView(cardTitle("处理模式"));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        modeRow.setLayoutParams(modeRowLp);

        btnModePunctuation = new MaterialButton(this);
        btnModePunctuation.setText("标点触发");
        btnModePunctuation.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        btnModePunctuation.setMinWidth(0);
        btnModePunctuation.setMinimumWidth(0);
        btnModePunctuation.setPadding(dp(8), 0, dp(8), 0);
        btnModePunctuation.setCornerRadius(dp(20));
        btnModePunctuation.setInsetTop(0);
        btnModePunctuation.setInsetBottom(0);
        btnModePunctuation.setOnClickListener(v -> setMode(false));
        modeRow.addView(btnModePunctuation, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT));
        modeRow.addView(gap);

        btnModeRealtime = new MaterialButton(this);
        btnModeRealtime.setText("实时处理");
        btnModeRealtime.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        btnModeRealtime.setMinWidth(0);
        btnModeRealtime.setMinimumWidth(0);
        btnModeRealtime.setPadding(dp(8), 0, dp(8), 0);
        btnModeRealtime.setCornerRadius(dp(20));
        btnModeRealtime.setInsetTop(0);
        btnModeRealtime.setInsetBottom(0);
        btnModeRealtime.setOnClickListener(v -> setMode(true));
        modeRow.addView(btnModeRealtime, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        modeLayout.addView(modeRow);

        modeRealtime = CatConfig.MODE_REALTIME.equals(config.processingMode);
        applyModeSelection();

        modeLayout.addView(hintText(
                "标点触发：打字时只在标点处立即处理（推荐，稳定省电）\n"
                        + "实时处理：每输入一个字立即处理（更快，但更容易和输入法打架）"));

        modeCard.addView(modeLayout);
        root.addView(modeCard);

        // ---------- 5. 功能开关 ----------
        MaterialCardView funcCard = createCard();
        LinearLayout funcLayout = cardContent();
        funcLayout.addView(cardTitle("功能开关"));

        swAppend = addSwitch(funcLayout, "断句追加", "在句号、叹号等标点分句后追加文本", config.enableAppend);

        TextInputLayout appendLayout = outlinedField("追加内容（默认：喵）");
        etAppendText = new TextInputEditText(this);
        etAppendText.setText(config.appendText != null ? config.appendText : "喵");
        appendLayout.addView(etAppendText);
        funcLayout.addView(appendLayout, fieldParams(dp(4), dp(8)));

        swEmoticon = addSwitch(funcLayout, "句末颜文字", "在消息末尾附加随机颜文字", config.enableRandomEmoticon);

        funcCard.addView(funcLayout);
        root.addView(funcCard);

        // ---------- 6. 文本替换规则 ----------
        MaterialCardView ruleCard = createCard();
        LinearLayout ruleLayout = cardContent();
        ruleLayout.addView(cardTitle("文本替换规则"));
        ruleLayout.addView(hintText(
                "每行一条，按顺序应用。格式：原词=替换词（也支持 ＝ 全角等号 / →）\n"
                        + "例：我=本喵 / 你＝主人 / 也支持数字等任意文本"));

        TextInputLayout rulesLayout = outlinedField(null);
        etRules = new TextInputEditText(this);
        etRules.setInputType(131073); // textMultiLine
        etRules.setMinLines(4);
        etRules.setGravity(Gravity.TOP);
        etRules.setText(CatConfig.rulesToString(config.rules));
        rulesLayout.addView(etRules);
        ruleLayout.addView(rulesLayout);

        ruleCard.addView(ruleLayout);
        root.addView(ruleCard);

        // ---------- 7. 自定义颜文字 ----------
        MaterialCardView emojiCard = createCard();
        LinearLayout emojiLayout = cardContent();
        emojiLayout.addView(cardTitle("自定义颜文字"));
        emojiLayout.addView(hintText("每行一个颜文字，留空则使用内置库（52 个）"));

        TextInputLayout emojiInputLayout = outlinedField(null);
        etCustomEmoticons = new TextInputEditText(this);
        etCustomEmoticons.setInputType(131073);
        etCustomEmoticons.setMinLines(3);
        etCustomEmoticons.setGravity(Gravity.TOP);
        etCustomEmoticons.setText(joinLines(config.customEmoticons));
        emojiInputLayout.addView(etCustomEmoticons);
        emojiLayout.addView(emojiInputLayout);

        emojiCard.addView(emojiLayout);
        root.addView(emojiCard);

        // ---------- 8. 目标应用包名 ----------
        MaterialCardView pkgCard = createCard();
        LinearLayout pkgLayout = cardContent();
        pkgLayout.addView(cardTitle("目标应用包名"));
        pkgLayout.addView(hintText(
                "无障碍服务只在这些应用里生效。输入包名后点添加，长按列表项可删除。\n"
                        + "不要乱添加，谨免封号；部分软件可能无效。"));

        LinearLayout pkgInputRow = new LinearLayout(this);
        pkgInputRow.setOrientation(LinearLayout.HORIZONTAL);
        pkgInputRow.setGravity(Gravity.CENTER_VERTICAL);

        TextInputLayout pkgInputLayout = outlinedField("例如 tv.danmaku.bili");
        pkgInputLayout.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        etPackageName = new TextInputEditText(this);
        etPackageName.setSingleLine(true);
        pkgInputLayout.addView(etPackageName);
        pkgInputRow.addView(pkgInputLayout);

        MaterialButton btnAddPackage = actionButton("添加", STYLE_TONAL);
        LinearLayout.LayoutParams btnPkgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnPkgLp.setMargins(dp(8), 0, 0, 0);
        btnAddPackage.setLayoutParams(btnPkgLp);
        btnAddPackage.setOnClickListener(v -> addPackage());
        pkgInputRow.addView(btnAddPackage);

        // 应用选择器：手输包名对普通用户太反人类——
        // 列出已安装应用，带图标和搜索，点一下就加。
        // 注意：Android 11+ 需要 Manifest 里的 <queries> 声明才看得到其他应用。
        MaterialButton btnPickApp = actionButton("选择", STYLE_OUTLINED);
        LinearLayout.LayoutParams btnPickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnPickLp.setMargins(dp(8), 0, 0, 0);
        btnPickApp.setLayoutParams(btnPickLp);
        btnPickApp.setOnClickListener(v ->
                AppPickerDialog.show(this, config.targetPackages,
                        (pkg, name) -> addPackageInternal(pkg, true)));
        pkgInputRow.addView(btnPickApp);

        pkgLayout.addView(pkgInputRow);

        packageListContainer = new LinearLayout(this);
        packageListContainer.setOrientation(LinearLayout.VERTICAL);
        packageListContainer.setPadding(0, dp(4), 0, 0);
        pkgLayout.addView(packageListContainer);

        pkgCard.addView(pkgLayout);
        root.addView(pkgCard);

        // ---------- 9. Shizuku 直写兜底 ----------
        MaterialCardView shizukuCard = createCard();
        LinearLayout shizukuLayout = cardContent();
        shizukuLayout.addView(cardTitle("Shizuku 直写兜底（高级）"));
        shizukuLayout.addView(hintText(
                "当部分应用（如微信）拒绝无障碍改写时，改用 Shizuku 切换输入法完成写入。"
                        + "全程无弹窗、不动剪贴板。需已安装并启动 Shizuku。"));

        shizukuStatus = new TextView(this);
        shizukuStatus.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium);
        shizukuStatus.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        shizukuStatus.setGravity(Gravity.CENTER);
        shizukuStatus.setPadding(0, dp(4), 0, dp(8));
        shizukuLayout.addView(shizukuStatus);

        shizukuAuthButton = actionButton("授权 Shizuku", STYLE_FILLED);
        LinearLayout.LayoutParams shzBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        shzBtnLp.setMargins(0, 0, 0, dp(4));
        shizukuAuthButton.setLayoutParams(shzBtnLp);
        shizukuAuthButton.setOnClickListener(v -> requestShizukuPermission());
        shizukuLayout.addView(shizukuAuthButton);

        swShizuku = addSwitch(shizukuLayout,
                "无障碍失败时启用 Shizuku 直写",
                "与无障碍通道互为兜底，尽可能保证每次改写成功且安静",
                config.shizukuFallbackEnabled);

        shizukuCard.addView(shizukuLayout);
        root.addView(shizukuCard);

        // ---------- 10. 底部操作 ----------
        floatingWindowButton = actionButton("开启悬浮窗", STYLE_TONAL);
        floatingWindowButton.setLayoutParams(fullWidthParams(dp(8)));
        floatingWindowButton.setOnClickListener(v -> toggleFloatingWindow());
        root.addView(floatingWindowButton);

        MaterialButton saveBtn = actionButton("保存设置", STYLE_FILLED);
        saveBtn.setLayoutParams(fullWidthParams(dp(12)));
        saveBtn.setOnClickListener(v -> saveConfig());
        root.addView(saveBtn);

        MaterialButton testBtn = actionButton("测试当前配置", STYLE_TONAL);
        testBtn.setLayoutParams(fullWidthParams(dp(8)));
        testBtn.setOnClickListener(v -> showTestDialog());
        root.addView(testBtn);

        MaterialButton logBtn = actionButton("查看诊断日志", STYLE_OUTLINED);
        logBtn.setLayoutParams(fullWidthParams(dp(8)));
        logBtn.setOnClickListener(v -> showLogDialog());
        root.addView(logBtn);

        TextView hint = new TextView(this);
        hint.setText("提示：修改设置后请点击保存，服务下次触发时自动加载");
        hint.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodySmall);
        hint.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(24), dp(16), dp(8));
        root.addView(hint);

        scrollView.addView(root);
        setContentView(scrollView);
        updatePackageListUI();
        updateServiceStatus();
    }

    // ==================================================================
    // 处理模式（分段按钮）
    // ==================================================================

    private void setMode(boolean realtime) {
        if (modeRealtime == realtime) return;
        modeRealtime = realtime;
        applyModeSelection();
    }

    private void applyModeSelection() {
        if (btnModePunctuation == null || btnModeRealtime == null) return;
        int primary = colorAttr(com.google.android.material.R.attr.colorPrimary);
        int onPrimary = colorAttr(com.google.android.material.R.attr.colorOnPrimary);
        int surface = colorAttr(com.google.android.material.R.attr.colorSurfaceVariant);
        int onSurface = colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant);

        btnModePunctuation.setBackgroundTintList(
                ColorStateList.valueOf(modeRealtime ? surface : primary));
        btnModePunctuation.setTextColor(modeRealtime ? onSurface : onPrimary);

        btnModeRealtime.setBackgroundTintList(
                ColorStateList.valueOf(modeRealtime ? primary : surface));
        btnModeRealtime.setTextColor(modeRealtime ? onPrimary : onSurface);
    }

    // ==================================================================
    // 忽略电池优化
    // ==================================================================

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true; // 6.0 以下没有这个机制
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
            Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show();
            updateBatteryUi();
            return;
        }
        // 方式一：直接弹系统确认框（部分国产 ROM 会拦截这个 Action）
        try {
            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(Uri.parse("package:" + getPackageName()));
            startActivity(it);
            return;
        } catch (Throwable ignored) {
        }
        // 方式二：退回电池优化白名单列表页，让用户手动找
        try {
            Intent it = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(it);
            Toast.makeText(this, "请在列表中找到本应用，设为「允许」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开电池优化设置页", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBatteryUi() {
        if (batteryStatus == null || batteryButton == null) return;
        boolean ok = isIgnoringBatteryOptimizations();
        batteryStatus.setText(ok ? "已忽略" : "未忽略");
        batteryStatus.setTextColor(colorAttr(
                ok ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorError));
        batteryButton.setText(ok ? "已开启" : "去设置");
        batteryButton.setEnabled(!ok);
        batteryButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    private boolean notificationsEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                return nm == null || nm.areNotificationsEnabled();
            }
            return true; // API 24 以下没法查，默认认为开着，别拦用户
        } catch (Throwable t) {
            return true;
        }
    }

    private void updateNotifyUi() {
        if (notifyStatus == null || notifyButton == null) return;
        boolean ok = notificationsEnabled();
        notifyStatus.setText(ok ? "已授权" : "未授权");
        notifyStatus.setTextColor(colorAttr(
                ok ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorError));
        notifyButton.setText(ok ? "已开启" : "去开启");
        notifyButton.setEnabled(!ok);
        notifyButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    /**
     * 请求通知权限。策略：
     *   - Android 13+ 且用户没拒绝过：弹系统权限对话框
     *   - 用户拒绝过一次：系统多半不再弹（二次弹窗会被拒），
     *     直接跳系统设置的通知页，那里有明确开关
     *   - Android 13 以下：通知默认有，跳设置页处理被手动关掉的情况
     */
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

    private void updateImeUi() {
        if (imeStatus == null || imeButton == null) return;
        boolean ok;
        try {
            ok = MiaoInjector.isImeEnabled(this);
        } catch (Throwable t) {
            ok = false;
        }
        imeStatus.setText(ok ? "已启用" : "未启用");
        imeStatus.setTextColor(colorAttr(
                ok ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorError));
        imeButton.setText(ok ? "已启用" : "前往启用");
        imeButton.setEnabled(!ok);
        imeButton.setAlpha(ok ? 0.5f : 1.0f);
    }

    // ==================================================================
    // Shizuku 状态与授权
    // ==================================================================

    /** 刷新 Shizuku 卡片的三态展示：未运行 / 未授权 / 已就绪 */
    private void updateShizukuUi() {
        if (shizukuStatus == null || shizukuAuthButton == null) return;
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

        if (!binderAlive) {
            shizukuStatus.setText("状态：未检测到运行\n（可选功能：先安装并启动 Shizuku，再回到本页）");
            shizukuAuthButton.setText("未检测到 Shizuku");
            shizukuAuthButton.setEnabled(false);
            shizukuAuthButton.setAlpha(0.5f);
        } else if (!granted) {
            shizukuStatus.setText("状态：已运行，等待授权");
            shizukuAuthButton.setText("授权 Shizuku");
            shizukuAuthButton.setEnabled(true);
            shizukuAuthButton.setAlpha(1.0f);
        } else {
            shizukuStatus.setText("状态：已就绪（无障碍改写失败时将自动直写）");
            shizukuAuthButton.setText("已授权");
            shizukuAuthButton.setEnabled(false);
            shizukuAuthButton.setAlpha(0.5f);
        }
    }

    /** 点击按钮时按需发起授权（仅一次性，授权后注入全程无弹窗） */
    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "未检测到 Shizuku，请先安装并启动 Shizuku", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Shizuku 不可用: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================================================================
    // UI 构件工厂（MD3）
    // ==================================================================

    /** MD3 elevated card：20dp 圆角、无描边、轻投影 */
    private MaterialCardView createCard() {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        card.setRadius(dp(20));
        card.setCardElevation(dp(2));
        card.setStrokeWidth(0);
        card.setUseCompatPadding(true);
        return card;
    }

    /** 卡片内容容器，统一内边距 */
    private LinearLayout cardContent() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        return l;
    }

    /** 卡片标题：MD3 风格——小号、左对齐、半粗、主题色 */
    private TextView cardTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceTitleMedium);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    /** 卡片内说明文字 */
    private TextView hintText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodySmall);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(0, 0, 0, dp(8));
        tv.setLineSpacing(0f, 1.25f);
        return tv;
    }

    private static final int STYLE_FILLED = 0;
    private static final int STYLE_TONAL = 1;
    private static final int STYLE_OUTLINED = 2;

    /**
     * 按 MD3 的按钮层级创建：filled=主操作，tonal=次操作，outlined=辅助操作。
     *
     * defStyleAttr 用项目自己声明的 attr（在 themes.xml 里指向
     * Widget.Material3.Button.TonalButton / OutlinedButton），
     * 而不是直接引用 material 库的 materialButtonTonalStyle——
     * 后者我没能在文档里核实一定存在，不存在就是编译失败。
     * 走自己的 attr，符号一定存在，效果完全相同。
     */
    private MaterialButton actionButton(String text, int style) {
        MaterialButton btn;
        if (style == STYLE_TONAL) {
            btn = new MaterialButton(this, null, R.attr.catTonalButtonStyle);
        } else if (style == STYLE_OUTLINED) {
            btn = new MaterialButton(this, null, R.attr.catOutlinedButtonStyle);
        } else {
            // filled：默认构造器就会套主题里的 Widget.Material3.Button
            btn = new MaterialButton(this);
        }
        btn.setText(text);
        btn.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        btn.setCornerRadius(dp(20));
        return btn;
    }

    private LinearLayout.LayoutParams fullWidthParams(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMargin, 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams fieldParams(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMargin, 0, bottomMargin);
        return lp;
    }

    /** MD3 描边输入框，统一圆角 */
    private TextInputLayout outlinedField(String hint) {
        TextInputLayout l = new TextInputLayout(this);
        if (hint != null) {
            l.setHint(hint);
        }
        // BOX_BACKGROUND_OUTLINE 会取主题 shapeAppearanceSmallComponent 的圆角，
        // MD3 主题下已经是 8dp，不需要额外设置。
        l.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        return l;
    }

    /** 一行「标题 + 描述 + 状态 + 按钮」，用于电池优化 / 输入法这类需要跳设置的项 */
    private LinearLayout buildStatusRow(String title, String desc,
                                        TextView statusView, MaterialButton button) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceTitleSmall);
        tvTitle.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodySmall);
        tvDesc.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvDesc.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(dp(8), 0, 0, 0);

        statusView.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceLabelLarge);
        statusView.setPadding(0, 0, dp(8), 0);
        right.addView(statusView);

        button.setMinWidth(0);
        button.setMinimumWidth(0);
        right.addView(button);

        row.addView(right);
        return row;
    }

    /** 小圆点状态指示 */
    private View dot(int color) {
        View v = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
        lp.setMargins(0, 0, dp(8), 0);
        v.setLayoutParams(lp);
        return v;
    }

    /** 改状态点颜色（GradientDrawable 改色最稳的做法是重建一个再设回去） */
    private void setDotColor(View v, int color) {
        if (v == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
    }

    // ==================================================================
    // MD3 开关（取代原来的小方框 CheckBox）
    // ==================================================================

    /**
     * 一行「标题 + 描述 + 胶囊开关」。
     * MaterialSwitch 在 MD3 下渲染为圆角轨道 + 圆形滑块，就是你要的胶囊形状。
     */
    private MaterialSwitch addSwitch(LinearLayout parent, String title, String desc, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceTitleSmall);
        tvTitle.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodySmall);
        tvDesc.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvDesc.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol);

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(checked);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
        return sw;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }

    /**
     * 解析 ?attr/xxx 主题颜色。
     *
     * 为什么不用 getResources().getColor(R.color.colorPrimary)：
     * 那是写死在 colors.xml 里的 #333333 深灰，跟 themes.xml 里 MD3 主题的
     * #6750A4 紫色不是一回事。原来整个界面都用的写死色，MD3 主题等于白声明了。
     */
    private int colorAttr(int attr) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                try {
                    return getResources().getColor(tv.resourceId);
                } catch (Throwable ignored) {
                }
            }
        }
        return 0xFF757575; // 兜底中灰
    }

    private String joinLines(String[] arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(t);
        }
        return sb.toString();
    }

    // ==================================================================
    // 包名列表
    // ==================================================================

    private void updatePackageListUI() {
        if (packageListContainer == null || config == null) return;
        packageListContainer.removeAllViews();
        for (final String pkg : config.targetPackages) {
            TextView tv = new TextView(this);
            tv.setText(pkg);
            tv.setTextAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium);
            tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            tv.setLayoutParams(lp);
            tv.setBackgroundResource(R.drawable.package_item_bg);
            tv.setOnLongClickListener(v -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除包名")
                        .setMessage("确定要移除 " + pkg + " 吗？")
                        .setPositiveButton("删除", (dialog, which) -> removePackage(pkg))
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            packageListContainer.addView(tv);
        }
    }

    private void addPackage() {
        String pkg = etPackageName.getText().toString().trim();
        etPackageName.setText("");
        addPackageInternal(pkg, false);
    }

    /** 真正的添加逻辑，手输和应用选择器共用。fromPicker：是否来自应用选择器 */
    private void addPackageInternal(String pkg, boolean fromPicker) {
        if (config == null) {
            return;
        }
        if (pkg.isEmpty()) {
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
            // 只在真正要用的时候才请求权限，不再一进 App 就弹
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, FloatingWindowService.class));
        isFloatingWindowShown = true;
        floatingWindowButton.setText("关闭悬浮窗");
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateShizukuUi();
        updateBatteryUi();
        updateImeUi();
        updateNotifyUi();
    }

    private void updateServiceStatus() {
        if (statusText == null || toggleButton == null) return;
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            statusText.setText("无障碍服务：已开启");
            statusText.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
            setDotColor(serviceDot, colorAttr(com.google.android.material.R.attr.colorPrimary));
            toggleButton.setText("服务已开启");
            toggleButton.setEnabled(false);
            toggleButton.setAlpha(0.5f);
        } else {
            statusText.setText("无障碍服务：未开启");
            statusText.setTextColor(colorAttr(com.google.android.material.R.attr.colorError));
            setDotColor(serviceDot, colorAttr(com.google.android.material.R.attr.colorError));
            toggleButton.setText("前往开启无障碍服务");
            toggleButton.setEnabled(true);
            toggleButton.setAlpha(1.0f);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService("accessibility");
            if (am == null) return false;
            List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(-1);
            for (AccessibilityServiceInfo info : services) {
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
    // 保存 / 测试
    // ==================================================================

    public void saveConfig() {
        try {
            config.enableAppend = swAppend.isChecked();
            String append = etAppendText.getText().toString().trim();
            config.appendText = append.isEmpty() ? "喵" : append;
            config.enableRandomEmoticon = swEmoticon.isChecked();
            config.processingMode = modeRealtime
                    ? CatConfig.MODE_REALTIME
                    : CatConfig.MODE_PUNCTUATION;
            config.shizukuFallbackEnabled = swShizuku == null || swShizuku.isChecked();

            ArrayList<CatConfig.Rule> rules = new ArrayList<>();
            String rulesText = etRules.getText() == null ? "" : etRules.getText().toString();
            for (String line : rulesText.split("\n")) {
                CatConfig.Rule r = CatConfig.parseRule(line);
                if (r != null) rules.add(r);
            }
            config.rules = rules;

            ArrayList<String> list = new ArrayList<>();
            String customText = etCustomEmoticons.getText() == null
                    ? "" : etCustomEmoticons.getText().toString().trim();
            if (!customText.isEmpty()) {
                for (String raw : customText.split("\n")) {
                    String t = raw.trim();
                    if (!t.isEmpty()) list.add(t);
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
            String msg = "断句追加：" + yn(testCfg.enableAppend) + "（" + (testCfg.appendText == null ? "" : testCfg.appendText) + "）"
                    + "\n句末颜文字：" + yn(testCfg.enableRandomEmoticon)
                    + "\n替换规则：" + testCfg.rules.size() + " 条"
                    + "\n自定义颜文字：" + (testCfg.customEmoticons.length > 0 ? testCfg.customEmoticons.length + "个" : "使用内置")
                    + "\nShizuku 兜底：" + yn(testCfg.shizukuFallbackEnabled) + "（" + (ShizukuInjector.isReady() ? "已就绪" : "未就绪/未授权") + "）"
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

    // ===================== 诊断日志 =====================

    /**
     * 展示自检快照 + 运行日志。
     *
     * 旧实现只 dump 内存日志，而用户从 QQ 切回来时进程往往已被回收，
     * 于是永远显示"请先去微信/QQ 输入…"，完全看不出问题在哪。
     * 现在：自检信息永远显示，日志为空时给出明确的排查方向。
     */
    private void showLogDialog() {
        String log = AppLog.dump();

        String body = AppLog.selfCheck(this)
                + "===== 运行日志（新的在最下面）=====\n"
                + (log.isEmpty()
                    ? "（空）\n\n若自检全部正常但仍无日志，说明无障碍服务没收到任何事件。\n"
                      + "按顺序检查：\n"
                      + "1. 系统设置 → 无障碍 → 本服务已开启\n"
                      + "2. 本 App 内总开关已打开\n"
                      + "3. 目标应用列表里勾选了 QQ / 微信\n"
                      + "4. 回到 QQ 输入框打字（要打出句号/逗号/空格/换行才会触发）"
                    : log);

        // BottomSheet 面板：可选中、可复制、可清空，视觉与选择器一致
        showInfoSheet("诊断日志", body, true, "清空", () -> {
            AppLog.clear();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Material 3 风格的信息面板（BottomSheet），配置预览和诊断日志共用。
     *
     * 【为什么不用 AlertDialog】和选择器同一个原因：原生 AlertDialog 的
     * 圆角、配色与 MD3 主题不搭，正文一长观感更差。BottomSheet 从底部
     * 滑出、自带主题圆角；正文设了最大高度，内容少时面板自适应高度，
     * 内容多时正文内部滚动，不会把面板撑满整屏。
     *
     * 正文沿用旧版的能力：setTextIsSelectable 让日志能长按选中手选复制，
     * 「复制全部」按钮则一键全拷——两条路都通。
     *
     * @param monospace  true=等宽小字（日志对齐），false=常规正文（配置预览）
     * @param extraLabel 可选次要按钮（日志的「清空」），null 则不显示
     * @param extraClick 次要按钮动作，点击后自动关闭面板
     */
    private void showInfoSheet(String title, String body, boolean monospace,
                               String extraLabel, Runnable extraClick) {
        float density = getResources().getDisplayMetrics().density;
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleMedium);
        titleTv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
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
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        // 380dp ≈ 屏幕 40%：日志再长也只在这高度内滚，面板不占满整屏
        tv.setMaxHeight((int) (380 * density));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = dp(12);
        root.addView(sv, svLp);

        // ---- 按钮行：次要动作 + 复制 + 关闭 ----
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(10), 0, 0);

        BottomSheetDialog sheet = new BottomSheetDialog(this);

        if (extraLabel != null) {
            MaterialButton extraBtn = actionButton(extraLabel, STYLE_TONAL);
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

        MaterialButton copyBtn = actionButton("复制全部", STYLE_TONAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.leftMargin = dp(8);
        copyBtn.setLayoutParams(copyLp);
        copyBtn.setOnClickListener(v -> copyTextToClipboard(body));
        btnRow.addView(copyBtn);

        MaterialButton closeBtn = actionButton("关闭", STYLE_OUTLINED);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(8);
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setOnClickListener(v -> sheet.dismiss());
        btnRow.addView(closeBtn);

        root.addView(btnRow);

        sheet.setContentView(root);
        sheet.show();
    }

    /** 一键把文本复制到剪贴板（日志、配置预览共用），方便贴给开发者排查 */
    private void copyTextToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("cat_diag_log", text));
            Toast.makeText(this, "已复制（" + text.length() + " 字）",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
