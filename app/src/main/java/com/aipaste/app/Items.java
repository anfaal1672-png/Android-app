package com.aipaste.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI の出力を項目に分解し、アプリとサービスで共有する。 */
public final class Items {

    public static final class Item {
        public final String label;
        public final String content;

        Item(String label, String content) {
            this.label = label;
            this.content = content;
        }

        public String title(int index) {
            return label.isEmpty() ? "項目" + (index + 1) : label;
        }
    }

    private static final String PREFS = "items";

    // 行頭の 【項目名】 または [[項目名]] を区切りとみなす
    private static final Pattern MARKER = Pattern.compile(
            "^\\s*(?:【([^】]{0,60})】|\\[\\[([^\\]]{0,60})\\]\\])[ \\t:：]*(.*)$");
    private static final Pattern SEPARATOR = Pattern.compile("^\\s*(-{3,}|={3,})\\s*$");

    private Items() {}

    public static List<Item> parse(String text) {
        List<Item> result = new ArrayList<>();
        if (text == null) return result;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        boolean hasMarker = false;
        for (String line : lines) {
            if (MARKER.matcher(line).matches()) {
                hasMarker = true;
                break;
            }
        }

        if (hasMarker) {
            String label = null;
            StringBuilder body = new StringBuilder();
            for (String line : lines) {
                if (line.trim().startsWith("```")) continue;
                Matcher m = MARKER.matcher(line);
                if (m.matches()) {
                    if (label != null) result.add(new Item(label, trimBlankLines(body.toString())));
                    label = (m.group(1) != null ? m.group(1) : m.group(2)).trim();
                    body.setLength(0);
                    if (!m.group(3).trim().isEmpty()) body.append(m.group(3)).append('\n');
                } else if (label != null) {
                    body.append(line).append('\n');
                }
            }
            if (label != null) result.add(new Item(label, trimBlankLines(body.toString())));
            return result;
        }

        // 印がない場合: --- / === の区切り線、それも無ければ 1 行 = 1 項目
        boolean hasSeparator = false;
        for (String line : lines) {
            if (SEPARATOR.matcher(line).matches()) {
                hasSeparator = true;
                break;
            }
        }
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (line.trim().startsWith("```")) continue;
            if (hasSeparator) {
                if (SEPARATOR.matcher(line).matches()) {
                    addIfNotEmpty(result, body.toString());
                    body.setLength(0);
                } else {
                    body.append(line).append('\n');
                }
            } else {
                addIfNotEmpty(result, line);
            }
        }
        if (hasSeparator) addIfNotEmpty(result, body.toString());
        return result;
    }

    private static void addIfNotEmpty(List<Item> list, String s) {
        String t = trimBlankLines(s);
        if (!t.isEmpty()) list.add(new Item("", t));
    }

    private static String trimBlankLines(String s) {
        return s.replaceAll("^(\\s*\\n)+", "").replaceAll("\\s+$", "");
    }

    public static boolean looksFormatted(String text) {
        if (text == null) return false;
        for (String line : text.split("\n")) {
            if (MARKER.matcher(line).matches()) return true;
        }
        return false;
    }

    /** AI への依頼に付け足す、出力形式の指示文。 */
    public static final String PROMPT =
            "この後の依頼への回答は、入力フォームの各欄に項目ごとに貼り付けて使います。次のルールを必ず守って出力してください。\n"
            + "\n"
            + "＃出力ルール\n"
            + "・各項目は、行頭に【項目名】だけを書いた行で始め、次の行から内容を書く\n"
            + "・項目は、貼り付ける入力欄の上から順に並べる\n"
            + "・項目名は、入力欄の名前がわかる場合はそれと同じにする\n"
            + "・内容はそのまま貼れる完成した文章にする（「ここに〇〇を入れる」などの説明や仮の文字は書かない）\n"
            + "・回答は【】で始まる項目だけにする。前置き・補足・締めの言葉は書かない\n"
            + "・コードブロック（```）や **、# などの装飾記号は使わない（改行はOK）\n"
            + "・内容の中では【】を使わない\n"
            + "\n"
            + "＃出力例\n"
            + "【タイトル】\n"
            + "秋限定 さつまいもモンブラン\n"
            + "【本文】\n"
            + "今年も秋限定のモンブランが登場しました。\n"
            + "なめらかなさつまいもクリームをたっぷり使っています。\n"
            + "【タグ】\n"
            + "#モンブラン #秋スイーツ #期間限定\n"
            + "\n"
            + "＃依頼\n";

    public static void save(Context ctx, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                arr.put(new JSONObject().put("l", it.label).put("c", it.content));
            }
        } catch (JSONException ignored) {
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply();
    }

    public static List<Item> load(Context ctx) {
        List<Item> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Item(o.optString("l"), o.optString("c")));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
