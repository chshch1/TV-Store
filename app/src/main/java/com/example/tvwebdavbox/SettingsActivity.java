package com.example.tvwebdavbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** WebDAV 服务器配置页：地址、账号、密码、远端根目录、证书策略。 */
public class SettingsActivity extends Activity {

    private EditText serverInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText rootInput;
    private CheckBox insecureCheck;
    private Button testButton;
    private Button saveButton;
    private TextView statusText;
    private CheckBox autoCleanCheck;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        serverInput = findViewById(R.id.input_server);
        userInput = findViewById(R.id.input_user);
        passwordInput = findViewById(R.id.input_password);
        rootInput = findViewById(R.id.input_root);
        insecureCheck = findViewById(R.id.check_insecure);
        testButton = findViewById(R.id.btn_test);
        saveButton = findViewById(R.id.btn_save);
        statusText = findViewById(R.id.settings_status);
        autoCleanCheck = findViewById(R.id.check_auto_clean);

        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        serverInput.setText(PreferenceStore.getServerUrl(this));
        userInput.setText(PreferenceStore.getUsername(this));
        passwordInput.setText(PreferenceStore.getPassword(this));
        rootInput.setText(PreferenceStore.getRemoteRoot(this));
        insecureCheck.setChecked(PreferenceStore.isInsecureTls(this));
        autoCleanCheck.setChecked(PreferenceStore.isAutoCleanAfterInstall(this));

        updateStatusLine();

        testButton.setOnClickListener(v -> testConnection());
        saveButton.setOnClickListener(v -> save());
    }

    /**
     * 顶部状态行：明确区分"已载入内置默认配置"和"空白待填"，
     * 避免输入框里的真值被误看成灰色占位提示。
     */
    private void updateStatusLine() {
        if (statusText == null) {
            return;
        }
        String url = serverInput.getText().toString().trim();
        String builtIn = BuildConfig.DAV_URL == null ? "" : BuildConfig.DAV_URL.trim();
        if (TextUtils.isEmpty(url)) {
            statusText.setText(R.string.settings_status_empty);
        } else if (!builtIn.isEmpty() && url.equalsIgnoreCase(builtIn)) {
            statusText.setText(getString(R.string.settings_status_default, url));
        } else {
            statusText.setText("");
        }
    }

    private WebDavConfig readConfig() {
        return new WebDavConfig(
                serverInput.getText().toString(),
                userInput.getText().toString(),
                passwordInput.getText().toString(),
                rootInput.getText().toString(),
                insecureCheck.isChecked());
    }

    private void testConnection() {
        final WebDavConfig config = readConfig();
        if (!config.isConfigured()) {
            toast(getString(R.string.error_no_server));
            return;
        }
        setFormEnabled(false);
        testButton.setText(R.string.testing);
        new Thread(() -> {
            try {
                WebDavClient client = new WebDavClient(config);
                client.testConnection();
                ui.post(() -> {
                    setFormEnabled(true);
                    testButton.setText(R.string.action_test);
                    toast(getString(R.string.test_ok));
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    setFormEnabled(true);
                    testButton.setText(R.string.action_test);
                    String message = e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage();
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(R.string.test_failed)
                            .setMessage(message)
                            .setPositiveButton(R.string.confirm, null)
                            .show();
                });
            }
        }, "webdav-test").start();
    }

    private void save() {
        WebDavConfig config = readConfig();
        if (TextUtils.isEmpty(config.serverUrl)) {
            toast(getString(R.string.error_no_server));
            return;
        }
        PreferenceStore.save(this,
                config.serverUrl,
                config.username,
                config.password,
                config.remoteRoot,
                config.insecureTls);
        PreferenceStore.setAutoCleanAfterInstall(this, autoCleanCheck.isChecked());
        toast(getString(R.string.saved));
        setResult(RESULT_OK);
        finish();
    }

    private void setFormEnabled(boolean enabled) {
        serverInput.setEnabled(enabled);
        userInput.setEnabled(enabled);
        passwordInput.setEnabled(enabled);
        rootInput.setEnabled(enabled);
        insecureCheck.setEnabled(enabled);
        autoCleanCheck.setEnabled(enabled);
        saveButton.setEnabled(enabled);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
