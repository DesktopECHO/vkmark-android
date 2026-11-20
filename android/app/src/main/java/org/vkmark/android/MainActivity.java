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

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
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

import java.nio.charset.StandardCharsets;

/**
 * Runs vkmark on a full screen surface.
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

    private final StringBuilder log = new StringBuilder();
    private SurfaceView surfaceView;
    private TextView statusView;
    private View resultsView;
    private TextView scoreView;
    private TextView logView;
    private boolean surfaceReady = false;
    private boolean running = false;
    private boolean finished = false;

    private native void nativeStart(Surface surface, AssetManager assets, String[] args);
    private native void nativeStop();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        setContentView(createContentView());
        surfaceView.getHolder().addCallback(this);
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

        Button runAgain = new Button(this);
        runAgain.setText(R.string.run_again);
        runAgain.setOnClickListener(v -> runAgain());
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
        // A new launch (e.g., with different arguments) restarts the benchmarks
        setIntent(intent);
        runAgain();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
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
        // Restart interrupted benchmarks, since the swapchain is tied to
        // the surface and its size.
        stop();
        surfaceReady = true;
        if (!finished) {
            start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // This blocks until the native side has stopped using the surface
        stop();
        surfaceReady = false;
    }

    private String[] getArgs() {
        String args = getIntent().getStringExtra(EXTRA_ARGS);
        if (args == null || args.trim().isEmpty()) {
            return new String[0];
        }
        return args.trim().split("\\s+");
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

    private void runAgain() {
        stop();
        finished = false;
        if (surfaceReady) {
            start();
        }
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
            logView.setText(log);
            statusView.setVisibility(View.GONE);
            resultsView.setVisibility(View.VISIBLE);
        });
    }
}
