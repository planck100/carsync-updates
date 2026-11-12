package app.car.downloadcenter;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Only owns hash-named APKs and abandoned temporary downloads in verified/. */
public final class ApkCache {
    public interface Inspector { Info inspect(File file) throws Exception; }
    public static final class Info {
        final String pkg;
        final long version;
        public Info(String pkg, long version) { this.pkg = pkg; this.version = version; }
    }
    public static final class Result {
        public int deleted, failed;
        public long bytes;
    }
    public static Result prune(File directory, Inspector inspector, long now) throws Exception {
        Result result = new Result();
        File[] files = directory.listFiles();
        if (files == null) {
            if (directory.exists()) throw new IOException("無法讀取安裝檔目錄");
            return result;
        }
        Map<String,File> keep = new HashMap<>();
        Map<String,Long> versions = new HashMap<>();
        Set<File> remove = new HashSet<>();
        // Finish every inspection before removing any APK; I/O errors leave originals intact.
        for (File f : files) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消清理");
            if (!f.isFile() || !f.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) continue;
            String name = f.getName();
            if (name.matches("download-.*\\.part")) {
                if (now - f.lastModified() > 3600000L) remove.add(f);
                continue;
            }
            if (!name.matches("[a-f0-9]{64}\\.apk")) continue;
            Info info = inspector.inspect(f);
            if (info == null || info.pkg == null || info.pkg.isEmpty()) { remove.add(f); continue; }
            File old = keep.get(info.pkg);
            if (old == null) { keep.put(info.pkg,f); versions.put(info.pkg,info.version); continue; }
            long version = versions.get(info.pkg);
            boolean newer = info.version > version || (info.version == version &&
                (f.lastModified() > old.lastModified() || (f.lastModified() == old.lastModified() && name.compareTo(old.getName()) > 0)));
            if (newer) { remove.add(old); keep.put(info.pkg,f); versions.put(info.pkg,info.version); }
            else remove.add(f);
        }
        for (File f : remove) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消清理");
            long bytes = f.length();
            if (f.delete()) { result.deleted++; result.bytes += bytes; }
            else if (f.exists()) result.failed++;
        }
        return result;
    }
}
