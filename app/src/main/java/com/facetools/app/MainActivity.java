package com.facetools.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 11;
    private static final int REQ_PERM = 12;
    private static final int BG = 0xFF0A0C11;

    private WebView web;
    private ValueCallback<Uri[]> chooser;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        int versionCode = 1;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        web.addJavascriptInterface(new PyBridge(this, versionCode), "AndroidPy");
        web.addJavascriptInterface(new SaveBridge(), "AndroidSave");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !url.startsWith("file:///android_asset/");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, "Select files"), REQ_FILE);
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }
        });

        // Android 6-9 needs a runtime permission to write into Download/.
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }

        if (state == null) {
            web.loadUrl("file:///android_asset/index.html");
        } else {
            web.restoreState(state);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_FILE) {
            if (chooser != null) {
                chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
                chooser = null;
            }
            return;
        }
        super.onActivityResult(req, result, data);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidPy");
            web.removeJavascriptInterface("AndroidSave");
            web.destroy();
        }
        super.onDestroy();
    }

    /** window.AndroidSave.save(name, base64) -> "ok" or an error message. */
    public class SaveBridge {
        @JavascriptInterface
        public String save(String name, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (safe.isEmpty()) safe = "face.glb";
                String mime = safe.toLowerCase().endsWith(".zip") ? "application/zip" : "model/gltf-binary";
                return Build.VERSION.SDK_INT >= 29 ? saveScoped(safe, mime, data) : saveLegacy(safe, data);
            } catch (Throwable t) {
                return t.getMessage() != null ? t.getMessage() : t.toString();
            }
        }

        private String saveScoped(String name, String mime, byte[] data) throws Exception {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FaceTools");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return "Could not create file in Downloads";
            try (OutputStream o = getContentResolver().openOutputStream(u)) {
                o.write(data);
            }
            return "ok";
        }

        private String saveLegacy(String name, byte[] data) throws Exception {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FaceTools");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "Cannot write to Download/FaceTools (allow the storage permission and retry)";
            }
            File f = new File(dir, name);
            int n = 1;
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            while (f.exists()) f = new File(dir, stem + " (" + (n++) + ")" + ext);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(data);
            }
            return "ok";
        }
    }
}
