package com.example.tvwebdavbox;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 本地文件选择器：用于把电视本机的文件上传到 WebDAV。
 * 不依赖系统文件选择器，全部由方向键操作，兼容老电视盒子。
 */
public class LocalPickerActivity extends Activity {

    public static final String EXTRA_PICKED_PATH = "picked_path";

    private static final int REQUEST_STORAGE = 3001;

    private ListView listView;
    private TextView pathText;
    private TextView titleText;
    private EntryAdapter adapter;

    private File currentDir;
    private File homeDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        listView = findViewById(R.id.picker_list);
        pathText = findViewById(R.id.picker_path);
        titleText = findViewById(R.id.picker_title);

        titleText.setText(R.string.pick_local_file);
        adapter = new EntryAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onEntryClicked(position));

        homeDir = resolveHomeDir();
        currentDir = homeDir;
        ensurePermissionThenLoad();
    }

    private File resolveHomeDir() {
        File external = Environment.getExternalStorageDirectory();
        if (external != null && external.exists()) {
            return external;
        }
        File fallback = getExternalFilesDir(null);
        return fallback != null ? fallback : getFilesDir();
    }

    private void ensurePermissionThenLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            return;
        }
        load(currentDir);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_STORAGE) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                toast(getString(R.string.storage_permission_denied));
                // 退回应用私有目录，仍可选择其中的文件
                currentDir = getExternalFilesDir(null);
                if (currentDir == null) {
                    currentDir = getFilesDir();
                }
            }
            load(currentDir);
        }
    }

    private void load(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            toast(getString(R.string.cannot_access_directory));
            return;
        }
        currentDir = dir;
        File[] files = dir.listFiles();
        List<RowItem> rows = new ArrayList<>();
        if (files != null) {
            List<File> fileList = new ArrayList<>();
            Collections.addAll(fileList, files);
            Collections.sort(fileList, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().toLowerCase(Locale.US).compareTo(b.getName().toLowerCase(Locale.US));
            });
            for (File file : fileList) {
                if (file.isDirectory()) {
                    rows.add(new RowItem(file.getName(), getString(R.string.type_folder), true, file));
                } else {
                    String meta = FileUtils.humanSize(file.length());
                    rows.add(new RowItem(file.getName(), meta, false, file));
                }
            }
        }
        adapter.submit(rows);
        pathText.setText(currentDir.getAbsolutePath());
        if (adapter.getCount() > 0) {
            listView.requestFocus();
            listView.setSelection(0);
        }
    }

    private void onEntryClicked(int position) {
        RowItem row = adapter.getItemAt(position);
        if (row == null || !(row.tag instanceof File)) {
            return;
        }
        File file = (File) row.tag;
        if (file.isDirectory()) {
            load(file);
        } else {
            Intent result = new Intent();
            result.putExtra(EXTRA_PICKED_PATH, file.getAbsolutePath());
            setResult(RESULT_OK, result);
            finish();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU
                || event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null && homeDir != null
                && !currentDir.equals(homeDir) && currentDir.getParentFile() != null) {
            load(currentDir.getParentFile());
            return;
        }
        super.onBackPressed();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
