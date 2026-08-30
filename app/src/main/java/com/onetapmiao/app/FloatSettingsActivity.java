package com.onetapmiao.app;

import com.onetapmiao.app.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 悬浮窗外观设置（v1.1 新增）。
 *
 * 为什么单独开一页：
 *   主界面已经很长了，大小/透明度这类「调一次就用一辈子」的设置
 *   塞在主界面只会让它更臃肿。集中到一个页面，主界面只留一个入口。
 *
 * 保存策略：所有改动「即时生效 + 即时落盘」——
 *   滑条在松手时保存（拖动过程不写存储，避免频繁 IO），
 *   开关和按钮在点击时保存。悬浮窗活着的话立刻应用新外观。
 *
 * 读写都走「load -> 只改自己管的字段 -> save」，绝不全量覆盖：
 *   悬浮窗位置（floatX/Y）是 FloatingWindowService 在拖动时写的，
 *   如果这里 load 出来直接改再存，会把位置写回旧值——踩过这种坑的项目不少。
 */
public class FloatSettingsActivity extends Activity {

    private TextView tvSizeValue;
    private TextView tvAlphaValue;
    private SeekBar seekSize;
    private SeekBar seekAlpha;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        // ---- 顶部返回行 ----
        MaterialButton btnBack = new MaterialButton(this, null, R.attr.catOutlinedButtonStyle);
        btnBack.setText("← 返回");
        btnBack.setMinWidth(0);
        btnBack.setMinimumWidth(0);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        backLp.setMargins(0, 0, 0, dp(12));
        btnBack.setLayoutParams(backLp);
        btnBack.setOnClickListener(v -> finish());
        root.addView(btnBack);

        TextView pageTitle = new TextView(this);
        pageTitle.setText("悬浮窗外观设置");
        pageTitle.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceHeadlineSmall);
        pageTitle.setTextColor(colorAttr(
                com.google.android.material.R.attr.colorOnSurface));
        pageTitle.setPadding(0, 0, 0, dp(12));
        root.addView(pageTitle);

        // ---- 主卡片 ----
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);
        card.setRadius(dp(20));
        card.setCardElevation(dp(2));
        card.setStrokeWidth(0);
        card.setUseCompatPadding(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(content);

        content.addView(title("悬浮窗外观"));
        content.addView(hint("拖动悬浮窗即可移动位置，松手自动记住；"
                + "开启贴边吸附后会自动吸到屏幕左右边缘。所有改动即时生效。"));

        CatConfig cfg = CatConfig.load(this);

        // ---- 大小滑条（70% ~ 150%）----
        content.addView(rowLabel("大小"));
        seekSize = new SeekBar(this);
        seekSize.setMax((int) (CatConfig.FLOAT_SIZE_MAX * 100)
                - (int) (CatConfig.FLOAT_SIZE_MIN * 100));
        seekSize.setProgress(Math.round(cfg.floatSize * 100)
                - (int) (CatConfig.FLOAT_SIZE_MIN * 100));
        content.addView(seekSize, fullWidthParams(dp(2), dp(8)));
        tvSizeValue = valueText();
        content.addView(tvSizeValue);
        tvSizeValue.setText(Math.round(cfg.floatSize * 100) + "%");
        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 松手才保存：拖动过程每次都写存储就太勤快了
                float size = CatConfig.clampFloatSize(
                        seekToSize(seekSize.getProgress()));
                tvSizeValue.setText(Math.round(size * 100) + "%");
                persistAppearance(size, null, null);
                FloatingWindowService.applyAppearanceFromSettings(
                        FloatSettingsActivity.this);
            }
        });

        // ---- 透明度滑条（30% ~ 100%）----
        content.addView(rowLabel("透明度"));
        seekAlpha = new SeekBar(this);
        seekAlpha.setMax(CatConfig.FLOAT_ALPHA_MAX - CatConfig.FLOAT_ALPHA_MIN);
        seekAlpha.setProgress(cfg.floatAlpha - CatConfig.FLOAT_ALPHA_MIN);
        content.addView(seekAlpha, fullWidthParams(dp(2), dp(8)));
        tvAlphaValue = valueText();
        content.addView(tvAlphaValue);
        tvAlphaValue.setText(cfg.floatAlpha + "%");
        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int alpha = CatConfig.clampFloatAlpha(
                        seekAlpha.getProgress() + CatConfig.FLOAT_ALPHA_MIN);
                tvAlphaValue.setText(alpha + "%");
                persistAppearance(null, alpha, null);
                FloatingWindowService.applyAppearanceFromSettings(
                        FloatSettingsActivity.this);
            }
        });

        // ---- 贴边吸附开关 ----
        MaterialSwitch swSnap = new MaterialSwitch(this);
        swSnap.setChecked(cfg.floatSnap);
        LinearLayout snapRow = switchRow("贴边吸附",
                "松手后自动吸到屏幕左右边缘，不挡屏幕中间的字", swSnap);
        content.addView(snapRow);
        swSnap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            persistAppearance(null, null, isChecked);
            Toast.makeText(this, isChecked ? "贴边吸附已开启" : "贴边吸附已关闭",
                    Toast.LENGTH_SHORT).show();
        });

        // ---- 加喵成功振动开关 ----
        final MaterialSwitch swVibrate = new MaterialSwitch(this);
        swVibrate.setChecked(cfg.enableVibrate);
        LinearLayout vibrateRow = switchRow("加喵成功振动",
                "加喵成功时轻微震动一下（失败不震），可随时关闭", swVibrate);
        content.addView(vibrateRow);
        swVibrate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            persistVibrate(isChecked);
            Toast.makeText(this, isChecked ? "振动反馈已开启" : "振动反馈已关闭",
                    Toast.LENGTH_SHORT).show();
        });

        // ---- 恢复默认设置（大小/透明度/贴边/位置全部归零）----
        MaterialButton btnReset = new MaterialButton(this, null, R.attr.catTonalButtonStyle);
        btnReset.setText("恢复默认设置");
        btnReset.setLayoutParams(fullWidthParams(dp(12), 0));
        btnReset.setOnClickListener(v -> {
            CatConfig c = CatConfig.load(this);
            c.floatSize = 1.0f;
            c.floatAlpha = 100;
            c.floatSnap = true;
            c.enableVibrate = true;
            c.floatX = CatConfig.FLOAT_POS_UNSET;
            c.floatY = CatConfig.FLOAT_POS_UNSET;
            c.save(this);

            // 滑条与开关同步回默认位置
            seekSize.setProgress(Math.round(1.0f * 100)
                    - (int) (CatConfig.FLOAT_SIZE_MIN * 100));
            seekAlpha.setProgress(CatConfig.FLOAT_ALPHA_MAX - CatConfig.FLOAT_ALPHA_MIN);
            tvSizeValue.setText("100%");
            tvAlphaValue.setText("100%");
            swSnap.setChecked(true);
            swVibrate.setChecked(true);

            // 悬浮窗若开着，立即应用外观并回到默认位置
            FloatingWindowService.applyAppearanceFromSettings(this);
            FloatingWindowService.resetPositionFromSettings(this);

            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
            AppLog.add("FloatSet", "悬浮窗外观已重置为默认");
        });
        content.addView(btnReset);

        root.addView(card);
    }

    // ------------------------------------------------------------------
    // 持久化：load -> 只改传入的字段 -> save
    // ------------------------------------------------------------------

    /**
     * 保存外观改动。传 null 的字段保持原值——
     * 这样三条控件各自保存时互不干扰，也绝不动悬浮窗位置。
     */
    private void persistAppearance(Float size, Integer alpha, Boolean snap) {
        try {
            CatConfig c = CatConfig.load(this);
            if (size != null) {
                c.floatSize = CatConfig.clampFloatSize(size);
            }
            if (alpha != null) {
                c.floatAlpha = CatConfig.clampFloatAlpha(alpha);
            }
            if (snap != null) {
                c.floatSnap = snap;
            }
            c.save(this);
        } catch (Throwable t) {
            AppLog.add("FloatSet", "保存悬浮窗外观失败", t);
        }
    }

    /** 单独保存振动开关（不属于外观，但放在本页方便统一管理） */
    private void persistVibrate(boolean enabled) {
        try {
            CatConfig c = CatConfig.load(this);
            c.enableVibrate = enabled;
            c.save(this);
        } catch (Throwable t) {
            AppLog.add("FloatSet", "保存振动开关失败", t);
        }
    }

    /** SeekBar 进度 -> 缩放倍率 */
    private float seekToSize(int progress) {
        return (progress + (int) (CatConfig.FLOAT_SIZE_MIN * 100)) / 100f;
    }

    // ------------------------------------------------------------------
    // 小部件工厂（与 MainActivity 同风格）
    // ------------------------------------------------------------------

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleMedium);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceBodySmall);
        tv.setTextColor(colorAttr(
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(0, 0, 0, dp(8));
        tv.setLineSpacing(0f, 1.25f);
        return tv;
    }

    private TextView rowLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleSmall);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        return tv;
    }

    private TextView valueText() {
        TextView tv = new TextView(this);
        tv.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceLabelLarge);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        tv.setGravity(Gravity.END);
        return tv;
    }

    private LinearLayout switchRow(String title, String desc, MaterialSwitch sw) {
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
        tvTitle.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceTitleSmall);
        tvTitle.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextAppearance(this,
                com.google.android.material.R.attr.textAppearanceBodySmall);
        tvDesc.setTextColor(colorAttr(
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvDesc.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout.LayoutParams fullWidthParams(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, top, 0, bottom);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int colorAttr(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
