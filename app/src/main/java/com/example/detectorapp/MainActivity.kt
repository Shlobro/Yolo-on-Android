package com.example.detectorapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var receiver: BroadcastReceiver
    private val gson = Gson()

    // Will be set by the BroadcastReceiver to push JSON into Compose
    private var onJsonReceived: (String) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate – starting background detection service")

        // Start the background detection service
        val serviceIntent = Intent(this, DetectorService::class.java)
        startForegroundService(serviceIntent)

        // Listen for detections from the service
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("json")?.let { json ->
                    Log.v(TAG, "Received JSON: ${json.take(100)}")
                    onJsonReceived(json)
                }
            }
        }

        registerReceiver(
            receiver,
            IntentFilter("com.example.detectorapp.DETECTION"),
            Context.RECEIVER_NOT_EXPORTED
        )

        setContent {
            DetectorAppTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy – unregistering receiver")
        unregisterReceiver(receiver)
    }

    @Composable
    private fun MainScreen() {
        var lastDetectionJson by remember { mutableStateOf("No detections yet...") }
        var isServiceRunning by remember { mutableStateOf(true) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Connect the callback
        LaunchedEffect(Unit) {
            onJsonReceived = { json -> lastDetectionJson = json }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "YOLO Detection Server",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Background Service Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isServiceRunning) "✅ Running on port 8080" else "❌ Stopped",
                        color = if (isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Available Endpoints:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = """
                        • POST /detect - All objects
                        • POST /detect_bottles_only - Bottles only  
                        • GET /bottles/status - Tracking status
                        • GET / - Health check
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val intent = Intent(context, DetectorService::class.java)
                            context.startForegroundService(intent)
                            isServiceRunning = true
                        }
                    }
                ) {
                    Text("Start Service")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val intent = Intent(context, DetectorService::class.java)
                            context.stopService(intent)
                            isServiceRunning = false
                        }
                    }
                ) {
                    Text("Stop Service")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Latest Detection Result",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = lastDetectionJson,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectorAppTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}
