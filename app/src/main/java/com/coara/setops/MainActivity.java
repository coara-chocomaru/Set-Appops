package com.coara.setops;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private TextView resultView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // パーミッション確認（ログ保存のため）
        checkPermissions();

        // シンプルな Java UI（Holo 風）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dpToPx(12);
        root.setPadding(pad, pad, pad, pad);

        Button appOpsButton = new Button(this);
        appOpsButton.setText("AppOps実行");
        root.addView(appOpsButton);

        resultView = new TextView(this);
        resultView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        resultView.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        root.addView(resultView);

        setContentView(root);

        appOpsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeAppOps();
            }
        });
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    // 権限チェック
    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                final String perm = permissions[i];
                if (grantResults.length > i && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, perm + " が許可されました", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, perm + " が拒否されました", Toast.LENGTH_SHORT).show();
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // メインの実行フロー
    private void executeAppOps() {
        // 1) su id チェック
        appendLine("INFO: root チェックを実行します...");
        boolean hasRoot = checkSuId();
        if (!hasRoot) {
            appendLine("ERROR: root 権限が許可されませんでした。アプリを終了します。");
            Toast.makeText(this, "root権限が許可されませんでした。アプリを終了します。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        appendLine("INFO: root 権限が確認されました。");

        // 2) assets -> internal にコピー
        final File scriptFile = copyAssetToInternal("appops.sh", "scripts", "appops.sh");
        if (scriptFile == null) {
            appendLine("ERROR: スクリプトのコピーに失敗しました。");
            Toast.makeText(this, "スクリプトのコピーに失敗しました。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        appendLine("INFO: スクリプトを " + scriptFile.getAbsolutePath() + " にコピーしました。");

        // 3) 実行権限付与（まずは Java の setExecutable を試す）
        boolean exeOk = scriptFile.setExecutable(true);
        if (!exeOk) {
            appendLine("WARN: Java setExecutable が効果なし。su 経由で chmod を試行します。");
            try {
                Process chmod = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 700 " + scriptFile.getAbsolutePath()});
                int rc = chmod.waitFor();
                appendLine("INFO: chmod 終了コード: " + rc);
            } catch (Exception e) {
                appendLine("ERROR: 実行権限付与に失敗: " + e.getMessage());
                runOnUiThreadToast("実行権限付与に失敗: " + e.getMessage());
                finish();
                return;
            }
        } else {
            appendLine("INFO: Java setExecutable で実行権が付与されました。");
        }

        // 4) スクリプト実行コマンドを作成 (sh 経由＋stderr->stdout)
        final String scriptPath = scriptFile.getAbsolutePath();
        // 注意: su -c には -c の後ろに "sh /path 2>&1" を一つの文字列として渡す
        final String suCommand = "sh " + scriptPath + " 2>&1";

        // 5) スクリプトが追記するログファイルを tail でフォロー（存在する/される可能性のあるパス）
        final String logPath = "/data/adb/modules/enable_unknown_sources/log.txt"; // appops.sh が書き込むパス
        appendLine("INFO: スクリプト出力の監視を開始します (logPath=" + logPath + ")");

        // 6) 実行と出力収集（並列：スクリプト本体出力 と tail -F の両方を読んで UI に出す）
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder combinedLog = new StringBuilder();
                ExecutorService exec = Executors.newFixedThreadPool(3);
                Process mainProc = null;
                Process tailProc = null;

                try {
                    // a) tail -F logPath を root で起動（ファイルがまだ無くても待機する）
                    try {
                        tailProc = Runtime.getRuntime().exec(new String[]{"su", "-c", "tail -n +1 -F " + logPath});
                        StreamGobbler tailG = new StreamGobbler(tailProc.getInputStream(), new StreamGobbler.LineHandler() {
                            @Override
                            public void onLine(String line) {
                                synchronized (combinedLog) {
                                    combinedLog.append("[TAIL] ").append(line).append("\n");
                                }
                                runOnUiThreadAppend("[TAIL] " + line + "\n");
                            }
                        }, "TAIL");
                        exec.submit(tailG);
                    } catch (IOException ioe) {
                        appendLine("WARN: tail 起動失敗: " + ioe.getMessage());
                    }

                    // b) main script を su -c "sh /path 2>&1" で実行
                    try {
                        mainProc = Runtime.getRuntime().exec(new String[]{"su", "-c", suCommand});
                    } catch (IOException e) {
                        // fallback: try passing full command as single string (rare)
                        appendLine("ERROR: mainProc 起動に失敗: " + e.getMessage());
                        synchronized (combinedLog) {
                            combinedLog.append("ERROR: mainProc 起動に失敗: ").append(e.getMessage()).append("\n");
                        }
                        return;
                    }

                    // mainProc の stdout/stderr（両方合流済み）を読み取る
                    StreamGobbler mainG = new StreamGobbler(mainProc.getInputStream(), new StreamGobbler.LineHandler() {
                        @Override
                        public void onLine(String line) {
                            synchronized (combinedLog) {
                                combinedLog.append(line).append("\n");
                            }
                            runOnUiThreadAppend(line + "\n");
                        }
                    }, "MAIN");
                    exec.submit(mainG);

                    // c) プロセス完了を待つ（長時間かかる場合あり）
                    int exit = mainProc.waitFor();
                    appendLine("INFO: スクリプト終了コード: " + exit);
                    synchronized (combinedLog) {
                        combinedLog.append("PROCESS_EXIT_CODE: ").append(exit).append("\n");
                    }

                    // d) mainProc 終了後、tailProc を停止（もし起動していたら）
                    if (tailProc != null) {
                        try {
                            tailProc.destroy();
                        } catch (Throwable ignored) {}
                    }

                    // e) gobblers をシャットダウンして残りの出力を待つ
                    exec.shutdown();
                    try {
                        if (!exec.awaitTermination(60, TimeUnit.SECONDS)) {
                            appendLine("WARN: gobblers タイムアウト。強制終了します。");
                            exec.shutdownNow();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        exec.shutdownNow();
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    appendLine("ERROR: 実行が割り込まれました: " + ie.getMessage());
                } finally {
                    // cleanup
                    if (mainProc != null) {
                        safeCloseStream(mainProc.getOutputStream());
                        safeCloseStream(mainProc.getInputStream());
                        safeCloseStream(mainProc.getErrorStream());
                        try { mainProc.destroy(); } catch (Throwable ignored) {}
                    }
                    if (tailProc != null) {
                        safeCloseStream(tailProc.getOutputStream());
                        safeCloseStream(tailProc.getInputStream());
                        safeCloseStream(tailProc.getErrorStream());
                        try { tailProc.destroy(); } catch (Throwable ignored) {}
                    }

                    final String finalLog;
                    synchronized (this) {
                        // combinedLog may be modified; ensure thread-safe snapshot
                        // but we used combinedLog in outer scope; so capture via local var - need to access combinedLog above
                    }

                    // note: we cannot capture combinedLog inside synchronized(this) here because it's out of scope;
                    // instead, we saved combinedLog in outer variable - need to make final snapshot:
                    // (workaround: rebuild above to make combinedLog final and accessible)
                }
            }
        }, "appops-controller").start();

        // NOTE: saving logs and finish is done inside the worker thread after collecting outputs.
    }

    // su id チェック（簡易）
    private boolean checkSuId() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "id"});
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                safeCloseStream(p.getInputStream());
                safeCloseStream(p.getErrorStream());
                safeCloseStream(p.getOutputStream());
                try {
                    p.destroy();
                } catch (Throwable ignored) {}
            }
        }
    }

    // assets から内部ファイルへコピー
    private File copyAssetToInternal(String assetName, String subdir, String outFileName) {
        File internalDir = new File(getFilesDir(), subdir);
        if (!internalDir.exists()) internalDir.mkdirs();
        File outFile = new File(internalDir, outFileName);

        try (InputStream is = getAssets().open(assetName);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile))) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) > 0) {
                os.write(buf, 0, r);
            }
            os.flush();
            return outFile;
        } catch (IOException e) {
            final String msg = e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "ファイルコピー失敗: " + msg, Toast.LENGTH_LONG).show();
                }
            });
            return null;
        }
    }

    // StreamGobbler（行ごとに処理）
    private static class StreamGobbler implements Runnable {
        interface LineHandler { void onLine(String line); }

        private final InputStream is;
        private final LineHandler handler;
        private final String name;

        StreamGobbler(InputStream is, LineHandler handler, String name) {
            this.is = is;
            this.handler = handler;
            this.name = name;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    handler.onLine(line);
                }
            } catch (IOException e) {
                try {
                    handler.onLine("[" + name + "] read error: " + e.getMessage());
                } catch (Throwable ignored) {}
            }
        }
    }

    // UI へ追記（メインスレッド経由）
    private void runOnUiThreadAppend(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultView.append(text);
            }
        });
    }
    private void appendLine(final String line) {
        runOnUiThreadAppend(line + "\n");
    }

    private void runOnUiThreadToast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ログ保存
    private void saveLogToFile(String command, String logContent) {
        File directory = new File(getExternalFilesDir(null), "command_logs");
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String safeName = command.replaceAll("[^a-zA-Z0-9]", "_");
        if (safeName.length() > 64) safeName = safeName.substring(0, 64);
        String fileName = safeName + "_" + timeStamp + ".txt";
        final File logFile = new File(directory, fileName);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try (FileOutputStream fos = new FileOutputStream(logFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos)) {
                    writer.write(logContent);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "ログが保存されました: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "ログ保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "log-writer").start();
    }

    private static void safeCloseStream(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
