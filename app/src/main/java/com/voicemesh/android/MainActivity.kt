package com.voicemesh.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.voicemesh.android.core.VoiceMeshService
import com.voicemesh.android.ui.VoiceMeshScreen
import com.voicemesh.android.ui.VoiceMeshViewModel
import com.voicemesh.android.ui.theme.VoiceMeshTheme
import com.voicemesh.android.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Core VoiceMesh service
    private lateinit var voiceMeshService: VoiceMeshService
    
    // ViewModels
    private val mainViewModel: MainViewModel by viewModels()
    private val voiceMeshViewModel: VoiceMeshViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return VoiceMeshViewModel(application, voiceMeshService) as T
            }
        }
    }
    
    // Required permissions for VoiceMesh
    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        
        if (allGranted) {
            initializeVoiceMesh()
        } else {
            mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_DENIED)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize VoiceMesh service
        voiceMeshService = VoiceMeshService(this)
        
        setContent {
            VoiceMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
        
        // Check permissions and start onboarding
        checkPermissionsAndInitialize()
    }
    
    @Composable
    private fun MainScreen() {
        val onboardingState by mainViewModel.onboardingState.collectAsState()
        
        when (onboardingState) {
            OnboardingState.CHECKING -> {
                OnboardingScreen(
                    state = onboardingState,
                    onContinue = { checkPermissionsAndInitialize() },
                    onRetry = { checkPermissionsAndInitialize() }
                )
            }
            
            OnboardingState.PERMISSION_REQUIRED -> {
                OnboardingScreen(
                    state = onboardingState,
                    onContinue = { requestPermissions() },
                    onRetry = { checkPermissionsAndInitialize() }
                )
            }
            
            OnboardingState.PERMISSION_DENIED -> {
                OnboardingScreen(
                    state = onboardingState,
                    onContinue = { requestPermissions() },
                    onRetry = { checkPermissionsAndInitialize() }
                )
            }
            
            OnboardingState.INITIALIZING -> {
                OnboardingScreen(
                    state = onboardingState,
                    onContinue = { },
                    onRetry = { initializeVoiceMesh() }
                )
            }
            
            OnboardingState.ERROR -> {
                OnboardingScreen(
                    state = onboardingState,
                    onContinue = { initializeVoiceMesh() },
                    onRetry = { initializeVoiceMesh() }
                )
            }
            
            OnboardingState.COMPLETE -> {
                VoiceMeshScreen(viewModel = voiceMeshViewModel)
            }
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    private fun checkPermissionsAndInitialize() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            initializeVoiceMesh()
        } else {
            mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_REQUIRED)
        }
    }
    
    /**
     * Request missing permissions
     */
    private fun requestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initializeVoiceMesh()
        }
    }
    
    /**
     * Initialize VoiceMesh service
     */
    private fun initializeVoiceMesh() {
        mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
        
        lifecycleScope.launch {
            try {
                val initialized = voiceMeshService.initialize()
                
                if (initialized) {
                    // Set up service delegate
                    voiceMeshService.delegate = voiceMeshViewModel
                    
                    mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
                    android.util.Log.d(TAG, "VoiceMesh initialized successfully")
                } else {
                    mainViewModel.updateOnboardingState(OnboardingState.ERROR)
                    android.util.Log.e(TAG, "Failed to initialize VoiceMesh")
                }
                
            } catch (e: Exception) {
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
                android.util.Log.e(TAG, "Error initializing VoiceMesh", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        voiceMeshService.release()
    }
}

/**
 * Main view model for managing app-level state
 */
class MainViewModel : androidx.lifecycle.ViewModel() {
    
    private val _onboardingState = kotlinx.coroutines.flow.MutableStateFlow(OnboardingState.CHECKING)
    val onboardingState: kotlinx.coroutines.flow.StateFlow<OnboardingState> = _onboardingState.asStateFlow()
    
    fun updateOnboardingState(state: OnboardingState) {
        _onboardingState.value = state
    }
}

/**
 * Onboarding states for the app
 */
enum class OnboardingState {
    CHECKING,           // Checking permissions and requirements
    PERMISSION_REQUIRED, // Need to request permissions
    PERMISSION_DENIED,   // User denied permissions
    INITIALIZING,       // Initializing VoiceMesh service
    ERROR,              // Error during initialization
    COMPLETE            // Ready to use VoiceMesh
} 