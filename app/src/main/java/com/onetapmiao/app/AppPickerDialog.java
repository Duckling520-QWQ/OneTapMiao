package com.onetapmiao.app;

import com.onetapmiao.app.R;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用选择器：列出已安装应用（有桌面入口的），点一下即选为适配目标。
 *
 * 【为什么用 BottomSheetDialog 而不是 AlertDialog】
 *   第一版用的是原生 AlertDialog + 普通 EditText，在 Material 3 主题下
 *   圆角、配色、控件风格全都对不上，很扎眼。BottomSheet 从底部滑出、
 *   自带圆角和主题配色，是「选一个东西」类交互的标准形态，
 *   Google 自家应用（分享、选联系人）都是它。内部布局完全按本应用的
 *   Material 3 规范手写：标题用 titleMedium、搜索框用 outlined 文本框、
 *   行背景复用包名列表的 package_item_bg，视觉上和设置页是一体的。
 *
 * 【两个必须知道的坑，处理逻辑都在注释里】
 *   1. 应用可见性（AndroidManifest.xml 的 <queries> 声明）：
 *      Android 11（targetSdk 30）起，PackageManager 默认看不见其他应用，
 *      必须在 Manifest 里声明 <queries> 请求「能启动 MAIN/LAUNCHER 的应用」。
 *      没声明的话列表会近乎全空——空状态文案里把检查方法写全了，
 *      用户一眼就能对上号。
 *   2. 查询耗时：应用一多，queryIntentActivities 连同逐个 loadIcon/loadLabel
 *      可能要几百毫秒，绝不能在主线程做——先显示「正在加载」，后台线程
 *      查完再回主线程渲染；期间用户关掉对话框也要安全收场。
 */
public final class AppPickerDialog {

    /** 选中一个应用后的回调（在主线程触发） */
    public interface OnAppPickedListener {
        void onAppPicked(String packageName, String appName);
    }

    /** 包可见：便于单元测试构造 */
    static final class AppItem {
        final String pkg;
        final String label;
        final Drawable icon;

        AppItem(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private final Activity activity;
    private final List<String> addedPackages;
    private final OnAppPickedListener listener;

    private BottomSheetDialog dialog;
    private LinearLayout listContainer;
    private List<AppItem> allApps = new ArrayList<>();
    private volatile String query = "";

    private AppPickerDialog(Activity activity, List<String> addedPackages,
                            OnAppPickedListener listener) {
        this.activity = activity;
        this.addedPackages = addedPackages;
        this.listener = listener;
    }

    /** 打开选择器。addedPackages 用于给已添加的应用打「已适配」标记。 */
    public static void show(Activity activity, List<String> addedPackages,
                            OnAppPickedListener listener) {
        new AppPickerDialog(activity, addedPackages, listener).showInternal();
    }

    private void showInternal() {
        float density = activity.getResources().getDisplayMetrics().density;

        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        dialog = sheet;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        root.setPadding(pad, pad, pad, pad);

        // ---- 标题 + 说明 ----
        TextView title = new TextView(activity);
        title.setText("选择要适配的应用");
        title.setTextAppearance(activity,
                com.google.android.material.R.attr.textAppearanceTitleMedium);
        title.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        root.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText("点一下应用即加为适配目标，已添加的带「已适配」标记");
        subtitle.setTextAppearance(activity,
                com.google.android.material.R.attr.textAppearanceBodySmall);
        subtitle.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setPadding(0, (int) (4 * density), 0, 0);
        root.addView(subtitle);

        // ---- 搜索框：与设置页一致的 outlined 文本框 ----
        TextInputLayout searchLayout = new TextInputLayout(activity);
        searchLayout.setHint("搜索应用名或包名");
        // BOX_BACKGROUND_OUTLINE 会取主题 shapeAppearanceSmallComponent 的圆角，
        // MD3 主题下已是 8dp，与设置页的包名输入框完全一致。
        searchLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText searchEdit = new TextInputEditText(activity);
        searchEdit.setSingleLine(true);
        searchLayout.addView(searchEdit);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = (int) (14 * density);
        root.addView(searchLayout, searchLp);

        // ---- 应用列表 ----
        ScrollView scroll = new ScrollView(activity);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = (int) (10 * density);
        root.addView(scroll, scrollLp);

        listContainer.addView(placeholderText("正在加载应用列表…"));

        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim();
                rebuildList();
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        sheet.setContentView(root);
        sheet.show();

        // 后台查询：不能让主线程等 PackageManager（可能几百毫秒）
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppItem> apps = loadApps();
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 用户可能已经把对话框关了，别再往已关闭/已销毁的界面上刷
                        if (dialog == null || !dialog.isShowing() || activity.isFinishing()
                                || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                            return;
                        }
                        allApps = apps;
                        rebuildList();
                    }
                });
            }
        }, "app-picker-load").start();
    }

    /** 查询有桌面入口的应用。请在后台线程调用。 */
    private List<AppItem> loadApps() {
        PackageManager pm = activity.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = null;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable ignored) {
        }
        return cleanAndSort(ris, pm, activity.getPackageName());
    }

    /**
     * 清洗 + 去重 + 排序。包可见，便于单元测试。
     *
     * 【为什么要去重】同一个应用可能有多个桌面入口（主入口、双开、游戏小号等），
     * queryIntentActivities 会为每个入口返回一条记录，包名相同——
     * 不去重的话列表里会出现好几个一样的应用，用户根本分不清该点哪个。
     *
     * 【pm 绝不能传 null——这里踩过真机大坑】
     *   真实 Android 的 ResolveInfo.loadLabel(pm)/loadIcon(pm) 内部要拿
     *   PackageManager 去解析应用名和图标，传 null 直接 NPE。
     *   之前的实现就在下面 safeLabel/safeIcon 里传了 null：
     *   本地桩子的 loadLabel(null) 优雅返回、测试全绿，
     *   真机上却每个应用都取不到名字和图标、全部走包名兜底——
     *   表现就是用户看到的「列表只有包名，没有名字和图标」。
     *   教训：桩子必须尽量贴近真实 SDK 的行为（现在桩子对 null 会抛 NPE），
     *   否则测试给的全是假信心。
     */
    static List<AppItem> cleanAndSort(List<ResolveInfo> ris, PackageManager pm,
                                      String selfPkg) {
        List<AppItem> out = new ArrayList<>();
        if (ris == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : ris) {
            try {
                if (ri == null || ri.activityInfo == null
                        || ri.activityInfo.packageName == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(selfPkg)) {
                    continue; // 自己已经在设置页里了，不进列表
                }
                if (!seen.add(pkg)) {
                    continue; // 同包名只留一条
                }
                out.add(new AppItem(pkg, safeLabel(ri, pm, pkg), safeIcon(ri, pm)));
            } catch (Throwable ignored) {
            }
        }
        // 中文按拼音排序，找应用才符合直觉
        final Collator coll = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, (a, b) -> coll.compare(a.label, b.label));
        return out;
    }

    /** 应用名取不到或为空就用包名兜底——宁可显示包名，也不显示一个空白条目 */
    private static String safeLabel(ResolveInfo ri, PackageManager pm, String fallbackPkg) {
        try {
            CharSequence label = ri.loadLabel(pm);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return fallbackPkg;
    }

    private static Drawable safeIcon(ResolveInfo ri, PackageManager pm) {
        try {
            return ri.loadIcon(pm);
        } catch (Throwable t) {
            return null; // 个别应用图标取不到，用首字占位，不挡整表
        }
    }

    /**
     * 搜索匹配：应用名或包名包含搜索词（忽略大小写）即算命中；
     * 空词匹配全部。包可见，便于单元测试。
     */
    static boolean matches(AppItem app, String query) {
        String q = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        return app.label.toLowerCase(Locale.ROOT).contains(q)
                || app.pkg.toLowerCase(Locale.ROOT).contains(q);
    }

    /** 按当前搜索词重建列表内容。 */
    private void rebuildList() {
        if (listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        int matched = 0;
        for (AppItem app : allApps) {
            if (!matches(app, query)) {
                continue;
            }
            matched++;
            listContainer.addView(buildRow(app));
        }
        if (matched == 0) {
            if (allApps.isEmpty()) {
                // 空列表几乎一定是 queries 没生效，把排查步骤写全，别让用户猜
                listContainer.addView(placeholderText(
                        "没有读到任何应用。请按顺序检查：\n\n"
                                + "1. AndroidManifest.xml 是否为最新版（带 <queries> 声明）\n"
                                + "2. 它是否放在 app/src/main/ 目录下\n"
                                + "3. 重新编译并卸载重装一次\n\n"
                                + "Android 11 起不声明 <queries>，系统会把其他应用全部隐藏"));
            } else {
                listContainer.addView(placeholderText("没有匹配「" + query + "」的应用"));
            }
        }
    }

    private View buildRow(AppItem app) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));
        row.setBackgroundResource(R.drawable.package_item_bg); // 与包名列表同一套背景

        // ---- 图标：取不到就用「应用名首字 + 主色圆角块」占位 ----
        View iconView;
        if (app.icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(activity);
            iv.setImageDrawable(app.icon);
            iconView = iv;
        } else {
            TextView fallback = new TextView(activity);
            String first = (app.label != null && !app.label.isEmpty())
                    ? app.label.substring(0, 1) : "?";
            fallback.setText(first);
            fallback.setGravity(Gravity.CENTER);
            fallback.setTextColor(0xFFFFFFFF);
            fallback.setTextSize(15);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
            gd.setCornerRadius(10 * density);
            fallback.setBackgroundDrawable(gd);
            iconView = fallback;
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                (int) (38 * density), (int) (38 * density));
        iconLp.rightMargin = pad;
        row.addView(iconView, iconLp);

        // ---- 应用名 + 包名 ----
        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(activity);
        name.setText(app.label);
        name.setTextAppearance(activity,
                com.google.android.material.R.attr.textAppearanceBodyMedium);
        name.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(name);

        TextView pkgTv = new TextView(activity);
        pkgTv.setText(app.pkg);
        pkgTv.setTextAppearance(activity,
                com.google.android.material.R.attr.textAppearanceBodySmall);
        pkgTv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        textCol.addView(pkgTv);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- 已适配标记 ----
        if (addedPackages.contains(app.pkg)) {
            TextView badge = new TextView(activity);
            badge.setText("已适配");
            badge.setTextAppearance(activity,
                    com.google.android.material.R.attr.textAppearanceLabelSmall);
            badge.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
            badge.setPadding((int) (8 * density), 0, 0, 0);
            row.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(v -> {
            if (dialog != null) {
                dialog.dismiss();
            }
            listener.onAppPicked(app.pkg, app.label);
        });
        return row;
    }

    private TextView placeholderText(String text) {
        float density = activity.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(0, 1.15f);
        tv.setPadding(0, (int) (24 * density), 0, (int) (24 * density));
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        return tv;
    }

    private int colorAttr(int attr) {
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                try {
                    return activity.getResources().getColor(tv.resourceId);
                } catch (Throwable ignored) {
                }
            }
        }
        return 0xFF757575; // 兜底中灰，与 MainActivity.colorAttr 一致
    }
}
