package com.dsh.deviceinfo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备信息 · 自研版 —— 纯 Java + 原生 View，无第三方依赖。
 * 所有采集在后台线程完成，主线程只做渲染；每个分区独立容错。
 */
public class MainActivity extends Activity {

    private LinearLayout cards;
    private TextView status;
    private Button refreshBtn;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { pool.shutdownNow(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ UI 搭建
    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // 顶栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(16), dp(14), dp(8));

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(getColor(R.color.title));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        refreshBtn = new Button(this);
        refreshBtn.setText(getString(R.string.refresh));
        refreshBtn.setAllCaps(false);
        refreshBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        refreshBtn.setTextColor(getColor(R.color.title));
        refreshBtn.setBackgroundResource(R.drawable.btn_bg);
        refreshBtn.setMinHeight(0);
        refreshBtn.setMinimumHeight(0);
        refreshBtn.setPadding(dp(20), dp(6), dp(20), dp(6));
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        header.addView(refreshBtn);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText(getString(R.string.hint_copy));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setTextColor(getColor(R.color.label));
        status.setPadding(dp(18), 0, dp(18), dp(8));
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(dp(14), dp(4), dp(14), dp(20));
        sv.addView(cards, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ------------------------------------------------------------ 采集与渲染
    private void reload() {
        if (loading) return;
        loading = true;
        if (refreshBtn != null) refreshBtn.setEnabled(false);
        if (status != null) status.setText(getString(R.string.collecting));
        pool.execute(new Runnable() {
            @Override public void run() {
                final long t0 = System.currentTimeMillis();
                LinkedHashMap<String, LinkedHashMap<String, String>> data = null;
                final String[] err = new String[1];
                try {
                    data = Collector.collect(MainActivity.this);
                } catch (Throwable t) {
                    err[0] = t.getClass().getSimpleName() + "：" + String.valueOf(t.getMessage());
                }
                final LinkedHashMap<String, LinkedHashMap<String, String>> d = data;
                final long cost = System.currentTimeMillis() - t0;
                ui.post(new Runnable() {
                    @Override public void run() {
                        try {
                            render(d, cost, err[0]);
                        } catch (Throwable t) {
                            if (status != null) {
                                status.setText("渲染异常：" + t.getClass().getSimpleName());
                            }
                        } finally {
                            loading = false;
                            if (refreshBtn != null) refreshBtn.setEnabled(true);
                        }
                    }
                });
            }
        });
    }

    private void render(LinkedHashMap<String, LinkedHashMap<String, String>> data,
                        long cost, String err) {
        cards.removeAllViews();
        if (err != null) {
            TextView tv = new TextView(this);
            tv.setText("采集失败：" + err);
            tv.setTextColor(getColor(R.color.value));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cards.addView(tv);
        }
        int items = 0;
        if (data != null) {
            for (Map.Entry<String, LinkedHashMap<String, String>> e : data.entrySet()) {
                LinearLayout card = buildCard(e.getKey());
                for (Map.Entry<String, String> row : e.getValue().entrySet()) {
                    addRow(card, row.getKey(), row.getValue());
                    items++;
                }
                cards.addView(card);
            }
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        String ts = f.format(new java.util.Date());
        status.setText("共 " + items + " 项 · 采集于 " + ts + " · 耗时 " + cost + " ms · "
                + getString(R.string.hint_copy));
    }

    private LinearLayout buildCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(11);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.accent));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);
        return card;
    }

    private void addRow(LinearLayout card, String label, String value) {
        if (value == null) value = "—";

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(9), 0, 0);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        l.setTextColor(getColor(R.color.label));
        row.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        v.setTextColor(getColor(R.color.value));
        v.setLineSpacing(dp(2), 1f);
        v.setTextIsSelectable(true);
        final String labelFinal = label;
        final TextView vFinal = v;
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                copy(labelFinal, vFinal.getText().toString());
                return true;
            }
        });
        row.addView(v);
        card.addView(row);
    }

    private void copy(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(this, "已复制：" + label, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
