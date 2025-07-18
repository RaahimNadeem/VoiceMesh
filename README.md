# VoiceMesh Android

**Ephemeral voice messaging over Bluetooth mesh networks**

VoiceMesh is an Android application that enables secure, private voice communication without internet connectivity, using only Bluetooth Low Energy mesh networking between nearby devices. It's built on proven BLE mesh architecture with voice-specific enhancements.

![VoiceMesh Logo](docs/voicemesh-logo.png)

## Features

- **🎙️ Walkie-talkie Voice Messaging**: Hold-to-record, release-to-send voice messages
- **⏰ Self-Destructing Messages**: Messages automatically delete after delivery or 5 minutes
- **📡 BLE Mesh Network**: No internet required - works entirely over Bluetooth
- **🔒 End-to-End Encryption**: Secure voice transmission using proven cryptography
- **📱 Modern Android UI**: Jetpack Compose with Material Design 3
- **🌙 Terminal Aesthetic**: Dark theme with Matrix-inspired design
- **⚡ Store-and-Forward**: Voice messages cached for offline recipients
- **🔋 Battery Optimized**: Adaptive power management for mobile usage

## Technical Specifications

### Audio System
- **Format**: AAC-LC compression for optimal quality/size balance
- **Sample Rate**: 16kHz, 16-bit, mono (optimized for voice)
- **Bitrate**: 32kbps target (5s ≈ 20KB, 30s ≈ 120KB, 60s ≈ 240KB)
- **Max Duration**: 60 seconds per message
- **Real-time**: Live audio level monitoring during recording

### Mesh Networking
- **Protocol**: Bluetooth Low Energy (BLE) mesh
- **Range**: Up to 100 meters between devices
- **Multi-hop**: Messages route through intermediate devices
- **Fragment Size**: 450 bytes (safe for BLE MTU limits)
- **Fragmentation**: Automatic splitting/reassembly with SHA-256 checksums
- **Self-healing**: Network adapts as devices join/leave

### Security & Privacy
- **Encryption**: X25519 + AES-256-GCM (compatible with BitChat)
- **Signatures**: Ed25519 digital signatures for authenticity
- **Ephemeral IDs**: Rotating peer identities for privacy
- **No Persistent Storage**: Voice data never saved to disk
- **Forward Secrecy**: New key pairs each session

### Ephemeral Messages
- **Auto-deletion**: Messages delete immediately after playback
- **Time Expiration**: 5-minute maximum lifetime
- **Network Cleanup**: Deletion commands propagated through mesh
- **Storage Limits**: Maximum 10 voice messages cached per device
- **Delivery Confirmation**: Recipients acknowledge message receipt

## Architecture

VoiceMesh follows a component-based architecture inspired by BitChat:

```
VoiceMeshService (Main Coordinator)
├── VoiceRecorder (Audio capture with AAC compression)
├── VoicePlayer (Playback with automatic cleanup)
├── VoiceCompressor (Adaptive compression)
├── EphemeralVoiceMessageManager (Message lifecycle)
├── VoiceFragmentManager (BLE fragmentation)
└── VoiceMeshNetworkService (BLE mesh networking)
```

### Core Components

- **VoiceMeshService**: Main coordinator integrating all voice functionality
- **VoiceRecorder**: Android MediaRecorder with real-time level monitoring
- **VoicePlayer**: MediaPlayer with ephemeral message support
- **EphemeralVoiceMessageManager**: Handles message expiration and cleanup
- **VoiceFragmentManager**: BLE fragmentation with error detection
- **VoiceMeshNetworkService**: Extends BLE mesh for voice messages

## Installation

### Prerequisites

- **Android 8.0+** (API level 26 minimum)
- **Bluetooth LE support** (required hardware feature)
- **Microphone access** (for voice recording)
- **2GB RAM** recommended for optimal performance

### Build from Source

1. **Clone the repository:**
   ```bash
   git clone https://github.com/your-org/voicemesh-android.git
   cd voicemesh-android
   ```

2. **Open in Android Studio:**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the `voicemesh-android` directory

3. **Build the project:**
   ```bash
   ./gradlew build
   ```

4. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

### Permissions

VoiceMesh requires the following permissions:

- **🎤 RECORD_AUDIO**: Voice message recording
- **📶 BLUETOOTH_***: BLE mesh networking (Android 12+ permissions)
- **📍 ACCESS_FINE_LOCATION**: Required for BLE scanning on Android
- **🔔 POST_NOTIFICATIONS**: Voice message alerts

## Usage

### Getting Started

1. **Install VoiceMesh** on your Android device
2. **Grant permissions** when prompted (microphone, Bluetooth, location)
3. **Launch the app** - it automatically starts mesh networking
4. **Wait for peers** - nearby VoiceMesh users will appear
5. **Select recipient** by tapping the record button
6. **Hold to record** voice message (max 60 seconds)
7. **Release to send** - message transmits through mesh network

### Voice Messages

- **Recording**: Hold the record button to capture voice
- **Waveform**: Real-time audio visualization during recording
- **Sending**: Automatic compression and mesh transmission
- **Receiving**: Incoming messages appear with sender info
- **Playback**: Tap play button to hear received messages
- **Auto-delete**: Messages disappear after playing (ephemeral)

### Network Status

- **Green dot**: Strong mesh connectivity (3+ peers)
- **Blue dot**: Good connectivity (1-2 peers)
- **Red dot**: No connectivity (0 peers)
- **Peer count**: Number shows connected voice-capable devices

## Development

### Project Structure

```
app/src/main/java/com/voicemesh/android/
├── VoiceMeshApplication.kt          # Application class
├── MainActivity.kt                  # Main activity with permissions
├── audio/                          # Audio recording and playback
│   ├── VoiceRecorder.kt           # MediaRecorder wrapper
│   ├── VoicePlayer.kt             # MediaPlayer wrapper  
│   └── VoiceCompressor.kt         # AAC compression
├── core/                          # Core business logic
│   ├── VoiceMeshService.kt        # Main service coordinator
│   ├── EphemeralVoiceMessageManager.kt
│   └── VoiceFragmentManager.kt
├── mesh/                          # BLE mesh networking
│   └── VoiceMeshNetworkService.kt
├── model/                         # Data models
│   ├── EphemeralVoiceMessage.kt
│   ├── VoiceFragment.kt
│   └── VoicePeer.kt
├── protocol/                      # Network protocols
│   └── VoiceProtocol.kt
└── ui/                           # Jetpack Compose UI
    ├── VoiceMeshScreen.kt
    ├── VoiceMeshViewModel.kt
    ├── onboarding/
    └── theme/
```

### Dependencies

- **Jetpack Compose**: Modern declarative UI framework
- **BouncyCastle**: Cryptographic operations (X25519, Ed25519, AES-GCM)
- **Kotlin Coroutines**: Asynchronous programming
- **Material Design 3**: UI components and theming
- **ExoPlayer**: Advanced audio processing capabilities

### Building

VoiceMesh uses modern Android development practices:

- **Kotlin**: 100% Kotlin codebase
- **Gradle Version Catalogs**: Centralized dependency management
- **Jetpack Compose**: Declarative UI
- **MVVM Pattern**: Clean architecture separation
- **Coroutines**: Structured concurrency

## Protocol Compatibility

VoiceMesh extends the BitChat protocol with voice-specific message types:

- `0x30`: VOICE_MESSAGE_START
- `0x31`: VOICE_MESSAGE_FRAGMENT  
- `0x32`: VOICE_MESSAGE_END
- `0x33`: VOICE_MESSAGE_ACK
- `0x34`: VOICE_MESSAGE_DELETE
- `0x35`: VOICE_MESSAGE_DELIVERED

The protocol maintains 100% compatibility with BitChat's mesh networking while adding voice capabilities.

## Performance

### Audio Performance
- **Low-latency recording**: Responsive UI during voice capture
- **Efficient compression**: Minimal CPU impact during encoding
- **Streaming fragmentation**: Transmission starts before recording complete
- **Memory management**: Bounded buffers prevent memory exhaustion

### Network Performance  
- **Adaptive fragmentation**: Adjusts to network conditions
- **Quality of service**: Voice messages get priority routing
- **Connection pooling**: Reuses existing BLE connections
- **Battery efficiency**: Duty cycling and adaptive power modes

### Storage Performance
- **Ephemeral design**: No persistent voice data storage
- **Automatic cleanup**: Expired messages removed automatically
- **Cache limits**: Maximum 10 messages prevent storage exhaustion
- **Temporary files**: Secure cleanup of recording artifacts

## Security Considerations

### Voice Data Protection
- **End-to-end encryption**: All voice data encrypted before transmission
- **Forward secrecy**: Past messages cannot be decrypted
- **No disk storage**: Voice data only exists in memory
- **Secure cleanup**: Memory cleared after message deletion

### Network Security
- **Authenticated routing**: Prevents mesh manipulation attacks
- **Rate limiting**: Protects against voice message flooding
- **Peer verification**: Ensures trusted mesh participation
- **Traffic analysis resistance**: Cover traffic and timing randomization

### Privacy Features
- **Ephemeral identities**: Peer IDs rotate every 5-15 minutes
- **No persistent metadata**: Messages leave no traces
- **Anonymous communication**: Optional identity revelation
- **Trust relationships**: Survive peer ID changes

## Testing

### Unit Testing
- **Audio system tests**: Recording quality and compression validation
- **Protocol tests**: Message lifecycle and network cleanup
- **Fragmentation tests**: Fragment assembly and error handling
- **Encryption tests**: End-to-end security verification

### Integration Testing
- **Multi-device scenarios**: Test with 3-10 devices
- **Range testing**: Verify operation at BLE limits
- **Mobility testing**: Handoffs while devices move
- **Battery testing**: Power consumption measurement

## Contributing

We welcome contributions to VoiceMesh! Key areas for enhancement:

1. **Audio Quality**: Advanced noise reduction and audio processing
2. **Network Optimization**: Improved BLE mesh performance
3. **UI/UX**: Enhanced Material Design 3 features
4. **Security**: Additional cryptographic features
5. **Platform Support**: iOS compatibility layer

### Development Setup

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Make your changes with tests
4. Submit a pull request with detailed description

## License

VoiceMesh is released into the public domain. See [LICENSE](LICENSE.md) for details.

## Acknowledgments

- **BitChat**: Foundation BLE mesh networking architecture
- **Android Audio API**: MediaRecorder and MediaPlayer framework
- **BouncyCastle**: Cryptographic library
- **Jetpack Compose**: Modern Android UI toolkit
- **Material Design**: Google's design system

## Support

- **Issues**: [GitHub Issues](https://github.com/your-org/voicemesh-android/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/voicemesh-android/discussions)
- **Documentation**: [Wiki](https://github.com/your-org/voicemesh-android/wiki)

---

**VoiceMesh**: Secure ephemeral voice communication without infrastructure. 🎙️📡🔒 