package com.onetapmiao.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public class CatConfig {
    public static final String[] BUILTIN_EMOTICONS = {"^⌯𖥦⌯^ ੭ ^", "⌯'ㅅ'⌯", "=^𖥦^=", "⌯•ㅅ•⌯", "ฅ•̀∀•́ฅ", "ฅ ̳͒•ˑ̫• ̳͒ฅ♡", "ฅ(̳•·̫•̳ฅ)♡", "ฅ^••^ฅ", "=^•ω•^=", "₍^ >ヮ<^₎", "/ᐠ - ˕ -マ Ⳋ", "ฅ^•ﻌ•^ฅ", "ฅ՞•ﻌ•՞ฅ", "(ฅ´ω`ฅ)", "ฅ(*`ω´*)ฅ", "ฅ꒰ ⸝˶• •˶⸝꒱ฅ", "₍˄·͈༝·͈˄*₎◞ ̑̑", "!!^⌯𖥦⌯^ ੭!!", "₍^⸝⸝> ·̫ <⸝⸝ ^₎", "ฅ^._.^ฅ", "₍🎀˄•͈༝•͈˄₎ฅ˒˒", "^•͈༝•^ฅ", "꒰ఎ(^ . ֑ .^)໒꒱", "ฅ●ω●ฅ", "₍⸍⸌·͈༝·͈⸍⸌₎◞", "(>^ω^<)", "ฅ^-﹃-^ฅ", "^ ̳ට ̫ ට ̳^", "୧₍˄·͈༝·͈˄₎୨", "^ ̳ᴗ  ̫ ᴗ ̳^", "˓˓ก(⸍⸌̣ʷ̣̫⸍̣⸌₎ค˒˒", "ヽ(ฅ≧へ≦)ฅ", "(`･ω･´)ฅ", "(=^･ᴥ･^=)", "(^ω^ฅ)", "ฅ(≧▽≦)ฅ", "ฅ(=´▽`=)ฅ", "ヾ((๑˘ㅂ˘๑)ฅ)", "(ฅ◑ω◑ฅ)", "(๑•̀ω•́ฅ)", "(ฅ>ω<*ฅ)", "(=^.^=)", "(=´ᴥ`)", "(=ↀωↀ=)", "(=^-ω-^=)", "ฅ(*°ω°*ฅ)", "ヽ(=^･ω･^=)丿", "(^•ᴥ•^)", "( Φ ω Φ )", "(=^x^=)", "ฅ( ̳• ◡ • ̳)ฅ", "o( =•ω•= )m", "~o( =∩ω∩= )m", "≡ω≡"};

    public static final String KEY_RULES = "rules";
    public static final String KEY_ENABLE_APPEND = "enable_append";
    public static final String KEY_APPEND_TEXT = "append_text";
    public static final String KEY_ENABLE_EMOTICON = "enable_emoticon";
    public static final String KEY_CUSTOM_EMOTICONS = "custom_emoticons";
    public static final String KEY_PROCESSING_MODE = "processing_mode";
    public static final String MODE_PUNCTUATION = "punctuation";
    public static final String MODE_REALTIME = "realtime";
    private static final String PREFS_NAME = "cat_config";
    public static final String KEY_PROCESSING_ENABLED = "processing_enabled";
    public static final String KEY_SHIZUKU_FALLBACK = "shizuku_fallback";

    // ---- v1.1 新增：悬浮窗外观 / 振动反馈 ----
    /** 悬浮窗缩放倍率存储键（0.7~1.5） */
    public static final String KEY_FLOAT_SIZE = "float_size";
    /** 悬浮窗透明度存储键（百分比 30~100） */
    public static final String KEY_FLOAT_ALPHA = "float_alpha";
    /** 贴边吸附开关存储键 */
    public static final String KEY_FLOAT_SNAP = "float_snap";
    /** 悬浮窗位置记忆 X（像素；-1 表示从未拖动过，用默认位置） */
    public static final String KEY_FLOAT_X = "float_x";
    /** 悬浮窗位置记忆 Y（像素；-1 表示从未拖动过） */
    public static final String KEY_FLOAT_Y = "float_y";
    /** 加喵成功振动反馈开关存储键 */
    public static final String KEY_ENABLE_VIBRATE = "enable_vibrate";
    /** 目标应用包名列表存储键（空串 = 用户已清空，null = 从未保存） */
    public static final String KEY_TARGET_PACKAGES = "target_packages";

    /** 悬浮窗缩放倍率下限 */
    public static final float FLOAT_SIZE_MIN = 0.7f;
    /** 悬浮窗缩放倍率上限 */
    public static final float FLOAT_SIZE_MAX = 1.5f;
    /** 悬浮窗透明度百分比下限 */
    public static final int FLOAT_ALPHA_MIN = 30;
    /** 悬浮窗透明度百分比上限 */
    public static final int FLOAT_ALPHA_MAX = 100;
    /** 位置记忆「未设置」标记值 */
    public static final int FLOAT_POS_UNSET = -1;

    public static class Rule {
        public final String from;
        public final String to;

        public Rule(String from, String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return from + "=" + to;
        }
    }

    public boolean enableAppend = true;
    public String appendText = "喵";
    public boolean enableRandomEmoticon = true;
    public String processingMode = MODE_PUNCTUATION;
    public String[] customEmoticons = new String[0];
    public ArrayList<String> targetPackages = new ArrayList<>();
    public List<Rule> rules = new ArrayList<>();
    public boolean processingEnabled = true;      // 总开关，默认开启
    public boolean shizukuFallbackEnabled = true; // 无障碍被拒时是否启用 Shizuku 直写兜底

    // ---- v1.1 新增字段 ----
    /** 悬浮窗缩放倍率（0.7~1.5，1.0 = 原始大小） */
    public float floatSize = 1.0f;
    /** 悬浮窗不透明度百分比（30~100，100 = 完全不透明） */
    public int floatAlpha = 100;
    /** 松手后是否自动吸附到屏幕左右边缘 */
    public boolean floatSnap = true;
    /** 悬浮窗位置记忆 X（像素；FLOAT_POS_UNSET = 未设置） */
    public int floatX = FLOAT_POS_UNSET;
    /** 悬浮窗位置记忆 Y（像素） */
    public int floatY = FLOAT_POS_UNSET;
    /** 加喵成功时是否振动反馈 */
    public boolean enableVibrate = true;

    /** 把任意缩放值钳制到合法区间 [FLOAT_SIZE_MIN, FLOAT_SIZE_MAX] */
    public static float clampFloatSize(float v) {
        if (v < FLOAT_SIZE_MIN) return FLOAT_SIZE_MIN;
        if (v > FLOAT_SIZE_MAX) return FLOAT_SIZE_MAX;
        return v;
    }

    /** 把任意透明度百分比钳制到合法区间 [FLOAT_ALPHA_MIN, FLOAT_ALPHA_MAX] */
    public static int clampFloatAlpha(int v) {
        if (v < FLOAT_ALPHA_MIN) return FLOAT_ALPHA_MIN;
        if (v > FLOAT_ALPHA_MAX) return FLOAT_ALPHA_MAX;
        return v;
    }

    public static Rule parseRule(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.isEmpty()) {
            return null;
        }
        String separators = "=＝→";
        int idx = -1;
        for (int i = 0; i < separators.length(); i++) {
            int p = s.indexOf(separators.charAt(i));
            if (p >= 0 && (idx < 0 || p < idx)) {
                idx = p;
            }
        }
        if (idx <= 0) {
            return null;
        }
        String from = s.substring(0, idx).trim();
        String to = s.substring(idx + 1).trim();
        if (from.isEmpty()) {
            return null;
        }
        return new Rule(from, to);
    }

    public static String rulesToString(List<Rule> rules) {
        StringBuilder sb = new StringBuilder();
        if (rules != null) {
            for (Rule r : rules) {
                if (r == null || r.from.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(r.from).append('=').append(r.to);
            }
        }
        return sb.toString();
    }

    public static CatConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, 0);
        CatConfig cfg = new CatConfig();
        cfg.enableAppend = sp.getBoolean(KEY_ENABLE_APPEND, true);
        cfg.appendText = sp.getString(KEY_APPEND_TEXT, "喵");
        cfg.enableRandomEmoticon = sp.getBoolean(KEY_ENABLE_EMOTICON, true);
        cfg.processingMode = sp.getString(KEY_PROCESSING_MODE, MODE_PUNCTUATION);
        cfg.processingEnabled = sp.getBoolean(KEY_PROCESSING_ENABLED, true);
        cfg.shizukuFallbackEnabled = sp.getBoolean(KEY_SHIZUKU_FALLBACK, true);

        // v1.1：悬浮窗外观与振动（越界值一律钳制，防止手改存储导致异常）
        cfg.floatSize = clampFloatSize(sp.getFloat(KEY_FLOAT_SIZE, 1.0f));
        cfg.floatAlpha = clampFloatAlpha(sp.getInt(KEY_FLOAT_ALPHA, 100));
        cfg.floatSnap = sp.getBoolean(KEY_FLOAT_SNAP, true);
        cfg.floatX = sp.getInt(KEY_FLOAT_X, FLOAT_POS_UNSET);
        cfg.floatY = sp.getInt(KEY_FLOAT_Y, FLOAT_POS_UNSET);
        cfg.enableVibrate = sp.getBoolean(KEY_ENABLE_VIBRATE, true);

        String rulesStr = sp.getString(KEY_RULES, "");
        if (rulesStr != null && !rulesStr.trim().isEmpty()) {
            List<Rule> list = new ArrayList<>();
            for (String line : rulesStr.split("\n")) {
                Rule r = parseRule(line);
                if (r != null) {
                    list.add(r);
                }
            }
            cfg.rules = list;
        }

        String custom = sp.getString(KEY_CUSTOM_EMOTICONS, "");
        if (custom != null && !custom.trim().isEmpty()) {
            List<String> list = new ArrayList<>();
            for (String s : custom.split("\n")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
            cfg.customEmoticons = list.toArray(new String[0]);
        } else {
            cfg.customEmoticons = new String[0];
        }

        // 读取目标包名列表（独立于 custom 分支）
        // 用 null 区分「从未保存过」和「用户主动清空」：
        // 只有从未保存过（首次安装）才填默认 QQ+微信；
        // 用户删空列表后再进来，必须保持空——否则删掉的项会自己复活。
        String pkgStr = sp.getString(KEY_TARGET_PACKAGES, null);
        cfg.targetPackages = new ArrayList<>();
        if (pkgStr == null) {
            cfg.targetPackages.add("com.tencent.mobileqq");
            cfg.targetPackages.add("com.tencent.mm");
        } else if (!pkgStr.isEmpty()) {
            cfg.targetPackages.addAll(Arrays.asList(pkgStr.split(",")));
        }

        return cfg;
    }

    public void save(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor ed = sp.edit();
        ed.putBoolean(KEY_ENABLE_APPEND, this.enableAppend);
        ed.putString(KEY_APPEND_TEXT, this.appendText == null ? "" : this.appendText);
        ed.putBoolean(KEY_ENABLE_EMOTICON, this.enableRandomEmoticon);
        ed.putString(KEY_PROCESSING_MODE, this.processingMode == null ? MODE_PUNCTUATION : this.processingMode);
        ed.putBoolean(KEY_PROCESSING_ENABLED, this.processingEnabled);
        ed.putBoolean(KEY_SHIZUKU_FALLBACK, this.shizukuFallbackEnabled);

        // v1.1：悬浮窗外观与振动
        ed.putFloat(KEY_FLOAT_SIZE, clampFloatSize(this.floatSize));
        ed.putInt(KEY_FLOAT_ALPHA, clampFloatAlpha(this.floatAlpha));
        ed.putBoolean(KEY_FLOAT_SNAP, this.floatSnap);
        ed.putInt(KEY_FLOAT_X, this.floatX);
        ed.putInt(KEY_FLOAT_Y, this.floatY);
        ed.putBoolean(KEY_ENABLE_VIBRATE, this.enableVibrate);
        ed.putString(KEY_RULES, rulesToString(this.rules));
        ed.putString(KEY_CUSTOM_EMOTICONS, join(this.customEmoticons, "\n"));

        // 保存目标包名列表（用逗号连接）
        // 注意：空列表也要存成空字符串，这样 load 时能区分「用户清空」与「从未保存」
        StringBuilder pkgBuilder = new StringBuilder();
        if (targetPackages != null) {
            for (String pkg : targetPackages) {
                if (pkgBuilder.length() > 0) pkgBuilder.append(",");
                pkgBuilder.append(pkg);
            }
        }
        ed.putString(KEY_TARGET_PACKAGES, pkgBuilder.toString());
        ed.apply();
    }

    public String[] getActiveEmoticons() {
        if (this.customEmoticons != null && this.customEmoticons.length > 0) {
            return this.customEmoticons;
        }
        return BUILTIN_EMOTICONS;
    }

    private static String join(String[] arr, String delim) {
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (int i = 0; i < arr.length; i++) {
                String s = arr[i];
                if (s == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(delim);
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
