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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private TextView resultView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 保険として currentProcess を保持（将来的にキャンセルが必要な場合に備える）
    private volatile Process currentProcess = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 権限チェック（ログ保存のため）
        checkPermissions();

        // JavaでUIを構築（Holo風・シンプル）
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

    // permission check
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

    // メイン処理
    private void executeAppOps() {
        appendLine("INFO: root チェックを開始します...");
        boolean hasRoot = checkSuId();
        if (!hasRoot) {
            appendLine("ERROR: root 権限が許可されませんでした。終了します。");
            Toast.makeText(this, "root権限が許可されませんでした。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        appendLine("INFO: root 権限確認 OK");

        // assets -> internal にコピー
        final File scriptFile = copyAssetToInternal("appops.sh", "scripts", "appops.sh");
        if (scriptFile == null) {
            appendLine("ERROR: スクリプトコピー失敗");
            Toast.makeText(this, "スクリプトのコピーに失敗しました。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        appendLine("INFO: スクリプトコピー完了: " + scriptFile.getAbsolutePath());

        // 実行権限付与
        boolean exeOk = scriptFile.setExecutable(true);
        if (!exeOk) {
            appendLine("WARN: Java setExecutable 無効。su 経由で chmod を試行します...");
            try {
                Process chmod = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 700 " + scriptFile.getAbsolutePath()});
                int rc = chmod.waitFor();
                appendLine("INFO: chmod 終了コード: " + rc);
            } catch (Exception e) {
                appendLine("ERROR: chmod 失敗: " + e.getMessage());
                runOnUiThreadToast("実行権限付与に失敗: " + e.getMessage());
                finish();
                return;
            }
        } else {
            appendLine("INFO: setExecutable 成功");
        }

        // 実行はワーカースレッドで行う（UIをブロックしない）
        new Thread(new Runnable() {
            @Override
            public void run() {
                // コマンド：su -c "sh /path/to/appops.sh 2>&1"
                final String scriptPath = scriptFile.getAbsolutePath();
                final String suCmdString = "sh " + scriptPath + " 2>&1"; // stderr を stdout にまとめる
                appendLine("INFO: スクリプトを実行します...");

                StringBuilder outputCollector = new StringBuilder();
                ExecutorService gobblers = Executors.newFixedThreadPool(2);
                CountDownLatch latch = new CountDownLatch(2);

                try {
                    // start process
                    currentProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", suCmdString});

                    // stdout（stderrは2>&1でマージ済み）を読み取るゴブラー
                    gobblers.submit(new Runnable() {
                        @Override
                        public void run() {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    synchronized (outputCollector) {
                                        outputCollector.append(line).append("\n");
                                    }
                                    final String l = line;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            resultView.append(l + "\n");
                                        }
                                    });
                                }
                            } catch (IOException e) {
                                synchronized (outputCollector) {
                                    outputCollector.append("ERROR(reader): ").append(e.getMessage()).append("\n");
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        resultView.append("ERROR(reader): " + e.getMessage() + "\n");
                                    }
                                });
                            } finally {
                                latch.countDown();
                            }
                        }
                    });

                    // ただし一部環境では stderr が別ストリームのままの場合に備え、errorStreamも別ゴブラーを立てる
                    gobblers.submit(new Runnable() {
                        @Override
                        public void run() {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    synchronized (outputCollector) {
                                        outputCollector.append("ERR: ").append(line).append("\n");
                                    }
                                    final String l = line;
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            resultView.append("ERR: " + l + "\n");
                                        }
                                    });
                                }
                            } catch (IOException e) {
                                synchronized (outputCollector) {
                                    outputCollector.append("ERROR(errReader): ").append(e.getMessage()).append("\n");
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        resultView.append("ERROR(errReader): " + e.getMessage() + "\n");
                                    }
                                });
                            } finally {
                                latch.countDown();
                            }
                        }
                    });

                    // プロセス完了まで待機（長時間かかる可能性あり）
                    int exitCode = currentProcess.waitFor();
                    appendLine("INFO: スクリプト終了コード: " + exitCode);
                    synchronized (outputCollector) {
                        outputCollector.append("PROCESS_EXIT_CODE: ").append(exitCode).append("\n");
                    }

                    // ゴブラーが終わるまで待つ（最大 60s）
                    if (!latch.await(60, TimeUnit.SECONDS)) {
                        appendLine("WARN: 出力読み取りゴブラーがタイムアウトしました。");
                    }

                } catch (IOException e) {
                    final String msg = e.getMessage();
                    synchronized (outputCollector) {
                        outputCollector.append("ERROR(IOException): ").append(msg).append("\n");
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            resultView.append("ERROR: " + msg + "\n");
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    final String msg = e.getMessage();
                    synchronized (outputCollector) {
                        outputCollector.append("ERROR(Interrupted): ").append(msg).append("\n");
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            resultView.append("ERROR: " + msg + "\n");
                        }
                    });
                } finally {
                    // cleanup
                    if (currentProcess != null) {
                        try {
                            currentProcess.getOutputStream().close();
                        } catch (Exception ignored) {}
                        try {
                            currentProcess.getInputStream().close();
                        } catch (Exception ignored) {}
                        try {
                            currentProcess.getErrorStream().close();
                        } catch (Exception ignored) {}
                        try {
                            currentProcess.destroy();
                        } catch (Throwable ignored) {}
                        currentProcess = null;
                    }

                    gobblers.shutdownNow();

                    final String fullLog;
                    synchronized (outputCollector) {
                        fullLog = outputCollector.toString();
                    }

                    // ログ保存
                    saveLogToFile(scriptPath, fullLog);

                    // 完了通知＋4秒後に終了
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "処理が完了しました。アプリを終了します。", Toast.LENGTH_LONG).show();
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    finish();
                                }
                            }, 4000);
                        }
                    });
                }
            }
        }, "appops-runner").start();
    }

    // su id チェック
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
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    // assets から内部にコピー
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

    // UI append
    private void appendLine(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultView.append(line + "\n");
            }
        });
    }

    private void runOnUiThreadToast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ログ保存（非同期）
    private void saveLogToFile(String command, String logContent) {
        final File directory = new File(getExternalFilesDir(null), "command_logs");
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
        try { c.close(); } catch (IOException ignored) {}
    }
}
