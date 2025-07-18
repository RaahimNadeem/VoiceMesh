package com.voicemesh.android.core

import com.voicemesh.android.model.EphemeralVoiceMessage
import com.voicemesh.android.model.VoicePeer

/**
 * Delegate interface for VoiceMesh network events
 */
interface VoiceMeshNetworkDelegate {
    
    /**
     * Called when a voice message is successfully delivered
     */
    fun onVoiceMessageDelivered(messageId: String, peer: VoicePeer)
    
    /**
     * Called when a network error occurs
     */
    fun onNetworkError(error: String)
    
    /**
     * Called when a message assembly is complete
     */
    fun onMessageComplete(messageId: String, message: EphemeralVoiceMessage)
    
    /**
     * Called when reassembly timeout occurs
     */
    fun onReassemblyTimeout(messageId: String)
    
    /**
     * Called when reassembly error occurs
     */
    fun onReassemblyError(messageId: String, error: String)
    
    /**
     * Called when a peer connects
     */
    fun onPeerConnected(peer: VoicePeer)
    
    /**
     * Called when a peer disconnects
     */
    fun onPeerDisconnected(peerId: String)
    
    /**
     * Called when a voice message is received
     */
    fun onVoiceMessageReceived(message: EphemeralVoiceMessage)
} 