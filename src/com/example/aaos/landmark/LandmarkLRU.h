#ifndef LANDMARK_LRU_H
#define LANDMARK_LRU_H

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>
#include <chrono>
#include <cmath>

// ── Node ──────────────────────────────────────────────────────────────────────
// Base doubly-linked list node. Every cached landmark is a Node.
// prev/next form the LRU eviction order: head = most recent, tail = evict next.
struct Node {
    std::string name, category, details;
    double lat = 0.0;
    double lon = 0.0;
    Node* prev = nullptr;
    Node* next = nullptr;
};

// ── DiscoveryNode ─────────────────────────────────────────────────────────────
// Extends Node with a rating field used by the cloud quality gate (>= 4.5)
// and by selectBestAlert() scoring.
struct DiscoveryNode : Node {
    float rating = 0.0f;
};

// ── LandmarkLRU ───────────────────────────────────────────────────────────────
class LandmarkLRU {
private:
    int capacity;

    // Primary LRU cache — pre-seeded known landmarks (EV stations, parks, cafés).
    // Key = landmark name. Value = heap-allocated DiscoveryNode.
    // LRU order maintained by the head/tail doubly-linked list.
    std::unordered_map<std::string, Node*> cache;

    // Cloud discovery buffer — high-rated social gems fetched by syncFromCloudNative.
    // Swapped atomically under dataMutex on every successful cloud response.
    // Items here are NOT in the LRU list; they serve as a read-only bonus pool.
    std::unordered_map<std::string, DiscoveryNode*> discoveryBuffer;

    // LRU list pointers. head = most recently used, tail = least recently used.
    Node* head = nullptr;
    Node* tail = nullptr;

    // Protects cache, discoveryBuffer, head, tail, and suppression state.
    std::mutex dataMutex;

    // ── Re-alert suppression ─────────────────────────────────────────────────
    // Tracks the name of the most recently alerted landmark and the wall-clock
    // time when that alert was issued.  selectBestAlert() skips any candidate
    // whose name matches lastAlertedName within SUPPRESS_WINDOW_MS milliseconds,
    // preventing the same landmark from alerting on every GPS fix.
    //
    // SUPPRESS_WINDOW_MS matches Java's ALERT_COOLDOWN_MS (30 s) so only one
    // suppression layer fires at a time — no double-suppression.
    std::string lastAlertedName;
    std::chrono::steady_clock::time_point lastAlertTime;
    static constexpr int SUPPRESS_WINDOW_MS = 30'000;  // 30 s — matches Java ALERT_COOLDOWN_MS

    // Proximity threshold in degrees (~111 m at the equator per 0.001°).
    // Used by checkProximity() for the fast cache-only lookup path.
    static constexpr double PROXIMITY_DEG = 0.018;  // ~2 km

    // ── Internal list helpers ─────────────────────────────────────────────────
    void removeNode(Node* node);
    void addToFront(Node* node);

public:
    explicit LandmarkLRU(int cap) : capacity(cap) {}
    ~LandmarkLRU();

    // addLandmark — inserts or refreshes a landmark in the LRU cache.
    void addLandmark(std::string name, std::string cat,
                     std::string det, float rating, bool isSocial);

    // addLandmark with coordinates — preferred overload when lat/lon are known.
    void addLandmark(std::string name, std::string cat, std::string det,
                     float rating, bool isSocial, double lat, double lon);

    // syncFromCloudNative — fetches high-rated social landmarks from the OEM
    // cloud API via libcurl and atomically replaces the discoveryBuffer.
    void syncFromCloudNative(double lat, double lon);

    // selectBestAlert — looks up every nearby landmark ID in the LRU cache,
    // scores by category (EV urgent=100, REST=60, SCENIC=40, SOCIAL=20),
    // promotes the winner to the LRU front, and returns its alert string.
    // Returns "" when no nearby landmark is cached or all are suppressed.
    std::string selectBestAlert(const std::vector<std::string>& nearby,
                                int battery, int driveTime);

    // checkProximity — fast Euclidean check against cached landmarks.
    // Returns the name of the nearest landmark within PROXIMITY_DEG, or "".
    // Used by nativeCheckProximity() JNI call.
    std::string checkProximity(double lat, double lon);

    // notifyHit — promotes a landmark to the front of the LRU list on a
    // direct zone-traversal event. Called from Java via nativeNotifyLandmarkHit
    // on every sim tick so the LRU order reflects the driver's actual path.
    void notifyHit(const std::string& name);
};

#endif // LANDMARK_LRU_H