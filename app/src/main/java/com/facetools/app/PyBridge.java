package com.facetools.app;

import android.content.Context;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Native replacement for Pyodide. The page calls these methods through
 * window.AndroidPy (see makePy() in index.html). All file paths must be inside root().
 */
public class PyBridge {
    private static final byte[] KEY = {90, (byte) 145, 29, (byte) 231, 68, (byte) 178, (byte) 143, 3,
            98, (byte) 205, 25, 126, (byte) 164, 43, (byte) 144, 86};

    private final Context ctx;
    private final File root;
    private final int version;

    PyBridge(Context context, int versionCode) {
        this.ctx = context.getApplicationContext();
        this.root = new File(ctx.getFilesDir(), "fts");
        this.version = versionCode;
    }

    @JavascriptInterface
    public synchronized String init() {
        try {
            ensureBundle();
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(ctx));
            }
            Python.getInstance().getModule("pybridge").callAttr("setup", root.getAbsolutePath());
            return "ok";
        } catch (Throwable t) {
            return stack(t);
        }
    }

    @JavascriptInterface
    public String root() {
        return root.getAbsolutePath();
    }

    @JavascriptInterface
    public String mkdirs(String path) {
        try {
            File f = inRoot(path);
            return (f.isDirectory() || f.mkdirs()) ? "ok" : "mkdir failed: " + path;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }

    @JavascriptInterface
    public String write(String path, String b64, boolean append) {
        try {
            File f = inRoot(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return "mkdir failed: " + parent;
            }
            byte[] data = b64.isEmpty() ? new byte[0] : Base64.decode(b64, Base64.DEFAULT);
            try (FileOutputStream out = new FileOutputStream(f, append)) {
                out.write(data);
            }
            return "ok";
        } catch (Throwable t) {
            return String.valueOf(t.getMessage() != null ? t.getMessage() : t);
        }
    }

    @JavascriptInterface
    public double size(String path) {
        try {
            File f = inRoot(path);
            return f.isFile() ? f.length() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @JavascriptInterface
    public String read(String path, int offset, int length) {
        try (RandomAccessFile raf = new RandomAccessFile(inRoot(path), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[length];
            raf.readFully(buf);
            return Base64.encodeToString(buf, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Runs Python code; returns JSON {r: result|null, o: stdout, e: stderr, x: traceback|null}. */
    @JavascriptInterface
    public String run(String code) {
        try {
            PyObject r = Python.getInstance().getModule("pybridge").callAttr("run", code);
            return r.toString();
        } catch (Throwable t) {
            return "{\"r\":null,\"o\":\"\",\"e\":\"\",\"x\":" + JSONObject.quote(stack(t)) + "}";
        }
    }

    // ---- bundle extraction (assets/bundle.bin is the XOR-obfuscated converter zip) ----

    private void ensureBundle() throws IOException {
        File marker = new File(root, ".ready-" + version);
        if (marker.exists()) return;
        deleteRec(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Cannot create " + root);
        String rootPath = root.getCanonicalPath() + File.separator;
        try (InputStream raw = ctx.getAssets().open("bundle.bin");
             ZipInputStream zin = new ZipInputStream(new XorStream(raw))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File dest = new File(root, e.getName());
                if (!dest.getCanonicalPath().startsWith(rootPath)) throw new IOException("Bad entry " + e.getName());
                if (e.isDirectory()) {
                    dest.mkdirs();
                    continue;
                }
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
        new FileOutputStream(marker).close();
    }

    private static class XorStream extends FilterInputStream {
        private int pos = 0;

        XorStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            return b < 0 ? b : ((b ^ KEY[(pos++) & 15]) & 255);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            for (int i = 0; i < n; i++) b[off + i] = (byte) (b[off + i] ^ KEY[(pos++) & 15]);
            return n;
        }
    }

    private File inRoot(String path) throws IOException {
        File f = new File(path).getCanonicalFile();
        String r = root.getCanonicalPath();
        if (!f.getPath().equals(r) && !f.getPath().startsWith(r + File.separator)) {
            throw new IOException("Path outside app folder: " + path);
        }
        return f;
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
