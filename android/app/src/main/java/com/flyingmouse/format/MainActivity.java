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
import android.provider.Settings;
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

    private static final int REQ_PICK = 1001;
    private static final int REQ_PERM = 1002;

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
        if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) {
                addUri(intent.getData(), queryName(intent.getData()));
                toast("已接收文件：可继续选择或直接转换");
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                addUri(u, queryName(u));
                toast("已接收文件：可继续选择或直接转换");
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ClipData cd = intent.getClipData();
            if (cd != null) {
                int added = 0;
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) {
                        addUri(u, queryName(u));
                        added++;
                    }
                }
                if (added > 0) toast("已接收 " + added + " 个文件");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            int added = 0;
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) {
                        addUri(u, queryName(u));
                        added++;
                    }
                }
            } else if (data.getData() != null) {
                addUri(data.getData(), queryName(data.getData()));
                added = 1;
            }
            if (added > 0) toast("已添加 " + added + " 个文件");
            setMascot(R.drawable.mouse_idle, queue.isEmpty() ? getString(R.string.empty_hint) : "选择完毕，挑一个目标格式吧");
        } else if (requestCode == REQ_PICK && resultCode == RESULT_CANCELED) {
            if (queue.isEmpty()) setMascot(R.drawable.mouse_idle, getString(R.string.empty_hint));
        }
    }

    private String queryName(Uri uri) {
        String name = null;
        try {
            String[] cols = {android.provider.OpenableColumns.DISPLAY_NAME};
            android.database.Cursor c = getContentResolver().query(uri, cols, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        return name;
    }

    private void addUri(Uri uri, String name) {
        String ext = FormatKit.extOf(name);
        if (!FormatKit.isSupportedExt(ext)) {
            toast("不支持的类型：" + (ext.isEmpty() ? "未知" : "." + ext));
            return;
        }
        for (FileItem it : queue) {
            if (it.uri.equals(uri)) {
                toast("文件已在队列中");
                return;
            }
        }
        queue.add(new FileItem(uri, name, ext));
        refreshQueueUi();
        refreshTargets(ext);
    }

    private void refreshQueueUi() {
        queueTitle.setText("队列 (" + queue.size() + ")");
        listView.setAdapter(new FileAdapter());
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
        if (converting) return;
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
        btnConvert.setEnabled(false);
        setMascot(R.drawable.mouse_converting, "正在转换，请稍候…");

        new Thread(() -> {
            try {
                List<Uri> saved = new ArrayList<>();
                List<String> names = new ArrayList<>();

                // 多图片 → 合并 PDF
                boolean allImages = true;
                for (FileItem it : queue) {
                    if (!"image".equals(FormatKit.categoryOf(it.ext))) {
                        allImages = false;
                        break;
                    }
                }
                if (allImages && "pdf".equals(selectedTargetId) && queue.size() > 1) {
                    List<Uri> uris = new ArrayList<>();
                    List<String> display = new ArrayList<>();
                    for (FileItem it : queue) {
                        uris.add(it.uri);
                        display.add(it.name);
                    }
                    String base = FormatKit.baseNameOf(queue.get(0).name) + "_合并" + queue.size() + "图";
                    ConversionEngine.Output out = ConversionEngine.imagesToPdf(this, uris, display, base);
                    Uri uri = OutputSaver.save(this, out.data, out.fileName, "pdf");
                    saved.add(uri);
                    names.add(out.fileName);
                } else {
                    for (FileItem it : queue) {
                        List<ConversionEngine.Output> outs =
                                ConversionEngine.convert(this, it.uri, it.name, selectedTargetId);
                        for (ConversionEngine.Output o : outs) {
                            Uri uri = OutputSaver.save(this, o.data, o.fileName, FormatKit.extOf(o.fileName));
                            saved.add(uri);
                            names.add(o.fileName);
                        }
                    }
                }

                runOnUiThread(() -> {
                    converting = false;
                    btnConvert.setEnabled(true);
                    setMascot(R.drawable.mouse_success, "转换完成");
                    File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), OutputSaver.SUB_DIR);
                    StringBuilder sb = new StringBuilder("已保存 ").append(names.size()).append(" 个文件到\n下载/")
                            .append(OutputSaver.SUB_DIR).append("/\n\n");
                    for (String n : names) sb.append("• ").append(n).append("\n");
                    new AlertDialog.Builder(this)
                            .setTitle("转换完成")
                            .setMessage(sb.toString())
                            .setPositiveButton("打开文件", (d, w) -> {
                                if (!saved.isEmpty()) openUri(saved.get(0));
                            })
                            .setNegativeButton("分享", (d, w) -> {
                                if (!saved.isEmpty()) shareUri(saved.get(0));
                            })
                            .setNeutralButton("完成", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    converting = false;
                    btnConvert.setEnabled(true);
                    setMascot(R.drawable.mouse_error, "转换失败");
                    new AlertDialog.Builder(this)
                            .setTitle("转换失败")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("好", null)
                            .show();
                });
            }
        }).start();
    }

    private void openUri(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, OutputSaver.mimeOf(FormatKit.extOf(queryName(uri))));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "打开文件"));
        } catch (Exception e) {
            toast("无法打开，请到下载/FlyingMouseFormat 目录查看");
        }
    }

    private void shareUri(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(OutputSaver.mimeOf(FormatKit.extOf(queryName(uri))));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享文件"));
        } catch (Exception e) {
            toast("无法分享");
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
                case "epub": catCn = "电子书"; break;
                default: catCn = cat;
            }
            meta.setText("." + it.ext + " · " + catCn);
            return convertView;
        }
    }
}
