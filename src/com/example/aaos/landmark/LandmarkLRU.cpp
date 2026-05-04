#include "LandmarkLRU.h"
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <android/log.h>

using json = nlohmann::json;

#define LOG_TAG "LandmarkCacheJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Internal list helpers ─────────────────────────────────────────────────────

/**
 * removeNode
 *
 * Unlinks a node from the doubly-linked LRU list in O(1).
 * Caller must hold dataMutex.
 */
void LandmarkLRU::removeNode(Node* node) {
    if (node->prev) node->prev->next = node->next;
    else            head = node->next;

    if (node->next) node->next->prev = node->prev;
    else            tail = node->prev;

    node->prev = nullptr;
    node->next = nullptr;
}

/**
 * addToFront
 *
 * Inserts a node at the head of the doubly-linked list (most recently used).
 * O(1). Caller must hold dataMutex.
 */
void LandmarkLRU::addToFront(Node* node) {
    node->next = head;
    node->prev = nullptr;
    if (head) head->prev = node;
    head = node;
    if (!tail) tail = node;
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * addLandmark (without coordinates)
 *
 * Inserts or refreshes a landmark. Coordinates default to (0, 0).
 * Use the overload below when lat/lon are available for proximity checks.
 */
void LandmarkLRU::addLandmark(std::string name, std::string cat,
                               std::string det, float rating, bool isSocial) {
    addLandmark(std::move(name), std::move(cat), std::move(det),
                rating, isSocial, 0.0, 0.0);
}

/**
 * addLandmark (with coordinates)
 *
 * Preferred overload. Stores lat/lon so checkProximity() can do a real
 * distance check without a separate lookup table.
 *
 * On capacity overflow the least-recently-used tail entry is evicted first.
 * Thread-safe: acquires dataMutex for the entire operation.
 */
void LandmarkLRU::addLandmark(std::string name, std::string cat,
                               std::string det, float rating,
                               bool /*isSocial*/, double lat, double lon) {
    std::lock_guard<std::mutex> lock(dataMutex);
    if (cache.count(name)) {
        DiscoveryNode* existing = static_cast<DiscoveryNode*>(cache[name]);
        existing->category = cat;
        existing->details  = det;
        existing->rating   = rating;
        existing->lat      = lat;
        existing->lon      = lon;
        removeNode(existing);
        addToFront(existing);
        LOGI("addLandmark: refreshed '%s'", name.c_str());
    } else {
        if (cache.size() >= static_cast<size_t>(capacity)) {
            LOGI("addLandmark: evicting LRU entry '%s'", tail->name.c_str());
            cache.erase(tail->name);
            Node* evicted = tail;
            removeNode(evicted);
            delete evicted;
        }
        DiscoveryNode* newNode = new DiscoveryNode();
        newNode->name     = name;
        newNode->category = cat;
        newNode->details  = det;
        newNode->rating   = rating;
        newNode->lat      = lat;
        newNode->lon      = lon;
        cache[name] = newNode;
        addToFront(newNode);
        LOGI("addLandmark: inserted '%s' (cache size=%zu)", name.c_str(), cache.size());
    }
}

/**
 * syncFromCloudNative
 *
 * Fetches high-rated social landmarks from the OEM cloud endpoint and
 * atomically replaces the discoveryBuffer.
 *
 * Quality gate: only items with is_social==true AND rating >= 4.5 are kept.
 * The old buffer is swapped out under dataMutex and deleted outside the lock
 * to minimise lock hold time.
 *
 * Network I/O via libcurl — must be called from a background thread.
 */
void LandmarkLRU::syncFromCloudNative(double lat, double lon) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        LOGE("syncFromCloudNative: curl_easy_init failed");
        return;
    }

    std::string readBuffer;
    std::string url = "https://api.oem-cloud.com/v1/discovery?lat="
                    + std::to_string(lat) + "&lon=" + std::to_string(lon);

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 10L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION,
        [](void* contents, size_t size, size_t nmemb, std::string* buf) -> size_t {
            size_t total = size * nmemb;
            buf->append(static_cast<char*>(contents), total);
            return total;
        });
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &readBuffer);

    CURLcode res = curl_easy_perform(curl);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        LOGE("syncFromCloudNative: curl error %s", curl_easy_strerror(res));
        return;
    }

    std::unordered_map<std::string, DiscoveryNode*> nextBuffer;
    try {
        auto jsonData = json::parse(readBuffer);
        for (auto& item : jsonData["landmarks"]) {
            if (item.value("is_social", false)
                    && static_cast<float>(item.value("rating", 0.0f)) >= 4.5f) {
                DiscoveryNode* dn = new DiscoveryNode();
                dn->name     = item["name"].get<std::string>();
                dn->category = item["category"].get<std::string>();
                dn->details  = item["details"].get<std::string>();
                dn->rating   = item["rating"].get<float>();
                dn->lat      = item.value("lat", 0.0);
                dn->lon      = item.value("lon", 0.0);
                nextBuffer[dn->name] = dn;
            }
        }
        LOGI("syncFromCloudNative: %zu gems passed quality gate", nextBuffer.size());
    } catch (const json::exception& e) {
        LOGE("syncFromCloudNative: JSON parse error: %s", e.what());
        for (auto& p : nextBuffer) delete p.second;
        return;
    }

    // Swap under lock — hold time is O(1).
    std::unordered_map<std::string, DiscoveryNode*> oldBuffer;
    {
        std::lock_guard<std::mutex> lock(dataMutex);
        std::swap(discoveryBuffer, nextBuffer);
    }
    for (auto& p : nextBuffer) delete p.second;
}

/**
 * selectBestAlert
 *
 * Picks the highest-priority cached landmark from the nearby array, promotes
 * it to the LRU front (recording the access), and returns its alert string.
 *
 * Scoring rules — safety first, then driver comfort, then discovery:
 *   EV_STATION  battery < 30 %  → 100  (urgent — always wins)
 *   REST_STOP                   → 60   (comfort)
 *   SCENIC                      → 40   (discovery)
 *   SOCIAL                      → 20   (nice-to-have)
 *   EV_STATION  battery >= 30 % → 20   (gentle heads-up, same weight as SOCIAL)
 *
 * Every nearby landmark is looked up directly in the LRU cache (O(1) hash).
 * A cache hit IS an LRU access — the winner is promoted to the front so the
 * least-recently-used eviction order stays accurate.  This is the core value
 * of the cache: fast offline lookup without a cloud round-trip.
 *
 * The cloud discoveryBuffer is checked as a bonus fallback when a nearby ID
 * is absent from the primary cache (e.g. a brand-new landmark not yet seeded).
 * Items served from the discoveryBuffer are NOT promoted in the LRU list
 * because they haven't been inserted into it yet.
 *
 * Re-alert suppression:
 *   The same landmark is suppressed for SUPPRESS_WINDOW_MS (30 s) after it
 *   fires.  This matches Java's ALERT_COOLDOWN_MS so only one layer suppresses
 *   at a time — no double-suppression.
 */
std::string LandmarkLRU::selectBestAlert(const std::vector<std::string>& nearby,
                                          int battery, int /*driveTime*/) {
    DiscoveryNode* best     = nullptr;
    int            maxScore = -1;
    bool           bestFromCache = false;

    std::lock_guard<std::mutex> lock(dataMutex);

    for (const auto& id : nearby) {
        DiscoveryNode* candidate = nullptr;
        bool           fromCache = false;

        if (cache.count(id)) {
            candidate = static_cast<DiscoveryNode*>(cache[id]);
            fromCache = true;
        } else if (discoveryBuffer.count(id)) {
            candidate = discoveryBuffer[id];
        }
        if (!candidate) continue;

        int score = 0;
        const std::string& cat = candidate->category;
        if      (cat == "EV_STATION" && battery < 30) score = 100;
        else if (cat == "REST_STOP")                   score =  60;
        else if (cat == "SCENIC")                      score =  40;
        else if (cat == "SOCIAL")                      score =  20;
        // EV_STATION with battery >= 30: gentle heads-up, same weight as SOCIAL
        else if (cat == "EV_STATION")                  score =  20;

        if (score > maxScore) {
            maxScore      = score;
            best          = candidate;
            bestFromCache = fromCache;
        }
    }

    if (!best || maxScore <= 0) return "";

    // ── Re-alert suppression (30 s, matches Java ALERT_COOLDOWN_MS) ──────────
    auto now     = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                       now - lastAlertTime).count();

    if (best->name == lastAlertedName && elapsed < SUPPRESS_WINDOW_MS) {
        LOGI("selectBestAlert: suppressing repeat '%s' (%lldms ago)",
             best->name.c_str(), static_cast<long long>(elapsed));
        return "";
    }

    lastAlertedName = best->name;
    lastAlertTime   = now;

    // ── LRU promotion — only for primary cache hits ───────────────────────────
    // Accessing a cached item is a use: move it to the front so the eviction
    // order reflects which landmarks the driver actually passed through.
    if (bestFromCache && cache.count(best->name)) {
        removeNode(cache[best->name]);
        addToFront(cache[best->name]);
        LOGI("selectBestAlert: LRU promoted '%s' to front", best->name.c_str());
    }

    LOGI("selectBestAlert: alerting '%s' [%s] score=%d batt=%d%%",
         best->name.c_str(), best->category.c_str(), maxScore, battery);
    return best->name + " (" + best->category + "): " + best->details;
}

/**
 * checkProximity
 *
 * Returns the name of the nearest landmark within PROXIMITY_DEG degrees,
 * or "" if no landmark is close enough.
 *
 * Uses a simple Euclidean approximation (adequate for ~2 km radius at
 * mid-latitudes). For production use, replace with full haversine.
 *
 * Thread-safe: acquires dataMutex.
 */
std::string LandmarkLRU::checkProximity(double lat, double lon) {
    std::lock_guard<std::mutex> lock(dataMutex);

    double bestDist = PROXIMITY_DEG;
    std::string bestName;

    for (const auto& kv : cache) {
        Node* node = kv.second;
        if (node->lat == 0.0 && node->lon == 0.0) continue;  // no coords stored
        double dLat = node->lat - lat;
        double dLon = node->lon - lon;
        double dist = std::sqrt(dLat * dLat + dLon * dLon);
        if (dist < bestDist) {
            bestDist = dist;
            bestName = node->name;
        }
    }

    if (!bestName.empty())
        LOGI("checkProximity: nearest='%s' dist=%.5f", bestName.c_str(), bestDist);

    return bestName;
}

/**
 * notifyHit
 *
 * Promotes a landmark to the front of the LRU list when it has been
 * directly hit (e.g. drive simulation crossed its waypoint).
 * Called from Java via nativeNotifyLandmarkHit on every sim tick so the
 * LRU order always reflects which zone the driver is currently in.
 *
 * Thread-safe: acquires dataMutex.
 */
void LandmarkLRU::notifyHit(const std::string& name) {
    std::lock_guard<std::mutex> lock(dataMutex);
    if (!cache.count(name)) {
        LOGI("notifyHit: '%s' not in primary cache", name.c_str());
        return;
    }
    removeNode(cache[name]);
    addToFront(cache[name]);
    LOGI("notifyHit: promoted '%s' to LRU front", name.c_str());
}

// ── Destructor ────────────────────────────────────────────────────────────────

LandmarkLRU::~LandmarkLRU() {
    for (auto& pair : cache)            delete pair.second;
    for (auto& pair : discoveryBuffer)  delete pair.second;
    LOGI("LandmarkLRU: destroyed, all nodes freed");
}