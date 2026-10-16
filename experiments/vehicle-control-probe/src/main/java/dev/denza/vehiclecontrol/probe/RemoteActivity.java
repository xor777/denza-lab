package dev.denza.vehiclecontrol.probe;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import dev.denza.disharebridge.LocalAdbClient;
import java.io.InputStream;

/** Owner-local prototype. Only the packaged page can call the validated controller. */
public final class RemoteActivity extends Activity {
    private WebView view;
    private Controller controller;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (!new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1").isFile()) {
                showAccess();
                return;
            }
            LocalAdbClient transport = LocalAdbClient.loopbackOnly(getApplicationContext());
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
            PyObject module = Python.getInstance().getModule("android_remote");
            PyObject remote = module.callAttr("create", transport,
                    new java.io.File(getFilesDir(), "actions").getAbsolutePath());
            showRemote(remote, module.callAttr("page").toString());
        } catch (Exception error) {
            TextView message = new TextView(this);
            message.setPadding(32, 32, 32, 32);
            message.setText("Пульт недоступен: " + error.getMessage());
            setContentView(message);
        }
    }
    void showAccess() {
        if (controller != null) controller.active = false;
        if (view != null) { view.removeJavascriptInterface("AndroidRemote"); view.destroy(); view = null; }
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);
        TextView status = new TextView(this);
        status.setText("Разрешите пульту локальный доступ ADB. Подтвердите запрос на экране машины и сравните отпечаток. Ключ создаётся только для этого приложения.");
        if (new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1").isFile()) {
            try { status.append("\nОтпечаток ключа пульта:\n" + LocalAdbClient.loopbackOnly(getApplicationContext()).publicKeyFingerprint()); }
            catch (Exception error) { status.append("\nНе удалось прочитать ключ: " + error.getMessage()); }
        }
        layout.addView(status);
        android.widget.Button request = new android.widget.Button(this);
        request.setText("Разрешить доступ ADB");
        request.setEnabled(!getSharedPreferences("access", MODE_PRIVATE).getBoolean("requested", false));
        layout.addView(request);
        android.widget.Button check = new android.widget.Button(this);
        check.setText("Я подтвердил — проверить");
        layout.addView(check);
        android.widget.Button again = new android.widget.Button(this);
        again.setText("Разрешить новую попытку запроса");
        layout.addView(again);
        android.widget.Button importKey = new android.widget.Button(this);
        importKey.setText("Дополнительно: импорт существующего ключа");
        importKey.setOnClickListener(v -> pickKey());
        layout.addView(importKey);
        setContentView(layout);
        request.setOnClickListener(v -> {
            // Latch on the UI thread before key preparation, so recreation cannot rearm it.
            if (getSharedPreferences("access", MODE_PRIVATE).getBoolean("requested", false)) return;
            if (!getSharedPreferences("access", MODE_PRIVATE).edit().putBoolean("requested", true).commit()) {
                status.append("\nНе удалось сохранить защиту от повтора; запрос не отправлен.");
                return;
            }
            request.setEnabled(false); check.setEnabled(false); again.setEnabled(false); importKey.setEnabled(false);
            new Thread(() -> {
                try {
                    LocalAdbClient client = LocalAdbClient.loopbackOnly(getApplicationContext());
                    String fingerprint = client.publicKeyFingerprint();
                    java.util.concurrent.CountDownLatch shown = new java.util.concurrent.CountDownLatch(1);
                    runOnUiThread(() -> {
                        status.setText("Отпечаток ключа пульта:\n" + fingerprint + "\nПодтвердите соответствующий запрос ADB на машине.");
                        status.postOnAnimation(shown::countDown);
                    });
                    if (!shown.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.io.IOException("Экран с отпечатком не готов; запрос не отправлен");
                    LocalAdbClient.AuthorizationRequestResult result = client.requestAuthorization();
                    runOnUiThread(() -> status.setText("Отпечаток ключа пульта:\n" + fingerprint +
                            (result == LocalAdbClient.AuthorizationRequestResult.ALREADY_AUTHORIZED
                            ? "\nДоступ уже разрешён. Нажмите «Я подтвердил — проверить»."
                            : "\nОдин запрос отправлен. Подтвердите на машине, затем нажмите «Я подтвердил — проверить».")));
                } catch (Exception error) {
                    runOnUiThread(() -> status.append("\nЗапрос не подтверждён: " + error.getMessage() + "\nАвтоматических повторов нет."));
                } finally {
                    runOnUiThread(() -> { check.setEnabled(true); again.setEnabled(true); importKey.setEnabled(true); });
                }
            }, "vehicle-adb-request").start();
        });
        check.setOnClickListener(v -> {
            check.setEnabled(false); request.setEnabled(false); again.setEnabled(false); importKey.setEnabled(false);
            new Thread(() -> {
                try {
                    if (!new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1").isFile())
                        throw new java.io.IOException("Сначала нажмите «Разрешить доступ ADB»");
                    LocalAdbClient.loopbackOnly(getApplicationContext()).shell("true");
                    runOnUiThread(this::recreate);
                } catch (Exception error) {
                    runOnUiThread(() -> status.append("\nДоступ пока не подтверждён: " + error.getMessage()));
                } finally {
                    runOnUiThread(() -> { check.setEnabled(true); again.setEnabled(true); importKey.setEnabled(true);
                        request.setEnabled(!getSharedPreferences("access", MODE_PRIVATE).getBoolean("requested", false)); });
                }
            }, "vehicle-adb-check").start();
        });
        again.setOnClickListener(v -> {
            if (getSharedPreferences("access", MODE_PRIVATE).edit().putBoolean("requested", false).commit()) {
                request.setEnabled(true);
                status.append("\nРазрешена новая попытка. Запрос ещё не отправлен; ключ и системная очередь не менялись.");
            }
        });
    }
    private void pickKey() {
        android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");
        pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, 1);
    }
    void showRemote(PyObject remote, String html) {
            view = new WebView(this);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setAllowFileAccess(false);
            view.getSettings().setAllowContentAccess(false);
            view.getSettings().setBlockNetworkLoads(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    return new WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
                }
            });
            controller = new Controller(remote);
            controller.active = true;
            view.addJavascriptInterface(controller, "AndroidRemote");
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.Button replace = new android.widget.Button(this);
            replace.setText("Доступ ADB");
            replace.setOnClickListener(v -> showAccess());
            layout.addView(replace);
            layout.addView(view, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(layout);
            view.loadDataWithBaseURL("https://vehicle-control.invalid/", html,
                    "text/html", "UTF-8", null);
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream input = getContentResolver().openInputStream(data.getData())) {
            byte[] bytes = input.readNBytes(32769);
            java.io.DataInputStream parsed = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            if (bytes.length > 32768 || parsed.readInt() != 0x44414b31) throw new Exception("Неверный формат ключа");
            java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
            int privateLength = parsed.readInt();
            if (privateLength < 1 || privateLength > 16384) throw new Exception("Неверный ключ");
            java.security.PrivateKey privateKey = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(parsed.readNBytes(privateLength)));
            int publicLength = parsed.readInt();
            if (publicLength < 1 || publicLength > 16384) throw new Exception("Неверный ключ");
            java.security.PublicKey publicKey = factory.generatePublic(new java.security.spec.X509EncodedKeySpec(parsed.readNBytes(publicLength)));
            if (((java.security.interfaces.RSAKey) privateKey).getModulus().bitLength() != 2048) throw new Exception("Нужна RSA2048 ADB identity");
            if (parsed.available() != 0) throw new Exception("Лишние данные");
            java.security.Signature proof = java.security.Signature.getInstance("SHA256withRSA");
            proof.initSign(privateKey); proof.update(new byte[] {1,2,3}); byte[] signature = proof.sign();
            proof.initVerify(publicKey); proof.update(new byte[] {1,2,3});
            if (!proof.verify(signature)) throw new Exception("Ключи не образуют пару");
            android.util.AtomicFile target = new android.util.AtomicFile(new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1"));
            java.io.FileOutputStream output = target.startWrite();
            try { output.write(bytes); target.finishWrite(output); }
            catch (Exception e) { target.failWrite(output); throw e; }
            recreate();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Импорт не выполнен: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
    public static final class Controller {
        private final PyObject remote;
        volatile boolean active;
        Controller(PyObject remote) { this.remote = remote; }
        @JavascriptInterface public String request(String payload) {
            if (!active) return "{\"error\":\"Пульт не на экране\"}";
            if (payload == null || payload.length() > 256) return "{\"error\":\"Некорректный запрос\"}";
            return remote.callAttr("request", payload).toString();
        }
    }
    @Override protected void onPause() { if (controller != null) controller.active = false; if (view != null) view.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (controller != null) controller.active = true; if (view != null) view.onResume(); }
    @Override protected void onDestroy() {
        if (view != null) { view.removeJavascriptInterface("AndroidRemote"); view.destroy(); }
        super.onDestroy();
    }
}
