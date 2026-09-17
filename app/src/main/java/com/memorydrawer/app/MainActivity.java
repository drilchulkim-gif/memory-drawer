package com.memorydrawer.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_SPEECH = 4101;
    private static final int REQ_FILE = 4102;
    private WebView webView;
    private String speechTarget = "memo";
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "MemoryDrawerNative");
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class NativeBridge {
        @JavascriptInterface
        public void startSpeech(String target) {
            runOnUiThread(() -> launchSpeech(target));
        }

        @JavascriptInterface
        public void stopSpeech() {
        }

        @JavascriptInterface
        public void saveBackup(String filename, String text) {
            runOnUiThread(() -> saveBackupToDownloads(filename, text));
        }
    }

    private void launchSpeech(String target) {
        speechTarget = "search".equals(target) ? "search" : "memo";
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                "search".equals(speechTarget) ? "찾고 싶은 기억을 말씀하세요" : "기억해 둘 내용을 말씀하세요");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            callSpeechError("이 휴대폰에서 음성인식 서비스를 열 수 없습니다.");
        }
    }

    private static String jsQuote(String value) {
        if (value == null) return "''";
        String s = value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
        return "'" + s + "'";
    }

    private void callSpeechError(String message) {
        if (webView == null) return;
        webView.evaluateJavascript("window.nativeSpeechError(" + jsQuote(message) + ")", null);
    }

    private void saveBackupToDownloads(String filename, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Android 10 이상에서 백업 저장을 지원합니다.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/기억의서랍");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("다운로드 파일을 만들 수 없습니다.");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("파일을 열 수 없습니다.");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "다운로드/기억의서랍 폴더에 백업했습니다.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "백업 파일 저장에 실패했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SPEECH) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) {
                    String text = results.get(0);
                    webView.evaluateJavascript(
                            "window.nativeSpeechResult(" + jsQuote(speechTarget) + "," + jsQuote(text) + ")", null);
                    return;
                }
            }
            callSpeechError("음성 입력이 취소되었거나 인식된 내용이 없습니다.");
            return;
        }

        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("MemoryDrawerNative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
