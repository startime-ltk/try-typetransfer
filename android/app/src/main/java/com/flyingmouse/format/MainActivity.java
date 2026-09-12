package com.flyingmouse.format;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.flyingmouse.format.convert.ConversionEngine;
import com.flyingmouse.format.util.FormatKit;
import com.flyingmouse.format.util.OutputSaver;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 主界面：选择文件（SAF）→ 选目标格式 → 转换 → 结果保存到 下载/FlyingMouseFormat。
 * 兼容外部"用…打开"与分享接收。
 */
public class MainActivity extends Activity {

    /** 外部 Uri 类型解析/入队过程的日志标签（便于 adb logcat 取证） */
    private static final String TAG = "FMFormat";

    private static final int REQ_PICK = 1001;
    private static final int REQ_PERM = 1002;
    /** 多文件另存：ACTION_OPEN_DOCUMENT_TREE 一次授权一个目录 */
    private static final int REQ_SAVE_DIR = 1003;
    /** 单文件另存：ACTION_CREATE_DOCUMENT 逐个选目标文件名/位置（不受目录授权限制） */
    private static final int REQ_SAVE_ONE = 1004;

    /** 待另存清单的持久化，避免 Activity 被系统回收后静默丢失 */
    private static final String SAVE_PREF = "fm_save_pending";
    private static final String KEY_URIS = "uris";
    private static final String KEY_NAMES = "names";
    private static final String KEY_TREE_TRIED = "tree_tried";
    private static final String KEY_OK = "ok_names";
    private static final String KEY_FAILED = "failed";

    /** 队列条目的转换状态 */
    private static final int ST_PENDING = 0;
    private static final int ST_RUNNING = 1;
    private static final int ST_DONE = 2;
    private static final int ST_FAILED = 3;
    private static final int ST_CANCELLED = 4;

    private final List<FileItem> queue = new ArrayList<>();
    private final List<FormatKit.Target> targets = new ArrayList<>();
    private String selectedTargetId;
    private String srcExtOfTargets; // 生成 target chips 时使用的源扩展名

    private TextView statusText;
    private TextView queueTitle;
    private TextView targetTitle;
    private ImageView mascot;
    private LinearLayout targetContainer;
    private ListView listView;
    private Button btnConvert;

    private boolean converting;
    /** 批量转换的取消请求标志（当前文件处理完后生效） */
    private volatile boolean cancelRequested;
    /** 待另存到用户所选位置的产物（点击另存为后暂存，选择器返回时消费） */
    private List<Uri> pendingSaveUris;
    private List<String> pendingSaveNames;
    /** 另存流程是否进行中 */
    private boolean saveFlowActive;
    /** 是否已尝试过"目录授权"（多文件首次尝试；失败后降级为逐张另存） */
    private boolean treeTried;
    /** 本轮另存的成功清单与失败清单（用于结束汇总） */
    private List<String> saveOkNames;
    private List<String> saveFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        queueTitle = findViewById(R.id.queueTitle);
        targetTitle = findViewById(R.id.targetTitle);
        mascot = findViewById(R.id.mascot);
        targetContainer = findViewById(R.id.targetContainer);
        listView = findViewById(R.id.listQueue);
        btnConvert = findViewById(R.id.btnConvert);
        Button btnPick = findViewById(R.id.btnPick);
        Button btnClear = findViewById(R.id.btnClear);
        TextView btnFormats = findViewById(R.id.btnFormats);

        mascot.setImageResource(R.drawable.mouse_idle);

        btnPick.setOnClickListener(v -> {
            if (converting) return;
            setMascot(R.drawable.mouse_upload, "选择要转换的文件…");
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PICK);
        });

        btnClear.setOnClickListener(v -> {
            if (converting) return;
            queue.clear();
            refreshQueueUi();
            refreshTargets(null);
            setStatus("点击下方按钮选择要转换的文件", R.drawable.mouse_idle);
        });

        btnConvert.setOnClickListener(v -> startConvert());

        btnFormats.setOnClickListener(v -> {
            if (converting) return;
            startActivity(new Intent(this, FormatsActivity.class));
        });

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "incoming action=" + action + " data=" + intent.getData() + " type=" + intent.getType()
                + " clipCount=" + (intent.getClipData() == null ? 0 : intent.getClipData().getItemCount()));
        if (Intent.ACTION_VIEW.equals(action)) {
            // 「用…打开」：常规数据位是 getData()；个别来源只塞 ClipData，此处回退读 ClipData，
            // 并把 Uri 列表交给与 SEND 分支相同的入队入口 receivedUris()，保证两条路径行为一致
            List<Uri> uris = new ArrayList<>();
            if (intent.getData() != null) uris.add(intent.getData());
            addClipDataUris(intent, uris);
            receivedUris(uris, intent.getType());
        } else if (Intent.ACTION_SEND.equals(action)) {
            // 常规数据位是 EXTRA_STREAM；部分发送方只塞 ClipData，此时回退读 ClipData
            List<Uri> uris = new ArrayList<>();
            Uri single = streamUri(intent);
            if (single != null) uris.add(single);
            addClipDataUris(intent, uris);
            receivedUris(uris, intent.getType());
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // 常规数据位是 ClipData；部分发送方只塞 EXTRA_STREAM(ArrayList)，此时回退读 EXTRA_STREAM
            List<Uri> uris = new ArrayList<>();
            addClipDataUris(intent, uris);
            for (Uri u : streamUriList(intent)) {
                if (u != null && !uris.contains(u)) uris.add(u);
            }
            receivedUris(uris, intent.getType());
        } else {
            Log.i(TAG, "忽略的非接收型 action=" + action);
        }
    }

    /** 把外部（分享/用…打开）进来的 Uri 依次入队；内部已对不支持的类型/重复项给出提示 */
    private void receivedUris(List<Uri> uris, String typeHint) {
        if (uris.isEmpty()) {
            Log.w(TAG, "接收型 intent 未携带任何 Uri，type=" + typeHint);
            toast("未接收到文件，请在应用内选择文件");
            return;
        }
        int added = 0;
        for (Uri u : uris) {
            if (addIncomingUri(u, typeHint)) added++;
        }
        Log.i(TAG, "外部 Uri 处理完毕 收到=" + uris.size() + " 入队=" + added);
        if (added > 1) {
            toast("已接收 " + added + " 个文件");
        } else if (added == 1) {
            toast("已接收文件：可继续选择或直接转换");
        }
    }

    /** 读取 ClipData 中的全部 Uri（已存在的不重复添加） */
    private void addClipDataUris(Intent intent, List<Uri> out) {
        ClipData cd = intent.getClipData();
        if (cd == null) return;
        for (int i = 0; i < cd.getItemCount(); i++) {
            Uri u = cd.getItemAt(i).getUri();
            if (u != null && !out.contains(u)) out.add(u);
        }
    }

    /** EXTRA_STREAM 里的单个 Uri（ACTION_SEND 常见形态），取不到返回 null */
    @SuppressWarnings("deprecation")
    private static Uri streamUri(Intent intent) {
        try {
            android.os.Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) return (Uri) p;
        } catch (Exception ignored) {
            // 个别发送方类型不符，忽略并按“没有”处理
        }
        return null;
    }

    /** EXTRA_STREAM 里的 Uri 列表（ACTION_SEND_MULTIPLE 常见形态），取不到返回空列表 */
    @SuppressWarnings("deprecation")
    private static List<Uri> streamUriList(Intent intent) {
        try {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) return list;
        } catch (Exception ignored) {
            // 个别发送方类型不符，忽略并按“没有”处理
        }
        Uri single = streamUri(intent);
        return single == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(single);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            int added = 0;
            String typeHint = data.getType();
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (addIncomingUri(u, typeHint)) added++;
                }
            } else if (data.getData() != null) {
                if (addIncomingUri(data.getData(), typeHint)) added = 1;
            }
            if (added > 0) {
                toast("已添加 " + added + " 个文件");
                setMascot(R.drawable.mouse_idle, queue.isEmpty() ? getString(R.string.empty_hint) : "选择完毕，挑一个目标格式吧");
            }
        } else if (requestCode == REQ_PICK && resultCode == RESULT_CANCELED) {
            if (queue.isEmpty()) setMascot(R.drawable.mouse_idle, getString(R.string.empty_hint));
        } else if (requestCode == REQ_SAVE_DIR) {
            // 目录授权结果：已授权 → 逐个写入该目录；未授权 → 明确提示并降级为逐张另存
            loadSavePendingIfNeeded();
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                boolean persisted = false;
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    persisted = true;
                } catch (Exception e) {
                    // 个别文件提供方不支持持久化授权，本次写入仍可继续，但必须留痕
                    Log.w(TAG, "另存为 目录授权已获得但持久化失败（本次仍尝试写入）：tree=" + tree
                            + " errType=" + e.getClass().getSimpleName() + " errMsg=" + e.getMessage());
                }
                Log.i(TAG, "另存为 目录授权结果=成功 tree=" + tree + " 持久化=" + (persisted ? "是" : "否")
                        + " flags=0x" + Integer.toHexString(data.getFlags()));
                saveToTree(tree, persisted);
            } else {
                Log.w(TAG, "另存为 未获得目录授权：resultCode=" + resultCode
                        + " data=" + (data == null ? "null" : String.valueOf(data.getData()))
                        + "；targetSdk>=30 时系统禁止授权 Download 根目录/存储根目录"
                        + "（DocumentsUI 显示 Can't use this folder 且 USE THIS FOLDER 置灰），降级为逐张另存");
                toast("该目录无法授权（系统隐私限制），改为逐个另存");
                nextSaveStep();
            }
        } else if (requestCode == REQ_SAVE_ONE) {
            // 单文件另存结果：OK → 把源产物字节写入用户新建的目标文档
            loadSavePendingIfNeeded();
            if (!saveFlowActive || pendingSaveUris == null || pendingSaveUris.isEmpty()) {
                Log.w(TAG, "另存为 收到单文件另存结果但待另存清单为空（可能已被消费或流程已结束）resultCode=" + resultCode);
            } else if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                copyOnePending(data.getData());
            } else {
                Log.w(TAG, "另存为 用户取消/未选择目标文件：resultCode=" + resultCode
                        + " name=" + pendingSaveNames.get(0));
                saveFlowActive = false;
                toast("已取消另存");
                finishSave(true);
            }
        }
    }

    /**
     * 取显示名：优先 OpenableColumns.DISPLAY_NAME；查不到（无读权限 / 提供方不支持）时退化为
     * Uri 末段路径，仍取不到则用 "file"。退化情形会写日志，便于定位"文件名变成纯数字"的问题。
     */
    private String queryName(Uri uri) {
        String name = null;
        try {
            String[] cols = {android.provider.OpenableColumns.DISPLAY_NAME};
            android.database.Cursor c = getContentResolver().query(uri, cols, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception e) {
            // 常见于未持有对应媒体读权限，或调用方未把该 Uri 的读权限转授给本应用
            Log.w(TAG, "DISPLAY_NAME 查询失败，退化为末段路径 uri=" + uri + " err=" + e);
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
            Log.w(TAG, "DISPLAY_NAME 为空，使用末段路径 name=" + name + " uri=" + uri);
        }
        if (name == null || name.trim().isEmpty()) name = "file";
        return name;
    }

    /**
     * 外部 Uri 入队统一入口：先按文件名的扩展名判类型；扩展名不可用（典型如 content:// 名称退化成
     * 纯数字 "468"，扩展名为空）时，改用 Intent 的 type 或 ContentResolver 的 MIME 推断扩展名并合成
     * 显示名，避免被误判为「不支持的类型」而静默丢弃。
     */
    private boolean addIncomingUri(Uri uri, String typeHint) {
        if (uri == null) return false;
        String name = queryName(uri);
        String ext = FormatKit.extOf(name);
        String from = "文件名";
        if (!FormatKit.isSupportedExt(ext)) {
            String mime = (typeHint == null || typeHint.trim().isEmpty()) ? typeOf(uri) : typeHint;
            String mimeExt = FormatKit.extOfMime(mime);
            Log.i(TAG, "扩展名不可用，尝试 MIME 兜底 uri=" + uri + " name=" + name + " ext=\"" + ext
                    + "\" typeHint=" + typeHint + " type=" + mime + " mimeExt=" + mimeExt);
            if (mimeExt.isEmpty() || !FormatKit.isSupportedExt(mimeExt)) {
                return reject(uri, name, ext, mime);
            }
            name = synthesizeName(name, mimeExt);
            ext = mimeExt;
            from = "MIME:" + mime;
        }
        Log.i(TAG, "入队 uri=" + uri + " name=" + name + " ext=" + ext + " 类型来源=" + from);
        return addUri(uri, name, ext);
    }

    /** ContentResolver 侧的 MIME；查询异常按"取不到"处理 */
    private String typeOf(Uri uri) {
        try {
            return getContentResolver().getType(uri);
        } catch (Exception e) {
            Log.w(TAG, "ContentResolver.getType 失败 uri=" + uri + " err=" + e);
            return null;
        }
    }

    /** MIME 兜底时合成显示名：纯数字名（如 "468"）补 media_ 前缀，避免出现无扩展名/无意义的条目 */
    private static String synthesizeName(String rawName, String ext) {
        String base = FormatKit.baseNameOf(rawName);
        if (base.matches("\\d+")) base = "media_" + base;
        return base + "." + ext;
    }

    /**
     * 拒绝入队：Toast + 日志 + 状态栏文案。
     * 状态栏文案是给界面取证工具（uiautomator dump 抓不到 Toast）留的可读证据。
     */
    private boolean reject(Uri uri, String name, String ext, String mime) {
        String label = (ext != null && !ext.isEmpty()) ? "." + ext
                : ((mime == null || mime.trim().isEmpty()) ? "未知" : mime);
        final String msg = "不支持的类型：" + label;
        Log.w(TAG, "拒绝入队 " + msg + " uri=" + uri + " name=" + name + " ext=\"" + ext + "\" mime=" + mime);
        toast(msg);
        // setStatus 内部自行切主线程，此处不再重复包一层
        setStatus(msg, R.drawable.mouse_idle);
        return false;
    }

    /** 入队单个文件（应用内选择路径）；返回是否真正加入 */
    private boolean addUri(Uri uri, String name) {
        return addUri(uri, name, FormatKit.extOf(name));
    }

    /** 入队单个文件（统一入口）；类型不支持或已在队列中返回 false */
    private boolean addUri(Uri uri, String name, String ext) {
        if (!FormatKit.isSupportedExt(ext)) {
            return reject(uri, name, ext, null);
        }
        for (FileItem it : queue) {
            if (it.uri.equals(uri)) {
                Log.i(TAG, "跳过重复项 uri=" + uri);
                toast("文件已在队列中");
                return false;
            }
        }
        queue.add(new FileItem(uri, name, ext));
        refreshQueueUi();
        refreshTargets(ext);
        return true;
    }

    /** 刷新队列标题与列表（含重建 Adapter）；线程切换在方法内部完成，后台线程调用同样安全。 */
    private void refreshQueueUi() {
        runOnUiThread(() -> {
            queueTitle.setText("队列 (" + queue.size() + ")");
            listView.setAdapter(new FileAdapter());
        });
    }

    /** 从队列中移除单个文件；转换进行中禁止操作，避免与批量任务抢队列。 */
    private void removeAt(int position) {
        if (converting) {
            toast("转换进行中，如需调整请先点「取消转换」");
            return;
        }
        if (position < 0 || position >= queue.size()) return;
        String removed = queue.get(position).name;
        queue.remove(position);
        refreshQueueUi();
        refreshTargets(null);
        if (queue.isEmpty()) {
            setStatus(getString(R.string.empty_hint), R.drawable.mouse_idle);
        } else {
            toast("已移除 " + removed);
        }
    }

    /** 目标格式候选刷新入口：线程切换在此完成，保证下面碰视图的逻辑只在主线程跑。 */
    private void refreshTargets(String newExt) {
        runOnUiThread(() -> refreshTargetsOnMain(newExt));
    }

    private void refreshTargetsOnMain(String newExt) {
        // 新加入文件与旧文件格式冲突时重置目标候选
        Set<String> cats = new LinkedHashSet<>();
        for (FileItem it : queue) cats.add(FormatKit.categoryOf(it.ext));
        boolean allImages = cats.size() == 1 && cats.contains("image");

        if (newExt != null) {
            if (srcExtOfTargets != null && !srcExtOfTargets.equals(newExt)) {
                // 混入不同类型：仅全图片可继续
                if (allImages && "image".equals(FormatKit.categoryOf(newExt))) {
                    // 图片混队列：目标按首文件的扩展名给（vc8 修复④：此处必须同步 srcExtOfTargets，
                    // 否则后续冲突检测会被整段跳过）
                    applyImageTargets();
                    rebuildTargetChips();
                    return;
                }
                toast("已添加不同类型文件（如需图片合并 PDF 可多选图片）");
                return;
            }
        }

        if (queue.isEmpty()) {
            srcExtOfTargets = null;
            targets.clear();
            selectedTargetId = null;
            targetTitle.setText("目标格式：请选择文件");
            targetContainer.removeAllViews();
            return;
        }

        if (allImages) {
            applyImageTargets();
        } else {
            targets.clear();
            targets.addAll(FormatKit.targetsOf(queue.get(0).ext));
            srcExtOfTargets = queue.get(0).ext;
        }
        rebuildTargetChips();
    }

    /**
     * 图片队列的目标候选（vc8 修复①）。
     * 多张图片时：逐张转换的目标（来自首文件扩展名）排在前面，保证 rebuildTargetChips 自动
     * 选中的第一项就是"逐张转换（默认 JPG）"；「合并 PDF」作为可选目标追加在末尾，不再自动选中。
     * 单张图片时保持原有候选与顺序（含"转 PDF"）。
     * 同时统一维护 srcExtOfTargets，避免调用方漏改导致后续冲突检测被跳过（vc8 修复④）。
     */
    private void applyImageTargets() {
        List<FormatKit.Target> baseTargets = FormatKit.targetsOf(queue.get(0).ext);
        targets.clear();
        if (queue.size() > 1) {
            for (FormatKit.Target t : baseTargets) {
                if (!"pdf".equals(t.id)) targets.add(t);
            }
            targets.add(new FormatKit.Target("pdf", "合并 PDF"));
        } else {
            targets.addAll(baseTargets);
        }
        srcExtOfTargets = queue.get(0).ext;
    }

    private void rebuildTargetChips() {
        targetContainer.removeAllViews();
        selectedTargetId = null;
        if (targets.isEmpty()) {
            targetTitle.setText("目标格式：暂无可转换目标");
            return;
        }
        targetTitle.setText("目标格式：");
        LinearLayout row = null;
        for (int i = 0; i < targets.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                row.setLayoutParams(lp);
                targetContainer.addView(row);
            }
            final FormatKit.Target t = targets.get(i);
            Button chip = new Button(this);
            chip.setText(t.label);
            chip.setTextSize(13f);
            chip.setAllCaps(false);
            chip.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            chip.setLayoutParams(lp);
            chip.setBackgroundResource(R.drawable.chip);
            chip.setTextColor(Color.parseColor("#3A2E2E"));
            chip.setOnClickListener(v -> {
                selectedTargetId = t.id;
                for (int x = 0; x < targetContainer.getChildCount(); x++) {
                    LinearLayout r = (LinearLayout) targetContainer.getChildAt(x);
                    for (int y = 0; y < r.getChildCount(); y++) {
                        Button b = (Button) r.getChildAt(y);
                        boolean sel = b == chip;
                        b.setBackgroundResource(sel ? R.drawable.chip_selected : R.drawable.chip);
                        b.setTextColor(sel ? Color.WHITE : Color.parseColor("#3A2E2E"));
                    }
                }
            });
            row.addView(chip);
            if (i == 0) chip.performClick();
        }
    }

    // ---------- 转换 ----------
    private void startConvert() {
        if (converting) {
            // 转换进行中：此时按钮语义是"取消转换"
            cancelRequested = true;
            btnConvert.setEnabled(false);
            btnConvert.setText("正在取消…");
            setMascot(R.drawable.mouse_converting, "正在取消，等当前文件处理完…");
            return;
        }
        if (queue.isEmpty()) {
            toast("请先选择文件");
            return;
        }
        if (selectedTargetId == null) {
            toast("请选择目标格式");
            return;
        }
        if (Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                return;
            }
        }
        converting = true;
        cancelRequested = false;
        btnConvert.setEnabled(true);
        btnConvert.setText("取消转换");
        for (FileItem it : queue) it.state = ST_PENDING;
        refreshQueueUi();
        setMascot(R.drawable.mouse_converting, "正在转换 0/" + queue.size() + " …");

        new Thread(() -> {
            final List<FileItem> batch = new ArrayList<>(queue);
            final List<Uri> saved = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            final List<String> failed = new ArrayList<>();
            final boolean[] cancelled = {false};
            // 后台线程使用前先快照界面字段，避免转换途中被点击改变语义
            final String target = selectedTargetId;

            // 多图片 → 合并 PDF：这是用户主动发起的一个整体任务，失败只记这一项
            boolean allImages = true;
            for (FileItem it : batch) {
                if (!"image".equals(FormatKit.categoryOf(it.ext))) {
                    allImages = false;
                    break;
                }
            }

            try {
                if (allImages && "pdf".equals(target) && batch.size() > 1) {
                    // 合并 PDF 分支（vc8 修复②）：单张图片读取/解码失败只标记该张，其余图片照常合并产出
                    Log.i(TAG, "startConvert 合并PDF分支：target=" + target + "，共 " + batch.size() + " 张图片");
                    markItem(batch.get(0), ST_RUNNING, 1, batch.size(), batch.get(0).name);
                    String base = FormatKit.baseNameOf(batch.get(0).name);
                    try {
                        List<Uri> uris = new ArrayList<>();
                        List<String> display = new ArrayList<>();
                        for (FileItem it : batch) {
                            uris.add(it.uri);
                            display.add(it.name);
                        }
                        ConversionEngine.MergeResult mr =
                                ConversionEngine.imagesToPdfTolerant(this, uris, display, base);
                        for (int k = 0; k < mr.failedIndexes.size(); k++) {
                            int idx = mr.failedIndexes.get(k);
                            if (idx >= 0 && idx < batch.size()) batch.get(idx).state = ST_FAILED;
                            failed.add(mr.failedReasons.get(k));
                        }
                        for (int idx : mr.okIndexes) {
                            if (idx >= 0 && idx < batch.size()) batch.get(idx).state = ST_DONE;
                        }
                        if (mr.output != null) {
                            Uri savedUri = OutputSaver.save(this, mr.output.data, mr.output.fileName, "pdf");
                            saved.add(savedUri);
                            // 用磁盘上的真实文件名（重名时会被追加单层序号），保证弹窗/分享里显示的名字与实际一致
                            names.add(OutputSaver.displayNameOf(this, savedUri, mr.output.fileName));
                            Log.i(TAG, "合并PDF 落盘：" + mr.output.fileName + " → " + savedUri);
                        } else {
                            Log.e(TAG, "合并PDF 无产出：可用图片为 0，失败明细=" + failed);
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "合并PDF 分支异常：" + e.getClass().getName() + ": " + e.getMessage(), e);
                        for (FileItem it : batch) {
                            if (it.state == ST_PENDING || it.state == ST_RUNNING) it.state = ST_FAILED;
                        }
                        failed.add(base + "_合并" + batch.size() + "图.pdf：" + briefMessage(e));
                    }
                    notifyQueue();
                } else {
                    // 批量逐文件转换：单个文件异常只记录该项，继续处理队列中其余文件，不中断整批
                    Log.i(TAG, "startConvert 逐张分支：target=" + target + "，共 " + batch.size() + " 个文件");
                    for (int i = 0; i < batch.size(); i++) {
                        if (cancelRequested) {
                            cancelled[0] = true;
                            for (int j = i; j < batch.size(); j++) batch.get(j).state = ST_CANCELLED;
                            break;
                        }
                        FileItem it = batch.get(i);
                        markItem(it, ST_RUNNING, i + 1, batch.size(), it.name);
                        Log.i(TAG, "逐张转换 [" + (i + 1) + "/" + batch.size() + "] 开始：" + it.name + " → " + target);
                        try {
                            List<ConversionEngine.Output> outs =
                                    ConversionEngine.convert(this, it.uri, it.name, target);
                            for (ConversionEngine.Output o : outs) {
                                Uri savedUri = OutputSaver.save(this, o.data, o.fileName, FormatKit.extOf(o.fileName));
                                saved.add(savedUri);
                                // 用磁盘上的真实文件名（重名时会被追加单层序号），保证弹窗/分享里显示的名字与实际一致
                                names.add(OutputSaver.displayNameOf(this, savedUri, o.fileName));
                                Log.i(TAG, "逐张转换 [" + (i + 1) + "/" + batch.size() + "] 落盘："
                                        + o.fileName + " → " + savedUri);
                            }
                            it.state = ST_DONE;
                        } catch (Throwable e) {
                            it.state = ST_FAILED;
                            failed.add(it.name + "：" + briefMessage(e));
                            Log.e(TAG, "逐张转换 [" + (i + 1) + "/" + batch.size() + "] 失败：" + it.name
                                    + " | " + e.getClass().getName() + ": " + e.getMessage(), e);
                        }
                        notifyQueue();
                    }
                }
            } catch (Throwable t) {
                // vc8 修复⑤：Error/OOM 等非 Exception 也可能从链路中冒出，兜底保证不卡在"取消转换"
                Log.e(TAG, "转换线程未捕获异常兜底：" + t.getClass().getName() + ": " + t.getMessage(), t);
                failed.add("严重异常（" + t.getClass().getSimpleName() + "）：" + briefMessage(t));
                for (FileItem it : batch) {
                    if (it.state == ST_PENDING || it.state == ST_RUNNING) it.state = ST_FAILED;
                }
            } finally {
                // 无论成功/失败/异常，UI 状态一定在此复位
                int remaining = 0;
                for (FileItem it : batch) if (it.state == ST_CANCELLED) remaining++;
                Log.i(TAG, "转换线程结束：成功=" + names.size() + "，失败=" + failed.size()
                        + "，取消=" + cancelled[0] + "，未处理=" + remaining);
                notifyQueue();
                // showConvertResult 内部自行切主线程，此处不再重复包一层
                showConvertResult(saved, names, failed, cancelled[0], remaining);
            }
        }).start();
    }

    /** 把"正在处理第几个文件"同步到列表状态与顶部状态栏。 */
    private void markItem(FileItem item, int state, int index, int total, String name) {
        item.state = state;
        runOnUiThread(() -> {
            notifyQueue();
            statusText.setText("正在转换 " + index + "/" + total + "：" + name);
            mascot.setImageResource(R.drawable.mouse_converting);
        });
    }

    /**
     * 刷新队列列表。
     * 转换/另存等耗时任务跑在后台线程，而 ListView.notifyDataSetChanged →
     * AdapterView.onChanged → requestLayout 必须由创建视图层级的主线程执行，否则抛出
     * CalledFromWrongThreadException（"Only the original thread that created a view hierarchy
     * can touch its views"）导致应用闪退。因此把线程切换收敛在本方法内部：已在主线程则直接执行，
     * 后台线程则 post 到主线程，调用方无需（也不应）各自再包一层。
     */
    private void notifyQueue() {
        runOnUiThread(() -> {
            if (listView.getAdapter() instanceof BaseAdapter) {
                ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
            }
        });
    }

    /** 汇总本次批量的成功 / 失败 / 取消结果，任一文件失败不影响其它文件。 */
    private void showConvertResult(List<Uri> saved, List<String> names, List<String> failed,
                                   boolean cancelled, int remaining) {
        runOnUiThread(() -> showConvertResultOnMain(saved, names, failed, cancelled, remaining));
    }

    /** 结果汇总的实际实现（含弹窗与按钮状态复位），只允许在主线程执行。 */
    private void showConvertResultOnMain(List<Uri> saved, List<String> names, List<String> failed,
                                         boolean cancelled, int remaining) {
        converting = false;
        cancelRequested = false;
        btnConvert.setEnabled(true);
        btnConvert.setText(getString(R.string.convert));

        StringBuilder sb = new StringBuilder();
        if (!names.isEmpty()) {
            sb.append("已保存 ").append(names.size()).append(" 个文件到\n下载/")
                    .append(OutputSaver.SUB_DIR).append("/\n\n");
            for (String n : names) sb.append("• ").append(n).append("\n");
        }
        if (!failed.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("失败 ").append(failed.size()).append(" 个：\n");
            for (String f : failed) sb.append("• ").append(f).append("\n");
        }

        if (cancelled) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("已取消转换");
            if (remaining > 0) sb.append("，剩余 ").append(remaining).append(" 个文件未处理");
            sb.append("。");
        }

        if (names.isEmpty()) {
            setMascot(R.drawable.mouse_error, cancelled ? "已取消转换" : "转换失败");
            new AlertDialog.Builder(this)
                    .setTitle(cancelled ? "已取消转换" : "转换失败")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        boolean partial = !failed.isEmpty() || cancelled;
        String tip;
        if (cancelled) tip = "已取消，完成 " + names.size() + " 个";
        else if (!failed.isEmpty()) tip = "完成 " + names.size() + " 个，失败 " + failed.size() + " 个";
        else tip = "转换完成";
        setMascot(partial ? R.drawable.mouse_error : R.drawable.mouse_success, tip);

        String title;
        if (cancelled) title = "已取消（部分完成）";
        else if (!failed.isEmpty()) title = "转换完成（部分失败）";
        else title = "转换完成";

        AlertDialog.Builder dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString().trim())
                .setPositiveButton("打开文件", (d, w) -> openUri(saved.get(0)))
                .setNegativeButton(names.size() > 1 ? "分享全部（" + names.size() + "）" : "分享",
                        (d, w) -> shareAll(saved))
                .setNeutralButton("另存为…", (d, w) -> startSaveTo(saved, names));
        dlg.show();
    }

    /** 异常信息压缩成一行，避免弹窗被超长堆栈撑爆。 */
    private static String briefMessage(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    private void openUri(Uri uri) {
        try {
            // API 26+ 禁止把 file:// 交给外部应用，统一转成 content://
            Uri target = OutputSaver.shareableUri(this, uri);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(target, OutputSaver.mimeOf(FormatKit.extOf(queryName(target))));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "打开文件");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            toast("无法打开，请到下载/FlyingMouseFormat 目录查看");
        }
    }

    private void shareUri(Uri uri) {
        try {
            // API 26+ 禁止把 file:// 放进 EXTRA_STREAM，统一转成 content://
            Uri target = OutputSaver.shareableUri(this, uri);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(OutputSaver.mimeOf(FormatKit.extOf(queryName(target))));
            i.putExtra(Intent.EXTRA_STREAM, target);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "分享文件");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            toast("无法分享");
        }
    }

    /**
     * 批量分享：1 个产物走 ACTION_SEND，多个产物走 ACTION_SEND_MULTIPLE。
     * 所有 Uri 均经 shareableUri 转为 content://，并附带 ClipData 保证接收方拿到读权限。
     */
    private void shareAll(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        try {
            List<Uri> targets = new ArrayList<>();
            String mime = null;
            boolean mixed = false;
            for (Uri u : uris) {
                Uri t = OutputSaver.shareableUri(this, u);
                String m = OutputSaver.mimeOf(FormatKit.extOf(queryName(t)));
                if (mime == null) mime = m;
                else if (!mime.equals(m)) mixed = true;
                targets.add(t);
            }
            if (mime == null || mixed) mime = "*/*";

            if (targets.size() == 1) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, targets.get(0));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "分享文件");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
                return;
            }

            Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.setType(mime);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(targets));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(buildClipData(targets));
            Intent chooser = Intent.createChooser(send, "分享 " + targets.size() + " 个文件");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception e) {
            toast("无法分享");
        }
    }

    private static ClipData buildClipData(List<Uri> uris) {
        ClipData clip = null;
        for (Uri u : uris) {
            if (clip == null) clip = ClipData.newRawUri("converted", u);
            else clip.addItem(new ClipData.Item(u));
        }
        return clip;
    }

    // ---------- 另存为（SAF） ----------

    /**
     * 另存为：把本轮产物保存到用户指定的位置。
     * <p>单文件直接走 ACTION_CREATE_DOCUMENT（系统"保存副本"窗口，可存到任意位置，含 Download）；
     * 多文件先尝试 ACTION_OPEN_DOCUMENT_TREE 一次授权写多份，拿不到授权时自动降级为逐张 CREATE_DOCUMENT。</p>
     * <p>不能把"目录授权"当作唯一路径：targetSdk>=30 时系统禁止用 ACTION_OPEN_DOCUMENT_TREE 授权
     * Download 根目录、内部存储根目录、Android/data、Android/obb（DocumentsUI 显示
     * "Can't use this folder" 且 "USE THIS FOLDER" 置灰），此时永远拿不到 tree Uri。</p>
     */
    private void startSaveTo(List<Uri> uris, List<String> names) {
        if (uris == null || uris.isEmpty()) {
            Log.w(TAG, "另存为 被调用但产物列表为空");
            toast("没有可另存的文件");
            return;
        }
        pendingSaveUris = new ArrayList<>(uris);
        pendingSaveNames = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) {
            String nm = (names != null && i < names.size()) ? names.get(i) : null;
            pendingSaveNames.add((nm == null || nm.trim().isEmpty()) ? ("converted_" + (i + 1)) : nm);
        }
        saveOkNames = new ArrayList<>();
        saveFailed = new ArrayList<>();
        saveFlowActive = true;
        // 单文件不需要目录授权（CREATE_DOCUMENT 一次选好文件名+位置）；多文件先试一次目录授权
        treeTried = pendingSaveUris.size() < 2;
        persistSavePending();
        Log.i(TAG, "另存为 启动：待另存 " + pendingSaveUris.size() + " 个 " + pendingSaveNames
                + "，首个环节=" + (treeTried ? "ACTION_CREATE_DOCUMENT（逐张）" : "ACTION_OPEN_DOCUMENT_TREE（一次授权）"));
        nextSaveStep();
    }

    /** 推进另存流程：还有产物 → 发起下一次请求；清单已空 → 汇总收尾。 */
    private void nextSaveStep() {
        if (!saveFlowActive) return;
        if (pendingSaveUris == null || pendingSaveUris.isEmpty()) {
            finishSave(false);
            return;
        }
        if (!treeTried) {
            treeTried = true;
            persistSavePending();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            Uri hint = initialTreeHint();
            if (hint != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint);
            Log.i(TAG, "另存为 请求目录授权：ACTION_OPEN_DOCUMENT_TREE（剩余 " + pendingSaveUris.size() + " 个产物）");
            try {
                startActivityForResult(i, REQ_SAVE_DIR);
            } catch (Exception e) {
                Log.w(TAG, "另存为 拉起目录选择器失败，降级为逐张另存 errType="
                        + e.getClass().getSimpleName() + " errMsg=" + e.getMessage());
                toast("当前系统不支持选择目录，改为逐个另存");
                nextSaveStep();
            }
            return;
        }
        startSaveOne();
    }

    /** 目录选择器的起始位置提示：优先打开 Documents（Download 与存储根目录是受限目录，选了也无法授权）。 */
    private Uri initialTreeHint() {
        try {
            return Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments");
        } catch (Exception e) {
            return null;
        }
    }

    /** 逐张另存：用 ACTION_CREATE_DOCUMENT 让用户为当前产物指定文件名与位置。 */
    private void startSaveOne() {
        final String nm = pendingSaveNames.get(0);
        final String mime = OutputSaver.mimeOf(FormatKit.extOf(nm));
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, nm);
        int total = saveOkNames.size() + pendingSaveUris.size();
        setStatus("另存 " + (saveOkNames.size() + 1) + "/" + total + "：" + nm, R.drawable.mouse_converting);
        Log.i(TAG, "另存为 请求保存单文件：ACTION_CREATE_DOCUMENT name=" + nm + " mime=" + mime
                + "（剩余 " + pendingSaveUris.size() + " 个）");
        try {
            startActivityForResult(i, REQ_SAVE_ONE);
        } catch (Exception e) {
            Log.e(TAG, "另存为 拉起保存窗口失败：name=" + nm + " errType="
                    + e.getClass().getSimpleName() + " errMsg=" + e.getMessage());
            toast("无法拉起系统保存窗口：" + briefMessage(e));
            saveFailed.add(nm + "：" + briefMessage(e));
            pendingSaveUris.remove(0);
            pendingSaveNames.remove(0);
            persistSavePending();
            nextSaveStep();
        }
    }

    /** 把当前待另存的第一个产物写入用户新建的目标文档（系统已在目标位置建好空文件）。 */
    private void copyOnePending(final Uri dst) {
        final Uri src = pendingSaveUris.get(0);
        final String nm = pendingSaveNames.get(0);
        new Thread(() -> {
            String failNote = null;
            try {
                Log.i(TAG, "另存(单张) 复制开始：name=" + nm + " src=" + src + " dst=" + dst);
                long bytes = copyUri(src, dst);
                String real = OutputSaver.displayNameOf(this, dst, nm);
                Log.i(TAG, "另存(单张) 复制成功：name=" + real + " bytes=" + bytes + " dst=" + dst);
                saveOkNames.add(real);
            } catch (Exception e) {
                Log.e(TAG, "另存(单张) 复制失败：name=" + nm + " errType="
                        + e.getClass().getSimpleName() + " errMsg=" + e.getMessage());
                saveFailed.add(nm + "：" + briefMessage(e));
                failNote = "另存失败：" + briefMessage(e);
            }
            final String note = failNote;
            runOnUiThread(() -> {
                pendingSaveUris.remove(0);
                pendingSaveNames.remove(0);
                persistSavePending();
                if (note != null) toast(note);
                nextSaveStep();
            });
        }).start();
    }

    /** 把暂存的产物逐个写入用户选定的目录；单个文件失败不影响其它文件。 */
    private void saveToTree(Uri tree, boolean persisted) {
        final List<Uri> srcs = new ArrayList<>(pendingSaveUris);
        final List<String> names = new ArrayList<>(pendingSaveNames);
        setMascot(R.drawable.mouse_converting, "正在另存 0/" + srcs.size() + " …");
        new Thread(() -> {
            for (int i = 0; i < srcs.size(); i++) {
                final int index = i;
                final String nm = i < names.size() ? names.get(i) : ("converted_" + (i + 1));
                // setStatus 内部自行切主线程，后台线程可直接调用
                setStatus("正在另存 " + (index + 1) + "/" + srcs.size() + "：" + nm,
                        R.drawable.mouse_converting);
                try {
                    Log.i(TAG, "另存(目录) 复制开始：name=" + nm + " src=" + srcs.get(i) + " tree=" + tree
                            + " 持久化授权=" + (persisted ? "是" : "否"));
                    Uri out = DocumentsContract.createDocument(getContentResolver(), tree,
                            OutputSaver.mimeOf(FormatKit.extOf(nm)), nm);
                    if (out == null) throw new Exception("所选目录未返回可写文件（可能没有写权限）");
                    long bytes = copyUri(srcs.get(i), out);
                    String real = OutputSaver.displayNameOf(this, out, nm);
                    Log.i(TAG, "另存(目录) 复制成功：name=" + real + " bytes=" + bytes + " dst=" + out);
                    saveOkNames.add(real);
                } catch (Exception e) {
                    Log.e(TAG, "另存(目录) 复制失败：name=" + nm + " errType="
                            + e.getClass().getSimpleName() + " errMsg=" + e.getMessage());
                    saveFailed.add(nm + "：" + briefMessage(e));
                }
            }
            runOnUiThread(() -> {
                pendingSaveUris.clear();
                pendingSaveNames.clear();
                persistSavePending();
                finishSave(false);
            });
        }).start();
    }

    /** 另存收尾：清掉待另存缓存，写汇总日志，并用弹窗明确告知成功/失败。 */
    private void finishSave(final boolean cancelled) {
        saveFlowActive = false;
        final int ok = saveOkNames == null ? 0 : saveOkNames.size();
        final int bad = saveFailed == null ? 0 : saveFailed.size();
        final List<String> okList = saveOkNames == null ? new ArrayList<>() : new ArrayList<>(saveOkNames);
        final List<String> badList = saveFailed == null ? new ArrayList<>() : new ArrayList<>(saveFailed);
        clearSavePending();
        Log.i(TAG, "另存为 结束：成功=" + ok + " 失败=" + bad + " 是否用户取消=" + cancelled
                + " 成功清单=" + okList + " 失败清单=" + badList);
        if (ok == 0 && bad == 0) {
            setMascot(R.drawable.mouse_idle, cancelled ? "已取消另存" : "没有可另存的文件");
            return;
        }
        runOnUiThread(() -> {
            setMascot(bad == 0 ? R.drawable.mouse_success : R.drawable.mouse_error,
                    bad == 0 ? "已另存 " + ok + " 个文件" : "另存完成 " + ok + " 个，失败 " + bad + " 个");
            StringBuilder sb = new StringBuilder();
            if (ok > 0) {
                sb.append("已另存 ").append(ok).append(" 个文件：\n");
                for (String n : okList) sb.append("· ").append(n).append("\n");
            } else {
                sb.append("本次没有文件另存成功。\n");
            }
            if (bad > 0) {
                sb.append("失败 ").append(bad).append(" 个：\n");
                for (String f : badList) sb.append("· ").append(f).append("\n");
            }
            if (cancelled) sb.append("已取消剩余文件的另存。");
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(bad > 0 ? "另存完成（有失败）" : "另存完成")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("好", null)
                    .show();
        });
    }

    /** 把内容提供方里的产物字节流复制到目标 Uri，返回写入字节数。 */
    private long copyUri(Uri src, Uri dst) throws Exception {
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(dst)) {
            if (in == null) throw new Exception("无法读取源文件");
            if (os == null) throw new Exception("目标位置不可写");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            os.flush();
        }
        return total;
    }

    // ---------- 待另存清单持久化（防 Activity 被回收后静默丢失） ----------

    private void persistSavePending() {
        try {
            SharedPreferences.Editor ed = getSharedPreferences(SAVE_PREF, MODE_PRIVATE).edit();
            if (pendingSaveUris == null || pendingSaveUris.isEmpty()) {
                ed.clear();
            } else {
                ed.putString(KEY_URIS, saveJoin(pendingSaveUris));
                ed.putString(KEY_NAMES, saveJoin(pendingSaveNames));
                ed.putBoolean(KEY_TREE_TRIED, treeTried);
                ed.putString(KEY_OK, saveJoin(saveOkNames));
                ed.putString(KEY_FAILED, saveJoin(saveFailed));
            }
            ed.apply();
        } catch (Exception e) {
            Log.w(TAG, "另存为 待另存清单持久化失败 errType=" + e.getClass().getSimpleName()
                    + " errMsg=" + e.getMessage());
        }
    }

    /**
     * 若当前实例的待另存字段为空（Activity 被系统回收后重建），从磁盘恢复，避免静默丢弃：
     * 旧实现里字段为 null 会直接 return，用户看不到任何成功/失败提示。
     */
    private void loadSavePendingIfNeeded() {
        if (pendingSaveUris != null && !pendingSaveUris.isEmpty()) return;
        try {
            SharedPreferences sp = getSharedPreferences(SAVE_PREF, MODE_PRIVATE);
            String rawUris = sp.getString(KEY_URIS, null);
            if (rawUris == null || rawUris.isEmpty()) return;
            List<Uri> uris = new ArrayList<>();
            for (String s : rawUris.split("\n")) {
                if (!s.isEmpty()) uris.add(Uri.parse(s));
            }
            if (uris.isEmpty()) return;
            List<String> names = saveSplit(sp.getString(KEY_NAMES, ""));
            while (names.size() < uris.size()) names.add("converted_" + (names.size() + 1));
            pendingSaveUris = uris;
            pendingSaveNames = names;
            treeTried = sp.getBoolean(KEY_TREE_TRIED, true);
            saveOkNames = saveSplit(sp.getString(KEY_OK, ""));
            saveFailed = saveSplit(sp.getString(KEY_FAILED, ""));
            saveFlowActive = true;
            Log.i(TAG, "另存为 从持久化恢复待另存清单：" + uris.size() + " 个 " + names
                    + "（Activity 可能被系统回收过）");
        } catch (Exception e) {
            Log.w(TAG, "另存为 恢复待另存清单失败 errType=" + e.getClass().getSimpleName()
                    + " errMsg=" + e.getMessage());
        }
    }

    private void clearSavePending() {
        pendingSaveUris = null;
        pendingSaveNames = null;
        saveOkNames = null;
        saveFailed = null;
        try {
            getSharedPreferences(SAVE_PREF, MODE_PRIVATE).edit().clear().apply();
        } catch (Exception e) {
            Log.w(TAG, "另存为 清理待另存缓存失败 errMsg=" + e.getMessage());
        }
    }

    private static String saveJoin(List<?> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.valueOf(o).replace('\n', ' '));
        }
        return sb.toString();
    }

    private static List<String> saveSplit(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split("\n")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startConvert();
        } else {
            toast("需要存储权限才能保存文件到下载目录");
        }
    }

    // ---------- UI 辅助 ----------
    private void setMascot(int res, String text) {
        runOnUiThread(() -> {
            mascot.setImageResource(res);
            statusText.setText(text);
        });
    }

    /** 更新底部状态文案与吉祥物图标；线程切换在方法内部完成。 */
    private void setStatus(String text, int res) {
        runOnUiThread(() -> {
            statusText.setText(text);
            mascot.setImageResource(res);
        });
    }

    /** 弹 Toast；后台线程调用时 post 到主线程，避免无 Looper 的线程直接弹 Toast 抛异常。 */
    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    static final class FileItem {
        final Uri uri;
        final String name;
        final String ext;
        /** 转换状态：ST_PENDING / ST_RUNNING / ST_DONE / ST_FAILED / ST_CANCELLED */
        int state = ST_PENDING;

        FileItem(Uri uri, String name, String ext) {
            this.uri = uri;
            this.name = name;
            this.ext = ext;
        }
    }

    private final class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return queue.size();
        }

        @Override
        public Object getItem(int position) {
            return queue.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_file, parent, false);
            }
            FileItem it = queue.get(position);
            TextView name = convertView.findViewById(R.id.itemName);
            TextView meta = convertView.findViewById(R.id.itemMeta);
            name.setText(it.name);
            String cat = FormatKit.categoryOf(it.ext);
            String catCn;
            switch (cat) {
                case "image": catCn = "图片"; break;
                case "pdf": catCn = "PDF"; break;
                case "text": catCn = "文本"; break;
                case "data": catCn = "数据"; break;
                case "zip": catCn = "压缩包"; break;
                case "ebook": catCn = "电子书"; break;
                case "music": catCn = "音乐"; break;
                case "audio": catCn = "音频"; break;
                default: catCn = cat;
            }
            meta.setText("." + it.ext + " · " + catCn + stateLabel(it.state));

            TextView remove = convertView.findViewById(R.id.itemRemove);
            remove.setVisibility(converting ? View.GONE : View.VISIBLE);
            remove.setOnClickListener(v -> removeAt(position));
            return convertView;
        }

        private String stateLabel(int state) {
            switch (state) {
                case ST_RUNNING: return " · 转换中…";
                case ST_DONE: return " · 已完成";
                case ST_FAILED: return " · 失败";
                case ST_CANCELLED: return " · 已取消";
                default: return "";
            }
        }
    }
}
