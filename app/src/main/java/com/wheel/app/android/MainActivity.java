package com.wheel.app.android;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.wheel.app.format.WheelFormats;
import com.wheel.app.model.Models;
import com.wheel.app.model.Models.*;
import com.wheel.app.search.WheelSearch;
import com.wheel.app.spin.SpinEngine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_IMPORT_PWH = 10, REQ_IMPORT_WWD = 11, REQ_EXPORT_PWH = 12, REQ_EXPORT_WWD = 13;
    private static final int DARK_BG = 0xff090d14, DARK_PANEL = 0xff111827, DARK_CARD = 0xff172033, DARK_TEXT = 0xfff8fafc, DARK_SUB = 0xff98a2b3;
    private static final int ACCENT = 0xffea4b3b, BLUE = 0xff2563eb, DANGER = 0xffef4444;

    private int bg, panel, card, text, sub, stroke;
    private boolean darkMain;
    private final SpinEngine engine = new SpinEngine();
    private WheelLibrary library = new WheelLibrary();
    private Wheel selectedWheel;
    private String selectedGroupId;
    private String listSearchText = "";
    private WheelView wheelView;
    private TextView badge, liveOption;
    private enum Screen { MAIN, LIST, EDITOR }
    private Screen currentScreen = Screen.MAIN;
    private Screen editorReturnScreen = Screen.MAIN;
    private Wheel editorWheel;
    private boolean editorCreating;
    private TextToSpeech tts;
    private boolean ttsReady;
    private SpinSoundPlayer spinSound;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        refreshMainPalette();
        tts = new TextToSpeech(this, this);
        spinSound = new SpinSoundPlayer();
        load(); seed();
        if (selectedWheel == null && !library.wheels.isEmpty()) selectedWheel = library.wheels.get(0);
        restoreNavigationState(b);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleSystemBack);
        }
        renderCurrentScreen();
    }

    public void onInit(int status) { ttsReady = status == TextToSpeech.SUCCESS; }
    protected void onDestroy() { super.onDestroy(); if (tts != null) tts.shutdown(); if (spinSound != null) spinSound.release(); }
    public void onBackPressed() { handleSystemBack(); }

    private void handleSystemBack() {
        if (currentScreen == Screen.LIST) navigateTo(Screen.MAIN);
        else if (currentScreen == Screen.EDITOR) navigateTo(editorReturnScreen == Screen.LIST ? Screen.LIST : Screen.MAIN);
        else showExitConfirmation();
    }

    private void showExitConfirmation() {
        new AlertDialog.Builder(this).setTitle("退出").setMessage("确定退出应用？").setPositiveButton("退出", (d,w) -> finish()).setNegativeButton("取消", null).show();
    }

    private void navigateTo(Screen screen) { currentScreen = screen; renderCurrentScreen(); }
    private void renderCurrentScreen() {
        if (currentScreen == Screen.LIST) renderListScreen();
        else if (currentScreen == Screen.EDITOR && editorWheel != null) renderEditorScreen();
        else { currentScreen = Screen.MAIN; buildMainUi(); select(selectedWheel); }
    }

    private void restoreNavigationState(Bundle state) {
        if (state == null) return;
        selectedGroupId = state.getString("selectedGroupId", selectedGroupId);
        listSearchText = state.getString("listSearchText", listSearchText);
        selectedWheel = wheelById(state.getString("selectedWheelId"), selectedWheel);
        try { currentScreen = Screen.valueOf(state.getString("currentScreen", Screen.MAIN.name())); } catch (Exception ignored) { currentScreen = Screen.MAIN; }
        try { editorReturnScreen = Screen.valueOf(state.getString("editorReturnScreen", Screen.MAIN.name())); } catch (Exception ignored) { editorReturnScreen = Screen.MAIN; }
        editorCreating = state.getBoolean("editorCreating", false);
        editorWheel = editorCreating ? applyAppDefaults(new Wheel()) : wheelById(state.getString("editorWheelId"), null);
        if (currentScreen == Screen.EDITOR && editorWheel == null) currentScreen = Screen.MAIN;
    }

    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("currentScreen", currentScreen.name());
        out.putString("editorReturnScreen", editorReturnScreen.name());
        out.putString("selectedWheelId", selectedWheel == null ? null : selectedWheel.id);
        out.putString("selectedGroupId", selectedGroupId);
        out.putString("listSearchText", listSearchText);
        out.putBoolean("editorCreating", editorCreating);
        out.putString("editorWheelId", editorWheel == null ? null : editorWheel.id);
    }

    private Wheel wheelById(String id, Wheel fallback) {
        if (id != null) for (Wheel wheel : library.wheels) if (id.equals(wheel.id)) return wheel;
        return fallback;
    }

    private void refreshMainPalette() {
        darkMain = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = darkMain ? 0xff0f172a : 0xffffffff;
        panel = darkMain ? 0xff111827 : 0xffffffff;
        card = darkMain ? 0xff182235 : 0xffffffff;
        text = darkMain ? 0xfff8fafc : 0xff222222;
        sub = darkMain ? 0xffaab3c2 : 0xff888888;
        stroke = darkMain ? 0xff263244 : 0xffe5e7eb;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(darkMain ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void useDarkBars() {
        getWindow().setStatusBarColor(DARK_BG);
        getWindow().setNavigationBarColor(DARK_BG);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void buildMainUi() {
        refreshMainPalette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(darkMain ? bg : 0xffffffff);
        root.setPadding(dp(20), statusBarHeight() + dp(20), dp(20), navBarHeight() + dp(20));

        // Top bar: gear (left) + spacer + three-dot (right)
        LinearLayout topBar = row(Gravity.CENTER_VERTICAL);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(40)));
        topBar.addView(iconPlain("⚙", "设置", v -> showSettingsSheet(), darkMain ? 0xffaab3c2 : 0xff555555, 24));
        Space topSp = new Space(this);
        topBar.addView(topSp, new LinearLayout.LayoutParams(0, 1, 1));
        topBar.addView(iconPlain("⋮", "列表", v -> navigateTo(Screen.LIST), darkMain ? 0xfff8fafc : 0xff333333, 24));

        // Title badge: [list icon button] + wheel name pill
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-1, -2);
        trlp.setMargins(0, dp(12), 0, dp(6));
        root.addView(titleRow, trlp);
        // List icon (tappable → list screen)
        TextView listIcon = iconPlain("☰", "转盘列表", v -> navigateTo(Screen.LIST), darkMain ? 0xffaab3c2 : 0xffaaaaaa, 22);
        titleRow.addView(listIcon);
        Space titleGap = new Space(this);
        titleRow.addView(titleGap, new LinearLayout.LayoutParams(dp(8), 1));
        // Wheel name badge
        badge = new TextView(this);
        badge.setBackground(round(darkMain ? 0xff1f2937 : 0xfff5f6fa, dp(40), 0, 0));
        badge.setTextColor(darkMain ? 0xfff8fafc : 0xff333333);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(18);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(18), dp(6), dp(18), dp(6));
        badge.setText("今天转什么？");
        titleRow.addView(badge);

        // Live option name (shows current pointer option / spin result)
        liveOption = label("准备好了", 15, sub, false);
        liveOption.setGravity(Gravity.CENTER);
        liveOption.setLetterSpacing(0.02f);
        liveOption.setPadding(0, dp(4), 0, dp(12));
        root.addView(liveOption, new LinearLayout.LayoutParams(-1, -2));

        // Wheel wrapper (1:1 aspect ratio via post)
        FrameLayout wheelWrap = new FrameLayout(this);
        root.addView(wheelWrap, new LinearLayout.LayoutParams(-1, 0, 1));
        wheelWrap.post(() -> {
            int w = wheelWrap.getWidth();
            if (w > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) wheelWrap.getLayoutParams();
                lp.height = w; lp.weight = 0;
                wheelWrap.setLayoutParams(lp);
            }
        });
        wheelView = new WheelView(this);
        wheelWrap.addView(wheelView, new FrameLayout.LayoutParams(-1, -1));

        // Bottom bar: hamburger circle + "点击旋转" + pencil circle
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(64));
        blp.setMargins(0, dp(16), 0, 0);
        root.addView(bottom, blp);
        bottom.addView(circleBtn("≡", v -> showSettingsSheet(), darkMain));
        Button spinBtn = new Button(this);
        spinBtn.setText("点击旋转");
        spinBtn.setTextSize(16);
        spinBtn.setTypeface(Typeface.DEFAULT);
        spinBtn.setAllCaps(false);
        spinBtn.setTextColor(ACCENT);
        spinBtn.setBackground(round(darkMain ? 0xff1f2937 : 0xffffffff, dp(24), ACCENT, 2));
        spinBtn.setOnClickListener(v -> spin());
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        spinLp.setMargins(dp(16), 0, dp(16), 0);
        bottom.addView(spinBtn, spinLp);
        bottom.addView(circleBtn("✎", v -> openEditor(selectedWheel, Screen.MAIN), darkMain));
        setContentView(root);
    }

    private void renderListScreen() {
        useDarkBars();
        LinearLayout root = darkScreen();
        LinearLayout appBar = row(Gravity.CENTER_VERTICAL); root.addView(appBar, new LinearLayout.LayoutParams(-1, dp(58)));
        appBar.addView(iconButton("‹", "返回", v -> onBackPressed(), true), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label("转盘列表", 22, DARK_TEXT, true); appBar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        TextView count = chip(library.wheels.size() + " 个", true); appBar.addView(count, new LinearLayout.LayoutParams(-2, dp(38)));
        Space s = new Space(this); appBar.addView(s, new LinearLayout.LayoutParams(dp(8), 1));
        appBar.addView(iconButton("＋", "添加", v -> openEditor(null, Screen.LIST), true), new LinearLayout.LayoutParams(dp(48), dp(48)));

        EditText search = input("搜索转盘 / 选项", listSearchText, true); search.setSingleLine(true); root.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));
        Spinner groups = new Spinner(this); ArrayList<GroupChoice> groupChoices = groupChoices(true); groups.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, groupChoices)); for (int i = 0; i < groupChoices.size(); i++) if (Objects.equals(groupChoices.get(i).id, selectedGroupId)) groups.setSelection(i); root.addView(groups, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this); scroll.addView(results); LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1); svlp.setMargins(0, dp(10), 0, 0); root.addView(scroll, svlp);
        Runnable refresh = () -> {
            listSearchText = search.getText().toString();
            selectedGroupId = groupChoices.get(groups.getSelectedItemPosition()).id;
            results.removeAllViews();
            java.util.List<Wheel> wheels = new ArrayList<>();
            for (Wheel w : library.wheels) if (selectedGroupId == null || Objects.equals(w.groupId, selectedGroupId)) wheels.add(w);
            for (WheelSearch.Result r : WheelSearch.search(wheels, listSearchText)) results.addView(wheelCardRow(r));
            if (results.getChildCount() == 0) results.addView(emptyText("没有匹配的转盘", true));
            count.setText(results.getChildCount() + " / " + library.wheels.size());
        };
        search.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence s,int a,int c,int d){} public void onTextChanged(CharSequence s,int a,int b,int c){ refresh.run(); } public void afterTextChanged(Editable e){} });
        groups.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){ public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id){ refresh.run(); } public void onNothingSelected(android.widget.AdapterView<?> p){} });
        setContentView(root); refresh.run(); search.requestFocus(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search, 0);
    }

    private View wheelCardRow(WheelSearch.Result r) {
        Wheel w = r.wheel();
        LinearLayout cardBox = new LinearLayout(this); cardBox.setOrientation(LinearLayout.VERTICAL); cardBox.setPadding(dp(14), dp(12), dp(14), dp(12)); cardBox.setBackground(round(DARK_CARD, dp(18), 0xff263244, 1));
        TextView name = label(w.name, 18, DARK_TEXT, true); cardBox.addView(name);
        TextView meta = label(w.options.size() + " 个选项 · " + groupName(w.groupId) + " · 匹配 " + r.score(), 13, DARK_SUB, false); cardBox.addView(meta);
        LinearLayout buttons = row(Gravity.CENTER_VERTICAL); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44)); lp.setMargins(0, dp(10), 0, 0); cardBox.addView(buttons, lp);
        buttons.addView(smallDarkButton("打开", v -> { select(w); navigateTo(Screen.MAIN); }), new LinearLayout.LayoutParams(0, dp(42), 1));
        buttons.addView(smallDarkButton("编辑", v -> openEditor(w, Screen.LIST)), new LinearLayout.LayoutParams(0, dp(42), 1));
        buttons.addView(smallDarkButton("复制", v -> { duplicateWheel(w); renderCurrentScreen(); }), new LinearLayout.LayoutParams(0, dp(42), 1));
        buttons.addView(smallDarkButton("删除", v -> confirmDelete(w)), new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2); outer.setMargins(0, 0, 0, dp(10)); cardBox.setLayoutParams(outer);
        return cardBox;
    }

    private void confirmDelete(Wheel w) {
        new AlertDialog.Builder(this).setTitle("删除转盘？").setMessage(w.name).setPositiveButton("删除", (d, which) -> { library.wheels.remove(w); if (selectedWheel == w) selectedWheel = library.wheels.isEmpty() ? null : library.wheels.get(0); save(); renderCurrentScreen(); }).setNegativeButton("取消", null).show();
    }

    private void duplicateWheel(Wheel src) {
        Wheel copy = new Wheel(); copy.name = library.uniqueWheelName(src.name + " 副本"); copy.groupId = src.groupId; copy.settings.rotationDurationMs = src.settings.rotationDurationMs; copy.settings.colorScheme = src.settings.colorScheme; copy.settings.fontSize = src.settings.fontSize; copy.settings.tickSoundEnabled = src.settings.tickSoundEnabled; copy.settings.selectedSoundEnabled = src.settings.selectedSoundEnabled; copy.settings.ttsEnabled = src.settings.ttsEnabled; copy.settings.ttsLanguageTag = src.settings.ttsLanguageTag;
        for (WheelOption o : src.options) copy.options.add(new WheelOption(o.text, o.trueWeight, o.fakeWeight));
        library.wheels.add(copy); save(); toast("已复制");
    }

    private void showSettingsSheet() {
        String[] items = {"新建转盘", "编辑当前转盘", "应用设置", "新建分组", "导入 PWH", "导入 WWD", "导出当前为 PWH", "导出全部为 WWD", "删除当前转盘"};
        new AlertDialog.Builder(this).setTitle("操作与设置").setItems(items, (d, which) -> {
            if (which == 0) openEditor(null, Screen.MAIN);
            else if (which == 1 && selectedWheel != null) openEditor(selectedWheel, Screen.MAIN);
            else if (which == 2) editAppSettings();
            else if (which == 3) newGroup();
            else if (which == 4) open(REQ_IMPORT_PWH, "*/*");
            else if (which == 5) open(REQ_IMPORT_WWD, "*/*");
            else if (which == 6) create(REQ_EXPORT_PWH, safeName(selectedWheel == null ? "export" : selectedWheel.name) + ".pwh");
            else if (which == 7) create(REQ_EXPORT_WWD, "export.wwd.json");
            else if (which == 8) deleteCurrent();
        }).show();
    }

    private void select(Wheel w) {
        selectedWheel = w;
        if (w != null) selectedGroupId = w.groupId;
        if (wheelView != null) wheelView.setWheel(w);
        if (badge != null) badge.setText(w == null ? "今天转什么？" : w.name);
        if (liveOption != null) liveOption.setText(w == null ? "请先新建转盘" : "准备好了");
    }

    private void openEditor(Wheel existing, Screen returnScreen) {
        editorReturnScreen = returnScreen == Screen.LIST ? Screen.LIST : Screen.MAIN;
        editorCreating = existing == null;
        editorWheel = existing == null ? applyAppDefaults(new Wheel()) : existing;
        navigateTo(Screen.EDITOR);
    }

    private void renderEditorScreen() {
        useDarkBars();
        Wheel existing = editorCreating ? null : editorWheel;
        Wheel w = editorWheel;
        LinearLayout root = darkScreen();
        LinearLayout bar = row(Gravity.CENTER_VERTICAL); root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
        bar.addView(iconButton("‹", "返回", v -> onBackPressed(), true), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label(existing == null ? "新建转盘" : "编辑转盘", 22, DARK_TEXT, true); bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(iconButton("✓", "保存", v -> {}, true), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView saveButton = (TextView) bar.getChildAt(2);

        ScrollView scroll = new ScrollView(this); LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); scroll.addView(p); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        EditText name = input("问题 / 转盘标题", w.name, true); p.addView(name, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView optionTitle = label("选项", 13, DARK_SUB, true); optionTitle.setPadding(0, dp(16), 0, dp(8)); p.addView(optionTitle);
        LinearLayout optionRows = new LinearLayout(this); optionRows.setOrientation(LinearLayout.VERTICAL); p.addView(optionRows);
        ArrayList<OptionDraft> drafts = new ArrayList<>(); for (WheelOption o : w.options) drafts.add(new OptionDraft(o)); if (existing == null && drafts.isEmpty()) { drafts.add(new OptionDraft("选项 1")); drafts.add(new OptionDraft("选项 2")); }
        Runnable[] render = new Runnable[1];
        render[0] = () -> renderOptionRows(optionRows, drafts, render[0], true); render[0].run();
        LinearLayout editorActions = row(Gravity.CENTER_VERTICAL); p.addView(editorActions, new LinearLayout.LayoutParams(-1, dp(54)));
        editorActions.addView(actionButton("添加选项", true, v -> { captureAll(drafts); drafts.add(new OptionDraft()); render[0].run(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(0, dp(48), 1); advLp.setMargins(dp(10),0,0,0); editorActions.addView(actionButton("高级设置", false, v -> showAdvancedDialog(w, drafts)), advLp);
        TextView footer = label("预览：权重 / 分组 / 时长 / 配色 / 字体 / 声音 / TTS 可在高级设置中调整", 13, DARK_SUB, false); footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(18), 0, dp(22)); p.addView(footer);
        saveButton.setOnClickListener(v -> {
            String wheelName = name.getText().toString().trim(); if (wheelName.isEmpty()) { toast("问题不能为空"); return; }
            ArrayList<WheelOption> parsed = new ArrayList<>(); for (int i = 0; i < drafts.size(); i++) { OptionDraft draft = drafts.get(i); String error = draft.validate(); if (error != null) { toast("第 " + (i + 1) + " 行：" + error); return; } parsed.add(draft.toOption()); }
            if (parsed.isEmpty()) { toast("至少需要一个选项"); return; }
            w.name = wheelName; w.options = parsed; w.updatedAt = System.currentTimeMillis(); if (editorCreating) library.wheels.add(w); save(); select(w);
            editorCreating = false; editorWheel = null; navigateTo(editorReturnScreen);
        });
        setContentView(root);
    }

    private void showAdvancedDialog(Wheel w, ArrayList<OptionDraft> drafts) {
        refreshMainPalette();
        ScrollView scroll = new ScrollView(this); LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(18), dp(10), dp(18), dp(8)); scroll.addView(p);
        TextView weights = emptyText("权重：每个选项卡片中的真权重决定抽中概率；显示权重决定扇区大小。", darkMain); weights.setGravity(Gravity.START); p.addView(weights);
        Spinner groups = new Spinner(this); ArrayList<GroupChoice> choices = groupChoices(false, "未分组"); groups.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices)); for (int i = 0; i < choices.size(); i++) if (Objects.equals(choices.get(i).id, w.groupId)) groups.setSelection(i); p.addView(groups);
        Button addGroup = primaryButton("新建分组"); addGroup.setOnClickListener(v -> newGroup(id -> { choices.clear(); choices.addAll(groupChoices(false, "未分组")); groups.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices)); for(int i=0;i<choices.size();i++) if(Objects.equals(choices.get(i).id,id)) groups.setSelection(i); })); p.addView(addGroup, new LinearLayout.LayoutParams(-1, dp(46)));
        EditText dur = input("旋转时长秒", String.valueOf(w.settings.rotationDurationMs / 1000), darkMain); p.addView(dur);
        Spinner colors = new Spinner(this); ArrayList<ColorSchemeChoice> colorChoices = colorSchemeChoices(); colors.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, colorChoices)); String currentColor = WheelFormats.normalizeColorScheme(w.settings.colorScheme); for (int i=0;i<colorChoices.size();i++) if(colorChoices.get(i).scheme.equals(currentColor)) colors.setSelection(i); p.addView(colors);
        EditText font = input("字体大小", String.valueOf(w.settings.fontSize), darkMain); p.addView(font);
        CheckBox tick = check("经过选项音效", w.settings.tickSoundEnabled); p.addView(tick); CheckBox sel = check("选中音效", w.settings.selectedSoundEnabled); p.addView(sel); CheckBox say = check("系统 TTS", w.settings.ttsEnabled); p.addView(say);
        Spinner locale = new Spinner(this); ArrayList<LocaleChoice> locales = localeChoices("使用应用默认"); String currentTag = w.settings.ttsLanguageTag == null ? "" : w.settings.ttsLanguageTag; boolean foundTag = false; for(LocaleChoice choice:locales) if(choice.tag.equals(currentTag)) foundTag = true; if(!foundTag && !currentTag.isEmpty()) locales.add(new LocaleChoice("导入语言：" + currentTag, currentTag)); locale.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, locales)); for(int i=0;i<locales.size();i++) if(locales.get(i).tag.equals(currentTag)) locale.setSelection(i); locale.setEnabled(say.isChecked()); say.setOnCheckedChangeListener((b,checked)->locale.setEnabled(checked)); p.addView(locale);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("高级设置").setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { captureAll(drafts); w.groupId = choices.get(groups.getSelectedItemPosition()).id; w.settings.rotationDurationMs = Math.max(1, longOr(dur,5)) * 1000; w.settings.colorScheme = colorChoices.get(colors.getSelectedItemPosition()).scheme; w.settings.fontSize = (int)Math.max(1,longOr(font,16)); w.settings.tickSoundEnabled = tick.isChecked(); w.settings.selectedSoundEnabled = sel.isChecked(); w.settings.ttsEnabled = say.isChecked(); w.settings.ttsLanguageTag = locales.get(locale.getSelectedItemPosition()).tag; dialog.dismiss(); toast("高级设置已应用"); }));
        dialog.show();
    }

    private Wheel applyAppDefaults(Wheel w) { AppSettings s = library.settings; w.settings.rotationDurationMs = s.defaultRotationDurationMs; w.settings.colorScheme = s.defaultColorScheme; w.settings.fontSize = s.defaultFontSize; w.settings.tickSoundEnabled = s.tickSoundEnabled; w.settings.selectedSoundEnabled = s.selectedSoundEnabled; w.settings.ttsEnabled = s.ttsEnabled; w.settings.ttsLanguageTag = ""; return w; }

    private void editAppSettings() {
        refreshMainPalette();
        AppSettings s = library.settings;
        ScrollView scroll = new ScrollView(this); LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(20), dp(8), dp(20), 0); scroll.addView(p);
        TextView help = emptyText("应用设置控制全局文字显示和默认值；已存在转盘会立即使用这里的文字显示设置。", darkMain); help.setGravity(Gravity.START); p.addView(help);
        EditText dur = input("默认旋转时长秒", String.valueOf(s.defaultRotationDurationMs / 1000), darkMain); p.addView(dur);
        Spinner colors = new Spinner(this); ArrayList<ColorSchemeChoice> colorChoices = colorSchemeChoices(); colors.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, colorChoices)); String currentColor=WheelFormats.normalizeColorScheme(s.defaultColorScheme); for(int i=0;i<colorChoices.size();i++) if(colorChoices.get(i).scheme.equals(currentColor)) colors.setSelection(i); p.addView(colors);
        EditText font = input("默认字体大小", String.valueOf(s.defaultFontSize), darkMain); p.addView(font);
        CheckBox tick = check("默认经过选项音效", s.tickSoundEnabled); p.addView(tick); CheckBox sel = check("默认选中音效", s.selectedSoundEnabled); p.addView(sel); CheckBox say = check("默认系统 TTS", s.ttsEnabled); p.addView(say);
        Spinner locale = new Spinner(this); ArrayList<LocaleChoice> locales = localeChoices("跟随系统"); String currentTag=s.defaultTtsLanguageTag==null?"":s.defaultTtsLanguageTag; boolean foundTag=false; for(LocaleChoice choice:locales)if(choice.tag.equals(currentTag))foundTag=true; if(!foundTag&&!currentTag.isEmpty())locales.add(new LocaleChoice("导入语言："+currentTag,currentTag)); locale.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, locales)); for(int i=0;i<locales.size();i++) if(locales.get(i).tag.equals(currentTag)) locale.setSelection(i); locale.setEnabled(say.isChecked()); say.setOnCheckedChangeListener((b,checked)->locale.setEnabled(checked)); p.addView(locale);
        Spinner textMode = new Spinner(this); ArrayList<TextModeChoice> textModes = textModeChoices(); textMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, textModes)); String mode=WheelTextLayout.normalizeTextDisplayMode(s.textDisplayMode); for(int i=0;i<textModes.size();i++) if(textModes.get(i).mode.equals(mode)) textMode.setSelection(i); p.addView(textMode);
        CheckBox radialAuto = check("径向文字自动缩小", s.radialTextAutoSize); radialAuto.setEnabled("radial".equals(mode)); textMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){ public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id){ radialAuto.setEnabled("radial".equals(textModes.get(pos).mode)); } public void onNothingSelected(android.widget.AdapterView<?> parent){} }); p.addView(radialAuto);
        CheckBox ellipsize = check("过长文字使用省略号", s.ellipsizeText); p.addView(ellipsize);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("应用设置").setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { s.defaultRotationDurationMs = Math.max(1, longOr(dur, 5)) * 1000; s.defaultColorScheme = colorChoices.get(colors.getSelectedItemPosition()).scheme; s.defaultFontSize = (int)Math.max(1, longOr(font, 16)); s.tickSoundEnabled = tick.isChecked(); s.selectedSoundEnabled = sel.isChecked(); s.ttsEnabled = say.isChecked(); s.defaultTtsLanguageTag = locales.get(locale.getSelectedItemPosition()).tag; s.textDisplayMode = textModes.get(textMode.getSelectedItemPosition()).mode; s.radialTextAutoSize = radialAuto.isChecked(); s.ellipsizeText = ellipsize.isChecked(); save(); if(wheelView!=null) wheelView.invalidate(); dialog.dismiss(); toast("应用设置已保存"); }));
        dialog.show();
    }

    private void renderOptionRows(LinearLayout container, ArrayList<OptionDraft> drafts, Runnable render, boolean dark) {
        container.removeAllViews();
        for (int i = 0; i < drafts.size(); i++) {
            final int index = i; OptionDraft d = drafts.get(i);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(6), dp(4), dp(6), dp(4));
            box.setBackground(round(dark ? DARK_CARD : 0xffffffff, dp(14), dark ? 0xff263244 : 0xffe5e0d8, 1));

            // Drag handle (⠿) — long-press + drag to reorder (no system shadow)
            TextView handle = new TextView(this);
            handle.setText("⠿"); handle.setTextSize(16);
            handle.setTextColor(dark ? 0xff64748b : 0xffaaaaaa);
            handle.setGravity(Gravity.CENTER);
            handle.setPadding(dp(4), 0, dp(6), 0);
            handle.setOnTouchListener(new View.OnTouchListener() {
                final Handler handler = new Handler(Looper.getMainLooper());
                boolean dragging;
                float startY;
                int targetIndex = index;
                final Runnable longPress = () -> {
                    if (!box.isAttachedToWindow()) return;
                    dragging = true;
                    box.setElevation(dp(8)); box.setTranslationZ(dp(8));
                    ViewParent parent = container.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                };

                void preview(int target) {
                    target = Math.max(0, Math.min(target, container.getChildCount() - 1));
                    if (target == targetIndex) return;
                    targetIndex = target;
                    int distance = box.getHeight() + dp(6);
                    for (int child = 0; child < container.getChildCount(); child++) {
                        View row = container.getChildAt(child);
                        if (row == box) continue;
                        float shift = 0;
                        if (targetIndex > index && child > index && child <= targetIndex) shift = -distance;
                        else if (targetIndex < index && child >= targetIndex && child < index) shift = distance;
                        row.animate().cancel();
                        row.animate().translationY(shift).setDuration(180)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    }
                }

                void finishDrag(boolean commit) {
                    handler.removeCallbacks(longPress);
                    ViewParent parent = container.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                    if (!dragging) return;
                    dragging = false;
                    box.setElevation(0); box.setTranslationZ(0);
                    if (commit && targetIndex != index && index < drafts.size()) {
                        captureAll(drafts);
                        OptionDraft moved = drafts.remove(index);
                        drafts.add(Math.max(0, Math.min(targetIndex, drafts.size())), moved);
                    }
                    for (int child = 0; child < container.getChildCount(); child++) {
                        container.getChildAt(child).animate().cancel();
                        container.getChildAt(child).setTranslationY(0);
                    }
                    container.post(render);
                }

                @Override public boolean onTouch(View v, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startY = ev.getRawY(); targetIndex = index; dragging = false;
                            handler.postDelayed(longPress, 350); return true;
                        case MotionEvent.ACTION_MOVE:
                            if (!dragging) {
                                if (Math.abs(ev.getRawY() - startY) > dp(12)) handler.removeCallbacks(longPress);
                                return true;
                            }
                            float translation = ev.getRawY() - startY;
                            box.setTranslationY(translation);
                            float center = box.getTop() + box.getHeight() / 2f + translation;
                            int target = index;
                            for (int child = 0; child < container.getChildCount(); child++) {
                                if (child == index) continue;
                                View row = container.getChildAt(child);
                                if (center > row.getTop() + row.getHeight() / 2f) target = Math.max(target, child);
                                if (center < row.getTop() + row.getHeight() / 2f) { target = Math.min(target, child); break; }
                            }
                            preview(target); return true;
                        case MotionEvent.ACTION_UP:
                            finishDrag(true); return true;
                        case MotionEvent.ACTION_CANCEL:
                            finishDrag(false); return true;
                    }
                    return false;
                }
            });
            box.addView(handle, new LinearLayout.LayoutParams(-2, dp(38)));

            d.text = input("选项名字", d.textValue, dark);
            d.text.setSingleLine(true); d.text.setLongClickable(false);
            d.text.setTextSize(14);
            box.addView(d.text, new LinearLayout.LayoutParams(0, dp(38), 1));

            d.trueWeight = input("权重", d.trueValue, dark);
            d.trueWeight.setSingleLine(true); d.trueWeight.setLongClickable(false);
            d.trueWeight.setTextSize(13);
            LinearLayout.LayoutParams tw = new LinearLayout.LayoutParams(dp(56), dp(38));
            tw.setMargins(dp(3), 0, dp(3), 0);
            box.addView(d.trueWeight, tw);

            d.fakeWeight = input("显示", d.fakeValue, dark);
            d.fakeWeight.setSingleLine(true); d.fakeWeight.setLongClickable(false);
            d.fakeWeight.setTextSize(13);
            LinearLayout.LayoutParams fw = new LinearLayout.LayoutParams(dp(56), dp(38));
            fw.setMargins(0, 0, dp(3), 0);
            box.addView(d.fakeWeight, fw);

            // Delete × button (red)
            TextView del = new TextView(this);
            del.setText("✕"); del.setTextSize(15);
            del.setTextColor(0xffef4444);
            del.setTypeface(Typeface.DEFAULT_BOLD);
            del.setGravity(Gravity.CENTER);
            del.setPadding(dp(6), 0, dp(4), 0);
            del.setOnClickListener(v -> { captureAll(drafts); drafts.remove(index); render.run(); });
            box.addView(del, new LinearLayout.LayoutParams(-2, dp(38)));

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.setMargins(0, 0, 0, dp(6));
            container.addView(box, blp);
        }
    }
    private void captureAll(ArrayList<OptionDraft> drafts) { for (OptionDraft d : drafts) if (d.text != null) d.capture(); }

    private void newGroup() { newGroup(null); }
    private void newGroup(java.util.function.Consumer<String> created) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = input("分组名", "", darkMain); p.addView(name);
        Spinner parent = new Spinner(this); ArrayList<GroupChoice> choices = groupChoices(false, "无上级分组"); parent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices)); p.addView(parent);
        Button nested=primaryButton("先创建父分组"); p.addView(nested,new LinearLayout.LayoutParams(-1,dp(46)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("新建分组").setView(p).setPositiveButton("保存",null).setNegativeButton("取消", null).create();
        nested.setOnClickListener(v -> newGroup(parentId -> { choices.clear(); choices.addAll(groupChoices(false,"无上级分组")); parent.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices)); for(int i=0;i<choices.size();i++)if(Objects.equals(choices.get(i).id,parentId))parent.setSelection(i); }));
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{ String value=name.getText().toString().trim(); if(value.isEmpty()){toast("分组名不能为空");return;} String parentId=choices.get(parent.getSelectedItemPosition()).id; for(WheelGroup existing:library.groups)if(Objects.equals(existing.parentId,parentId)&&existing.name.equalsIgnoreCase(value)){toast("同一上级分组下不能有重名分组");return;} WheelGroup g=new WheelGroup(); g.name=value; g.parentId=parentId; library.groups.add(g); save(); select(selectedWheel); dialog.dismiss(); if(created!=null)created.accept(g.id); })); dialog.show();
    }

    private void deleteCurrent() { if (selectedWheel == null) return; new AlertDialog.Builder(this).setTitle("删除转盘？").setMessage(selectedWheel.name).setPositiveButton("删除", (d,w) -> { library.wheels.remove(selectedWheel); selectedWheel = library.wheels.isEmpty() ? null : library.wheels.get(0); save(); select(selectedWheel); }).setNegativeButton("取消", null).show(); }

    private void spin() {
        if (selectedWheel == null || selectedWheel.options.isEmpty()) { toast("请先新建转盘"); return; }
        if (wheelView.spinning) return;
        SpinEngine.SpinPlan plan = engine.createPlan(selectedWheel, wheelView.rotation);
        liveOption.setText("旋转中…");
        wheelView.spin(plan, o -> { liveOption.setText(o.text); if(selectedWheel.settings.tickSoundEnabled) spinSound.tick(); }, () -> { liveOption.setText(plan.target().text); if(selectedWheel.settings.selectedSoundEnabled) spinSound.selected(); speakResult(plan.target().text); });
    }

    private void speakResult(String value) {
        if (!selectedWheel.settings.ttsEnabled || !ttsReady) return;
        String tag=selectedWheel.settings.ttsLanguageTag; if(tag==null||tag.isEmpty()) tag=library.settings.defaultTtsLanguageTag; Locale requested=tag==null||tag.isEmpty()?Locale.getDefault():Locale.forLanguageTag(tag);
        int support=tts.setLanguage(requested); if(support==TextToSpeech.LANG_MISSING_DATA||support==TextToSpeech.LANG_NOT_SUPPORTED){ Locale fallback=Locale.getDefault(); support=tts.setLanguage(fallback); }
        if(support==TextToSpeech.LANG_MISSING_DATA||support==TextToSpeech.LANG_NOT_SUPPORTED){ toast("TTS 不支持所选语言"); return; }
        tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,"wheel-result");
    }

    protected void onActivityResult(int req, int res, Intent data) { super.onActivityResult(req,res,data); if(res!=RESULT_OK||data==null||data.getData()==null)return; Uri u=data.getData(); try { if(req==REQ_IMPORT_PWH){ for(Wheel w: WheelFormats.importPwh(read(u), selectedGroupId)){applyAppDefaults(w); w.name=library.uniqueWheelName(w.name); library.wheels.add(w); selectedWheel=w;} save(); buildMainUi(); select(selectedWheel); toast("PWH 导入完成"); } else if(req==REQ_IMPORT_WWD){ WheelLibrary l=WheelFormats.importWwd(read(u)); library.settings=l.settings; library.groups.addAll(l.groups); library.wheels.addAll(l.wheels); if(!l.wheels.isEmpty()) selectedWheel=l.wheels.get(0); save(); buildMainUi(); select(selectedWheel); toast("WWD 导入完成"); } else if(req==REQ_EXPORT_PWH){ write(u, WheelFormats.exportPwh(selectedWheel==null?library.wheels:java.util.List.of(selectedWheel))); toast("PWH 导出完成"); } else if(req==REQ_EXPORT_WWD){ write(u, WheelFormats.exportWwd(library,false)); toast("WWD 导出完成"); } } catch(Exception e){ toast(e.getMessage()); } }
    private void open(int req,String type){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(type); startActivityForResult(i,req); }
    private void create(int req,String name){ Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/octet-stream"); i.putExtra(Intent.EXTRA_TITLE,name); startActivityForResult(i,req); }
    private byte[] read(Uri u) throws IOException { try(InputStream in=getContentResolver().openInputStream(u)){ return in.readAllBytes(); } }
    private void write(Uri u, byte[] b) throws IOException { try(OutputStream out=getContentResolver().openOutputStream(u)){ out.write(b); } }

    private LinearLayout row(int gravity) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(gravity); return l; }
    private LinearLayout darkScreen() { LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(DARK_BG); root.setPadding(dp(16), statusBarHeight() + dp(8), dp(16), navBarHeight() + dp(12)); return root; }
    private TextView label(String s, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView iconPlain(String text, String desc, View.OnClickListener l, int color, int sizeDp) { TextView v = label(text, sizeDp, color, false); v.setGravity(Gravity.CENTER); v.setContentDescription(desc); v.setOnClickListener(l); return v; }
    private View circleBtn(String text, View.OnClickListener l, boolean dark) { TextView v = label(text, 22, dark ? 0xffaab3c2 : 0xff888888, false); v.setGravity(Gravity.CENTER); v.setBackground(round(dark ? 0xff1f2937 : 0xfff6f6f8, dp(22), 0, 0)); v.setOnClickListener(l); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48)); v.setLayoutParams(lp); return v; }
    private TextView chip(String s, boolean dark) { TextView v = label(s, 13, dark ? DARK_TEXT : text, true); v.setGravity(Gravity.CENTER); v.setPadding(dp(12), 0, dp(12), 0); v.setBackground(round(dark ? 0xff1f2937 : (darkMain ? 0xff182235 : 0xffffffff), dp(18), dark ? 0xff334155 : stroke, 1)); return v; }
    private EditText input(String hint, String value, boolean dark) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextColor(dark ? DARK_TEXT : text); e.setHintTextColor(dark ? 0xff64748b : 0xff9aa3af); e.setSingleLine(false); e.setBackground(round(dark ? DARK_PANEL : 0xffffffff, dp(12), dark ? 0xff263244 : stroke, 1)); e.setPadding(dp(12),0,dp(12),0); return e; }
    private CheckBox check(String label, boolean checked) { CheckBox c = new CheckBox(this); c.setText(label); c.setTextColor(text); c.setChecked(checked); return c; }
    private TextView emptyText(String value, boolean dark) { TextView v = label(value, 14, dark ? DARK_SUB : sub, false); v.setGravity(Gravity.CENTER); v.setPadding(0, dp(24), 0, dp(24)); return v; }
    private Button primaryButton(String value) { Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(round(ACCENT, dp(16), 0, 0)); return b; }
    private Button actionButton(String value, boolean primary, View.OnClickListener l) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT_BOLD); b.setTextColor(primary ? Color.WHITE : text); b.setBackground(round(primary ? ACCENT : panel, dp(18), primary ? 0 : stroke, primary ? 0 : 1)); b.setOnClickListener(l); return b; }
    private TextView iconButton(String value, String desc, View.OnClickListener l, boolean dark) { TextView v = label(value, 27, dark ? DARK_TEXT : text, true); v.setGravity(Gravity.CENTER); v.setContentDescription(desc); v.setBackground(round(dark ? DARK_PANEL : panel, dp(16), dark ? 0xff263244 : stroke, 1)); v.setOnClickListener(l); return v; }
    private TextView smallDarkButton(String value, View.OnClickListener l) { TextView v = label(value, 13, DARK_TEXT, true); v.setGravity(Gravity.CENTER); v.setBackground(round(0xff0f172a, dp(12), 0xff263244, 1)); v.setOnClickListener(l); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1); lp.setMargins(dp(3),0,dp(3),0); v.setLayoutParams(lp); return v; }
    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); if(strokeWidth>0)g.setStroke(strokeWidth, strokeColor); return g; }
    private long longOr(EditText e,long d){ try{return Long.parseLong(e.getText().toString().trim());}catch(Exception ex){return d;} }
    private void toast(String s){ Toast.makeText(this, s==null?"完成":s, Toast.LENGTH_LONG).show(); }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    private int statusBarHeight(){ int id=getResources().getIdentifier("status_bar_height","dimen","android"); return id>0?getResources().getDimensionPixelSize(id):0; }
    private int navBarHeight(){ int id=getResources().getIdentifier("navigation_bar_height","dimen","android"); return id>0?getResources().getDimensionPixelSize(id):0; }
    private ArrayList<LocaleChoice> localeChoices(String emptyLabel){ ArrayList<LocaleChoice> out=new ArrayList<>(); out.add(new LocaleChoice(emptyLabel,"")); out.add(new LocaleChoice("简体中文","zh-CN")); out.add(new LocaleChoice("繁體中文","zh-TW")); out.add(new LocaleChoice("English (US)","en-US")); out.add(new LocaleChoice("English (UK)","en-GB")); out.add(new LocaleChoice("日本語","ja-JP")); out.add(new LocaleChoice("한국어","ko-KR")); out.add(new LocaleChoice("Français","fr-FR")); out.add(new LocaleChoice("Deutsch","de-DE")); out.add(new LocaleChoice("Español","es-ES")); return out; }
    private ArrayList<ColorSchemeChoice> colorSchemeChoices(){ ArrayList<ColorSchemeChoice> out=new ArrayList<>(); out.add(new ColorSchemeChoice("经典", "classic")); out.add(new ColorSchemeChoice("柔和", "pastel")); out.add(new ColorSchemeChoice("鲜艳", "vivid")); out.add(new ColorSchemeChoice("黑白", "mono")); return out; }
    private ArrayList<TextModeChoice> textModeChoices(){ ArrayList<TextModeChoice> out=new ArrayList<>(); out.add(new TextModeChoice("浮动文字", "floating")); out.add(new TextModeChoice("径向文字", "radial")); return out; }
    private ArrayList<GroupChoice> groupChoices(boolean filter){ return groupChoices(filter, filter?"全部":"未分组"); }
    private ArrayList<GroupChoice> groupChoices(boolean filter,String rootLabel){ ArrayList<GroupChoice> c=new ArrayList<>(); c.add(new GroupChoice(rootLabel,null)); addGroupChoices(c,null,"",new HashSet<>()); return c; }
    private void addGroupChoices(ArrayList<GroupChoice> c,String pid,String pre,Set<String> path){ for(WheelGroup g:library.groups) if(Objects.equals(g.parentId,pid)&&g.id!=null&&!path.contains(g.id)){ c.add(new GroupChoice(pre+g.name,g.id)); HashSet<String> next=new HashSet<>(path);next.add(g.id);addGroupChoices(c,g.id,pre+"  / ",next); } }
    private String groupName(String id){ if(id==null)return "未分组"; for(WheelGroup g:library.groups) if(Objects.equals(g.id,id)) return g.name; return "未分组"; }
    private String safeName(String s){ return s == null ? "export" : s.replaceAll("[^a-zA-Z0-9._-]+", "_"); }
    private record GroupChoice(String label,String id){ public String toString(){return label;} }
    private record LocaleChoice(String label,String tag){ public String toString(){return label;} }
    private record ColorSchemeChoice(String label,String scheme){ public String toString(){return label;} }
    private record TextModeChoice(String label,String mode){ public String toString(){return label;} }
    private final class OptionDraft { final String id; String textValue="", trueValue="", fakeValue=""; EditText text,trueWeight,fakeWeight; OptionDraft(){id=Models.newId();} OptionDraft(String label){id=Models.newId();textValue=label;} OptionDraft(WheelOption o){id=o.id;textValue=o.text;trueValue=String.valueOf(o.trueWeight);fakeValue=o.fakeWeight==null?"":String.valueOf(o.fakeWeight);} void capture(){textValue=text.getText().toString();trueValue=trueWeight.getText().toString();fakeValue=fakeWeight.getText().toString();} private double w(String v,double def){if(v==null||v.trim().isEmpty())return def; try{return Double.parseDouble(v.trim());}catch(Exception e){return def;} } String validate(){capture(); if(textValue.trim().isEmpty())return "名字不能为空"; double tv=w(trueValue,1); if(!Double.isFinite(tv)||tv<=0)return "真权重必须是大于 0 的有限数字"; if(!fakeValue.trim().isEmpty()){double fv=w(fakeValue,1); if(!Double.isFinite(fv)||fv<=0)return "显示权重必须是大于 0 的有限数字";} return null;} WheelOption toOption(){WheelOption o=new WheelOption();o.id=id;o.text=textValue.trim();o.trueWeight=w(trueValue,1);o.fakeWeight=fakeValue.trim().isEmpty()?null:w(fakeValue,1);return o;} }

    private void seed(){ if(!library.wheels.isEmpty())return; Wheel w=applyAppDefaults(new Wheel()); w.name="今天吃什么"; w.options.add(new WheelOption("火锅",1,null)); w.options.add(new WheelOption("烤肉",2,null)); w.options.add(new WheelOption("米饭",1,null)); w.options.add(new WheelOption("面",1,null)); library.wheels.add(w); selectedWheel=w; }
    private void load(){ try{ String s=getPreferences(0).getString("library",null); if(s!=null) library=WheelFormats.importWwd(s.getBytes(StandardCharsets.UTF_8)); }catch(Exception ignored){} }
    private void save(){ try{ getPreferences(0).edit().putString("library", new String(WheelFormats.exportWwd(library,false), StandardCharsets.UTF_8)).apply(); }catch(Exception e){toast("保存失败");} }

    private final class WheelView extends View {
        Wheel wheel; double rotation; boolean spinning; String last; long lastTick;
        WheelView(Context c){ super(c); setBackgroundColor(Color.TRANSPARENT); }
        void setWheel(Wheel w){ wheel=w; invalidate(); }
        void spin(SpinEngine.SpinPlan plan, java.util.function.Consumer<WheelOption> pass, Runnable done){ spinning=true; last=null; lastTick=0; rotation=normalizeRotation(rotation); final long start=SystemClock.elapsedRealtimeNanos(); double from=rotation,to=rotation+plan.totalRotation(); postOnAnimation(new Runnable(){ public void run(){ double elapsedMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0; double t=Math.min(1,elapsedMs/plan.durationMs()); double eased=1-Math.pow(1-t,3); rotation=from+(to-from)*eased; WheelOption cur=engine.optionAtPointer(wheel,rotation); long now=SystemClock.elapsedRealtime(); if(cur!=null&&!Objects.equals(cur.id,last)&&now-lastTick>50){last=cur.id;lastTick=now;pass.accept(cur);} invalidate(); if(t<1)postOnAnimation(this); else{rotation=normalizeRotation(to);spinning=false;invalidate();done.run();}}}); }
        private double normalizeRotation(double value){ value%=360.0; return value<0?value+360.0:value; }
        protected void onDraw(Canvas c){ super.onDraw(c); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); int size=Math.min(getWidth(),getHeight())-dp(48), x=(getWidth()-size)/2, y=(getHeight()-size)/2; if(wheel==null){p.setTextSize(dp(18));p.setColor(sub);p.setTextAlign(Paint.Align.CENTER);c.drawText("请先新建转盘",getWidth()/2f,getHeight()/2f,p);return;} int[] cs=colors(wheel.settings.colorScheme); RectF oval=new RectF(x,y,x+size,y+size); java.util.List<SpinEngine.Segment> segs=engine.segments(wheel); drawSectors(c,p,oval,segs,cs); drawLabels(c,p,x,y,size,segs); drawRimPointerAndHub(c,p,oval,y); }
        private void drawSectors(Canvas c, Paint p, RectF oval, java.util.List<SpinEngine.Segment> segs, int[] cs){ p.setStyle(Paint.Style.FILL); for(int i=0;i<segs.size();i++){ SpinEngine.Segment s=segs.get(i); p.setColor(cs[i%cs.length]); c.drawArc(oval,(float)(s.startAngle()+rotation),(float)s.sweepAngle(),true,p); } float cx=oval.centerX(),cy=oval.centerY(),r=oval.width()/2f; p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(0x99ffffff); for(SpinEngine.Segment s:segs){ double rad=Math.toRadians(s.startAngle()+rotation); c.drawLine(cx,cy,cx+(float)Math.cos(rad)*r,cy+(float)Math.sin(rad)*r,p); } }
        private void drawLabels(Canvas c, Paint p, int x, int y, int size, java.util.List<SpinEngine.Segment> segs){ p.setStyle(Paint.Style.FILL); p.setColor(0xff111827); p.setTypeface(Typeface.DEFAULT_BOLD); String mode=WheelTextLayout.normalizeTextDisplayMode(library.settings.textDisplayMode); for(SpinEngine.Segment s:segs){ if("radial".equals(mode)) drawRadialLabel(c,p,x,y,size,s); else drawFloatingLabel(c,p,x,y,size,s); } }
        private void drawFloatingLabel(Canvas c, Paint p, int x, int y, int size, SpinEngine.Segment s){ float textSize=wheel.settings.fontSize*getResources().getDisplayMetrics().scaledDensity; p.setTextSize(textSize); p.setTextAlign(Paint.Align.CENTER); double mid=Math.toRadians(s.startAngle()+s.sweepAngle()/2+rotation); float maxWidth=(float)Math.max(dp(24), size*Math.sin(Math.toRadians(Math.max(2, s.sweepAngle()))/2.0)*0.72); WheelTextLayout.LabelLayout label=WheelTextLayout.floatingLabel(s.option().text,maxWidth,textSize,library.settings.ellipsizeText,(txt,value)->{ p.setTextSize(value); return p.measureText(txt); }); if(!label.draw)return; p.setTextSize(label.textSize); Paint.FontMetrics fm=p.getFontMetrics(); float baseline=(float)(y+size/2+Math.sin(mid)*size*.27-(fm.ascent+fm.descent)/2); c.drawText(label.text,(float)(x+size/2+Math.cos(mid)*size*.27),baseline,p); }
        private void drawRadialLabel(Canvas c, Paint p, int x, int y, int size, SpinEngine.Segment s){ float base=wheel.settings.fontSize*getResources().getDisplayMetrics().scaledDensity; float min=Math.max(dp(8),base*0.58f); float maxWidth=size*0.39f; WheelTextLayout.LabelLayout label=WheelTextLayout.radialLabel(s.option().text,base,min,maxWidth,s.sweepAngle(),library.settings.radialTextAutoSize,library.settings.ellipsizeText,(txt,value)->{ p.setTextSize(value); return p.measureText(txt); }); if(!label.draw)return; p.setTextSize(label.textSize); p.setTextAlign(Paint.Align.LEFT); float cx=x+size/2f, cy=y+size/2f, start=size*0.13f; Paint.FontMetrics fm=p.getFontMetrics(); float baseline=-(fm.ascent+fm.descent)/2; c.save(); c.rotate((float)(s.startAngle()+s.sweepAngle()/2+rotation),cx,cy); c.drawText(label.text,cx+start,cy+baseline,p); c.restore(); }
        private void drawRimPointerAndHub(Canvas c, Paint p, RectF oval, int y) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(4)); p.setColor(0xffffffff); c.drawOval(oval, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xffffffff);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        int hubR = Math.max(dp(14), (int) (oval.width() * 0.065f));
        // Pointer extends above the hub so it's visible against wheel sectors
        float ptrH = hubR * 0.4f, ptrW = hubR * 0.35f;
        Path ptr = new Path();
        ptr.moveTo(cx, cy - hubR - ptrH);            // top tip (above hub)
        ptr.lineTo(cx - ptrW, cy - hubR + dp(2));      // bottom-left
        ptr.lineTo(cx + ptrW, cy - hubR + dp(2));      // bottom-right
        ptr.close();
        c.drawPath(ptr, p);
        // Hub circle drawn AFTER (on top of) pointer base
        c.drawCircle(cx, cy, hubR, p);
    }
        private int[] colors(String s){ s=WheelFormats.normalizeColorScheme(s); if("pastel".equals(s))return new int[]{0xffffb3ba,0xffffdfba,0xffffffba,0xffbaffc9,0xffbae1ff}; if("vivid".equals(s))return new int[]{0xffff5252,0xffffc107,0xff22c55e,0xff06b6d4,0xffd946ef}; if("mono".equals(s))return new int[]{0xffeeeeee,0xffbbbbbb,0xff888888,0xff555555}; return new int[]{0xffffb703,0xfffb8500,0xff8ecae6,0xff219ebc,0xffff006e,0xff8338ec}; }
    }
}
