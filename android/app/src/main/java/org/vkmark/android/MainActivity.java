/*
 * Copyright © 2026 vkmark contributors
 *
 * This file is part of vkmark.
 *
 * vkmark is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * vkmark is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with vkmark. If not, see <http://www.gnu.org/licenses/>.
 */

package org.vkmark.android;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runs vkmark on a full screen surface.
 *
 * The benchmarks start a few seconds after the activity has come to the
 * front in full screen, and when they finish the results are saved to a
 * text file in the Documents folder.
 *
 * The usual vkmark command line options can be passed in the "args"
 * string extra of the launching intent, e.g.:
 *
 *   adb shell am start -n org.vkmark.android/.MainActivity \
 *       --es args "'-b cube -b shading:shading=phong'"
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    static {
        System.loadLibrary("vkmark-android");
    }

    private static final String EXTRA_ARGS = "args";
    private static final String EXTRA_FULLSCREEN_REQUESTED = "fullscreen-requested";
    private static final int REQUEST_STORAGE_PERMISSION = 1;
    // How long to wait in the foreground before starting the benchmarks,
    // to let the system settle after the launch
    private static final int START_DELAY_SECONDS = 5;

    private final StringBuilder log = new StringBuilder();
    private SurfaceView surfaceView;
    private TextView statusView;
    private View resultsView;
    private TextView scoreView;
    private TextView savedView;
    private TextView logView;
    private boolean resumed = false;
    private boolean surfaceReady = false;
    // Whether the benchmarks should run when the surface is ready
    private boolean runRequested = false;
    private boolean running = false;
    private boolean finished = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean countingDown = false;
    private int countdown;
    private final Runnable countdownTick = this::onCountdownTick;

    private native void nativeStart(Surface surface, AssetManager assets, String[] args);
    private native void nativeStop();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (relaunchInFullscreen()) {
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // Draw behind the system bars, so that the surface covers the whole
        // screen even while the bars are (transiently) visible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        setContentView(createContentView());
        surfaceView.getHolder().addCallback(this);
        hideSystemBars();

        requestRun();
    }

    private View createContentView() {
        int pad = dp(12);

        FrameLayout root = new FrameLayout(this);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Shows the latest log lines while the benchmarks are running
        statusView = new TextView(this);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setTextColor(Color.WHITE);
        statusView.setShadowLayer(4, 0, 0, Color.BLACK);
        statusView.setPadding(pad, pad, pad, pad);
        root.addView(statusView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START));

        // Shows the full log and the score when the benchmarks have finished
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setGravity(Gravity.CENTER_HORIZONTAL);
        results.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);

        scoreView = new TextView(this);
        scoreView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        scoreView.setTextColor(Color.WHITE);
        scoreView.setTypeface(Typeface.DEFAULT_BOLD);
        results.addView(scoreView);

        savedView = new TextView(this);
        savedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        savedView.setTextColor(0xFFCCCCCC);
        savedView.setGravity(Gravity.CENTER);
        results.addView(savedView);

        Button runAgain = new Button(this);
        runAgain.setText(R.string.run_again);
        runAgain.setOnClickListener(v -> {
            finished = false;
            requestRun();
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = pad;
        buttonParams.bottomMargin = pad;
        results.addView(runAgain, buttonParams);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextColor(Color.WHITE);
        logView.setTextIsSelectable(true);
        results.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xE6000000);
        scroll.setFillViewport(true);
        scroll.setVisibility(View.GONE);
        scroll.addView(results);
        root.addView(scroll, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        resultsView = scroll;

        return root;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A new launch (e.g., with different arguments) starts over
        setIntent(intent);
        finished = false;
        requestRun();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && surfaceView != null) {
            hideSystemBars();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (surfaceView != null) {
            hideSystemBars();
            maybeBeginCountdown();
        }
    }

    @Override
    protected void onPause() {
        // Start counting again when we are back in front
        resumed = false;
        cancelCountdown();
        super.onPause();
    }

    /**
     * If the activity has been placed in a window or a split screen, starts
     * it again asking for the whole screen.
     *
     * @return whether the activity is being relaunched
     */
    private boolean relaunchInFullscreen() {
        if (!isInMultiWindowMode()
                || getIntent().getBooleanExtra(EXTRA_FULLSCREEN_REQUESTED, false)) {
            return false;
        }

        Intent intent = new Intent(getIntent());
        intent.setClass(this, MainActivity.class);
        intent.putExtra(EXTRA_FULLSCREEN_REQUESTED, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // There is no public API to ask for the fullscreen windowing mode,
        // but the platform reads it from this activity option
        // (WINDOWING_MODE_FULLSCREEN == 1).
        Bundle options = ActivityOptions.makeBasic().toBundle();
        options.putInt("android.activity.windowingMode", 1);

        // The windowing mode belongs to the task, so remove the current task
        // first to get the activity started in a new one.
        finishAndRemoveTask();
        try {
            getApplicationContext().startActivity(intent, options);
        } catch (RuntimeException e) {
            // Try again without asking for anything special
            intent.putExtra(EXTRA_FULLSCREEN_REQUESTED, true);
            getApplicationContext().startActivity(intent);
        }

        return true;
    }

    @SuppressWarnings("deprecation")
    private void bringToFront() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        try {
            am.moveTaskToFront(getTaskId(), 0);
        } catch (RuntimeException e) {
            // We did our best
        }
    }

    /**
     * Starts the benchmarks after a short delay, once the activity is in
     * front and has a surface to render to.
     */
    private void maybeBeginCountdown() {
        if (countingDown || running || finished || !runRequested || !surfaceReady || !resumed) {
            return;
        }

        bringToFront();
        hideSystemBars();

        countingDown = true;
        countdown = START_DELAY_SECONDS;
        resultsView.setVisibility(View.GONE);
        statusView.setVisibility(View.VISIBLE);
        onCountdownTick();
    }

    private void onCountdownTick() {
        if (!countingDown) {
            return;
        }
        if (countdown <= 0) {
            countingDown = false;
            start();
            return;
        }
        statusView.setText(getString(R.string.starting_in, countdown));
        countdown--;
        handler.postDelayed(countdownTick, 1000);
    }

    private void cancelCountdown() {
        countingDown = false;
        handler.removeCallbacks(countdownTick);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Restart running benchmarks, since the swapchain is tied to
        // the surface and its size.
        stop();
        surfaceReady = true;
        maybeBeginCountdown();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // This blocks until the native side has stopped using the surface
        stop();
        cancelCountdown();
        surfaceReady = false;
    }

    private String[] getArgs() {
        String args = getIntent().getStringExtra(EXTRA_ARGS);
        if (args == null || args.trim().isEmpty()) {
            return new String[0];
        }
        return args.trim().split("\\s+");
    }

    /** Runs the benchmarks as soon as possible. */
    private void requestRun() {
        // Before Android 10 a permission is needed to save the results
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE_PERMISSION);
            return;
        }
        run();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Run even without the permission; the results just won't be saved
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            run();
        }
    }

    private void run() {
        stop();
        cancelCountdown();
        runRequested = true;
        maybeBeginCountdown();
    }

    private void start() {
        log.setLength(0);
        statusView.setText("");
        statusView.setVisibility(View.VISIBLE);
        resultsView.setVisibility(View.GONE);
        running = true;
        nativeStart(surfaceView.getHolder().getSurface(), getAssets(), getArgs());
    }

    private void stop() {
        if (running) {
            running = false;
            nativeStop();
        }
    }

    /**
     * Saves the results to Documents/vkmark-yymmdd-hhmm.txt.
     *
     * @return the name of the saved file
     */
    private String saveResults(String results) throws IOException {
        String name = "vkmark-"
            + new SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(new Date()) + ".txt";
        byte[] data = results.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
            Uri uri = getContentResolver().insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) {
                throw new IOException("Failed to create " + name);
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Failed to open " + name);
                }
                out.write(data);
            } catch (IOException | RuntimeException e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
        } else {
            @SuppressWarnings("deprecation")
            File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Failed to create " + dir);
            }
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(data);
            }
        }

        return name;
    }

    private void updateStatus() {
        // Show the last couple of lines of the log
        int end = log.length();
        while (end > 0 && log.charAt(end - 1) == '\n') {
            end--;
        }
        int start = end;
        for (int lines = 0; lines < 2 && start > 0; lines++) {
            start = log.lastIndexOf("\n", start - 1);
            if (start < 0) {
                start = 0;
                break;
            }
        }
        statusView.setText(log.substring(start, end).trim());
    }

    /** Called from native code, on an arbitrary thread. */
    @SuppressWarnings("unused")
    private void onNativeOutput(byte[] data) {
        final String text = new String(data, StandardCharsets.UTF_8);
        runOnUiThread(() -> {
            log.append(text);
            if (resultsView.getVisibility() == View.VISIBLE) {
                logView.setText(log);
            } else {
                updateStatus();
            }
        });
    }

    /** Called from native code, on an arbitrary thread. */
    @SuppressWarnings("unused")
    private void onNativeFinished(final int score) {
        runOnUiThread(() -> {
            if (!running) {
                return;
            }
            finished = true;
            if (score >= 0) {
                scoreView.setText(getString(R.string.score, score));
            } else {
                scoreView.setText(R.string.no_score);
            }
            try {
                savedView.setText(getString(R.string.saved, saveResults(log.toString())));
            } catch (IOException | RuntimeException e) {
                // If the results can't be saved, just carry on without the file
                savedView.setText("");
            }
            logView.setText(log);
            statusView.setVisibility(View.GONE);
            resultsView.setVisibility(View.VISIBLE);
        });
    }
}
