package com.jhcwcolin.jctranslator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jhcwcolin.jctranslator.ui.BaseActivity;
import com.jhcwcolin.jctranslator.ui.Ui;

/** Main translation screen. */
public class MainActivity extends BaseActivity implements TranslatorController.Listener {

    private TranslatorController ctl;

    private LinearLayout root;
    private EditText input;
    private TextView btnTranslate;
    private TextView btnStop;
    private TextView btnClear;
    private TextView btnPaste;
    private TextView btnSwap;
    private TextView btnCopy;
    private final TextView[] dirButtons = new TextView[2];
    private LinearLayout statusBox;
    private TextView statusView;
    private TextView detailView;
    private ScrollView resultScroll;
    private TextView resultView;
    private boolean inputLocked;
    /** Pure mode: after a translation finishes, its result replaces the text in the input box. */
    private boolean pendingPure;
    /** Last draft text we pushed to / read from the input, so user typing is never overwritten. */
    private String shownDraft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ctl = TranslatorController.get(this);
        buildUi();
        setContentView(root);
    }

    // ---------------------------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------------------------

    private void buildUi() {
        final Context c = this;
        final int ui = Prefs.uiSizeSp();
        final int txt = Prefs.textSizeSp();
        final boolean deviceLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        final boolean landscape = Prefs.boxLayout() == Prefs.LAYOUT_AUTO ? deviceLandscape
                : Prefs.boxLayout() == Prefs.LAYOUT_SIDE;
        final int gap = Ui.dp(c, 8);
        final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
        final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

        root = Ui.column(c);
        root.setBackgroundColor(Ui.bg());
        root.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true); // keeps the keyboard from popping up at start

        // ---- header ----
        LinearLayout header = Ui.row(c);
        TextView title = Ui.label(c, "日中互译", ui + 6, true);
        header.addView(title, Ui.lp(0, WRAP, 1f));
        TextView btnHistory = Ui.button(c, "历史", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncDraftFromInput();
                go(HistoryActivity.class);
            }
        });
        TextView btnSettings = Ui.button(c, "设置", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncDraftFromInput();
                go(SettingsActivity.class);
            }
        });
        header.addView(btnHistory, Ui.lpMargins(WRAP, WRAP, 0, 0, 0, gap, 0));
        header.addView(btnSettings, Ui.lp(WRAP, WRAP));
        root.addView(header, Ui.lp(MATCH, WRAP));
        root.addView(Ui.divider(c, 3), Ui.lpMargins(MATCH, 0, 0, 0, gap, 0, gap));

        // ---- direction row ----
        LinearLayout dirRow = Ui.row(c);
        Direction[] dirs = Direction.values();
        for (int i = 0; i < dirs.length; i++) {
            final Direction d = dirs[i];
            dirButtons[i] = Ui.button(c, d.label(), ui, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    syncDraftFromInput();
                    ctl.setDirection(d);
                }
            });
            dirRow.addView(dirButtons[i], Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
        }
        btnSwap = Ui.button(c, "互换", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncDraftFromInput();
                ctl.swapDirection();
            }
        });
        dirRow.addView(btnSwap, Ui.lp(WRAP, WRAP));

        // ---- input ----
        input = new EditText(c);
        Ui.styleEdit(c, input, txt);
        input.setHint("在此输入或粘贴要翻译的文字");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setMinLines(3);
        input.setVerticalScrollBarEnabled(true);
        input.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // ---- actions under input ----
        LinearLayout actions = Ui.row(c);
        btnClear = Ui.button(c, "清空", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ctl.clearAll();
            }
        });
        btnPaste = Ui.button(c, "粘贴", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        btnStop = Ui.button(c, "停止翻译", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ctl.cancel();
            }
        });
        btnTranslate = Ui.button(c, "翻  译", ui + 4, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTranslateClicked();
            }
        });
        btnTranslate.setMinHeight(Ui.dp(c, 60));
        actions.addView(btnClear, Ui.lpMargins(WRAP, WRAP, 0, 0, 0, gap, 0));
        actions.addView(btnPaste, Ui.lpMargins(WRAP, WRAP, 0, 0, 0, gap, 0));
        actions.addView(btnStop, Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
        actions.addView(btnTranslate, Ui.lp(0, WRAP, 2f));

        // ---- status box ----
        statusBox = Ui.column(c);
        statusBox.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
        statusView = Ui.label(c, "", ui + 2, true);
        detailView = Ui.label(c, "", ui - 2, false);
        statusBox.addView(statusView, Ui.lp(MATCH, WRAP));
        statusBox.addView(detailView, Ui.lp(MATCH, WRAP));

        // ---- result ----
        resultView = new TextView(c);
        resultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, txt);
        resultView.setTextColor(Ui.fg());
        resultView.setLineSpacing(0, Prefs.lineSpacing());
        resultView.setTextIsSelectable(true);
        if (Prefs.bold()) {
            resultView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        resultView.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        resultScroll = new ScrollView(c);
        resultScroll.setBackground(Ui.panel(c, 2));
        resultScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        resultScroll.setScrollbarFadingEnabled(false);
        resultScroll.setFillViewport(true);
        resultScroll.addView(resultView, new ViewGroup.LayoutParams(MATCH, WRAP));

        LinearLayout resultActions = Ui.row(c);
        btnCopy = Ui.button(c, "复制译文", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ctl.result.isEmpty()) {
                    TranslatorController.copyText(MainActivity.this, "translation", ctl.result);
                    ctl.setNote("已复制译文到剪贴板", ctl.detail);
                }
            }
        });
        TextView pageUp = Ui.button(c, "上页", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollResult(-1);
            }
        });
        TextView pageDown = Ui.button(c, "下页", ui, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollResult(1);
            }
        });
        resultActions.addView(btnCopy, Ui.lpMargins(0, WRAP, 2f, 0, 0, gap, 0));
        resultActions.addView(pageUp, Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
        resultActions.addView(pageDown, Ui.lp(0, WRAP, 1f));

        // ---- assemble ----
        if (Prefs.pureMode()) {
            input.setMinLines(1);
            input.setHint("输入要翻译的文字");
            btnSwap.setText(ctl.direction.label());
            LinearLayout bar = Ui.row(c);
            bar.addView(btnSwap, Ui.lpMargins(0, WRAP, 1.3f, 0, 0, gap, 0));
            bar.addView(btnClear, Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
            bar.addView(btnStop, Ui.lpMargins(0, WRAP, 1f, 0, 0, gap, 0));
            bar.addView(btnTranslate, Ui.lp(0, WRAP, 1.5f));
            root.addView(input, Ui.lpMargins(MATCH, 0, 1f, 0, 0, 0, gap));
            root.addView(bar, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
            if (Prefs.showStatus()) {
                root.addView(statusBox, Ui.lp(MATCH, WRAP));
            }
            return;
        }
        if (!Prefs.showStatus()) {
            statusBox.setVisibility(View.GONE);
        }
        if (landscape) {
            LinearLayout body = Ui.row(c);
            body.setGravity(Gravity.TOP);

            LinearLayout left = Ui.column(c);
            left.addView(dirRow, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
            left.addView(input, Ui.lpMargins(MATCH, 0, 1f, 0, 0, 0, gap));
            left.addView(actions, Ui.lp(MATCH, WRAP));

            LinearLayout right = Ui.column(c);
            right.addView(statusBox, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
            right.addView(resultScroll, Ui.lpMargins(MATCH, 0, 1f, 0, 0, 0, gap));
            right.addView(resultActions, Ui.lp(MATCH, WRAP));

            body.addView(left, Ui.lpMargins(0, MATCH, 1f, 0, 0, gap, 0));
            body.addView(right, Ui.lpMargins(0, MATCH, 1f, gap, 0, 0, 0));
            root.addView(body, Ui.lp(MATCH, 0, 1f));
        } else {
            root.addView(dirRow, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
            root.addView(input, Ui.lpMargins(MATCH, 0, 1f, 0, 0, 0, gap));
            root.addView(actions, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
            root.addView(statusBox, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, gap));
            root.addView(resultScroll, Ui.lpMargins(MATCH, 0, 1.3f, 0, 0, 0, gap));
            root.addView(resultActions, Ui.lp(MATCH, WRAP));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Behaviour
    // ---------------------------------------------------------------------------------------

    private void syncDraftFromInput() {
        ctl.draft = input.getText().toString();
        shownDraft = ctl.draft;
    }

    private void onTranslateClicked() {
        if (ctl.isBusy()) {
            return;
        }
        syncDraftFromInput();
        String text = ctl.draft;
        // The keyboard must not stay on screen while we wait: hide it, drop focus, and (in
        // refresh()) make the field read-only for the duration of the translation.
        if (Prefs.autoHideKeyboard()) {
            hideKeyboardNow();
        }
        pendingPure = Prefs.pureMode() && !text.trim().isEmpty();
        ctl.translate(text);
    }

    private void hideKeyboardNow() {
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        input.clearFocus();
        root.requestFocus();
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
        // some input methods re-show themselves once after a focus change; hide again shortly after
        input.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (imm != null && !input.hasFocus()) {
                    imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                }
            }
        }, 250);
    }

    private void setInputLocked(boolean locked) {
        if (locked == inputLocked) {
            return;
        }
        inputLocked = locked;
        if (locked) {
            hideKeyboardNow();
            input.setFocusable(false);
            input.setFocusableInTouchMode(false);
            input.setCursorVisible(false);
            input.setLongClickable(false);
        } else {
            input.setFocusable(true);
            input.setFocusableInTouchMode(true);
            input.setCursorVisible(true);
            input.setLongClickable(true);
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
            ctl.setNote("剪贴板是空的", "请先在其他应用中复制文字");
            return;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence t = item.coerceToText(this);
        if (t == null || t.length() == 0) {
            ctl.setNote("剪贴板是空的", "请先在其他应用中复制文字");
            return;
        }
        input.setText(t);
        input.setSelection(input.length());
        syncDraftFromInput();
        ctl.setNote("已粘贴", "共 " + t.length() + " 个字符，点「翻译」开始");
    }

    private void scrollResult(int dir) {
        int step = Math.max(1, (int) (resultScroll.getHeight() * 0.9f));
        resultScroll.scrollBy(0, dir * step);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!input.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                scrollResult(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                scrollResult(-1);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------------------------------
    // State rendering
    // ---------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        ctl.addListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        ctl.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onPause() {
        syncDraftFromInput();
        hideKeyboard();
        super.onPause();
    }

    @Override
    public void onTranslatorStateChanged() {
        refresh();
    }

    private void refresh() {
        final boolean busy = ctl.isBusy();

        Direction[] dirs = Direction.values();
        for (int i = 0; i < dirs.length; i++) {
            dirButtons[i].setSelected(ctl.direction == dirs[i]);
            dirButtons[i].setEnabled(!busy);
        }
        btnSwap.setEnabled(!busy);
        if (Prefs.pureMode()) {
            btnSwap.setText(ctl.direction.label());
        }
        if (pendingPure && !busy) {
            pendingPure = false;
            if (!ctl.result.isEmpty()) {
                ctl.draft = ctl.result;
            }
        }

        if (!ctl.draft.equals(shownDraft)) {
            if (!ctl.draft.equals(input.getText().toString())) {
                input.setText(ctl.draft);
                input.setSelection(input.length());
            }
            shownDraft = ctl.draft;
        }
        setInputLocked(busy && Prefs.lockInputWhileTranslating());

        btnTranslate.setText(busy ? "翻译中…" : "翻  译");
        btnTranslate.setEnabled(!busy);
        btnStop.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnClear.setVisibility(busy ? View.GONE : View.VISIBLE);
        btnPaste.setVisibility(busy ? View.GONE : View.VISIBLE);

        statusView.setText(ctl.status);
        detailView.setText(ctl.detail);
        detailView.setVisibility(ctl.detail == null || ctl.detail.isEmpty() ? View.GONE : View.VISIBLE);
        statusBox.setBackground(Ui.panel(this, busy ? 5 : 2));

        String r = ctl.result;
        if (busy) {
            resultView.setText("【 翻译进行中 】\n\n请稍候，不要关闭程序。\n翻译在本机完成，速度取决于设备性能。");
        } else if (r.isEmpty()) {
            resultView.setText("译文将显示在这里");
        } else {
            resultView.setText(r);
        }
        btnCopy.setEnabled(!busy && !r.isEmpty());
    }
}
