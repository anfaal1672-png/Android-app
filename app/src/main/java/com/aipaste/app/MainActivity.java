package com.aipaste.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private EditText fieldNames;
    private TextView promptPreview;
    private EditText rawInput;
    private TextView resultTitle;
    private LinearLayout itemList;
    private TextView serviceStatus;
    private String lastAutoImported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fieldNames = findViewById(R.id.fieldNames);
        promptPreview = findViewById(R.id.promptPreview);
        rawInput = findViewById(R.id.rawInput);
        resultTitle = findViewById(R.id.resultTitle);
        itemList = findViewById(R.id.itemList);
        serviceStatus = findViewById(R.id.serviceStatus);

        fieldNames.setText(Items.prefs(this).getString("fieldNames", ""));
        fieldNames.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                Items.prefs(MainActivity.this).edit().putString("fieldNames", s.toString()).apply();
                updatePrompt();
            }
        });
        updatePrompt();

        findViewById(R.id.copyPrompt).setOnClickListener(v -> {
            copy("指示文", promptPreview.getText().toString());
            toast("指示文をコピーしました");
        });
        findViewById(R.id.fromClipboard).setOnClickListener(v -> {
            String clip = readClipboard();
            if (TextUtils.isEmpty(clip)) {
                toast("クリップボードが空です");
                return;
            }
            importText(clip);
        });
        findViewById(R.id.parse).setOnClickListener(v -> importText(rawInput.getText().toString()));
        findViewById(R.id.openSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        showItems(Items.load(this));
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = FillService.isRunning();
        serviceStatus.setText(on ? "● フローティングボタン：有効" : "○ フローティングボタン：無効（下のボタンから有効にしてください）");
        serviceStatus.setTextColor(on ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android 10 以降はフォーカス取得後でないとクリップボードを読めない
        if (!hasFocus) return;
        String clip = readClipboard();
        if (clip != null && !clip.equals(lastAutoImported) && Items.looksFormatted(clip)
                && !clip.equals(Items.prefs(this).getString("lastRaw", null))) {
            lastAutoImported = clip;
            importText(clip);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        CharSequence text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (text != null) {
            importText(text.toString());
            setIntent(new Intent(this, MainActivity.class));
        }
    }

    private void importText(String text) {
        List<Items.Item> items = Items.parse(text);
        if (items.isEmpty()) {
            toast("項目が見つかりませんでした");
            return;
        }
        rawInput.setText(text);
        Items.save(this, items);
        Items.prefs(this).edit().putString("lastRaw", text).apply();
        showItems(items);
        toast(items.size() + "件の項目に分けました");
    }

    private void showItems(List<Items.Item> items) {
        resultTitle.setText("③ 分割された項目（" + items.size() + "件）");
        itemList.removeAllViews();
        int pad = dp(10);
        for (int i = 0; i < items.size(); i++) {
            Items.Item it = items.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(8));
            card.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            card.setLayoutParams(lp);

            TextView title = new TextView(this);
            title.setText((i + 1) + ". " + it.title(i));
            title.setTextColor(Color.parseColor("#3F51B5"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView body = new TextView(this);
            body.setText(it.content);
            body.setTextColor(Color.parseColor("#222222"));
            body.setMaxLines(6);
            body.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(title);
            card.addView(body);

            final int idx = i;
            card.setOnClickListener((View v) -> {
                copy(it.title(idx), it.content);
                toast("「" + it.title(idx) + "」をコピーしました");
            });
            itemList.addView(card);
        }
    }

    private void updatePrompt() {
        promptPreview.setText(Items.buildPrompt(fieldNames.getText().toString()));
    }

    private String readClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        return t == null ? null : t.toString();
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
