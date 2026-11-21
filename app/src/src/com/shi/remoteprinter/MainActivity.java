package com.shi.remoteprinter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 宅家打印机 APP：纯原生 Java，无 WebView、无第三方库、无广告、无 Google 服务。
 *
 * 界面按苹果风格：浅灰分组背景 #F2F2F7、白色大圆角卡片配柔和阴影、
 * iOS 系统蓝 #007AFF 主按钮、大标题层级、iOS 分段控件、底部标签栏。
 *
 * 功能：远程打印（邮件分享到打印机邮箱）、试卷去手写、内网 eSCL 扫描、
 * 证件照、错题本、家里接收模式。打印机自动发现（NSD + 子网扫描），
 * IP 变了自动跟上，手动 IP 只作备用。
 *
 * AI 功能（DeepSeek V4.1 Flash，OpenAI Chat Completions 兼容，走用户自己的
 * API 地址，密钥只存在本机）：AI 指导去手写、错题本 AI 分析、AI 组卷打印、
 * 证件照 AI 看图指导、学习助手问 AI、拍照质检。模型只返回文字，抠图/擦除/
 * 排版/打印的执行都在本地做；没填密钥时 AI 功能会提示去设置里填。
 */
public class MainActivity extends Activity {

    // ---------- 常量 ----------
    /** 打印机云打印邮箱（预填，仅供自家人用；序列号/初始密码绝不放进 APP） */
    /**
     * 打印机云邮箱**不再硬编码**：APK 会放到公开仓库做自动更新，写死就等于把家里的打印机
     * 公开给所有人。改成首次扫码绑定，或用配置串一次性导入（见 设置 → 打印发信）。
     */
    private static final String PRINTER_EMAIL_DEFAULT = "";
    private static final String DEFAULT_IP = "192.168.5.4";
    /** 自动更新的清单地址（build.ps1 生成的 update.json，由局域网下载页托管） */
    /** 更新清单地址：优先 CDN（任何网络都能用），失败再试家里局域网 */
    private static final String[] UPDATE_URLS = {
            // 清单走 raw（不缓存，永远是最新）；国内偶尔连不上，所以还有两个兜底
            "https://raw.githubusercontent.com/heibai082/zhaijia-dayinji/main/update.json",
            // jsDelivr 国内快，但会对分支地址做缓存，可能滞后几小时
            "https://cdn.jsdelivr.net/gh/heibai082/zhaijia-dayinji@main/update.json",
            // 在家连局域网时的兜底
            "http://192.168.5.26:8021/update.json",
    };
    private static final String UPDATE_URL_DEFAULT = UPDATE_URLS[0];
    private static final String PREFS = "printer";

    private static final int REQ_PICK_PRINT = 1;
    private static final int REQ_PICK_PAPER = 2;
    private static final int REQ_PICK_ID = 3;
    private static final int REQ_CAMERA_PAPER = 4;
    private static final int REQ_CAMERA_ID = 5;
    private static final int REQ_PICK_ANALYZE = 6;
    private static final int REQ_PICK_CHAT = 7;
    private static final int REQ_CAMERA_CHAT = 8;
    private static final int REQ_QR_PICK = 9;
    private static final int REQ_QR_CAMERA = 10;
    private static final int REQ_PERM = 100;

    // iOS 配色
    private static final int C_BG = 0xFFF2F2F7;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_BLUE = 0xFF007AFF;
    private static final int C_TEXT = 0xFF000000;
    private static final int C_TEXT2 = 0xFF3A3A3C;
    private static final int C_GRAY = 0xFF8E8E93;
    private static final int C_GRAY2 = 0xFF6D6D72;
    private static final int C_SEG_BG = 0xFFE5E5EA;
    private static final int C_SEP = 0xFFC6C6C8;
    private static final int C_RED = 0xFFFF3B30;
    private static final int C_GREEN = 0xFF34C759;

    // ---------- 状态 ----------
    private SharedPreferences prefs;
    private String printerEmail;
    private String manualIp;
    private PrinterDiscovery.DiscoveredPrinter discovered;
    private boolean discoveryRunning = false;

    private Bitmap currentPaper;
    /** 去手写强度：0 柔和 / 1 标准 / 2 强力 */
    private int paperStrength = 1;
    private Bitmap originalPaper;
    private boolean eraserMode = false;
    private ImageView paperPreview;

    private final List<Bitmap> scannedPages = new ArrayList<Bitmap>();
    private ImageView scanPreview;
    private TextView scanStatusText;
    /** 预览图下面的小字（扫描成功后换成页数信息，不再一直挂着“结果会显示在这里”） */
    private TextView scanHintText;
    /** 这台机器没有 eSCL（L3150/L3151 实测 404）：记住它，下次直接走 ESC/I，不再试探 */
    private boolean esclUnsupported = false;
    /** 扫描慢的时候补一句进度提示，免得用户以为卡死了 */
    private Runnable scanSlowHint;    private TextView discoveryText;
    private LinearLayout manualIpCard;
    private EditText manualIpEdit;
    private SegmentedControl scanColorSeg;
    private SegmentedControl scanDpiSeg;
    private SegmentedControl scanFormatSeg;

    private Bitmap idPhoto;
    private Bitmap originalIdPhoto;
    private SegmentedControl idBgSeg;
    private TextView idPlaceholder;
    /** 证件照底色：0 原图 / 1 白 / 2 蓝 / 3 红（索引对应 ID_BG_COLORS） */
    private int idBgIndex = 0;
    private static final int[] ID_BG_COLORS = {
            0, 0xFFFFFFFF, 0xFF438EDB, 0xFFD9001B};
    /** 手指涂背景模式（自动换底漏了的地方手动补） */
    private boolean idBrushMode = false;
    /** 用户框选头部/主体后的源图（null 表示用整张原图） */
    private Bitmap idRoiSource;
    private float idBrushLastX = -999, idBrushLastY = -999;
    /** 撤销栈：存每次涂之前的像素（最多 6 步） */
    private final java.util.ArrayList<int[]> idUndoStack = new java.util.ArrayList<int[]>();
    private final java.util.ArrayList<int[]> idUndoSize = new java.util.ArrayList<int[]>();
    private ImageView idPreview;
    private SegmentedControl idSizeSeg;
    private EditText emailEdit;
    private EditText settingsIpEdit;
    private boolean pendingHomeScan = false;
    private TextView homeStatusText;
    private Button homeShareBtn;
    /** 家里接收模式：把已扫的多页合成一个 PDF 一次性发出去 */
    private Button homeSendPdfBtn;
    /** 家里接收模式里的预览：最后一页大图 + 全部页缩略图 */
    private ImageView homePreview;
    private LinearLayout homeThumbsRow;
    private HorizontalScrollView homeThumbsScroll;
    /** 扫描页的已扫缩略图（长按删单页） */
    private LinearLayout scanThumbsRow;
    private HorizontalScrollView scanThumbsScroll;

    private LinearLayout notebookList;
    private TextView printStatusText;
    /** 设置页里"当前打印机邮箱（脱敏）"那一行 */
    private TextView printerEmailLabel;
    /** 打印页"已选图片"的缩略图行 */
    private LinearLayout pickedPreviewRow;
    private HorizontalScrollView pickedPreviewScroll;
    private TextView printHistoryText;
    private Uri pickedPrintUri;
    private String pickedPrintName = "";
    /** 多选打印时选中的全部文件（顺序即打印顺序） */
    private final List<Uri> pickedPrintUris = new ArrayList<Uri>();
    private final List<String> pickedPrintNames = new ArrayList<String>();
    private Uri cameraUri;

    // ---------- AI 相关状态 ----------
    private TextView aiCleanStatusText;
    private TextView idAiResultText;
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private EditText chatInput;
    private ImageView chatPhotoPreview;
    private Bitmap chatPhoto;
    private final List<AiClient.Msg> chatHistory = new ArrayList<AiClient.Msg>();
    private boolean chatBusy = false;
    private boolean qualityCheckRunning = false;

    private FrameLayout contentArea;
    private LinearLayout tabBar;
    private View[] screens = new View[6];
    private final String[] tabNames = {"打印", "去手写", "扫描", "工具", "问 AI", "设置"};
    private int currentTab = 0;
    private boolean scanTabDiscovered = false;
    private View homeModeOverlay;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---------- 生命周期 ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        printerEmail = prefs.getString("email", PRINTER_EMAIL_DEFAULT);
        manualIp = prefs.getString("manualIp", DEFAULT_IP);
        esclUnsupported = prefs.getBoolean("esclUnsupported", false);
        requestPermissionsIfNeeded();
        buildMainUi();
        showTab(0);
        // 每天启动时静默检查一次更新（有新版本才弹窗）
        long last = prefs.getLong("lastUpdateCheck", 0L);
        if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkUpdate(true);
                }
            }, 2500);
        }
    }

    /**
     * 兜底崩溃记录：任何未捕获异常都写到
     * Android/data/com.shi.remoteprinter/files/崩溃日志.txt，然后交给系统默认处理。
     * 这样下次闪退能拿到堆栈，不用再靠猜（上一版“转 A4 PDF 闪退”就是靠读代码猜出来的）。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread th, Throwable e) {
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    java.io.File f = new java.io.File(dir, "崩溃日志.txt");
                    if (f.length() > 200 * 1024) {
                        f.delete();
                    }
                    java.io.FileWriter w = new java.io.FileWriter(f, true);
                    w.write("\n===== " + new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + "  线程 " + th.getName() + " =====\n");
                    w.write(Log.getStackTraceString(e));
                    w.write("\n");
                    w.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(th, e);
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限被拒也不闪退：用到选图/拍照时系统会再提示，用户可去设置里开
    }

    // ---------- iOS 风格组件 ----------
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int sp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics()));
    }

    /** 白色大圆角卡片背景（柔和阴影靠 elevation） */
    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_CARD);
        d.setCornerRadius(dp(18));
        return d;
    }

    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 白色卡片容器：圆角 18 + 柔和阴影 + 内边距 */
    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setElevation(dp(2));
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    /** 分组小标题：13sp 灰色 */
    private TextView makeHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_GRAY2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(32);
        lp.rightMargin = dp(16);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** 大标题：30sp 粗体 */
    private TextView makeLargeTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(30));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView makeBody(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_TEXT2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        t.setLineSpacing(dp(4), 1.0f);
        return t;
    }

    private TextView makeCaption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_GRAY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
        t.setLineSpacing(dp(3), 1.0f);
        return t;
    }

    private TextView makeCardTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** iOS 主按钮：系统蓝、圆角 12、白字、50dp 高 */
    private Button makePrimaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17));
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setBackground(roundBg(C_BLUE, 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 次要按钮：浅灰底、深色字、圆角 12 */
    private Button makeSecondaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(C_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        b.setBackground(roundBg(C_SEG_BG, 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 文字按钮：蓝色文字、无背景 */
    private Button makeTextButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(C_BLUE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText makeTextField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(C_TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(16));
        e.setBackground(roundBg(0xFFF2F2F7, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(C_SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String nowTime() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date());
    }

    // ---------- iOS 分段控件 ----------
    interface OnSelect {
        void onSelect(int index);
    }

    private class SegmentedControl {
        final LinearLayout layout;
        final TextView[] items;
        final String[] options;
        int selected = 0;
        OnSelect onSelect;

        SegmentedControl(String[] options) {
            this.options = options;
            layout = new LinearLayout(MainActivity.this);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setBackground(roundBg(C_SEG_BG, 10));
            layout.setPadding(dp(2), dp(2), dp(2), dp(2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            lp.topMargin = dp(8);
            layout.setLayoutParams(lp);
            items = new TextView[options.length];
            for (int i = 0; i < options.length; i++) {
                final int idx = i;
                TextView t = new TextView(MainActivity.this);
                t.setText(options[i]);
                t.setGravity(Gravity.CENTER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                p.leftMargin = dp(1);
                p.rightMargin = dp(1);
                t.setLayoutParams(p);
                t.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setSelected(idx);
                        if (onSelect != null) onSelect.onSelect(idx);
                    }
                });
                items[i] = t;
                layout.addView(t);
            }
            refresh();
        }

        void setSelected(int idx) {
            selected = idx;
            refresh();
        }

        int getSelected() {
            return selected;
        }

        String getSelectedText() {
            return options[selected];
        }

        void setOnSelect(OnSelect l) {
            onSelect = l;
        }

        private void refresh() {
            for (int i = 0; i < items.length; i++) {
                if (i == selected) {
                    items[i].setBackground(roundBg(Color.WHITE, 8));
                    items[i].setElevation(dp(1));
                    items[i].setTextColor(C_TEXT);
                    items[i].setTypeface(null, android.graphics.Typeface.BOLD);
                } else {
                    items[i].setBackgroundColor(Color.TRANSPARENT);
                    items[i].setElevation(0);
                    items[i].setTextColor(C_TEXT2);
                    items[i].setTypeface(null, android.graphics.Typeface.NORMAL);
                }
            }
        }
    }

    // ---------- 主框架：内容区 + 底部标签栏 ----------
    private void buildMainUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentArea.setLayoutParams(cp);
        screens[0] = buildPrintScreen();
        screens[1] = buildCleanScreen();
        screens[2] = buildScanScreen();
        screens[3] = buildToolsScreen();
        screens[4] = buildAiChatScreen();
        screens[5] = buildSettingsScreen();
        for (View s : screens) contentArea.addView(s);
        root.addView(contentArea);

        View div = new View(this);
        div.setBackgroundColor(C_SEP);
        root.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(Color.WHITE);
        tabBar.setElevation(dp(8));
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        buildTabBar();
        root.addView(tabBar);

        setContentView(root);
    }

    private void buildTabBar() {
        tabBar.removeAllViews();
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            tab.setLayoutParams(lp);
            // 选中指示条：顶部 3dp 蓝色圆角小条
            View indicator = new View(this);
            indicator.setBackground(roundBg(i == currentTab ? C_BLUE : Color.TRANSPARENT, 2));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(24), dp(3));
            ip.topMargin = dp(8);
            indicator.setLayoutParams(ip);
            TextView label = new TextView(this);
            label.setText(tabNames[i]);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12));
            if (i == currentTab) {
                label.setTextColor(C_BLUE);
                label.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                label.setTextColor(C_GRAY);
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(4);
            label.setLayoutParams(tp);
            tab.addView(indicator);
            tab.addView(label);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            tabBar.addView(tab);
        }
    }

    private void showTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < screens.length; i++) {
            screens[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        buildTabBar();
        if (idx == 2 && !scanTabDiscovered) {
            scanTabDiscovered = true;
            runDiscovery();
        }
        if (idx == 3) {
            refreshNotebookList();
        }
    }

    private ScrollView wrapScreen(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.addView(content);
        sv.setVisibility(View.GONE);
        return sv;
    }

    private LinearLayout screenContent() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(C_BG);
        l.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int bottom = dp(24);
        l.setPadding(0, 0, 0, bottom);
        return l;
    }

    // ---------- 打印页 ----------
    private View buildPrintScreen() {
        LinearLayout content = screenContent();
        content.addView(makeLargeTitle("远程打印"));

        content.addView(makeHeader("打印机"));
        LinearLayout statusCard = makeCard();
        statusCard.addView(makeCardTitle("Epson L3150 / L3151"));
        printStatusText = makeBody("正在发现打印机…");
        statusCard.addView(printStatusText);
        // 绑定状态要如实显示：邮箱空着就明确说"没绑定"，别再写"云打印已连接"糊弄人
        final TextView modelNote = makeCaption(printerEmail == null || printerEmail.isEmpty()
                ? "还没绑定打印机邮箱：云打印用不了（内网直打不受影响）"
                : "云打印已连接 · 远程打印走邮件发送，不用代理也能用");
        modelNote.setPadding(0, dp(6), 0, 0);
        statusCard.addView(modelNote);
        final Button bindBtn = makePrimaryButton("扫码绑定打印机（拍卡片上的二维码）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        qrBindDialog();
                    }
                });
        bindBtn.setVisibility(printerEmail == null || printerEmail.isEmpty()
                ? View.VISIBLE : View.GONE);
        statusCard.addView(bindBtn);
        statusCard.addView(makeSecondaryButton("重新发现打印机", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiscovery();
            }
        }));
        // 内网直连测一页：9100 + ESC/P2 文本，只在手机和打印机同一 Wi-Fi 时有效。
        // 用来快速判断"内网通不通"，不代表中文页面也能这么打（中文仍走云端邮件）。
        statusCard.addView(makeSecondaryButton("内网直连测一页（英文）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        printLanTestPage();
                    }
                }));
        TextView lanNote = makeCaption("内网直连：打印机自己的 9100 端口，不经云端。"
                + "这台机器没有中文字库，所以这条路只能打英文/数字，"
                + "中文页面继续用上面的云端邮件打印。");
        lanNote.setPadding(0, dp(6), 0, 0);
        statusCard.addView(lanNote);
        content.addView(statusCard);

        content.addView(makeHeader("打印文件"));
        LinearLayout printCard = makeCard();
        printCard.addView(makeCardTitle("发文件去打印"));
        printCard.addView(makeBody("选好文件后点「打印」，APP 直接把它发到打印机邮箱："
                + "会跳到手机上的邮箱 App，收件人和标题都填好了，点发送就出纸。"
                + "图片不用先转 PDF——想按 A4 排版再发才用下面的可选按钮。\n"
                + "可以一次选多个文件：几张图会合成一个多页 PDF（一次任务、一次出纸），"
                + "照片和 PDF 混着选就逐个发送。"));
        printCard.addView(makePrimaryButton("选择文件 / 照片", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPrintFile();
            }
        }));
        final TextView pickedName = makeCaption("");
        pickedName.setPadding(0, dp(10), 0, 0);
        printCard.addView(pickedName);
        // 已选图片的缩略图：每张都能看到，点开看大图，避免选错文件
        pickedPreviewScroll = new HorizontalScrollView(this);
        pickedPreviewScroll.setHorizontalScrollBarEnabled(false);
        pickedPreviewRow = new LinearLayout(this);
        pickedPreviewRow.setOrientation(LinearLayout.HORIZONTAL);
        pickedPreviewRow.setPadding(0, dp(8), 0, 0);
        pickedPreviewScroll.addView(pickedPreviewRow);
        pickedPreviewScroll.setVisibility(View.GONE);
        printCard.addView(pickedPreviewScroll);
        // 一个按钮搞定：先探内网，通了直连打印，不通自动走云端
        final Button autoBtn = makePrimaryButton("打印（自动选最快）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        printBest();
                    }
                });
        autoBtn.setVisibility(View.GONE);
        printCard.addView(autoBtn);
        // 手动指定：想强制某条路时才用
        final Button directBtn = makeSecondaryButton("强制用云端邮件打印",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sharePickedDirect();
                    }
                });
        // 可选步骤：图片按 A4 排版（居中、留白），更适合打印
        final Button toPdfBtn = makeSecondaryButton("可选：先转成 A4 PDF 再打印",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sharePickedAsPdf();
                    }
                });
        toPdfBtn.setVisibility(View.GONE);
        directBtn.setVisibility(View.GONE);
        printCard.addView(directBtn);
        // 内网直打：交给 Epson iPrint（爱普生自家 ESC/P-R 编码器），手机与打印机同 Wi-Fi 时最快
        if (ImageUtils.iprintPackage(this) != null) {
            printCard.addView(makeSecondaryButton("用 Epson iPrint 直接打印（同 Wi-Fi）",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            sharePickedToIPrint();
                        }
                    }));
        }
        printCard.addView(toPdfBtn);
        // 内网直打：ESC/P-R 直接发到 9100，不经云端、不用邮箱；只对图片有效（PDF 没法光栅化）
        final Button lanBtn = makeSecondaryButton("强制内网直打（需连家里 Wi-Fi）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        lanPrintPicked();
                    }
                });
        lanBtn.setVisibility(View.GONE);
        printCard.addView(lanBtn);
        printCard.setTag(new Object[]{pickedName, toPdfBtn, directBtn, lanBtn, autoBtn});
        content.addView(printCard);

        content.addView(makeHeader("已发送记录"));
        LinearLayout histCard = makeCard();
        printHistoryText = makeBody(prefs.getString("printHistory", "暂无记录"));
        histCard.addView(printHistoryText);
        content.addView(histCard);

        content.addView(makeCaption("说明：这台打印机不支持 IPP/AirPrint，也没有中文字库，"
                + "所以 APP 没法自己做「点一下直接出纸」的图像打印。"
                + "现在两条可用路径：① 发到打印机邮箱（要装一个邮箱 App）；"
                + "② 交给 Epson iPrint 用爱普生自家驱动直连打印。"));
        return wrapScreen(content);
    }

    private void pickPrintFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.setType("*/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "image/*", "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
        // 允许多选：一次挑好几张图 / 好几个文件
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(it, REQ_PICK_PRINT);
        } catch (Exception e) {
            toast("打不开文件选择器：" + e.getMessage());
        }
    }

    private String getDisplayName(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            android.database.Cursor c = cr.query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && idx >= 0) return c.getString(idx);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "文件" : p;
    }

    private void onPrintPicked(List<Uri> uris) {
        pickedPrintUris.clear();
        pickedPrintNames.clear();
        boolean allImages = true;
        for (Uri u : uris) {
            if (u == null) continue;
            pickedPrintUris.add(u);
            pickedPrintNames.add(getDisplayName(u));
            if (!isImageUri(u)) allImages = false;
        }
        if (pickedPrintUris.isEmpty()) return;
        pickedPrintUri = pickedPrintUris.get(0);
        pickedPrintName = pickedPrintNames.get(0);

        int n = pickedPrintUris.size();
        View printScreen = screens[0];
        LinearLayout printCard = findPrintCard(printScreen);
        if (printCard != null) {
            Object[] tag = (Object[]) printCard.getTag();
            TextView nameView = (TextView) tag[0];
            Button toPdfBtn = (Button) tag[1];
            Button directBtn = (Button) tag[2];
            Button lanBtn = tag.length > 3 ? (Button) tag[3] : null;
            Button autoBtn = tag.length > 4 ? (Button) tag[4] : null;
            if (autoBtn != null) {
                autoBtn.setVisibility(View.VISIBLE);
                autoBtn.setText(n == 1 ? "打印（自动选最快）" : "打印 " + n + " 个（自动选最快）");
            }
            if (lanBtn != null) {
                lanBtn.setVisibility(allImages ? View.VISIBLE : View.GONE);
                lanBtn.setText(n == 1 ? "内网直打（不经云端）" : "内网直打（" + n + " 张）");
            }
            if (n == 1) {
                nameView.setText("已选：" + pickedPrintName);
                toPdfBtn.setText("可选：先转成 A4 PDF 再打印");
                toPdfBtn.setVisibility(allImages ? View.VISIBLE : View.GONE);
                directBtn.setText(allImages ? "打印这张照片" : "打印这个文件");
            } else {
                StringBuilder sb = new StringBuilder("已选 " + n + " 个文件：");
                for (int i = 0; i < Math.min(n, 3); i++) {
                    sb.append(i == 0 ? "" : "、").append(pickedPrintNames.get(i));
                }
                if (n > 3) sb.append(" 等");
                nameView.setText(sb.toString());
                // 多张图能拼成一个多页 PDF，一次发完就是一次打印任务
                toPdfBtn.setText("合成一个 A4 PDF 再打印（" + n + " 页）");
                toPdfBtn.setVisibility(allImages ? View.VISIBLE : View.GONE);
                directBtn.setText("全部打印（" + n + " 个文件）");
            }
            directBtn.setVisibility(View.VISIBLE);
        }
        updatePickedPreview(pickedPrintUris);
        toast("已选 " + n + " 个文件");
    }

    /** 刷新"已选图片"缩略图；非图片显示一个文件名块。 */
    private void updatePickedPreview(final List<Uri> uris) {
        if (pickedPreviewRow == null || pickedPreviewScroll == null) return;
        pickedPreviewRow.removeAllViews();
        pickedPreviewScroll.setVisibility(uris.isEmpty() ? View.GONE : View.VISIBLE);
        for (final Uri u : uris) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(96));
            lp.rightMargin = dp(8);
            if (!isImageUri(u)) {
                TextView tv = makeCaption(getDisplayName(u));
                tv.setLayoutParams(lp);
                tv.setGravity(Gravity.CENTER);
                tv.setBackground(roundBg(C_SEG_BG, 10));
                tv.setMaxLines(4);
                pickedPreviewRow.addView(tv);
                continue;
            }
            final ImageView iv = new ImageView(this);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(roundBg(C_SEG_BG, 10));
            pickedPreviewRow.addView(iv);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBigPreview(u);
                }
            });
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDeletePage("这个文件（" + getDisplayName(u) + "）", new Runnable() {
                        @Override
                        public void run() {
                            removePickedPrint(u);
                        }
                    });
                    return true;
                }
            });
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap thumb = ImageUtils.loadBitmapFromUri(MainActivity.this, u, 320);
                    if (thumb == null) return;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            iv.setImageBitmap(thumb);
                        }
                    });
                }
            }).start();
        }
    }

    /** 打印页：把某个已选文件从本次任务里移除（长按缩略图触发）。 */
    private void removePickedPrint(Uri u) {
        List<Uri> remaining = new ArrayList<Uri>();
        List<String> remainingNames = new ArrayList<String>();
        for (int i = 0; i < pickedPrintUris.size(); i++) {
            if (!pickedPrintUris.get(i).equals(u)) {
                remaining.add(pickedPrintUris.get(i));
                remainingNames.add(pickedPrintNames.get(i));
            }
        }
        pickedPrintUris.clear();
        pickedPrintUris.addAll(remaining);
        pickedPrintNames.clear();
        pickedPrintNames.addAll(remainingNames);
        if (pickedPrintUris.isEmpty()) {
            pickedPrintUri = null;
            pickedPrintName = "";
            pickedPreviewRow.removeAllViews();
            pickedPreviewScroll.setVisibility(View.GONE);
            LinearLayout card = findPrintCard(screens[0]);
            if (card != null) {
                Object[] tag = (Object[]) card.getTag();
                ((TextView) tag[0]).setText("");
                ((Button) tag[1]).setVisibility(View.GONE);
                ((Button) tag[2]).setVisibility(View.GONE);
                if (tag.length > 3) ((Button) tag[3]).setVisibility(View.GONE);
            }
            toast("已清空选择");
            return;
        }
        pickedPrintUri = pickedPrintUris.get(0);
        pickedPrintName = pickedPrintNames.get(0);
        onPrintPicked(pickedPrintUris);
        toast("已移除，还剩 " + pickedPrintUris.size() + " 个");
    }
    /** 点缩略图看大图（确认选对没有）。 */
    private void showBigPreview(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = ImageUtils.loadBitmapFromUri(MainActivity.this, uri, 1600);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bm == null) {
                            toast("读图失败");
                            return;
                        }
                        ImageView iv = new ImageView(MainActivity.this);
                        iv.setAdjustViewBounds(true);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setImageBitmap(bm);
                        int pad = dp(8);
                        iv.setPadding(pad, pad, pad, pad);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("预览：" + getDisplayName(uri))
                                .setView(iv)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private boolean isImageUri(Uri uri) {
        try {
            String mime = getContentResolver().getType(uri);
            return mime != null && mime.startsWith("image/");
        } catch (Exception e) {
            return false;
        }
    }

    /** 在打印页里找到“打印文件”卡片（第二个 makeCard） */
    private LinearLayout findPrintCard(View screen) {
        if (!(screen instanceof ScrollView)) return null;
        LinearLayout content = (LinearLayout) ((ScrollView) screen).getChildAt(0);
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof LinearLayout && v.getTag() instanceof Object[]) {
                return (LinearLayout) v;
            }
        }
        return null;
    }

    private void sharePickedAsPdf() {
        if (pickedPrintUri == null) return;
        final List<Uri> uris = new ArrayList<Uri>(pickedPrintUris);
        if (uris.isEmpty()) uris.add(pickedPrintUri);
        final int n = uris.size();
        final String srcName = n == 1
                ? (pickedPrintName == null ? "文件" : pickedPrintName)
                : (n + " 页");
        toast(n == 1 ? "正在转 A4 PDF…" : "正在把 " + n + " 张图合成一个 PDF…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Bitmap> pages = new ArrayList<Bitmap>();
                    for (Uri u : uris) {
                        Bitmap bm = ImageUtils.loadBitmapFromUri(MainActivity.this, u, 2000);
                        if (bm != null) pages.add(bm);
                    }
                    if (pages.isEmpty()) {
                        toast("读图失败，换张图试试");
                        return;
                    }
                    final Uri pdfUri = ImageUtils.savePdfToDownloads(MainActivity.this,
                            pages, "打印-" + System.currentTimeMillis() + ".pdf");
                    for (Bitmap b : pages) b.recycle();
                    if (pdfUri == null) {
                        toast("生成 PDF 失败");
                        return;
                    }
                    // 注意：recordPrint 会改 TextView，必须回主线程；
                    // 0.7 之前它在后台线程里直接调用，导致点这个按钮必定闪退
                    // （CalledFromWrongThreadException）。
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            deliverToPrinter(pdfUri, srcName + "（A4 PDF）", "application/pdf");
                        }
                    });
                } catch (final Throwable t) {
                    // 后台线程抛出的异常会直接杀掉进程，这里兜住并给出可读提示
                    Log.e("Print", "转 A4 PDF 失败", t);
                    toast("转 A4 PDF 失败：" + t.getClass().getSimpleName()
                            + "，可改用「直接发原文件」");
                }
            }
        }).start();
    }

    private void sharePickedDirect() {
        if (pickedPrintUri == null) return;
        int n = pickedPrintUris.size();
        if (n <= 1) {
            String mime = "application/octet-stream";
            try {
                String t = getContentResolver().getType(pickedPrintUri);
                if (t != null) mime = t;
            } catch (Exception ignored) {
            }
            deliverToPrinter(pickedPrintUri, pickedPrintName, mime);
            return;
        }
        // 多选：全是图片就合成一个多页 PDF（一次任务，一次出纸）；混着别的就逐个发
        boolean allImages = true;
        for (Uri u : pickedPrintUris) {
            if (!isImageUri(u)) allImages = false;
        }
        if (allImages) {
            sharePickedAsPdf();
            return;
        }
        final List<Uri> uris = new ArrayList<Uri>(pickedPrintUris);
        final List<String> names = new ArrayList<String>(pickedPrintNames);
        final MailSender.Config cfg = smtpConfig();
        if (cfg == null) {
            toast("多个文件必须用 APP 内发信（设置 → 打印发信），先填邮箱和授权码");
            return;
        }
        toast("正在逐个发送 " + uris.size() + " 个文件…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                for (int i = 0; i < uris.size(); i++) {
                    try {
                        sendPrintMailSync(cfg, uris.get(i), names.get(i), null);
                        ok++;
                    } catch (IOException e) {
                        Log.w("Print", "第 " + (i + 1) + " 个文件发送失败", e);
                    }
                }
                final int sent = ok;
                recordPrint(names.size() + " 个文件（逐个发送，成功 " + sent + "）");
                toast("已发送 " + sent + "/" + names.size() + " 个文件到打印机邮箱");
            }
        }).start();
    }

    /**
     * 统一的"送到打印机"入口，按可用性依次退让：
     * 1）APP 自己发信（最快，家里人零操作）；
     * 2）手机上有邮箱 App 就跳过去（旧路径）；
     * 3）都没有就弹说明，引导装邮箱或交给 Epson iPrint。
     */
    private void deliverToPrinter(final Uri uri, final String name, final String mime) {
        if (!ensurePrinterBound()) return;
        if (smtpConfig() != null) {
            sendPrintMail(uri, name, mime);
            return;
        }
        if (!ImageUtils.hasEmailApp(this)) {
            askWithoutEmailApp(uri, mime == null ? "application/octet-stream" : mime, name);
            return;
        }
        recordPrint(name);
        ImageUtils.shareToPrinter(this, uri, mime == null ? "application/octet-stream" : mime,
                "远程打印", printerEmail);
    }

    /** 交给 Epson iPrint 打印（它自带 ESC/P-R 编码器，能真出纸）。 */
    private void sharePickedToIPrint() {
        if (pickedPrintUri == null) {
            toast("先选文件或照片");
            return;
        }
        String mime = "image/jpeg";
        try {
            String t = getContentResolver().getType(pickedPrintUri);
            if (t != null) mime = t;
        } catch (Exception ignored) {
        }
        if (ImageUtils.shareToIPrint(this, pickedPrintUri, mime)) {
            recordPrint(pickedPrintName + "（iPrint 直打）");
        } else {
            toast("没找到 Epson iPrint，先装一个再试");
        }
    }

    /**
     * 取发信配置：优先用设置里手填的；没填就用构建时内置的（家里人装了就能直接打）。
     */
    private MailSender.Config smtpConfig() {
        MailSender.Config c = new MailSender.Config();
        String user = prefs.getString("smtpUser", "").trim();
        String pass = prefs.getString("smtpPass", "");
        String host = prefs.getString("smtpHost", "").trim();
        if (user.isEmpty() || pass.isEmpty()) {
            MailSender.Config builtIn = MailConfig.defaults();
            if (builtIn == null) return null;
            c = builtIn;
        } else {
            c.user = user;
            c.pass = pass;
            c.host = host.isEmpty() ? guessSmtpHost(user) : host;
            c.port = prefs.getInt("smtpPort", 465);
            c.ssl = prefs.getBoolean("smtpSsl", true);
            c.from = prefs.getString("smtpFrom", user);
        }
        if (c.host == null || c.host.isEmpty()) c.host = guessSmtpHost(c.user);
        c.to = printerEmail;
        return c;
    }

    /** 按邮箱域名猜 SMTP 服务器（QQ/163/126 都是 SSL 465）。 */
    private static String guessSmtpHost(String email) {
        String d = email != null && email.contains("@")
                ? email.substring(email.indexOf('@') + 1).toLowerCase(Locale.US) : "";
        if (d.contains("qq.com")) return "smtp.qq.com";
        if (d.contains("163.com")) return "smtp.163.com";
        if (d.contains("126.com")) return "smtp.126.com";
        if (d.contains("sina")) return "smtp.sina.com";
        if (d.contains("aliyun")) return "smtp.aliyun.com";
        if (d.contains("outlook") || d.contains("hotmail")) return "smtp.office365.com";
        return d.isEmpty() ? "" : "smtp." + d;
    }

    /**
     * 导出"发信配置串"：把发件邮箱 / 授权码 / 服务器 / 端口打包成一行，
     * 通过微信发给家里人，对方在设置里点「粘贴导入配置」就自动填好。
     * 配置串只在他们两台手机之间传，不写进安装包。
     */
    private void copyMailConfig() {
        MailSender.Config c = smtpConfig();
        if (c == null) {
            toast("先在上面的输入框里填好发信邮箱和授权码，再点导出");
            return;
        }
        String raw = c.user + "\u0000" + c.pass + "\u0000"
                + (c.host == null ? "" : c.host) + "\u0000" + c.port
                + "\u0000" + (printerEmail == null ? "" : printerEmail);
        String payload = android.util.Base64.encodeToString(
                raw.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                android.util.Base64.NO_WRAP);
        String text = "ZJDYJ1:" + payload;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("打印配置", text));
        toast("配置串已复制，可直接粘贴到微信发给家里人");
    }

    /** 从剪贴板导入别人发来的配置串。 */
    private void importMailConfig() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板是空的，先在微信里复制那条配置串");
                return;
            }
            String text = String.valueOf(
                    cm.getPrimaryClip().getItemAt(0).coerceToText(this)).trim();
            if (!text.startsWith("ZJDYJ1:")) {
                toast("这不是发信配置串（应以 ZJDYJ1: 开头）");
                return;
            }
            String raw = new String(android.util.Base64.decode(
                    text.substring(7).trim(), android.util.Base64.NO_WRAP), "UTF-8");
            String[] p = raw.split("\u0000", -1);
            if (p.length < 2 || p[0].isEmpty() || p[1].isEmpty()) {
                toast("配置串内容不完整");
                return;
            }
            String host = p.length > 2 && !p[2].isEmpty() ? p[2] : guessSmtpHost(p[0]);
            String boundPrinter = p.length > 4 ? p[4].trim() : "";
            if (!boundPrinter.isEmpty()) {
                printerEmail = boundPrinter;
                prefs.edit().putString("email", boundPrinter).apply();
            }
            int port = p.length > 3 ? Integer.parseInt(p[3].trim()) : 465;
            prefs.edit().putString("smtpUser", p[0])
                    .putString("smtpPass", p[1])
                    .putString("smtpHost", host)
                    .putString("smtpFrom", p[0])
                    .putInt("smtpPort", port)
                    .putBoolean("smtpSsl", true)
                    .apply();
            toast("已导入发信配置：" + p[0] + "，现在可以直接打印了");
        } catch (Exception e) {
            Log.w("Print", "导入配置失败", e);
            toast("导入失败：" + (e.getMessage() == null ? "格式不对" : e.getMessage()));
        }
    }

    // ---------- 扫二维码绑定打印机 ----------

    /** 扫码绑定：拍照或从相册选，读出二维码里的打印机邮箱。 */
    private void qrBindDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("扫二维码绑定打印机");
        b.setMessage("拍打印机说明书/机身上的二维码（内容就是这台机器的打印邮箱），"
                + "APP 会自动读出来并绑定，不用手抄。\n\n绑定后界面上只显示脱敏地址。");
        b.setPositiveButton("拍照", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                qrTakePhoto();
            }
        });
        b.setNeutralButton("从相册选", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                qrPickPhoto();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void qrPickPhoto() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(it, REQ_QR_PICK);
        } catch (Exception e) {
            toast("打不开相册：" + e.getMessage());
        }
    }

    private void qrTakePhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "qr-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/宅家打印机");
            cameraUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri == null) {
                toast("相机启动失败");
                return;
            }
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            startActivityForResult(it, REQ_QR_CAMERA);
        } catch (Exception e) {
            toast("相机启动失败：" + e.getMessage());
        }
    }

    /** 解码选中的二维码图：像邮箱就绑定，否则把内容展示出来。 */
    private void handleQrUri(final Uri uri) {
        toast("正在识别二维码…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = ImageUtils.loadBitmapFromUri(MainActivity.this, uri, 1600);
                final String text = QrScan.decode(bm);
                if (bm != null) bm.recycle();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null) {
                            toast("没识别到二维码：让二维码占满画面、别反光、别拍糊再试");
                            return;
                        }
                        if (QrScan.looksLikeEmail(text)) {
                            printerEmail = text.trim();
                            prefs.edit().putString("email", printerEmail).apply();
                            if (printerEmailLabel != null) {
                                printerEmailLabel.setText("当前打印机邮箱："
                                        + QrScan.mask(printerEmail));
                            }
                            toast("已绑定：" + QrScan.mask(printerEmail));
                            refreshPrintPageBinding();
                        } else {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("二维码内容")
                                    .setMessage(text + "\n\n这不是打印机邮箱，没有自动绑定。")
                                    .setPositiveButton("复制内容",
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface d, int w) {
                                                    android.content.ClipboardManager cm =
                                                            (android.content.ClipboardManager)
                                                                    getSystemService(CLIPBOARD_SERVICE);
                                                    cm.setPrimaryClip(android.content.ClipData
                                                            .newPlainText("二维码", text));
                                                    toast("已复制");
                                                }
                                            })
                                    .setNegativeButton("关闭", null)
                                    .show();
                        }
                    }
                });
            }
        }).start();
    }
    /** 测发信连接：只做 EHLO + 认证，不发信、不出纸。 */
    private void testSmtp() {
        final MailSender.Config c = smtpConfig();
        if (c == null) {
            toast("还没填发信邮箱和授权码");
            return;
        }
        toast("正在测试发信连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String r = MailSender.testLogin(c);
                    toast(r);
                } catch (final Exception e) {
                    Log.w("Print", "发信测试失败", e);
                    toast("发信测试失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
                }
            }
        }).start();
    }

    /**
     * APP 自己把文件发到打印机邮箱（Epson Connect Email Print）。
     * 这条路上不出现任何邮箱 App：家里人点一下"打印"就行。
     */
    private void sendPrintMail(final Uri uri, final String name, final String mime) {
        final MailSender.Config cfg = smtpConfig();
        if (cfg == null) return;
        toast("正在发到打印机邮箱…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sendPrintMailSync(cfg, uri, name, mime);
                    toast("已发到打印机邮箱，约 30 秒出纸");
                } catch (final IOException e) {
                    Log.e("Print", "发信失败", e);
                    final String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                    toast("发信失败：" + msg);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (printStatusText != null) {
                                printStatusText.setText("发信失败：" + msg
                                        + "\n可在「设置 → 打印发信」里检查邮箱和授权码。");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    /** 同步发一封打印邮件（只能在后台线程调用）。多文件逐个发送时复用。 */
    private void sendPrintMailSync(MailSender.Config cfg, Uri uri, String name, String mime)
            throws IOException {
        byte[] data;
        String sendMime = mime == null ? "application/octet-stream" : mime;
        try {
            if (sendMime.equals("application/octet-stream")) {
                String t = getContentResolver().getType(uri);
                if (t != null) sendMime = t;
            }
        } catch (Exception ignored) {
        }
        String sendName = name == null || name.isEmpty() ? "print" : name;
        if (sendMime.startsWith("image/")) {
            Bitmap bm = ImageUtils.loadBitmapFromUri(MainActivity.this, uri, 2400);
            if (bm == null) throw new IOException("读图失败");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 90, bos);
            bm.recycle();
            data = bos.toByteArray();
            sendMime = "image/jpeg";
            sendName = "print-" + System.currentTimeMillis() + ".jpg";
        } else {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) throw new IOException("打不开这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            data = bos.toByteArray();
            if (!sendName.contains(".")) {
                sendName = sendName + (sendMime.contains("pdf") ? ".pdf" : ".dat");
            }
        }
        if (data.length > 20 * 1024 * 1024) {
            throw new IOException("文件太大（超过 20MB），打印机邮箱可能不收");
        }
        MailSender.send(cfg, "打印：" + (name == null ? "文件" : name),
                "宅家打印机 APP 直接发送，请打印。", sendName, sendMime, data);
        if (name != null) recordPrint(name);
    }

    private void askWithoutEmailApp(final Uri uri, final String mime, final String name) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("手机上没有邮箱 App");
        b.setMessage("邮件打印要先发一封邮件到打印机邮箱，点完会跳到邮箱 App 里点“发送”。"
                + "你现在手机里没有能收邮件的 App，所以分享面板只列出了微信、抖音这些。\n\n"
                + "两条路：\n"
                + "1）装一个邮箱 App（QQ 邮箱 / 163 邮箱都行），装好再点「打印」，"
                + "收件人会自动填成打印机地址；\n"
                + "2）用 Epson iPrint 直连打印（手机和打印机在同一个 Wi-Fi 时最快，"
                + "打照片不需要邮件）。");
        if (ImageUtils.iprintPackage(this) != null) {
            b.setPositiveButton("用 Epson iPrint 打", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    if (ImageUtils.shareToIPrint(MainActivity.this, uri, mime)) {
                        recordPrint(name + "（iPrint 直打）");
                    } else {
                        toast("拉起 Epson iPrint 失败");
                    }
                }
            });
        }
        b.setNeutralButton("复制打印机邮箱", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("打印机邮箱", printerEmail));
                toast("已复制：" + printerEmail + "（发邮件时自己粘一下）");
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /**
     * 内网直打：把选中的图片渲染成 A4@360dpi 的 JPEG，用 ESC/P-R 直接发到打印机 9100。
     * 全程不经云端、不用邮箱 App；打完用 SNMP 页计数确认，没动就提示改用邮件。
     */
    /** 绑定状态变了以后，刷新打印页上的提示与按钮（不用重启 APP）。 */
    private void refreshPrintPageBinding() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showTab(0);
            }
        });
    }
    /** 打印机邮箱没绑定时提示怎么绑（扫码或粘贴配置），返回是否已绑定。 */
    private boolean ensurePrinterBound() {
        if (printerEmail != null && !printerEmail.trim().isEmpty()) return true;
        new AlertDialog.Builder(this)
                .setTitle("还没绑定打印机")
                .setMessage("这台手机还不知道打印机的云邮箱。两种绑法：\n\n"
                        + "1）扫打印机说明书/卡片上的二维码（最快）；\n"
                        + "2）在设置里手动填一次，或粘贴家里人发来的配置串。\n\n"
                        + "内网直打（同 Wi-Fi）不需要这个邮箱。")
                .setPositiveButton("扫码绑定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        qrBindDialog();
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
        return false;
    }
    private void lanPrintPicked() {
        final String ip = currentScanIp();
        if (ip == null) {
            toast("先发现打印机，或手动填 IP");
            return;
        }
        lanPrintUris(ip, new ArrayList<Uri>(pickedPrintUris), pickedPrintName);
    }

    /**
     * 自动选路：**优先内网直打，内网不通再走云端邮件**。
     * 判断依据是 9100 能不能连上（1.2 秒探测）+ 文件类型（PDF/Word 没法在本地光栅化）。
     */
    private void printBest() {
        final Uri uri = pickedPrintUri;
        if (uri == null) {
            toast("先选文件或照片");
            return;
        }
        final String ip = currentScanIp();
        final String name = pickedPrintName;
        final String mime = mimeOf(uri);
        final List<Uri> uris = new ArrayList<Uri>(pickedPrintUris);
        if (uris.isEmpty()) uris.add(uri);
        final boolean imagesOnly = allImages(uris);
        toast("正在看内网通不通…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean lanOk = ip != null && probeLan(ip, 1200);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (lanOk && imagesOnly) {
                            toast("内网通，直连打印（不经云端）");
                            lanPrintUris(ip, uris, name);
                        } else if (lanOk) {
                            toast("内网通，但 PDF/Word 只能走云端，已发邮件");
                            deliverToPrinter(uri, name, mime);
                        } else {
                            toast("内网不通（可能不在家），走云端邮件打印");
                            deliverToPrinter(uri, name, mime);
                        }
                    }
                });
            }
        }).start();
    }

    /** 探一下打印机 9100 是否可达；真机上比 ping 更准（打印机可能禁 ping）。 */
    private boolean probeLan(String ip, int timeoutMs) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(ip, EscprClient.PORT), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String mimeOf(Uri uri) {
        try {
            String t = getContentResolver().getType(uri);
            if (t != null) return t;
        } catch (Exception ignored) {
        }
        return "application/octet-stream";
    }

    private boolean allImages(List<Uri> uris) {
        for (Uri u : uris) {
            if (!isImageUri(u)) return false;
        }
        return !uris.isEmpty();
    }

    /** 内网直打（指定 IP 与文件列表）；扫描页、多选、自动模式共用。 */
    private void lanPrintUris(final String ip, final List<Uri> uris, final String label) {
        if (uris == null || uris.isEmpty()) {
            toast("先选图片");
            return;
        }
        toast("正在准备内网直打…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                long before = SnmpClient.pageCount(ip);
                int ok = 0;
                for (Uri u : uris) {
                    Bitmap bm = ImageUtils.loadBitmapFromUri(MainActivity.this, u, 3000);
                    if (bm == null) continue;
                    byte[] jpeg = ImageUtils.renderA4Jpeg(bm, 88);
                    bm.recycle();
                    if (jpeg == null) continue;
                    try {
                        EscprClient.printJpeg(ip, jpeg);
                        ok++;
                        Thread.sleep(1200);      // 让打印机先把上一页吃进去
                    } catch (final IOException e) {
                        Log.w("Print", "内网直打失败", e);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                offerEmailFallback("内网直打失败：" + e.getMessage());
                            }
                        });
                        return;
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                if (ok == 0) {
                    toast("没读到可用图片");
                    return;
                }
                // 用页计数确认：9100 没有回执，只能看它到底有没有出纸
                boolean printed = false;
                if (before >= 0) {
                    for (int i = 0; i < 25; i++) {
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            break;
                        }
                        long now = SnmpClient.pageCount(ip);
                        if (now >= before + ok) {
                            printed = true;
                            break;
                        }
                    }
                }
                final boolean done = printed;
                final int sent = ok;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            recordPrint(label + "（内网直打）", "已直连打印");
                            toast("已直连打印 " + sent + " 页（不经云端）");
                        } else {
                            offerEmailFallback("已发给打印机，但 60 秒内没检测到出纸。"
                                    + "可能是纸没用对，或者这台固件对参数有要求。");
                        }
                    }
                });
            }
        }).start();
    }

    /** 内网直打不顺时的兜底：问一下要不要改走邮件打印（避免重复出纸，不自动打）。 */
    private void offerEmailFallback(String reason) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("内网直打没成功");
        b.setMessage(reason + "\n\n要改用云端邮件打印吗？（会走打印机邮箱，大约 30 秒出纸）");
        b.setPositiveButton("改用邮件打印", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                if (pickedPrintUri == null) {
                    toast("没有可打印的文件");
                    return;
                }
                String mime = "application/octet-stream";
                try {
                    String t = getContentResolver().getType(pickedPrintUri);
                    if (t != null) mime = t;
                } catch (Exception ignored) {
                }
                deliverToPrinter(pickedPrintUri, pickedPrintName, mime);
            }
        });
        b.setNegativeButton("知道了", null);
        b.show();
    }
    private void recordPrint(String name) {
        recordPrint(name, "已分享");
    }

    /** verb 用来区分"已分享（邮件）"和"已直连打印（内网）"。 */
    private void recordPrint(String name, String verb) {
        String old = prefs.getString("printHistory", "");
        String line = nowTime() + "  " + name + "  " + verb;
        String updated = line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = updated.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, lines.length); i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        prefs.edit().putString("printHistory", sb.toString()).apply();
        // 更新界面必须回主线程：这个方法以前被后台线程直接调用，会抛
        // CalledFromWrongThreadException 把进程打死（打印页“转 A4 PDF”闪退就是这个）。
        final String text = sb.toString();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (printHistoryText != null) printHistoryText.setText(text);
            }
        });
    }

    // ---------- 去手写页 ----------
    private View buildCleanScreen() {
        LinearLayout content = screenContent();
        content.addView(makeLargeTitle("试卷去手写"));

        content.addView(makeHeader("试卷照片"));
        LinearLayout pickCard = makeCard();
        pickCard.addView(makeCardTitle("拍一张做过的试卷"));
        pickCard.addView(makeBody("红笔答案、圈画、演算会自动去掉，只留黑色印刷题目，"
                + "孩子能拿空白卷重做一遍。黑笔写的小标记用橡皮擦手动抹。"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button camBtn = makeSecondaryButton("拍照", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePaperPhoto();
            }
        });
        Button pickBtn = makeSecondaryButton("从相册选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.setType("image/*");
                it.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(it, REQ_PICK_PAPER);
                } catch (Exception e) {
                    toast("打不开相册：" + e.getMessage());
                }
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        rp.rightMargin = dp(6);
        camBtn.setLayoutParams(rp);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        rp2.leftMargin = dp(6);
        pickBtn.setLayoutParams(rp2);
        row.addView(camBtn);
        row.addView(pickBtn);
        pickCard.addView(row);
        content.addView(pickCard);

        LinearLayout previewCard = makeCard();
        paperPreview = new ImageView(this);
        paperPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        paperPreview.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        paperPreview.setLayoutParams(pp);
        paperPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!eraserMode || currentPaper == null) return false;
                if (event.getAction() != MotionEvent.ACTION_DOWN
                        && event.getAction() != MotionEvent.ACTION_MOVE) {
                    return true;
                }
                Matrix inverse = new Matrix();
                paperPreview.getImageMatrix().invert(inverse);
                float[] pts = {event.getX(), event.getY()};
                inverse.mapPoints(pts);
                Canvas c = new Canvas(currentPaper);
                Paint p = new Paint();
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.FILL);
                p.setAntiAlias(true);
                c.drawCircle(pts[0], pts[1], 32, p);
                paperPreview.invalidate();
                return true;
            }
        });
        previewCard.addView(paperPreview);
        TextView previewHint = makeCaption("还没选试卷照片");
        previewHint.setPadding(0, dp(8), 0, 0);
        previewHint.setGravity(Gravity.CENTER);
        previewCard.addView(previewHint);
        previewCard.setTag(previewHint);
        content.addView(previewCard);

        content.addView(makeHeader("处理"));
        LinearLayout procCard = makeCard();
        procCard.addView(makeCardTitle("去掉已做过的答案"));
        final SegmentedControl modeSeg = new SegmentedControl(
                new String[]{"查看", "橡皮擦"});
        modeSeg.setOnSelect(new OnSelect() {
            @Override
            public void onSelect(int index) {
                eraserMode = (index == 1);
                toast(eraserMode ? "橡皮擦：在图上划动可擦掉黑笔标记"
                        : "查看模式");
            }
        });
        procCard.addView(modeSeg.layout);
        procCard.addView(makePrimaryButton("AI 识别手写", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aiCleanPaper();
            }
        }));
        aiCleanStatusText = makeCaption(
                "AI 识别：把试卷发给 AI 看图，标出手写笔迹的位置再擦除，"
                + "黑笔字也能区分。AI 出错时自动按老办法去红。");
        aiCleanStatusText.setPadding(0, dp(8), 0, 0);
        procCard.addView(aiCleanStatusText);
        TextView strLabel = makeCaption("强度（柔和留细节，强力更干净但浅色印刷会变淡）");
        strLabel.setPadding(0, dp(10), 0, 0);
        procCard.addView(strLabel);
        SegmentedControl paperStrSeg = new SegmentedControl(new String[]{"柔和", "标准", "强力"});
        paperStrSeg.setOnSelect(new OnSelect() {
            @Override
            public void onSelect(int index) {
                paperStrength = index;
                applyPaperClean();
            }
        });
        procCard.addView(paperStrSeg.layout);
        procCard.addView(makePrimaryButton("一键去手写（自动去彩色笔迹）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyPaperClean();
                    }
                }));
        procCard.addView(makeSecondaryButton("只去红笔（旧算法，仅红色）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processPaper(true);
            }
        }));
        procCard.addView(makeSecondaryButton("提白锐化", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processPaper(false);
            }
        }));
        procCard.addView(makeSecondaryButton("恢复原图", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (originalPaper == null) {
                    toast("还没有原图");
                    return;
                }
                currentPaper = originalPaper.copy(Bitmap.Config.ARGB_8888, true);
                paperPreview.setImageBitmap(currentPaper);
                if (aiCleanStatusText != null) {
                    aiCleanStatusText.setText("已恢复原图");
                }
                toast("已恢复原图");
            }
        }));
        content.addView(procCard);

        content.addView(makeHeader("导出"));
        LinearLayout exportCard = makeCard();
        exportCard.addView(makePrimaryButton("导出 A4 PDF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportPaperPdf(false);
            }
        }));
        exportCard.addView(makePrimaryButton("直接打印（内网优先）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        printPaperBest();
                    }
                }));
        exportCard.addView(makeSecondaryButton("导出并直接发去打印",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        exportPaperPdf(true);
                    }
                }));
        exportCard.addView(makeSecondaryButton("存入错题本",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        savePaperToNotebook();
                    }
                }));
        content.addView(exportCard);
        return wrapScreen(content);
    }

    private void takePaperPhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "试卷-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/宅家打印机");
            cameraUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri == null) {
                toast("相机启动失败");
                return;
            }
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            startActivityForResult(it, REQ_CAMERA_PAPER);
        } catch (Exception e) {
            toast("相机启动失败：" + e.getMessage());
        }
    }

    private void setPaperBitmap(final Bitmap bm) {
        if (bm == null) return;
        originalPaper = bm;
        currentPaper = bm.copy(Bitmap.Config.ARGB_8888, true);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                paperPreview.setImageBitmap(currentPaper);
            }
        });
        // 拍照质检：照片载入后自动跑，有问题弹窗提醒（没填密钥时跳过）
        runPaperQualityCheck(bm);
    }

    /** 一键去手写：用 PaperClean（背景归一化 + 多色去墨 + 去噪点），强度按选项。 */
    private void applyPaperClean() {
        if (originalPaper == null) {
            toast("先拍照或从相册选一张试卷");
            return;
        }
        final Bitmap src = originalPaper;
        final int s = paperStrength;
        final String label = s == 0 ? "柔和" : (s == 1 ? "标准" : "强力");
        toast("正在去手写（" + label + "）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap out = ImageUtils.cleanPaper(src, s);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (out == null) {
                            toast("处理失败，换张照片试试");
                            return;
                        }
                        currentPaper = out;
                        paperPreview.setImageBitmap(out);
                        if (aiCleanStatusText != null) {
                            aiCleanStatusText.setText("已去手写（" + label
                                    + "）。漏掉的小标记可以用下面的橡皮擦抹掉。");
                        }
                        toast("处理完成（" + label + "）");
                    }
                });
            }
        }).start();
    }

    /** 试卷直接打印：优先内网直打，不通则导出 PDF 走云端。 */
    private void printPaperBest() {
        if (currentPaper == null) {
            toast("先把试卷照片载入并去手写");
            return;
        }
        final String ip = currentScanIp();
        toast("正在看内网通不通…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean lanOk = ip != null && probeLan(ip, 1200);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (lanOk) {
                            toast("内网通，直连打印");
                            lanPrintBitmap(ip, currentPaper, "试卷");
                        } else {
                            toast("内网不通，走云端邮件打印");
                            exportPaperPdf(true);
                        }
                    }
                });
            }
        }).start();
    }

    /** 直接把一张位图按 A4 内网直打（试卷、扫描件都能用）。 */
    private void lanPrintBitmap(final String ip, final Bitmap bm, final String label) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] jpeg = ImageUtils.renderA4Jpeg(bm, 88);
                if (jpeg == null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("渲染 A4 失败（内存不足？）");
                        }
                    });
                    return;
                }
                long before = SnmpClient.pageCount(ip);
                try {
                    EscprClient.printJpeg(ip, jpeg);
                } catch (final IOException e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            offerEmailFallback("内网直打失败：" + e.getMessage());
                        }
                    });
                    return;
                }
                boolean printed = false;
                if (before >= 0) {
                    for (int i = 0; i < 24; i++) {
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (SnmpClient.pageCount(ip) > before) {
                            printed = true;
                            break;
                        }
                    }
                }
                final boolean done = printed;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            recordPrint(label + "（内网直打）", "已直连打印");
                            toast("已直连打印：" + label);
                        } else {
                            offerEmailFallback("已发给打印机，但 60 秒内没检测到出纸。");
                        }
                    }
                });
            }
        }).start();
    }
    /** red=true 去红笔；false 提白锐化 */
    private void processPaper(final boolean red) {
        if (currentPaper == null) {
            toast("先选一张试卷照片");
            return;
        }
        toast("处理中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap src = currentPaper;
                final Bitmap out = red
                        ? ImageUtils.removeRed(src)
                        : ImageUtils.whitenSharpen(src);
                currentPaper = out;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        paperPreview.setImageBitmap(currentPaper);
                        toast(red ? "红笔已去掉" : "已提白锐化");
                    }
                });
            }
        }).start();
    }

    private void exportPaperPdf(final boolean shareAfter) {
        if (currentPaper == null) {
            toast("先选一张试卷照片");
            return;
        }
        toast("正在生成 A4 PDF…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Bitmap> pages = new ArrayList<Bitmap>();
                pages.add(currentPaper);
                final Uri uri = ImageUtils.savePdfToDownloads(MainActivity.this,
                        pages, "空白卷-" + System.currentTimeMillis() + ".pdf");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            toast("生成 PDF 失败");
                            return;
                        }
                        if (shareAfter) {
                            deliverToPrinter(uri, "空白卷（A4 PDF）", "application/pdf");
                        } else {
                            toast("已存到 Download/宅家打印机");
                        }
                    }
                });
            }
        }).start();
    }

    private void savePaperToNotebook() {
        if (currentPaper == null) {
            toast("先选一张试卷照片");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                File f = ImageUtils.saveToNotebook(MainActivity.this, currentPaper,
                        "错题-" + System.currentTimeMillis() + ".jpg");
                toast(f == null ? "存入错题本失败" : "已存入错题本");
            }
        }).start();
    }

    // ================= AI 通用 =================
    /** 没填密钥时提示并跳到设置页，返回 false 表示不能继续 */
    private boolean requireAiKey() {
        if (AiClient.hasKey(this)) return true;
        toast("AI 功能要先填 API 密钥：去“设置 → AI 设置”里填");
        showTab(5);
        return false;
    }

    /** 拍照质检后自动跑：判断模糊、倾斜、阴影、反光 */
    private void runPaperQualityCheck(final Bitmap bm) {
        if (bm == null || !AiClient.hasKey(this) || qualityCheckRunning) return;
        qualityCheckRunning = true;
        String system = "你是一个照片质量检查助手。只返回 JSON，不要解释。";
        String user = "请检查这张试卷/错题照片的质量，判断：是否模糊、是否倾斜、"
                + "有没有阴影、有没有反光。只返回 JSON，格式："
                + "{\"ok\":true,\"issues\":[],\"suggestion\":\"\"}。"
                + "有问题时 ok 为 false，issues 列出问题（如 [\"模糊\",\"阴影\"]），"
                + "suggestion 给一句重拍建议（如“靠近一点、避开阴影再拍”）。";
        AiClient.chatWithImage(this, system, user, bm, new AiClient.Callback() {
            @Override
            public void onOk(String content) {
                qualityCheckRunning = false;
                try {
                    String json = AiClient.extractJsonObject(content);
                    if (json == null) return;
                    JSONObject o = new JSONObject(json);
                    boolean ok = o.optBoolean("ok", true);
                    if (ok) {
                        toast("照片质量没问题，可以处理");
                        return;
                    }
                    JSONArray issues = o.optJSONArray("issues");
                    StringBuilder sb = new StringBuilder();
                    if (issues != null) {
                        for (int i = 0; i < issues.length(); i++) {
                            if (i > 0) sb.append("、");
                            sb.append(issues.optString(i));
                        }
                    }
                    String suggestion = o.optString("suggestion", "");
                    showQualityDialog(sb.toString(), suggestion);
                } catch (Exception ignored) {
                    // 质检解析失败不打扰用户：当作没检查过
                }
            }

            @Override
            public void onError(String friendlyError) {
                qualityCheckRunning = false;
                // 质检失败不打扰用户，静默跳过
                Log.w("AI", "质检失败：" + friendlyError);
            }
        });
    }

    /** 质检有问题时的弹窗：重拍 / 一键本地增强 / 不用管 */
    private void showQualityDialog(String issues, String suggestion) {
        String msg = "这张照片有点问题：" + (issues.isEmpty() ? "不太清楚" : issues)
                + "。\n" + (suggestion.isEmpty() ? "建议换个角度、避开阴影再拍一张。"
                : suggestion);
        new AlertDialog.Builder(this)
                .setTitle("照片质量提醒")
                .setMessage(msg)
                .setPositiveButton("一键本地增强", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        processPaper(false);
                    }
                })
                .setNeutralButton("重拍 / 换一张", null)
                .setNegativeButton("不用管", null)
                .show();
    }

    // ================= 功能 1：AI 指导去手写 =================
    private void aiCleanPaper() {
        if (currentPaper == null) {
            toast("先选一张试卷照片");
            return;
        }
        if (!requireAiKey()) return;
        if (aiCleanStatusText != null) {
            aiCleanStatusText.setText("AI 正在看图识别手写笔迹…");
        }
        toast("AI 正在看图识别手写…");
        final Bitmap src = currentPaper;
        String system = "你是一个试卷图像分析助手。只返回 JSON，不要解释。";
        String user = "请找出这张试卷照片里所有的手写笔迹区域，包括红笔和黑笔。"
                + "坐标用 0-1000 归一化（x,y 为区域左上角，w,h 为宽高）。"
                + "只返回 JSON，格式："
                + "{\"regions\":[{\"x\":120,\"y\":200,\"w\":300,\"h\":40,\"kind\":\"红笔\"}]}。"
                + "没有手写就返回 {\"regions\":[]}。区域尽量贴紧笔迹，不要框住整道题。";
        AiClient.chatWithImage(this, system, user, src, new AiClient.Callback() {
            @Override
            public void onOk(String content) {
                try {
                    String json = AiClient.extractJsonObject(content);
                    if (json == null) throw new Exception("no json");
                    JSONObject o = new JSONObject(json);
                    JSONArray arr = o.optJSONArray("regions");
                    if (arr == null || arr.length() == 0) {
                        toast("AI 没找到手写笔迹，已按老办法去红");
                        processPaper(true);
                        return;
                    }
                    final List<Rect> red = new ArrayList<Rect>();
                    final List<Rect> black = new ArrayList<Rect>();
                    int w = src.getWidth();
                    int h = src.getHeight();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.getJSONObject(i);
                        int x = r.optInt("x", 0) * w / 1000;
                        int y = r.optInt("y", 0) * h / 1000;
                        int rw = Math.max(1, r.optInt("w", 0) * w / 1000);
                        int rh = Math.max(1, r.optInt("h", 0) * h / 1000);
                        Rect rect = new Rect(x, y, x + rw, y + rh);
                        String kind = r.optString("kind", "");
                        if (kind.contains("红")) red.add(rect);
                        else black.add(rect);
                    }
                    final int total = red.size() + black.size();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Bitmap erased = ImageUtils.eraseRegions(
                                    src, red, black);
                            final Bitmap out = ImageUtils.whitenSharpen(erased);
                            currentPaper = out;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    paperPreview.setImageBitmap(currentPaper);
                                    String msg = "AI 识别到 " + total + " 处手写"
                                            + "（红笔 " + red.size() + " 处，黑笔 "
                                            + black.size() + " 处），已擦除并提白锐化";
                                    if (aiCleanStatusText != null) {
                                        aiCleanStatusText.setText(msg);
                                    }
                                    toast(msg);
                                }
                            });
                        }
                    }).start();
                } catch (Exception e) {
                    toast("AI 返回格式不对，已按老办法去红");
                    if (aiCleanStatusText != null) {
                        aiCleanStatusText.setText("AI 返回格式不对，已按老办法去红");
                    }
                    processPaper(true);
                }
            }

            @Override
            public void onError(String friendlyError) {
                toast(friendlyError + "，已按老办法去红");
                if (aiCleanStatusText != null) {
                    aiCleanStatusText.setText(friendlyError + "，已按老办法去红");
                }
                processPaper(true);
            }
        });
    }

    // ================= 功能 2：错题本 AI 分析 =================
    private void pickImageForAnalysis() {
        if (!requireAiKey()) return;
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(it, REQ_PICK_ANALYZE);
        } catch (Exception e) {
            toast("打不开相册：" + e.getMessage());
        }
    }

    private void analyzeNotebookImage(final Bitmap bm) {
        toast("AI 正在看题分析…");
        String system = "你是一个初中错题分析助手。只返回 JSON，不要解释。";
        String user = "请分析这道错题图片，返回 JSON："
                + "{\"subject\":\"数学\",\"topic\":\"知识点名称\","
                + "\"question\":\"题目文字（完整）\","
                + "\"analysis\":\"错误原因和正确解法，分步骤讲\","
                + "\"similar\":[{\"q\":\"同类题1题目\",\"a\":\"答案1\"},"
                + "{\"q\":\"同类题2题目\",\"a\":\"答案2\"},"
                + "{\"q\":\"同类题3题目\",\"a\":\"答案3\"}]}。"
                + "同类题要和原题同知识点、难度相近，题目文字完整，答案简明。"
                + "如果图片里有多道题，只分析最清楚的那一道。";
        AiClient.chatWithImage(this, system, user, bm, new AiClient.Callback() {
            @Override
            public void onOk(String content) {
                try {
                    String json = AiClient.extractJsonObject(content);
                    if (json == null) throw new Exception("no json");
                    JSONObject o = new JSONObject(json);
                    if (o.optString("question", "").isEmpty()) {
                        throw new Exception("empty question");
                    }
                    final String stamp = String.valueOf(System.currentTimeMillis());
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            File img = ImageUtils.saveToNotebook(MainActivity.this,
                                    bm, "错题-" + stamp + ".jpg");
                            File jf = new File(ImageUtils.notebookDir(MainActivity.this),
                                    "错题-" + stamp + ".json");
                            boolean ok = img != null
                                    && ImageUtils.saveTextFile(jf, jsonText(o));
                            final boolean done = ok;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    toast(done ? "已存入错题本（AI 分析完成）"
                                            : "分析完了，但存入错题本失败");
                                    refreshNotebookList();
                                }
                            });
                        }

                        private String jsonText(JSONObject o) {
                            return o.toString();
                        }
                    }).start();
                } catch (Exception e) {
                    toast("AI 没能读懂这道题，换张清楚点的照片再试");
                }
            }

            @Override
            public void onError(String friendlyError) {
                toast(friendlyError);
            }
        });
    }

    /** 读某道错题的 AI 分析 JSON，失败返回 null */
    private JSONObject readNotebookAnalysis(File imgFile) {
        try {
            String name = imgFile.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File jf = new File(imgFile.getParentFile(), base + ".json");
            if (!jf.exists()) return null;
            String text = ImageUtils.readTextFile(jf);
            if (text == null) return null;
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 点错题名：弹窗看题目、知识点、解析、同类题 */
    private void showNotebookDetail(File imgFile) {
        JSONObject o = readNotebookAnalysis(imgFile);
        if (o == null) {
            toast("这道题还没有 AI 分析，点“AI 分析错题”重新分析");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("科目：").append(o.optString("subject", ""))
                .append("　知识点：").append(o.optString("topic", "")).append("\n\n");
        sb.append("题目：\n").append(o.optString("question", "")).append("\n\n");
        sb.append("解析：\n").append(o.optString("analysis", "")).append("\n\n");
        JSONArray sim = o.optJSONArray("similar");
        if (sim != null && sim.length() > 0) {
            sb.append("同类题：\n");
            for (int i = 0; i < sim.length(); i++) {
                JSONObject s = sim.optJSONObject(i);
                if (s == null) continue;
                sb.append(i + 1).append(". ").append(s.optString("q", ""))
                        .append("\n答案：").append(s.optString("a", ""))
                        .append("\n\n");
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(imgFile.getName())
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    // ================= 功能 3：AI 组卷打印 =================
    private void composePaperDialog() {
        if (!requireAiKey()) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        final EditText subjectEdit = makeTextField("科目，如：数学", "数学");
        final EditText countEdit = makeTextField("题数，如：10", "10");
        form.addView(makeCaption("科目"));
        form.addView(subjectEdit);
        form.addView(makeCaption("题数"));
        form.addView(countEdit);
        new AlertDialog.Builder(this)
                .setTitle("AI 组卷打印")
                .setView(form)
                .setMessage("从错题本里按科目挑题（优先用 AI 分析过的错题的同类题），"
                        + "排版成 A4 练习卷，点确定后生成 PDF 发到打印机邮箱。")
                .setPositiveButton("生成并打印", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String subject = subjectEdit.getText().toString().trim();
                        int count = 10;
                        try {
                            count = Integer.parseInt(
                                    countEdit.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        if (count < 1) count = 1;
                        if (count > 30) count = 30;
                        composePaper(subject, count);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void composePaper(final String subject, final int count) {
        toast("正在组卷…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = ImageUtils.notebookDir(MainActivity.this);
                File[] files = dir.listFiles();
                final java.util.ArrayList<String[]> pool =
                        new java.util.ArrayList<String[]>();
                if (files != null) {
                    java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                        @Override
                        public int compare(File a, File b) {
                            return Long.compare(b.lastModified(), a.lastModified());
                        }
                    });
                    for (File f : files) {
                        if (!f.getName().toLowerCase().endsWith(".jpg")) continue;
                        JSONObject o = readNotebookAnalysis(f);
                        if (o == null) continue;
                        if (subject != null && !subject.isEmpty()
                                && !subject.equals(o.optString("subject", ""))) {
                            continue;
                        }
                        JSONArray sim = o.optJSONArray("similar");
                        if (sim == null) continue;
                        for (int i = 0; i < sim.length(); i++) {
                            JSONObject s = sim.optJSONObject(i);
                            if (s == null) continue;
                            String q = s.optString("q", "");
                            String a = s.optString("a", "");
                            if (!q.isEmpty()) pool.add(new String[]{q, a});
                        }
                    }
                }
                if (pool.isEmpty()) {
                    toast("错题本里还没有 AI 分析过的"
                            + (subject == null || subject.isEmpty() ? "" : subject)
                            + "错题，先点“AI 分析错题”");
                    return;
                }
                final int take = Math.min(count, pool.size());
                List<String> lines = new ArrayList<String>();
                lines.add("错题重练卷（" + (subject == null || subject.isEmpty()
                        ? "全部科目" : subject) + "）");
                lines.add("共 " + take + " 道题　请认真作答，写完对照末页答案自查");
                lines.add("");
                final List<String> answers = new ArrayList<String>();
                for (int i = 0; i < take; i++) {
                    String[] qa = pool.get(i);
                    lines.add((i + 1) + ". " + qa[0]);
                    lines.add("答：");
                    lines.add("");
                    lines.add("");
                    answers.add((i + 1) + ". " + qa[1]);
                }
                lines.add("");
                lines.add("—— 参考答案 ——");
                lines.addAll(answers);
                final Uri uri = ImageUtils.saveTextPdfToDownloads(MainActivity.this,
                        "错题重练卷", lines,
                        "重练卷-" + System.currentTimeMillis() + ".pdf");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            toast("生成 PDF 失败");
                            return;
                        }
                        deliverToPrinter(uri, "AI 重练卷（" + take + " 道题）", "application/pdf");
                    }
                });
            }
        }).start();
    }

    // ================= 功能 4：证件照 AI 看图指导 =================
    private void aiIdGuide() {
        if (idPhoto == null || originalIdPhoto == null) {
            toast("先选一张照片");
            return;
        }
        if (!requireAiKey()) return;
        if (idAiResultText != null) {
            idAiResultText.setText("AI 正在看图…");
        }
        toast("AI 正在看图…");
        final Bitmap src = originalIdPhoto;
        String system = "你是一个证件照拍摄指导助手。只返回 JSON，不要解释。";
        String user = "请分析这张证件照。返回 JSON："
                + "{\"face\":{\"x\":..,\"y\":..,\"w\":..,\"h\":..},"
                + "\"bg\":\"背景颜色描述\",\"tilt\":true/false,"
                + "\"suggestion\":\"裁剪和拍摄建议\"}。"
                + "face 是人像在图中的框，坐标 0-1000 归一化；"
                + "tilt 表示人像是否明显倾斜；suggestion 一句话，"
                + "比如“人偏左了，往右移一点再拍”或“背景太杂，换白墙再拍”。";
        AiClient.chatWithImage(this, system, user, src, new AiClient.Callback() {
            @Override
            public void onOk(String content) {
                try {
                    String json = AiClient.extractJsonObject(content);
                    if (json == null) throw new Exception("no json");
                    JSONObject o = new JSONObject(json);
                    JSONObject face = o.optJSONObject("face");
                    String bg = o.optString("bg", "");
                    boolean tilt = o.optBoolean("tilt", false);
                    String suggestion = o.optString("suggestion", "");
                    StringBuilder sb = new StringBuilder();
                    sb.append("AI 指导：背景").append(bg.isEmpty() ? "未识别" : bg)
                            .append("；人像").append(tilt ? "有点倾斜" : "端正");
                    if (!suggestion.isEmpty()) sb.append("。").append(suggestion);
                    sb.append("\nAI 只做指导；裁剪和换底都是本地做的"
                            + "（换底按边缘颜色近似，背景干净才干净）。");
                    final String result = sb.toString();
                    if (face != null) {
                        final float cx = (face.optInt("x", 500)
                                + face.optInt("w", 0) / 2) / 1000f;
                        final float cy = (face.optInt("y", 500)
                                + face.optInt("h", 0) / 2) / 1000f;
                        int[] ratio = idSizeSeg.getSelected() == 0
                                ? new int[]{25, 35} : new int[]{35, 49};
                        final Bitmap cropped = ImageUtils.cropToRatioAround(
                                originalIdPhoto, ratio[0], ratio[1], cx, cy);
                        idPhoto = withIdBackground(cropped);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                idPreview.setImageBitmap(idPhoto);
                                idPreview.setVisibility(View.VISIBLE);
                                if (idPlaceholder != null) {
                                    idPlaceholder.setVisibility(View.GONE);
                                }
                            }
                        });
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (idAiResultText != null) {
                                idAiResultText.setText(result);
                            }
                            toast("AI 指导完成，已按人像位置调整裁剪");
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (idAiResultText != null) {
                                idAiResultText.setText(
                                        "AI 没能看懂这张照片，换张清楚点的再试。"
                                        + "\nAI 只做指导，抠图为本地简易处理。");
                            }
                            toast("AI 没能看懂这张照片");
                        }
                    });
                }
            }

            @Override
            public void onError(String friendlyError) {
                if (idAiResultText != null) {
                    idAiResultText.setText(friendlyError
                            + "\nAI 只做指导，抠图为本地简易处理。");
                }
                toast(friendlyError);
            }
        });
    }

    // ---------- 扫描页 ----------
    private View buildScanScreen() {
        LinearLayout content = screenContent();
        content.addView(makeLargeTitle("内网扫描"));

        content.addView(makeHeader("打印机"));
        LinearLayout discCard = makeCard();
        discCard.addView(makeCardTitle("Epson L3150 / L3151"));
        discoveryText = makeBody("还没发现打印机");
        discCard.addView(discoveryText);
        TextView discNote = makeCaption("APP 会自动发现打印机：先用 NSD（mDNS）找，"
                + "找不到再扫当前 Wi-Fi 网段。IP 变了也能自动跟上，不用手动填。");
        discNote.setPadding(0, dp(6), 0, 0);
        discCard.addView(discNote);
        discCard.addView(makeSecondaryButton("重新发现打印机", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiscovery();
            }
        }));
        content.addView(discCard);

        // 手动 IP：只在自动发现失败时出现，设置里也保留
        manualIpCard = makeCard();
        manualIpCard.addView(makeCardTitle("手动输入打印机 IP"));
        manualIpCard.addView(makeBody("自动发现没找到时才用这个。先确认手机连的是家里 Wi-Fi，"
                + "打印机已开机。"));
        manualIpEdit = makeTextField("如 192.168.5.4", manualIp);
        manualIpCard.addView(manualIpEdit);
        manualIpCard.addView(makePrimaryButton("保存并用这个 IP",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String ip = manualIpEdit.getText().toString().trim();
                        if (ip.isEmpty()) {
                            toast("先填 IP");
                            return;
                        }
                        manualIp = ip;
                        prefs.edit().putString("manualIp", ip).apply();
                        discovered = new PrinterDiscovery.DiscoveredPrinter(
                                ip, 80, "手动输入", "手动");
                        setDiscoveryText("已用手动 IP：" + ip);
                        toast("已用手动 IP " + ip);
                    }
                }));
        manualIpCard.setVisibility(View.GONE);
        content.addView(manualIpCard);

        content.addView(makeHeader("扫描设置"));
        LinearLayout setCard = makeCard();
        TextView colorLabel = makeCaption("颜色");
        setCard.addView(colorLabel);
        scanColorSeg = new SegmentedControl(new String[]{"彩色", "黑白"});
        setCard.addView(scanColorSeg.layout);
        TextView dpiLabel = makeCaption("分辨率");
        dpiLabel.setPadding(0, dp(10), 0, 0);
        setCard.addView(dpiLabel);
        scanDpiSeg = new SegmentedControl(new String[]{"300 dpi", "600 dpi"});
        setCard.addView(scanDpiSeg.layout);
        TextView fmtLabel = makeCaption("保存格式");
        fmtLabel.setPadding(0, dp(10), 0, 0);
        setCard.addView(fmtLabel);
        scanFormatSeg = new SegmentedControl(new String[]{"图片", "PDF"});
        setCard.addView(scanFormatSeg.layout);
        content.addView(setCard);

        LinearLayout scanCard = makeCard();
        scanCard.addView(makePrimaryButton("开始扫描", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doScan();
            }
        }));
        // 扫描连接诊断：只连 1865 端口、收欢迎包、锁定、解锁，不扫描，
        // 每一步结果逐行显示在状态栏里，方便定位锁定失败是打印机状态问题还是协议问题。
        scanCard.addView(makeSecondaryButton("诊断扫描连接", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnoseScan();
            }
        }));
        scanStatusText = makeBody("把纸放在扫描仪玻璃上，点“开始扫描”");
        scanStatusText.setPadding(0, dp(10), 0, 0);
        scanCard.addView(scanStatusText);
        content.addView(scanCard);

        LinearLayout previewCard = makeCard();
        scanPreview = new ImageView(this);
        scanPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scanPreview.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        scanPreview.setLayoutParams(sp);
        previewCard.addView(scanPreview);
        TextView scanHint = makeCaption("扫描结果会显示在这里");
        scanHint.setGravity(Gravity.CENTER);
        scanHint.setPadding(0, dp(8), 0, 0);
        scanHintText = scanHint;
        previewCard.addView(scanHintText);
        // 已扫页面缩略图：长按删除扫错的那一页
        scanThumbsScroll = new HorizontalScrollView(this);
        scanThumbsScroll.setHorizontalScrollBarEnabled(false);
        scanThumbsRow = new LinearLayout(this);
        scanThumbsRow.setOrientation(LinearLayout.HORIZONTAL);
        scanThumbsRow.setPadding(0, dp(10), 0, 0);
        scanThumbsScroll.addView(scanThumbsRow);
        scanThumbsScroll.setVisibility(View.GONE);
        previewCard.addView(scanThumbsScroll);
        content.addView(previewCard);

        LinearLayout doneCard = makeCard();
        doneCard.addView(makeSecondaryButton("继续扫描下一页",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        scanWithCurrentIp();
                    }
                }));
        doneCard.addView(makePrimaryButton("完成并存为 PDF",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finishScanPdf(false);
                    }
                }));
        // 扫多页（比如一张试卷几页）直接打出来，不用先去下载目录找 PDF
        doneCard.addView(makePrimaryButton("完成并存为 PDF 并打印",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finishScanPdf(true);
                    }
                }));
        doneCard.addView(makeSecondaryButton("发给家人",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        shareLastScan();
                    }
                }));
        content.addView(doneCard);

        content.addView(makeCaption("说明：扫描走手机直连打印机内网地址，"
                + "不用代理、不走云端。扫不到时会如实显示未连接，不会假装成功。"));
        return wrapScreen(content);
    }

    // ---------- 自动更新 ----------

    /** 检查更新；silent=true 时失败不打扰（启动时静默检查用）。 */
    private void checkUpdate(final boolean silent) {
        final String custom = prefs.getString("updateUrl", "");
        final java.util.List<String> urls = new java.util.ArrayList<String>();
        if (!custom.isEmpty()) {
            urls.add(custom);
        } else {
            for (String u : UPDATE_URLS) urls.add(u);
        }
        final int my = UpdateChecker.currentVersionCode(this);
        if (!silent) toast("正在检查更新…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateChecker.Info found = null;
                String lastErr = "网络不通";
                for (String url : urls) {
                    try {
                        found = UpdateChecker.fetch(url, my);
                        break;
                    } catch (Exception e) {
                        lastErr = e.getMessage() == null ? "网络错误" : e.getMessage();
                    }
                }
                final UpdateChecker.Info info = found;
                final String errMsg = lastErr;
                try {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (info == null) {
                                if (!silent) {
                                    toast("已是最新版本（" + UpdateChecker.currentVersionName(
                                            MainActivity.this) + "）");
                                }
                                return;
                            }
                            showUpdateDialog(info);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? "网络错误" : e.getMessage();
                    if (!silent) {
                        toast("检查更新失败：" + msg);
                    }
                }
            }
        }).start();
    }

    private void showUpdateDialog(final UpdateChecker.Info info) {
        String notes = info.notes == null || info.notes.isEmpty() ? "" : ("\n\n" + info.notes);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage("当前版本：" + UpdateChecker.currentVersionName(this)
                        + "\n新版本：" + info.versionName + notes
                        + "\n\n点「下载并安装」会下载到手机「下载」目录，然后弹出系统安装界面。")
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        downloadAndInstall(info);
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void downloadAndInstall(final UpdateChecker.Info info) {
        toast("正在下载 " + info.versionName + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri apk = UpdateChecker.download(MainActivity.this, info.url,
                            info.versionName, new UpdateChecker.Progress() {
                                @Override
                                public void onProgress(int done, int total) {
                                    if (total > 0 && done / (512 * 1024)
                                            != (done - 1) / (512 * 1024)) {
                                        Log.i("Update", "下载 " + (done * 100 / total) + "%");
                                    }
                                }
                            });
                    final String msg = UpdateChecker.install(MainActivity.this, apk);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(msg);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? "下载失败" : e.getMessage();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("更新失败：" + msg);
                        }
                    });
                }
            }
        }).start();
    }
    /** 读崩溃日志并显示（可一键复制，方便发出来定位）。 */
    private void showCrashLog() {
        java.io.File f = new java.io.File(getExternalFilesDir(null), "崩溃日志.txt");
        if (!f.exists() || f.length() == 0) {
            toast("没有崩溃记录，说明还没闪退过");
            return;
        }
        String text;
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            text = new String(buf, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) {
            toast("读日志失败：" + e.getMessage());
            return;
        }
        final String body = text.length() > 6000
                ? "…（只显示最后 6000 字）\n" + text.substring(text.length() - 6000)
                : text;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("崩溃日志");
        b.setMessage(body);
        b.setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", body));
                toast("已复制，粘贴发给我即可");
            }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    private void setDiscoveryText(final String s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (discoveryText != null) discoveryText.setText(s);
            }
        });
    }

    private void setScanStatus(final String s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (scanStatusText != null) scanStatusText.setText(s);
            }
        });
    }

    /** 完整发现：NSD -> 子网扫描 -> 失败才显示手动 IP */
    private void runDiscovery() {
        if (discoveryRunning) {
            toast("正在发现打印机，请稍等");
            return;
        }
        discoveryRunning = true;
        setDiscoveryText("正在发现打印机…（先 NSD，找不到再扫网段）");
        if (printStatusText != null) printStatusText.setText("正在发现打印机…");
        PrinterDiscovery.discover(this, new PrinterDiscovery.Callback() {
            @Override
            public void onFound(PrinterDiscovery.DiscoveredPrinter p) {
                discoveryRunning = false;
                discovered = p;
                manualIp = p.ip;
                prefs.edit().putString("lastIp", p.ip).apply();
                setDiscoveryText("已发现：" + p.name + "  " + p.ip + ":" + p.port
                        + "（" + p.source + "）");
                if (printStatusText != null) {
                    printStatusText.setText("已发现打印机：" + p.ip + "（" + p.source + "）");
                }
                if (manualIpCard != null) manualIpCard.setVisibility(View.GONE);
                if (pendingScan) {
                    pendingScan = false;
                    scanWithIp(p.ip);
                }
                if (pendingHomeScan) {
                    pendingHomeScan = false;
                    homeScanWithIp(p.ip);
                }
            }

            @Override
            public void onNotFound(String reason) {
                discoveryRunning = false;
                discovered = null;
                setDiscoveryText("未发现打印机：" + reason);
                if (printStatusText != null) printStatusText.setText("未发现打印机");
                if (manualIpCard != null) manualIpCard.setVisibility(View.VISIBLE);
                pendingScan = false;
                pendingHomeScan = false;
            }
        });
    }

    private boolean pendingScan = false;
    private Uri lastScanUri;
    private String lastScanMime = "image/jpeg";

    private String currentScanIp() {
        if (discovered != null) return discovered.ip;
        if (manualIp != null && !manualIp.isEmpty()) return manualIp;
        return null;
    }

    /** 点“开始扫描”：没发现过就先发现，发现到了自动接着扫 */
    private void doScan() {
        if (discoveryRunning) {
            toast("正在发现打印机，请稍等");
            return;
        }
        if (discovered != null) {
            scanWithIp(discovered.ip);
            return;
        }
        pendingScan = true;
        runDiscovery();
    }

    /**
     * 点“内网直连测一页”：把一张英文测试页直接打到打印机的 9100 端口，
     * 全程不经云端。只在手机和打印机同一 Wi-Fi 时有意义。
     * 9100 没有回执，所以文案只说"发出去了"，不说"打印成功"。
     */
    private void printLanTestPage() {
        final String ip = currentScanIp();
        if (ip == null) {
            toast("先发现打印机，或手动填 IP");
            if (manualIpCard != null) manualIpCard.setVisibility(View.VISIBLE);
            return;
        }
        if (printStatusText != null) {
            printStatusText.setText("正在把测试页直连发到 " + ip + ":9100…");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String r = RawPrintClient.printTestPage(ip);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (printStatusText != null) printStatusText.setText(r);
                            toast("已直连发送，看打印机是否出纸");
                        }
                    });
                } catch (final IOException e) {
                    final String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                    Log.w("Print", "内网直连打印失败", e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (printStatusText != null) {
                                printStatusText.setText("内网直连打印失败：" + msg);
                            }
                            toast("内网直连打印失败");
                        }
                    });
                }
            }
        }).start();
    }

    /** 点“诊断扫描连接”：只做 connect → 欢迎包 → 锁定 → 解锁，不扫描，每一步结果逐行显示 */
    private void diagnoseScan() {
        final String ip = currentScanIp();
        if (ip == null) {
            toast("先发现打印机，或手动填 IP");
            if (manualIpCard != null) manualIpCard.setVisibility(View.VISIBLE);
            return;
        }
        setScanStatus("正在诊断扫描连接…（只连 1865 端口、锁定、解锁，不扫描）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = EsciClient.diagnose(ip);
                setScanStatus(result);
            }
        }).start();
    }

    private void scanWithCurrentIp() {
        String ip = currentScanIp();
        if (ip == null) {
            toast("先发现打印机，或手动填 IP");
            if (manualIpCard != null) manualIpCard.setVisibility(View.VISIBLE);
            return;
        }
        scanWithIp(ip);
    }

    /** 一页扫描结果（带协议名，方便状态栏注明 eSCL 还是 ESC/I） */
    private static class ScanResult {
        final byte[] jpg;
        final String protocol;

        ScanResult(byte[] jpg, String protocol) {
            this.jpg = jpg;
            this.protocol = protocol;
        }
    }

    /**
     * 扫一页：先走 eSCL（别的打印机可能支持）；eSCL 不存在（404）时改走 ESC/I。
     * esclMissing=true 时跳过 eSCL 直接走 ESC/I（用于状态接口已返回 404 的情况）。
     * ESC/I 失败时抛出的 IOException 带 "ESC/I" 前缀，说明走到了哪一步。
     */
    private static ScanResult scanPageAuto(String ip, int dpi, boolean color,
            boolean esclMissing) throws IOException {
        if (!esclMissing) {
            try {
                return new ScanResult(EsclClient.scanPage(ip, dpi, color), "eSCL");
            } catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!m.contains("404")) throw e;
                Log.w("Scan", "eSCL 404，改走 ESC/I", e);
            }
        }
        return new ScanResult(EsciClient.scanPage(ip, dpi, color), "ESC/I");
    }

    private void scanWithIp(final String ip) {
        final boolean color = scanColorSeg.getSelected() == 0;
        final int dpi = scanDpiSeg.getSelected() == 0 ? 300 : 600;
        final boolean wantPdf = scanFormatSeg.getSelected() == 1;
        final int estimate = dpi >= 600 ? 90 : 30;

        // 一眼看上去必须是"正在干活"，不能出现"不支持/失败"这类字眼——
        // eSCL 404 是这台机器的正常情况，不是错误。
        setScanStatus(scannedPages.isEmpty()
                ? "正在扫描…约 " + estimate + " 秒（打印机正在扫玻璃上的纸）"
                : "正在扫第 " + (scannedPages.size() + 1) + " 页…约 " + estimate + " 秒");
        startScanSlowHint(dpi);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 这台机器没有 eSCL（实测 /eSCL/ScannerStatus 返回 404）：记住结论，
                // 以后直接走 ESC/I，既省一次试探，也不会再闪出让人误解的提示。
                boolean esclMissing = esclUnsupported;
                if (!esclMissing) {
                    try {
                        String state = EsclClient.getStatus(ip);
                        if (state != null && !"Idle".equalsIgnoreCase(state)) {
                            setScanStatus("打印机正在忙（" + state + "），等它空下来再扫…");
                        }
                    } catch (final Exception e) {
                        Log.w("Scan", "eSCL 状态查询失败", e);
                        if (e.getMessage() != null && e.getMessage().contains("404")) {
                            esclMissing = true;
                            markEsclUnsupported();
                            setScanStatus("正在扫描…（这台机器用爱普生 ESC/I 协议，"
                                    + "约 " + estimate + " 秒）");
                        }
                        // 其他原因（比如超时）就不改文案了，继续扫
                    }
                }
                try {
                    ScanResult result = scanPageAuto(ip, dpi, color, esclMissing);
                    final String protocol = result.protocol;
                    if ("ESC/I".equals(protocol)) markEsclUnsupported();
                    final Bitmap bm = EsclClient.decodeJpeg(result.jpg);
                    if (bm == null) {
                        stopScanSlowHint();
                        setScanStatus("扫描失败：打印机返回的不是图片，请重试");
                        toast("扫描失败：返回的不是图片");
                        return;
                    }
                    scannedPages.add(bm);
                    if (!wantPdf) {
                        final Uri uri = ImageUtils.saveJpegToPictures(
                                MainActivity.this, bm,
                                "扫描-" + System.currentTimeMillis() + ".jpg");
                        lastScanUri = uri;
                        lastScanMime = "image/jpeg";
                    }
                    stopScanSlowHint();
                    final int pages = scannedPages.size();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            scanPreview.setImageBitmap(bm);
                            setScanStatus("已扫 " + pages + " 页（" + protocol + "，" + dpi + "dpi）"
                                    + (wantPdf ? "，点“完成并存为 PDF”保存"
                                    : "，已存到相册 Pictures/宅家打印机"));
                            if (scanHintText != null) {
                                scanHintText.setText(pages == 1
                                        ? "第 1 页 · " + dpi + "dpi · 可继续扫下一页"
                                        : "共 " + pages + " 页 · " + dpi + "dpi");
                            }
                            updateScanThumbs();
                            toast("扫描成功");
                        }
                    });
                } catch (final Exception e) {
                    stopScanSlowHint();
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.startsWith("ESC/I")) {
                        // ESC/I 也失败了：显示走到哪一步、打印机回了什么，并给 Smart Panel 兜底
                        setScanStatus("扫描失败：" + msg.replaceFirst("^ESC/I\\s*", "")
                                + "\n若反复失败，可先用 Epson Smart Panel 扫好后在 APP 里导入。");
                    } else if (msg.contains("404")) {
                        setScanStatus("扫描失败：打印机拒绝了扫描请求（HTTP 404）。"
                                + "可能是不支持 eSCL，或扫描设置不被接受。"
                                + "请重试一次；还不行就用 Epson Smart Panel 扫好后导入 APP 处理。"
                                + "原因：" + msg);
                    } else {
                        setScanStatus("扫描失败：未连接或超时。请确认手机和打印机在同一 Wi-Fi，"
                                + "IP 是 " + ip + "。原因：" + msg);
                    }
                    toast("扫描失败：" + msg);
                }
            }
        }).start();
    }

    /** 记住"这台机器没有 eSCL"，下次扫描直接走 ESC/I。 */
    private void markEsclUnsupported() {
        if (esclUnsupported) return;
        esclUnsupported = true;
        prefs.edit().putBoolean("esclUnsupported", true).apply();
    }

    /**
     * 扫描比较慢（300dpi 约 25 秒、600dpi 约 95 秒），12 秒后补一句进度，
     * 免得界面一直停在"正在扫描"让用户以为卡死了。
     */
    private void startScanSlowHint(final int dpi) {
        stopScanSlowHint();
        scanSlowHint = new Runnable() {
            @Override
            public void run() {
                setScanStatus("还在扫…（" + (dpi >= 600 ? "600dpi 大概要 90 秒" : "300dpi 大概 30 秒")
                        + "）纸张正在被扫，别掀盖板、别关 APP");
            }
        };
        mainHandler.postDelayed(scanSlowHint, 12000);
    }

    private void stopScanSlowHint() {
        if (scanSlowHint != null) {
            mainHandler.removeCallbacks(scanSlowHint);
            scanSlowHint = null;
        }
    }

    /**
     * 把已扫的页合成一个 PDF。
     *
     * @param printAfter true 表示存完直接送到打印机（多页就是一次任务、一次出纸）
     */
    private void finishScanPdf(final boolean printAfter) {
        if (scannedPages.isEmpty()) {
            toast("还没扫到内容，先点“开始扫描”");
            return;
        }
        final int pageCount = scannedPages.size();
        toast(printAfter ? "正在生成 PDF 并发送…" : "正在生成 PDF…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Bitmap> pages = new ArrayList<Bitmap>(scannedPages);
                final Uri uri = ImageUtils.savePdfToDownloads(MainActivity.this,
                        pages, "扫描-" + System.currentTimeMillis() + ".pdf");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            toast("生成 PDF 失败");
                            return;
                        }
                        lastScanUri = uri;
                        lastScanMime = "application/pdf";
                        setScanStatus("PDF 已保存，共 " + pageCount + " 页");
                        if (printAfter) {
                            deliverToPrinter(uri, "扫描件 " + pageCount + " 页",
                                    "application/pdf");
                        } else {
                            toast("已存到 Download/宅家打印机");
                        }
                    }
                });
            }
        }).start();
    }

    private void shareLastScan() {
        if (lastScanUri == null) {
            toast("还没可分享的扫描件，先扫一页");
            return;
        }
        ImageUtils.shareFile(this, lastScanUri, lastScanMime, "发给家人");
    }

    // ---------- 工具页：证件照 + 错题本 ----------
    private View buildToolsScreen() {
        LinearLayout content = screenContent();
        content.addView(makeLargeTitle("工具"));

        content.addView(makeHeader("证件照"));
        LinearLayout idCard = makeCard();
        idCard.addView(makeCardTitle("证件照"));
        idCard.addView(makeBody("按选好的尺寸裁剪，换底是本地自动抠图（分离人像再合成底色，"
                + "不上传照片）。白墙、纯色背景效果最好；复杂背景留残边时，"
                + "点下面的「手指涂背景」补一下即可。"));
        idSizeSeg = new SegmentedControl(new String[]{"1寸", "2寸"});
        idSizeSeg.setOnSelect(new OnSelect() {
            @Override
            public void onSelect(int index) {
                recropIdPhoto();
            }
        });
        idCard.addView(idSizeSeg.layout);
        TextView idBgLabel = makeCaption("背景");
        idBgLabel.setPadding(0, dp(10), 0, 0);
        idCard.addView(idBgLabel);
        idBgSeg = new SegmentedControl(new String[]{"原图", "白底", "蓝底", "红底"});
        idBgSeg.setOnSelect(new OnSelect() {
            @Override
            public void onSelect(int index) {
                idBgIndex = index;
                recropIdPhoto();
            }
        });
        idCard.addView(idBgSeg.layout);
        LinearLayout idRow = new LinearLayout(this);
        idRow.setOrientation(LinearLayout.HORIZONTAL);
        Button idCamBtn = makeSecondaryButton("拍照", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takeIdPhoto();
            }
        });
        Button idPickBtn = makeSecondaryButton("从相册选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.setType("image/*");
                it.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(it, REQ_PICK_ID);
                } catch (Exception e) {
                    toast("打不开相册：" + e.getMessage());
                }
            }
        });
        LinearLayout.LayoutParams irp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        irp.rightMargin = dp(6);
        idCamBtn.setLayoutParams(irp);
        LinearLayout.LayoutParams irp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        irp2.leftMargin = dp(6);
        idPickBtn.setLayoutParams(irp2);
        idRow.addView(idCamBtn);
        idRow.addView(idPickBtn);
        idCard.addView(idRow);
        idPreview = new ImageView(this);
        idPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        idPreview.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        ip.topMargin = dp(10);
        idPreview.setLayoutParams(ip);
        idPreview.setVisibility(View.GONE);   // 没选照片时不占那 240dp，免得一片大白块
        idCard.addView(idPreview);
        idPlaceholder = makeCaption("还没选照片：点上面「拍照」或「从相册选」");
        idPlaceholder.setPadding(0, dp(10), 0, 0);
        idCard.addView(idPlaceholder);
        // 宠物/不居中/背景杂乱时：先框住头，再在框内抠图
        idCard.addView(makeSecondaryButton("框选头部再抠图（宠物、背景杂乱时用）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showRoiDialog();
                    }
                }));
        // 自动换底只是草稿：漏了的地方用手指涂，支持撤销
        final Button brushBtn = makeSecondaryButton("手指涂背景（自动换底没盖住的地方）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        idBrushMode = !idBrushMode;
                        ((Button) v).setText(idBrushMode
                                ? "涂背景中：点预览拖动，再按一次退出"
                                : "手指涂背景（自动换底没盖住的地方）");
                    }
                });
        idCard.addView(brushBtn);
        idCard.addView(makeSecondaryButton("撤销上一笔", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoCropStep();
            }
        }));
        idPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (!idBrushMode || idPhoto == null) return false;
                boolean down = e.getAction() == android.view.MotionEvent.ACTION_DOWN;
                if (down || e.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                    if (down || Math.hypot(e.getX() - idBrushLastX,
                            e.getY() - idBrushLastY) > 8) {
                        try {
                            applyIdBrush(e.getX(), e.getY(), down);
                        } catch (Throwable t) {
                            Log.w("IdPhoto", "涂背景失败", t);
                            toast("这一步失败了，可以撤销或重新选照片");
                        }
                    }
                }
                return true;
            }
        });
        idCard.addView(makePrimaryButton("AI 看图指导", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aiIdGuide();
            }
        }));
        idAiResultText = makeCaption(
                "AI 看图指导：AI 看照片后告诉你人像位置、背景和裁剪建议，"
                + "并按人像位置调整裁剪框。AI 只做指导；"
                + "裁剪和换底在本地完成（换底按边缘颜色近似，不做逐像素抠图）。");
        idAiResultText.setPadding(0, dp(8), 0, 0);
        idCard.addView(idAiResultText);
        idCard.addView(makePrimaryButton("导出证件照 JPG", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportIdJpg();
            }
        }));
        idCard.addView(makeSecondaryButton("排版 A4 PDF（8 张）并打印",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        exportIdSheetAndPrint();
                    }
                }));
        content.addView(idCard);

        content.addView(makeHeader("错题本"));
        LinearLayout nbCard = makeCard();
        nbCard.addView(makeCardTitle("错题本"));
        nbCard.addView(makeBody("在“去手写”页处理完试卷点“存入错题本”，"
                + "这里勾选几道题，就能拼成一张重练卷再印。"));
        notebookList = new LinearLayout(this);
        notebookList.setOrientation(LinearLayout.VERTICAL);
        nbCard.addView(notebookList);
        nbCard.addView(makePrimaryButton("AI 分析错题", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImageForAnalysis();
            }
        }));
        nbCard.addView(makePrimaryButton("AI 组卷打印", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                composePaperDialog();
            }
        }));
        nbCard.addView(makeSecondaryButton("刷新列表", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshNotebookList();
            }
        }));
        nbCard.addView(makePrimaryButton("导出重练卷 PDF 并打印",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        exportNotebookPdf();
                    }
                }));
        content.addView(nbCard);
        return wrapScreen(content);
    }

    private void takeIdPhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "证件照-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/宅家打印机");
            cameraUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri == null) {
                toast("相机启动失败");
                return;
            }
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            startActivityForResult(it, REQ_CAMERA_ID);
        } catch (Exception e) {
            toast("相机启动失败：" + e.getMessage());
        }
    }

    private void setIdBitmap(Bitmap bm) {
        if (bm == null) return;
        originalIdPhoto = bm;
        idRoiSource = null;
        recropIdPhoto();
    }

    private void recropIdPhoto() {
        final Bitmap base = idRoiSource != null ? idRoiSource : originalIdPhoto;
        if (base == null) return;
        final Bitmap src = base;
        final int sizeIdx = idSizeSeg == null ? 0 : idSizeSeg.getSelected();
        // 裁剪 + 换底都可能比较重（几百万像素的扩散填色），放后台线程做
        new Thread(new Runnable() {
            @Override
            public void run() {
                int[] ratio = sizeIdx == 0 ? new int[]{25, 35} : new int[]{35, 49};
                Bitmap cropped = ImageUtils.cropToRatio(src, ratio[0], ratio[1]);
                if (cropped == null) {
                    toast("裁剪失败，换张照片试试");
                    return;
                }
                final Bitmap ready = withIdBackground(cropped);
                idPhoto = ready;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (idPreview != null) {
                            idPreview.setImageBitmap(ready);
                            idPreview.setVisibility(View.VISIBLE);
                        }
                        if (idPlaceholder != null) idPlaceholder.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    /**
     * 手指涂背景：把点到的位置向四周扩散成当前底色。
     * 只涂"和这个点颜色接近"的连通区域，所以不会糊掉头发和衣服。
     */
    /**
     * 框选头部/主体：拖一个框，然后在框内做自动抠图。
     * 这样"背景"只取框边缘——猫躺在花枕头上也能把猫头抠出来。
     */
    private void showRoiDialog() {
        final Bitmap base = originalIdPhoto;
        if (base == null) {
            toast("先拍照或从相册选一张照片");
            return;
        }
        final RoiPickerView picker = new RoiPickerView(this, base,
                new RoiPickerView.Callback() {
                    @Override
                    public void onRect(Rect rect) {
                        // 拖完松手什么都不做，等用户点"用这个框抠图"
                    }
                });
        picker.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(picker);
        TextView tip = makeCaption("拖动框住要保留的部分（比如猫头），框外一律当背景。");
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("框选头部/主体")
                .setView(box)
                .setPositiveButton("用这个框抠图", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Rect r = picker.getRect();
                        if (r == null || r.width() < 16 || r.height() < 16) {
                            toast("框太小了，拖一个大一点的框");
                            return;
                        }
                        int margin = Math.max(4, Math.min(r.width(), r.height()) / 12);
                        int left = Math.max(0, r.left - margin);
                        int top = Math.max(0, r.top - margin);
                        int right = Math.min(base.getWidth(), r.right + margin);
                        int bottom = Math.min(base.getHeight(), r.bottom + margin);
                        Bitmap roi = Bitmap.createBitmap(base, left, top,
                                Math.max(16, right - left), Math.max(16, bottom - top));
                        idRoiSource = roi;
                        idUndoStack.clear();
                        idUndoSize.clear();
                        idBrushMode = false;
                        recropIdPhoto();
                        toast("已按框抠图；可继续选 1寸/2寸 或换底色");
                    }
                })
                .setNeutralButton("恢复整张照片", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        idRoiSource = null;
                        recropIdPhoto();
                        toast("已恢复用整张照片");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    private void applyIdBrush(float vx, float vy, boolean newStroke) {
        if (idPhoto == null || idPreview == null) return;
        int vw = idPreview.getWidth(), vh = idPreview.getHeight();
        if (vw == 0 || vh == 0) return;
        float s = Math.min((float) vw / idPhoto.getWidth(), (float) vh / idPhoto.getHeight());
        float dw = idPhoto.getWidth() * s, dh = idPhoto.getHeight() * s;
        float ox = (vw - dw) / 2f, oy = (vh - dh) / 2f;
        int bx = (int) ((vx - ox) / s);
        int by = (int) ((vy - oy) / s);
        if (bx < 0 || by < 0 || bx >= idPhoto.getWidth() || by >= idPhoto.getHeight()) return;
        idBrushLastX = vx;
        idBrushLastY = vy;

        Bitmap mutable = idPhoto.isMutable() ? idPhoto
                : idPhoto.copy(Bitmap.Config.ARGB_8888, true);
        if (mutable == null) return;
        int w = mutable.getWidth(), h = mutable.getHeight();
        int[] px = new int[w * h];
        mutable.getPixels(px, 0, w, 0, 0, w, h);
        if (newStroke) pushIdUndo(px, w, h);
        int color = ID_BG_COLORS[Math.max(1, Math.min(ID_BG_COLORS.length - 1, idBgIndex))];
        BgReplace.fillFromPoint(px, w, h, bx, by, color, 40);
        mutable.setPixels(px, 0, w, 0, 0, w, h);
        idPhoto = mutable;
        idPreview.setImageBitmap(mutable);
    }

    private void pushIdUndo(int[] px, int w, int h) {
        if (idUndoStack.size() >= 6) {
            idUndoStack.remove(0);
            idUndoSize.remove(0);
        }
        idUndoStack.add(px.clone());
        idUndoSize.add(new int[]{w, h});
    }

    /** 撤销上一笔涂色。 */
    private void undoCropStep() {
        if (idUndoStack.isEmpty()) {
            toast("没有可撤销的操作了");
            return;
        }
        int[] px = idUndoStack.remove(idUndoStack.size() - 1);
        int[] wh = idUndoSize.remove(idUndoSize.size() - 1);
        Bitmap bm = Bitmap.createBitmap(wh[0], wh[1], Bitmap.Config.ARGB_8888);
        bm.setPixels(px, 0, wh[0], 0, 0, wh[0], wh[1]);
        idPhoto = bm;
        if (idPreview != null) idPreview.setImageBitmap(bm);
        toast("已撤销");
    }
    /** 按当前选择的底色做本地换底；选"原图"就直接返回裁剪结果。 */
    private Bitmap withIdBackground(Bitmap cropped) {
        if (cropped == null) return null;
        int idx = idBgIndex;
        if (idx <= 0 || idx >= ID_BG_COLORS.length) return cropped;
        idUndoStack.clear();
        idUndoSize.clear();
        // 主力：U²-Net 分割模型（人/猫/狗都认，全自动）；模型不可用时退回本地颜色模型
        Bitmap replaced = OrtCut.replaceBackground(this, cropped, ID_BG_COLORS[idx]);
        if (replaced == null) {
            replaced = PortraitCut.replaceBackground(cropped, ID_BG_COLORS[idx]);
        }
        if (replaced == null) {
            toast("自动抠图没成功，先用原背景；可点「手指涂背景」手动补");
            return cropped;
        }
        return replaced;
    }

    private void exportIdJpg() {
        if (idPhoto == null) {
            toast("先选一张照片");
            return;
        }
        Uri uri = ImageUtils.saveJpegToPictures(this, idPhoto,
                "证件照-" + System.currentTimeMillis() + ".jpg");
        toast(uri == null ? "导出失败" : "已存到相册 Pictures/宅家打印机");
    }

    private void exportIdSheetAndPrint() {
        if (idPhoto == null) {
            toast("先选一张照片");
            return;
        }
        toast("正在排版 A4…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri = ImageUtils.saveIdPhotoSheetPdf(MainActivity.this,
                        idPhoto, "证件照-" + System.currentTimeMillis() + ".pdf");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            toast("生成 PDF 失败");
                            return;
                        }
                        deliverToPrinter(uri, "证件照（A4 排版 8 张）", "application/pdf");
                    }
                });
            }
        }).start();
    }

    private void refreshNotebookList() {
        if (notebookList == null) return;
        notebookList.removeAllViews();
        File dir = ImageUtils.notebookDir(this);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            TextView empty = makeCaption("错题本是空的，去“去手写”页处理完点“存入错题本”，"
                    + "或点“AI 分析错题”拍一道错题让 AI 分析。");
            notebookList.addView(empty);
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        boolean any = false;
        for (final File f : files) {
            if (!f.getName().toLowerCase().endsWith(".jpg")) continue;
            any = true;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            cb.setTag(f);
            cb.setChecked(true);
            TextView name = new TextView(this);
            String label = f.getName();
            JSONObject analysis = readNotebookAnalysis(f);
            if (analysis != null) {
                String tag = analysis.optString("subject", "");
                String topic = analysis.optString("topic", "");
                if (!tag.isEmpty() || !topic.isEmpty()) {
                    label += "  ·  " + tag + (tag.isEmpty() || topic.isEmpty()
                            ? "" : "·") + topic + "（已 AI 分析，点开看解析）";
                }
            }
            name.setText(label);
            name.setTextColor(C_TEXT2);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(np);
            name.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showNotebookDetail(f);
                }
            });
            Button del = new Button(this);
            del.setText("删除");
            del.setAllCaps(false);
            del.setTextColor(C_RED);
            del.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14));
            del.setBackgroundColor(Color.TRANSPARENT);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 连同 AI 分析 JSON 一起删
                    String n = f.getName();
                    int dot = n.lastIndexOf('.');
                    String base = dot > 0 ? n.substring(0, dot) : n;
                    File jf = new File(f.getParentFile(), base + ".json");
                    if (jf.exists()) jf.delete();
                    if (f.delete()) {
                        toast("已删除");
                        refreshNotebookList();
                    }
                }
            });
            row.addView(cb);
            row.addView(name);
            row.addView(del);
            notebookList.addView(row);
        }
        if (!any) {
            TextView empty = makeCaption("错题本是空的，去“去手写”页处理完点“存入错题本”，"
                    + "或点“AI 分析错题”拍一道错题让 AI 分析。");
            notebookList.addView(empty);
        }
    }

    private void exportNotebookPdf() {
        if (notebookList == null) return;
        final List<File> chosen = new ArrayList<File>();
        for (int i = 0; i < notebookList.getChildCount(); i++) {
            View row = notebookList.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            CheckBox cb = (CheckBox) ((LinearLayout) row).getChildAt(0);
            if (cb.isChecked()) chosen.add((File) cb.getTag());
        }
        if (chosen.isEmpty()) {
            toast("先在错题本里勾选要打印的题");
            return;
        }
        toast("正在拼重练卷…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Bitmap> pages = new ArrayList<Bitmap>();
                for (File f : chosen) {
                    Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bm != null) pages.add(bm);
                }
                if (pages.isEmpty()) {
                    toast("读错题本失败");
                    return;
                }
                final Uri uri = ImageUtils.savePdfToDownloads(MainActivity.this,
                        pages, "重练卷-" + System.currentTimeMillis() + ".pdf");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri == null) {
                            toast("生成 PDF 失败");
                            return;
                        }
                        deliverToPrinter(uri, "重练卷（" + chosen.size() + " 道题）",
                                "application/pdf");
                    }
                });
            }
        }).start();
    }

    // ---------- 问 AI 页：学习助手 ----------
    private View buildAiChatScreen() {
        LinearLayout content = screenContent();
        content.addView(makeLargeTitle("问 AI"));

        content.addView(makeHeader("学习助手"));
        LinearLayout introCard = makeCard();
        introCard.addView(makeCardTitle("学习助手"));
        introCard.addView(makeBody("孩子做题卡住了，拍题发图或直接打字提问，"
                + "AI 给解题思路，不直接给答案。支持纯文字提问。"));
        content.addView(introCard);

        LinearLayout chatCard = makeCard();
        chatScroll = new ScrollView(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(520));
        chatScroll.setLayoutParams(sp);
        // 嵌套 ScrollView：聊天区在整页 ScrollView 里面，手指在聊天区里滑动时，
        // 不让外层整页抢走手势，否则内层聊天区根本滑不动、翻不到前面的问答。
        chatScroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false; // 仍由 ScrollView 自己处理滑动
            }
        });
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        chatScroll.addView(chatList);
        chatCard.addView(chatScroll);
        TextView chatHint = makeCaption("先在“设置 → AI 设置”里填好 API 密钥再用。");
        chatHint.setPadding(0, dp(8), 0, 0);
        chatCard.addView(chatHint);
        content.addView(chatCard);

        LinearLayout photoCard = makeCard();
        photoCard.addView(makeCardTitle("拍题提问"));
        LinearLayout photoRow = new LinearLayout(this);
        photoRow.setOrientation(LinearLayout.HORIZONTAL);
        Button chatCamBtn = makeSecondaryButton("拍照", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takeChatPhoto();
            }
        });
        Button chatPickBtn = makeSecondaryButton("从相册选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.setType("image/*");
                it.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(it, REQ_PICK_CHAT);
                } catch (Exception e) {
                    toast("打不开相册：" + e.getMessage());
                }
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        cp.rightMargin = dp(6);
        chatCamBtn.setLayoutParams(cp);
        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        cp2.leftMargin = dp(6);
        chatPickBtn.setLayoutParams(cp2);
        photoRow.addView(chatCamBtn);
        photoRow.addView(chatPickBtn);
        photoCard.addView(photoRow);
        chatPhotoPreview = new ImageView(this);
        chatPhotoPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chatPhotoPreview.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        pp.topMargin = dp(10);
        chatPhotoPreview.setLayoutParams(pp);
        chatPhotoPreview.setVisibility(View.GONE);
        photoCard.addView(chatPhotoPreview);
        content.addView(photoCard);

        LinearLayout inputCard = makeCard();
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        chatInput = makeTextField("输入问题，如：这道题怎么做？", "");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.rightMargin = dp(8);
        chatInput.setLayoutParams(ilp);
        Button sendBtn = makePrimaryButton("发送", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendChat();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                dp(72), dp(50));
        slp.topMargin = dp(6);
        sendBtn.setLayoutParams(slp);
        inputRow.addView(chatInput);
        inputRow.addView(sendBtn);
        inputCard.addView(inputRow);
        content.addView(inputCard);
        return wrapScreen(content);
    }

    private void takeChatPhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "问AI-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/宅家打印机");
            cameraUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri == null) {
                toast("相机启动失败");
                return;
            }
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            startActivityForResult(it, REQ_CAMERA_CHAT);
        } catch (Exception e) {
            toast("相机启动失败：" + e.getMessage());
        }
    }

    private void setChatPhoto(Bitmap bm) {
        if (bm == null) return;
        chatPhoto = bm;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                chatPhotoPreview.setImageBitmap(chatPhoto);
                chatPhotoPreview.setVisibility(View.VISIBLE);
                toast("已附上图片，输入问题后点发送");
            }
        });
    }

    /** 聊天气泡：我的用浅蓝底，AI 的用白底 */
    private void addChatBubble(String who, String text, boolean isUser) {
        // AI 回答先去掉 Markdown 标记再显示（只改显示，不改发给模型的内容）
        final String display = isUser ? text : stripMarkdownForDisplay(text);
        final TextView t = new TextView(this);
        t.setTextColor(C_TEXT2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        t.setLineSpacing(dp(4), 1.0f);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        // AI 回答超过 400 字才折叠：先只显示前 300 字，展开状态记在气泡自己身上
        final boolean collapsible = !isUser && display.length() > 400;
        final View bubbleView;
        if (collapsible) {
            final String full = who + display;
            final String preview = who + display.substring(0, 300) + "……";
            t.setText(preview);
            final TextView toggle = new TextView(this);
            toggle.setText("展开全文 ▾");
            toggle.setTextColor(C_BLUE);
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13));
            toggle.setPadding(dp(12), dp(2), dp(12), dp(10));
            toggle.setTag(Boolean.FALSE); // false = 未展开
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean expanded = Boolean.TRUE.equals(toggle.getTag());
                    if (expanded) {
                        t.setText(preview);
                        toggle.setText("展开全文 ▾");
                        toggle.setTag(Boolean.FALSE);
                    } else {
                        t.setText(full);
                        toggle.setText("收起 ▴");
                        toggle.setTag(Boolean.TRUE);
                    }
                }
            });
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setBackground(roundBg(Color.WHITE, 12));
            wrap.setElevation(dp(1));
            wrap.addView(t);
            wrap.addView(toggle);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            lp.bottomMargin = dp(2);
            wrap.setLayoutParams(lp);
            chatList.addView(wrap);
            bubbleView = wrap;
        } else {
            t.setText(who + display);
            t.setBackground(roundBg(isUser ? 0xFFE3F0FF : Color.WHITE, 12));
            if (!isUser) t.setElevation(dp(1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            lp.bottomMargin = dp(2);
            t.setLayoutParams(lp);
            chatList.addView(t);
            bubbleView = t;
        }
        if (isUser) {
            scrollChatToBottom();
        } else {
            // AI 回答从头开始看：滚到新气泡顶部，不跳到底
            scrollChatToView(bubbleView);
        }
    }

    /** 滚到指定气泡的顶部（AI 回答用：从头开始看，不跳到底）。 */
    private void scrollChatToView(final View v) {
        if (chatScroll == null || v == null) return;
        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                chatScroll.scrollTo(0, v.getTop());
            }
        });
    }

    /**
     * 去掉回答里的 Markdown 标记再显示（** 粗体、## 标题、列表符、行内代码等）。
     * 只处理显示文本，不改发给模型的内容；单个 * 不动，避免误伤数学里的乘号。
     */
    private static String stripMarkdownForDisplay(String s) {
        if (s == null) return "";
        String out = s;
        out = out.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");   // ## 标题
        out = out.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");     // **粗体**
        out = out.replaceAll("__([^_]+)__", "$1");              // __粗体__
        out = out.replaceAll("`([^`]*)`", "$1");                // 行内代码
        out = out.replaceAll("(?m)^\\s{0,3}```.*$", "");        // 代码块围栏
        out = out.replaceAll("(?m)^\\s{0,3}>\\s?", "");         // 引用 >
        out = out.replaceAll("(?m)^\\s{0,3}[-*+]\\s+", "• ");   // 无序列表
        out = out.replaceAll("(?m)^\\s{0,3}\\d+[.)]\\s+", "");  // 有序列表序号
        out = out.replaceAll("(?m)^\\s{0,3}---+\\s*$", "");     // 分隔线
        out = out.replace("**", "").replace("__", "");          // 残留的标记
        return out;
    }

    private void scrollChatToBottom() {
        if (chatScroll == null) return;
        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                chatScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void sendChat() {
        if (chatBusy) {
            toast("AI 正在回答，请稍等");
            return;
        }
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty() && chatPhoto == null) {
            toast("先输入问题或附上一张题图");
            return;
        }
        if (!requireAiKey()) return;
        final Bitmap photo = chatPhoto;
        final String userText = text.isEmpty() ? "请讲讲这道题怎么做" : text;
        chatHistory.add(new AiClient.Msg("user", userText, photo));
        addChatBubble("我：", text.isEmpty() ? "（发了一张题图）" : text, true);
        chatInput.setText("");
        chatPhoto = null;
        if (chatPhotoPreview != null) {
            chatPhotoPreview.setImageBitmap(null);
            chatPhotoPreview.setVisibility(View.GONE);
        }
        final TextView thinking = new TextView(this);
        thinking.setText("AI 正在想…");
        thinking.setTextColor(C_GRAY);
        thinking.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15));
        thinking.setPadding(dp(12), dp(10), dp(12), dp(10));
        chatList.addView(thinking);
        scrollChatToBottom();
        chatBusy = true;
        // 只带最近 12 条历史，控制请求大小
        List<AiClient.Msg> recent = new ArrayList<AiClient.Msg>();
        int start = Math.max(0, chatHistory.size() - 12);
        for (int i = start; i < chatHistory.size(); i++) recent.add(chatHistory.get(i));
        List<AiClient.Msg> request = new ArrayList<AiClient.Msg>();
        request.add(new AiClient.Msg("system",
                "你是一个耐心的小学/初中学习助手，面向初一学生和家长。"
                + "用中文回答。只给解题思路和引导式讲解：先指出这道题考什么，"
                + "再一步步引导孩子自己想出来，不要直接给最终答案；"
                + "如果孩子实在不会，再给关键步骤。语气亲切、简明。"));
        request.addAll(recent);
        AiClient.chat(this, request, new AiClient.Callback() {
            @Override
            public void onOk(String content) {
                chatBusy = false;
                chatList.removeView(thinking);
                chatHistory.add(new AiClient.Msg("assistant", content));
                if (chatHistory.size() > 24) {
                    chatHistory.remove(0);
                }
                addChatBubble("AI：", content, false);
            }

            @Override
            public void onError(String friendlyError) {
                chatBusy = false;
                chatList.removeView(thinking);
                addChatBubble("AI 出错了：", friendlyError, false);
            }
        });
    }

    // ---------- 设置页 ----------
    private View buildSettingsScreen() {
        LinearLayout content = screenContent();
        content.addView(makeLargeTitle("设置"));

        content.addView(makeHeader("打印机"));
        LinearLayout printerCard = makeCard();
        printerCard.addView(makeCardTitle("打印机邮箱"));
        printerCard.addView(makeCaption("远程打印就发到这个邮箱（预填好的，不用改）。"));
        // 邮箱不在界面上明文显示（脱敏）：输入框留空表示不修改，要换就整条粘进去
        emailEdit = makeTextField("留空表示不修改：" + QrScan.mask(printerEmail), "");
        printerCard.addView(emailEdit);
        TextView ipLabel = makeCaption("内网 IP（手动备用，自动发现失败时才用）");
        ipLabel.setPadding(0, dp(12), 0, 0);
        printerCard.addView(ipLabel);
        settingsIpEdit = makeTextField("如 192.168.5.4", manualIp);
        printerCard.addView(settingsIpEdit);
        printerCard.addView(makePrimaryButton("保存设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String em = emailEdit.getText().toString().trim();
                String ip = settingsIpEdit.getText().toString().trim();
                if (!em.isEmpty()) printerEmail = em;
                if (!ip.isEmpty()) manualIp = ip;
                prefs.edit().putString("email", printerEmail)
                        .putString("manualIp", manualIp).apply();
                toast("已保存");
            }
        }));
        // 扫打印机卡片上的二维码（内容就是这台机器的 Email Print 地址），自动绑定，不用手抄
        printerCard.addView(makeSecondaryButton("扫二维码绑定打印机",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        qrBindDialog();
                    }
                }));
        printerEmailLabel = makeCaption("当前打印机邮箱：" + QrScan.mask(printerEmail));
        printerEmailLabel.setPadding(0, dp(8), 0, 0);
        printerCard.addView(printerEmailLabel);
        content.addView(printerCard);

        // ---- 打印发信：APP 自己发邮件到打印机，不依赖任何邮箱 App ----
        content.addView(makeHeader("打印发信（可直接发，不用邮箱 App）"));
        LinearLayout mailCard = makeCard();
        mailCard.addView(makeCardTitle("发件邮箱"));
        boolean builtIn = MailConfig.defaults() != null;
        mailCard.addView(makeCaption(builtIn
                ? "这一版已经内置了发件邮箱，装上就能直接打印，不用填任何东西。"
                + "下面的输入框只有想换邮箱时才用。"
                : "填一个你自己的邮箱（QQ / 163 / 126 都行），密码那栏填"
                + "「SMTP 授权码」（在邮箱网页版的设置里生成，不是登录密码）。"
                + "填一次，之后点打印就直接发到打印机，不再跳邮箱 App。"));
        final EditText smtpUserEdit = makeTextField("发件邮箱，如 1234@qq.com",
                prefs.getString("smtpUser", ""));
        mailCard.addView(smtpUserEdit);
        final EditText smtpPassEdit = makeTextField("SMTP 授权码", "");
        smtpPassEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mailCard.addView(smtpPassEdit);
        final EditText smtpHostEdit = makeTextField("SMTP 服务器（留空自动识别）",
                prefs.getString("smtpHost", ""));
        mailCard.addView(smtpHostEdit);
        final EditText scanMailToEdit = makeTextField(
                "扫描件发到（留空＝发到上面的发件邮箱自己）",
                prefs.getString("scanMailTo", ""));
        mailCard.addView(scanMailToEdit);
        mailCard.addView(makePrimaryButton("保存发信设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = smtpUserEdit.getText().toString().trim();
                String p = smtpPassEdit.getText().toString().trim();
                String h = smtpHostEdit.getText().toString().trim();
                prefs.edit().putString("scanMailTo", scanMailToEdit.getText().toString().trim()).apply();
                if (u.isEmpty()) {
                    toast("先填发件邮箱");
                    return;
                }
                prefs.edit().putString("smtpUser", u)
                        .putString("smtpHost", h)
                        .putString("smtpFrom", u)
                        .apply();
                if (!p.isEmpty()) prefs.edit().putString("smtpPass", p).apply();
                toast("已保存");
            }
        }));
        mailCard.addView(makeSecondaryButton("测试发信连接（不发信）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testSmtp();
                    }
                }));
        mailCard.addView(makeSecondaryButton("复制发信配置（微信发给家里人）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyMailConfig();
                    }
                }));
        mailCard.addView(makeSecondaryButton("粘贴导入配置",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        importMailConfig();
                    }
                }));
        content.addView(mailCard);

        content.addView(makeHeader("AI 设置"));
        LinearLayout aiCard = makeCard();
        aiCard.addView(makeCardTitle("AI 设置（DeepSeek V4.1 Flash）"));
        aiCard.addView(makeCaption("AI 功能走你自己的 API 地址，密钥只存在这台手机上，"
                + "不会发给别人，也不会写进安装包。没填密钥时，点 AI 功能会来这里提示你填。"));
        TextView aiUrlLabel = makeCaption("API 地址");
        aiUrlLabel.setPadding(0, dp(12), 0, 0);
        aiCard.addView(aiUrlLabel);
        final EditText aiUrlEdit = makeTextField("如 https://api.deepseek.com/v1",
                AiClient.getBaseUrl(this));
        aiCard.addView(aiUrlEdit);
        TextView aiModelLabel = makeCaption("模型");
        aiModelLabel.setPadding(0, dp(12), 0, 0);
        aiCard.addView(aiModelLabel);
        final EditText aiModelEdit = makeTextField("如 deepseek-v4.1-flash",
                AiClient.getModel(this));
        aiCard.addView(aiModelEdit);
        TextView aiKeyLabel = makeCaption("API 密钥（只存本机）");
        aiKeyLabel.setPadding(0, dp(12), 0, 0);
        aiCard.addView(aiKeyLabel);
        final EditText aiKeyEdit = makeTextField("粘贴 API 密钥",
                AiClient.getKey(this));
        aiKeyEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        aiCard.addView(aiKeyEdit);
        aiCard.addView(makePrimaryButton("保存 AI 设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = aiUrlEdit.getText().toString().trim();
                String model = aiModelEdit.getText().toString().trim();
                String key = aiKeyEdit.getText().toString().trim();
                prefs.edit()
                        .putString("aiBaseUrl",
                                url.isEmpty() ? AiClient.DEFAULT_BASE_URL : url)
                        .putString("aiModel",
                                model.isEmpty() ? AiClient.DEFAULT_MODEL : model)
                        .putString("aiKey", key)
                        .apply();
                toast(key.isEmpty() ? "AI 设置已保存（密钥为空，AI 功能暂不可用）"
                        : "AI 设置已保存");
            }
        }));
        final TextView aiTestResult = makeCaption("");
        aiTestResult.setPadding(0, dp(8), 0, 0);
        aiCard.addView(makePrimaryButton("测试连接", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 先把当前框里的值存下来再测，免得用户填完忘了点保存
                String url = aiUrlEdit.getText().toString().trim();
                String model = aiModelEdit.getText().toString().trim();
                String key = aiKeyEdit.getText().toString().trim();
                prefs.edit()
                        .putString("aiBaseUrl",
                                url.isEmpty() ? AiClient.DEFAULT_BASE_URL : url)
                        .putString("aiModel",
                                model.isEmpty() ? AiClient.DEFAULT_MODEL : model)
                        .putString("aiKey", key)
                        .apply();
                aiTestResult.setText("正在测试连接，请稍等…");
                AiClient.testConnection(MainActivity.this, new AiClient.Callback() {
                    @Override
                    public void onOk(String content) {
                        aiTestResult.setText("✓ " + content);
                        toast("连接成功");
                    }

                    @Override
                    public void onError(String friendlyError) {
                        aiTestResult.setText("✗ " + friendlyError);
                        toast("连接失败：" + friendlyError);
                    }
                });
            }
        }));
        aiCard.addView(aiTestResult);
        content.addView(aiCard);

        content.addView(makeHeader("家里接收模式"));
        LinearLayout homeCard = makeCard();
        homeCard.addView(makeCardTitle("家里那台手机用这个模式"));
        homeCard.addView(makeBody("远程扫描没有官方云端触发：家里手机必须在同一 Wi-Fi 下，"
                + "才能点扫描。进这个模式后，界面只剩大字和两个大按钮，"
                + "老人放好纸、点一下就能扫，扫完点“发给家人”发过去。"));
        homeCard.addView(makePrimaryButton("进入家里接收模式",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        enterHomeMode();
                    }
                }));
        content.addView(homeCard);

        content.addView(makeHeader("版本与更新"));
        LinearLayout updCard = makeCard();
        updCard.addView(makeCardTitle("宅家打印机 " + UpdateChecker.currentVersionName(this)));
        updCard.addView(makeCaption("安装包 versionCode "
                + UpdateChecker.currentVersionCode(this)
                + "。点「检查更新」会去下面这个地址读 update.json，有新版本就自动下载并弹出安装界面。"));
        final EditText updUrlEdit = makeTextField("更新地址（update.json）",
                prefs.getString("updateUrl", UPDATE_URL_DEFAULT));
        updCard.addView(updUrlEdit);
        updCard.addView(makePrimaryButton("检查更新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = updUrlEdit.getText().toString().trim();
                if (u.isEmpty()) u = UPDATE_URL_DEFAULT;
                prefs.edit().putString("updateUrl", u).apply();
                checkUpdate(false);
            }
        }));
        updCard.addView(makeSecondaryButton("打开「安装未知应用」设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } else {
                        toast("当前系统不需要这个设置");
                    }
                } catch (Exception e) {
                    toast("打不开设置：" + e.getMessage());
                }
            }
        }));
        content.addView(updCard);

        content.addView(makeHeader("诊断"));
        LinearLayout diagCard = makeCard();
        diagCard.addView(makeCardTitle("闪退日志"));
        diagCard.addView(makeCaption("APP 万一闪退，堆栈会自动写进手机里的一个日志文件。"
                + "点下面按钮复制出来发给我，我照着改，不用再猜。"));
        diagCard.addView(makeSecondaryButton("查看崩溃日志",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCrashLog();
                    }
                }));
        content.addView(diagCard);

        content.addView(makeHeader("关于"));
        LinearLayout aboutCard = makeCard();
        aboutCard.addView(makeBody("宅家打印机 0.9.24\n零广告 · 不用代理 · 不依赖 Gmail / Google 服务\n"
                + "扫描走手机直连打印机内网，打印走邮件分享。\n"
                + "AI 功能：AI 识别手写、错题分析、AI 组卷、证件照指导、问 AI、拍照质检"
                + "（需在上方 AI 设置里填 API 密钥）。"));
        TextView aboutNote = makeCaption("实话：Epson 官方没有互联网远程触发扫描的功能，"
                + "所以“远程点一下、家里自动扫”做不到；能做到的是家里手机在同一 Wi-Fi 下点扫描，"
                + "扫完分享给不在家的家人。APP 里不会假装能远程触发。");
        aboutNote.setPadding(0, dp(8), 0, 0);
        aboutCard.addView(aboutNote);
        content.addView(aboutCard);
        return wrapScreen(content);
    }

    // ---------- 家里接收模式（大字大按钮） ----------
    private void enterHomeMode() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(C_BG);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        overlay.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText("家里接收模式");
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(34));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        overlay.addView(title);

        TextView big = new TextView(this);
        big.setText("请把纸放在\n扫描仪玻璃上（盖好盖板）");
        big.setTextColor(C_TEXT2);
        big.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24));
        big.setGravity(Gravity.CENTER);
        big.setLineSpacing(dp(8), 1.0f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(24);
        bp.bottomMargin = dp(24);
        big.setLayoutParams(bp);
        overlay.addView(big);

        // 扫描预览：让放纸的人当场看到扫得对不对
        homePreview = new ImageView(this);
        homePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        homePreview.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        hp.bottomMargin = dp(10);
        homePreview.setLayoutParams(hp);
        homePreview.setVisibility(View.GONE);
        overlay.addView(homePreview);

        homeThumbsScroll = new HorizontalScrollView(this);
        homeThumbsScroll.setHorizontalScrollBarEnabled(false);
        homeThumbsRow = new LinearLayout(this);
        homeThumbsRow.setOrientation(LinearLayout.HORIZONTAL);
        homeThumbsRow.setGravity(Gravity.CENTER_HORIZONTAL);
        homeThumbsScroll.addView(homeThumbsRow);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        htp.bottomMargin = dp(10);
        homeThumbsScroll.setLayoutParams(htp);
        homeThumbsScroll.setVisibility(View.GONE);
        overlay.addView(homeThumbsScroll);

        Button scanBtn = makePrimaryButton("纸已放好，点这里扫描并发送",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        homeScan();
                    }
                });
        scanBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        scanBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        overlay.addView(scanBtn);

        homeStatusText = makeBody("");
        homeStatusText.setGravity(Gravity.CENTER);
        homeStatusText.setPadding(0, dp(16), 0, 0);
        overlay.addView(homeStatusText);

        homeSendPdfBtn = makePrimaryButton("发出去（合成一个 PDF）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendHomeScansAsPdf();
                    }
                });
        homeSendPdfBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        LinearLayout.LayoutParams spb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        spb.topMargin = dp(12);
        homeSendPdfBtn.setLayoutParams(spb);
        homeSendPdfBtn.setVisibility(View.GONE);
        overlay.addView(homeSendPdfBtn);

        homeShareBtn = makeSecondaryButton("发给家人", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLastScan();
            }
        });
        homeShareBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20));
        homeShareBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        homeShareBtn.setVisibility(View.GONE);
        overlay.addView(homeShareBtn);

        Button exitBtn = makeTextButton("退出接收模式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitHomeMode();
            }
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.topMargin = dp(24);
        ep.gravity = Gravity.CENTER_HORIZONTAL;
        exitBtn.setLayoutParams(ep);
        overlay.addView(exitBtn);

        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        content.addView(overlay);
        homeModeOverlay = overlay;
    }

    private void exitHomeMode() {
        if (homeModeOverlay != null) {
            ViewGroup parent = (ViewGroup) homeModeOverlay.getParent();
            if (parent != null) parent.removeView(homeModeOverlay);
            homeModeOverlay = null;
        }
    }

    private void homeScan() {
        if (discoveryRunning) {
            toast("正在发现打印机，请稍等");
            return;
        }
        if (discovered == null) {
            pendingHomeScan = true;
            setHomeStatus("正在发现打印机…");
            runDiscovery();
            return;
        }
        homeScanWithIp(discovered.ip);
    }

    private void setHomeStatus(final String s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (homeStatusText != null) homeStatusText.setText(s);
            }
        });
    }

    private void homeScanWithIp(final String ip) {
        setHomeStatus("正在扫描…约 30 秒（别掀盖板）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 家里接收模式固定 300dpi 彩色；已知没有 eSCL 时直接走 ESC/I
                    ScanResult result = scanPageAuto(ip, 300, true, esclUnsupported);
                    if ("ESC/I".equals(result.protocol)) markEsclUnsupported();
                    final Bitmap bm = EsclClient.decodeJpeg(result.jpg);
                    if (bm == null) {
                        setHomeStatus("扫描失败：返回的不是图片");
                        return;
                    }
                    scannedPages.add(bm);
                    final Uri uri = ImageUtils.saveJpegToPictures(
                            MainActivity.this, bm,
                            "扫描-" + System.currentTimeMillis() + ".jpg");
                    lastScanUri = uri;
                    lastScanMime = "image/jpeg";
                    // 多页累积：扫完不立刻发，等老人点"发出去"时合成一个 PDF 一次发
                    final int pages = scannedPages.size();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setHomeStatus("已扫 " + pages + " 页。\n"
                                    + "还有纸就换一张再按上面的按钮；\n"
                                    + "扫完了就按下面「发出去」，只发一封邮件。");
                            if (homeSendPdfBtn != null) {
                                homeSendPdfBtn.setVisibility(View.VISIBLE);
                                homeSendPdfBtn.setText("发出去（" + pages + " 页合成一个 PDF）");
                            }
                            if (homeShareBtn != null) {
                                homeShareBtn.setVisibility(View.VISIBLE);
                            }
                            scanPreview.setImageBitmap(bm);
                            // 家里模式的预览：最后一页大图 + 全部页缩略图
                            if (homePreview != null) {
                                homePreview.setImageBitmap(bm);
                                homePreview.setVisibility(View.VISIBLE);
                            }
                            updateHomeThumbs();
                        }
                    });
                } catch (final Exception e) {
                    // 已连上打印机、走到协议步骤才失败（如 ESC/I 锁定失败），
                    // 直接显示真实原因；只有确实连不上时才提示未连接。
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if (m.startsWith("ESC/I")) {
                        setHomeStatus(m);
                    } else {
                        setHomeStatus("扫描失败：未连接。请确认手机和打印机在同一 Wi-Fi。\n"
                                + m);
                    }
                }
            }
        }).start();
    }
    /**
     * 把家里接收模式已扫的页合成一个 PDF，一次性发到"主人邮箱"。
     * 老人只需：放纸 → 按扫描（可多页）→ 按「发出去」。
     */
    private void sendHomeScansAsPdf() {
        if (scannedPages.isEmpty()) {
            toast("还没扫到内容，先按上面的按钮扫描");
            return;
        }
        final int pages = scannedPages.size();
        setHomeStatus("正在合成 PDF 并发送（" + pages + " 页）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Bitmap> list = new ArrayList<Bitmap>(scannedPages);
                final Uri pdf = ImageUtils.savePdfToDownloads(MainActivity.this, list,
                        "扫描-" + System.currentTimeMillis() + ".pdf");
                if (pdf == null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setHomeStatus("合成 PDF 失败，可以点“发给家人”先发出去");
                        }
                    });
                    return;
                }
                byte[] data = readUriBytes(pdf);
                final String msg;
                if (data == null) {
                    msg = "PDF 生成成功但读不出来，可以点“发给家人”";
                } else {
                    msg = mailBytesToOwner(data, "application/pdf",
                            "scan-" + System.currentTimeMillis() + ".pdf", pages);
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (msg.startsWith("已发出")) {
                            scannedPages.clear();
                            setHomeStatus(msg + "\n\n可以接着扫下一批了。");
                            if (homeSendPdfBtn != null) homeSendPdfBtn.setVisibility(View.GONE);
                            if (homePreview != null) {
                                homePreview.setImageBitmap(null);
                                homePreview.setVisibility(View.GONE);
                            }
                            updateHomeThumbs();
                        } else {
                            setHomeStatus(msg);
                        }
                    }
                });
            }
        }).start();
    }

    /** 通用：确认删除某一项（扫描页/家里模式/打印已选都用它）。 */
    private void confirmDeletePage(String label, final Runnable onOk) {
        new AlertDialog.Builder(this)
                .setTitle("删掉" + label + "？")
                .setMessage("只从这次的任务里移除，不会删手机相册里的原图。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onOk.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 扫描页：刷新已扫缩略图（长按删单页）。 */
    private void updateScanThumbs() {
        if (scanThumbsRow == null || scanThumbsScroll == null) return;
        scanThumbsRow.removeAllViews();
        if (scannedPages.isEmpty()) {
            scanThumbsScroll.setVisibility(View.GONE);
            return;
        }
        scanThumbsScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < scannedPages.size(); i++) {
            final Bitmap page = scannedPages.get(i);
            final int idx = i;
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), dp(112));
            lp.rightMargin = dp(8);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(roundBg(C_SEG_BG, 8));
            iv.setImageBitmap(page);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBitmapDialog("第 " + (idx + 1) + " 页", page);
                }
            });
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDeletePage("第 " + (idx + 1) + " 页", new Runnable() {
                        @Override
                        public void run() {
                            removeScannedPage(idx, null);
                        }
                    });
                    return true;
                }
            });
            scanThumbsRow.addView(iv);
        }
    }

    /** 从已扫页面里删掉一页（扫描页与家里模式共用）。 */
    private void removeScannedPage(int index, final Runnable after) {
        if (index < 0 || index >= scannedPages.size()) return;
        scannedPages.remove(index);
        int n = scannedPages.size();
        if (scanHintText != null) {
            scanHintText.setText(n == 0 ? "扫描结果会显示在这里"
                    : "共 " + n + " 页（长按缩略图可删除）");
        }
        if (scanPreview != null) {
            if (n > 0) scanPreview.setImageBitmap(scannedPages.get(n - 1));
            else scanPreview.setImageBitmap(null);
        }
        updateScanThumbs();
        if (homeThumbsRow != null) updateHomeThumbs();
        if (homePreview != null) {
            if (n > 0) {
                homePreview.setImageBitmap(scannedPages.get(n - 1));
            } else {
                homePreview.setImageBitmap(null);
                homePreview.setVisibility(View.GONE);
            }
        }
        if (homeSendPdfBtn != null) {
            if (n > 0) {
                homeSendPdfBtn.setVisibility(View.VISIBLE);
                homeSendPdfBtn.setText("发出去（" + n + " 页合成一个 PDF）");
            } else {
                homeSendPdfBtn.setVisibility(View.GONE);
            }
        }
        toast("已删除，还剩 " + n + " 页");
        if (after != null) after.run();
    }
    /** 家里模式：把已扫的每一页做成小缩略图，点一下看大图。 */
    private void updateHomeThumbs() {
        if (homeThumbsRow == null || homeThumbsScroll == null) return;
        homeThumbsRow.removeAllViews();
        if (scannedPages.isEmpty()) {
            homeThumbsScroll.setVisibility(View.GONE);
            return;
        }
        homeThumbsScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < scannedPages.size(); i++) {
            final Bitmap page = scannedPages.get(i);
            final int idx = i + 1;
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), dp(112));
            lp.rightMargin = dp(8);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(roundBg(C_SEG_BG, 8));
            iv.setImageBitmap(page);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBitmapDialog("第 " + idx + " 页", page);
                }
            });
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDeletePage("第 " + idx + " 页", new Runnable() {
                        @Override
                        public void run() {
                            removeScannedPage(idx - 1, null);
                        }
                    });
                    return true;
                }
            });
            homeThumbsRow.addView(iv);
        }
    }

    /** 弹窗看一张图（家里模式预览用）。 */
    private void showBitmapDialog(String title, Bitmap bm) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bm);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }
    /** 把一段字节作为附件发到"主人邮箱"；返回给老人看的一句话。 */
    private String mailBytesToOwner(byte[] data, String mime, String fileName, int pages) {
        MailSender.Config cfg = smtpConfig();
        if (cfg == null) {
            return "还没配置发信邮箱（设置 → 打印发信）。可以先点“发给家人”。";
        }
        String to = prefs.getString("scanMailTo", "").trim();
        if (to.isEmpty()) to = cfg.user == null ? "" : cfg.user;
        if (to.isEmpty()) {
            return "收件邮箱没填（设置 → 扫描件发到）。";
        }
        try {
            MailSender.Config c2 = new MailSender.Config();
            c2.host = cfg.host;
            c2.port = cfg.port;
            c2.ssl = cfg.ssl;
            c2.user = cfg.user;
            c2.pass = cfg.pass;
            c2.from = cfg.from;
            c2.to = to;
            MailSender.send(c2, "扫描件 " + nowTime() + "（" + pages + " 页）",
                    "家里接收模式自动发送（宅家打印机 APP），共 " + pages + " 页。",
                    fileName, mime, data);
            recordPrint("家里接收模式 " + pages + " 页", "已发到 " + QrScan.mask(to));
            return "已发出：" + QrScan.mask(to) + "\n（" + pages + " 页合成一个 PDF）";
        } catch (Exception e) {
            Log.w("Scan", "扫描件发信失败", e);
            String m = e.getMessage() == null ? "未知错误" : e.getMessage();
            return "发信失败：" + m + "\n可以先点“发给家人”。";
        }
    }

    /** 把 content:// 的 PDF 读成字节，供发信使用。 */
    private byte[] readUriBytes(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w("Scan", "读 PDF 失败", e);
            return null;
        }
    }
    // ---------- 结果回调 ----------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_QR_CAMERA) {
            if (cameraUri != null) handleQrUri(cameraUri);
            return;
        }
        if (requestCode == REQ_QR_PICK && data != null && data.getData() != null) {
            handleQrUri(data.getData());
            return;
        }
        if (requestCode == REQ_PICK_PRINT && data != null) {
            List<Uri> uris = new ArrayList<Uri>();
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            }
            if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) {
                onPrintPicked(uris);
                return;
            }
        }
        if ((requestCode == REQ_PICK_PAPER || requestCode == REQ_PICK_ID)
                && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final boolean isPaper = requestCode == REQ_PICK_PAPER;
            toast("正在读图…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Bitmap bm = ImageUtils.loadBitmapFromUri(
                            MainActivity.this, uri, 2000);
                    if (bm == null) {
                        toast("读图失败");
                        return;
                    }
                    if (isPaper) setPaperBitmap(bm);
                    else setIdBitmap(bm);
                    toast("已载入");
                }
            }).start();
            return;
        }
        if ((requestCode == REQ_CAMERA_PAPER || requestCode == REQ_CAMERA_ID)
                && cameraUri != null) {
            final Uri uri = cameraUri;
            final boolean isPaper = requestCode == REQ_CAMERA_PAPER;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Bitmap bm = ImageUtils.loadBitmapFromUri(
                            MainActivity.this, uri, 2000);
                    if (bm == null) {
                        toast("读图失败");
                        return;
                    }
                    if (isPaper) setPaperBitmap(bm);
                    else setIdBitmap(bm);
                    toast("已载入");
                }
            }).start();
            return;
        }
        // AI 分析错题：选图后先质检提醒（有问题也继续分析），再发给 AI
        if (requestCode == REQ_PICK_ANALYZE && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            toast("正在读图…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Bitmap bm = ImageUtils.loadBitmapFromUri(
                            MainActivity.this, uri, 2000);
                    if (bm == null) {
                        toast("读图失败");
                        return;
                    }
                    runPaperQualityCheck(bm);
                    analyzeNotebookImage(bm);
                }
            }).start();
            return;
        }
        // 问 AI：选图 / 拍照后附到提问里
        if ((requestCode == REQ_PICK_CHAT && data != null && data.getData() != null)
                || (requestCode == REQ_CAMERA_CHAT && cameraUri != null)) {
            final Uri uri = requestCode == REQ_PICK_CHAT ? data.getData() : cameraUri;
            toast("正在读图…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Bitmap bm = ImageUtils.loadBitmapFromUri(
                            MainActivity.this, uri, 2000);
                    if (bm == null) {
                        toast("读图失败");
                        return;
                    }
                    setChatPhoto(bm);
                }
            }).start();
        }
    }
}

