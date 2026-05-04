package com.example.aaos.landmark;

import android.content.Context;
import android.location.LocationManager;
import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * UnifiedDiscoveryProvider
 *
 * Single implementation that works on BOTH emulator and real hardware
 * without any code change or flag flip.
 *
 * Auto-detection strategy (checked in order):
 *   1. ro.kernel.qemu == "1"       → Cuttlefish / QEMU kernel
 *   2. ro.product.model            → contains "cuttlefish", "sdk", "goldfish"
 *   3. GPS provider absent         → Cuttlefish has no real GPS by default
 *   4. assets/landmarks.csv absent → safety fallback to emulator mode
 *
 * Emulator  → getNearby() returns array last set by setMockData()
 * Real HW   → getNearby() returns cached cloud API result (async refresh
 *             every GPS tick), falls back to haversine on landmarks.csv if
 *             network is unavailable or the API errors.
 *
 * The rest of the app uses this via LandmarkDiscoveryProvider interface
 * and is completely unaware of which path is active.
 */
public class UnifiedDiscoveryProvider implements LandmarkDiscoveryProvider {

    private static final String TAG    = "UnifiedDiscovery";
    private static final String CSV_ASSET = "landmarks.csv";
    private static final float  RADIUS_KM = 2.0f;

    // ── Cloud API config ──────────────────────────────────────────────────────
    private static final String API_BASE   = "https://api.yourbackend.com/landmarks/nearby";
    private static final String API_KEY    = "YOUR_API_KEY_HERE";
    private static final int    CONNECT_MS = 3000;
    private static final int    READ_MS    = 3000;

    // ── Async cache ───────────────────────────────────────────────────────────
    // GPS tick always reads mCloudCache — background thread refreshes it.
    // Never blocks the drive loop regardless of network latency.
    private volatile String[]  mCloudCache       = new String[0];
    private volatile boolean   mCloudFetchActive = false;  // prevents parallel fetches

    // ── Internal state ────────────────────────────────────────────────────────

    static class LandmarkRecord {
        final String name;
        final double lat;
        final double lon;
        LandmarkRecord(String name, double lat, double lon) {
            this.name = name; this.lat = lat; this.lon = lon;
        }
    }

    private final boolean              isEmulator;
    private final List<LandmarkRecord> allLandmarks;  // CSV records — used as fallback
    private volatile String[]          mockNearby = new String[0];

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Production constructor — auto-detects environment */
    public UnifiedDiscoveryProvider(Context ctx) {
        isEmulator   = detectEmulator(ctx);
        allLandmarks = isEmulator ? new ArrayList<>() : loadFromAssets(ctx);
        Log.i(TAG, "Mode: " + (isEmulator
                ? "EMULATOR (mock data)"
                : "REAL HW (" + allLandmarks.size() + " CSV landmarks as fallback)"));
    }

    /** Test constructor */
    UnifiedDiscoveryProvider(List<LandmarkRecord> records) {
        isEmulator = false; allLandmarks = records;
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    @Override
    public String[] getNearby(double lat, double lon) {
        if (isEmulator) return mockNearby;

        // Kick off async cloud refresh — non-blocking, returns immediately
        refreshCacheAsync(lat, lon);

        // Return last cached result.
        // First tick returns empty [] — fills within one background cycle (~1-2s)
        return mCloudCache;
    }

    /** Called by LRU demo on emulator — no-op on real HW */
    public void setMockData(String[] nearby) {
        if (isEmulator) mockNearby = (nearby != null) ? nearby : new String[0];
    }

    public boolean isEmulatorMode() { return isEmulator; }

    // ── Async cloud fetch ─────────────────────────────────────────────────────

    /**
     * Fires a background thread to refresh mCloudCache.
     * Skips if a fetch is already in flight — no parallel requests.
     */
    private void refreshCacheAsync(double lat, double lon) {
        if (mCloudFetchActive) return;
        mCloudFetchActive = true;
        new Thread(() -> {
            try {
                String[] fresh = fetchFromCloud(lat, lon);
                if (fresh != null) {
                    mCloudCache = fresh;
                    Log.d(TAG, "☁️ Cloud cache updated — " + fresh.length + " nearby");
                }
            } finally {
                mCloudFetchActive = false;   // always release even on error
            }
        }, "CloudFetchThread").start();
    }

    /**
     * Synchronous HTTP call — always runs on CloudFetchThread, never on GPS/UI thread.
     * Returns null on unrecoverable error; caller falls back to CSV haversine.
     *
     * Expected API response:
     *   { "nearby": ["EV_ChargeZone_Hosur", "RestStop_NH44_Hosur"] }
     *
     * Names in the response must match landmark names seeded via landmarks.json
     * so the native LRU cache can resolve them.
     */
    private String[] fetchFromCloud(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            String endpoint = String.format(
                    "%s?lat=%.6f&lon=%.6f&radius=%.1f",
                    API_BASE, lat, lon, RADIUS_KM);

            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status != 200) {
                Log.w(TAG, "Cloud API HTTP " + status + " — falling back to CSV");
                return fallbackHaversine(lat, lon);
            }

            String   body = readResponse(conn);
            JSONArray arr = new JSONObject(body).getJSONArray("nearby");
            String[] result = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) result[i] = arr.getString(i);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Cloud fetch failed: " + e.getMessage() + " — falling back to CSV");
            return fallbackHaversine(lat, lon);

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── CSV fallback ──────────────────────────────────────────────────────────

    /**
     * Haversine lookup against locally loaded CSV.
     * Used when cloud API is unavailable, times out, or returns a non-200.
     * Guarantees the app keeps working offline.
     */
    private String[] fallbackHaversine(double lat, double lon) {
        List<String> result = new ArrayList<>();
        for (LandmarkRecord r : allLandmarks) {
            if (haversineKm(lat, lon, r.lat, r.lon) <= RADIUS_KM)
                result.add(r.name);
        }
        Log.w(TAG, "📂 CSV fallback — " + result.size() + " nearby");
        return result.toArray(new String[0]);
    }

    // ── Environment detection ─────────────────────────────────────────────────

    private boolean detectEmulator(Context ctx) {
        // 1. QEMU kernel flag
        if ("1".equals(SystemProperties.get("ro.kernel.qemu", "0"))) {
            Log.i(TAG, "Emulator: ro.kernel.qemu=1"); return true;
        }
        // 2. Product model
        String model = SystemProperties.get("ro.product.model", "").toLowerCase();
        if (model.contains("cuttlefish") || model.contains("sdk")
                || model.contains("emulator") || model.contains("goldfish")) {
            Log.i(TAG, "Emulator: model=" + model); return true;
        }
        // 3. No GPS provider
        LocationManager lm = (LocationManager)
                ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && !lm.getAllProviders()
                             .contains(LocationManager.GPS_PROVIDER)) {
            Log.i(TAG, "Emulator: no GPS provider"); return true;
        }
        // 4. CSV absent — safety fallback
        try { ctx.getAssets().open(CSV_ASSET).close(); }
        catch (IOException e) {
            Log.w(TAG, "landmarks.csv absent — emulator mode"); return true;
        }
        Log.i(TAG, "Real hardware confirmed"); return false;
    }

    // ── CSV loading ───────────────────────────────────────────────────────────

    private List<LandmarkRecord> loadFromAssets(Context ctx) {
        List<LandmarkRecord> records = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(CSV_ASSET)))) {
            String line; boolean hdr = false;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!hdr) { hdr = true; if (line.startsWith("name")) continue; }
                String[] c = line.split(",", 7);
                if (c.length < 3) continue;
                try {
                    records.add(new LandmarkRecord(c[0].trim(),
                            Double.parseDouble(c[1].trim()),
                            Double.parseDouble(c[2].trim())));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            Log.e(TAG, "Cannot open " + CSV_ASSET + ": " + e.getMessage());
        }
        return records;
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private static String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    // ── Haversine (package-private for tests) ─────────────────────────────────

    static float haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon/2)*Math.sin(dLon/2);
        return (float)(6371.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a)));
    }
}
