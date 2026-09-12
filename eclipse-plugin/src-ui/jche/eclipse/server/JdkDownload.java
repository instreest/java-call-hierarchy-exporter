// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse.server;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 解析に使う JDK が手元に無いときに、取ってきて展開する。
 *
 * <p>取得先は Adoptium（Eclipse Temurin）の API。置き場所は呼び出し側が決める
 * （プラグインの状態フォルダ）。<b>黙って取りに行かない</b>のが約束で、
 * 実行するかどうかは画面で確認してから呼ぶこと（約 200MB ある）。
 *
 * <p>閉域ネットワークでは当然失敗する。そのときは例外の内容をそのまま画面に出して、
 * 「JDK の場所を指定する」へ誘導する。失敗そのものは異常ではない。
 *
 * <p>展開は、zip なら自前で、tar.gz なら {@code tar} コマンドに任せる
 * （Java 8 の標準ライブラリに tar は無い。Linux / macOS には必ずある）。
 */
public final class JdkDownload {

    /** 取得の進み具合を画面へ伝える受け口 */
    public interface Progress {
        /**
         * @param done  受け取った量（バイト）
         * @param total 全体の量。分からなければ -1
         */
        void received(long done, long total);

        /** 中止が要求されているか */
        boolean isCancelled();
    }

    private static final String API = "https://api.adoptium.net/v3/binary/latest/";

    private JdkDownload() {
    }

    /**
     * 取得先の URL を組み立てる。
     *
     * @param feature   取りたい版（25 など）
     * @param osName    {@code os.name} の値
     * @param archName  {@code os.arch} の値
     */
    public static String urlFor(int feature, String osName, String archName) {
        return API + feature + "/ga/" + osOf(osName) + "/" + archOf(archName)
                + "/jdk/hotspot/normal/eclipse";
    }

    /** Adoptium の OS 名 */
    public static String osOf(String osName) {
        String lower = (osName == null) ? "" : osName.toLowerCase(Locale.ENGLISH);
        if (lower.contains("win")) {
            return "windows";
        }
        if (lower.contains("mac") || lower.contains("darwin")) {
            return "mac";
        }
        if (lower.contains("aix")) {
            return "aix";
        }
        return "linux";
    }

    /** Adoptium のアーキテクチャ名 */
    public static String archOf(String archName) {
        String lower = (archName == null) ? "" : archName.toLowerCase(Locale.ENGLISH);
        if (lower.equals("amd64") || lower.equals("x86_64")) {
            return "x64";
        }
        if (lower.equals("aarch64") || lower.equals("arm64")) {
            return "aarch64";
        }
        if (lower.contains("ppc64")) {
            return "ppc64le";
        }
        if (lower.equals("x86") || lower.contains("i386") || lower.contains("i586")) {
            return "x32";
        }
        return lower.isEmpty() ? "x64" : lower;
    }

    /** 取得したファイルの名前（zip か tar.gz か）を OS から決める */
    public static String archiveNameFor(String osName) {
        return "windows".equals(osOf(osName)) ? "jdk.zip" : "jdk.tar.gz";
    }

    /**
     * JDK を取ってきて展開し、java の実行ファイルを返す。
     *
     * @param feature   取りたい版
     * @param targetDir 展開先（空でなくてもよい。中に版ごとのフォルダを作る）
     * @param progress  進み具合の受け口。null 可
     * @return 展開した JDK の java
     */
    public static File install(int feature, File targetDir, Progress progress) throws IOException {
        String osName = System.getProperty("os.name", "");
        File versionDir = new File(targetDir, String.valueOf(feature));
        File archive = new File(targetDir, archiveNameFor(osName));
        if (!versionDir.isDirectory() && !versionDir.mkdirs()) {
            throw new IOException("展開先を作れません: " + versionDir);
        }
        download(urlFor(feature, osName, System.getProperty("os.arch", "")), archive, progress);
        try {
            extract(archive, versionDir, osName);
        } finally {
            if (!archive.delete()) {
                archive.deleteOnExit();
            }
        }
        File java = findJava(versionDir);
        if (java == null) {
            throw new IOException("展開はできましたが、java が見つかりません: " + versionDir);
        }
        if (!java.canExecute() && !java.setExecutable(true)) {
            throw new IOException("java に実行権限を付けられません: " + java);
        }
        int version = JavaLocator.versionOf(java);
        if (version < JavaLocator.MINIMUM) {
            throw new IOException("取得した JDK が動きません（版=" + version + "）: " + java);
        }
        return java;
    }

    private static void download(String url, File target, Progress progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "java-call-hierarchy-exporter");
        int status = connection.getResponseCode();
        if (status / 100 == 3) {
            // Adoptium は実体の置き場所へ飛ばす。プロトコルが変わると自動では追わないので自分で追う
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("取得先が分かりません（" + status + "）: " + url);
            }
            download(location, target, progress);
            return;
        }
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("取得できませんでした（HTTP " + status + "）: " + url);
        }
        long total = connection.getContentLengthLong();
        InputStream in = connection.getInputStream();
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(target));
            try {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (progress != null && progress.isCancelled()) {
                        throw new IOException("取得を中止しました");
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null) {
                        progress.received(done, total);
                    }
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
            connection.disconnect();
        }
    }

    private static void extract(File archive, File targetDir, String osName) throws IOException {
        if (archive.getName().endsWith(".zip")) {
            unzip(archive, targetDir);
            return;
        }
        // tar.gz は外部コマンドに任せる（Java 8 の標準ライブラリに tar は無い）
        Process process = new ProcessBuilder("tar", "-xzf", archive.getAbsolutePath(),
                "-C", targetDir.getAbsolutePath()).redirectErrorStream(true).start();
        try {
            InputStream in = process.getInputStream();
            try {
                byte[] buffer = new byte[4096];
                while (in.read(buffer) > 0) {
                    // 出力は捨てる。失敗したかどうかは終了コードで見る
                }
            } finally {
                in.close();
            }
            if (process.waitFor() != 0) {
                throw new IOException("展開に失敗しました: " + archive);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("展開が中断されました", e);
        }
    }

    private static void unzip(File archive, File targetDir) throws IOException {
        ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(archive));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());
                // zip の中の相対パスで外へ出られないようにする
                if (!file.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("展開先の外を指す項目があります: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    out.close();
                }
            }
        } finally {
            zip.close();
        }
    }

    /** 展開したフォルダの中から java を探す（配布物は中に1段フォルダを作る） */
    public static File findJava(File dir) {
        File direct = JavaLocator.executableIn(dir);
        if (direct.isFile()) {
            return direct;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            File found = findJava(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
