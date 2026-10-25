package com.aipaste.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 画面上の「貼」ボタンから、表示中アプリの入力欄へ項目をまとめて入力する。 */
public class FillService extends AccessibilityService {

    private static final int SKIP = -1;
    private static FillService instance;

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View panel;
    private FrameLayout markers;

    private List<Items.Item> items = new ArrayList<>();
    private final List<AccessibilityNodeInfo> fields = new ArrayList<>();
    private int[] assign = new int[0];
    private int nextIndex;

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showBubble();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        closePanel();
        if (bubble != null) wm.removeView(bubble);
        bubble = null;
        instance = null;
        return super.onUnbind(intent);
    }

    // ---------- フローティングボタン ----------

    private void showBubble() {
        bubble = new TextView(this);
        bubble.setText("貼");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(20);
        bubble.setTypeface(null, Typeface.BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E0FF6D00"));
        bg.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(bg);
        bubble.setElevation(dp(6));

        bubbleLp = overlayParams(dp(52), dp(52),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = Items.prefs(this).getInt("bubbleX", dp(8));
        bubbleLp.y = Items.prefs(this).getInt("bubbleY", dp(240));

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            final Runnable longPress = () -> {
                moved = true;
                Intent i = new Intent(FillService.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        moved = false;
                        v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) {
                            moved = true;
                            v.removeCallbacks(longPress);
                        }
                        if (moved) {
                            bubbleLp.x = startX + (int) dx;
                            bubbleLp.y = startY + (int) dy;
                            wm.updateViewLayout(bubble, bubbleLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.removeCallbacks(longPress);
                        if (moved) {
                            Items.prefs(FillService.this).edit()
                                    .putInt("bubbleX", bubbleLp.x).putInt("bubbleY", bubbleLp.y).apply();
                        } else {
                            togglePanel();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(longPress);
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
    }

    // ---------- パネル ----------

    private void togglePanel() {
        if (panel != null) {
            closePanel();
        } else {
            openPanel();
        }
    }

    private void openPanel() {
        items = Items.load(this);
        if (items.isEmpty()) {
            toast("項目がありません。長押しでアプリを開き、AIの出力を読み込んでください");
            return;
        }
        scanFields();
        assignDefault();
        nextIndex = 0;
        showMarkers();
        showPanel();
    }

    private void closePanel() {
        if (panel != null) wm.removeView(panel);
        if (markers != null) wm.removeView(markers);
        panel = null;
        markers = null;
        for (AccessibilityNodeInfo n : fields) n.recycle();
        fields.clear();
    }

    private void refreshPanel() {
        if (panel != null) wm.removeView(panel);
        if (markers != null) wm.removeView(markers);
        panel = null;
        markers = null;
        showMarkers();
        showPanel();
    }

    private void showPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5FFFFFF"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#3F51B5"));
        root.setBackground(bg);
        root.setElevation(dp(8));

        TextView head = text("入力欄 " + fields.size() + "件 ／ 項目 " + items.size() + "件", 15, "#303F9F");
        head.setTypeface(null, Typeface.BOLD);
        root.addView(head);

        if (fields.isEmpty()) {
            root.addView(text("この画面に入力欄が見つかりません。入力欄が表示された状態で「再検出」してください。", 13, "#C62828"));
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < fields.size(); i++) list.addView(fieldRow(i));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        list.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.min(maxH, list.getMeasuredHeight())));

        LinearLayout row1 = new LinearLayout(this);
        Button fillAll = button("一括貼り付け", "#FF6D00");
        fillAll.setOnClickListener(v -> fillAll());
        Button rescan = button("再検出", "#5C6BC0");
        rescan.setOnClickListener(v -> {
            for (AccessibilityNodeInfo n : fields) n.recycle();
            fields.clear();
            items = Items.load(this);
            scanFields();
            assignDefault();
            refreshPanel();
        });
        row1.addView(fillAll, weight(2));
        row1.addView(rescan, weight(1));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        String nextLabel = nextIndex < items.size()
                ? "選択中の欄に次を貼る（" + (nextIndex + 1) + "/" + items.size() + "）"
                : "選択中の欄に次を貼る（完了）";
        Button next = button(nextLabel, "#26A69A");
        next.setOnClickListener(v -> pasteNextIntoFocused());
        Button close = button("閉じる", "#9E9E9E");
        close.setOnClickListener(v -> closePanel());
        row2.addView(next, weight(2));
        row2.addView(close, weight(1));
        root.addView(row2);

        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        lp.gravity = Gravity.BOTTOM;
        lp.horizontalMargin = 0.02f;
        lp.verticalMargin = 0.01f;
        panel = root;
        wm.addView(panel, lp);
    }

    private View fieldRow(int i) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(i + 1));
        TextView desc = text(" " + describe(fields.get(i)), 12, "#555555");
        desc.setSingleLine(true);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(desc);
        row.addView(top);

        LinearLayout pick = new LinearLayout(this);
        pick.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = button("◀", "#E8EAF6");
        prev.setTextColor(Color.parseColor("#303F9F"));
        Button nxt = button("▶", "#E8EAF6");
        nxt.setTextColor(Color.parseColor("#303F9F"));
        TextView value = text(assignLabel(assign[i]), 13, "#222222");
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPadding(dp(6), 0, dp(6), 0);
        prev.setOnClickListener(v -> {
            assign[i] = assign[i] <= SKIP ? items.size() - 1 : assign[i] - 1;
            value.setText(assignLabel(assign[i]));
        });
        nxt.setOnClickListener(v -> {
            assign[i] = assign[i] >= items.size() - 1 ? SKIP : assign[i] + 1;
            value.setText(assignLabel(assign[i]));
        });
        pick.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(40)));
        pick.addView(value, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        pick.addView(nxt, new LinearLayout.LayoutParams(dp(44), dp(40)));
        row.addView(pick);
        return row;
    }

    private String assignLabel(int idx) {
        if (idx == SKIP) return "（スキップ）";
        Items.Item it = items.get(idx);
        String preview = it.content.replace('\n', ' ');
        if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
        return (idx + 1) + ". " + it.title(idx) + "：" + preview;
    }

    // ---------- 入力欄の番号表示 ----------

    private void showMarkers() {
        markers = new FrameLayout(this);
        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(markers, lp);
        // オーバーレイ自体の画面上の位置を差し引いて座標を合わせる
        markers.post(() -> {
            if (markers == null) return;
            int[] origin = new int[2];
            markers.getLocationOnScreen(origin);
            Rect r = new Rect();
            for (int i = 0; i < fields.size(); i++) {
                fields.get(i).getBoundsInScreen(r);
                r.offset(-origin[0], -origin[1]);

                View frame = new View(this);
                GradientDrawable fb = new GradientDrawable();
                fb.setColor(Color.parseColor("#22FF6D00"));
                fb.setStroke(dp(2), Color.parseColor("#FF6D00"));
                fb.setCornerRadius(dp(4));
                frame.setBackground(fb);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(r.width(), r.height());
                flp.leftMargin = r.left;
                flp.topMargin = r.top;
                markers.addView(frame, flp);

                TextView b = badge(i + 1);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                blp.leftMargin = Math.max(0, r.left - dp(6));
                blp.topMargin = Math.max(0, r.top - dp(10));
                markers.addView(b, blp);
            }
        });
    }

    // ---------- 入力欄の検出 ----------

    private void scanFields() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || getPackageName().contentEquals(nullToEmpty(root.getPackageName()))) {
            if (root != null) root.recycle();
            root = null;
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (getPackageName().contentEquals(nullToEmpty(r.getPackageName()))) {
                    r.recycle();
                    continue;
                }
                root = r;
                break;
            }
        }
        if (root == null) return;
        collect(root);
        fields.sort((a, b) -> {
            Rect ra = new Rect(), rb = new Rect();
            a.getBoundsInScreen(ra);
            b.getBoundsInScreen(rb);
            // ほぼ同じ高さなら左から順
            if (Math.abs(ra.top - rb.top) > dp(8)) return Integer.compare(ra.top, rb.top);
            return Integer.compare(ra.left, rb.left);
        });
    }

    private void collect(AccessibilityNodeInfo node) {
        if (node == null) return;
        boolean keep = false;
        if (node.isEditable() && node.isVisibleToUser() && node.isEnabled()) {
            fields.add(node);
            keep = true;
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i));
        if (!keep) node.recycle();
    }

    private void assignDefault() {
        assign = new int[fields.size()];
        Arrays.fill(assign, SKIP);
        boolean[] used = new boolean[items.size()];
        // 項目名と入力欄のヒントが一致するものを優先
        for (int f = 0; f < fields.size(); f++) {
            String d = describe(fields.get(f));
            for (int k = 0; k < items.size(); k++) {
                String label = items.get(k).label;
                if (used[k] || label.isEmpty()) continue;
                if (d.contains(label)) {
                    assign[f] = k;
                    used[k] = true;
                    break;
                }
            }
        }
        // 残りは上から順に割り当て
        int k = 0;
        for (int f = 0; f < fields.size(); f++) {
            if (assign[f] != SKIP) continue;
            while (k < items.size() && used[k]) k++;
            if (k >= items.size()) break;
            assign[f] = k;
            used[k] = true;
        }
    }

    private String describe(AccessibilityNodeInfo n) {
        CharSequence hint = n.getHintText();
        if (!TextUtils.isEmpty(hint)) return hint.toString();
        if (n.isPassword()) return "（パスワード欄）";
        CharSequence cd = n.getContentDescription();
        if (!TextUtils.isEmpty(cd)) return cd.toString();
        String id = n.getViewIdResourceName();
        if (!TextUtils.isEmpty(id)) return id.substring(id.lastIndexOf('/') + 1);
        CharSequence t = n.getText();
        if (!TextUtils.isEmpty(t)) return "入力済み：" + t;
        return "入力欄";
    }

    // ---------- 貼り付け ----------

    private void fillAll() {
        int ok = 0, ng = 0;
        for (int f = 0; f < fields.size(); f++) {
            if (assign[f] == SKIP) continue;
            if (setText(fields.get(f), items.get(assign[f]).content)) ok++;
            else ng++;
        }
        toast(ok + "件を貼り付けました" + (ng > 0 ? "（" + ng + "件失敗）" : ""));
        closePanel();
    }

    private void pasteNextIntoFocused() {
        if (nextIndex >= items.size()) {
            nextIndex = 0;
            toast("最初の項目に戻りました");
            refreshPanel();
            return;
        }
        AccessibilityNodeInfo target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null || !target.isEditable()) {
            toast("貼り付け先の入力欄をタップしてから押してください");
            return;
        }
        Items.Item it = items.get(nextIndex);
        if (setText(target, it.content)) {
            toast("「" + it.title(nextIndex) + "」を貼り付けました");
            nextIndex++;
        } else {
            toast("貼り付けに失敗しました");
        }
        target.recycle();
        refreshPanel();
    }

    private boolean setText(AccessibilityNodeInfo node, String text) {
        node.refresh();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
        // SET_TEXT 非対応の欄はクリップボード経由で貼り付け
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("AI一括ペースト", text));
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    // ---------- 小物 ----------

    private WindowManager.LayoutParams overlayParams(int w, int h, int flags) {
        return new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
    }

    private TextView badge(int n) {
        TextView b = new TextView(this);
        b.setText(String.valueOf(n));
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(22));
        b.setPadding(dp(5), dp(1), dp(5), dp(1));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#FF6D00"));
        g.setCornerRadius(dp(11));
        b.setBackground(g);
        return b;
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private Button button(String label, String color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), w);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        return lp;
    }

    private static CharSequence nullToEmpty(CharSequence s) {
        return s == null ? "" : s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
