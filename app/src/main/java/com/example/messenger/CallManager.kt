package com.example.messenger

import android.content.Context
import android.util.Log
import com.huddle01.kotlin_client.HuddleClient
import com.huddle01.kotlin_client.live_data.store.models.Peer
import com.huddle01.kotlin_client.utils.PeerConnectionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.AudioManager
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.Scanner

object CallManager {
    private const val TAG = "CallManager"
    private val PROJECT_ID = BuildConfig.HUDDLE_PROJECT_ID
    private val API_KEY = BuildConfig.HUDDLE_API_KEY

    private var _huddleClient: HuddleClient? = null
    val huddleClient: HuddleClient? get() = _huddleClient

    private var _eglBase: EglBase? = null
    val eglBaseContext: EglBase.Context? get() = _eglBase?.eglBaseContext

    private val scope = CoroutineScope(Dispatchers.Main)
    private var connectionTimeoutJob: Job? = null

    private val _isCallActive = MutableStateFlow(false)
    val isCallActive = _isCallActive.asStateFlow()

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId = _activeRoomId.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers = _peers.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        this.appContext = context.applicationContext
        if (_huddleClient != null) return

        _eglBase = EglBase.create()
        Log.d(TAG, "EglBase initialized")

        _huddleClient = HuddleClient(PROJECT_ID, context.applicationContext)
        
        val room = _huddleClient?.room
        room?.on("room-joined") {
            Log.d(TAG, "Joined room successfully")
            _isCallActive.value = true
            connectionTimeoutJob?.cancel()
            
            // Setup audio for speakerphone
            setupAudioForCall()
            
            // Auto-enable audio on join to exit spectator mode
            enableAudio()
            
            // Initial refresh and a delayed one to ensure peers are sync'd
            refreshPeers()
            scope.launch {
                delay(1000)
                refreshPeers()
            }
        }

        room?.on("room-left") {
            Log.d(TAG, "Left room")
            _isCallActive.value = false
            _activeRoomId.value = null
            _peers.value = emptyList()
        }

        room?.on("new-peer-joined") { args ->
            Log.d(TAG, "New peer joined")
            refreshPeers()
        }

        room?.on("peer-left") { args ->
            Log.d(TAG, "Peer left")
            refreshPeers()
        }

        room?.on("stream-added") { args ->
            Log.d(TAG, "Stream added")
            refreshPeers()
        }

        room?.on("stream-closed") { args ->
            Log.d(TAG, "Stream closed")
            refreshPeers()
        }
    }

    private fun refreshPeers() {
        val room = _huddleClient?.room
        val store = _huddleClient?.localPeer?.store
        
        if (room != null && store != null) {
            val remotePeersMap = room.remotePeers
            Log.d(TAG, "Syncing store with remotePeers map. Map size: ${remotePeersMap.size}")
            
            remotePeersMap.forEach { (peerId, remotePeer) ->
                val existingPeer = store.peers.value?.allPeers?.find { it.peerId == peerId }
                if (existingPeer == null) {
                    Log.d(TAG, "Peer $peerId missing from store, adding manually")
                    val peerData = JSONObject().apply {
                        put("peerId", peerId)
                        put("role", remotePeer.role ?: "guest")
                    }
                    store.addPeer(peerId, peerData)
                }
            }
        }

        val remotePeers = _huddleClient?.localPeer?.store?.peers?.value?.allPeers ?: emptyList()
        Log.d(TAG, "Refreshing peers from store, final count: ${remotePeers.size}")
        
        // Log details about consumers to help debug black screen
        remotePeers.forEach { peer ->
            val videoConsumer = peer.consumers.values.find { it.kind == "video" }
            Log.d(TAG, "Peer ${peer.peerId}: hasVideo=${videoConsumer != null}, track=${videoConsumer?.track != null}")
        }
        
        _peers.value = remotePeers
    }

    suspend fun createRoom(): String? = withContext(Dispatchers.IO) {
        try {
            // Updated to V2 API for better reliability and simpler payload
            val url = URL("https://api.huddle01.com/api/v2/sdk/rooms/create-room")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", API_KEY)
            conn.doOutput = true
 
            // V2 payload is simpler: roomLocked and metadata
            val jsonInputString = "{\"roomLocked\": false, \"metadata\": \"{}\"}"
            
            Log.d(TAG, "Creating room (V2) with payload: $jsonInputString")
            
            conn.outputStream.use { os ->
                val input = jsonInputString.toByteArray(charset("utf-8"))
                os.write(input, 0, input.size)
            }
 
            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val response = Scanner(conn.inputStream).useDelimiter("\\A").next()
                Log.d(TAG, "Create room response: $response")
                val jsonObject = JSONObject(response)
                
                // Robust parsing for V2 response
                return@withContext if (jsonObject.has("data")) {
                    val data = jsonObject.getJSONObject("data")
                    data.optString("roomId")
                } else {
                    jsonObject.optString("roomId")
                }
            } else {
                val errorStream = conn.errorStream
                val errorResponse = if (errorStream != null) Scanner(errorStream).useDelimiter("\\A").next() else "No error details"
                Log.e(TAG, "Failed to create room (V2): $responseCode $errorResponse")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating room: ${e.message}")
            null
        }
    }

    private suspend fun fetchToken(roomId: String, userType: String = "host", displayName: String = "User"): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching V2 token from Worker for room: $roomId as $userType with name: $displayName")
            
            val url = URL("https://infra-api.huddle01.workers.dev/api/v2/sdk/create-peer-token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", API_KEY)
            conn.doOutput = true

            val permissions = JSONObject().apply {
                put("admin", userType == "host")
                put("canConsume", true)
                put("canProduce", true)
                put("canProduceSources", JSONObject().apply {
                    put("cam", true)
                    put("mic", true)
                    put("screen", true)
                })
                put("canRecvData", true)
                put("canSendData", true)
                put("canUpdateMetadata", true)
            }

            val metadata = JSONObject().apply {
                put("displayName", displayName)
            }

            val payload = JSONObject().apply {
                put("roomId", roomId)
                put("role", if (userType == "host") "host" else "guest")
                put("permissions", permissions)
                put("metadata", metadata.toString())
            }

            conn.outputStream.use { os ->
                val input = payload.toString().toByteArray(charset("utf-8"))
                os.write(input, 0, input.size)
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val response = Scanner(conn.inputStream).useDelimiter("\\A").next()
                val jsonObject = JSONObject(response)
                val token = jsonObject.getString("token")
                Log.d(TAG, "Successfully fetched token from worker: ${token.take(20)}...")
                return@withContext token
            } else {
                val errorStream = conn.errorStream
                val errorResponse = if (errorStream != null) Scanner(errorStream).useDelimiter("\\A").next() else "No details"
                Log.e(TAG, "Worker token error: $responseCode $errorResponse")
                withContext(Dispatchers.Main) {
                    appContext?.let { android.widget.Toast.makeText(it, "Worker Error $responseCode", android.widget.Toast.LENGTH_LONG).show() }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching token: ${e.message}")
            withContext(Dispatchers.Main) {
                appContext?.let { android.widget.Toast.makeText(it, "Token Exception: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
            null
        }
    }

    fun joinRoom(roomId: String, accessToken: String, isInitiator: Boolean = false, displayName: String = "User") {
        Log.d(TAG, "Joining room: $roomId (initiator=$isInitiator, name=$displayName)")
        _activeRoomId.value = roomId
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(30000) // 30 seconds timeout
            if (!_isCallActive.value) {
                Log.w(TAG, "Connection timeout after 30s, leaving room")
                leaveRoom()
            }
        }
        scope.launch {
            val token = if (accessToken == "MOCK_TOKEN") {
                fetchToken(roomId, if (isInitiator) "host" else "guest", displayName)
            } else {
                accessToken
            }

            if (token == null) {
                Log.e(TAG, "No valid token available")
                appContext?.let { ctx ->
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "Failed to fetch join token", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                connectionTimeoutJob?.cancel()
                return@launch
            }

            try {
                Log.d(TAG, "Calling joinRoom with token: ${token.take(10)}...")
                _huddleClient?.joinRoom(roomId, token)
                // If joinRoom completes, ensure state is updated
                if (!_isCallActive.value) {
                    Log.d(TAG, "joinRoom returned, manually setting call active")
                    _isCallActive.value = true
                    connectionTimeoutJob?.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join room: ${e.message}")
                appContext?.let { ctx ->
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "Join failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                connectionTimeoutJob?.cancel()
            }
        }
    }

    fun leaveRoom() {
        connectionTimeoutJob?.cancel()
        scope.launch {
            _huddleClient?.leaveRoom()
        }
    }
    
    fun enableAudio() {
        scope.launch {
            _huddleClient?.localPeer?.enableAudio()
        }
    }
    
    fun disableAudio() {
        _huddleClient?.localPeer?.disableAudio()
    }
    
    suspend fun enableVideo(renderer: SurfaceViewRenderer) {
        Log.d(TAG, "Enabling local video with renderer")
        _huddleClient?.localPeer?.enableVideo(renderer)
    }
    
    fun disableVideo(renderer: SurfaceViewRenderer) {
        Log.d(TAG, "Disabling local video")
        _huddleClient?.localPeer?.disableVideo(renderer)
    }

    private fun setupAudioForCall() {
        appContext?.let { context ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
                Log.d(TAG, "Audio setup: Speakerphone enabled, mode set to IN_COMMUNICATION")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up audio: ${e.message}")
            }
        }
    }
}
