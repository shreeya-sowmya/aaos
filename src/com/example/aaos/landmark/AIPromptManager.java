package com.example.aaos.landmark;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * AIPromptManager — on-device alert enrichment via Gemini Nano (AICore).
 *
 * <p>Generates a single TTS-ready sentence describing a landmark alert,
 * enriched with real-time context: battery level, drain rate, route zone,
 * and per-category driver preference history.
 *
 * <p><b>Current status:</b> AICore SDK integration is stubbed — all paths
 * route to the rule-based {@link #fallback} method. The fallback produces
 * category-specific, battery-aware sentences that are safe for TTS and
 * appropriate for cockpit display without any model dependency.
 *
 * <p><b>To enable real on-device inference:</b>
 * <ol>
 *   <li>Add to {@code Android.bp}:
 *       {@code static_libs: ["com.google.android.aicore"]}</li>
 *   <li>Add to {@code AndroidManifest.xml}:
 *       {@code <uses-permission android:name="android.permission.USE_ON_DEVICE_INTELLIGENCE"/>}</li>
 *   <li>Replace the body of {@link #runOnDeviceInference} with the real SDK call
 *       (see inline comment in that method).</li>
 *   <li>Confirm the system-feature string in {@link #isAICoreAvailable} against
 *       the final AICore SDK release notes.</li>
 * </ol>
 *
 * <p>Thread safety: {@link #generatePredictiveAlert} may be called from any thread.
 * The {@code callback} is always delivered on the main thread.
 */
public class AIPromptManager {

    private static final String TAG = "AIPromptManager";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;

    /** Delivers the enriched TTS sentence back to the caller on the main thread. */
    public interface AlertCallback {
        void onResult(String enrichedText);
    }

    public AIPromptManager(Context context) {
        this.context = context;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generates a single TTS-ready alert sentence for the given landmark event.
     *
     * <p>If AICore is available the prompt is sent for on-device inference.
     * Otherwise the rule-based fallback fires immediately — no latency penalty,
     * no dependency on model availability.
     *
     * @param eventType    Landmark category: {@code EV_STATION}, {@code REST_STOP},
     *                     {@code SCENIC}, {@code SOCIAL}, or {@code LANDMARK}.
     * @param rawAlert     Raw alert string from the native engine,
     *                     format: {@code "Name (CAT): detail"}.
     * @param batteryPct   Current EV battery level, clamped {@code [0, 100]}.
     * @param drainPerMin  Battery drain rate in %/min; {@code 0} if insufficient samples.
     * @param minsToEmpty  Estimated minutes to empty at current drain rate;
     *                     negative if unknown.
     * @param routeStep    Current NH44 route step counter used to derive zone label.
     * @param prefSummary  Compact driver preference history, e.g. {@code "SOCIAL:3/5"}.
     * @param callback     Receives the enriched sentence on the main thread.
     */
    public void generatePredictiveAlert(
            String eventType, String rawAlert, int batteryPct,
            double drainPerMin, double minsToEmpty,
            int routeStep, String prefSummary,
            AlertCallback callback) {

        String prompt = buildPrompt(eventType, rawAlert, batteryPct,
                                    drainPerMin, minsToEmpty, routeStep, prefSummary);
        Log.i(TAG, "Prompt → " + prompt);

        if (isAICoreAvailable()) {
            runOnDeviceInference(prompt, eventType, batteryPct, minsToEmpty, callback);
        } else {
            mainHandler.post(() -> callback.onResult(
                    fallback(eventType, batteryPct, minsToEmpty)));
        }
    }

    // =========================================================================
    // Prompt construction
    // =========================================================================

    /**
     * Builds the instruction prompt sent to Gemini Nano.
     *
     * <p>Constrained to one sentence / 20 words so the model output is safe to
     * pass directly to TTS without post-processing. Route zone is derived from
     * {@code routeStep} to provide spatial context without raw GPS coordinates.
     */
    private String buildPrompt(String eventType, String rawAlert, int battery,
                                double drainPerMin, double minsToEmpty,
                                int routeStep, String prefSummary) {

        String zone = routeStep < 15 ? "Bengaluru/Hosur"
                    : routeStep < 30 ? "Krishnagiri"
                    : routeStep < 45 ? "Vellore"
                    : routeStep < 60 ? "Ranipet/Kanchipuram"
                    :                  "approaching Chennai";

        String drainLine = drainPerMin > 0
                ? String.format("draining at %.1f%%/min (~%.0f min remaining)", drainPerMin, minsToEmpty)
                : "drain rate unavailable";

        return String.format(
            "You are a calm AAOS co-pilot. Reply in ONE sentence, max 20 words, suitable for TTS.\n"
          + "Event: %s | Alert: %s\n"
          + "Battery: %d%% (%s)\n"
          + "Location: %s on NH44 Bengaluru–Chennai\n"
          + "Driver preference: %s\n"
          + "Give one concrete, safety-first action. No filler words.",
            eventType, rawAlert, battery, drainLine, zone, prefSummary
        );
    }

    // =========================================================================
    // AICore integration
    // =========================================================================

    /**
     * Returns {@code true} when the device reports on-device AI capability.
     *
     * <p>Uses the standard PackageManager system-feature check so no SDK import
     * is required at compile time. When the AICore SDK is wired in via
     * {@code Android.bp}, supported hardware will automatically return {@code true}
     * here without requiring further changes.
     */
    private boolean isAICoreAvailable() {
        try {
            return context.getPackageManager()
                          .hasSystemFeature("android.hardware.on_device_ai");
        } catch (Exception e) {
            Log.w(TAG, "isAICoreAvailable check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sends {@code prompt} to Gemini Nano for inference and delivers the result
     * to {@code callback} on the main thread.
     *
     * <p>Replace the body of this method with the real SDK call when AICore is
     * available, for example:
     * <pre>
     *   InferenceModel model = InferenceModel.getInstance(context);
     *   model.generateAsync(prompt, result ->
     *       mainHandler.post(() -> callback.onResult(result.getText())));
     * </pre>
     * On any inference failure, fall back to {@link #fallback} so the driver
     * always receives a coherent alert regardless of model availability.
     */
    private void runOnDeviceInference(String prompt, String eventType,
                                       int batteryPct, double minsToEmpty,
                                       AlertCallback callback) {
        Log.w(TAG, "runOnDeviceInference: AICore not yet integrated — using rule-based fallback");
        mainHandler.post(() -> callback.onResult(fallback(eventType, batteryPct, minsToEmpty)));
    }

    // =========================================================================
    // Rule-based fallback
    // =========================================================================

    /**
     * Produces a TTS-safe alert sentence without any model dependency.
     *
     * <p>Used whenever AICore is unavailable or inference fails. Each category
     * produces distinct language — in particular, break/rest phrasing is confined
     * to {@code REST_STOP} so that EV, scenic, and social alerts are never
     * inadvertently presented as a suggestion to stop for a rest.
     *
     * <p>EV alerts are split into three battery-severity tiers and embed the
     * time-to-empty estimate when the drain rate is available.
     *
     * @param eventType   Landmark category string.
     * @param battery     Current battery percentage, clamped {@code [0, 100]}.
     * @param minsToEmpty Estimated minutes to empty; negative if unknown.
     * @return A single sentence, under 20 words, safe for TTS.
     */
    private String fallback(String eventType, int battery, double minsToEmpty) {
        switch (eventType) {

            case "BATTERY_LOW":
            case "EV_STATION":
                if (battery < 10) {
                    return "Battery critical at " + battery
                            + "% — pull into the next charger immediately.";
                }
                if (battery < 20) {
                    String suffix = minsToEmpty > 0
                            ? String.format(" (~%.0f min remaining).", minsToEmpty)
                            : ".";
                    return "Battery very low at " + battery + "%" + suffix
                            + " Charge at the next available station.";
                }
                return "Battery at " + battery + "% — plan a charging stop soon.";

            case "REST_STOP":
                // REST_STOP is the only category that uses break or rest language.
                return "You've been driving a while — a rest stop is coming up ahead.";

            case "SCENIC":
                // Curiosity language — no break suggestion.
                return "Scenic viewpoint coming up — worth a short stop if time allows.";

            case "SOCIAL":
                // Discovery language — no break suggestion.
                return "Highly rated stop ahead — check the display for details.";

            case "LANDMARK":
                // Generic fallback when category cannot be determined more precisely.
                return "Point of interest nearby — check the display for details.";

            default:
                Log.w(TAG, "fallback: unhandled eventType '" + eventType + "'");
                return "Nearby point of interest detected — check the display for details.";
        }
    }
}