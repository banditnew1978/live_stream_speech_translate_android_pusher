package com.example.sesstream

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sesstream.ui.theme.SesstreamTheme
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isStreaming = mutableStateOf(false) // Yayın durumunu Activity seviyesinde takip et
    private var selectedPort = StreamingService.DEFAULT_PORT // Default port değeri
    private val TAG = "MainActivity"

    // Storage izni için launcher
    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) {
                    allGranted = false
                    Log.w(TAG, "Permission not granted: ${it.key}")
                }
            }
            
            if (allGranted) {
                Log.d(TAG, "All storage permissions granted.")
                initializeFileLogger()
            } else {
                Log.w(TAG, "Some storage permissions denied.")
                
                // Android 11+ için özel izin gerekebilir
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.addCategory("android.intent.category.DEFAULT")
                            intent.data = android.net.Uri.parse("package:$packageName")
                            manageAllFilesPermissionLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not request MANAGE_EXTERNAL_STORAGE permission", e)
                        }
                    }
                }
            }
        }
        
    // Android 11+ için MANAGE_EXTERNAL_STORAGE izni
    private val manageAllFilesPermissionLauncher = 
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.d(TAG, "MANAGE_EXTERNAL_STORAGE permission granted")
                    initializeFileLogger()
                } else {
                    Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission denied")
                }
            }
        }

    // Ses kaydı izni için launcher
    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted.")
                // Ses izni alındı, şimdi MediaProjection iznini iste
                requestMediaProjectionPermission()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                // TODO: Kullanıcıya neden izin gerektiğini açıklayan bir mesaj göster.
            }
        }

    // MediaProjection izni için launcher
    private val requestMediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection permission granted.")
                // İzin verildi, servisi başlat ve projection verisini gönder
                startStreamingService(result.resultCode, result.data)
                isStreaming.value = true // Yayın durumunu güncelle
                
                // Log to file
                FileLogger.getInstance()?.info(TAG, "Streaming started with port: $selectedPort")
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                isStreaming.value = false // Başlatılamadı
                
                // Log to file
                FileLogger.getInstance()?.warn(TAG, "MediaProjection permission denied by user")
                // TODO: Kullanıcıya neden izin gerektiğini açıklayan bir mesaj göster.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "onCreate called")
        
        // Storage iznini kontrol et
        checkAndRequestStoragePermissions()
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        enableEdgeToEdge()
        setContent {
            SesstreamTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Composable'a Activity state'ini ve olay tetikleyicilerini geç
                    StreamingScreen(
                        modifier = Modifier.padding(innerPadding),
                        isStreaming = isStreaming.value, // State'i Composable'a aktar
                        onStartClick = { checkAndRequestPermissions() },
                        onStopClick = { stopStreamingServiceInternal() }
                    )
                }
            }
        }
        
        FileLogger.getInstance()?.info(TAG, "Application initialized with default port: $selectedPort")
        
        // Start ANR monitoring
        ANRMonitor.getInstance().start()
    }
    
    override fun onDestroy() {
        // Stop ANR monitoring
        ANRMonitor.getInstance().stop()
        
        // Ensure logger is closed properly
        FileLogger.getInstance()?.info(TAG, "Application shutting down")
        FileLogger.getInstance()?.close()
        super.onDestroy()
    }

    // Storage izinlerini kontrol et ve iste
    private fun checkAndRequestStoragePermissions() {
        Log.d(TAG, "Checking storage permissions")
        
        // Android 11+ (API 30+) için MANAGE_EXTERNAL_STORAGE gerekli
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse("package:$packageName")
                    manageAllFilesPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not request MANAGE_EXTERNAL_STORAGE permission", e)
                }
            } else {
                // İzin zaten verilmiş, logger'ı başlat
                initializeFileLogger()
            }
        } else {
            // Android 10 ve altı için READ_EXTERNAL_STORAGE ve WRITE_EXTERNAL_STORAGE
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            
            val permissionsToRequest = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            if (permissionsToRequest.isNotEmpty()) {
                requestStoragePermissionLauncher.launch(permissionsToRequest)
            } else {
                // Tüm izinler zaten verilmiş, logger'ı başlat
                initializeFileLogger()
            }
        }
    }
    
    // FileLogger'ı başlat
    private fun initializeFileLogger() {
        Log.d(TAG, "Initializing FileLogger")
        val success = FileLogger.initialize(applicationContext)
        if (success) {
            Log.d(TAG, "FileLogger initialized successfully")
            // Log device information
            logDeviceInfo()
        } else {
            Log.e(TAG, "Failed to initialize FileLogger")
        }
    }

    // Cihaz bilgilerini logla
    private fun logDeviceInfo() {
        val logger = FileLogger.getInstance() ?: return
        
        try {
            logger.info(TAG, "--------- DEVICE INFORMATION ---------")
            logger.info(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            logger.info(TAG, "Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            logger.info(TAG, "Build number: ${Build.DISPLAY}")
            logger.info(TAG, "App version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            
            // Memory info
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
            val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
            logger.info(TAG, "Total memory: $totalMemoryMB MB")
            logger.info(TAG, "Available memory: $availableMemoryMB MB")
            
            // Screen info
            val displayMetrics = resources.displayMetrics
            logger.info(TAG, "Screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} pixels, ${displayMetrics.densityDpi} dpi")
            
            // Network info
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnected
            logger.info(TAG, "Network connected: $isConnected")
            if (isConnected) {
                logger.info(TAG, "Network type: ${activeNetwork?.typeName}")
            }
            
            logger.info(TAG, "---------------------------------------")
        } catch (e: Exception) {
            logger.error(TAG, "Error logging device info", e)
        }
    }

    // İzinleri kontrol et ve iste (Önce Ses, sonra MediaProjection)
    private fun checkAndRequestPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "RECORD_AUDIO permission already granted.")
                FileLogger.getInstance()?.debug(TAG, "RECORD_AUDIO permission already granted")
                // Ses izni var, MediaProjection iznini iste
                requestMediaProjectionPermission()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Log.i(TAG, "Showing rationale for RECORD_AUDIO permission.")
                FileLogger.getInstance()?.info(TAG, "Showing rationale for RECORD_AUDIO permission")
                // TODO: Rationale UI göster
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                Log.d(TAG, "Requesting RECORD_AUDIO permission.")
                FileLogger.getInstance()?.debug(TAG, "Requesting RECORD_AUDIO permission")
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // MediaProjection iznini istemek için Intent'i başlat
    private fun requestMediaProjectionPermission() {
        // Android Q (10) ve üzeri için gerekli
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Requesting MediaProjection permission.")
            FileLogger.getInstance()?.debug(TAG, "Requesting MediaProjection permission")
            requestMediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            // Android 9 ve altı için bu yöntem desteklenmiyor.
            // Sadece mikrofon kaydı veya başka bir yöntem kullanılabilir.
            Log.w(TAG, "MediaProjection not supported below Android Q. Starting service without it.")
            FileLogger.getInstance()?.warn(TAG, "MediaProjection not supported below Android Q. Starting service without it")
            // TODO: Android 9 ve altı için alternatif bir akış başlatma mantığı ekle (örn. sadece mikrofon)
            // Şimdilik MediaProjection olmadan servisi başlatıyoruz (ses yakalamayacak)
            startStreamingService(Activity.RESULT_CANCELED, null)
            isStreaming.value = true
        }
    }

    // Kullanıcının seçtiği port değerini ayarlamak için method
    fun setSelectedPort(port: Int) {
        selectedPort = port
        FileLogger.getInstance()?.info(TAG, "Port changed to: $port")
    }

    // Servisi başlat (MediaProjection verisi ile birlikte)
    private fun startStreamingService(resultCode: Int, data: Intent?) {
        try {
            val intent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START_STREAMING
                // MediaProjection verisini Intent'e ekle (null olabilir)
                putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(StreamingService.EXTRA_DATA, data)
                putExtra(StreamingService.EXTRA_PORT, selectedPort) // Seçilen portu ekle
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            FileLogger.getInstance()?.info(TAG, "StreamingService started with port: $selectedPort")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting streaming service", e)
            FileLogger.getInstance()?.error(TAG, "Error starting streaming service: ${e.message}", e)
            isStreaming.value = false
            
            // Show error to user
            Toast.makeText(
                this,
                "Servis başlatılamadı: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Servisi durdur
    private fun stopStreamingServiceInternal() {
        Log.d(TAG, "Stopping streaming service.")
        FileLogger.getInstance()?.info(TAG, "Stopping streaming service")
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP_STREAMING
        }
        startService(intent)
        isStreaming.value = false // Yayın durumunu güncelle
    }
}

// Composable'ı Activity state'ine ve event'lerine bağla
@Composable
fun StreamingScreen(
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val context = LocalContext.current
    var ipAddress by remember { mutableStateOf<String?>(null) }
    var selectedPort by remember { mutableStateOf(StreamingService.DEFAULT_PORT.toString()) }
    var isPortValid by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        ipAddress = getLocalIpAddress(context)
        // TODO: Servisin zaten çalışıp çalışmadığını Activity'den kontrol et
        //       ve başlangıçta isStreaming state'ini doğru ayarla.
    }

    fun validatePort(port: String): Boolean {
        return try {
            val portNum = port.toInt()
            portNum in 1024..65535
        } catch (e: NumberFormatException) {
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (ipAddress != null) {
            Text("Cihaz IP Adresi:")
            Text(text = "$ipAddress:${selectedPort}", style = MaterialTheme.typography.headlineMedium)
        } else {
            Text("İnternet bağlantısı bulunamadı. WiFi veya mobil veri bağlantınızı kontrol edin.")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // Port seçimi
        OutlinedTextField(
            value = selectedPort,
            onValueChange = { 
                selectedPort = it
                isPortValid = validatePort(it)
            },
            label = { Text("Port") },
            enabled = !isStreaming,
            isError = !isPortValid,
            supportingText = {
                if (!isPortValid) {
                    Text("Port 1024-65535 arasında olmalıdır")
                }
            },
            modifier = Modifier.width(200.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isStreaming) {
            Button(onClick = onStopClick) { // Activity'deki metodu çağır
                Text("Yayını Durdur")
            }
        } else {
            Button(
                onClick = {
                    if (isPortValid && ipAddress != null) {
                        (context as MainActivity).setSelectedPort(selectedPort.toInt())
                        onStartClick()
                    }
                },
                enabled = ipAddress != null && isPortValid
            ) {
                Text("Yayını Başlat")
            }
        }
    }
}

// --- Yardımcı Fonksiyonlar --- (Değişiklik yok)
private fun getLocalIpAddress(context: Context): String? {
    try {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null

        // Check for any internet connectivity (including mobile data)
        if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return null
        }

        // Get network interface details
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        Log.e("getLocalIpAddress", "IP adresi alınırken hata oluştu", e)
    }
    return null
}

// Composable içinden doğrudan context.startService çağırmak yerine
// event'leri Activity'ye ilettiğimiz için bu global helper'lara gerek kalmadı.
/*
private fun startStreamingService(context: Context) {
    // ... (eski kod)
}

private fun stopStreamingService(context: Context) {
    // ... (eski kod)
}
*/