package com.onetapmiao.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 三页（状态 / 功能 / 权限）共用的 UI 小部件工厂（v1.1.1 重构提取）。
 *
 * 原来这些方法都是 MainActivity 的私有成员，拆页后三个 Activity 都要用，
 * 提取成静态工具最干净——每页保持「只写自己的业务，不复制粘贴样式」。
 */
public final class UiKit {

    public static final int STYLE_FILLED = 0;
    public static final int STYLE_TONAL = 1;
    public static final int STYLE_OUTLINED = 2;

    private UiKit() {
        // 工具类，禁止实例化
    }

    public static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().scaledDensity);
    }

    public static int colorAttr(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    public static MaterialCardView createCard(Context c) {
        MaterialCardView card = new MaterialCardView(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 12));
        card.setLayoutParams(lp);
        card.setRadius(dp(c, 20));
        card.setCardElevation(dp(c, 2));
        card.setStrokeWidth(0);
        card.setUseCompatPadding(true);
        return card;
    }

    public static LinearLayout cardContent(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        return l;
    }

    public static TextView cardTitle(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceTitleMedium);
        tv.setTextColor(colorAttr(c, com.google.android.material.R.attr.colorPrimary));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(c, 4));
        return tv;
    }

    public static TextView hintText(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceBodySmall);
        tv.setTextColor(colorAttr(c, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(0, 0, 0, dp(c, 8));
        tv.setLineSpacing(0f, 1.25f);
        return tv;
    }

    public static MaterialButton actionButton(Context c, String text, int style) {
        MaterialButton btn;
        if (style == STYLE_TONAL) {
            btn = new MaterialButton(c, null, R.attr.catTonalButtonStyle);
        } else if (style == STYLE_OUTLINED) {
            btn = new MaterialButton(c, null, R.attr.catOutlinedButtonStyle);
        } else {
            btn = new MaterialButton(c);
        }
        btn.setText(text);
        btn.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceLabelLarge);
        btn.setCornerRadius(dp(c, 20));
        return btn;
    }

    public static LinearLayout.LayoutParams fullWidthParams(Context c, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMargin, 0, 0);
        return lp;
    }

    public static LinearLayout.LayoutParams fieldParams(Context c, int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, topMargin, 0, bottomMargin);
        return lp;
    }

    public static View dot(Context c, int color) {
        View v = new View(c);
        setDotColor(v, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 10), dp(c, 10));
        lp.setMargins(0, 0, dp(c, 8), 0);
        v.setLayoutParams(lp);
        return v;
    }

    /** 改状态点颜色（GradientDrawable 改色最稳的做法是重建一个再设回去） */
    public static void setDotColor(View v, int color) {
        if (v == null) {
            return;
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
    }

    /** 一行「标题 + 描述 + 状态 + 按钮」开关行，权限页状态行用 */
    public static LinearLayout buildStatusRow(Context c, String title, String desc,
                                              TextView statusView, MaterialButton button) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 8), 0, dp(c, 8));

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(c);
        tvTitle.setText(title);
        tvTitle.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceTitleSmall);
        tvTitle.setTextColor(colorAttr(c, com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(c);
        tvDesc.setText(desc);
        tvDesc.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceBodySmall);
        tvDesc.setTextColor(colorAttr(c, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvDesc.setPadding(0, dp(c, 2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol);

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(dp(c, 8), 0, 0, 0);

        statusView.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceLabelLarge);
        statusView.setPadding(0, 0, dp(c, 8), 0);
        right.addView(statusView);

        button.setMinWidth(0);
        button.setMinimumWidth(0);
        right.addView(button);

        row.addView(right);
        return row;
    }

    /** 一行「标题 + 描述 + 开关」行，功能页开关用 */
    public static MaterialSwitch addSwitch(LinearLayout parent, Context c,
                                          String title, String desc, boolean checked) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, 8), 0, dp(c, 8));
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(c);
        tvTitle.setText(title);
        tvTitle.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceTitleSmall);
        tvTitle.setTextColor(colorAttr(c, com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(c);
        tvDesc.setText(desc);
        tvDesc.setTextAppearance(c, com.google.android.material.R.attr.textAppearanceBodySmall);
        tvDesc.setTextColor(colorAttr(c, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvDesc.setPadding(0, dp(c, 2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol);

        MaterialSwitch sw = new MaterialSwitch(c);
        sw.setChecked(checked);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
        return sw;
    }
}
