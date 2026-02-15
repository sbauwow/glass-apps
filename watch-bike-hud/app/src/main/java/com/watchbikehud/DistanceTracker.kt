package com.watchbikehud

import android.location.Location

/**
 * Accumulates GPS distance with jitter filtering.
 * Rejects points with poor accuracy and unrealistic jumps.
 */
class DistanceTracker {
    var totalMeters: Float = 0f
        private set
    private var lastLocation: Location? = null

    fun addPoint(loc: Location) {
        // Filter poor GPS accuracy
        if (loc.accuracy > 20f) return

        val last = lastLocation
        if (last != null) {
            val dist = last.distanceTo(loc)
            // Reject jitter (< 2m) and teleports (> 100m between 1s updates)
            if (dist > 2f && dist < 100f) {
                totalMeters += dist
            }
        }
        lastLocation = loc
    }

    fun reset() {
        totalMeters = 0f
        lastLocation = null
    }
}
