package com.flyingmouse.format;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flyingmouse.format.util.FormatKit;

import java.util.List;

/**
 * 可转换格式汇总页：分组展示所有支持的输入格式与可转换目标。
 * 数据由 FormatKit.summaries() 动态生成，与转换格式表保持一致。
 */
public class FormatsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FFF7F0"));

        // ---- 顶部栏 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.parseColor("#E95F6D"));
        header.setPadding(dp(8), dp(12), dp(8), dp(12));

        TextView back = new TextView(this);
        back.setText(R.string.back);
        back.setTextColor(Color.WHITE);
        back.setTextSize(16f);
        back.setPadding(dp(10), dp(6), dp(10), dp(6));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText(R.string.formats_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(dp(6), 0, 0, 0);
        header.addView(title);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 滚动内容 ----
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));

        boolean firstGroup = true;
        for (FormatKit.SummaryGroup g : FormatKit.summaries()) {
            // 分组标题
            TextView gt = new TextView(this);
            gt.setText(g.title);
            gt.setTextColor(Color.parseColor("#E95F6D"));
            gt.setTextSize(16f);
            gt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams gtp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gtp.topMargin = dp(firstGroup ? 0 : 14);
            gtp.bottomMargin = dp(6);
            content.addView(gt, gtp);
            firstGroup = false;

            // 分组卡片
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.card_round);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));

            for (String line : g.lines) {
                TextView row = new TextView(this);
                int arrow = line.indexOf("→");
                if (arrow > 0) {
                    // 源格式高亮 + 目标灰色，同段拼接更紧凑
                    row.setTextColor(Color.parseColor("#3A2E2E"));
                    row.setTextSize(14f);
                    row.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    row.setText(line.substring(0, arrow).trim());
                    TextView rest = new TextView(this);
                    rest.setText(line.substring(arrow));
                    rest.setTextColor(Color.parseColor("#8A7A7A"));
                    rest.setTextSize(14f);
                    rest.setPadding(0, 0, 0, dp(6));
                    // 用横向行把两段拼起来
                    LinearLayout rowWrap = new LinearLayout(this);
                    rowWrap.setOrientation(LinearLayout.HORIZONTAL);
                    rowWrap.setGravity(Gravity.CENTER_VERTICAL);
                    rowWrap.addView(row);
                    rowWrap.addView(rest);
                    card.addView(rowWrap);
                } else {
                    row.setText(line);
                    row.setTextColor(Color.parseColor("#3A2E2E"));
                    row.setTextSize(14f);
                    row.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    row.setPadding(0, 0, 0, dp(6));
                    card.addView(row);
                }
            }
            content.addView(card);
        }

        // 底部说明
        TextView note = new TextView(this);
        note.setText("提示：选中文件后，主页会自动列出该文件可转换的目标格式；\n多张图片可合并为一个 PDF。");
        note.setTextColor(Color.parseColor("#8A7A7A"));
        note.setTextSize(12f);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(14);
        content.addView(note, np);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
