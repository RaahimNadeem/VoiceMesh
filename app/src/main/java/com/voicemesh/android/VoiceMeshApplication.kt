package com.voicemesh.android

import android.app.Application

/**
 * Main application class for VoiceMesh Android
 * Handles global app initialization and service setup
 */
class VoiceMeshApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize any global services or configurations
        // For now, keep it simple to match BitChat's pattern
    }
} 