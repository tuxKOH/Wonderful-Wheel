package com.wheel.app.android;

import android.app.*;
import android.Manifest;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_IMPORT_PWH = 10, REQ_IMPORT_WWD = 11, REQ_EXPORT_PWH = 12, REQ_EXPORT_WWD = 13, REQ_RENDER_STORAGE = 14;
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
    private Button spinBtn, autoSpinBtn, renderBtn;
    private Spinner renderTargetSpinner;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean renderCancelled = new AtomicBoolean();
    private Future<?> renderFuture;
    private AlertDialog renderDialog;
    private WheelOption pendingRenderTarget;
    private EditText autoSpinCount, editorNameInput, batchInput;
    private long activeSpinId, autoGeneration;
    private int autoTarget, autoCompleted;
    private String autoWheelId;
    private Runnable pendingAutoSpin;
    private Runnable autoFeedbackTimeout;
    private enum SpinMode { IDLE, MANUAL, AUTO_SPINNING, AUTO_WAITING }
    private SpinMode spinMode = SpinMode.IDLE;
    private enum Screen { MAIN, LIST, EDITOR, BATCH_EDITOR, IMPORT_PWH }
    private Screen currentScreen = Screen.MAIN;
    private Screen editorReturnScreen = Screen.MAIN;
    private Wheel editorWheel;
    private boolean editorCreating;
    private String editorNameDraft = "", batchText = "";
    private ArrayList<OptionDraft> editorDrafts;
    private ArrayList<Wheel> pendingPwhWheels;
    private final HashSet<Integer> pendingPwhSelection = new HashSet<>();
    private TextToSpeech tts;
    private boolean ttsReady;
    private SpinSoundPlayer spinSound;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        refreshMainPalette();
        tts = new TextToSpeech(this, this);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){runOnUiThread(()->finishAutoFeedback(id));}public void onError(String id){runOnUiThread(()->finishAutoFeedback(id));}});
        spinSound = new SpinSoundPlayer(getApplicationContext());
        load(); seed();
        if (selectedWheel == null && !library.wheels.isEmpty()) selectedWheel = library.wheels.get(0);
        restoreNavigationState(b);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleSystemBack);
        }
        renderCurrentScreen();
        consumeExternalImportIntent(getIntent());
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeExternalImportIntent(intent);
    }

    public void onInit(int status) { ttsReady = status == TextToSpeech.SUCCESS; }
    protected void onStop() { super.onStop(); stopAutoSpin(false); cancelCurrentSpin(false); if (spinSound != null) spinSound.cancelTicks(); if (tts != null) tts.stop(); }
    protected void onDestroy() { cancelRender(); renderExecutor.shutdownNow(); stopAutoSpin(false); cancelCurrentSpin(false); if (tts != null) { tts.stop(); tts.shutdown(); } if (spinSound != null) spinSound.release(); super.onDestroy(); }
    public void onBackPressed() { handleSystemBack(); }

    private void handleSystemBack() {
        if (currentScreen == Screen.IMPORT_PWH) cancelPwhImport();
        else if (currentScreen == Screen.LIST) navigateTo(Screen.MAIN);
        else if (currentScreen == Screen.BATCH_EDITOR) { captureBatch(); navigateTo(Screen.EDITOR); }
        else if (currentScreen == Screen.EDITOR) navigateTo(editorReturnScreen == Screen.LIST ? Screen.LIST : Screen.MAIN);
        else showExitConfirmation();
    }

    private void showExitConfirmation() {
        new AlertDialog.Builder(this).setTitle("退出").setMessage("确定退出应用？").setPositiveButton("退出", (d,w) -> finish()).setNegativeButton("取消", null).show();
    }

    private void navigateTo(Screen screen) { currentScreen = screen; renderCurrentScreen(); }
    private void renderCurrentScreen() {
        if (currentScreen != Screen.MAIN) { stopAutoSpin(false); cancelCurrentSpin(false); }
        if (currentScreen == Screen.LIST) renderListScreen();
        else if (currentScreen == Screen.IMPORT_PWH && pendingPwhWheels != null) renderPwhImportScreen();
        else if (currentScreen == Screen.BATCH_EDITOR && editorWheel != null) renderBatchEditorScreen();
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
        editorNameDraft = state.getString("editorNameDraft", editorWheel == null ? "" : editorWheel.name);
        batchText = state.getString("batchText", "");
        ArrayList<String> values = state.getStringArrayList("editorDrafts");
        if (values != null) { editorDrafts = new ArrayList<>(); for (int i=0;i+4<values.size();i+=5) editorDrafts.add(new OptionDraft(values.get(i),values.get(i+1),values.get(i+2),values.get(i+3),Boolean.parseBoolean(values.get(i+4)))); }
        if ((currentScreen == Screen.EDITOR || currentScreen == Screen.BATCH_EDITOR) && editorWheel == null) currentScreen = Screen.MAIN;
        if (currentScreen == Screen.IMPORT_PWH) currentScreen = Screen.MAIN;
    }

    protected void onSaveInstanceState(Bundle out) {
        captureEditor(); captureBatch();
        super.onSaveInstanceState(out);
        out.putString("currentScreen", currentScreen.name());
        out.putString("editorReturnScreen", editorReturnScreen.name());
        out.putString("selectedWheelId", selectedWheel == null ? null : selectedWheel.id);
        out.putString("selectedGroupId", selectedGroupId);
        out.putString("listSearchText", listSearchText);
        out.putBoolean("editorCreating", editorCreating);
        out.putString("editorWheelId", editorWheel == null ? null : editorWheel.id);
        out.putString("editorNameDraft", editorNameDraft);
        out.putString("batchText", batchText);
        if (editorDrafts != null) {
            ArrayList<String> values = new ArrayList<>();
            for (OptionDraft d : editorDrafts) { values.add(d.id); values.add(d.textValue); values.add(d.trueValue); values.add(d.fakeValue);values.add(String.valueOf(d.hiddenValue)); }
            out.putStringArrayList("editorDrafts", values);
        }
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
        stopAutoSpin(false);
        cancelCurrentSpin(false);
        refreshMainPalette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(darkMain ? bg : 0xffffffff);
        root.setPadding(dp(20), statusBarHeight() + dp(20), dp(20), navBarHeight() + dp(20));

        // Top bar: gear (left) + spacer + three-dot (right)
        LinearLayout topBar = row(Gravity.CENTER_VERTICAL);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, dp(56)));
        topBar.addView(iconPlain("⚙", "设置", v -> showSettingsSheet(), darkMain ? 0xffaab3c2 : 0xff555555, 24), new LinearLayout.LayoutParams(dp(56), dp(56)));
        Space topSp = new Space(this);
        topBar.addView(topSp, new LinearLayout.LayoutParams(0, 1, 1));
        topBar.addView(iconPlain("⋮", "更多操作", this::showOverflowMenu, darkMain ? 0xfff8fafc : 0xff333333, 24), new LinearLayout.LayoutParams(dp(56), dp(56)));

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

        // Bottom controls: manual, automatic, then offline-render row.
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(-1, dp(172)); controlsLp.setMargins(0, dp(12), 0, 0); root.addView(controls, controlsLp);
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER_VERTICAL); controls.addView(bottom, new LinearLayout.LayoutParams(-1, dp(56)));
        bottom.addView(circleBtn("≡", v -> showSettingsSheet(), darkMain));
        spinBtn = new Button(this); spinBtn.setTextSize(16); spinBtn.setTypeface(Typeface.DEFAULT); spinBtn.setAllCaps(false);
        spinBtn.setOnClickListener(v -> { if (spinMode == SpinMode.AUTO_SPINNING || spinMode == SpinMode.AUTO_WAITING) stopAutoSpin(true); else if (wheelView != null && wheelView.spinning) cancelCurrentSpin(true); else spin(); });
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(0, dp(48), 1); spinLp.setMargins(dp(16), 0, dp(16), 0); bottom.addView(spinBtn, spinLp);
        bottom.addView(circleBtn("✎", v -> openEditor(selectedWheel, Screen.MAIN), darkMain));
        LinearLayout autoRow = row(Gravity.CENTER_VERTICAL); controls.addView(autoRow, new LinearLayout.LayoutParams(-1, dp(56)));
        autoSpinCount = input("次数（留空持续）", "", darkMain); autoSpinCount.setSingleLine(true); autoSpinCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        autoRow.addView(autoSpinCount, new LinearLayout.LayoutParams(0, dp(46), 1));
        autoSpinBtn = actionButton("自动转", false, v -> { if (spinMode == SpinMode.AUTO_SPINNING || spinMode == SpinMode.AUTO_WAITING) stopAutoSpin(true); else startAutoSpin(); });
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(dp(126), dp(46)); autoLp.setMargins(dp(10),0,0,0); autoRow.addView(autoSpinBtn, autoLp);
        LinearLayout renderRow = row(Gravity.CENTER_VERTICAL); controls.addView(renderRow, new LinearLayout.LayoutParams(-1, dp(56)));
        renderTargetSpinner = new Spinner(this);
        renderRow.addView(renderTargetSpinner, new LinearLayout.LayoutParams(0, dp(46), 1));
        renderBtn = actionButton("渲染视频", false, v -> requestRender());
        LinearLayout.LayoutParams renderLp = new LinearLayout.LayoutParams(dp(126), dp(46)); renderLp.setMargins(dp(10),0,0,0); renderRow.addView(renderBtn, renderLp);
        updateRenderChoices();
        updateSpinControls();
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
        for (WheelOption o : src.options) {WheelOption copyOption=new WheelOption(o.text,o.trueWeight,o.fakeWeight);copyOption.hidden=o.hidden;copy.options.add(copyOption);}
        library.wheels.add(copy); save(); toast("已复制");
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "新建");
        menu.getMenu().add(0, 2, 1, "列表");
        menu.getMenu().add(0, 3, 2, "复位");
        menu.getMenu().add(0, 4, 3, "历史记录");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openEditor(null, Screen.MAIN);
            else if (item.getItemId() == 2) navigateTo(Screen.LIST);
            else if (item.getItemId() == 3) resetRuntimeState();
            else if (item.getItemId() == 4) showCurrentWheelHistory();
            return true;
        });
        menu.show();
    }

    private void resetRuntimeState() {
        stopAutoSpin(false);
        cancelCurrentSpin(false);
        activeSpinId = 0;
        if (spinSound != null) spinSound.cancelTicks();
        if (tts != null) tts.stop();
        updateSpinControls();
        if (liveOption != null) liveOption.setText(selectedWheel == null ? "请先新建转盘" : "准备好了");
        if (wheelView != null) wheelView.invalidate();
    }

    private void showCurrentWheelHistory() {
        if (selectedWheel == null) { toast("请先新建转盘"); return; }
        Wheel wheel = selectedWheel;
        ArrayList<SpinHistoryEntry> entries = new ArrayList<>();
        for (SpinHistoryEntry entry : library.history) if (Objects.equals(wheel.id, entry.wheelId)) entries.add(entry);
        Collections.sort(entries, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(8), dp(20), dp(8));
        if (entries.isEmpty()) content.addView(emptyText("暂无自然完成的抽取记录", darkMain));
        else {
            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            for (SpinHistoryEntry entry : entries) {
                String when = entry.createdAt > 0 ? format.format(new Date(entry.createdAt)) : "时间未知";
                LinearLayout historyRow=row(Gravity.CENTER_VERTICAL);TextView row = label(entry.optionText + "\n" + entry.wheelName + " · " + when, 15, text, false);historyRow.addView(row,new LinearLayout.LayoutParams(0,-2,1));WheelOption target=historyTarget(wheel,entry);TextView render=smallDarkButton("渲染",v->{historyDialogSafeStart(wheel,target);});render.setEnabled(target!=null);render.setAlpha(target==null?.4f:1f);historyRow.addView(render,new LinearLayout.LayoutParams(dp(72),dp(42)));
                historyRow.setPadding(dp(12), dp(10), dp(12), dp(10));
                historyRow.setBackground(round(panel, dp(12), stroke, 1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(8)); content.addView(historyRow, lp);
            }
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("历史记录 · " + wheel.name).setView(scroll).setNegativeButton("关闭", null).setPositiveButton("清空", null).create();
        dialog.setOnShowListener(ignored -> {
            Button clear = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            clear.setEnabled(!entries.isEmpty());
            clear.setOnClickListener(v -> confirmClearHistory(wheel, dialog));
        });
        dialog.show();
    }

    private WheelOption historyTarget(Wheel wheel,SpinHistoryEntry entry){if(entry.optionId!=null){for(WheelOption o:wheel.options)if(entry.optionId.equals(o.id))return o;return null;}WheelOption match=null;for(WheelOption o:wheel.options)if(Objects.equals(entry.optionText,o.text)){if(match!=null)return null;match=o;}return match;}
    private void historyDialogSafeStart(Wheel wheel,WheelOption target){if(target==null)return;Wheel snapshot=copyWheel(wheel);WheelOption copied=null;for(WheelOption o:snapshot.options)if(o.id.equals(target.id)){copied=o;break;}if(copied==null)return;copied.hidden=false;if(copied.trueWeight==0)copied.trueWeight=1;if(copied.fakeWeight!=null&&copied.fakeWeight==0)copied.fakeWeight=1.0;startRenderSnapshot(snapshot,copied,wheelView==null?0:wheelView.rotation);}

    private void confirmClearHistory(Wheel wheel, AlertDialog historyDialog) {
        new AlertDialog.Builder(this).setTitle("清空历史记录？")
                .setMessage("只会清空当前转盘「" + wheel.name + "」的历史记录，其他转盘和转盘数据不会受影响。")
                .setPositiveButton("清空", (d, which) -> {
                    int before = library.history.size();
                    for (Iterator<SpinHistoryEntry> it = library.history.iterator(); it.hasNext();) if (Objects.equals(wheel.id, it.next().wheelId)) it.remove();
                    save(); historyDialog.dismiss(); toast("已清空 " + (before - library.history.size()) + " 条记录");
                }).setNegativeButton("取消", null).show();
    }

    private void showSettingsSheet() {
        String[] items = {"新建转盘", "编辑当前转盘", "应用设置", "新建分组", "导入 PWH", "导入 WWD", "导出全部为 PWH", "导出全部为 WWD", "删除当前转盘"};
        new AlertDialog.Builder(this).setTitle("操作与设置").setItems(items, (d, which) -> {
            if (which == 0) openEditor(null, Screen.MAIN);
            else if (which == 1 && selectedWheel != null) openEditor(selectedWheel, Screen.MAIN);
            else if (which == 2) editAppSettings();
            else if (which == 3) newGroup();
            else if (which == 4) open(REQ_IMPORT_PWH, "*/*");
            else if (which == 5) open(REQ_IMPORT_WWD, "*/*");
            else if (which == 6) create(REQ_EXPORT_PWH, "export.pwh");
            else if (which == 7) create(REQ_EXPORT_WWD, "export.wwd");
            else if (which == 8) deleteCurrent();
        }).show();
    }

    private void select(Wheel w) {
        selectedWheel = w;
        if (w != null) selectedGroupId = w.groupId;
        if (wheelView != null) wheelView.setWheel(w);
        if (badge != null) badge.setText(w == null ? "今天转什么？" : w.name);
        if (liveOption != null) liveOption.setText(w == null ? "请先新建转盘" : "准备好了");
        updateRenderChoices();
    }

    private void openEditor(Wheel existing, Screen returnScreen) {
        editorReturnScreen = returnScreen == Screen.LIST ? Screen.LIST : Screen.MAIN;
        editorCreating = existing == null; editorWheel = existing == null ? applyAppDefaults(new Wheel()) : existing;
        editorNameDraft=editorWheel.name; editorDrafts=new ArrayList<>(); for(WheelOption o:editorWheel.options)editorDrafts.add(new OptionDraft(o)); if(editorCreating&&editorDrafts.isEmpty()){editorDrafts.add(new OptionDraft("选项 1"));editorDrafts.add(new OptionDraft("选项 2"));} batchText="";
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
        EditText name = input("问题 / 转盘标题", editorNameDraft, true); editorNameInput=name; p.addView(name, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView optionTitle = label("选项", 13, DARK_SUB, true); optionTitle.setPadding(0, dp(16), 0, dp(8)); p.addView(optionTitle);
        LinearLayout optionRows = new LinearLayout(this); optionRows.setOrientation(LinearLayout.VERTICAL); p.addView(optionRows);
        ArrayList<OptionDraft> drafts = editorDrafts == null ? new ArrayList<>() : editorDrafts; editorDrafts=drafts;
        Runnable[] render = new Runnable[1];
        render[0] = () -> renderOptionRows(optionRows, drafts, render[0], true); render[0].run();
        LinearLayout editorActions = row(Gravity.CENTER_VERTICAL); p.addView(editorActions, new LinearLayout.LayoutParams(-1, dp(54)));
        editorActions.addView(actionButton("添加选项", true, v -> { captureEditor(); drafts.add(new OptionDraft()); render[0].run(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams batchLp=new LinearLayout.LayoutParams(0,dp(48),1);batchLp.setMargins(dp(8),0,0,0);editorActions.addView(actionButton("批量创建",false,v->{captureEditor();navigateTo(Screen.BATCH_EDITOR);}),batchLp);
        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(0, dp(48), 1); advLp.setMargins(dp(8),0,0,0); editorActions.addView(actionButton("高级设置", false, v -> showAdvancedDialog(w, drafts)), advLp);
        saveButton.setOnClickListener(v -> {
            String wheelName = name.getText().toString().trim(); if (wheelName.isEmpty()) { toast("问题不能为空"); return; }
            ArrayList<WheelOption> parsed = new ArrayList<>(); for (int i = 0; i < drafts.size(); i++) { OptionDraft draft = drafts.get(i); String error = draft.validate(); if (error != null) { toast("第 " + (i + 1) + " 行：" + error); return; } parsed.add(draft.toOption()); }
            if (parsed.isEmpty()) { toast("至少需要一个选项"); return; }
            w.name = wheelName; w.options = parsed; w.updatedAt = System.currentTimeMillis(); if (editorCreating) library.wheels.add(w); save(); select(w);
            editorCreating = false; editorWheel = null; editorDrafts=null; editorNameInput=null; navigateTo(editorReturnScreen);
        });
        setContentView(root);
    }

    private void renderBatchEditorScreen(){
        useDarkBars(); LinearLayout root=darkScreen(); LinearLayout bar=row(Gravity.CENTER_VERTICAL);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(58)));
        bar.addView(iconButton("‹","返回",v->{captureBatch();navigateTo(Screen.EDITOR);},true),new LinearLayout.LayoutParams(dp(48),dp(48)));
        bar.addView(label("批量创建",22,DARK_TEXT,true),new LinearLayout.LayoutParams(0,-1,1));
        TextView add=iconButton("✓","添加",v->{},true);bar.addView(add,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView help=label("每行一个选项名；权重稍后在编辑页设置",14,DARK_SUB,false);help.setPadding(0,dp(8),0,dp(8));root.addView(help);
        batchInput=input("选项 A\n选项 B\n选项 C",batchText,true);batchInput.setGravity(Gravity.TOP);batchInput.setPadding(dp(14),dp(14),dp(14),dp(14));root.addView(batchInput,new LinearLayout.LayoutParams(-1,0,1));
        add.setOnClickListener(v->{captureBatch();BatchResult result=appendBatchOptions(editorDrafts,batchText);if(result.added==0){toast("没有可添加的新选项");return;}batchText="";batchInput=null;toast("已添加 "+result.added+" 项；跳过空行 "+result.blank+"、重复 "+result.duplicate);navigateTo(Screen.EDITOR);});
        setContentView(root);batchInput.requestFocus();batchInput.post(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(batchInput,InputMethodManager.SHOW_IMPLICIT));
    }

    private void captureEditor(){ if(editorNameInput!=null)editorNameDraft=editorNameInput.getText().toString();if(editorDrafts!=null)captureAll(editorDrafts); }
    private void captureBatch(){ if(batchInput!=null)batchText=batchInput.getText().toString(); }
    private BatchResult appendBatchOptions(ArrayList<OptionDraft> drafts,String raw){
        HashSet<String> existing=new HashSet<>();for(OptionDraft d:drafts)existing.add(d.textValue.trim());HashSet<String> batch=new HashSet<>();int added=0,blank=0,duplicate=0;
        for(String line:(raw==null?"":raw).split("\\R",-1)){String name=line.trim();if(name.isEmpty()){blank++;continue;}if(existing.contains(name)||!batch.add(name)){duplicate++;continue;}drafts.add(new OptionDraft(name));added++;}
        return new BatchResult(added,blank,duplicate);
    }
    private record BatchResult(int added,int blank,int duplicate){}

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
            box.setBackground(round(dark ? (d.hiddenValue?0xff20242c:DARK_CARD) : (d.hiddenValue?0xffeeeeee:0xffffffff), dp(14), dark ? 0xff263244 : 0xffe5e0d8, 1));
            box.setAlpha(d.hiddenValue?.58f:1f);

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

            TextView hide=new TextView(this);hide.setText(d.hiddenValue?"显示":"隐藏");hide.setTextSize(12);hide.setTextColor(dark?DARK_TEXT:text);hide.setGravity(Gravity.CENTER);hide.setContentDescription(d.hiddenValue?"显示选项":"隐藏选项");hide.setOnClickListener(v->{captureAll(drafts);if(d.hiddenValue){Double tv=d.parse(d.trueValue,1.0),fv=d.fakeValue.trim().isEmpty()?tv:d.parse(d.fakeValue,null);if(tv==null||fv==null||tv<=0||fv<=0){toast("请先把真权重和显示权重改为大于 0");return;}d.hiddenValue=false;}else d.hiddenValue=true;render.run();});box.addView(hide,new LinearLayout.LayoutParams(dp(48),dp(38)));

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

    private void deleteCurrent() { if (selectedWheel == null) return; new AlertDialog.Builder(this).setTitle("删除转盘？").setMessage(selectedWheel.name).setPositiveButton("删除", (d,w) -> { stopAutoSpin(false); cancelCurrentSpin(false); library.wheels.remove(selectedWheel); selectedWheel = library.wheels.isEmpty() ? null : library.wheels.get(0); save(); select(selectedWheel); }).setNegativeButton("取消", null).show(); }

    private void spin() {
        spinMode = SpinMode.MANUAL;
        startSingleSpin(false, autoGeneration);
    }

    private void startAutoSpin() {
        if (selectedWheel == null || selectedWheel.options.isEmpty()) { toast("请先新建转盘"); return; }
        String raw = autoSpinCount == null ? "" : autoSpinCount.getText().toString().trim();
        int count = -1;
        if (!raw.isEmpty()) { try { count=Integer.parseInt(raw); } catch(Exception e){ toast("次数必须是 1–9999 的整数"); return; } if(count<1||count>9999){toast("次数必须是 1–9999 的整数");return;} }
        autoGeneration++; autoTarget=count; autoCompleted=0; autoWheelId=selectedWheel.id; spinMode=SpinMode.AUTO_SPINNING; updateSpinControls(); startSingleSpin(true,autoGeneration);
    }

    private void startSingleSpin(boolean automatic, long generation) {
        if (selectedWheel == null || selectedWheel.options.isEmpty() || wheelView == null || wheelView.spinning) { if(automatic)stopAutoSpin(false); else {spinMode=SpinMode.IDLE;updateSpinControls();} return; }
        if (automatic && (generation!=autoGeneration || currentScreen!=Screen.MAIN || !Objects.equals(autoWheelId,selectedWheel.id))) { stopAutoSpin(false); return; }
        Wheel spinningWheel = selectedWheel;
        SpinEngine.SpinPlan plan;
        try { plan=engine.createPlan(spinningWheel,wheelView.rotation); } catch(Exception e){ toast(e.getMessage()); if(automatic)stopAutoSpin(false); else{spinMode=SpinMode.IDLE;updateSpinControls();} return; }
        if (tts != null) tts.stop(); spinSound.cancelTicks(); liveOption.setText("旋转中…"); updateSpinControls();
        activeSpinId = wheelView.spin(plan, o -> { liveOption.setText(o.text); if(spinningWheel.settings.tickSoundEnabled)spinSound.tick(); }, () -> {
            if (automatic && generation != autoGeneration) return;
            activeSpinId=0; SpinHistoryEntry entry=new SpinHistoryEntry(); entry.wheelId=spinningWheel.id;entry.wheelName=spinningWheel.name;entry.optionId=plan.target().id;entry.optionText=plan.target().text;library.history.add(entry);save();
            liveOption.setText(plan.target().text);
            if(spinningWheel.settings.selectedSoundEnabled)spinSound.selected();
            if (!automatic) { speakResult(spinningWheel,plan.target().text,null); spinMode=SpinMode.IDLE; updateSpinControls(); return; }
            autoCompleted++; spinMode=SpinMode.AUTO_WAITING; updateSpinControls(); final String feedbackId="auto-result-"+generation+"-"+autoCompleted;
            Runnable speak=()->{if(generation!=autoGeneration)return;boolean speaking=speakResult(spinningWheel,plan.target().text,feedbackId);if(!speaking)finishAutoFeedback(feedbackId);else{autoFeedbackTimeout=()->finishAutoFeedback(feedbackId);wheelView.postDelayed(autoFeedbackTimeout,30_000);}};
            wheelView.postDelayed(speak,spinningWheel.settings.selectedSoundEnabled?170:0);
        });
    }

    private void finishAutoFeedback(String id){
        if(!id.startsWith("auto-result-"+autoGeneration+"-")||spinMode!=SpinMode.AUTO_WAITING)return;
        if(autoFeedbackTimeout!=null&&wheelView!=null)wheelView.removeCallbacks(autoFeedbackTimeout);autoFeedbackTimeout=null;
        if(autoTarget>0&&autoCompleted>=autoTarget){spinMode=SpinMode.IDLE;autoWheelId=null;updateSpinControls();return;}
        long run=autoGeneration;pendingAutoSpin=()->{pendingAutoSpin=null;if(run!=autoGeneration||spinMode!=SpinMode.AUTO_WAITING)return;spinMode=SpinMode.AUTO_SPINNING;updateSpinControls();startSingleSpin(true,run);};wheelView.post(pendingAutoSpin);
    }

    private void updateSpinControls() {
        boolean auto = spinMode==SpinMode.AUTO_SPINNING||spinMode==SpinMode.AUTO_WAITING;
        boolean manual = spinMode==SpinMode.MANUAL;
        if(spinBtn!=null){spinBtn.setText(auto?"停止自动转":manual?"终止转盘":"点击旋转");spinBtn.setTextColor((auto||manual)?Color.WHITE:ACCENT);spinBtn.setBackground(round((auto||manual)?DANGER:(darkMain?0xff1f2937:0xffffffff),dp(24),(auto||manual)?0:ACCENT,(auto||manual)?0:2));}
        if(autoSpinBtn!=null){autoSpinBtn.setText(auto?(autoTarget>0?"停止 "+autoCompleted+"/"+autoTarget:"停止自动"):"自动转");autoSpinBtn.setEnabled(!manual);}
        if(autoSpinCount!=null)autoSpinCount.setEnabled(!auto&&!manual);
        updateRenderChoices();
    }

    private void updateRenderChoices() {
        if (renderTargetSpinner == null) return;
        ArrayList<RenderChoice> choices = new ArrayList<>();
        choices.add(new RenderChoice("当前指针（默认）", null));
        if (selectedWheel != null) for (WheelOption option : selectedWheel.options) if(option.eligible())choices.add(new RenderChoice(option.text, option));
        renderTargetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        boolean valid = selectedWheel != null && !selectedWheel.options.isEmpty() && wheelView != null && engine.optionAtPointer(selectedWheel,wheelView.rotation)!=null && spinMode==SpinMode.IDLE;
        renderTargetSpinner.setEnabled(valid && renderFuture == null);
        if (renderBtn != null) { boolean enabled=valid&&renderFuture==null;renderBtn.setEnabled(enabled); renderBtn.setAlpha(enabled ? 1f : .45f); }
    }

    private void requestRender() {
        if (selectedWheel == null || selectedWheel.options.isEmpty() || wheelView == null) { toast("没有可渲染的结果"); return; }
        if (renderFuture != null) return;
        @SuppressWarnings("unchecked") ArrayAdapter<RenderChoice> adapter=(ArrayAdapter<RenderChoice>)renderTargetSpinner.getAdapter();
        RenderChoice choice=adapter.getItem(renderTargetSpinner.getSelectedItemPosition());
        WheelOption target=choice==null?null:choice.option;
        if(target==null)target=engine.optionAtPointer(selectedWheel,wheelView.rotation);
        if(target==null){toast("没有可渲染的结果");return;}
        pendingRenderTarget=target;
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_RENDER_STORAGE);return;}
        startRender(target);
    }

    private void startRender(WheelOption target) {
        Wheel wheel=copyWheel(selectedWheel); if(wheel==null||target==null)return;
        WheelOption copiedTarget=null;for(WheelOption option:wheel.options)if(option.id.equals(target.id)){copiedTarget=option;break;}if(copiedTarget==null)return;startRenderSnapshot(wheel,copiedTarget,wheelView==null?0:wheelView.rotation);
    }
    private void startRenderSnapshot(Wheel wheel,WheelOption target,double rotation){
        stopAutoSpin(false);cancelCurrentSpin(false);renderCancelled.set(false);
        TextView message=label("准备渲染…",15,text,false);message.setPadding(dp(20),dp(20),dp(20),dp(20));
        renderDialog=new AlertDialog.Builder(this).setTitle("离线渲染 MP4").setView(message).setNegativeButton("取消",(d,w)->cancelRender()).create();renderDialog.setCanceledOnTouchOutside(false);renderDialog.show();
        boolean dark=darkMain;float fontScale=getResources().getConfiguration().fontScale;
        final WheelOption renderTarget=target;
        renderFuture=renderExecutor.submit(()->{try{OfflineMp4Renderer.Result result=new OfflineMp4Renderer(this,renderCancelled).render(wheel,library.settings,renderTarget,rotation,dark,fontScale,status->runOnUiThread(()->message.setText(status)));runOnUiThread(()->finishRender(result,null));}catch(Exception e){runOnUiThread(()->finishRender(null,e));}});
        updateRenderChoices();
    }

    private Wheel copyWheel(Wheel source){if(source==null)return null;Wheel copy=new Wheel();copy.id=source.id;copy.name=source.name;copy.groupId=source.groupId;copy.settings.rotationDurationMs=source.settings.rotationDurationMs;copy.settings.colorScheme=source.settings.colorScheme;copy.settings.fontSize=source.settings.fontSize;copy.settings.tickSoundEnabled=source.settings.tickSoundEnabled;copy.settings.selectedSoundEnabled=source.settings.selectedSoundEnabled;copy.settings.ttsEnabled=source.settings.ttsEnabled;copy.settings.ttsLanguageTag=source.settings.ttsLanguageTag;for(WheelOption option:source.options){WheelOption o=new WheelOption(option.text,option.trueWeight,option.fakeWeight);o.id=option.id;o.hidden=option.hidden;copy.options.add(o);}return copy;}

    private void finishRender(OfflineMp4Renderer.Result result,Exception error){renderFuture=null;if(renderDialog!=null){renderDialog.dismiss();renderDialog=null;}updateRenderChoices();if(isFinishing()||isDestroyed())return;if(error!=null){if(!(error instanceof InterruptedException))new AlertDialog.Builder(this).setTitle("渲染失败").setMessage(error.getMessage()==null?error.toString():error.getMessage()).setPositiveButton("确定",null).show();return;}toast("已保存到 Movies/wheel："+result.name);}
    private void cancelRender(){renderCancelled.set(true);if(renderFuture!=null)renderFuture.cancel(true);renderFuture=null;if(renderDialog!=null){renderDialog.dismiss();renderDialog=null;}updateRenderChoices();}
    public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_RENDER_STORAGE){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startRender(pendingRenderTarget);else toast("需要存储权限才能保存到 Movies/wheel");pendingRenderTarget=null;}}
    private record RenderChoice(String label,WheelOption option){public String toString(){return label;}}

    private void stopAutoSpin(boolean userInitiated) {
        boolean wasAuto=spinMode==SpinMode.AUTO_SPINNING||spinMode==SpinMode.AUTO_WAITING;
        if(!wasAuto)return; autoGeneration++; if(pendingAutoSpin!=null&&wheelView!=null)wheelView.removeCallbacks(pendingAutoSpin);if(autoFeedbackTimeout!=null&&wheelView!=null)wheelView.removeCallbacks(autoFeedbackTimeout);pendingAutoSpin=null;autoFeedbackTimeout=null;spinMode=SpinMode.IDLE;autoWheelId=null;cancelCurrentSpin(false);if(spinSound!=null)spinSound.cancelTicks();if(tts!=null)tts.stop();if(userInitiated&&liveOption!=null)liveOption.setText("自动转已停止");updateSpinControls();
    }

    private boolean cancelCurrentSpin(boolean userInitiated) {
        if (wheelView == null) return false;
        long spinId = activeSpinId != 0 ? activeSpinId : wheelView.currentSpinId();
        if (!wheelView.cancelSpin(spinId)) return false;
        activeSpinId = 0;
        if (spinSound != null) spinSound.cancelTicks();
        if (tts != null) tts.stop();
        if (userInitiated && liveOption != null) liveOption.setText("本次抽取已取消");
        if(spinMode==SpinMode.MANUAL)spinMode=SpinMode.IDLE;
        updateSpinControls();
        return true;
    }

    private boolean speakResult(Wheel wheel, String value, String utteranceId) {
        if (!wheel.settings.ttsEnabled || !ttsReady) return false;
        Locale locale=TtsLanguageResolver.apply(wheel.settings.ttsLanguageTag,library.settings.defaultTtsLanguageTag,Locale.getDefault(),tts::setLanguage);
        if(locale==null){ toast("TTS 不支持转盘、应用或系统语言"); return false; }
        String id=utteranceId==null?"wheel-result":utteranceId;
        int result=tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,id);
        return result==TextToSpeech.SUCCESS;
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req,res,data);
        if(res!=RESULT_OK||data==null||data.getData()==null)return;
        Uri u=data.getData();
        try {
            if(req==REQ_IMPORT_PWH) importPwhBytes(read(u));
            else if(req==REQ_IMPORT_WWD){ WheelLibrary l=WheelFormats.importWwd(read(u)); mergeWwd(l); save(); buildMainUi(); select(selectedWheel); toast("WWD 导入完成"); }
            else if(req==REQ_EXPORT_PWH){ write(u, WheelFormats.exportPwh(library.wheels)); toast("PWH 导出完成"); }
            else if(req==REQ_EXPORT_WWD){ write(u, WheelFormats.exportWwd(library,false)); toast("WWD 导出完成"); }
        } catch(Exception e){ toast(e.getMessage()); }
    }

    private void importPwhBytes(byte[] bytes) throws IOException {
        List<Wheel> imported = WheelFormats.importPwh(bytes, null);
        if (imported.isEmpty()) throw new IOException("PWH 中没有有效转盘");
        pendingPwhWheels = new ArrayList<>(imported);
        pendingPwhSelection.clear();
        for (int i=0;i<pendingPwhWheels.size();i++) pendingPwhSelection.add(i);
        currentScreen = Screen.IMPORT_PWH;
        renderPwhImportScreen();
    }

    private void renderPwhImportScreen() {
        useDarkBars(); LinearLayout root=darkScreen();
        LinearLayout bar=row(Gravity.CENTER_VERTICAL);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(58)));
        bar.addView(iconButton("‹","取消导入",v->cancelPwhImport(),true),new LinearLayout.LayoutParams(dp(48),dp(48)));
        bar.addView(label("分配 PWH 转盘",22,DARK_TEXT,true),new LinearLayout.LayoutParams(0,-1,1));
        TextView finish=smallDarkButton("导入",v->finishPwhImport());bar.addView(finish,new LinearLayout.LayoutParams(dp(72),dp(42)));
        TextView help=label("长按滑动多选",14,DARK_SUB,false);help.setPadding(0,dp(8),0,dp(10));root.addView(help);
        LinearLayout actions=row(Gravity.CENTER_VERTICAL);
        actions.addView(smallDarkButton("全选",v->{pendingPwhSelection.clear();for(int i=0;i<pendingPwhWheels.size();i++)pendingPwhSelection.add(i);renderPwhImportScreen();}));
        actions.addView(smallDarkButton("反选",v->{for(int i=0;i<pendingPwhWheels.size();i++)if(!pendingPwhSelection.remove(i))pendingPwhSelection.add(i);renderPwhImportScreen();}));
        actions.addView(smallDarkButton("新建分组",v->newGroup(id->{assignSelectedPwh(id);renderPwhImportScreen();})));
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(46)));
        ArrayList<GroupChoice> choices=groupChoices(false,"未分组");
        Spinner group=new Spinner(this);group.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));
        Button assign=primaryButton("选中项放入分组");assign.setOnClickListener(v->{if(pendingPwhSelection.isEmpty()){toast("请先选择转盘");return;}assignSelectedPwh(choices.get(group.getSelectedItemPosition()).id);renderPwhImportScreen();});
        LinearLayout assignRow=row(Gravity.CENTER_VERTICAL);assignRow.addView(group,new LinearLayout.LayoutParams(0,dp(50),1));assignRow.addView(assign,new LinearLayout.LayoutParams(dp(174),dp(46)));root.addView(assignRow);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        for(int i=0;i<pendingPwhWheels.size();i++){
            final int index=i;Wheel w=pendingPwhWheels.get(i);boolean checked=pendingPwhSelection.contains(i);
            LinearLayout item=row(Gravity.CENTER_VERTICAL);item.setPadding(dp(12),dp(9),dp(12),dp(9));item.setBackground(round(checked?0xff24334d:DARK_PANEL,dp(12),checked?BLUE:0xff263244,1));
            CheckBox check=new CheckBox(this);check.setChecked(checked);check.setClickable(false);item.addView(check,new LinearLayout.LayoutParams(dp(44),dp(44)));
            LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.addView(label(w.name,16,DARK_TEXT,true));words.addView(label(w.options.size()+" 个选项 · "+groupName(w.groupId),13,DARK_SUB,false));item.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            Handler holdHandler=new Handler(Looper.getMainLooper());final boolean[] selecting={false},dragValue={false};final int[] last={-1};final float[] down={0,0};
            Runnable beginSelection=()->{selecting[0]=true;dragValue[0]=!pendingPwhSelection.contains(index);last[0]=index;item.getParent().requestDisallowInterceptTouchEvent(true);item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);setPendingPwhRow(list,index,dragValue[0]);};
            item.setOnTouchListener((v,e)->{int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN){down[0]=e.getRawX();down[1]=e.getRawY();selecting[0]=false;last[0]=-1;holdHandler.postDelayed(beginSelection,1000);return true;}if(action==MotionEvent.ACTION_MOVE){if(!selecting[0]){int slop=ViewConfiguration.get(this).getScaledTouchSlop();if(Math.hypot(e.getRawX()-down[0],e.getRawY()-down[1])>slop)holdHandler.removeCallbacks(beginSelection);return true;}v.getParent().requestDisallowInterceptTouchEvent(true);for(int j=0;j<list.getChildCount();j++){View child=list.getChildAt(j);int[] loc=new int[2];child.getLocationOnScreen(loc);if(e.getRawY()>=loc[1]&&e.getRawY()<loc[1]+child.getHeight()&&last[0]!=j){last[0]=j;setPendingPwhRow(list,j,dragValue[0]);break;}}return true;}holdHandler.removeCallbacks(beginSelection);if(action==MotionEvent.ACTION_UP&&!selecting[0])setPendingPwhRow(list,index,!pendingPwhSelection.contains(index));if(selecting[0])v.getParent().requestDisallowInterceptTouchEvent(false);selecting[0]=false;return true;});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(7));list.addView(item,lp);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }

    private void setPendingPwhRow(LinearLayout list,int index,boolean selected){if(selected)pendingPwhSelection.add(index);else pendingPwhSelection.remove(index);View child=list.getChildAt(index);if(child==null)return;CheckBox check=(CheckBox)((LinearLayout)child).getChildAt(0);check.setChecked(selected);child.setBackground(round(selected?0xff24334d:DARK_PANEL,dp(12),selected?BLUE:0xff263244,1));}
    private void assignSelectedPwh(String groupId){for(int i:pendingPwhSelection)if(i>=0&&i<pendingPwhWheels.size())pendingPwhWheels.get(i).groupId=groupId;}
    private void finishPwhImport(){if(pendingPwhWheels==null)return;for(Wheel w:pendingPwhWheels){applyAppDefaults(w);w.name=library.uniqueWheelName(w.name);library.wheels.add(w);selectedWheel=w;}int count=pendingPwhWheels.size();pendingPwhWheels=null;pendingPwhSelection.clear();save();currentScreen=Screen.LIST;renderCurrentScreen();toast("已导入 "+count+" 个转盘");}
    private void cancelPwhImport(){pendingPwhWheels=null;pendingPwhSelection.clear();currentScreen=Screen.MAIN;renderCurrentScreen();}

    private void mergeWwd(WheelLibrary imported) {
        HashSet<String> usedGroupIds = new HashSet<>();
        for (WheelGroup group : library.groups) usedGroupIds.add(group.id);
        HashMap<String, String> groupIds = new HashMap<>();
        for (WheelGroup group : imported.groups) {
            String oldId = group.id;
            if (usedGroupIds.contains(group.id)) group.id = Models.newId();
            usedGroupIds.add(group.id); groupIds.putIfAbsent(oldId, group.id);
        }
        for (WheelGroup group : imported.groups) if (groupIds.containsKey(group.parentId)) group.parentId = groupIds.get(group.parentId);
        for (Wheel wheel : imported.wheels) if (groupIds.containsKey(wheel.groupId)) wheel.groupId = groupIds.get(wheel.groupId);
        HashSet<String> usedWheelIds = new HashSet<>();
        for (Wheel wheel : library.wheels) usedWheelIds.add(wheel.id);
        HashMap<String, String> wheelIds = new HashMap<>();
        for (Wheel wheel : imported.wheels) {
            String oldId = wheel.id;
            if (usedWheelIds.contains(wheel.id)) wheel.id = Models.newId();
            usedWheelIds.add(wheel.id); wheelIds.putIfAbsent(oldId, wheel.id);
        }
        for (SpinHistoryEntry entry : imported.history) if (wheelIds.containsKey(entry.wheelId)) entry.wheelId = wheelIds.get(entry.wheelId);
        library.settings=imported.settings; library.groups.addAll(imported.groups); library.wheels.addAll(imported.wheels); library.history.addAll(imported.history);
        if(!imported.wheels.isEmpty()) selectedWheel=imported.wheels.get(0);
    }

    private void consumeExternalImportIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return;
        Uri uri = externalUri(intent);
        Intent consumed = new Intent(intent); consumed.setAction(null); consumed.setData(null); consumed.removeExtra(Intent.EXTRA_STREAM); consumed.setClipData(null); setIntent(consumed);
        if (uri == null) { toast("没有可导入的文件"); return; }
        try {
            byte[] bytes=read(uri);
            if(isMagic(bytes,"pwh")){importPwhBytes(bytes);return;}
            try{WheelLibrary imported=WheelFormats.importWwd(bytes);mergeWwd(imported);save();currentScreen=Screen.MAIN;renderCurrentScreen();toast("WWD 导入完成");}
            catch(Exception invalid){toast("分享的文件不是有效 PWH 或 WWD");}
        } catch (SecurityException e) { toast("无法读取该文件，请重新分享或打开"); }
        catch (Exception e) { toast(e.getMessage()); }
    }

    private boolean isMagic(byte[] bytes,String magic){return bytes.length>=3&&bytes[0]==magic.charAt(0)&&bytes[1]==magic.charAt(1)&&bytes[2]==magic.charAt(2);}

    private Uri externalUri(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 1) return null;
        Uri uri;
        if (Build.VERSION.SDK_INT >= 33) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() == 1) uri = intent.getClipData().getItemAt(0).getUri();
        return uri;
    }

    private String displayName(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) return new File(uri.getPath()).getName();
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index >= 0) return cursor.getString(index); }
        }
        return null;
    }

    private void open(int req,String type){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(type); startActivityForResult(i,req); }
    private void create(int req,String name){ Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/octet-stream"); i.putExtra(Intent.EXTRA_TITLE,name); startActivityForResult(i,req); }
    private byte[] read(Uri u) throws IOException { try(InputStream in=getContentResolver().openInputStream(u)){ if(in==null)throw new IOException("无法读取该文件"); return readAll(in); } }
    private byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int count; while((count=in.read(buffer))!=-1)out.write(buffer,0,count); return out.toByteArray(); }
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
    private final class OptionDraft { final String id; String textValue="", trueValue="", fakeValue="";boolean hiddenValue; EditText text,trueWeight,fakeWeight; OptionDraft(){id=Models.newId();} OptionDraft(String label){id=Models.newId();textValue=label;} OptionDraft(String id,String text,String trueValue,String fakeValue,boolean hidden){this.id=id;this.textValue=text;this.trueValue=trueValue;this.fakeValue=fakeValue;this.hiddenValue=hidden;} OptionDraft(WheelOption o){id=o.id;textValue=o.text;trueValue=String.valueOf(o.trueWeight);fakeValue=o.fakeWeight==null?"":String.valueOf(o.fakeWeight);hiddenValue=o.hidden;} void capture(){if(text==null)return;textValue=text.getText().toString();trueValue=trueWeight.getText().toString();fakeValue=fakeWeight.getText().toString();} Double parse(String v,Double def){if(v==null||v.trim().isEmpty())return def;try{double n=Double.parseDouble(v.trim());return Double.isFinite(n)&&n>=0?n:null;}catch(Exception e){return null;}} String validate(){capture();if(textValue.trim().isEmpty())return "名字不能为空";Double tv=parse(trueValue,1.0);if(tv==null)return "真权重必须是大于等于 0 的有限数字";Double fv=fakeValue.trim().isEmpty()?null:parse(fakeValue,null);if(!fakeValue.trim().isEmpty()&&fv==null)return "显示权重必须是大于等于 0 的有限数字";if(tv==0||(fv!=null&&fv==0))hiddenValue=true;return null;} WheelOption toOption(){WheelOption o=new WheelOption();o.id=id;o.text=textValue.trim();Double tv=parse(trueValue,1.0),fv=fakeValue.trim().isEmpty()?null:parse(fakeValue,null);o.trueWeight=tv==null?1:tv;o.fakeWeight=fv;o.hidden=hiddenValue||o.trueWeight==0||o.displayWeight()==0;return o;} }

    private void seed(){ if(!library.wheels.isEmpty())return; Wheel w=applyAppDefaults(new Wheel()); w.name="今天吃什么"; w.options.add(new WheelOption("火锅",1,null)); w.options.add(new WheelOption("烤肉",2,null)); w.options.add(new WheelOption("米饭",1,null)); w.options.add(new WheelOption("面",1,null)); library.wheels.add(w); selectedWheel=w; }
    private void load(){ try{ String s=getPreferences(0).getString("library",null); if(s!=null) library=WheelFormats.importWwd(s.getBytes(StandardCharsets.UTF_8)); }catch(Exception ignored){} }
    private void save(){ try{ getPreferences(0).edit().putString("library", new String(WheelFormats.exportWwd(library,false), StandardCharsets.UTF_8)).apply(); }catch(Exception e){toast("保存失败");} }

    private final class WheelView extends View {
        Wheel wheel; double rotation; boolean spinning; String last; long lastTick;
        Runnable activeSpinFrame;
        long nextSpinId, activeSpinId;
        WheelView(Context c){ super(c); setBackgroundColor(Color.TRANSPARENT); }
        void setWheel(Wheel w){ wheel=w; invalidate(); }
        long spin(SpinEngine.SpinPlan plan, java.util.function.Consumer<WheelOption> pass, Runnable done){
            cancelSpin(activeSpinId);
            spinning=true; last=null; lastTick=0; rotation=normalizeRotation(rotation);
            final long spinId=++nextSpinId, start=SystemClock.elapsedRealtimeNanos();
            activeSpinId=spinId;
            double from=rotation,to=rotation+plan.totalRotation();
            activeSpinFrame=new Runnable(){ public void run(){
                if(!spinning||activeSpinId!=spinId||activeSpinFrame!=this)return;
                double elapsedMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0;
                double t=Math.min(1,elapsedMs/plan.durationMs());
                rotation=SpinEngine.rotationAt(from,plan,elapsedMs);
                WheelOption cur=engine.optionAtPointer(wheel,rotation);
                long now=SystemClock.elapsedRealtime();
                if(cur!=null&&!Objects.equals(cur.id,last)&&now-lastTick>50){last=cur.id;lastTick=now;pass.accept(cur);}
                invalidate();
                if(t<1)postOnAnimation(this);
                else{
                    rotation=normalizeRotation(to); spinning=false; activeSpinFrame=null; activeSpinId=0; last=null; lastTick=0; invalidate(); done.run();
                }
            }};
            postOnAnimation(activeSpinFrame);
            return spinId;
        }
        long currentSpinId(){ return spinning?activeSpinId:0; }
        boolean cancelSpin(long expectedSpinId){
            if(!spinning||expectedSpinId==0||activeSpinId!=expectedSpinId)return false;
            if(activeSpinFrame!=null)removeCallbacks(activeSpinFrame);
            rotation=normalizeRotation(rotation); spinning=false; activeSpinFrame=null; activeSpinId=0; last=null; lastTick=0; invalidate();
            return true;
        }
        protected void onDetachedFromWindow(){
            if(activeSpinFrame!=null)removeCallbacks(activeSpinFrame);
            spinning=false; activeSpinFrame=null; activeSpinId=0; last=null; lastTick=0;
            super.onDetachedFromWindow();
        }
        private double normalizeRotation(double value){ value%=360.0; return value<0?value+360.0:value; }
        protected void onDraw(Canvas c){ super.onDraw(c); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); int size=Math.min(getWidth(),getHeight())-dp(48), x=(getWidth()-size)/2, y=(getHeight()-size)/2; if(wheel==null){p.setTextSize(dp(18));p.setColor(sub);p.setTextAlign(Paint.Align.CENTER);c.drawText("请先新建转盘",getWidth()/2f,getHeight()/2f,p);return;}WheelCanvasRenderer.draw(c,new RectF(x,y,x+size,y+size),wheel,library.settings,engine,rotation,getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().scaledDensity); }
    }
}
