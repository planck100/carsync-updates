package app.car.downloadcenter;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ApkCacheTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static File apk(File dir, int id, long modified) throws Exception {
        File f = new File(dir,String.format("%064x.apk",id));
        Files.write(f.toPath(), new byte[]{1,2,3}); f.setLastModified(modified); return f;
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("apk-cache-test").toFile();
        long now = System.currentTimeMillis();
        Map<File,ApkCache.Info> info = new HashMap<>();
        File old = apk(dir,1,now), latest = apk(dir,2,now-10000), other = apk(dir,3,now);
        info.put(old,new ApkCache.Info("shield",241)); info.put(latest,new ApkCache.Info("shield",242));
        info.put(other,new ApkCache.Info("carsync",104));
        File stale = new File(dir,"download-old.part"), active = new File(dir,"download-new.part");
        Files.write(stale.toPath(),new byte[]{1}); stale.setLastModified(now-3600001);
        Files.write(active.toPath(),new byte[]{1}); active.setLastModified(now);
        File unrelated = new File(dir,"notes.txt"); Files.write(unrelated.toPath(),new byte[]{1});
        File bad = apk(dir,4,now);
        // An inspection failure must not delete the old APK or other cache entries.
        try { ApkCache.prune(dir,f -> { throw new IOException("read failed"); },now); throw new AssertionError(); }
        catch (IOException expected) { check(old.exists() && latest.exists() && other.exists()); }
        ApkCache.Result r = ApkCache.prune(dir,info::get,now);
        check(r.deleted == 3 && r.failed == 0 && r.bytes == 7);
        check(!old.exists() && latest.exists() && other.exists() && !bad.exists());
        check(!stale.exists() && active.exists() && unrelated.exists());
        // Equal version codes keep the most recently downloaded file.
        File rebuilt = apk(dir,5,now+1000); info.put(rebuilt,new ApkCache.Info("shield",242));
        ApkCache.prune(dir,info::get,now); check(rebuilt.exists() && !latest.exists() && other.exists());
        // A failed/unverified download never replaces the known-good APK.
        File failed = new File(dir,"download-failed.part"); Files.write(failed.toPath(),new byte[]{1});
        ApkCache.prune(dir,info::get,now); check(rebuilt.exists() && failed.exists());
        // Do not follow a hash-named link outside the owned directory.
        File outside = File.createTempFile("keep-outside", ".apk");
        Path link = new File(dir,String.format("%064x.apk",6)).toPath();
        Files.createSymbolicLink(link,outside.toPath());
        ApkCache.prune(dir,info::get,now); check(outside.exists() && Files.isSymbolicLink(link));
        check(ApkCache.prune(new File(dir,"missing"),info::get,now).deleted == 0);
        try (java.util.stream.Stream<Path> stream = Files.walk(dir.toPath())) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException e) { throw new RuntimeException(e); } });
        }
        outside.delete();
        System.out.println("APK cache: migration, package isolation, version ordering, equal-code replacement, failed download, stale temporary files, I/O failure and path isolation PASS");
    }
}
