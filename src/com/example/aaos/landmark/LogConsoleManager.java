package com.example.aaos.landmark;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * LogConsoleManager
 *
 * Two responsibilities:
 *   1. Streams filtered logcat lines into the TextView console.
 *   2. Provides log(tag, message) and logToUI(message) APIs so Java code can
 *      write directly to the console without waiting for logcat delivery.
 *
 * Console is capped at MAX_LINES to prevent unbounded TextView growth during
 * long simulation runs. Both write paths (logcat reader and direct log()) go
 * through the same appendLine() method so the cap is always enforced.
 */
public class LogConsoleManager {

    private static final String TAG = "LogConsoleManager";

    /**
     * Logcat filter — all Verbose+ lines from relevant app tags.
     * "*:S" silences everything else.
     */
    private static final String LOGCAT_FILTER =
            "logcat" +
            " LandmarkCacheApp:V" +
            " LandmarkCacheJNI:V" +
            " LogConsoleManager:V" +
            " AIPromptManager:V" +
            " JsonDiscoveryProvider:V" +
            " GridZoneSync:V" +
            " *:S";

    /** Maximum lines retained in the TextView before oldest are trimmed. */
    private static final int MAX_LINES = 400;

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final SimpleDateFormat TIME_FMT_SHORT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ── Injectable factory for testing ────────────────────────────────────────
    public interface LogcatProcessFactory {
        Process start() throws IOException;
    }

    private static final LogcatProcessFactory DEFAULT_FACTORY = () ->
            Runtime.getRuntime().exec(LOGCAT_FILTER);

    // ── Fields ────────────────────────────────────────────────────────────────
    private final TextView             console;
    private final ScrollView           scrollView;
    private final Handler              mainHandler;
    private final LogcatProcessFactory factory;

    private HandlerThread    readerThread;
    private volatile boolean isRunning     = false;
    private volatile Process activeProcess = null;
    private int              lineCount     = 0;   // main thread only

    /**
     * Unbounded full-session log buffer.
     * The TextView console is capped at MAX_LINES for rendering performance,
     * but this buffer retains every line written since startStreaming() so
     * that copyLogsToClipboard() (via getFullLog()) always returns complete logs.
     * Access only on the main thread (same constraint as appendLine).
     */
    private final StringBuilder mFullLog = new StringBuilder();

    // ── Constructors ──────────────────────────────────────────────────────────

    public LogConsoleManager(TextView console, ScrollView scrollView, Handler mainHandler) {
        this(console, scrollView, mainHandler, DEFAULT_FACTORY);
    }

    public LogConsoleManager(TextView console,
                             ScrollView scrollView,
                             Handler mainHandler,
                             LogcatProcessFactory factory) {
        this.console     = console;
        this.scrollView  = scrollView;
        this.mainHandler = mainHandler;
        this.factory     = factory;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Starts the logcat reader. No-op if already running. */
    public synchronized void startStreaming() {
        if (isRunning) return;
        isRunning = true;

        readerThread = new HandlerThread("LogReaderThread");
        readerThread.start();
        new Handler(readerThread.getLooper()).post(this::runReadLoop);
    }

    /** Stops the reader. Idempotent — safe to call from onDestroy(). */
    public synchronized void stopStreaming() {
        if (!isRunning) return;
        isRunning = false;

        Process p = activeProcess;
        if (p != null) p.destroy();

        if (readerThread != null) {
            readerThread.quitSafely();
            readerThread = null;
        }
    }

    /**
     * log — writes a timestamped line to both the console and android.util.Log.
     *
     * Use for important app events (engine phases, alerts, errors) so they
     * appear instantly without waiting for logcat delivery latency.
     * Can be called from any thread.
     */
    public void log(String tag, String message) {
        Log.i(tag, message);
        String line = TIME_FMT.format(new Date()) + " " + tag + ": " + message;
        mainHandler.post(() -> appendLine(line));
    }

    /**
     * logToUI — writes a formatted line directly to the console.
     *
     * ALERT and CRITICAL lines are prefixed with a red marker for visibility.
     * Respects the MAX_LINES cap (routes through appendLine).
     * Can be called from any thread.
     */
    public void logToUI(String message) {
        mainHandler.post(() -> {
            String timestamp = TIME_FMT_SHORT.format(new Date());
            String prefix = (message.contains("ALERT") || message.contains("CRITICAL"))
                    ? "\n🔴 " : "";
            appendLine(prefix + "[" + timestamp + "] " + message);
        });
    }

    /**
     * getFullLog — returns the complete unbounded log accumulated this session.
     *
     * Unlike the TextView console (capped at MAX_LINES), this buffer is never
     * trimmed, so it faithfully captures every line for clipboard export.
     * Safe to call from any thread — the returned String is an immutable snapshot.
     */
    public String getFullLog() {
        // StringBuilder.toString() is thread-safe for reading the current content.
        // All writes happen on the main thread via appendLine(), so no lock needed
        // as long as callers only read (never mutate) the returned String.
        return mFullLog.toString();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void runReadLoop() {
        try {
            activeProcess = factory.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(activeProcess.getInputStream()));
            String line;
            while (isRunning && (line = reader.readLine()) != null) {
                final String logLine = line;
                mainHandler.post(() -> appendLine(logLine));
            }
        } catch (IOException e) {
            if (isRunning) {
                Log.w(TAG, "Logcat read error: " + e.getMessage());
                mainHandler.post(() ->
                        appendLine("[LogConsole error: " + e.getMessage() + "]"));
            }
        } finally {
            activeProcess = null;
        }
    }

    /**
     * appendLine — single write path for all console output.
     *
     * Trims the oldest line when MAX_LINES is reached, then appends the new
     * line and schedules a post-layout scroll. Must run on the main thread.
     */
    private void appendLine(String line) {
        if (lineCount >= MAX_LINES) {
            String text = console.getText().toString();
            int nl = text.indexOf('\n');
            if (nl >= 0) {
                console.setText(text.substring(nl + 1));
            } else {
                console.setText("");
                lineCount = 0;
            }
            lineCount--;
        }

        mFullLog.append(line).append('\n');
        console.append(line + "\n");
        lineCount++;
        scrollAfterLayout();
    }

    /**
     * scrollAfterLayout — one-shot OnGlobalLayoutListener that scrolls after
     * layout re-runs, so fullScroll() sees the TextView's updated height.
     */
    private void scrollAfterLayout() {
        scrollView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        scrollView.getViewTreeObserver()
                                  .removeOnGlobalLayoutListener(this);
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
    }
}