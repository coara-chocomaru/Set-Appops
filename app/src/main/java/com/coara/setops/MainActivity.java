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

        
        checkPermissions();

        
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

    
    private void executeAppOps() {
      
        boolean hasRoot = checkSuId();
        if (!hasRoot) {
            Toast.makeText(this, "root権限が許可されませんでした。アプリを終了します。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        
        final File scriptFile = copyAssetToInternal("appops.sh", "scripts", "appops.sh");
        if (scriptFile == null) {
            Toast.makeText(this, "スクリプトのコピーに失敗しました。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

    
        boolean exeOk = scriptFile.setExecutable(true);
        if (!exeOk) {
            
            try {
                Process chmod = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 700 " + scriptFile.getAbsolutePath()});
                chmod.waitFor();
            } catch (Exception e) {
                
                final String emsg = e.getMessage();
                runOnUiThreadToast("実行権限付与に失敗: " + emsg);
                finish();
                return;
            }
        }

        
        final String cmd = scriptFile.getAbsolutePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                runCommandWithReaders(new String[]{"su", "-c", cmd}, cmd);
            }
        }, "appops-runner").start();
    }

    
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


    private void runCommandWithReaders(String[] command, String commandLabel) {
        Process process = null;
        final StringBuilder logBuilder = new StringBuilder();
        ExecutorService gobblers = Executors.newFixedThreadPool(2);
        try {
            process = Runtime.getRuntime().exec(command);

            
            StreamGobbler outGobbler = new StreamGobbler(process.getInputStream(), new StreamGobbler.LineHandler() {
                @Override
                public void onLine(String line) {
                    synchronized (logBuilder) {
                        logBuilder.append(line).append("\n");
                    }
                    runOnUiThreadAppend(line + "\n");
                }
            }, "OUT");

            StreamGobbler errGobbler = new StreamGobbler(process.getErrorStream(), new StreamGobbler.LineHandler() {
                @Override
                public void onLine(String line) {
                    synchronized (logBuilder) {
                        logBuilder.append("ERROR: ").append(line).append("\n");
                    }
                    runOnUiThreadAppend("ERROR: " + line + "\n");
                }
            }, "ERR");

            gobblers.submit(outGobbler);
            gobblers.submit(errGobbler);

            
            int exitCode = process.waitFor();

            
            gobblers.shutdown();
            gobblers.awaitTermination(2, TimeUnit.SECONDS);

            synchronized (logBuilder) {
                logBuilder.append("PROCESS_EXIT_CODE: ").append(exitCode).append("\n");
            }

        } catch (IOException e) {
            final String msg = e.getMessage();
            synchronized (logBuilder) {
                logBuilder.append("ERROR(IOException): ").append(msg).append("\n");
            }
            runOnUiThreadAppend("ERROR: " + msg + "\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (logBuilder) {
                logBuilder.append("ERROR(Interrupted): ").append(e.getMessage()).append("\n");
            }
            runOnUiThreadAppend("ERROR: " + e.getMessage() + "\n");
        } finally {
            
            if (process != null) {
                try {
                    process.getOutputStream().close();
                } catch (Exception ignored) {}
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {}
                try {
                    process.getErrorStream().close();
                } catch (Exception ignored) {}
                try {
                    process.destroy();
                } catch (Throwable ignored) {}
            }
            
            if (!gobblers.isShutdown()) {
                gobblers.shutdownNow();
            }

            final String fullLog;
            synchronized (logBuilder) {
                fullLog = logBuilder.toString();
            }
            
            saveLogToFile(commandLabel, fullLog);

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
          
                handler.onLine("[" + name + "] read error: " + e.getMessage());
            }
        }
    }

  
    private void runOnUiThreadAppend(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultView.append(text);
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
