package com.example.aaos.landmark;

import android.app.Activity;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.Manifest;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LandmarkCacheApp extends Activity implements TextToSpeech.OnInitListener {

    private static final String TAG = "LandmarkCacheApp";

    // ── Simulation constants ──────────────────────────────────────────────────
    private static final float DRIVE_TIME_FACTOR      = 2.4f;
    private static final long  ALERT_COOLDOWN_MS      = 30_000L;
    private static final long  ALERT_STARTUP_GRACE_MS = 5_500L;
    private static final int   EV_INJECT_THRESHOLD    = 30;
    private static final int   LRU_DISPLAY_CAP        = 7;

    // Token used to cancel stale seedOne() panel-refresh posts before posting a fresher one.
    // Must be a non-null Object distinct from any other token used with mainHandler.
    private static final Object SEED_UI_TOKEN = new Object();

    // Throttle battery bar updates so the gauge advances smoothly (1 tick ≈ 1.2 s)
    private static final int BATTERY_UI_INTERVAL_TICKS    = 5;
    private static final int BATTERY_DRAIN_INTERVAL_TICKS = 3;

    // NH44 simulation start position (Bengaluru)
    private static final double DRIVE_START_LAT     = 12.8458;
    private static final double DRIVE_START_LON     = 77.6692;
    private static final int    DRIVE_START_BATTERY = 92;

    // Charging session constants
    private static final int  CHARGE_TARGET_PCT = 85;
    private static final int  CHARGE_STEP_PCT   = 5;
    private static final long CHARGE_TICK_MS    = 400L;

    private static final long DEFER_DURATION_MS = 10 * 60 * 1000L;

    // ── Volatile cross-thread state ───────────────────────────────────────────
    private volatile long   mLastAlertTimeMs   = 0L;
    private volatile String mLastAlertLandmark = "";
    private volatile String mLastAlertCategory = "";

    // Per-run alert fire counters — reset in startAutoDriveSimulation()
    private volatile int mRestAlertCount   = 0;
    private volatile int mScenicAlertCount = 0;
    private volatile int mSocialAlertCount = 0;
    private static final int MAX_REST_ALERTS_S1   = 2;
    private static final int MAX_SCENIC_ALERTS_S1 = 1;
    private static final int MAX_SOCIAL_ALERTS_S1 = 2; // one mid-route + Cafe Sangeetha near Chennai

    // ── Continuous drive tracking ─────────────────────────────────────────────
    // mLastRestAcceptedMs: updated when driver accepts REST_STOP or charging begins.
    private volatile long mDriveStartTimeMs   = 0L;
    private volatile long mLastRestAcceptedMs = 0L;

    private static final long REST_SUGGEST_AFTER_MS = 60 * 60 * 1000L;
    private volatile double mSavedLat     = DRIVE_START_LAT;
    private volatile double mSavedLon     = DRIVE_START_LON;
    private volatile int    mSavedBattery = DRIVE_START_BATTERY;

    // Route-step counter: advances every tick independent of battery level
    private volatile int     mRouteStep      = 0;
    private volatile boolean mRouteComplete  = false;
    private volatile boolean mLruDemoRunning = false;

    // ── Scenario sequencing ───────────────────────────────────────────────────
    // 0 = Scenario 1 (92% battery)  |  1 = Scenario 2 (50% battery)
    // volatile: written on main thread (showDestinationDialog), read on sim thread (buildNearbyForStep).
    private volatile int mScenarioIndex = 0;

    // ── Battery prediction samples — guarded by synchronized methods ──────────
    private int  mPredSampleBatt1 = -1;
    private long mPredSampleTime1 = 0L;
    private int  mPredSampleBatt2 = -1;
    private long mPredSampleTime2 = 0L;

    // ── Landmark registries — populated at runtime from assets/landmarks.json ─
    private final List<String> mEvNames      = new ArrayList<>();
    private final List<String> mEvDetails    = new ArrayList<>();
    private final List<String> mSocialNames  = new ArrayList<>();
    private final List<String> mSocialDetails = new ArrayList<>();
    private final List<String> mScenicNames  = new ArrayList<>();
    private final List<String> mScenicDetails = new ArrayList<>();
    private final List<String> mRestNames    = new ArrayList<>();
    private final List<String> mRestDetails  = new ArrayList<>();

    /**
     * Authoritative name→category map built during seedLandmarks().
     * Replaces fragile prefix inference so any landmark name not following the
     * EV_/RestStop_/Viewpoint_ convention is still resolved correctly from the
     * JSON "category" field. Populated once during seeding; effectively read-only
     * after that point.
     */
    private final Map<String, String> mNameToCategory = new java.util.HashMap<>();

    // ── Driver preference tracker ─────────────────────────────────────────────

    /**
     * Tracks per-category accept/reject history across sessions via SharedPreferences.
     *
     * Storage format — one key per category (e.g. "pref_SOCIAL"):
     *   A string of '1' (accepted) and '0' (rejected) chars, newest appended right.
     *   Example: "11010" → 5 interactions, 3 accepted. Capped at MAX_PREF_HISTORY.
     *
     * Inspectable via adb:
     *   adb shell am get-shared-pref &lt;pkg&gt; driver_prefs
     *
     * Thread safety: every public method is synchronized. recordAccepted/recordRejected
     * are called on the main thread; shouldShow/hasStrongAcceptance on the sim thread.
     * SharedPreferences.Editor.apply() is asynchronous — no disk I/O on the caller.
     */
    private static final class DriverPreferenceTracker {

        private static final int    MAX_PREF_HISTORY    = 20;
        private static final int    MIN_SAMPLES         = 3;
        private static final float  MIN_ACCEPT_RATIO    = 0.25f;  // below → suppress alert entirely
        private static final float  STRONG_ACCEPT_RATIO = 0.55f;  // above → show specific landmark name
        private static final String PREFS_FILE          = "driver_prefs";
        private static final String KEY_PREFIX          = "pref_";

        private final android.content.SharedPreferences mPrefs;

        DriverPreferenceTracker(android.content.Context ctx) {
            mPrefs = ctx.getSharedPreferences(PREFS_FILE, android.content.Context.MODE_PRIVATE);
        }

        synchronized void recordAccepted(String category) { record(category, true);  }
        synchronized void recordRejected(String category) { record(category, false); }

        /**
         * Suppression gate — returns false if the driver has consistently rejected
         * this category (accept ratio below MIN_ACCEPT_RATIO). Alert is dropped entirely.
         * Returns true when there is insufficient history (benefit of the doubt).
         */
        synchronized boolean shouldShow(String category) {
            String bits = load(category);
            if (bits.length() < MIN_SAMPLES) return true;
            return acceptRatio(bits) >= MIN_ACCEPT_RATIO;
        }

        /**
         * Format signal — returns true if the driver has accepted this category
         * more often than not (above STRONG_ACCEPT_RATIO).
         * Used by formatAlertBody() to decide specific name vs generic label.
         * Returns false when there is insufficient history → default to generic label.
         */
        synchronized boolean hasStrongAcceptance(String category) {
            String bits = load(category);
            if (bits.length() < MIN_SAMPLES) return false;
            return acceptRatio(bits) >= STRONG_ACCEPT_RATIO;
        }

        /** Returns a compact accept/total summary string for all stored categories. */
        synchronized String summary() {
            Map<String, ?> all = mPrefs.getAll();
            if (all.isEmpty()) return "no data";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (!e.getKey().startsWith(KEY_PREFIX)) continue;
                if (!(e.getValue() instanceof String)) continue; // guard against corrupted/foreign prefs
                String bits  = (String) e.getValue();
                String cat   = e.getKey().substring(KEY_PREFIX.length());
                int total    = bits.length();
                int accepted = countOnes(bits);
                sb.append(cat).append(":").append(accepted).append("/")
                  .append(total).append("  ");
            }
            return sb.length() == 0 ? "no data" : sb.toString().trim();
        }

        /**
         * Returns the raw '1'/'0' history string for a single category.
         * Used by refreshPrefPanel() to render per-category accept ratios in the UI.
         * Returns "" when no history exists yet for that category.
         */
        synchronized String rawBits(String category) {
            return load(category);
        }

        // ── private helpers ───────────────────────────────────────────────────

        private void record(String category, boolean accepted) {
            String bits = load(category) + (accepted ? "1" : "0");
            // Trim oldest entry from the left when over cap
            if (bits.length() > MAX_PREF_HISTORY)
                bits = bits.substring(bits.length() - MAX_PREF_HISTORY);
            // apply() is asynchronous — commits to disk on a background thread
            mPrefs.edit().putString(KEY_PREFIX + category, bits).apply();
        }

        private String load(String category) {
            return mPrefs.getString(KEY_PREFIX + category, ""); // "" = no history yet
        }

        private static float acceptRatio(String bits) {
            return (float) countOnes(bits) / bits.length();
        }

        private static int countOnes(String bits) {
            int count = 0;
            for (int i = 0; i < bits.length(); i++) if (bits.charAt(i) == '1') count++;
            return count;
        }
    }

    // Initialised in onCreate() — SharedPreferences requires a Context
    private DriverPreferenceTracker mPrefs;

    // ── UI views ──────────────────────────────────────────────────────────────

    private LandmarkDiscoveryProvider mDiscoveryService;

    // Status bar
    private TextView    statusText;
    private TextView    mBatteryText;
    private ProgressBar mBatteryBar;
    private TextView    mDriveTimeText;
    private TextView    mEvictCountText;

    // LRU panel
    private LinearLayout mLruPanel;
    // AtomicInteger: incremented from sim thread, read on main thread
    private final AtomicInteger mEvictTotal = new AtomicInteger(0);

    // Alert card
    private LinearLayout mAlertCard;
    private TextView     mAlertTitle;
    private TextView     mAlertBody;
    private Button       mAlertAccept;
    private Button       mAlertDefer;
    private Button       mAlertDismiss;
    private String       mPendingAlertCategory;
    private String       mPendingAlertKey; // raw cooldownKey at fire time e.g. "Cafe_Murugan_Vellore|SOCIAL"

    // Driver preference panel — live accept/reject ratio per category
    private TextView mPrefPanel;

    // Log console
    private TextView          mConsoleView;
    private LogConsoleManager mLogManager;
    private ScrollView        mLogScroll;

    // Threading
    private Handler       mainHandler;
    private HandlerThread engineThread;
    private Handler       engineHandler;
    private HandlerThread simulationThread;
    // volatile: written from both main thread and sim thread; prevents stale handler
    // reference being visible after teardown.
    private volatile Handler simulationHandler;

    // TTS / Audio
    private TextToSpeech      mTts;
    private AudioManager      mAudioManager;
    private AudioFocusRequest mFocusRequest;
    private boolean           mIsTtsReady    = false;
    private int               mTtsRetryCount = 0;
    private static final int  TTS_MAX_RETRIES = 3;

    // Battery + sync state
    private final AtomicInteger mCurrentBatteryLevel = new AtomicInteger(100);
    private final AtomicBoolean isSyncing            = new AtomicBoolean(false);
    // Guards against a race where an in-flight sim-tick alert triggers a second charging
    // session after the first has already started.
    private volatile boolean    mIsCharging          = false;
    private volatile String     mCurrentZoneId       = "";
    private volatile String     mCurrentLandmarkLabel = "En route…"; // human-readable zone shown in status bar
    private volatile boolean    nativeLibraryLoaded  = false;
    private volatile boolean    isRunning            = false;

    private Button mDriveButton;
    private Button mStopButton;

    // ── JNI ──────────────────────────────────────────────────────────────────
    private native void   nativeInit(int cap);
    private native void   nativeDestroy();
    private native void   nativeAddLandmark(String name, String cat, String det,
                                             float rating, boolean isSocial);
    private native void   nativeUpdateBattery(int level);
    private native void   nativeSyncFromCloud(double lat, double lon);
    private native String nativeGetAlert(String[] nearby, int driveTime);
    private native String nativeCheckProximity(double lat, double lon);
    private native void   nativeNotifyLandmarkHit(String landmarkName);

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs        = new DriverPreferenceTracker(this);
        mainHandler   = new Handler(Looper.getMainLooper());
        engineThread  = new HandlerThread("EngineWorker");
        engineThread.start();
        engineHandler = new Handler(engineThread.getLooper());

        bindViews();
        setupButtons();

        mLogManager = new LogConsoleManager(mConsoleView, mLogScroll, mainHandler);
        mLogManager.startStreaming();

        mDiscoveryService = new UnifiedDiscoveryProvider(this);
        boolean emu = ((UnifiedDiscoveryProvider) mDiscoveryService).isEmulatorMode();
        mLogManager.logToUI("📡 Discovery: " + (emu ? "EMULATOR (mock)" : "REAL HW"));

        refreshLruPanel(new ArrayList<>(), null, null, false);
        if (mEvictCountText != null) mEvictCountText.setText("Evict cnt: 0");
        if (mDriveTimeText  != null) mDriveTimeText.setText("Drive Time: --");
        if (mBatteryText    != null) mBatteryText.setText("Batt: --%");
        refreshPrefPanel();

        if (statusText != null)
            statusText.setText("Have a good trip! Alerts will keep you refreshed along the way.");
        mainHandler.postDelayed(() -> {
            if (mLogManager != null) {
                mLogManager.logToUI("👋 Have a good trip on NH44!");
                mLogManager.logToUI("   Tap ▶ Init Engine to get started.");
                mLogManager.logToUI("   Alerts for EV, rest stops, scenic spots & more.");
            }
        }, 400);
    }

    /**
     * Binds all XML views to their fields and wires alert card button listeners.
     * Every post-bind call is guarded against null so a missing layout ID causes
     * a silent no-op rather than an NPE crash on startup.
     */
    private void bindViews() {
        statusText      = findViewById(R.id.status_text);
        mBatteryText    = findViewById(R.id.battery_text);
        mBatteryBar     = findViewById(R.id.battery_bar);
        mDriveTimeText  = findViewById(R.id.drive_time_text);
        mEvictCountText = findViewById(R.id.evict_count_text);
        mLruPanel       = findViewById(R.id.lru_panel);
        mAlertCard      = findViewById(R.id.alert_card);
        mAlertTitle     = findViewById(R.id.alert_title);
        mAlertBody      = findViewById(R.id.alert_body);
        mAlertAccept    = findViewById(R.id.btn_alert_accept);
        mAlertDefer     = findViewById(R.id.btn_alert_defer);
        mAlertDismiss   = findViewById(R.id.btn_alert_dismiss);
        mConsoleView    = findViewById(R.id.log_console);
        mLogScroll      = findViewById(R.id.log_scroll);
        mDriveButton    = findViewById(R.id.btn_drive_test);
        mStopButton     = findViewById(R.id.btn_stop_simulation);
        mPrefPanel      = findViewById(R.id.pref_panel);

        if (mAlertCard    != null) mAlertCard.setVisibility(View.GONE);
        if (mAlertAccept  != null) mAlertAccept.setOnClickListener(v  -> onAlertAccept());
        if (mAlertDefer   != null) mAlertDefer.setOnClickListener(v   -> onAlertDefer());
        if (mAlertDismiss != null) mAlertDismiss.setOnClickListener(v -> onAlertDismiss());
    }

    /**
     * Wires all action button click listeners and sets initial enabled/alpha states.
     * All buttons are guarded against null — a missing layout ID is a silent no-op.
     */
    private void setupButtons() {
        Button startButton = findViewById(R.id.btn_start_simulation);
        Button clearButton = findViewById(R.id.btn_clear_log);
        Button copyButton  = findViewById(R.id.btn_copy_log);

        if (mDriveButton != null) setButtonState(mDriveButton, false);
        if (mStopButton  != null) setButtonState(mStopButton,  false);

        if (startButton != null) {
            startButton.setOnClickListener(v -> {
                mScenarioIndex = 0;
                Toast.makeText(this, "Loading engine…", Toast.LENGTH_SHORT).show();
                startIntegratedEngine();
            });
        }

        if (mDriveButton != null) {
            mDriveButton.setOnClickListener(v -> {
                if (!isRunning) {
                    String label = mDriveButton.getText().toString();
                    String toast = label.equals("Resume Drive")
                            ? "Resuming drive — NH44"
                            : "Starting drive — NH44 Bengaluru → Chennai";
                    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
                    startAutoDriveSimulation();
                } else {
                    stopSimulation(true);
                }
            });
        }

        if (mStopButton != null) {
            mStopButton.setOnClickListener(v -> {
                mLruDemoRunning = false;
                setButtonState(mStopButton, false);
                updateStatus("Demo stopped");
            });
        }

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                if (mConsoleView != null) mConsoleView.setText("");
                refreshLruPanel(new ArrayList<>(), null, null, false);
            });
        }

        if (copyButton != null) {
            copyButton.setOnClickListener(v -> copyLogsToClipboard());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;

        // Cancel ALL pending mainHandler callbacks (auto-dismiss/auto-accept postDelayed,
        // TTS retry, greeting) before tearing down views. Without this, a 4-5s postDelayed
        // that was scheduled while an alert was visible will fire after onDestroy and access
        // mAlertCard / mAlertBody / statusText — all null by then → guaranteed NPE crash.
        mainHandler.removeCallbacksAndMessages(null);

        // Stop sim thread before tearing down engine to prevent new work being posted
        HandlerThread simThread = simulationThread;
        simulationThread  = null;
        simulationHandler = null;
        if (simThread != null) simThread.quitSafely();

        if (mLogManager != null) mLogManager.stopStreaming();
        if (mTts != null) { mTts.stop(); mTts.shutdown(); }

        // Clear the flag before posting nativeDestroy so any lingering engineHandler posts
        // that already passed isNativeReady() cannot call into freed native memory.
        nativeLibraryLoaded = false;
        engineHandler.post(() -> {
            nativeDestroy();
            engineThread.quitSafely();
        });
    }

    // =========================================================================
    // Copy log to clipboard
    // =========================================================================

    private void copyLogsToClipboard() {
        if (mConsoleView == null) return;
        String logs = mConsoleView.getText().toString();
        if (logs.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("LandmarkLogs", logs));
            Toast.makeText(this, "📋 Logs copied — " + logs.length() + " chars",
                    Toast.LENGTH_LONG).show();
            mLogManager.logToUI("📋 Logs copied to clipboard");
        } else {
            Toast.makeText(this, "Clipboard unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    void setDiscoveryService(LandmarkDiscoveryProvider svc) { mDiscoveryService = svc; }
    Handler getEngineHandler() { return engineHandler; }

    // =========================================================================
    // LRU panel — data model + animated display
    // =========================================================================

    /**
     * In-memory LRU list shown in the panel.
     * Index 0 = MRU (most-recently used). Capped at LRU_DISPLAY_CAP entries.
     */
    private final List<LruEntry> mLruEntries = new ArrayList<>();

    private static final class LruEntry {
        final String name;
        final String category;
        int hitCount;
        LruEntry(String name, String category) {
            this.name = name; this.category = category; this.hitCount = 1;
        }
    }

    /**
     * Mutates mLruEntries for each arrived landmark and returns a snapshot.
     * Single synchronized block prevents a gap between mutation and snapshot.
     */
    private List<LruEntry> updateLruState(String[] arrivedNames, String evictedName) {
        synchronized (this) {
            for (String name : arrivedNames) {
                String cat = resolveCategoryFromName(name);
                int existing = -1;
                for (int i = 0; i < mLruEntries.size(); i++) {
                    if (mLruEntries.get(i).name.equals(name)) { existing = i; break; }
                }
                if (existing >= 0) {
                    LruEntry e = mLruEntries.remove(existing);
                    e.hitCount++;
                    mLruEntries.add(0, e);
                } else {
                    mLruEntries.add(0, new LruEntry(name, cat));
                    if (mLruEntries.size() > LRU_DISPLAY_CAP)
                        mLruEntries.remove(mLruEntries.size() - 1);
                }
            }
            return new ArrayList<>(mLruEntries);
        }
    }

    /**
     * Rebuilds the LRU LinearLayout.
     *
     * Three distinct animations make each cache event visually unambiguous:
     *   NEW ENTRY    (hitCount == 1, position 0) → slides in from the right with a fade
     *   HIT/PROMOTED (hitCount > 1, position 0)  → amber highlight pulse
     *   EVICTED      (tail entry)                → amber tint + strikethrough, then
     *                                               slides out to the right and fades
     *
     * The evicted row uses slideOutRight() — mirroring the slide-in direction — so
     * new entries appear to push the old one out.  The brief strikethrough phase
     * (visible before the translate begins) gives the driver's eye time to read
     * which landmark left the cache before it disappears.
     */
    private void refreshLruPanel(List<LruEntry> entries, String evictedName,
                                  String[] arrivedNames, boolean animate) {
        if (mLruPanel == null) return;
        mLruPanel.removeAllViews();

        for (int i = 0; i < entries.size(); i++) {
            LruEntry e = entries.get(i);
            TextView row = buildLruRow(i + 1, e, false);
            mLruPanel.addView(row);

            if (animate && arrivedNames != null && isInArray(arrivedNames, e.name)) {
                if (e.hitCount == 1) {
                    // Cache miss → brand-new entry slides in from the right
                    row.startAnimation(slideInFromRight());
                } else if (i == 0) {
                    // Cache hit → entry was promoted to MRU position; pulse amber to show the move
                    row.startAnimation(hitPromotionPulse());
                }
            }
        }

        // Evicted slide-out row: briefly shows the evicted entry at the bottom of
        // the list, then slides it right and fades out — unambiguously showing
        // which entry left the cache and where (tail) it was sitting.
        if (evictedName != null && !evictedName.isEmpty()) {
            String evictCat = resolveCategoryFromName(evictedName);
            LruEntry evictEntry = new LruEntry(evictedName, evictCat);
            TextView slideOutRow = buildLruRow(LRU_DISPLAY_CAP + 1, evictEntry, false);

            // Amber tint + strikethrough: signals removal before the row physically
            // leaves — gives the eye a moment to register what is going.
            slideOutRow.setBackgroundColor(Color.argb(60, 186, 117, 23));
            slideOutRow.setPaintFlags(
                    slideOutRow.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            slideOutRow.setText("\u21b3  " + slideOutRow.getText());

            mLruPanel.addView(slideOutRow);

            if (animate) {
                final LinearLayout panel = mLruPanel;
                AnimationSet out = slideOutRight();
                out.setAnimationListener(new Animation.AnimationListener() {
                    @Override public void onAnimationStart(Animation a) {}
                    @Override public void onAnimationRepeat(Animation a) {}
                    @Override public void onAnimationEnd(Animation a) {
                        mainHandler.post(() -> { if (panel != null) panel.removeView(slideOutRow); });
                    }
                });
                slideOutRow.startAnimation(out);
            } else {
                mLruPanel.removeView(slideOutRow);
            }
        }

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Cache empty");
            empty.setTextSize(12f);
            empty.setTextColor(Color.parseColor("#888780"));
            empty.setPadding(dp(8), dp(4), dp(8), dp(4));
            mLruPanel.addView(empty);
        }
    }

    private TextView buildLruRow(int rank, LruEntry e, boolean isEvictedRow) {
        TextView tv = new TextView(this);
        String displayName = e.name.replace("_", " ");
        tv.setText(rank + ". [" + categoryLabel(e.category) + "] " + displayName
                + (e.hitCount > 1 ? "  ×" + e.hitCount : ""));
        tv.setTextSize(14f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(isEvictedRow ? Color.parseColor("#FF5252") : categoryTextColor(e.category));
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundColor(categoryCardBg(e.category, 40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(4));
        tv.setLayoutParams(lp);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    /** New LRU entry slides in from the right with a fade. */
    private static Animation slideInFromRight() {
        // AnimationSet(false) — independent interpolators per child animation
        AnimationSet set = new AnimationSet(false);
        TranslateAnimation slide = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 1.1f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,  Animation.RELATIVE_TO_SELF, 0f);
        slide.setInterpolator(new DecelerateInterpolator());
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setInterpolator(new LinearInterpolator());
        slide.setDuration(350); fade.setDuration(350);
        set.addAnimation(slide); set.addAnimation(fade);
        return set;
    }

    /**
     * Evicted slide-out row animation — slides the row off to the right while fading out.
     *
     * Sliding right (away from the list) is intentional: the incoming entry always slides
     * in from the right, so the evicted entry exiting right creates a clean mirror — the
     * new entry pushes the old one out in the same direction.  AccelerateInterpolator
     * gives a snappy departure that feels decisive rather than lingering.
     *
     * Duration 420 ms: long enough to track with the eye, short enough to not interrupt
     * the next step in the demo sequence (1800 ms tick).
     */
    private static AnimationSet slideOutRight() {
        // AnimationSet(false) — independent interpolators; no setFillAfter (removeView handles cleanup)
        AnimationSet set = new AnimationSet(false);
        TranslateAnimation slide = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,  Animation.RELATIVE_TO_SELF, 1.1f,
                Animation.RELATIVE_TO_SELF, 0f,  Animation.RELATIVE_TO_SELF, 0f);
        slide.setInterpolator(new AccelerateInterpolator());
        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setInterpolator(new LinearInterpolator());
        slide.setDuration(420); fade.setDuration(420);
        set.addAnimation(slide); set.addAnimation(fade);
        return set;
    }

    /**
     * Promoted-hit pulse — fires on the MRU row (position 0) when a cached entry
     * is re-accessed and jumps to the top of the list.
     *
     * Animation: the row background fades from its normal category tint to a bright
     * amber and back over 500 ms, drawing the eye to the promotion without moving
     * the row or disturbing the rest of the panel.
     *
     * AlphaAnimation on a separate overlay View is used instead of animating the
     * row's own background so the category tint is never lost mid-animation.
     */
    private static Animation hitPromotionPulse() {
        AlphaAnimation pulse = new AlphaAnimation(0f, 1f);
        pulse.setDuration(180);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(1);          // 0→1→0 = one full pulse, 360 ms total
        pulse.setInterpolator(new DecelerateInterpolator());
        return pulse;
    }

    // =========================================================================
    // Alert card — Accept / Defer / Dismiss
    // =========================================================================

    private void showAlertCard(String rawAlert, String category, int batteryPct, long driveMins) {
        // Guard: suppress if views are torn down or a charging session is in progress.
        if (mAlertCard == null || mAlertTitle == null || mAlertBody == null) return;
        if (mIsCharging) return;
        mPendingAlertCategory = category;

        String urgency;
        switch (category) {
            case "EV_STATION":
                urgency = batteryPct < 10 ? "⚠️ CRITICAL — " : "⚠️ LOW BATTERY — ";
                break;
            case "REST_STOP":
                // Mention drive duration in the title only when it meaningfully motivates the stop.
                // < 30 min: driver doesn't need a break yet — just flag the stop, no pressure.
                // 30–60 min: light suggestion.
                // > 60 min: firm recommendation.
                urgency = driveMins >= 60 ? "🛑 BREAK RECOMMENDED — "
                        : driveMins >= 30 ? "🛑 REST AHEAD — "
                        :                   "🛑 NEARBY — ";
                break;
            case "SCENIC":
                urgency = "🌿 VIEWPOINT — ";
                break;
            default:  // SOCIAL
                urgency = mPrefs.hasStrongAcceptance(category) ? "⭐ YOUR PICK — " : "☕ NEARBY — ";
                break;
        }

        mAlertTitle.setText(alertIcon(category) + " " + urgency + categoryLabel(category));
        mAlertBody.setText(formatAlertBody(rawAlert, category, driveMins));
        mAlertCard.setBackgroundColor(categoryCardBg(category, 25));
        mAlertCard.setVisibility(View.VISIBLE);

        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(250);
        mAlertCard.startAnimation(fadeIn);

        // Auto-accept on critical battery (< 10%) after 5 seconds
        if ("EV_STATION".equals(category) && batteryPct < 10) {
            final String pinnedAlertText = formatAlertBody(rawAlert, category, driveMins);
            mainHandler.postDelayed(() -> {
                if (mAlertCard != null && mAlertCard.getVisibility() == View.VISIBLE
                        && "EV_STATION".equals(mPendingAlertCategory)
                        && mAlertBody != null
                        && pinnedAlertText.equals(mAlertBody.getText().toString())) {
                    log("⚡ Auto-accepted EV alert (battery critical)");
                    dismissAlertCard(true);
                }
            }, 5000);
        }

        // Social alerts auto-dismiss after 20 seconds — they are informational
        // and should not linger while the driver focuses on the road.
        if ("SOCIAL".equals(category)) {
            final String pinnedCategory = category;
            mainHandler.postDelayed(() -> {
                if (mAlertCard != null && mAlertCard.getVisibility() == View.VISIBLE
                        && pinnedCategory.equals(mPendingAlertCategory)) {
                    log("☕ Social alert auto-dismissed");
                    dismissAlertCard(false);
                }
            }, 20000);
        }
    }

    private void onAlertAccept() {
        if (mPendingAlertCategory != null) {
            mPrefs.recordAccepted(mPendingAlertCategory);
            log("✔ Alert accepted [" + mPendingAlertCategory + "]");
        }
        refreshPrefPanel();
        log("📊 Prefs updated: " + mPrefs.summary());
        if ("EV_STATION".equals(mPendingAlertCategory)) {
            dismissAlertCard(true);
            startChargingSession();
            return;
        }
        // Re-stamp the exact cooldown key and timestamp from the moment of accept.
        // mPendingAlertKey is the raw "LandmarkName|CATEGORY" stored at fire time —
        // this guarantees sameTarget stays true for the full 30 s cooldown window
        // and prevents the LRU alternating between two SOCIAL landmarks from leaking through.
        mLastAlertTimeMs   = System.currentTimeMillis();
        if (mPendingAlertKey != null) mLastAlertLandmark = mPendingAlertKey;
        mLastAlertCategory = mPendingAlertCategory;
        // REST_STOP accept also resets the continuous-drive timer.
        if ("REST_STOP".equals(mPendingAlertCategory)) {
            mLastRestAcceptedMs = System.currentTimeMillis();
        }
        dismissAlertCard(true);
    }

    private void startChargingSession() {
        // Stop the drive sim loop first so no stale tick can post to the new handler
        // or call nativeGetAlert while the charging session is running.
        isRunning = false;
        HandlerThread oldSimThread = simulationThread;
        simulationHandler = null;
        simulationThread  = null;
        if (oldSimThread != null) oldSimThread.quitSafely();

        // Spin up a dedicated charge-session thread.
        simulationThread  = new HandlerThread("ChargeSessionEngine");
        simulationThread.start();
        simulationHandler = new Handler(simulationThread.getLooper());

        mIsCharging = true;
        setButtonState(mDriveButton, false);
        setButtonState(mStopButton,  false);

        // Treat the charging stop as a rest break so the break-suggestion timer
        // does not fire immediately when the driver resumes after charging.
        mLastRestAcceptedMs = System.currentTimeMillis();

        log("⚡ Fast charging started — target " + CHARGE_TARGET_PCT + "%");
        updateStatus("Charging…");

        simulationHandler.post(new Runnable() {
            int chargeTick = 0;

            @Override
            public void run() {
                int current = mCurrentBatteryLevel.get();

                if (current >= CHARGE_TARGET_PCT) {
                    mSavedBattery  = current;
                    mIsCharging    = false;
                    mLastAlertLandmark = "";
                    mLastAlertCategory = "";
                    mLastAlertTimeMs   = 0L;
                    // Final update so the bar shows the completed charge level
                    mainHandler.post(() -> applyBatteryToViews(current));
                    log(String.format(
                            "✅ Charging complete — %d%% | resuming from Lat:%.4f Lon:%.4f",
                            current, mSavedLat, mSavedLon));
                    mainHandler.post(() -> {
                        if (mDriveButton != null) {
                            mDriveButton.setText("Resume Drive");
                            setButtonState(mDriveButton, true);
                        }
                        updateStatus("Charge complete — tap Resume Drive");
                    });
                    return;
                }

                int next = Math.min(CHARGE_TARGET_PCT, current + CHARGE_STEP_PCT);
                mCurrentBatteryLevel.set(next);
                recordBatterySample(next);
                // Guard: nativeLibraryLoaded may become false if onDestroy() races this tick.
                engineHandler.post(() -> { if (isNativeReady()) nativeUpdateBattery(next); });

                // Throttle UI refresh to avoid the bar jumping on every fast-charge tick.
                chargeTick++;
                if (chargeTick % BATTERY_UI_INTERVAL_TICKS == 0) {
                    mainHandler.post(() -> applyBatteryToViews(next));
                    log("⚡ Charging… " + next + "%");
                }

                Handler sh = simulationHandler;
                if (sh != null) sh.postDelayed(this, CHARGE_TICK_MS);
            }
        });
    }

    private void onAlertDefer() {
        if (mPendingAlertCategory != null) {
            log("⏱ Alert deferred — remind in 10 min ["
                    + mPendingAlertCategory + "]");
            // Advance mLastAlertTimeMs by the deferral window so the next cooldown
            // check in handleAlert fires exactly DEFER_DURATION_MS from now.
            mLastAlertTimeMs = System.currentTimeMillis() + DEFER_DURATION_MS - ALERT_COOLDOWN_MS;
        }
        refreshPrefPanel();
        dismissAlertCard(false);
    }

    private void onAlertDismiss() {
        if (mPendingAlertCategory != null) {
            mPrefs.recordRejected(mPendingAlertCategory);
            log("✕ Alert dismissed [" + mPendingAlertCategory + "]");
        }
        // Reset cooldown so the same landmark doesn't re-alert within 30 s of dismiss.
        mLastAlertTimeMs = System.currentTimeMillis();
        refreshPrefPanel();
        log("📊 Prefs updated: " + mPrefs.summary());
        dismissAlertCard(false);
    }

    private void dismissAlertCard(boolean accepted) {
        if (mAlertCard == null) return;
        final LinearLayout card = mAlertCard;
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                card.setVisibility(View.GONE);
                mPendingAlertCategory = null;
                if (statusText != null)
                    statusText.setText(mCurrentLandmarkLabel);
            }
        });
        card.startAnimation(fadeOut);
    }

    // =========================================================================
    // UI update helpers
    // =========================================================================

    /**
     * Single mainHandler.post per drive tick.
     * Consolidates battery bar, evict counter, and LRU panel into one UI pass.
     * Battery bar is only redrawn when {@code batteryUiDue} is true.
     * LRU panel is only redrawn when the cache actually changed.
     */
    private void handleUIUpdate(int battery, String[] nearby, String evicted, boolean batteryUiDue) {
        final int evictTotal = (evicted != null)
                ? mEvictTotal.incrementAndGet()
                : mEvictTotal.get();

        // Capture pre-update snapshot to detect whether the cache actually changed.
        final List<LruEntry> before;
        synchronized (this) { before = new ArrayList<>(mLruEntries); }

        final List<LruEntry> snapshot = updateLruState(nearby, evicted);

        // Compare snapshots: only redraw if an entry was added, promoted, or evicted.
        boolean cacheChanged = evicted != null || !lruSnapshotEquals(before, snapshot);

        mainHandler.post(() -> {
            if (batteryUiDue) {
                applyBatteryToViews(battery);
            }
            if (mEvictCountText != null)
                mEvictCountText.setText("Evict cnt: " + evictTotal);
            if (cacheChanged)
                refreshLruPanel(snapshot, evicted, nearby, true);
        });
    }

    /**
     * Returns true when two LRU snapshots have identical name+hitCount sequences.
     * Used by handleUIUpdate to skip redundant panel redraws on ticks where the
     * nearby array is the same as the previous tick (same zone, all cache hits).
     */
    private static boolean lruSnapshotEquals(List<LruEntry> a, List<LruEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name.equals(b.get(i).name)) return false;
            if (a.get(i).hitCount != b.get(i).hitCount)  return false;
        }
        return true;
    }

    /**
     * Updates battery text, drive-time estimate, and progress bar color.
     * Must be called on the main thread.
     */
    private void applyBatteryToViews(int pct) {
        // Guard: may arrive via mainHandler.post after onDestroy
        if (mBatteryText == null) return;
        mBatteryText.setText("Batt: " + pct + "%");
        if (mDriveTimeText != null)
            mDriveTimeText.setText("Drive Time: " + (int)(pct * DRIVE_TIME_FACTOR) + "m");
        if (mBatteryBar != null) {
            mBatteryBar.setProgress(pct);
            int color = pct < 20 ? Color.parseColor("#E24B4A")
                      : pct < 40 ? Color.parseColor("#EF9F27")
                      :            Color.parseColor("#1D9E75");
            // Guard: getProgressDrawable() can return null on some AOSP builds when the
            // ProgressBar is detached or the drawable hasn't been inflated yet.
            android.graphics.drawable.Drawable progressDrawable = mBatteryBar.getProgressDrawable();
            if (progressDrawable != null) {
                progressDrawable.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    /** Enables or disables a button and adjusts its alpha accordingly. */
    private void setButtonState(Button btn, boolean enabled) {
        if (btn == null) return;
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.4f);
    }

    /**
     * Refreshes the driver preference panel with the latest accept/reject ratios.
     *
     * Format per category:
     *   EV ✅3/4  REST ✅1/2  SCENIC ✅0/1  SOCIAL ✅2/3
     *
     * Called on the main thread after every Accept, Defer, or Dismiss interaction
     * and also on each alert fire so the panel always reflects current state.
     * Gracefully no-ops when mPrefPanel is null (view not present in layout).
     */
    private void refreshPrefPanel() {
        if (mPrefPanel == null || mPrefs == null) return;
        String[] categories = { "EV_STATION", "REST_STOP", "SCENIC", "SOCIAL" };
        String[] labels     = { "EV",         "REST",      "SCENIC", "SOCIAL" };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < categories.length; i++) {
            String bits = mPrefs.rawBits(categories[i]);
            if (bits.isEmpty()) {
                sb.append(labels[i]).append(" —  ");
            } else {
                int total    = bits.length();
                int accepted = 0;
                for (int j = 0; j < total; j++) if (bits.charAt(j) == '1') accepted++;
                // Visual heat: green for high acceptance, amber for mid, red for low
                float ratio = (float) accepted / total;
                String icon = ratio >= 0.55f ? "✅" : ratio >= 0.25f ? "🟡" : "🔴";
                sb.append(labels[i]).append(" ").append(icon)
                  .append(accepted).append("/").append(total).append("  ");
            }
        }
        String line = sb.toString().trim();
        mPrefPanel.setText("Prefs: " + (line.isEmpty() ? "no data yet" : line));
        mPrefPanel.setTypeface(null, android.graphics.Typeface.BOLD);
        mPrefPanel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
    }

    // =========================================================================
    // Engine startup
    // =========================================================================

    private void startIntegratedEngine() {
        engineHandler.post(() -> {
            loadNativeLibrary();
            if (!nativeLibraryLoaded) {
                updateStatus("❌ Native load failed — aborting");
                return;
            }

            nativeInit(50);
            updateStatus("Seeding landmarks…");
            try { Thread.sleep(600); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

            seedLandmarks();
            mainHandler.post(() -> setButtonState(mStopButton, true));

            // Final panel refresh after all seeds complete
            final List<LruEntry> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(mLruEntries); }
            mainHandler.post(() -> refreshLruPanel(snapshot, null, null, false));
            updateStatus("Native engine ready");

            // Car API must initialise on main thread — use latch to wait
            CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> { initCarApi(); latch.countDown(); });
            try {
                if (!latch.await(5, TimeUnit.SECONDS))
                    updateStatus("⚠️ Car API init timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            initLocationProvider();
            mainHandler.post(this::setupTTS);
            mainHandler.post(() -> {
                setButtonState(mDriveButton, true);
                log("✅ Engine ready — tap Drive to start NH44 route");
            });
        });
    }

    private void loadNativeLibrary() {
        try {
            System.loadLibrary("landmark_native_lib");
            nativeLibraryLoaded = true;
            updateStatus("Library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "loadNativeLibrary failed: " + e.getMessage());
            updateStatus("❌ Link error: " + e.getMessage());
        }
    }

    /**
     * Loads landmarks from assets/landmarks.json into native engine and registry lists.
     * Single source of truth: add or remove entries in the JSON file only.
     */
    private void seedLandmarks() {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getAssets().open("landmarks.json")))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONArray landmarks = new JSONArray(sb.toString());
            int[] counter = {0};
            int total = landmarks.length();

            for (int i = 0; i < total; i++) {
                JSONObject lm  = landmarks.getJSONObject(i);
                String name    = lm.getString("name");
                String cat     = lm.getString("category");
                String detail  = lm.getString("detail");
                float  rating  = (float) lm.getDouble("rating");
                boolean social = lm.optBoolean("isSocial", false);

                // Authoritative category lookup — populated once, read-only thereafter
                mNameToCategory.put(name, cat);

                // Populate fallback alert registry lists by category
                switch (cat) {
                    case "EV_STATION": mEvNames.add(name);     mEvDetails.add(detail);     break;
                    case "SOCIAL":     mSocialNames.add(name); mSocialDetails.add(detail); break;
                    case "SCENIC":     mScenicNames.add(name); mScenicDetails.add(detail); break;
                    case "REST_STOP":  mRestNames.add(name);   mRestDetails.add(detail);   break;
                }
                seedOne(name, cat, detail, rating, social, counter, total);
            }
        } catch (Exception e) {
            Log.e(TAG, "landmarks.json load failed: " + e.getMessage());
            updateStatus("❌ Failed to load landmarks");
        }
    }

    private void seedOne(String name, String cat, String det,
                         float rating, boolean isSocial, int[] counter, int total) {
        nativeAddLandmark(name, cat, det, rating, isSocial);
        counter[0]++;

        String[] arrived = { name };
        String evicted   = computeEvictedName(arrived);
        if (evicted != null) mEvictTotal.incrementAndGet();

        // Mutate and snapshot in a single lock — no stale read gap
        final List<LruEntry> snapshot;
        synchronized (this) {
            mLruEntries.add(0, new LruEntry(name, resolveCategoryFromName(name)));
            if (mLruEntries.size() > LRU_DISPLAY_CAP)
                mLruEntries.remove(mLruEntries.size() - 1);
            snapshot = new ArrayList<>(mLruEntries);
        }

        updateStatus("Seeding " + counter[0] + "/" + total + " — " + name);
        log(String.format("📌 [%d/%d] Seeded: %s [%s]",
                counter[0], total, name, cat));
        // Use a token object so mainHandler.removeCallbacksAndMessages(SEED_UI_TOKEN) can
        // cancel any still-queued panel refresh before posting the fresher snapshot.
        // Without this, rapid seeding posts accumulate and the panel can render an older
        // snapshot on top of a newer one, producing duplicate or out-of-order rows.
        mainHandler.removeCallbacksAndMessages(SEED_UI_TOKEN);
        mainHandler.postAtTime(() -> refreshLruPanel(snapshot, null, null, false),
                SEED_UI_TOKEN, android.os.SystemClock.uptimeMillis());

        // Progressive delay: slower early so user can read, faster toward the end
        long delayMs = counter[0] <= 7          ? 400
                     : counter[0] <= total / 2  ? 250
                     : counter[0] <= total*3/4  ? 150
                     :                             80;
        try { Thread.sleep(delayMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // Car API
    // =========================================================================

    private void initCarApi() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("initCarApi() must run on main thread");
        try {
            Car car = Car.createCar(this);
            CarPropertyManager pm =
                    (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (pm == null) {
                updateStatus("Car API skipped (no property service)");
                return;
            }

            pm.registerCallback(new CarPropertyManager.CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue v) {
                    if (v.getPropertyId() != VehiclePropertyIds.EV_BATTERY_LEVEL) return;
                    // Clamp value from VHAL binder thread; post UI updates to main thread
                    int pct = Math.max(0, Math.min(100, Math.round((Float) v.getValue())));
                    mCurrentBatteryLevel.set(pct);
                    recordBatterySample(pct);
                    mainHandler.post(() -> applyBatteryToViews(pct));
                    // Guard: nativeLibraryLoaded may be false if onDestroy() races this callback.
                    engineHandler.post(() -> { if (isNativeReady()) nativeUpdateBattery(pct); });
                    log("🔋 VHAL battery: " + pct + "%");
                }

                @Override
                public void onErrorEvent(int p, int a) {
                    Log.w(TAG, "CarProperty error prop=" + p);
                }
            }, VehiclePropertyIds.EV_BATTERY_LEVEL, CarPropertyManager.SENSOR_RATE_ONCHANGE);

            updateStatus("Car API registered");
        } catch (Exception e) {
            Log.w(TAG, "Car API unavailable: " + e.getMessage());
            updateStatus("Car API skipped (emulator)");
        }
    }

    // =========================================================================
    // Location + TTS
    // =========================================================================

    private void initLocationProvider() {
        boolean isEmu = (mDiscoveryService instanceof UnifiedDiscoveryProvider)
                && ((UnifiedDiscoveryProvider) mDiscoveryService).isEmulatorMode();
        if (isEmu) {
            updateStatus("Emulator — running LRU demo");
            startLruDemo();
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean hasPerm = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean gpsOn = false;
        if (lm != null) {
            try { gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); }
            catch (Exception ignored) {}
        }
        if (lm != null && hasPerm && gpsOn) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 10,
                    loc -> processNewLocation(loc.getLatitude(), loc.getLongitude()),
                    engineThread.getLooper());
            updateStatus("GPS active");
        } else {
            updateStatus("No GPS — running LRU demo");
            startLruDemo();
        }
    }

    private void setupTTS() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("setupTTS() must run on main thread");
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        mFocusRequest = new AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build();
        if (mTts != null) { mTts.shutdown(); mTts = null; }
        mTts = new TextToSpeech(this, this);
        mTts.setAudioAttributes(attrs);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            mIsTtsReady    = true;
            mTtsRetryCount = 0;
            int r = mTts.setLanguage(java.util.Locale.US);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                mTts.setLanguage(java.util.Locale.getDefault());
            updateStatus("Audio ready");
        } else {
            mIsTtsReady = false;
            if (mTtsRetryCount < TTS_MAX_RETRIES) {
                mTtsRetryCount++;
                mainHandler.postDelayed(this::setupTTS, 2000);
            } else {
                updateStatus("TTS unavailable — alerts logged only");
            }
        }
    }

    // =========================================================================
    // Core location processing
    // =========================================================================

    void processNewLocation(double lat, double lon) {
        int batteryPct = Math.max(0, Math.min(100, mCurrentBatteryLevel.get()));
        int driveTime  = (int)(batteryPct * DRIVE_TIME_FACTOR);

        // Persist position so Resume Drive works on the real GPS path
        synchronized (this) {
            mSavedLat     = lat;
            mSavedLon     = lon;
            mSavedBattery = batteryPct;
        }

        log(String.format("📍 Location fix lat=%.4f lon=%.4f batt=%d%%",
                lat, lon, batteryPct));

        handleZoneSync(lat, lon);

        String[] nearby = mDiscoveryService.getNearby(lat, lon);
        // Null-guard: getNearby() may return null on emulator or when no landmarks are loaded.
        // All downstream consumers (computeEvictedName, handleAlert, for-loops) require non-null.
        if (nearby == null) nearby = new String[0];

        // Derive a readable landmark label from the first nearby entry for the status bar.
        if (nearby != null && nearby.length > 0 && nearby[0] != null) {
            mCurrentLandmarkLabel = "📍 " + nearby[0].replace("_", " ");
        }

        mainHandler.post(() -> {
            boolean alertVisible = mAlertCard != null
                    && mAlertCard.getVisibility() == View.VISIBLE;
            if (!alertVisible && statusText != null)
                statusText.setText(mCurrentLandmarkLabel);
        });

        String   evicted = computeEvictedName(nearby);
        if (evicted != null) log("✕ LRU EVICTED: " + evicted);

        handleUIUpdate(batteryPct, nearby, evicted, /* batteryUiDue= */ true);
        handleAlert(nearby, batteryPct);
    }

    /**
     * Returns the name of the LRU tail entry that would be evicted by the incoming arrivals,
     * or null if the cache has room or all arrivals are already cached.
     */
    private String computeEvictedName(String[] arrivedNames) {
        synchronized (this) {
            int newCount = 0;
            for (String name : arrivedNames) {
                boolean cached = false;
                for (LruEntry e : mLruEntries) {
                    if (e.name.equals(name)) { cached = true; break; }
                }
                if (!cached) newCount++;
            }
            if (newCount == 0 || mLruEntries.size() < LRU_DISPLAY_CAP) return null;
            return mLruEntries.get(mLruEntries.size() - 1).name;
        }
    }

    /** Extracts the category token from a native alert string of the form "Name (CAT): detail". */
    private String categoryFromAlert(String alert) {
        int open  = alert.indexOf('(');
        int close = alert.indexOf(')');
        if (open < 0 || close <= open) return "LANDMARK";
        return alert.substring(open + 1, close);
    }

    /**
     * Searches the registry for a nearby landmark by exact name first.
     * If no exact match, falls back to a registry entry of the same category whose
     * name shares a location token with the nearby key (e.g. "Hosur", "Krishnagiri").
     * Last resort: first registry entry of that category.
     * This decouples the nearby zone array from exact JSON landmark names.
     */
    private String findAlertInRegistry(String[] nearby,
                                        List<String> names, List<String> details,
                                        String category) {
        // Exact match first
        for (String n : nearby)
            for (int i = 0; i < names.size(); i++)
                if (names.get(i).equals(n))
                    return names.get(i) + " (" + category + "): " + details.get(i);

        // Category-presence fallback
        for (String n : nearby) {
            String cat = mNameToCategory.get(n);
            if (cat == null) cat = resolveCategoryFromName(n);
            if (!cat.equals(category)) continue;
            if (names.isEmpty()) return null;

            // Prefer a registry entry that shares a location token with the nearby key
            // e.g. nearby "Cafe_Adyar_Ananda_Bhavan_Hosur" → prefer entry containing "Hosur"
            String[] tokens = n.split("_");
            for (String token : tokens) {
                if (token.length() < 4) continue; // skip short tokens like "NH", "EV"
                for (int i = 0; i < names.size(); i++) {
                    if (names.get(i).contains(token))
                        return names.get(i) + " (" + category + "): " + details.get(i);
                }
            }
            // No token match — use first entry
            return names.get(0) + " (" + category + "): " + details.get(0);
        }
        return null;
    }

    // =========================================================================
    // Alert display formatting — per-category, context-aware
    // =========================================================================

    /**
     * Builds the two-line body shown on the alert card.
     *
     * Each category produces distinctly different language:
     *
     *   EV_STATION  — charger network name always shown (TANGEDCO vs Tesla matters).
     *                 Battery level drives urgency wording; no "break" language.
     *
     *   REST_STOP   — the ONLY category that uses break/rest language.
     *                 Wording escalates with continuous drive time:
     *                   < 30 min  → neutral ("well-rated stop ahead")
     *                   30–60 min → soft suggestion ("good time for a quick break")
     *                   > 60 min  → clear recommendation ("you've been driving N hrs…")
     *                 Specific name shown only when rating >= 4.5 — the rating IS the
     *                 decision signal for an unnamed rest stop.
     *
     *   SCENIC      — curiosity/discovery language. Never a break suggestion.
     *                 Always generic — viewpoint name adds no value over category.
     *
     *   SOCIAL      — discovery language, specific only when driver has shown consistent
     *                 interest (> 55% acceptance). Never a break suggestion.
     *
     * Raw landmark keys ("Cafe_Murugan_Vellore") are humanised by stripping underscores.
     */
    private String formatAlertBody(String rawAlert, String category, long driveMins) {
        // Parse "Name (CAT): detail"
        int parenOpen  = rawAlert.indexOf('(');
        int colonIdx   = rawAlert.indexOf(':');
        String rawName = (parenOpen > 0)
                ? rawAlert.substring(0, parenOpen).trim()
                : rawAlert;
        String detail  = (colonIdx > 0 && colonIdx < rawAlert.length() - 1)
                ? rawAlert.substring(colonIdx + 1).trim()
                : "";
        String displayName = rawName.replace("_", " ");
        float  rating      = parseRatingFromDetail(detail);

        switch (category) {

            case "EV_STATION":
                // Always specific — charger network/type is the information the driver needs.
                // Language is functional, not social; no break suggestion.
                return displayName + "\n" + detail;

            case "REST_STOP": {
                // Break language lives HERE and nowhere else.
                // Name shown only when highly rated — low-rated stop needs no introduction.
                String nameStr = (rating >= 4.5f)
                        ? displayName + "  " + formatRating(rating)
                        : null;

                String breakLine;
                if (driveMins >= 90) {
                    long hrs  = driveMins / 60;
                    long mins = driveMins % 60;
                    String elapsed = hrs > 0
                            ? hrs + "h " + (mins > 0 ? mins + "m" : "")
                            : mins + " min";
                    breakLine = "You've been driving " + elapsed.trim()
                            + " — a stop here is a good idea.";
                } else if (driveMins >= 60) {
                    breakLine = "Over an hour of driving — consider a break here.";
                } else if (driveMins >= 30) {
                    breakLine = "Good time for a quick stop if you need one.";
                } else {
                    // Drive is young — don't push a break. Just flag the stop.
                    breakLine = "Rest stop coming up" + (detail.isEmpty() ? "." : " — " + detail);
                    return (nameStr != null ? nameStr + "\n" : "") + breakLine;
                }

                // For ≥ 30 min: show name line (if rated) + break suggestion + detail
                StringBuilder sb = new StringBuilder();
                if (nameStr != null) sb.append(nameStr).append("\n");
                sb.append(breakLine);
                if (!detail.isEmpty()) sb.append("\n").append(detail);
                return sb.toString();
            }

            case "SCENIC":
                // Curiosity language — never a break suggestion.
                // Name omitted: "Viewpoint_Krishnagiri_Dam" means nothing at 120 km/h.
                return "A scenic viewpoint is coming up.\n" + detail;

            case "SOCIAL":
            default:
                // Discovery language — specific only when driver has shown strong interest.
                // Never use rest/break language for a café or restaurant.
                if (mPrefs.hasStrongAcceptance(category)) {
                    String ratingStr = rating > 0f ? "  " + formatRating(rating) : "";
                    return displayName + ratingStr + "\n" + detail;
                }
                // Generic discovery nudge — category-appropriate, not a break suggestion
                return (rating >= 4.0f)
                        ? "A highly rated stop is coming up.\n" + detail
                        : "A stop is coming up nearby.\n" + detail;
        }
    }

    /**
     * Builds a single concise line for the status bar and TTS.
     *
     * Rules:
     *  - Under ~10 words — readable at a glance on the cockpit display.
     *  - Each category has its own vocabulary; no category bleeds into another.
     *  - REST is the only category that mentions driving time or a break.
     *  - EV mentions battery level so the driver knows why the alert fired.
     *  - SCENIC and SOCIAL are informational, not action-demanding.
     */
    private String buildStatusText(String category, String rawAlert, int battery, long driveMins) {
        // Extract display name from "Name (CAT): detail"
        int parenOpen = rawAlert.indexOf('(');
        String rawName = (parenOpen > 0) ? rawAlert.substring(0, parenOpen).trim() : rawAlert;
        String name = rawName.replace("_", " ");

        switch (category) {

            case "EV_STATION":
                if (battery < 10)  return "Battery critical — charging station ahead now.";
                if (battery < 20)  return "Battery low — " + name + " charger ahead.";
                return name + " charger coming up.";

            case "REST_STOP":
                if (driveMins >= 90) {
                    long hrs  = driveMins / 60;
                    long mins = driveMins % 60;
                    String t  = hrs > 0 ? hrs + " hour" + (hrs > 1 ? "s" : "") : mins + " minutes";
                    return "Driving " + t + " — rest stop ahead.";
                }
                if (driveMins >= 60) return "Over an hour driving — rest stop ahead.";
                if (driveMins >= 30) return "Rest stop ahead if you need a break.";
                return "Rest stop coming up.";

            case "SCENIC":
                return "Scenic viewpoint coming up on your route.";

            case "SOCIAL":
            default:
                return mPrefs.hasStrongAcceptance(category)
                        ? name + " is just ahead."
                        : "Highly rated stop coming up.";
        }
    }

    /**
     * Extracts a numeric rating from a landmark detail string.
     * Handles patterns: "4.8★", "4.8 ★", "Rated 4.8", "Rating: 4.8"
     * Returns 0 if no rating is found.
     */
    private float parseRatingFromDetail(String detail) {
        if (detail == null || detail.isEmpty()) return 0f;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d\\.\\d)\\s*[★*]|[Rr]at(?:ed|ing)[:\\s]+(\\d\\.\\d)")
                .matcher(detail);
        if (m.find()) {
            String val = m.group(1) != null ? m.group(1) : m.group(2);
            try { return Float.parseFloat(val); }
            catch (NumberFormatException ignored) {}
        }
        return 0f;
    }

    /** Formats a rating float as "4.8★" for display. */
    private static String formatRating(float rating) {
        return String.format(java.util.Locale.US, "%.1f★", rating);
    }

    /**
     * Returns how many real-time minutes the driver has been driving without a break.
     * "Break" means: either the drive just started, or the driver last accepted a
     * REST_STOP alert / charging session — whichever happened more recently.
     * Returns 0 if the drive hasn't started yet.
     */
    private long continuousDriveMinutes() {
        long start = mDriveStartTimeMs;
        if (start == 0L) return 0L;
        long lastBreak = Math.max(start, mLastRestAcceptedMs);
        return (System.currentTimeMillis() - lastBreak) / 60_000L;
    }

    // =========================================================================
    // Alert pipeline
    // =========================================================================

    /**
     * Alert pipeline — LRU-first, simple, driver-focused.
     *
     * 1. Notify the native LRU engine of every nearby landmark so LRU order
     *    reflects the current zone (most-recently-passed landmark = MRU).
     * 2. Ask the native engine for the best alert via nativeGetAlert().
     *    selectBestAlert() scores every cached nearby item, picks the winner,
     *    promotes it to the LRU front, and returns the alert string — all in
     *    one native call.  No Java fallback chain required.
     * 3. Apply a minimal EV safety fallback ONLY when native returns empty AND
     *    the battery is critical — ensures the driver never misses a charge
     *    warning even if the native engine is briefly unavailable.
     * 4. Apply Java-side cooldown + driver-preference filter and fire the card.
     *
     * The Java fallback priority chain (REST/SCENIC/SOCIAL list lookups) has
     * been removed: those categories are now fully served by the LRU cache,
     * which is the entire point of this application.
     */
    private void handleAlert(String[] nearby, int battery) {
        int driveTime = (int)(battery * DRIVE_TIME_FACTOR);

        if (!isRunning) return;

        // ANR guard: handleAlert blocks the calling thread for up to 1 s via CountDownLatch
        // (Step 2 below) when not already on the engine thread. If processNewLocation() is
        // invoked by a LocationListener that fires on the main thread (possible on some AAOS
        // builds), this would freeze the UI. Route back through engineHandler in that case.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "handleAlert() called on main thread — rerouting to engineHandler");
            engineHandler.post(() -> handleAlert(nearby, battery));
            return;
        }

        // Startup grace window — suppress alerts for the first few seconds.
        long driveStart = mDriveStartTimeMs;
        if (driveStart > 0 && (System.currentTimeMillis() - driveStart) < ALERT_STARTUP_GRACE_MS) {
            long elapsed = (System.currentTimeMillis() - driveStart) / 1000;
            if (elapsed == 0) log("⏳ Alert grace window active (5.5s)…");
            return;
        }

        // ── Step 1: notify LRU of every nearby landmark (keeps LRU order honest) ──
        // Called on the engine thread to avoid deadlock.  Fire-and-forget — we
        // don't need to wait for promotion to complete before querying.
        boolean onEngineThread = (engineThread != null)
                && (Thread.currentThread() == engineThread.getLooper().getThread());
        for (String name : nearby) {
            if (onEngineThread) {
                if (isNativeReady()) nativeNotifyLandmarkHit(name);
            } else {
                engineHandler.post(() -> { if (isNativeReady()) nativeNotifyLandmarkHit(name); });
            }
        }

        // ── Step 2: query the LRU cache for the best alert ──────────────────────
        final String[] holder = {""};
        if (onEngineThread) {
            if (isNativeReady()) holder[0] = nativeGetAlert(nearby, driveTime);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            engineHandler.post(() -> {
                try { if (isNativeReady()) holder[0] = nativeGetAlert(nearby, driveTime); }
                finally { latch.countDown(); }
            });
            try {
                if (!latch.await(1, TimeUnit.SECONDS))
                    log("⚠️ nativeGetAlert timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Guard: if onDestroy fired while we were waiting, nativeLibraryLoaded is now
            // false and nativeDestroy() may have already run — drop this result entirely.
            if (!isRunning || !nativeLibraryLoaded) return;
        }
        if (!isRunning) return;

        String alert = holder[0];
        String cat   = (alert != null && !alert.isEmpty()) ? categoryFromAlert(alert) : null;

        // ── Step 3: Battery gate — suppress EV alerts when battery is healthy ───
        // An EV charger alert is only relevant when the battery is low. If the
        // native engine selected an EV station but the battery is still above the
        // threshold, drop the result so the driver is not nagged at 75%.
        // The gate does not apply to the critical (<10%) auto-accept path in
        // showAlertCard(), which fires unconditionally once an EV alert is already
        // being displayed.
        if ("EV_STATION".equals(cat) && battery > EV_INJECT_THRESHOLD) {
            log("🔋 EV alert suppressed — battery OK (" + battery + "% > threshold " + EV_INJECT_THRESHOLD + "%)");
            return;
        }

        // ── Step 4: EV safety fallback (cache miss + low battery only) ──────────
        // Only falls outside the LRU cache when the engine returned nothing AND the
        // battery is genuinely low — e.g. a cold start before seeding completes.
        if (cat == null && battery <= EV_INJECT_THRESHOLD) {
            alert = findAlertInRegistry(nearby, mEvNames, mEvDetails, "EV_STATION");
            if (alert != null) {
                cat = "EV_STATION";
                log("⚡ EV safety fallback — battery " + battery + "%");
            }
        }

        if (cat == null) return;

        // ── Step 4: Java-side cooldown + driver-preference filter ────────────────
        String  landmarkName = alert.contains("(") ? alert.substring(0, alert.indexOf('(')).trim() : alert;
        String  cooldownKey  = landmarkName + "|" + cat;
        long    now          = System.currentTimeMillis();
        boolean sameTarget   = cooldownKey.equals(mLastAlertLandmark);
        // Also suppress if same *category* fired recently — prevents a different
        // landmark of the same type from bypassing the cooldown after Accept/Dismiss.
        boolean sameCat      = cat.equals(mLastAlertCategory);
        boolean cooldownOk   = (now - mLastAlertTimeMs) >= ALERT_COOLDOWN_MS;
        boolean isEvAlert    = "EV_STATION".equals(cat);

        if (!isEvAlert && (sameTarget || sameCat) && !cooldownOk) {
            long remainSec = (ALERT_COOLDOWN_MS - (now - mLastAlertTimeMs)) / 1000;
            log("⛔ SUPPRESSED (cooldown " + remainSec + "s): " + cooldownKey);
            return;
        }
        log("📋 Pref [" + cat + "]: " + mPrefs.summary());
        if (!isEvAlert && !mPrefs.shouldShow(cat)) {
            log("🚫 SUPPRESSED (low preference): " + cooldownKey);
            return;
        }

        // Per-category caps for Scenario 1 — keeps the demo arc tidy and prevents
        // the same category from dominating the route. Checked BEFORE committing
        // the alert so the cooldown state is not dirtied on a suppressed alert.
        if (mScenarioIndex == 0) {
            if ("REST_STOP".equals(cat) && mRestAlertCount >= MAX_REST_ALERTS_S1) {
                log("⛔ SUPPRESSED (REST cap reached " + MAX_REST_ALERTS_S1 + "): " + cooldownKey);
                return;
            }
            if ("SCENIC".equals(cat) && mScenicAlertCount >= MAX_SCENIC_ALERTS_S1) {
                log("⛔ SUPPRESSED (SCENIC cap reached " + MAX_SCENIC_ALERTS_S1 + "): " + cooldownKey);
                return;
            }
            if ("SOCIAL".equals(cat) && mSocialAlertCount >= MAX_SOCIAL_ALERTS_S1) {
                log("⛔ SUPPRESSED (SOCIAL cap reached " + MAX_SOCIAL_ALERTS_S1 + "): " + cooldownKey);
                return;
            }
        }

        mLastAlertLandmark = cooldownKey;
        mLastAlertCategory = cat;
        mLastAlertTimeMs   = now;

        // Increment per-category counters after the alert is confirmed to fire.
        if ("REST_STOP".equals(cat))  mRestAlertCount++;
        if ("SCENIC".equals(cat))     mScenicAlertCount++;
        if ("SOCIAL".equals(cat))     mSocialAlertCount++;

        final String finalAlert = alert, finalCat = cat, finalKey = cooldownKey, icon = alertIcon(cat);
        final int    finalBatt  = battery;
        final long   driveMins  = continuousDriveMinutes();

        log(icon + " ALERT [LRU] [" + cat + "]: " + alert);

        mainHandler.post(() -> {
            mPendingAlertKey = finalKey; // raw key stored so onAlertAccept uses exact cooldown match
            refreshPrefPanel();
            showAlertCard(finalAlert, finalCat, finalBatt, driveMins);
            String statusLine = buildStatusText(finalCat, finalAlert, finalBatt, driveMins);
            handleTTS(statusLine, icon);
        });
    }

    /**
     * Delivers an audible + visible alert to the driver.
     *
     * Audio strategy (two-layer):
     *  1. A synthesised 880 Hz chime fires unconditionally via AudioTrack so the
     *     driver always gets an immediate audible cue — even on online AOSP emulators
     *     where TTS initialises successfully but routes audio to a virtual device
     *     with no physical speaker attached.
     *  2. TTS speech is attempted on top of the chime when the engine is ready and
     *     audio focus is granted, providing the full enriched text read-aloud on
     *     real hardware.
     *
     * The enriched text is mirrored to the status bar as the primary visible surface
     * regardless of audio state.
     */
    private void handleTTS(String enriched, String icon) {
        log("🔵 AI: " + enriched);

        // Always show in status bar — primary visible surface for the driver
        if (statusText != null) statusText.setText(icon + " " + enriched);

        // Chime — fires unconditionally; silently no-ops on no-HAL emulators
        playAlertChime();

        if (mIsTtsReady && mTts != null && mAudioManager != null) {
            int focusResult = mAudioManager.requestAudioFocus(mFocusRequest);
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mTts.speak(enriched, TextToSpeech.QUEUE_FLUSH, null, "LandmarkAlertID");
                log("🟢 TTS: speaking");
            } else {
                log("🔈 AudioFocus denied — chime only");
            }
        } else {
            log("🔔 TTS not ready — chime only");
        }
    }

    /**
     * Plays a short 880 Hz sine-wave beep (~300 ms) synthesised via AudioTrack.
     *
     * Two audio bugs were present in the original and are fixed here:
     *
     * Bug 1 — Wrong AudioAttributes usage type.
     *   The original used USAGE_MEDIA + CONTENT_TYPE_MUSIC. On AAOS the audio HAL
     *   routes streams by usage: USAGE_MEDIA maps to the media zone, which on the
     *   emulator is either muted or sent to a virtual device with no speaker output.
     *   That is why the Android boot chime (routed via the system session, not
     *   USAGE_MEDIA) is audible while this beep was not.
     *   Fixed to USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + CONTENT_TYPE_SONIFICATION,
     *   which the AAOS audio policy routes to the cabin speaker — the same zone used
     *   by TTS — ensuring the beep is heard on both emulator and real hardware.
     *
     * Bug 2 — Audio focus not requested before AudioTrack.play().
     *   handleTTS() requests focus for TTS, but playAlertChime() started its
     *   AudioTrack without holding focus. On AAOS with a strict audio policy the
     *   policy engine can silently duck or block an AudioTrack that has no focus,
     *   even after STATE_INITIALIZED is confirmed. Fixed by requesting focus via the
     *   shared mFocusRequest (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) before play() and
     *   abandoning it in the finally block so TTS or media resumes immediately.
     *
     * No resource file required — works on any AOSP build.
     * Wrapped in catch(Throwable) so audio-subsystem errors never crash the app.
     */
    private void playAlertChime() {
        new Thread("AlertChimeThread") {
            @Override public void run() {
            if (!isRunning) return; // Skip if the activity has been torn down

            AudioTrack track = null;
            boolean focusGranted = false;
            try {
                final int   sampleRate = 44100;
                final int   durationMs = 300;
                final int   numSamples = sampleRate * durationMs / 1000;
                final float frequency  = 880f;

                short[] samples = new short[numSamples];
                for (int i = 0; i < numSamples; i++) {
                    double envelope = 1.0 - (double) i / numSamples;
                    samples[i] = (short) (Short.MAX_VALUE * envelope
                            * Math.sin(2 * Math.PI * frequency * i / sampleRate));
                }

                int minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) minBuf = 4096;

                // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE routes to the cabin speaker
                // in the AAOS audio policy — the same zone as TTS.
                AudioAttributes chimeAttrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();

                int bufferBytes = Math.max(minBuf, numSamples * 2);
                track = new AudioTrack(
                        chimeAttrs,
                        new android.media.AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        bufferBytes,
                        AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE);

                // No-HAL emulators leave the track STATE_UNINITIALIZED — skip silently.
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    track.release();
                    track = null;
                    return;
                }

                // Request audio focus before play() so the AAOS policy engine does
                // not duck or block this track. Capture fields locally — mAudioManager
                // and mFocusRequest are nulled on the main thread during onDestroy.
                AudioManager am = mAudioManager;
                AudioFocusRequest req = mFocusRequest;
                if (am != null && req != null) {
                    focusGranted = (am.requestAudioFocus(req)
                            == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                }

                track.write(samples, 0, numSamples);
                track.play();
                Thread.sleep(durationMs + 50L);
                track.stop();

            } catch (Throwable t) {
                Log.w(TAG, "Chime playback failed: " + t.getMessage());
            } finally {
                if (track != null) {
                    try { track.release(); } catch (Throwable ignored) {}
                }
                // Abandon focus so TTS or media resumes at full volume immediately.
                if (focusGranted) {
                    AudioManager am = mAudioManager;
                    AudioFocusRequest req = mFocusRequest;
                    if (am != null && req != null) {
                        try { am.abandonAudioFocusRequest(req); } catch (Throwable ignored) {}
                    }
                }
            }
            } // end run()
        }.start();
    }

    /** Detects zone changes and triggers a cloud sync on the engine thread. */
    private void handleZoneSync(double lat, double lon) {
        String zoneId = "Z_" + (long)(lat / 0.02) + "_" + (long)(lon / 0.02);
        if (zoneId.equals(mCurrentZoneId)) return;
        if (!isSyncing.compareAndSet(false, true)) return;
        mCurrentZoneId = zoneId;
        log("───────────────────────────");
        log("📍 ZONE: " + zoneId);
        log("───────────────────────────");
        engineHandler.post(() -> {
            try { nativeSyncFromCloud(lat, lon); }
            finally { isSyncing.set(false); }
        });
    }

    // =========================================================================
    // Battery prediction
    // =========================================================================

    /** Records the two most recent battery samples for drain-rate estimation. */
    private synchronized void recordBatterySample(int battPct) {
        mPredSampleBatt1 = mPredSampleBatt2;
        mPredSampleTime1 = mPredSampleTime2;
        mPredSampleBatt2 = battPct;
        mPredSampleTime2 = System.currentTimeMillis();
    }

    /** Computes and logs drain rate, estimated time to empty, and time to EV alert threshold. */
    private synchronized void logBatteryPrediction(int currentBatt) {
        if (mPredSampleBatt1 < 0 || mPredSampleTime1 == 0) return;
        long  deltaMs   = mPredSampleTime2 - mPredSampleTime1;
        int   deltaBatt = mPredSampleBatt1 - mPredSampleBatt2;
        if (deltaMs <= 0 || deltaBatt <= 0) return;
        float drainPerMin = (deltaBatt * 60_000f) / deltaMs;
        float minsToEmpty = currentBatt / drainPerMin;
        float minsTo20    = (currentBatt > 20) ? (currentBatt - 20) / drainPerMin : 0f;
        String pred = String.format("drain=%.2f%%/min  empty≈%.0fmin  EValert≈%.0fmin",
                drainPerMin, minsToEmpty, minsTo20);
        log("🔋 " + pred);
    }

    // =========================================================================
    // Drive simulation — NH44 Bengaluru → Chennai
    // =========================================================================

    /**
     * Maps route step counter to NH44 zone landmark arrays for the current scenario.
     *
     * Scenario 1 (92 % battery): café surfaces immediately at steps 0–4, scenic in the
     * middle zones, EV stations dominant near the end when battery naturally drops below 20 %.
     *
     * Scenario 2 (50 % battery): EV stations lead every early zone so the EV alert fires
     * around step 30 when battery crosses 20 %. After charging to 85 %, rest stop and
     * scenic alerts surface in the later zones.
     *
     * Zone thresholds (each tick ≈ 1.2 s, drain 1 %/tick):
     *   steps  0–14  Bengaluru / Hosur
     *   steps 15–29  Krishnagiri
     *   steps 30–44  Vellore
     *   steps 45–59  Ranipet / Kanchipuram
     *   steps 60–74  Sriperumbudur (EV charging corridor before Chennai entry)
     *   steps 75+    ECR / Chennai (rest stop + city landmarks)
     */
    private String[] buildNearbyForStep(int step) {
        if (mScenarioIndex == 0) {
            // Scenario 1 arc (92% battery, ~5.5 min):
            //   steps  0–14  SOCIAL  — café fires early (Bengaluru/Hosur)
            //   steps 15–29  REST    — one rest stop alert (Krishnagiri)
            //   steps 30–49  SOCIAL  — café/restaurant alert in the middle (Vellore)
            //   steps 50–69  EV      — EV alert fires as battery dips toward 30% (Ranipet/Sriperumbudur)
            //   steps 70+    REST    — optional second rest + café near Chennai (ECR)
            //
            // Each zone exposes only the landmark category we want to fire —
            // other categories are deliberately absent to prevent the fallback
            // priority chain from picking the wrong alert type.
            if (step < 15)
                // SOCIAL only — café near Hosur on NH44
                return new String[]{ "Cafe_Adyar_Ananda_Bhavan_Hosur" };
            else if (step < 30)
                // REST only — one rest stop alert
                return new String[]{ "RestStop_NH44_Hosur", "RestStop_NH44_Krishnagiri" };
            else if (step < 50)
                // SOCIAL only — café/restaurant alert in the middle of the route
                return new String[]{ "Cafe_Murugan_Vellore", "Hotel_Annapoorna_Krishnagiri" };
            else if (step < 70)
                // EV only — battery is now ~38–25%; EV alert fires when battery ≤ 30%
                return new String[]{ "EV_ChargePoint_Ranipet", "EV_TNERC_Kanchipuram",
                                     "EV_ChargeGrid_Sriperumbudur" };
            else if (step < 85)
                // REST only — rest stop near ECR before Chennai entry
                return new String[]{ "RestStop_ECR_Chennai" };
            else
                // SOCIAL only — Cafe Sangeetha as sole candidate, cooldown fully cleared by step 85
                return new String[]{ "Cafe_Sangeetha_Chennai" };
        } else {
            // Scenario 2 arc (50% battery, ~3 min):
            //   steps  0–24  EV stations always present — alert fires once battery < 20%
            //                (at drain rate 1%/3 ticks, battery crosses 20% around step 90,
            //                 which at Scenario 2 start of 50% means step ~90 into the run;
            //                 but starting at 50% means battery = 20 at step ~90 — too late.
            //                 So keep EV in every zone and let the battery gate do the work.)
            //   steps 25–49  EV still present + REST so post-charge alerts work
            //   steps 50–74  REST + SCENIC after charging to 85%
            //   steps 75+    REST + SOCIAL near Chennai
            if (step < 25)
                return new String[]{ "EV_ChargeZone_Hosur", "EV_KSEB_Krishnagiri",
                                     "EV_TANGEDCO_Vellore" };
            else if (step < 50)
                return new String[]{ "EV_TANGEDCO_Vellore", "EV_ChargePoint_Ranipet",
                                     "RestStop_NH44_Vellore" };
            else if (step < 75)
                return new String[]{ "Viewpoint_Vellore_Fort", "RestStop_NH44_Ranipet",
                                     "EV_ChargeGrid_Sriperumbudur" };
            else
                return new String[]{ "RestStop_ECR_Chennai", "Cafe_Sangeetha_Chennai" };
        }
    }

    private static final double[][] NH44_PATH = {
        {12.8458, 77.6692}, // Bengaluru
        {12.7409, 77.8253}, // Hosur
        {12.5266, 78.2148}, // Krishnagiri
        {12.9165, 79.1325}, // Vellore
        {12.9815, 79.7154}, // Ranipet / Kanchipuram
        {13.0827, 80.2707}  // Chennai
    };

    private void startAutoDriveSimulation() {
        mLruDemoRunning = false;

        // Tear down any existing sim thread before starting a fresh one
        HandlerThread oldThread = simulationThread;
        simulationHandler = null;
        simulationThread  = null;
        if (oldThread != null) oldThread.quitSafely();

        // Set starting battery based on current scenario.
        // Scenario 1: full battery — café fires early, EV fires naturally near the end.
        // Scenario 2: half battery — EV fires around step 30, then charge → rest/scenic.
        boolean isFreshStart = (mSavedLat == DRIVE_START_LAT && mSavedLon == DRIVE_START_LON);
        if (isFreshStart) {
            int startBattery = (mScenarioIndex == 0) ? 92 : 50;
            synchronized (this) { mSavedBattery = startBattery; }
            mCurrentBatteryLevel.set(startBattery);
        }

        isRunning = true;
        String scenarioLabel = (mScenarioIndex == 0) ? "Scenario 1 — café › restaurant › EV"
                                                      : "Scenario 2 — EV › charge › rest/scenic";
        updateStatus("Drive active — " + scenarioLabel);
        if (mDriveButton != null) mDriveButton.setText("Stop Drive");
        mLastAlertLandmark = "";
        mLastAlertCategory = "";
        mLastAlertTimeMs   = 0L;
        mRestAlertCount    = 0;
        mScenicAlertCount  = 0;
        mSocialAlertCount  = 0;
        mDriveStartTimeMs  = System.currentTimeMillis();
        if (mLastRestAcceptedMs == 0L) mLastRestAcceptedMs = mDriveStartTimeMs;
        synchronized (this) {
            mPredSampleBatt1 = -1; mPredSampleTime1 = 0L;
            mPredSampleBatt2 = -1; mPredSampleTime2 = 0L;
        }

        simulationThread = new HandlerThread("DriveSimEngine");
        simulationThread.start();
        simulationHandler = new Handler(simulationThread.getLooper());

        // Capture resume state on main thread before posting to sim thread (happens-before)
        final double resumeLat, resumeLon;
        final int    resumeBattery, resumeStep;
        synchronized (this) {
            resumeLat     = mSavedLat;
            resumeLon     = mSavedLon;
            resumeBattery = mSavedBattery;
            resumeStep    = mRouteStep;
        }
        if (isFreshStart) {
            mRouteStep     = 0;
            mRouteComplete = false;
        }

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("▶ Drive started — " + scenarioLabel);
        log(String.format("   Lat:%.4f Lon:%.4f Bat:%d%% Step:%d",
                resumeLat, resumeLon, resumeBattery, resumeStep));
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Restore pathIdx from the saved position so a mid-route resume does not
        // snap the vehicle backward toward Bengaluru.
        int computedPathIdx = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < NH44_PATH.length - 1; i++) {
            // Distance from saved position to the START of each segment
            double dLat = resumeLat - NH44_PATH[i][0];
            double dLon = resumeLon - NH44_PATH[i][1];
            double dist = dLat * dLat + dLon * dLon;
            if (dist < minDist) {
                minDist   = dist;
                computedPathIdx = i;
            }
        }
        // Effectively-final captures required by the anonymous Runnable below.
        final int initBattery = resumeBattery;
        final int initStep    = resumeStep;
        final int initPathIdx = computedPathIdx;

        simulationHandler.post(new Runnable() {
            double lat  = resumeLat;
            double lon  = resumeLon;
            int battery = initBattery;
            int step    = initStep;
            int pathIdx = initPathIdx;

            @Override
            public void run() {
                if (!isRunning) return;

                // Interpolate toward next NH44 waypoint
                if (pathIdx < NH44_PATH.length - 1) {
                    double tLat = NH44_PATH[pathIdx + 1][0];
                    double tLon = NH44_PATH[pathIdx + 1][1];
                    lat += (tLat - lat) * 0.07;
                    lon += (tLon - lon) * 0.07;
                    // Snap to the waypoint when within ~2.2 km (0.02°) and advance the index.
                    // A tighter threshold (0.005°) was unreachable because the 5 % interpolation
                    // step halves the remaining gap each tick, asymptotically approaching zero.
                    if (Math.abs(lat - tLat) < 0.03 && Math.abs(lon - tLon) < 0.03) {
                        lat = tLat;
                        lon = tLon;
                        pathIdx++;
                        if (pathIdx == NH44_PATH.length - 1) mRouteComplete = true;
                    }
                }

                // Advance state — drain 1% every BATTERY_DRAIN_INTERVAL_TICKS ticks
                step++;
                if (step % BATTERY_DRAIN_INTERVAL_TICKS == 0) {
                    battery = Math.max(0, battery - 1);
                }
                mSavedLat = lat;  mSavedLon = lon;
                mSavedBattery = battery;  mRouteStep = step;
                mCurrentBatteryLevel.set(battery);
                recordBatterySample(battery);
                // Guard: nativeLibraryLoaded may become false if onDestroy() races this tick.
                engineHandler.post(() -> { if (isNativeReady()) nativeUpdateBattery(battery); });

                // Route completed on this tick — waypoint snap set mRouteComplete above.
                if (mRouteComplete) {
                    log("🏁 Destination Reached: Chennai");
                    mainHandler.post(() -> {
                        if (statusText != null)
                            statusText.setText("🏁 Chennai — destination arrived! Have a safe trip, hope the alerts were useful.");
                        showDestinationDialog(true);
                    });
                    stopSimulation();
                    return;
                }

                // Battery halt — only reachable when the route is not yet complete.
                // If the vehicle is within one segment of Chennai when power runs out,
                // treat it as a successful arrival rather than a dead-stop.
                if (battery <= 0) {
                    boolean nearDestination = pathIdx >= NH44_PATH.length - 2;
                    log(nearDestination
                            ? "🏁 Destination Reached: Chennai"
                            : "❌ VEHICLE POWER LOST. Halted.");
                    mainHandler.post(() -> {
                        if (nearDestination && statusText != null)
                            statusText.setText("🏁 Chennai — destination arrived! Have a safe trip, hope the alerts were useful.");
                        else
                            updateStatus("❌ Power lost");
                        showDestinationDialog(nearDestination);
                    });
                    stopSimulation();
                    return;
                }

                // Throttle battery bar refresh to avoid the gauge visibly ticking every tick.
                boolean batteryUiDue = (step % BATTERY_UI_INTERVAL_TICKS == 0);

                // Log every tick for the first 10 steps so the console is immediately
                // active after pressing Drive. After that throttle to UI-due ticks only.
                boolean shouldLog = (step <= 10) || batteryUiDue;
                if (shouldLog) {
                    String battLabel = battery < 20 ? "⚠️ CRITICAL"
                                     : battery < 40 ? "⚠️ LOW"
                                     :                "OK";
                    log(String.format("🚗 Lat:%.4f Lon:%.4f  Bat:%d%%  Step:%d  %s",
                            lat, lon, battery, step, battLabel));
                }

                String[] nearby  = buildNearbyForStep(step);
                String   evicted = computeEvictedName(nearby);
                if (evicted != null) log("✕ LRU EVICTED: " + evicted);

                handleUIUpdate(battery, nearby, evicted, batteryUiDue);
                handleAlert(nearby, battery);
                logBatteryPrediction(battery);

                Handler h = simulationHandler;
                if (isRunning && h != null) h.postDelayed(this, 1200);
            }
        });
    }

    /** Returns true if the native library is loaded; silently returns false otherwise. */
    private boolean isNativeReady() {
        if (!nativeLibraryLoaded) {
            Log.w(TAG, "isNativeReady: native not loaded");
            return false;
        }
        return true;
    }

    /**
     * Shows a dialog after the route ends.
     *
     * After Scenario 1 destination: prompts the user to tap Drive again for Scenario 2.
     * After Scenario 2 (or a non-destination stop): shows a "Demo complete" message.
     *
     * Must be called on the main thread.
     */
    private void showDestinationDialog(boolean reachedDestination) {
        // isFinishing()/isDestroyed() guard catches most cases, but a tiny window remains
        // between this check and AlertDialog.show() on older AAOS builds.  Wrap both
        // show() calls in try/catch so a stale WindowToken never crashes the app.
        if (isFinishing() || isDestroyed()) return;
        try {
            if (mScenarioIndex == 0 && reachedDestination) {
                // Advance to Scenario 2 so the next Drive tap starts it automatically.
                mScenarioIndex = 1;
                new android.app.AlertDialog.Builder(this)
                        .setTitle("🏁 Arrived in Chennai!")
                        .setMessage("Scenario 1 complete — café stop, restaurant suggestion, and rest stop demonstrated.\n\nTap Drive again to run Scenario 2: starting with 50 % battery, simulating an early EV alert, fast charge, and rest stop alerts.")
                        .setPositiveButton("Got it", null)
                        .setCancelable(true)
                        .show();
            } else {
                // Scenario 2 complete (or halted early).
                String msg = reachedDestination
                        ? "Both scenarios complete.\n\nScenario 1: café › restaurant › EV alert.\nScenario 2: EV alert › fast charge › rest/scenic alerts.\n\nTap Init Engine to restart the full demo."
                        : "Simulation halted (power lost).\n\nTap Init Engine to restart.";
                new android.app.AlertDialog.Builder(this)
                        .setTitle(reachedDestination ? "🎉 Demo Complete" : "⚡ Power Lost")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .setCancelable(true)
                        .show();
                // Reset scenario index so Init → Drive starts fresh from Scenario 1.
                mScenarioIndex = 0;
            }
        } catch (android.view.WindowManager.BadTokenException e) {
            // Activity window is already gone — log and ignore; no dialog is better than a crash.
            Log.w(TAG, "showDestinationDialog: window already detached, skipping dialog");
        }
    }

    private void stopSimulation() { stopSimulation(false); }

    private void stopSimulation(boolean userInitiated) {
        isRunning   = false;
        mIsCharging = false;
        HandlerThread thread = simulationThread;
        simulationHandler = null;
        simulationThread  = null;
        if (thread != null) thread.quitSafely();

        // Compute atStart under the same lock used to reset position — this prevents
        // the main thread from reading a stale mSavedLat before the reset is visible.
        final boolean atStart;
        if (mRouteComplete || userInitiated) {
            synchronized (this) {
                mSavedLat     = DRIVE_START_LAT;
                mSavedLon     = DRIVE_START_LON;
                mSavedBattery = DRIVE_START_BATTERY;
                mRouteStep    = 0;
                atStart = true;
            }
            mRouteComplete = false;
        } else {
            synchronized (this) {
                atStart = (mSavedLat == DRIVE_START_LAT && mSavedLon == DRIVE_START_LON);
            }
        }

        mDriveStartTimeMs   = 0L;
        mLastRestAcceptedMs = 0L;
        mLastAlertLandmark  = "";
        mLastAlertCategory  = "";
        mLastAlertTimeMs    = 0L;
        mCurrentLandmarkLabel = "En route…";

        mainHandler.post(() -> {
            if (mDriveButton != null) {
                mDriveButton.setText(atStart ? "Drive" : "Resume Drive");
                setButtonState(mDriveButton, true);
            }
            if (mStopButton != null) setButtonState(mStopButton, false);
        });
        updateStatus("Simulation stopped");
    }

    // =========================================================================
    // LRU Cache Demo — runs on engineThread, independent of GPS / drive state
    // =========================================================================

    void startLruDemo() {
        mLruDemoRunning = true;
        engineHandler.post(() -> {
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log("🧠 LRU CACHE DEMO — watch the panel");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            // Sequence designed to clearly show: ADD, HIT (promote), MISS (evict)
            String[][] sequence = {
                { "Cafe_Adyar_Ananda_Bhavan_Hosur",  "ADD     → new entry inserted" },
                { "RestStop_NH44_Hosur",              "ADD     → new entry inserted" },
                { "EV_ChargeZone_Hosur",              "ADD     → new entry inserted" },
                { "Viewpoint_Krishnagiri_Dam",        "ADD     → new entry inserted" },
                { "Cafe_Murugan_Vellore",             "ADD     → new entry inserted" },
                { "Hotel_Annapoorna_Krishnagiri",     "ADD     → new entry inserted" },
                { "RestStop_NH44_Krishnagiri",        "ADD     → cache now full (7/7)" },
                // Cache full — next new entry evicts LRU tail
                { "Cafe_Adyar_Ananda_Bhavan_Hosur",  "HIT     → promoted to top ↑" },
                { "EV_ChargeZone_Hosur",              "HIT     → promoted to top ↑" },
                { "EV_TANGEDCO_Vellore",              "MISS    → evicts LRU tail ✕" },
                { "Viewpoint_Vellore_Fort",           "MISS    → evicts LRU tail ✕" },
                { "Cafe_Adyar_Ananda_Bhavan_Hosur",  "HIT     → promoted to top ↑" },
                { "RestStop_NH44_Vellore",            "MISS    → evicts LRU tail ✕" },
                { "EV_ChargeZone_Hosur",              "HIT     → promoted to top ↑" },
            };

            for (String[] entry : sequence) {
                if (!mLruDemoRunning) {
                    log("⏹ LRU demo stopped");
                    break;
                }
                String name    = entry[0];
                String reason  = entry[1];
                String[] arrived = { name };
                String evicted   = computeEvictedName(arrived);
                if (evicted != null) mEvictTotal.incrementAndGet();

                final List<LruEntry> snapshot = updateLruState(arrived, evicted);
                final int evictTotal = mEvictTotal.get();

                mainHandler.post(() -> {
                    if (mEvictCountText != null)
                        mEvictCountText.setText("Evict cnt: " + evictTotal);
                    refreshLruPanel(snapshot, evicted, arrived, true);
                });

                log(String.format("%-34s %s", name.replace("_", " "), reason));
                if (evicted != null)
                    log("   ✕ evicted: " + evicted.replace("_", " "));

                try { Thread.sleep(1800); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (mLruDemoRunning) {
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log("✅ LRU demo done — tap Drive to start NH44");
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                mainHandler.post(() -> {
                    updateStatus("LRU demo done — tap Drive");
                    setButtonState(mStopButton, false);
                });
            }
        });
    }

    // =========================================================================
    // Status + utility
    // =========================================================================

    void updateStatus(String msg) {
        mainHandler.post(() -> {
            if (statusText != null) statusText.setText("Status: " + msg);
        });
    }

    /**
     * Null-safe log to UI console. Safe to call from any thread.
     * Guards against mLogManager being null after onDestroy() while a
     * background tick is still in flight.
     */
    private void log(String msg) {
        LogConsoleManager lm = mLogManager;
        if (lm != null) lm.logToUI(msg);
    }

    // ── Category helpers ──────────────────────────────────────────────────────

    /**
     * Returns the canonical category for a landmark name.
     *
     * Primary source: mNameToCategory, which is populated from the JSON "category"
     * field during seedLandmarks(). This is the authoritative lookup and works for
     * any naming convention — not just the EV_/RestStop_/Viewpoint_ prefixes.
     *
     * Fallback: prefix inference, kept only as a safety net for names that arrive
     * before seeding completes (e.g. the LRU demo sequence which runs immediately
     * after Init before the drive has started). In practice every demo landmark
     * name does follow the prefix convention, so the fallback is rarely reached.
     */
    private String resolveCategoryFromName(String name) {
        String cat = mNameToCategory.get(name);
        if (cat != null) return cat;
        // Prefix fallback — kept for LRU demo names and any future ad-hoc entries
        if (name.startsWith("EV_"))                                   return "EV_STATION";
        if (name.startsWith("RestStop_"))                             return "REST_STOP";
        if (name.startsWith("Viewpoint_") || name.startsWith("Park_")) return "SCENIC";
        return "SOCIAL";
    }

    private static String categoryLabel(String cat) {
        switch (cat) {
            case "EV_STATION": return "EV";
            case "REST_STOP":  return "Rest";
            case "SCENIC":     return "Scenic";
            default:           return "Social";
        }
    }

    private static String alertIcon(String cat) {
        switch (cat) {
            case "EV_STATION": return "🔋";
            case "REST_STOP":  return "🛑";
            case "SCENIC":     return "🏞️";
            default:           return "☕";
        }
    }

    /**
     * Returns a tinted background color for LRU rows (alpha=40) or the alert card (alpha=25).
     * Consolidates two formerly-identical switch blocks that differed only in alpha.
     */
    private static int categoryCardBg(String cat, int alpha) {
        switch (cat) {
            case "EV_STATION": return Color.argb(alpha, 29, 158, 117);  // teal
            case "REST_STOP":  return Color.argb(alpha, 186, 117, 23);  // amber
            case "SCENIC":     return Color.argb(alpha, 24, 95, 165);   // blue
            default:           return Color.argb(alpha, 83, 74, 183);   // purple
        }
    }

    private static int categoryTextColor(String cat) {
        switch (cat) {
            case "EV_STATION": return Color.parseColor("#00E676"); // bright green
            case "REST_STOP":  return Color.parseColor("#FFD740"); // bright amber
            case "SCENIC":     return Color.parseColor("#40C4FF"); // sky blue
            default:           return Color.parseColor("#E1BEE7"); // light lavender
        }
    }

    private static boolean isInArray(String[] arr, String val) {
        for (String s : arr) if (s.equals(val)) return true;
        return false;
    }

    /** Converts dp to pixels using the display density. */
    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }
}