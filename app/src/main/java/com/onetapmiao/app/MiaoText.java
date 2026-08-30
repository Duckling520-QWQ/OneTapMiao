package com.onetapmiao.app;

/**
 * 文本预处理工具。
 * 作用：把「上一次已经追加过的内容」从文本尾部剥离，得到用户真正输入的原文。
 *
 * 为什么需要它：
 *   一键加喵是可以反复点的。如果第一次点完变成「你好喵 (^ω^ฅ)」，
 *   第二次点的时候如果直接拿全文去 TextProcessor.process，就会变成
 *   「你好喵喵 (^ω^ฅ) (=´ᴥ`)」——越点越长。
 *   所以每次处理前先剥离，保证幂等：点 N 次的结果和点 1 次结构一致，
 *   只是颜文字会重新随机（这个行为是故意保留的，等于「换一个表情」）。
 *
 * 注意：
 *   QQAccessibilityService 里有一份私有的 stripAll()，逻辑与本类一致。
 *   那份是无障碍通道专用的，保持不变（避免动已经跑通的 QQ 全自动逻辑）；
 *   新的一键加喵通道统一用本类。
 */
public final class MiaoText {

    private MiaoText() {
    }

    /** 取当前生效的追加词（配置为空时兜底为「喵」） */
    public static String defaultAppendText(CatConfig cfg) {
        if (cfg != null && cfg.appendText != null && !cfg.appendText.isEmpty()) {
            return cfg.appendText;
        }
        return "喵";
    }

    /**
     * 剥离文本尾部所有已追加的内容（颜文字 + 追加词 + 尾部非正文符号）。
     *
     * @param text 输入框里的全文
     * @param cfg  当前配置（可为 null，此时按默认「喵」处理）
     * @return 用户真正输入的原文
     */
    public static String stripAll(String text, CatConfig cfg) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        String appendText = defaultAppendText(cfg);

        for (int i = 0; i < lines.length; i++) {
            sb.append(stripOneLine(lines[i], cfg, appendText));
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static String stripOneLine(String line, CatConfig cfg, String appendText) {
        String s = line.trim();

        // ---- 阶段一：剥尾部（颜文字 / 追加词 / 孤立符号），循环到剥不动 ----
        int guard = 0;
        boolean changed;
        do {
            changed = false;

            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return "";
            }

            // 1) 剥自定义颜文字
            String hit = matchEmoticon(trimmed, (cfg != null) ? cfg.getActiveEmoticons() : null);
            if (hit == null) {
                hit = matchEmoticon(trimmed, CatConfig.BUILTIN_EMOTICONS);
            }
            if (hit != null) {
                s = trimmed.substring(0, trimmed.length() - hit.length()).trim();
                changed = true;
            } else if (!appendText.isEmpty() && trimmed.endsWith(appendText)) {
                // 2) 尾部就是追加词（例如没有标点的「你好喵」）
                s = trimmed.substring(0, trimmed.length() - appendText.length()).trim();
                changed = true;
            } else {
                // 3) 兜底：尾部残留的非正文字符清掉
                String cleaned = trimmed.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9，。！？,.!?\\s]+$", "");
                if (!cleaned.equals(trimmed)) {
                    s = cleaned.trim();
                    changed = true;
                }
            }
        } while (changed && ++guard < 32);

        // ---- 阶段二（关键）：剥「标点前」的追加词 ----
        // TextProcessor.appendPerSentence 把追加词插在分句正文之后、标点之前，
        // 例如「你好。」处理完是「你好喵。」——尾部是「。」，阶段一剥不到。
        // 不剥掉的话，每触发一次就多一个追加词（喵 → 喵喵 → 喵喵喵）。
        return stripAppendBeforePunctuation(s, appendText);
    }

    /**
     * 按标点切段，剥掉每段末尾的追加词；分隔符原样保留。
     */
    private static String stripAppendBeforePunctuation(String s, String ap) {
        if (ap.isEmpty() || s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        StringBuilder seg = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSeparator(c)) {
                out.append(stripTail(seg.toString(), ap, 16)).append(c);
                seg.setLength(0);
            } else {
                seg.append(c);
            }
        }
        out.append(stripTail(seg.toString(), ap, 16));
        return out.toString();
    }

    private static String stripTail(String seg, String ap, int maxTimes) {
        String piece = seg;
        int n = 0;
        while (piece.endsWith(ap) && piece.length() > ap.length() && n < maxTimes) {
            piece = piece.substring(0, piece.length() - ap.length());
            n++;
        }
        return piece;
    }

    private static boolean isSeparator(char c) {
        return c == '，' || c == ',' || c == '。' || c == '！' || c == '!'
                || c == '？' || c == '?' || Character.isWhitespace(c);
    }

    /** 返回命中的颜文字（用于从尾部剥除），没命中返回 null */
    private static String matchEmoticon(String trimmed, String[] emoticons) {
        if (emoticons == null) {
            return null;
        }
        for (String emo : emoticons) {
            if (emo != null && !emo.isEmpty() && trimmed.endsWith(emo)) {
                return emo;
            }
        }
        return null;
    }

    /**
     * 判断一段文本看起来是否已经处理过（尾部带追加词或颜文字）。
     * 仅用于日志提示，不影响处理逻辑——处理逻辑一律先 strip 再 process。
     */
    public static boolean looksProcessed(String text, CatConfig cfg) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String t = text.trim();

        String ap = defaultAppendText(cfg);
        if (!ap.isEmpty() && t.endsWith(ap)) {
            return true;
        }

        String[] active = (cfg != null) ? cfg.getActiveEmoticons() : CatConfig.BUILTIN_EMOTICONS;
        if (active != null) {
            for (String emo : active) {
                if (emo != null && !emo.isEmpty() && t.endsWith(emo)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 长文本缩写，用于日志（避免刷屏） */
    public static String abbrev(CharSequence cs, int max) {
        if (cs == null) {
            return "null";
        }
        String s = cs.toString();
        s = s.replace('\n', '⏎');
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…(" + s.length() + ")";
    }
}
