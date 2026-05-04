/**
 * LandmarkCacheJNI.cpp
 *
 * JNI bridge between LandmarkCacheApp.java and the native LandmarkLRU engine.
 *
 * Threading model
 * ────────────────
 * 1. gEngine is a std::shared_ptr. Cloud-sync lambdas capture a copy so a
 *    re-init (nativeDestroy + nativeInit) never leaves a dangling raw pointer
 *    in a running thread.
 *
 * 2. gSyncThread is joinable. nativeDestroy joins it before releasing the
 *    engine so Activity tear-down is clean.
 *
 * 3. gBatteryLevel is std::atomic<int> to prevent torn reads from concurrent
 *    JNI calls on binder threads.
 *
 * 4. gEngineMutex serialises init/destroy so two threads can't race on startup.
 *
 * Testability
 * ───────────
 * Each JNI function has a single responsibility and delegates immediately to
 * LandmarkLRU methods, keeping the bridge thin and the engine unit-testable
 * without JNI.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <memory>
#include <mutex>
#include <thread>
#include <android/log.h>

#include "LandmarkLRU.h"

#define LOG_TAG "LandmarkCacheJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────────────

static std::shared_ptr<LandmarkLRU> gEngine;   // shared_ptr: safe cross-thread capture
static std::atomic<int>  gBatteryLevel{100};   // atomic: written from Java battery callback
static std::mutex        gEngineMutex;         // serialises init / destroy
static std::thread       gSyncThread;          // joinable cloud-sync thread

// ── Helpers ───────────────────────────────────────────────────────────────────

static std::string jstringToStd(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ── JNI functions ─────────────────────────────────────────────────────────────

extern "C" {

/**
 * nativeInit — creates the LRU engine.
 * Safe to call again after nativeDestroy (Activity restart).
 */
JNIEXPORT void JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeInit(
        JNIEnv* /*env*/, jobject /*thiz*/, jint cap) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        LOGI("nativeInit: engine already exists, skipping re-create");
        return;
    }
    gEngine = std::make_shared<LandmarkLRU>(static_cast<int>(cap));
    LOGI("nativeInit: engine created (capacity=%d)", static_cast<int>(cap));
}

/**
 * nativeDestroy — joins the sync thread, then destroys the engine.
 * Must be called from Activity.onDestroy() on the main thread.
 */
JNIEXPORT void JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gSyncThread.joinable()) {
        LOGI("nativeDestroy: joining sync thread");
        gSyncThread.join();
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine.reset();
        LOGI("nativeDestroy: engine destroyed");
    }
}

/**
 * nativeAddLandmark — inserts one landmark into the LRU cache.
 */
JNIEXPORT void JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeAddLandmark(
        JNIEnv* env, jobject /*thiz*/,
        jstring name, jstring cat, jstring det,
        jfloat rating, jboolean isSocial) {
    std::shared_ptr<LandmarkLRU> engine;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engine = gEngine;
    }
    if (!engine) { LOGE("nativeAddLandmark: engine is null"); return; }

    engine->addLandmark(
        jstringToStd(env, name),
        jstringToStd(env, cat),
        jstringToStd(env, det),
        static_cast<float>(rating),
        static_cast<bool>(isSocial));
}

/**
 * nativeUpdateBattery — updates the battery level atomically.
 * Called from CarPropertyManager callback (may be on a binder thread).
 */
JNIEXPORT void JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeUpdateBattery(
        JNIEnv* /*env*/, jobject /*thiz*/, jint level) {
    gBatteryLevel.store(static_cast<int>(level), std::memory_order_relaxed);
    LOGI("nativeUpdateBattery: %d%%", static_cast<int>(level));
}

/**
 * nativeSyncFromCloud — triggers a managed (joinable) background sync.
 *
 * Only one sync runs at a time. A previous sync is joined before the new one
 * starts, preventing unbounded thread growth.
 *
 * The lambda captures a copy of the shared_ptr so the engine outlives any
 * Activity re-creation that might call nativeDestroy mid-flight.
 */
JNIEXPORT void JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeSyncFromCloud(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble lat, jdouble lon) {
    std::shared_ptr<LandmarkLRU> engine;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engine = gEngine;
    }
    if (!engine) { LOGE("nativeSyncFromCloud: engine is null"); return; }

    if (gSyncThread.joinable()) {
        LOGI("nativeSyncFromCloud: joining previous sync thread");
        gSyncThread.join();
    }

    gSyncThread = std::thread([engine, lat, lon]() {
        LOGI("syncThread: starting sync at (%.4f, %.4f)", lat, lon);
        engine->syncFromCloudNative(lat, lon);
        LOGI("syncThread: sync complete");
    });
}

/**
 * nativeGetAlert — queries the LRU cache for the best alert.
 * Returns an empty string (not null) if the engine is uninitialised.
 */
JNIEXPORT jstring JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeGetAlert(
        JNIEnv* env, jobject /*thiz*/,
        jobjectArray nearby, jint driveTime) {
    std::shared_ptr<LandmarkLRU> engine;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engine = gEngine;
    }
    if (!engine) return env->NewStringUTF("");

    std::vector<std::string> proximity;
    jsize len = env->GetArrayLength(nearby);
    proximity.reserve(len);
    for (jsize i = 0; i < len; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(nearby, i));
        proximity.push_back(jstringToStd(env, js));
        env->DeleteLocalRef(js);
    }

    int battLevel = gBatteryLevel.load(std::memory_order_relaxed);
    std::string alert = engine->selectBestAlert(proximity, battLevel, static_cast<int>(driveTime));
    LOGI("nativeGetAlert: returning \"%s\"", alert.c_str());
    return env->NewStringUTF(alert.c_str());
}

/**
 * nativeCheckProximity — returns the name of a cached landmark near the given
 * coordinates, or an empty string if none match within the engine's proximity
 * threshold.
 *
 * Note: This is a lightweight spatial check against the LRU cache.
 * For full haversine lookup against the CSV dataset use UnifiedDiscoveryProvider
 * on the Java side.
 */
JNIEXPORT jstring JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeCheckProximity(
        JNIEnv* env, jobject /*thiz*/,
        jdouble lat, jdouble lon) {
    std::shared_ptr<LandmarkLRU> engine;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engine = gEngine;
    }
    if (!engine) return env->NewStringUTF("");

    // Delegate to the engine's proximity check so coordinates and threshold
    // are data-driven rather than hardcoded here.
    std::string result = engine->checkProximity(lat, lon);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_aaos_landmark_LandmarkCacheApp_nativeNotifyLandmarkHit(
        JNIEnv* env, jobject /*thiz*/, jstring landmarkName) {
    std::shared_ptr<LandmarkLRU> engine;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engine = gEngine;
    }
    if (!engine) { LOGE("nativeNotifyLandmarkHit: engine is null"); return; }

    std::string name = jstringToStd(env, landmarkName);
    LOGI("nativeNotifyLandmarkHit: '%s'", name.c_str());
    // Promote the hit landmark to the front of the LRU list.
    engine->notifyHit(name);
}

} // extern "C"