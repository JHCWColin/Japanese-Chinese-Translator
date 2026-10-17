package com.jhcwcolin.jctranslator;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jhcwcolin.jctranslator.data.HistoryDb;
import com.jhcwcolin.jctranslator.data.HistoryEntry;
import com.jhcwcolin.jctranslator.ui.BaseActivity;
import com.jhcwcolin.jctranslator.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Translation history. Both source and translation are stored, so opening an entry just shows
 * the saved text — nothing is translated again. Paged instead of scrolled (friendlier to e-ink).
 */
public class HistoryActivity extends BaseActivity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private HistoryDb db;
    private TranslatorController ctl;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private String query = "";
    private int page = 0;
    private HistoryEntry detail; // non-null while the detail view is shown
    private boolean confirmClear;

    private EditText searchBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = HistoryDb.get(this);
        ctl = TranslatorController.get(this);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (detail != null) {
            detail = null;
            showList();
        } else {
            super.onBackPressed();
        }
    }

    private LinearLayout newRoot() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg());
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        return root;
    }

    private void hideIme(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------------------------------
    // List view
    // ---------------------------------------------------------------------------------------

    private void showList() {
        final Context c = this;
        final int ui = Prefs.uiSizeSp();
        final int gap = Ui.dp(c, 8);
        final int pageSize = Math.max(1, Prefs.historyPageSize());

        final int total = db.count(query);
        final int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }

        final LinearLayout root = newRoot();

        // header
        LinearLayout header = Ui.row(c);
        header.addView(Ui.iconButton(c, R.drawable.ic_arrow_left, "返回", 26, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), Ui.lpMargins(WRAP, WRAP, 0, 0, 0, gap, 0));
        header.addView(Ui.label(c, "翻译历史", ui + 6, true), Ui.lp(0, WRAP, 1f));
        final TextView clear = Ui.button(c, confirmClear ? "再点一次确认清空" : "清空全部", ui, null);
        clear.setEnabled(total > 0);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!confirmClear) {
                    confirmClear = true;
                    showList();
                } else {
                    confirmClear = false;
                    db.clear();
                    page = 0;
                    showList();
                }
            }
        });
        header.addView(clear, Ui.lp(WRAP, WRAP));
        root.addView(header, Ui.lp(MATCH, WRAP));
        root.addView(Ui.divider(c, 3), Ui.lpMargins(MATCH, 0, 0, 0, gap, 0, gap));

        // search
        LinearLayout searchRow = Ui.row(c);
        searchBox = new EditText(c);
        Ui.styleEdit(c, searchBox, ui + 2);
        searchBox.setHint("搜索原文或译文");
        searchBox.setSingleLine(true);
        searchBox.setText(query);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                applySearch();
                return true;
            }
        });
        searchRow.addView(searchBox, Ui.lpMargins(0, Ui.dp(c, 52), 1f, 0, 0, gap, 0));
        searchRow.addView(Ui.button(c, "搜索", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applySearch();
            }
        }), Ui.lp(WRAP, WRAP));
        if (!query.isEmpty()) {
            searchRow.addView(Ui.button(c, "全部", ui, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideIme(searchBox);
                    query = "";
                    page = 0;
                    showList();
                }
            }), Ui.lpMargins(WRAP, WRAP, 0, gap, 0, 0, 0));
        }
        root.addView(searchRow, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));

        // entries
        LinearLayout list = Ui.column(c);
        List<HistoryEntry> entries = db.page(query, pageSize, page * pageSize);
        if (entries.isEmpty()) {
            TextView empty = Ui.label(c, query.isEmpty() ? "还没有翻译记录" : "没有找到匹配的记录", ui + 2, true);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(c, 40), 0, Ui.dp(c, 40));
            list.addView(empty, Ui.lp(MATCH, WRAP));
        }
        for (final HistoryEntry e : entries) {
            list.addView(entryView(e, ui), Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
        }
        ScrollView scroll = new ScrollView(c);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(list, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, Ui.lpMargins(MATCH, 0, 1f, 0, 0, 0, gap));

        // pager
        LinearLayout pager = Ui.row(c);
        final TextView prev = Ui.button(c, "上一页", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                page--;
                showList();
            }
        });
        prev.setEnabled(page > 0);
        final TextView next = Ui.button(c, "下一页", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                page++;
                showList();
            }
        });
        next.setEnabled(page < pages - 1);
        TextView info = Ui.label(c, "第 " + (page + 1) + " / " + pages + " 页\n共 " + total + " 条", ui - 1, true);
        info.setGravity(Gravity.CENTER);
        pager.addView(prev, Ui.lp(0, WRAP, 1f));
        pager.addView(info, Ui.lp(0, WRAP, 1.2f));
        pager.addView(next, Ui.lp(0, WRAP, 1f));
        root.addView(pager, Ui.lp(MATCH, WRAP));

        setContentView(root);
    }

    private void applySearch() {
        hideIme(searchBox);
        query = searchBox.getText().toString().trim();
        page = 0;
        showList();
    }

    private View entryView(final HistoryEntry e, int ui) {
        final Context c = this;
        LinearLayout item = Ui.column(c);
        item.setBackground(Ui.panel(c, 2));
        item.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detail = e;
                showDetail();
            }
        });

        item.addView(Ui.label(c, e.direction.label() + "  ·  " + fmt.format(new Date(e.time)), ui - 3, true),
                Ui.lp(MATCH, WRAP));
        TextView src = Ui.label(c, e.source, Prefs.textSizeSp() > 28 ? 24 : ui + 2, false);
        src.setMaxLines(2);
        src.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(src, Ui.lpMargins(MATCH, WRAP, 0, 0, Ui.dp(c, 4), 0, 0));
        TextView dst = Ui.label(c, e.translation, Prefs.textSizeSp() > 28 ? 24 : ui + 2, true);
        dst.setMaxLines(2);
        dst.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(dst, Ui.lpMargins(MATCH, WRAP, 0, 0, Ui.dp(c, 4), 0, 0));
        return item;
    }

    // ---------------------------------------------------------------------------------------
    // Detail view
    // ---------------------------------------------------------------------------------------

    private void showDetail() {
        final Context c = this;
        final int ui = Prefs.uiSizeSp();
        final int txt = Prefs.textSizeSp();
        final int gap = Ui.dp(c, 8);
        final HistoryEntry e = detail;

        LinearLayout root = newRoot();
        LinearLayout header = Ui.row(c);
        header.addView(Ui.button(c, "返回列表", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detail = null;
                showList();
            }
        }), Ui.lpMargins(WRAP, WRAP, 0, 0, 0, gap, 0));
        header.addView(Ui.label(c, "历史详情", ui + 6, true), Ui.lp(0, WRAP, 1f));
        root.addView(header, Ui.lp(MATCH, WRAP));
        root.addView(Ui.divider(c, 3), Ui.lpMargins(MATCH, 0, 0, 0, gap, 0, gap));

        LinearLayout body = Ui.column(c);
        body.addView(Ui.label(c, e.direction.label() + "  ·  " + fmt.format(new Date(e.time)), ui - 2, true),
                Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
        body.addView(Ui.label(c, "原文", ui, true), Ui.lp(MATCH, WRAP));
        body.addView(textBox(e.source, txt, false), Ui.lpMargins(MATCH, WRAP, 0, 0, Ui.dp(c, 4), 0, gap));
        body.addView(Ui.label(c, "译文（已保存，未重新翻译）", ui, true), Ui.lp(MATCH, WRAP));
        body.addView(textBox(e.translation, txt, true), Ui.lpMargins(MATCH, WRAP, 0, 0, Ui.dp(c, 4), 0, gap));
        ScrollView scroll = new ScrollView(c);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, Ui.lpMargins(MATCH, 0, 1f, 0, 0, 0, gap));

        LinearLayout row1 = Ui.row(c);
        row1.addView(Ui.button(c, "复制原文", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TranslatorController.copyText(HistoryActivity.this, "source", e.source);
                ((TextView) v).setText("已复制");
            }
        }), Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
        row1.addView(Ui.button(c, "复制译文", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TranslatorController.copyText(HistoryActivity.this, "translation", e.translation);
                ((TextView) v).setText("已复制");
            }
        }), Ui.lp(0, WRAP, 1f));
        root.addView(row1, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));

        LinearLayout row2 = Ui.row(c);
        row2.addView(Ui.button(c, "在主界面查看", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ctl.showEntry(e)) {
                    finish();
                } else {
                    ((TextView) v).setText("翻译进行中，稍后再试");
                }
            }
        }), Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
        final TextView del = Ui.button(c, "删除此条", ui, null);
        del.setOnClickListener(new View.OnClickListener() {
            private boolean armed;

            @Override
            public void onClick(View v) {
                if (!armed) {
                    armed = true;
                    del.setText("再点一次确认");
                } else {
                    db.delete(e.id);
                    detail = null;
                    showList();
                }
            }
        });
        row2.addView(del, Ui.lp(0, WRAP, 1f));
        root.addView(row2, Ui.lp(MATCH, WRAP));

        setContentView(root);
    }

    private TextView textBox(String text, int sp, boolean bold) {
        TextView t = Ui.label(this, text, sp, bold || Prefs.bold());
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, Prefs.lineSpacing());
        t.setBackground(Ui.panel(this, 2));
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        return t;
    }
}
