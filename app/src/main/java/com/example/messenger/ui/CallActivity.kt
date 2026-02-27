package com.example.messenger.ui

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.messenger.CallManager
import com.huddle01.kotlin_client.live_data.store.models.Peer
import com.huddle01.kotlin_client.utils.PeerConnectionUtils
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : ComponentActivity() {
    private var localRenderer: SurfaceViewRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val roomId = intent.getStringExtra("room_id") ?: ""
        val isVideo = intent.getBooleanExtra("is_video", false)
        val isInitiator = intent.getBooleanExtra("is_initiator", false)

        setContent {
            CallScreen(
                roomId = roomId,
                isVideo = isVideo,
                isInitiator = isInitiator,
                onHangUp = {
                    localRenderer?.let { CallManager.disableVideo(it) }
                    CallManager.leaveRoom()
                    finish()
                },
                onLocalRendererCreated = { renderer ->
                    localRenderer = renderer
                }
            )
        }
        
        // Moved initialization to CallScreen using LaunchedEffect and permission launcher
    }
}

@Composable
fun CallScreen(
    roomId: String,
    isVideo: Boolean,
    isInitiator: Boolean,
    onHangUp: () -> Unit,
    onLocalRendererCreated: (SurfaceViewRenderer) -> Unit
) {
    val context = LocalContext.current
    val isCallActive by CallManager.isCallActive.collectAsState()
    val peers by CallManager.peers.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    var permissionsGranted by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        permissionsGranted = cameraGranted && micGranted
        
        if (permissionsGranted) {
            if (roomId.isNotEmpty()) {
                val walletAddress = com.example.messenger.KeyManager.getSavedWalletAddress(context) ?: "User"
                CallManager.joinRoom(roomId, "MOCK_TOKEN", isInitiator, walletAddress)
            }
        } else {
            Toast.makeText(context, "Camera and Microphone permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(if (isVideo) "Video Call" else "Audio Call", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Room: $roomId", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isCallActive) {
                Text("Connected", color = MaterialTheme.colorScheme.primary)
                Text("Peers in room: ${peers.size}")
                
                if (isVideo) {
                    // Local Video Preview
                    Box(modifier = Modifier.size(160.dp, 120.dp)) {
                        AndroidView(
                            factory = { context ->
                                SurfaceViewRenderer(context).also { renderer ->
                                    renderer.init(CallManager.eglBaseContext, null)
                                    renderer.setEnableHardwareScaler(true)
                                    renderer.setMirror(true)
                                    onLocalRendererCreated(renderer)
                                    coroutineScope.launch {
                                        CallManager.enableVideo(renderer)
                                    }
                                }
                            },
                            update = { renderer ->
                                // Rely on CallManager.enableVideo(renderer) called in factory.
                                // If the screen is still black, we might need to re-trigger it
                                // or find the track once we know the correct SDK property.
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Remote Peers Video
                    LazyRow(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                        items(peers) { peer ->
                            Box(modifier = Modifier.size(160.dp, 120.dp).padding(4.dp)) {
                                AndroidView(
                                    factory = { context ->
                                        SurfaceViewRenderer(context).apply {
                                            init(CallManager.eglBaseContext, null)
                                            setMirror(false)
                                            setEnableHardwareScaler(true)
                                        }
                                    },
                                    update = { renderer ->
                                        // Update/Rebind track reactively
                                        val videoConsumer = peer.consumers.values.find { it.kind == "video" }
                                        val track = videoConsumer?.track as? VideoTrack
                                        Log.d("CallActivity", "Updating remote peer ${peer.peerId} video, track found: ${track != null}")
                                        // We don't removeSink here to avoid flickering, 
                                        // but we ensure the current track is added to the renderer.
                                        track?.addSink(renderer)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(peer.peerId ?: "Peer", modifier = Modifier.align(Alignment.BottomStart))
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator()
                Text("Connecting...")
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Hang Up")
            }
        }
    }
}
