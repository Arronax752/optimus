package com.example.optimus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.optimus.helper.DeviceInfo
import com.example.optimus.helper.State
import com.example.optimus.voice.Voice
import com.example.optimus.voice.CommandProcessor


class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String> = mutableListOf<String>().apply {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val allGranted = perms.all { it.value }
            if (allGranted) {
                Log.d("OPTIMUS", "✅ All critical permissions granted.")
                startSystem()
            } else {
                val missing = perms.filterValues { !it }.keys
                    .joinToString("\n") { it.split(".").last() }
                Log.e("OPTIMUS", "❌ Missing Permissions:\n$missing")
                Toast.makeText(this, "Missing: \n$missing", Toast.LENGTH_LONG).show()
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestStandardPermissions()
        setContent { OptimusUI() }
    }

    private fun checkAndRequestStandardPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSystem()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    fun openAssistantSettings() {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkAndRequestPermissions() {

        if (!checkOverlayPermission()) {
            return
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            startSystem()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Please enable 'Display over other apps'",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
        }
        return true
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${packageName}".toUri()
        }
        startActivity(intent)
    }


    private fun startSystem() {
        //requestIgnoreBatteryOptimizations()
        val voiceIntent = Intent(this, Voice::class.java)
        ContextCompat.startForegroundService(this, voiceIntent)
        Log.d("OPTIMUS","Voice system initiated")
    }



    override fun onResume() {
        super.onResume()
        // FIX: Change LISTENING to WAITING.
        // You want the WakeWord active on resume, not Google STT.
        if (State.currentStatus.value == "IDLE") {
            State.currentStatus.value = "WAITING"
            Log.d("OPTIMUS", "System resumed, wake word engine active")
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("optimus", MODE_PRIVATE).edit { putBoolean("system_running", false) }
    }

}

@Composable
fun OptimusUI() {
    val context = LocalContext.current
    val status by State.currentStatus.collectAsState()
    val baseColor = State.getStatusColor(status)
    val aiText by State.aiResponse.collectAsState()
    val isVisionActive by State.visionEnabled.collectAsState()
    val isMicActive = status != "IDLE"

    val scrollState = rememberScrollState()
    val infinite = rememberInfiniteTransition(label = "orb")
    val orbColor by infinite.animateColor(
        initialValue = baseColor.copy(alpha = 0.4f),
        targetValue = baseColor,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "glow"
    )
    LaunchedEffect(aiText) { scrollState.scrollTo(scrollState.maxValue) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
    ) {
        // --- TOP HUD SECTION ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. LEFT: Orb
            Orb(orbColor)

            // 2. CENTER: Diagnostics (Weighted to take middle space and center content)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "SYSTEM_DIAGNOSTICS v3.2",
                    color = Color.White.copy(0.3f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // VOICE is Clickable
                    ServiceButton(
                        label = "VOICE",
                        isActive = isMicActive,
                        activeColor = Color(0xFF00FF88),
                        isClickable = true
                    ) {
                        if (isMicActive) {
                            State.currentStatus.value = "IDLE"
                        } else {
                            State.VoiceController.forceMicRecovery()
                        }
                    }
                    val processor = remember { CommandProcessor(context) }
                    ServiceButton(
                        label = "VISION",
                        isActive = isVisionActive,
                        activeColor = Color(0xFF00FF88),
                        isClickable = true
                    ) {
                        processor.handleVisionToggle()
                    }
                }
            }

            // 3. RIGHT: RAM Indicator
            RamIndicator()
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- TERMINAL SECTION ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    val len = 20.dp.toPx()
                    drawRect(color = orbColor.copy(0.15f), style = Stroke(1.dp.toPx()))
                    drawLine(orbColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(len, 0f), stroke)
                    drawLine(orbColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, len), stroke)
                }
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Optimus Terminal>_",
                    color = orbColor.copy(0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (aiText.isBlank()) {
                    // Centered IDLE message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SYSTEM IDLE",
                            color = Color.Gray.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            letterSpacing = 2.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                } else {
                    // Scrollable AI Text
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        Text(
                            text = aiText,
                            color = Color.White,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RamIndicator() {
    val context = LocalContext.current
    val ramUiColor = Color(0xFFE36522)
    var ramFactor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // Logic moved to DeviceInfo.kt
    LaunchedEffect(Unit) {
        while(true) {
            ramFactor = DeviceInfo.getRamUsageFactor(context)
            kotlinx.coroutines.delay(3000)
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = Color.White.copy(0.05f), style = Stroke(4.dp.toPx()))
            drawArc(
                color = ramUiColor,
                startAngle = -90f,
                sweepAngle = 360f * ramFactor,
                useCenter = false,
                style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
        Text("RAM", color = ramUiColor.copy(0.8f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun ServiceButton(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    isClickable: Boolean,
    onClick: () -> Unit
) {
    val displayColor = if (isActive) activeColor else Color(0xFFFF3333)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(displayColor.copy(0.1f))
            .border(1.dp, displayColor.copy(0.4f), RoundedCornerShape(4.dp))
            .then(if (isClickable) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(displayColor))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = displayColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

@Composable
fun Orb(color: Color) {
    Box(modifier = Modifier.size(70.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(color.copy(0.6f), Color.Transparent)))
        drawCircle(color = color, style = Stroke(3.dp.toPx()))
        drawCircle(color = color, radius = 5.dp.toPx())
    })
}
