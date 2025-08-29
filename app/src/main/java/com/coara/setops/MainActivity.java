package com.coara.setops;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private TextView resultView;
    private boolean isRootAvailable = false;
    private Process currentProcess;
    private Handler timeoutHandler;
    private Runnable timeoutRunnable;
    private static final long OUTPUT_TIMEOUT = 5000; // 5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_Holo);
        super.onCreate(savedInstanceState);

        // Create layout programmatically
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setGravity(Gravity.CENTER);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Button in the center
        Button appopsButton = new Button(this);
        appopsButton.setText("appops");
        appopsButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        appopsButton.setOnClickListener(this::onAppopsButtonClick);

        // Result view
        resultView = new TextView(this);
        resultView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f)); // Weighted to take remaining space
        resultView.setTextIsSelectable(true);

        // ScrollView for result
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(resultView);

        mainLayout.addView(appopsButton);
        mainLayout.addView(scrollView);

        setContentView(mainLayout);

        // Check permissions
        checkPermissions();

        // Initialize handler for timeout
        timeoutHandler = new Handler(Looper.getMainLooper());
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
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, permissions[i] + " 権限が許可されました", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, permissions[i] + " 権限が拒否されました", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void onAppopsButtonClick(View view) {
        if (!isRootAvailable) {
            // First, check root with su -c id
            checkRootAccess();
        } else {
            // Already have root, execute appops.sh
            executeAppopsScript();
        }
    }

    private void checkRootAccess() {
        resultView.setText("");
        String command = "su -c id";
        executeCommand(command, true); // Check mode
    }

    private void executeAppopsScript() {
        resultView.setText("");
        File scriptFile = copyAssetToInternalStorage("appops.sh");
        if (scriptFile != null && scriptFile.setExecutable(true)) {
            String command = "su -c " + scriptFile.getAbsolutePath();
            executeCommand(command, false); // Normal execution
        } else {
            resultView.setText("ERROR: appops.shのコピーまたは実行権限付与に失敗しました。");
        }
    }

    private File copyAssetToInternalStorage(String assetName) {
        File directory = new File(getFilesDir(), "scripts");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "ディレクトリ作成に失敗しました。", Toast.LENGTH_SHORT).show();
            return null;
        }

        try (InputStream inputStream = getAssets().open(assetName)) {
            File destFile = new File(directory, assetName);
            try (FileOutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            return destFile;
        } catch (IOException e) {
            Toast.makeText(this, "ファイルのコピーに失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void executeCommand(String command, boolean isCheckMode) {
        resultView.setText("");
        try {
            currentProcess = Runtime.getRuntime().exec(command);

            Executors.newSingleThreadExecutor().submit(() -> {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
                     BufferedReader errorReader = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()))) {

                    // Initial timeout reset
                    runOnUiThread(() -> resetTimeoutRunnable(command, output, isCheckMode));

                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        final String finalLine = line;
                        runOnUiThread(() -> resultView.append(finalLine + "\n"));
                        runOnUiThread(() -> resetTimeoutRunnable(command, output, isCheckMode));
                    }

                    while ((line = errorReader.readLine()) != null) {
                        output.append("ERROR: ").append(line).append("\n");
                        final String finalErrorLine = line;
                        runOnUiThread(() -> resultView.append("ERROR: " + finalErrorLine + "\n"));
                        runOnUiThread(() -> resetTimeoutRunnable(command, output, isCheckMode));
                    }

                    // Remove timeout after successful read
                    runOnUiThread(() -> {
                        if (timeoutRunnable != null) {
                            timeoutHandler.removeCallbacks(timeoutRunnable);
                        }
                    });

                    int exitValue = currentProcess.waitFor();
                    if (isCheckMode) {
                        handleRootCheckResult(exitValue, output.toString());
                    } else {
                        saveLogToFile(command, output.toString());
                    }

                } catch (IOException e) {
                    runOnUiThread(() -> resultView.append("ERROR: " + e.getMessage() + "\n"));
                    if (!isCheckMode) {
                        saveLogToFile(command, output.toString());
                    } else {
                        handleRootCheckResult(-1, output.toString()); // Treat as failure
                    }
                } catch (InterruptedException e) {
                    runOnUiThread(() -> resultView.append("ERROR: " + e.getMessage() + "\n"));
                } finally {
                    // Clean up timeout
                    runOnUiThread(() -> {
                        if (timeoutRunnable != null) {
                            timeoutHandler.removeCallbacks(timeoutRunnable);
                        }
                    });
                }
            });

        } catch (IOException e) {
            resultView.setText("ERROR: " + e.getMessage());
        }
    }

    private void resetTimeoutRunnable(String command, StringBuilder output, boolean isCheckMode) {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = () -> {
            if (currentProcess != null && currentProcess.isAlive()) {
                currentProcess.destroy();
                resultView.append("INFO: 出力が停止したためプロセスを終了しました\n");
                if (!isCheckMode) {
                    saveLogToFile(command, output.toString());
                } else {
                    handleRootCheckResult(-1, output.toString()); // Failure on timeout
                }
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, OUTPUT_TIMEOUT);
    }

    private void handleRootCheckResult(int exitValue, String output) {
        runOnUiThread(() -> {
            if (exitValue == 0 && output.contains("uid=0(root)")) {
                isRootAvailable = true;
                Toast.makeText(this, "Rootアクセスが許可されました。", Toast.LENGTH_SHORT).show();
                resultView.append("Root confirmed. Now executing appops.sh...\n");
                executeAppopsScript();
            } else {
                Toast.makeText(this, "Rootアクセスが拒否されました。アプリを終了します。", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void saveLogToFile(String command, String logContent) {
        File directory = new File(getExternalFilesDir(null), "command_logs");
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = command.replaceAll("[^a-zA-Z0-9]", "_") + "_" + timeStamp + ".txt";
        File logFile = new File(directory, fileName);

        try (FileOutputStream fos = new FileOutputStream(logFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos)) {
            writer.write(logContent);
            runOnUiThread(() -> Toast.makeText(this, "ログが保存されました: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "ログ保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }
}
