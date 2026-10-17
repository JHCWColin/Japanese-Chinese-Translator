package com.jhcwcolin.jctranslator;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jhcwcolin.jctranslator.data.HistoryDb;
import com.jhcwcolin.jctranslator.engine.ModelStore;
import com.jhcwcolin.jctranslator.ui.BaseActivity;
import com.jhcwcolin.jctranslator.ui.Ui;

/** Settings screen: every row applies immediately; the page is rebuilt so the new look shows at once. */
public class SettingsActivity extends BaseActivity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private interface IntCallback {
        void on(int value);
    }

    private interface BoolCallback {
        void on(boolean value);
    }

    private ScrollView scroll;
    private LinearLayout page;
    private boolean confirmClearHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build(0);
    }

    private void changed() {
        final int y = scroll != null ? scroll.getScrollY() : 0;
        applyWindow();
        markBuilt();
        build(y);
    }

    // ---------------------------------------------------------------------------------------
    // Page
    // ---------------------------------------------------------------------------------------

    private void build(final int restoreScrollY) {
        final Context c = this;
        final int ui = Prefs.uiSizeSp();
        final int gap = Ui.dp(c, 8);

        LinearLayout root = Ui.column(c);
        root.setBackgroundColor(Ui.bg());
        root.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));

        LinearLayout header = Ui.row(c);
        header.addView(Ui.iconButton(c, R.drawable.ic_arrow_left, "返回", 26, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), Ui.lpMargins(WRAP, WRAP, 0, 0, 0, gap, 0));
        header.addView(Ui.label(c, "设置", ui + 6, true), Ui.lp(0, WRAP, 1f));
        root.addView(header, Ui.lp(MATCH, WRAP));
        root.addView(Ui.divider(c, 3), Ui.lpMargins(MATCH, 0, 0, 0, gap, 0, gap));

        page = Ui.column(c);
        scroll = new ScrollView(c);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(page, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, Ui.lp(MATCH, 0, 1f));
        setContentView(root);

        buildRows();

        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, restoreScrollY);
            }
        });
    }

    private void buildRows() {
        section(R.drawable.ic_brain, "翻译模型");
        infoText("中→日 与 日→中 可以分别选择模型。1 GB 内存设备默认使用 OPUS-MT 精简版；标准 OPUS-MT 可手动切换。M2M100 与 NLLB-200 更聪明、译文更通顺、错误更少，"
                + "但体积更大、翻译更慢、占用更多内存（本设备约 " + totalRamMb() + " MB）。"
                + "第一次使用某个模型时需要花几分钟解压，之后不再需要。");
        modelRow(Direction.ZH_JA);
        modelRow(Direction.JA_ZH);

        section(R.drawable.ic_palette, "个性化");
        switchRow(R.drawable.ic_minimize_2, "纯净模式", "主界面只保留一个输入框，旁边是方向切换与翻译按钮；点「翻译」后译文直接替换框内内容。", Prefs.pureMode(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setPureMode(v);
                changed();
            }
        });
        choiceRow(R.drawable.ic_columns_2, "原文/译文排布", "「自动」竖屏上下排布、横屏左右排布。纯净模式下无效。",
                new String[]{"自动", "上下排布", "左右排布"}, Prefs.boxLayout(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setBoxLayout(v);
                        changed();
                    }
                });
        choiceRow(R.drawable.ic_square_round_corner, "按钮圆角", "方角在墨水屏上最清晰；圆角更柔和。",
                new String[]{"方角", "小圆角", "大圆角"}, Prefs.cornerIndex(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setCornerIndex(v);
                        changed();
                    }
                });
        choiceRow(R.drawable.ic_frame, "边框粗细", "按钮与文本框的边框。",
                new String[]{"极细", "细", "中", "粗"}, Prefs.borderIndex(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setBorderIndex(v);
                        changed();
                    }
                });
        choiceRow(R.drawable.ic_move_vertical, "按钮高度", "按钮越高越容易点按。",
                new String[]{"紧凑", "标准", "较高", "很高"}, Prefs.buttonHeightIndex(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setButtonHeightIndex(v);
                        changed();
                    }
                });
        choiceRow(R.drawable.ic_a_large_small, "全局字体缩放", "同时缩放所有文字（含界面字号与输入译文字号）。",
                new String[]{"80%", "90%", "100%", "110%", "125%", "150%"}, Prefs.fontScaleIndex(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setFontScaleIndex(v);
                        changed();
                    }
                });
        switchRow(R.drawable.ic_activity, "显示状态栏", "主界面显示翻译状态与提示信息；关闭后更简洁。", Prefs.showStatus(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setShowStatus(v);
                changed();
            }
        });

        // ---- display ----
        section(R.drawable.ic_monitor, "显示");
        choiceRow(R.drawable.ic_smartphone, "屏幕方向", "选择应用的显示方向。选「跟随系统」时由设备的旋转设置决定。",
                new String[]{"跟随系统", "竖屏", "横屏"}, Prefs.orientation(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setOrientation(v);
                        changed();
                    }
                });
        choiceRow(R.drawable.ic_contrast, "配色", "墨水屏通常选白底黑字；黑底白字在部分屏幕上残影更明显。",
                new String[]{"白底黑字", "黑底白字"}, Prefs.dark() ? 1 : 0, new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setDark(v == 1);
                        changed();
                    }
                });
        stepperRow(R.drawable.ic_type, "输入与译文字号", "当前 " + Prefs.textSizeSp() + " sp。", new Runnable() {
            @Override
            public void run() {
                Prefs.setTextSizeIndex(Prefs.textSizeIndex() - 1);
                changed();
            }
        }, new Runnable() {
            @Override
            public void run() {
                Prefs.setTextSizeIndex(Prefs.textSizeIndex() + 1);
                changed();
            }
        }, Prefs.textSizeSp() + " sp");
        stepperRow(R.drawable.ic_scan_text, "界面字号", "按钮与提示文字的大小。当前 " + Prefs.uiSizeSp() + " sp。", new Runnable() {
            @Override
            public void run() {
                Prefs.setUiSizeIndex(Prefs.uiSizeIndex() - 1);
                changed();
            }
        }, new Runnable() {
            @Override
            public void run() {
                Prefs.setUiSizeIndex(Prefs.uiSizeIndex() + 1);
                changed();
            }
        }, Prefs.uiSizeSp() + " sp");
        choiceRow(R.drawable.ic_align_justify, "行距", "输入框与译文的行间距。",
                new String[]{"紧凑", "标准", "宽松"}, Prefs.lineSpacingIndex(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setLineSpacingIndex(v);
                        changed();
                    }
                });
        switchRow(R.drawable.ic_bold, "加粗输入与译文", "文字更粗，在墨水屏上更清晰。", Prefs.bold(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setBold(v);
                changed();
            }
        });
        buttonRow(R.drawable.ic_eraser, "清除残影", "全屏黑白闪烁几次，用来清除墨水屏上的残影。", "全屏刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(RefreshActivity.class);
            }
        });

        // ---- input & translation ----
        section(R.drawable.ic_languages, "输入与翻译");
        switchRow(R.drawable.ic_keyboard, "翻译后自动收起键盘", "点「翻译」后立即隐藏输入法，不用手动收起。", Prefs.autoHideKeyboard(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setAutoHideKeyboard(v);
                changed();
            }
        });
        switchRow(R.drawable.ic_lock, "翻译时锁定输入框", "翻译过程中输入框变为只读，输入法不会再弹出。", Prefs.lockInputWhileTranslating(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setLockInputWhileTranslating(v);
                changed();
            }
        });
        switchRow(R.drawable.ic_languages, "自动识别输入语言", "含日文假名时自动切到「日语 → 中文」；含简体中文特征字时切到「中文 → 日语」。纯汉字文本以你选的方向为准。",
                Prefs.autoDetect(), new BoolCallback() {
                    @Override
                    public void on(boolean v) {
                        Prefs.setAutoDetect(v);
                        changed();
                    }
                });
        int beamIdx = Prefs.beams() == 1 ? 0 : Prefs.beams() == 2 ? 1 : 2;
        choiceRow(R.drawable.ic_sparkles, "翻译质量", "越精准越慢。快速=贪心搜索；均衡=束宽 2；精准=束宽 4（最准确，设备较慢时耗时更久）。",
                new String[]{"快速", "均衡", "精准"}, beamIdx, new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setBeams(v == 0 ? 1 : v == 1 ? 2 : 4);
                        changed();
                    }
                });
        final int[] segValues = {60, 100, 150, 250};
        int segIdx = 1;
        for (int i = 0; i < segValues.length; i++) {
            if (segValues[i] == Prefs.maxSegmentChars()) {
                segIdx = i;
            }
        }
        choiceRow(R.drawable.ic_scissors, "长句拆分长度", "超过此字数的句子会在逗号处拆开分段翻译，更稳定。",
                new String[]{"60 字", "100 字", "150 字", "250 字"}, segIdx, new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setMaxSegmentChars(segValues[v]);
                        changed();
                    }
                });
        switchRow(R.drawable.ic_copy, "完成后自动复制译文", "翻译完成时把译文放进剪贴板。", Prefs.autoCopy(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setAutoCopy(v);
                changed();
            }
        });

        // ---- performance ----
        section(R.drawable.ic_gauge, "性能与内存");
        boolean low = Prefs.lowMemory(this, Prefs.model(Direction.JA_ZH)) || Prefs.lowMemory(this, Prefs.model(Direction.ZH_JA));
        choiceRow(R.drawable.ic_memory_stick, "内存模式", "设备总内存 " + totalRamMb() + " MB，当前实际使用：" + (low ? "省内存" : "常驻")
                        + "。\n自动=当前可用内存放不下编码器与解码器时用省内存。省内存：编码器与解码器分步加载以降低峰值内存，空闲 45 秒后自动释放；常驻：两者都留在内存里，翻译更快但占用更多。",
                new String[]{"自动", "省内存", "常驻"}, Prefs.memoryMode(), new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setMemoryMode(v);
                        changed();
                    }
                });
        final int[] threadValues = {0, 1, 2, 4};
        int thrIdx = 0;
        for (int i = 0; i < threadValues.length; i++) {
            if (threadValues[i] == Prefs.threads()) {
                thrIdx = i;
            }
        }
        choiceRow(R.drawable.ic_cpu, "推理线程数", "自动 = 使用 " + Prefs.effectiveThreads() + " 个线程（最多 4 个）。线程越多越快，但更耗电发热。",
                new String[]{"自动", "1", "2", "4"}, thrIdx, new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setThreads(threadValues[v]);
                        changed();
                    }
                });

        // ---- history ----
        section(R.drawable.ic_history, "历史记录");
        switchRow(R.drawable.ic_save, "保存翻译历史", "同时保存原文和译文；查看历史时直接显示，不会重新翻译。", Prefs.historyEnabled(), new BoolCallback() {
            @Override
            public void on(boolean v) {
                Prefs.setHistoryEnabled(v);
                changed();
            }
        });
        final int[] maxValues = {50, 200, 500, 1000};
        int maxIdx = 2;
        for (int i = 0; i < maxValues.length; i++) {
            if (maxValues[i] == Prefs.historyMax()) {
                maxIdx = i;
            }
        }
        choiceRow(R.drawable.ic_list_ordered, "最多保留条数", "超过后自动删除最早的记录。",
                new String[]{"50", "200", "500", "1000"}, maxIdx, new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setHistoryMax(maxValues[v]);
                        changed();
                    }
                });
        final int[] pageValues = {5, 8, 12, 20};
        int pageIdx = 1;
        for (int i = 0; i < pageValues.length; i++) {
            if (pageValues[i] == Prefs.historyPageSize()) {
                pageIdx = i;
            }
        }
        choiceRow(R.drawable.ic_book_open, "历史每页条数", "历史记录按页显示，翻页比滚动更适合墨水屏。",
                new String[]{"5", "8", "12", "20"}, pageIdx, new IntCallback() {
                    @Override
                    public void on(int v) {
                        Prefs.setHistoryPageSize(pageValues[v]);
                        changed();
                    }
                });
        buttonRow(R.drawable.ic_trash_2, "清空历史记录", "删除全部已保存的翻译记录，无法恢复。",
                confirmClearHistory ? "再点一次确认清空" : "清空历史记录", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!confirmClearHistory) {
                            confirmClearHistory = true;
                        } else {
                            HistoryDb.get(SettingsActivity.this).clear();
                            confirmClearHistory = false;
                        }
                        changed();
                    }
                });

        // ---- about ----
        section(R.drawable.ic_info, "关于");
        infoText("日中互译 " + versionName() + "\n"
                + "完全离线的日语 ⇄ 中文翻译，翻译模型已内置在安装包中，不需要联网，也不会上传任何文字。\n\n"
                + "翻译模型（可在最上方分别为两个方向选择）：\n"
                + "· OPUS-MT（Marian）日→中 / 中→日 直译模型，int8 量化，每个方向约 146 MB。来源 shun89/opus-mt-ja-zh、shun89/opus-mt-zh-ja（基于 Helsinki-NLP OPUS-MT，CC BY 4.0 / Apache-2.0）。\n"
                + "· OPUS-MT 精简版：同一批模型裁掉用不到的词表（约一半），其余结构不变，体积与内存占用约减半。\n"
                + "· M2M100 418M（Facebook/Meta，MIT），int8 量化并裁剪词表。\n"
                + "· NLLB-200 distilled 600M（Meta，CC BY-NC 4.0，仅限非商业使用），int8 量化并裁剪词表。\n"
                + "推理引擎：ONNX Runtime（MIT）。\n"
                + "项目主页：github.com/JHCWColin/Japanese-Chinese-Translator\n\n"
                + "提示：机器翻译可能出错，重要内容请人工核对。");
    }

    private void modelRow(final Direction d) {
        LinearLayout b = block(R.drawable.ic_languages, d.label() + " 使用的模型", null);
        ModelChoice cur = Prefs.model(d);
        for (final ModelChoice m : ModelChoice.LIST_ORDER) {
            boolean bundled = ModelStore.isBundled(this, m);
            String text = m.title + "　" + m.size + "\n" + m.desc + (bundled ? "" : "（本安装包未包含）");
            TextView btn = Ui.button(this, text, Prefs.uiSizeSp(), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.setModel(d, m);
                    changed();
                }
            });
            btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            btn.setEnabled(bundled);
            btn.setSelected(m == cur);
            b.addView(btn, Ui.lpMargins(MATCH, WRAP, 0, 0, 0, 0, Ui.dp(this, 6)));
        }
    }

    private String versionName() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private long totalRamMb() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / 1024 / 1024;
    }

    // ---------------------------------------------------------------------------------------
    // Row builders
    // ---------------------------------------------------------------------------------------

    private void section(int icon, String title) {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.fg());
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        ImageView iv = Ui.icon(this, icon, Prefs.uiSizeSp() + 8);
        iv.setImageTintList(ColorStateList.valueOf(Ui.bg()));
        bar.addView(iv, Ui.lpMargins(Ui.dp(this, Prefs.uiSizeSp() + 8), Ui.dp(this, Prefs.uiSizeSp() + 8), 0, 0, 0, Ui.dp(this, 8), 0));
        TextView t = Ui.label(this, title, Prefs.uiSizeSp() + 2, true);
        t.setTextColor(Ui.bg());
        bar.addView(t, Ui.lp(0, WRAP, 1f));
        page.addView(bar, Ui.lpMargins(MATCH, WRAP, 0, 0, Ui.dp(this, 14), 0, Ui.dp(this, 6)));
    }

    private LinearLayout block(int icon, String title, String desc) {
        LinearLayout b = Ui.column(this);
        b.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        LinearLayout head = Ui.row(this);
        int isz = Prefs.uiSizeSp() + 6;
        head.addView(Ui.icon(this, icon, isz), Ui.lpMargins(Ui.dp(this, isz), Ui.dp(this, isz), 0, 0, 0, Ui.dp(this, 8), 0));
        head.addView(Ui.label(this, title, Prefs.uiSizeSp() + 1, true), Ui.lp(0, WRAP, 1f));
        b.addView(head, Ui.lp(MATCH, WRAP));
        if (desc != null && !desc.isEmpty()) {
            TextView d = Ui.label(this, desc, Prefs.uiSizeSp() - 2, false);
            b.addView(d, Ui.lpMargins(MATCH, WRAP, 0, 0, Ui.dp(this, 2), 0, Ui.dp(this, 6)));
        }
        page.addView(b, Ui.lp(MATCH, WRAP));
        page.addView(Ui.divider(this, 1), Ui.lp(MATCH, 1));
        return b;
    }

    private void choiceRow(int icon, String title, String desc, String[] labels, int selected, final IntCallback cb) {
        LinearLayout b = block(icon, title, desc);
        LinearLayout row = Ui.row(this);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView btn = Ui.button(this, labels[i], Prefs.uiSizeSp(), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.on(idx);
                }
            });
            btn.setMinWidth(0);
            btn.setSelected(i == selected);
            row.addView(btn, Ui.lpMargins(0, WRAP, 1f, 0, 0, i < labels.length - 1 ? Ui.dp(this, 6) : 0, 0));
        }
        b.addView(row, Ui.lp(MATCH, WRAP));
    }

    private void switchRow(int icon, String title, String desc, boolean value, final BoolCallback cb) {
        choiceRowInternal(icon, title, desc, new String[]{"关", "开"}, value ? 1 : 0, new IntCallback() {
            @Override
            public void on(int v) {
                cb.on(v == 1);
            }
        });
    }

    private void choiceRowInternal(int icon, String title, String desc, String[] labels, int selected, IntCallback cb) {
        choiceRow(icon, title, desc, labels, selected, cb);
    }

    private void stepperRow(int icon, String title, String desc, final Runnable dec, final Runnable inc, String value) {
        LinearLayout b = block(icon, title, desc);
        LinearLayout row = Ui.row(this);
        TextView minus = Ui.button(this, "−", Prefs.uiSizeSp() + 6, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dec.run();
            }
        });
        TextView plus = Ui.button(this, "+", Prefs.uiSizeSp() + 6, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inc.run();
            }
        });
        TextView val = Ui.label(this, value, Prefs.uiSizeSp() + 4, true);
        val.setGravity(Gravity.CENTER);
        row.addView(minus, Ui.lp(0, WRAP, 1f));
        row.addView(val, Ui.lp(0, WRAP, 1.4f));
        row.addView(plus, Ui.lp(0, WRAP, 1f));
        b.addView(row, Ui.lp(MATCH, WRAP));
    }

    private void buttonRow(int icon, String title, String desc, String buttonText, View.OnClickListener l) {
        LinearLayout b = block(icon, title, desc);
        b.addView(Ui.button(this, buttonText, Prefs.uiSizeSp(), l), Ui.lp(MATCH, WRAP));
    }

    private void infoText(String text) {
        TextView t = Ui.label(this, text, Prefs.uiSizeSp() - 1, false);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 16));
        page.addView(t, Ui.lp(MATCH, WRAP));
    }
}
