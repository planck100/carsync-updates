package app.car.downloadcenter;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final String CATALOG = "https://raw.githubusercontent.com/planck100/carsync-updates/main/download-center/catalog.json";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private LinearLayout root, list;
    private TextView status;
    private Button refresh;
    private boolean busy;
    private Entry pending;
    private File pendingFile;
    private SharedPreferences prefs;

    static final class Entry {
        String id, name, pkg, version, url, hash, notes, error = "";
        long bytes, code;
        Bitmap icon;
        boolean active;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("center", MODE_PRIVATE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(16, 24, 36));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                dp(12) + insets.getSystemWindowInsetTop(),
                dp(20) + insets.getSystemWindowInsetRight(),
                dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = text("車機下載中心", 27);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView hint = text("每個 App 保留最新安裝檔，完成後由你確認安裝", 15);
        root.addView(hint);
        refresh = new Button(this);
        refresh.setText("檢查更新");
        refresh.setMinHeight(dp(52));
        refresh.setOnClickListener(v -> load());
        root.addView(refresh);
        status = text("準備讀取清單…", 15);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }
    private void ui(Runnable r) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) r.run(); });
    }
    private void setBusy(boolean value) {
        busy = value; refresh.setEnabled(!value);
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private static String str(JSONObject j, String key) {
        return j.isNull(key) ? "" : j.optString(key, "");
    }
    private JSONObject fetchJson(String address) throws Exception {
        HttpURLConnection c = connect(address);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                if (out.size() + n > 2 * 1024 * 1024) throw new IOException("清單過大");
                out.write(b, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally { c.disconnect(); }
    }
    private HttpURLConnection connect(String address) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("下載來源必須使用 HTTPS");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "CarDownloadCenter/0.3");
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = c.getHeaderField("Location"); c.disconnect();
                if (next == null) throw new IOException("下載網址重新導向失敗");
                url = new URL(url, next);
            } else if (code >= 200 && code < 300) return c;
            else { c.disconnect(); throw new IOException("伺服器回應 " + code); }
        }
        throw new IOException("重新導向次數過多");
    }
    private void validateCatalog(JSONObject catalog) throws Exception {
        if (catalog.optInt("schemaVersion") != 1) throw new IOException("清單格式需要新版下載中心");
        JSONArray apps = catalog.getJSONArray("apps");
        if (apps.length() > 100) throw new IOException("App 清單過大");
        HashSet<String> ids = new HashSet<>();
        for (int i=0; i<apps.length(); i++) {
            JSONObject a = apps.getJSONObject(i);
            String id = a.getString("id");
            if (!id.matches("[a-zA-Z0-9_-]{1,80}") || !ids.add(id) || str(a,"name").isEmpty())
                throw new IOException("清單項目不完整或重複");
        }
    }
    private void load() {
        if (busy) return;
        setBusy(true); status.setText("正在讀取線上清單…");
        worker.execute(() -> {
            ArrayList<Entry> result = new ArrayList<>();
            String message;
            String cleanup = cleanupCache();
            int unavailable = 0;
            try {
                JSONObject catalog;
                boolean offline = false;
                try {
                    catalog = fetchJson(CATALOG); validateCatalog(catalog);
                    prefs.edit().putString("catalog", catalog.toString()).apply();
                } catch (Exception networkError) {
                    String cache = prefs.getString("catalog", "");
                    if (cache.isEmpty()) {
                        try (InputStream in = getAssets().open("catalog.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[8192]; int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            cache = out.toString("UTF-8");
                        }
                    }
                    catalog = new JSONObject(cache); validateCatalog(catalog); offline = true;
                }
                JSONArray apps = catalog.getJSONArray("apps");
                for (int i=0; i<apps.length(); i++) {
                    JSONObject item = apps.getJSONObject(i);
                    Entry e = new Entry();
                    e.id = item.getString("id"); e.name = item.getString("name");
                    e.pkg = str(item,"packageName");
                    e.active = "active".equals(item.optString("status"));
                    if (!e.active) {
                        e.error = item.optString("unavailableReason", "尚未提供下載");
                    } else {
                        try {
                            JSONObject source = item.getJSONObject("source");
                            if (!"json".equals(source.optString("type")) && !"apk".equals(source.optString("type"))) throw new IOException("此下載來源需要新版下載中心");
                            JSONObject release = item;
                            JSONObject fields = null;
                            if ("json".equals(source.optString("type"))) {
                                fields = source.getJSONObject("fields");
                                String cacheKey = "release_" + source.getString("url");
                                if (offline) {
                                    release = new JSONObject(prefs.getString(cacheKey, ""));
                                } else {
                                    release = fetchJson(source.getString("url"));
                                    prefs.edit().putString(cacheKey, release.toString()).apply();
                                }
                            }
                            String remotePkg = str(release, fields == null ? "packageName" : fields.optString("packageName","package"));
                            if (!remotePkg.isEmpty() && !e.pkg.equals(remotePkg)) throw new IOException("套件名稱與清單不符");
                            e.version = str(release, fields == null ? "versionName" : fields.optString("versionName","version"));
                            e.url = fields == null ? str(source,"url") : str(release, fields.optString("apkUrl","apkUrl"));
                            e.hash = str(release, fields == null ? "sha256" : fields.optString("sha256","sha256")).toLowerCase(Locale.ROOT);
                            e.notes = str(release, fields == null ? "releaseNotes" : fields.optString("releaseNotes","notes"));
                            e.bytes = release.optLong(fields == null ? "sizeBytes" : fields.optString("sizeBytes","size"), 0);
                            e.code = release.optLong(fields == null ? "versionCode" : fields.optString("versionCode","versionCode"), 0);
                            if (e.pkg.isEmpty() || e.version.isEmpty() || !e.url.startsWith("https://") || !e.hash.matches("[a-f0-9]{64}"))
                                throw new IOException("下載資料不完整");
                            if (offline) e.error = "離線快取：連線後再確認是否有新版";
                        } catch (Exception ex) {
                            e.active = false; e.error = "無法取得版本：" + brief(ex);
                        }
                    }
                    if (!offline && !str(item,"iconUrl").isEmpty()) {
                        try { e.icon = fetchIcon(item.getString("iconUrl")); } catch (Exception ignored) { }
                    }
                    if (!e.active) unavailable++;
                    result.add(e);
                }
                message = offline ? "目前顯示上次清單；網路恢復後請重新整理" : "清單已更新，共 " + result.size() + " 個 App" + (unavailable > 0 ? "；其中 " + unavailable + " 個目前無法下載" : "");
            } catch (Exception ex) { message = "清單讀取失敗：" + brief(ex) + "；請檢查網路後重試"; }
            final String finalMessage = message + cleanup;
            ui(() -> { entries.clear(); entries.addAll(result); setBusy(false); render(); status.setText(finalMessage); });
        });
    }
    private Bitmap fetchIcon(String url) throws Exception {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > 512 * 1024) throw new IOException("圖示過大");
                out.write(b,0,n);
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data,0,data.length,o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = Math.max(1, Math.max(o.outWidth,o.outHeight) / 128);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data,0,data.length,o);
        } finally { c.disconnect(); }
    }
    private PackageInfo installed(String pkg) {
        try { return getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNATURES); }
        catch (PackageManager.NameNotFoundException ex) { return null; }
    }
    private static long code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }
    private File downloaded(Entry e) {
        if (e.hash == null || !e.hash.matches("[a-f0-9]{64}")) return null;
        File f = new File(new File(getFilesDir(),"verified"), e.hash + ".apk");
        return f.isFile() ? f : null;
    }
    private void render() {
        list.removeAllViews();
        for (Entry e : entries) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16),dp(12),dp(16),dp(12));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(30,43,61)); bg.setCornerRadius(dp(12));
            card.setBackground(bg);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,dp(10),0,dp(4));
            list.addView(card, cp);
            LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
            if (e.icon == null) {
                TextView badge = text("shield-bridge".equals(e.id) ? "盾" : "car-launcher".equals(e.id) ? "車" : "C", 26);
                badge.setGravity(Gravity.CENTER); badge.setTypeface(null, Typeface.BOLD);
                GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
                circle.setColor("shield-bridge".equals(e.id) ? Color.rgb(28,125,110) : "car-launcher".equals(e.id) ? Color.rgb(39,101,190) : Color.rgb(114,74,180));
                badge.setBackground(circle);
                LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(dp(44),dp(44));
                badgeLayout.setMargins(0,0,dp(12),0); heading.addView(badge,badgeLayout);
            }
            if (e.icon != null) {
                ImageView image = new ImageView(this); image.setImageBitmap(e.icon);
                heading.addView(image, new LinearLayout.LayoutParams(dp(44),dp(44)));
            }
            TextView name = text(e.name,23); name.setTypeface(null,Typeface.BOLD);
            heading.addView(name); card.addView(heading);
            PackageInfo local = e.pkg == null || e.pkg.isEmpty() ? null : installed(e.pkg);
            String localText = local == null ? (e.pkg == null || e.pkg.isEmpty() ? "套件資料待補齊" : "尚未安裝") : "已安裝：" + local.versionName;
            card.addView(text(localText,16));
            if (e.version != null) {
                String label = "可下載：" + e.version;
                if (local != null && (e.code > 0 ? code(local) >= e.code : e.version.equals(local.versionName)))
                    label += " · 已是相同或較新版本";
                card.addView(text(label,16));
            }
            if (!e.error.isEmpty()) card.addView(text(e.error,15));
            if (e.notes != null && !e.notes.isEmpty()) {
                TextView notes = text(e.notes,14); notes.setMaxLines(3);
                notes.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(e.name + " 更新說明").setMessage(e.notes).setPositiveButton("關閉",null).show());
                card.addView(notes);
            }
            Button action = new Button(this); action.setMinHeight(dp(54));
            File file = downloaded(e);
            action.setText(!e.active ? "尚未提供下載" : file != null ? "確認安裝" : "下載 APK");
            action.setEnabled(e.active && !busy);
            action.setOnClickListener(v -> {
                if (busy) return;
                new AlertDialog.Builder(this).setTitle(e.name)
                    .setMessage(file == null ? "下載 " + e.version + "？下載後會檢查檔案，再由你確認安裝。" : "開啟 Android 安裝確認？")
                    .setNegativeButton("取消",null).setPositiveButton("繼續",(d,w) -> {
                        if (file == null) download(e); else verifyForInstall(e,file);
                    }).show();
            });
            card.addView(action);
        }
    }
    private static String brief(Exception ex) {
        String m = ex.getMessage(); return m == null ? ex.getClass().getSimpleName() : m;
    }
    private void download(Entry e) {
        if (busy) return;
        setBusy(true); render(); status.setText("正在下載 " + e.name + "…請保持此畫面開啟");
        worker.execute(() -> {
            File partial = null;
            try {
                File dir = new File(getFilesDir(),"verified");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("無法建立下載目錄");
                partial = File.createTempFile("download-", ".part", dir);
                HttpURLConnection c = connect(e.url);
                long actual = 0, lastProgress = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    long total = c.getContentLength();
                    long expected = e.bytes > 0 ? e.bytes : total;
                    long limit = 1024L * 1024 * 1024;
                    if (expected > limit) throw new IOException("APK 超過 1 GB 上限");
                    if (expected > 0 && dir.getUsableSpace() < expected + 20L * 1024 * 1024) throw new IOException("儲存空間不足");
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消，請重新下載");
                        actual += n;
                        if (actual > limit) throw new IOException("APK 超過大小上限");
                        out.write(buf,0,n);
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastProgress > 300) {
                            lastProgress = now;
                            String p = expected > 0 ? (actual * 100 / expected) + "%" : (actual / 1024) + " KB";
                            ui(() -> status.setText("下載 " + e.name + "：" + p));
                        }
                    }
                    if ((e.bytes > 0 && actual != e.bytes) || (total > 0 && actual != total)) throw new IOException("下載未完成，請重試");
                } finally { c.disconnect(); }
                verify(e, partial);
                File target = new File(dir,e.hash + ".apk");
                if (target.exists()) { verify(e,target); }
                else if (!partial.renameTo(target)) throw new IOException("無法儲存 APK");
                String cleanup = cleanupCache();
                if (!target.isFile()) throw new IOException("本機已保留較新安裝檔，請重新檢查更新" + cleanup);
                pending = e; pendingFile = target;
                prefs.edit().putString("pendingId",e.id).putString("pendingHash",e.hash).apply();
                ui(() -> { setBusy(false); render(); status.setText(e.name + " 下載與檔案驗證完成，請確認安裝" + cleanup); installPending(); });
            } catch (Exception ex) {
                String error = brief(ex);
                ui(() -> { setBusy(false); render(); status.setText("下載失敗：" + error); });
            } finally { if (partial != null) partial.delete(); }
        });
    }
    private String cleanupCache() {
        try {
            ApkCache.Result result = ApkCache.prune(new File(getFilesDir(),"verified"), file -> {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消清理");
                        digest.update(buffer,0,count);
                    }
                }
                if (!file.getName().equals(hex(digest.digest()) + ".apk")) return null;
                PackageInfo apk = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),0);
                return apk == null ? null : new ApkCache.Info(apk.packageName,code(apk));
            },System.currentTimeMillis());
            return (result.deleted > 0 ? "；已清理 " + result.deleted + " 個舊／損壞／暫存檔" : "") +
                (result.failed > 0 ? "；部分舊檔無法刪除，下次開啟重試" : "");
        } catch (Exception ex) { return "；自動清理未完成，下次開啟重試"; }
    }
    private static String hex(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (byte b : data) s.append(String.format(Locale.ROOT,"%02x",b & 255));
        return s.toString();
    }
    private void verify(Entry e, File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                digest.update(buf,0,n);
            }
        }
        if (!e.hash.equals(hex(digest.digest()))) throw new IOException("檔案校驗不符，未開啟安裝");
        PackageInfo apk = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (apk == null || !e.pkg.equals(apk.packageName)) throw new IOException("APK 套件名稱不符");
        if (!e.version.equals(apk.versionName)) throw new IOException("APK 版本與清單不符");
        if (e.code > 0 && code(apk) != e.code) throw new IOException("APK 版本代碼與清單不符");
        PackageInfo local = installed(e.pkg);
        if (local != null) {
            if (code(apk) < code(local)) throw new IOException("已安裝較新版本，不進行降版");
            if (apk.signatures == null || local.signatures == null ||
                !new HashSet<>(Arrays.asList(apk.signatures)).equals(new HashSet<>(Arrays.asList(local.signatures))))
                throw new IOException("與已安裝 App 簽章不同，無法覆蓋更新；請保留原 App");
        }
    }
    private void verifyForInstall(Entry e, File file) {
        if (busy) return;
        setBusy(true); render(); status.setText("正在驗證 APK…");
        worker.execute(() -> {
            try {
                verify(e,file);
                pending = e; pendingFile = file;
                prefs.edit().putString("pendingId",e.id).putString("pendingHash",e.hash).apply();
                ui(() -> { setBusy(false); render(); installPending(); });
            } catch (Exception ex) {
                String error = brief(ex);
                ui(() -> { setBusy(false); render(); status.setText("無法安裝：" + error); });
            }
        });
    }
    private void installPending() {
        if (pendingFile == null || !pendingFile.isFile()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this).setTitle("允許安裝 App")
                    .setMessage("請在接下來的系統設定允許「車機下載中心」安裝未知應用程式。返回後再按「確認安裝」。")
                    .setNegativeButton("稍後",null)
                    .setPositiveButton("開啟設定",(d,w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:" + getPackageName()))); }
                        catch (Exception ex) { status.setText("請至系統設定允許此 App 安裝未知應用程式"); }
                    }).show();
                return;
            }
            Uri uri = Uri.parse("content://" + getPackageName() + ".apk/" + pendingFile.getName());
            Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive");
            install.setClipData(ClipData.newRawUri("APK",uri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            status.setText("請在系統畫面確認；返回後會重新讀取已安裝版本");
        } catch (Exception ex) { status.setText("無法開啟安裝：" + brief(ex)); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (list != null && !busy) render();
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
