package com.example.aaos.landmark;

public interface LandmarkDiscoveryProvider {
    /**
     * Retrieves landmarks near the given coordinates.
     */
    String[] getNearby(double lat, double lon);
}