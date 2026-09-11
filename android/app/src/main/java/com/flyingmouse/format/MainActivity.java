package com.flyingmouse.format;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
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
    private static final int REQ_SAVE_DIR = 1003;

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
    /** 待另存到用户所选目录的产物（点击另存为后暂存，目录选择返回时消费） */
    private List<Uri> pendingSaveUris;
    private List<String> pendingSaveNames;

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
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                    // 个别文件提供方不支持持久化授权，本次写入仍可继续
                }
                saveToTree(tree);
            } else {
                pendingSaveUris = null;
                pendingSaveNames = null;
                toast("已取消另存");
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
        runOnUiThread(() -> setStatus(msg, R.drawable.mouse_idle));
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

    private void refreshQueueUi() {
        queueTitle.setText("队列 (" + queue.size() + ")");
        listView.setAdapter(new FileAdapter());
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

    private void refreshTargets(String newExt) {
        // 新加入文件与旧文件格式冲突时重置目标候选
        Set<String> cats = new LinkedHashSet<>();
        for (FileItem it : queue) cats.add(FormatKit.categoryOf(it.ext));
        boolean allImages = cats.size() == 1 && cats.contains("image");

        if (newExt != null) {
            if (srcExtOfTargets != null && !srcExtOfTargets.equals(newExt)) {
                // 混入不同类型：仅全图片可继续
                if (allImages && "image".equals(FormatKit.categoryOf(newExt))) {
                    // 图片混队列：目标按首文件的扩展名给
                    targets.clear();
                    targets.addAll(FormatKit.targetsOf(queue.get(0).ext));
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
            // 图片队列：若只选一个图片按自身目标；若多张额外提供合并 PDF
            FormatKit.Target pdfT = new FormatKit.Target("pdf", "合并 PDF");
            boolean hasPdf = false;
            List<FormatKit.Target> baseTargets = FormatKit.targetsOf(queue.get(0).ext);
            List<FormatKit.Target> list = new ArrayList<>(baseTargets);
            if (list.size() == 1 && "pdf".equals(list.get(0).id)) hasPdf = true;
            targets.clear();
            if (queue.size() > 1) {
                for (FormatKit.Target t : baseTargets) {
                    if (!"pdf".equals(t.id)) targets.add(t);
                }
                targets.add(0, pdfT);
            } else {
                targets.addAll(baseTargets);
            }
            srcExtOfTargets = queue.get(0).ext;
        } else {
            targets.clear();
            targets.addAll(FormatKit.targetsOf(queue.get(0).ext));
            srcExtOfTargets = queue.get(0).ext;
        }
        rebuildTargetChips();
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

            // 多图片 → 合并 PDF：这是用户主动发起的一个整体任务，失败只记这一项
            boolean allImages = true;
            for (FileItem it : batch) {
                if (!"image".equals(FormatKit.categoryOf(it.ext))) {
                    allImages = false;
                    break;
                }
            }

            if (allImages && "pdf".equals(selectedTargetId) && batch.size() > 1) {
                markItem(batch.get(0), ST_RUNNING, 1, batch.size(), batch.get(0).name);
                String base = FormatKit.baseNameOf(batch.get(0).name) + "_合并" + batch.size() + "图";
                try {
                    List<Uri> uris = new ArrayList<>();
                    List<String> display = new ArrayList<>();
                    for (FileItem it : batch) {
                        uris.add(it.uri);
                        display.add(it.name);
                    }
                    ConversionEngine.Output out = ConversionEngine.imagesToPdf(this, uris, display, base);
                    Uri savedUri = OutputSaver.save(this, out.data, out.fileName, "pdf");
                    saved.add(savedUri);
                    // 用磁盘上的真实文件名（重名时会被追加单层序号），保证弹窗/分享里显示的名字与实际一致
                    names.add(OutputSaver.displayNameOf(this, savedUri, out.fileName));
                    for (FileItem it : batch) it.state = ST_DONE;
                } catch (Exception e) {
                    for (FileItem it : batch) it.state = ST_FAILED;
                    failed.add(base + ".pdf：" + briefMessage(e));
                }
                notifyQueue();
            } else {
                // 批量逐文件转换：单个文件异常只记录该项，继续处理队列中其余文件，不中断整批
                for (int i = 0; i < batch.size(); i++) {
                    if (cancelRequested) {
                        cancelled[0] = true;
                        for (int j = i; j < batch.size(); j++) batch.get(j).state = ST_CANCELLED;
                        break;
                    }
                    FileItem it = batch.get(i);
                    markItem(it, ST_RUNNING, i + 1, batch.size(), it.name);
                    try {
                        List<ConversionEngine.Output> outs =
                                ConversionEngine.convert(this, it.uri, it.name, selectedTargetId);
                        for (ConversionEngine.Output o : outs) {
                            Uri savedUri = OutputSaver.save(this, o.data, o.fileName, FormatKit.extOf(o.fileName));
                            saved.add(savedUri);
                            // 用磁盘上的真实文件名（重名时会被追加单层序号），保证弹窗/分享里显示的名字与实际一致
                            names.add(OutputSaver.displayNameOf(this, savedUri, o.fileName));
                        }
                        it.state = ST_DONE;
                    } catch (Exception e) {
                        it.state = ST_FAILED;
                        failed.add(it.name + "：" + briefMessage(e));
                    }
                    notifyQueue();
                }
            }

            int remaining = 0;
            for (FileItem it : batch) if (it.state == ST_CANCELLED) remaining++;
            final int rest = remaining;
            runOnUiThread(() -> showConvertResult(saved, names, failed, cancelled[0], rest));
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

    private void notifyQueue() {
        if (listView.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
        }
    }

    /** 汇总本次批量的成功 / 失败 / 取消结果，任一文件失败不影响其它文件。 */
    private void showConvertResult(List<Uri> saved, List<String> names, List<String> failed,
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

    /** 另存为：先用系统目录选择器（SAF）选目录，再逐个写入产物。 */
    private void startSaveTo(List<Uri> uris, List<String> names) {
        if (uris == null || uris.isEmpty()) {
            toast("没有可另存的文件");
            return;
        }
        pendingSaveUris = new ArrayList<>(uris);
        pendingSaveNames = new ArrayList<>(names);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_SAVE_DIR);
        } catch (Exception e) {
            pendingSaveUris = null;
            pendingSaveNames = null;
            toast("当前系统不支持选择目录");
        }
    }

    /** 把暂存的产物逐个写入用户选定的目录；单个文件失败不影响其它文件。 */
    private void saveToTree(Uri tree) {
        final List<Uri> srcs = pendingSaveUris;
        final List<String> names = pendingSaveNames;
        pendingSaveUris = null;
        pendingSaveNames = null;
        if (srcs == null || srcs.isEmpty() || names == null) return;

        setMascot(R.drawable.mouse_converting, "正在另存 0/" + srcs.size() + " …");
        new Thread(() -> {
            final List<String> okNames = new ArrayList<>();
            final List<String> fail = new ArrayList<>();
            for (int i = 0; i < srcs.size(); i++) {
                final int index = i;
                final String nm = i < names.size() ? names.get(i) : ("converted_" + (i + 1));
                runOnUiThread(() -> {
                    statusText.setText("正在另存 " + (index + 1) + "/" + srcs.size() + "：" + nm);
                    mascot.setImageResource(R.drawable.mouse_converting);
                });
                try {
                    String ext = FormatKit.extOf(nm);
                    Uri out = DocumentsContract.createDocument(getContentResolver(), tree,
                            OutputSaver.mimeOf(ext), nm);
                    if (out == null) throw new Exception("无法在所选目录创建文件");
                    copyUri(srcs.get(i), out);
                    okNames.add(nm);
                } catch (Exception e) {
                    fail.add(nm + "：" + briefMessage(e));
                }
            }
            runOnUiThread(() -> {
                boolean allOk = fail.isEmpty();
                setMascot(allOk ? R.drawable.mouse_success : R.drawable.mouse_error,
                        allOk ? "已另存 " + okNames.size() + " 个文件"
                              : "另存完成 " + okNames.size() + " 个，失败 " + fail.size() + " 个");
                StringBuilder sb = new StringBuilder();
                sb.append("已另存 ").append(okNames.size()).append(" / ").append(srcs.size())
                        .append(" 个文件到所选目录");
                for (String n : okNames) sb.append(" · ").append(n);
                if (!fail.isEmpty()) {
                    sb.append("；失败 ").append(fail.size()).append(" 个：");
                    for (String f : fail) sb.append(" · ").append(f);
                }
                new AlertDialog.Builder(this)
                        .setTitle("另存完成")
                        .setMessage(sb.toString().trim())
                        .setPositiveButton("好", null)
                        .show();
            });
        }).start();
    }

    /** 把内容提供方里的产物字节流复制到目标 Uri。 */
    private void copyUri(Uri src, Uri dst) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(dst)) {
            if (in == null || os == null) throw new Exception("无法读写所选目录");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        }
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

    private void setStatus(String text, int res) {
        statusText.setText(text);
        mascot.setImageResource(res);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
