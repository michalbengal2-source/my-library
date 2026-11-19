package app.mylibrary.reader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The library app (index.html and its files, in assets/) shown full screen in a web view.
 * The page runs at https://appassets.androidplatform.net/assets/ - an address that exists only inside this app,
 * so its encrypted library stays in the app's own private storage.
 * The "Native" object lets the page do what a web page can't: hide Android's bars, save files and receive shared files.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final int PICK = 1, SAVE = 2;

    WebView web;
    FrameLayout root;
    WindowInsetsControllerCompat bars;
    boolean immersive, loaded;
    ValueCallback<Uri[]> fileCallback;

    /** Files shared to the app, waiting for the page to import them (after the library is unlocked). */
    final List<Uri> shared = new ArrayList<>();
    final List<String> sharedNames = new ArrayList<>();

    /** Files the page is saving: written in pieces to a temporary file, then copied where the user chooses. */
    static class Job { File file; String name, mime; }
    final Map<String, Job> saving = new ConcurrentHashMap<>();
    final ArrayDeque<Job> toSave = new ArrayDeque<>();
    boolean choosing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#990000"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        bars = WindowCompat.getInsetsController(getWindow(), root);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        // Keep the page clear of Android's bars, the camera cut-out and the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/shared/", this::sharedFile)
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        web.addJavascriptInterface(new Bridge(), "Native");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;   // links to other sites (like AO3) open in the browser
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                String type = "*/*";
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0 && accept[0] != null && accept[0].startsWith("image/")) type = "image/*";
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(type);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, PICK);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/assets/index.html");
        receive(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receive(intent);
    }

    /** A file shared to the app or opened with it: kept until the page imports it. */
    void receive(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> got = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) got.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) got.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            got.add(intent.getData());
        }
        if (got.isEmpty()) return;
        synchronized (shared) {
            for (Uri u : got) { shared.add(u); sharedNames.add(nameOf(u)); }
        }
        intent.setAction(Intent.ACTION_MAIN);   // so it isn't imported again after the screen turns
        if (loaded) web.evaluateJavascript("typeof importShared === 'function' && importShared()", null);
    }

    String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (Exception ignored) { }
        String last = u.getLastPathSegment();
        return last != null ? last : "shared file";
    }

    /** Serves shared file number N at https://appassets.androidplatform.net/shared/N */
    WebResourceResponse sharedFile(String path) {
        try {
            Uri u;
            synchronized (shared) { u = shared.get(Integer.parseInt(path)); }
            InputStream in = getContentResolver().openInputStream(u);
            return new WebResourceResponse("application/octet-stream", null, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
        }
    }

    void setImmersive(boolean on) {
        immersive = on;
        if (on) bars.hide(WindowInsetsCompat.Type.systemBars());
        else bars.show(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (immersive) {   // Back leaves full screen first, like a second double tap
            web.evaluateJavascript("document.body.classList.contains('full') && actions.full()", null);
            setImmersive(false);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK) {
            Uri[] picked = null;
            if (result == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    picked = new Uri[clip.getItemCount()];
                    for (int i = 0; i < picked.length; i++) picked[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    picked = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) fileCallback.onReceiveValue(picked);
            fileCallback = null;
        } else if (request == SAVE) {
            Job job = toSave.poll();
            choosing = false;
            if (job != null) {
                Uri target = result == RESULT_OK && data != null ? data.getData() : null;
                new Thread(() -> {
                    boolean ok = false;
                    if (target != null) {
                        try (InputStream in = new FileInputStream(job.file); OutputStream out = getContentResolver().openOutputStream(target)) {
                            byte[] buf = new byte[1 << 16];
                            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                            ok = true;
                        } catch (Exception ignored) { }
                    }
                    job.file.delete();
                    String msg = ok ? "Saved: " + job.name : target == null ? "Not saved" : "Couldn't save " + job.name;
                    runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
                }).start();
            }
            nextSave();
        }
    }

    void nextSave() {
        if (choosing || toSave.isEmpty()) return;
        Job job = toSave.peek();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(job.mime);
        i.putExtra(Intent.EXTRA_TITLE, job.name);
        try {
            choosing = true;
            startActivityForResult(i, SAVE);
        } catch (ActivityNotFoundException e) {
            choosing = false;
            toSave.poll();
            job.file.delete();
            Toast.makeText(this, "No app to save files with", Toast.LENGTH_LONG).show();
        }
    }

    /** What the page can ask of the app (window.Native) */
    class Bridge {
        @JavascriptInterface
        public void setImmersive(boolean on) { runOnUiThread(() -> MainActivity.this.setImmersive(on)); }

        @JavascriptInterface
        public void setBarColor(String color) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(color);
                    boolean light = Color.red(c) + Color.green(c) + Color.blue(c) > 600;
                    root.setBackgroundColor(c);
                    bars.setAppearanceLightStatusBars(light);
                    bars.setAppearanceLightNavigationBars(light);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public int sharedCount() { synchronized (shared) { return shared.size(); } }

        @JavascriptInterface
        public String sharedName(int i) { synchronized (shared) { return i < sharedNames.size() ? sharedNames.get(i) : "shared file"; } }

        @JavascriptInterface
        public void sharedClear() { synchronized (shared) { shared.clear(); sharedNames.clear(); } }

        @JavascriptInterface
        public String saveBegin(String name, String mime) {
            Job job = new Job();
            job.file = new File(getCacheDir(), "save-" + UUID.randomUUID());
            job.name = name;
            job.mime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            String id = UUID.randomUUID().toString();
            saving.put(id, job);
            return id;
        }

        @JavascriptInterface
        public void saveChunk(String id, String base64) {
            Job job = saving.get(id);
            if (job == null) return;
            try (FileOutputStream out = new FileOutputStream(job.file, true)) {
                out.write(Base64.decode(base64, Base64.DEFAULT));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void saveEnd(String id) {
            Job job = saving.remove(id);
            if (job == null) return;
            runOnUiThread(() -> { toSave.add(job); nextSave(); });
        }
    }
}
