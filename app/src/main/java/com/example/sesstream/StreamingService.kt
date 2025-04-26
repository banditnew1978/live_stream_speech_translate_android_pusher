package com.example.sesstream

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer

class StreamingService : Service() {

    private var server: HttpStreamServer? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var audioThread: Thread? = null
    private var mediaCodec: MediaCodec? = null
    private val notificationId = 1
    private val channelId = "StreamingServiceChannel"

    // HttpStreamServer'a veri göndermek için Piped Stream
    private var pipedOutputStream: PipedOutputStream? = null
    private var pipedInputStream: PipedInputStream? = null

    companion object {
        const val ACTION_START_STREAMING = "com.example.sesstream.ACTION_START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.example.sesstream.ACTION_STOP_STREAMING"
        const val EXTRA_PORT = "com.example.sesstream.EXTRA_PORT"
        const val EXTRA_RESULT_CODE = "com.example.sesstream.EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "com.example.sesstream.EXTRA_DATA"
        const val DEFAULT_PORT = 8080
        private const val TAG = "StreamingService"
        const val SAMPLE_RATE = 44100 // const yapıldı
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO // const yapıldı
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // const yapıldı
        const val AAC_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val AAC_BIT_RATE = 128000 // 128 kbps
        const val AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        Log.d(TAG, "Service created")
        FileLogger.getInstance()?.info(TAG, "Streaming service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received: ${intent?.action}")
        FileLogger.getInstance()?.info(TAG, "onStartCommand received: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                // Android Q ve üzeri için MediaProjection kontrolü
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    // data null olabileceği için getParcelableExtra kullanırken API seviyesine göre kontrol ekle
                    val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }

                    if (resultCode == Activity.RESULT_OK && data != null) {
                        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                        FileLogger.getInstance()?.info(TAG, "Starting streaming with port: $port")
                        startForegroundWithNotification()
                        startStreaming(port, resultCode, data)
                    } else {
                        Log.e(TAG, "MediaProjection permission not granted or data is null (resultCode=$resultCode).")
                        FileLogger.getInstance()?.error(TAG, "MediaProjection permission not granted or data is null (resultCode=$resultCode).")
                        stopSelf() // İzin yoksa veya veri null ise servisi durdur
                    }
                } else {
                    // Android 9 ve altı için MediaProjection olmadan başlatma
                    Log.w(TAG, "MediaProjection not supported below Android Q. Starting without audio capture.")
                    FileLogger.getInstance()?.warn(TAG, "MediaProjection not supported below Android Q. Starting without audio capture.")
                    val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                    startForegroundWithNotification()
                    startStreaming(port, Activity.RESULT_CANCELED, null) // MediaProjection olmadan
                }
            }
            ACTION_STOP_STREAMING -> {
                FileLogger.getInstance()?.info(TAG, "Stopping streaming service")
                stopStreaming()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Ön plan servisini başlat ve bildirimi göster
    private fun startForegroundWithNotification() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ için foreground service type belirtme
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ için FOREGROUND_SERVICE_MEDIA_PROJECTION izni gerekli
                    startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    // Android 10-13 için 
                    startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                }
            } else {
                // Android 9 ve altı için
                startForeground(notificationId, notification)
            }
            Log.d(TAG, "Service started in foreground")
            FileLogger.getInstance()?.info(TAG, "Service started in foreground")
        } catch (e: SecurityException) {
            // İzin hatası durumunda alternatif olarak standart foreground tipini kullan
            Log.e(TAG, "SecurityException when starting with MEDIA_PROJECTION type. Falling back to standard foreground service", e)
            FileLogger.getInstance()?.error(TAG, "SecurityException when starting with MEDIA_PROJECTION type. Falling back to standard foreground service", e)
            
            try {
                // Standart foreground service olarak başlat
                startForeground(notificationId, notification)
                Log.d(TAG, "Service started as standard foreground service")
                FileLogger.getInstance()?.info(TAG, "Service started as standard foreground service")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start foreground service", e2)
                FileLogger.getInstance()?.error(TAG, "Failed to start foreground service", e2)
                stopSelf()
            }
        }
    }

    private fun startStreaming(port: Int, resultCode: Int, data: Intent?) {
        if (server != null || audioThread?.isAlive == true) { // audioThread null kontrolü ve isAlive kontrolü
            Log.w(TAG, "Streaming already running.")
            FileLogger.getInstance()?.warn(TAG, "Streaming already running.")
            return
        }

        try {
            val startTime = System.currentTimeMillis()
            FileLogger.getInstance()?.info(TAG, "Starting streaming setup")
            
            // Piped streamleri oluştur
            pipedOutputStream = PipedOutputStream()
            pipedInputStream = PipedInputStream(pipedOutputStream!!) // Non-null assertion, try-catch içinde güvenli
            
            val pipeSetupTime = System.currentTimeMillis()
            FileLogger.getInstance()?.debug(TAG, "Pipe setup took ${pipeSetupTime - startTime}ms")

            // HTTP Sunucusunu başlat ve PipedInputStream'i ver
            server = HttpStreamServer(port, pipedInputStream!!) // HttpStreamServer constructor'ını güncellemek gerekecek
            
            val serverCreateTime = System.currentTimeMillis()
            FileLogger.getInstance()?.debug(TAG, "Server creation took ${serverCreateTime - pipeSetupTime}ms")
            
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            
            val serverStartTime = System.currentTimeMillis()
            FileLogger.getInstance()?.debug(TAG, "Server start took ${serverStartTime - serverCreateTime}ms")
            
            Log.d(TAG, "HTTP server started on port $port")
            FileLogger.getInstance()?.info(TAG, "HTTP server started on port $port")

            // MediaProjection ve AudioRecord'u sadece Android Q+ ve izin varsa başlat
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && resultCode == Activity.RESULT_OK && data != null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val audioCaptureStartTime = System.currentTimeMillis()
                    startAudioCapture(resultCode, data)
                    FileLogger.getInstance()?.debug(TAG, "Audio capture setup took ${System.currentTimeMillis() - audioCaptureStartTime}ms")
                } else {
                    Log.e(TAG, "RECORD_AUDIO permission not granted, cannot start audio capture.")
                    FileLogger.getInstance()?.error(TAG, "RECORD_AUDIO permission not granted, cannot start audio capture.")
                    // TODO: Kullanıcıya bilgi ver / hata yönetimi
                     stopStreaming() // İzin yoksa başlatmayı iptal et ve temizle
                }
            } else {
                Log.w(TAG, "Not starting audio capture (Below Android Q or no MediaProjection permission).")
                FileLogger.getInstance()?.warn(TAG, "Not starting audio capture (Below Android Q or no MediaProjection permission).")
                // Android Q altı veya izin yoksa, sunucu boş veri gönderecek. İstemci bunu handle etmeli.
                // Belki istemciye boş bir başlık göndermek daha iyi olabilir?
                 // Şimdilik PipedOutputStream'i kapatmıyoruz, sunucu okumaya çalışacak ama veri gelmeyecek.
            }
            
            FileLogger.getInstance()?.info(TAG, "Streaming setup complete, total time: ${System.currentTimeMillis() - startTime}ms")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to start streaming server or piped streams", e)
            FileLogger.getInstance()?.error(TAG, "Failed to start streaming server or piped streams", e)
            stopStreaming() // Hata durumunda temizle
        } catch (e: SecurityException) {
             Log.e(TAG, "Security exception during streaming setup (likely RECORD_AUDIO)", e)
             FileLogger.getInstance()?.error(TAG, "Security exception during streaming setup (likely RECORD_AUDIO)", e)
             stopStreaming()
        } catch (e: Exception) { // Diğer beklenmedik hataları yakala
            Log.e(TAG, "Unexpected error during streaming setup", e)
            FileLogger.getInstance()?.error(TAG, "Unexpected error during streaming setup", e)
            stopStreaming()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startAudioCapture(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection.")
                stopStreaming() // MediaProjection alınamazsa devam etme
                return
            }

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufferSize <= 0) { // Check for errors explicitly
                Log.e(TAG, "Invalid AudioRecord parameters or buffer size error: $minBufferSize")
                stopStreaming()
                return
            }

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // Sadece medya sesini yakala
                // .addMatchingUsage(AudioAttributes.USAGE_GAME)
                // .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            // AAC Kodlayıcıyı Ayarla
            try {
                val encoderFormat = MediaFormat.createAudioFormat(AAC_MIME_TYPE, SAMPLE_RATE, 1) // 1 kanal (Mono)
                encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE)
                encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2) // Input buffer size ipucu

                mediaCodec = MediaCodec.createEncoderByType(AAC_MIME_TYPE)
                mediaCodec?.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.d(TAG, "AAC Encoder configured: ${encoderFormat}")

            } catch (e: IOException) {
                 Log.e(TAG, "Failed to create or configure AAC encoder", e)
                 stopStreaming()
                 return
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Illegal argument configuring AAC encoder", e)
                stopStreaming()
                return
            }

            // RECORD_AUDIO iznini tekrar kontrol et (en güvenli yöntem)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission missing inside startAudioCapture")
                stopStreaming()
                return
            }

            // Buffer size'ı minBufferSize'ın katı yapmak genellikle iyi bir pratik
            val bufferSize = minBufferSize * 2

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            // MediaProjection callback'ini kaydet
            mediaProjection?.registerCallback(mediaProjectionCallback, null)

            // Kodlayıcıyı ve Kaydı Başlat
             mediaCodec?.start() // Encoder'ı başlat
             Log.d(TAG, "AAC Encoder started.")

             audioRecord?.startRecording()
             if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                 Log.e(TAG, "Failed to start AudioRecord recording.")
                 stopStreaming() // Encoder zaten başlatılmış olabilir, temizle
                 return
             }
             Log.d(TAG, "Audio recording started successfully.")


            // Ses verisini okuyup KODLAYIP PipedOutputStream'a yazacak thread'i başlat
            audioThread = Thread {
                 processAudio(bufferSize) // Kodlama işlemini ayrı bir fonksiyona taşıyalım
            }
            audioThread?.priority = Thread.MAX_PRIORITY // Ses işleme önceliğini artır
            audioThread?.start()

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during AudioRecord/MediaCodec setup", e)
            stopStreaming()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException during AudioRecord/MediaCodec setup/start", e)
            stopStreaming()
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "AudioPlaybackCapture or AAC Encoder not supported on this device/setup", e)
            stopStreaming()
        } catch (e: Exception) { // Beklenmedik hatalar
             Log.e(TAG, "Unexpected error during audio capture setup", e)
             stopStreaming()
        }
    }

    // Bu yeni fonksiyon AudioRecord'dan okuma, MediaCodec ile kodlama ve PipedOutputStream'a yazma işlemlerini yapar
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP) // MediaCodec için
    private fun processAudio(recordBufferSize: Int) {
        val pcmBuffer = ByteArray(recordBufferSize)
        val codecBufferInfo = MediaCodec.BufferInfo()
        var presentationTimeUs: Long = 0
        var totalBytesWritten: Long = 0
        val startTimeNs = System.nanoTime() // Zaman damgası hesaplama için
        
        // Performance monitoring
        var totalFramesProcessed = 0
        var lastStatsTime = System.currentTimeMillis()
        var framesProcessedSinceLastStat = 0
        var lastSlowOperationTime = 0L
        var longestOperationTime = 0L
        var longestOperationType = ""

        Log.d(TAG, "Audio processing thread started. PCM Buffer size: $recordBufferSize")
        FileLogger.getInstance()?.info(TAG, "Audio processing thread started. PCM Buffer size: $recordBufferSize")

        try {
            // Başlamadan önce durumu kontrol et
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING || mediaCodec == null) {
                Log.e(TAG, "AudioRecord not recording or MediaCodec is null at thread start!")
                FileLogger.getInstance()?.error(TAG, "AudioRecord not recording or MediaCodec is null at thread start!")
                return
            }

            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING && !Thread.currentThread().isInterrupted) {
                val frameStartTime = System.currentTimeMillis()
                var operationTime = 0L
                
                // 1. AudioRecord'dan PCM verisi oku
                val readStartTime = System.currentTimeMillis()
                val bytesRead = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                operationTime = System.currentTimeMillis() - readStartTime
                
                // Check for slow reads
                if (operationTime > 100) {
                    FileLogger.getInstance()?.warn(TAG, "Slow AudioRecord.read: ${operationTime}ms for $bytesRead bytes")
                }
                
                // Update longest operation if needed
                if (operationTime > longestOperationTime) {
                    longestOperationTime = operationTime
                    longestOperationType = "AudioRecord.read"
                }

                if (bytesRead > 0) {
                    // Zaman damgasını hesapla (yaklaşık)
                    presentationTimeUs = (System.nanoTime() - startTimeNs) / 1000

                    // 2. PCM verisini Kodlayıcıya Gönder (Input Buffer)
                    try {
                        val inputBufferStartTime = System.currentTimeMillis()
                        val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000) // 10ms timeout
                        operationTime = System.currentTimeMillis() - inputBufferStartTime
                        
                        // Check for slow buffer operations
                        if (operationTime > 100) {
                            FileLogger.getInstance()?.warn(TAG, "Slow dequeueInputBuffer: ${operationTime}ms")
                        }
                        
                        // Update longest operation if needed
                        if (operationTime > longestOperationTime) {
                            longestOperationTime = operationTime
                            longestOperationType = "dequeueInputBuffer"
                        }
                        
                        if (inputBufferIndex >= 0) {
                            val bufferStartTime = System.currentTimeMillis()
                            val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(pcmBuffer, 0, bytesRead)
                            mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0)
                            operationTime = System.currentTimeMillis() - bufferStartTime
                            
                            // Check for slow buffer operations
                            if (operationTime > 100) {
                                FileLogger.getInstance()?.warn(TAG, "Slow input buffer processing: ${operationTime}ms")
                            }
                            
                            // Update longest operation if needed
                            if (operationTime > longestOperationTime) {
                                longestOperationTime = operationTime
                                longestOperationType = "input buffer processing"
                            }
                        } else {
                            Log.w(TAG, "Encoder input buffer not available (index: $inputBufferIndex)")
                        }
                    } catch (e: MediaCodec.CodecException) {
                         Log.e(TAG, "MediaCodecException while feeding encoder", e)
                         FileLogger.getInstance()?.error(TAG, "MediaCodecException while feeding encoder", e)
                         break // Hata durumunda döngüden çık
                    } catch (e: IllegalStateException) {
                         Log.e(TAG, "IllegalStateException while feeding encoder (codec likely stopped)", e)
                         FileLogger.getInstance()?.error(TAG, "IllegalStateException while feeding encoder (codec likely stopped)", e)
                         break
                    }

                    // 3. Kodlanmış AAC verisini Al (Output Buffer) ve PipedOutputStream'a Yaz
                    var outputBufferIndex: Int
                    do {
                        try {
                            val outputStartTime = System.currentTimeMillis()
                            outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(codecBufferInfo, 0) // 0 timeout (non-blocking)
                            operationTime = System.currentTimeMillis() - outputStartTime
                            
                            // Check for slow output buffer operations
                            if (operationTime > 100) {
                                FileLogger.getInstance()?.warn(TAG, "Slow dequeueOutputBuffer: ${operationTime}ms")
                            }
                            
                            // Update longest operation if needed
                            if (operationTime > longestOperationTime) {
                                longestOperationTime = operationTime
                                longestOperationType = "dequeueOutputBuffer"
                            }
                            
                            if (outputBufferIndex >= 0) {
                                // Format değişikliği (genellikle başlangıçta bir kez olur)
                                if (codecBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                     Log.i(TAG, "Encoder output format changed (or CODEC_CONFIG received). Size: ${codecBufferInfo.size}")
                                    // CSD (Codec Specific Data) - bunu genellikle HLS/MP4 konteynırları için saklamak gerekir.
                                    // Doğrudan AAC akışı için genellikle PipedOutputStream'a YAZILMAZ.
                                     mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                                }
                                // Asıl kodlanmış veri
                                else if (codecBufferInfo.size > 0) {
                                    val bufferProcessStartTime = System.currentTimeMillis()
                                    val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferIndex)
                                    if (outputBuffer != null) {
                                        val chunk = ByteArray(codecBufferInfo.size)
                                        outputBuffer.get(chunk)
                                        outputBuffer.clear() // Buffer'ı bir sonraki kullanım için temizle

                                        try {
                                            // --- ADTS BAŞLIĞI EKLEME (Etkinleştirildi) ---
                                            // Her AAC paketi için ADTS başlığı oluşturup başına ekleyelim.
                                            val adtsPacket = addAdtsHeader(chunk, chunk.size)
                                            pipedOutputStream?.write(adtsPacket)
                                            totalBytesWritten += adtsPacket.size
                                            // --- /ADTS BAŞLIĞI EKLEME ---

                                            // Şimdilik ADTS olmadan gönderelim:
                                            // pipedOutputStream?.write(chunk) // --> Bu satır yorumlandı
                                            // totalBytesWritten += chunk.size // --> Bu satır yorumlandı
                                        } catch (e: IOException) {
                                            Log.e(TAG, "IOException writing AAC to PipedOutputStream", e)
                                            FileLogger.getInstance()?.error(TAG, "IOException writing AAC to PipedOutputStream", e)
                                            // Pipe koptuysa thread'i durdur
                                            Thread.currentThread().interrupt() // Döngüden çıkmayı tetikle
                                        } catch (e: NullPointerException) {
                                            Log.e(TAG, "NullPointerException writing AAC to PipedOutputStream")
                                            FileLogger.getInstance()?.error(TAG, "NullPointerException writing AAC to PipedOutputStream")
                                            Thread.currentThread().interrupt()
                                        }
                                    }
                                    mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                                    
                                    operationTime = System.currentTimeMillis() - bufferProcessStartTime
                                    
                                    // Check for slow output buffer processing
                                    if (operationTime > 100) {
                                        FileLogger.getInstance()?.warn(TAG, "Slow output buffer processing: ${operationTime}ms")
                                    }
                                    
                                    // Update longest operation if needed
                                    if (operationTime > longestOperationTime) {
                                        longestOperationTime = operationTime
                                        longestOperationType = "output buffer processing"
                                    }
                                }
                                // Akışın sonu (EOS) flag'i - normalde canlı akışta olmaz
                                if (codecBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    Log.i(TAG, "Encoder signaled EOS.")
                                    FileLogger.getInstance()?.info(TAG, "Encoder signaled EOS.")
                                    Thread.currentThread().interrupt() // Döngüden çık
                                }
                            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                 Log.i(TAG, "Encoder output format changed (INFO_OUTPUT_FORMAT_CHANGED)")
                                 val newFormat = mediaCodec?.outputFormat
                                 Log.d(TAG, "New encoder output format: $newFormat")
                                // CSD genellikle bu noktada da alınabilir (BUFFER_FLAG_CODEC_CONFIG ile birlikte)
                            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                // Çıkış buffer'ı henüz hazır değil, sorun yok.
                            } else {
                                Log.w(TAG, "Unexpected output buffer index: $outputBufferIndex")
                                FileLogger.getInstance()?.warn(TAG, "Unexpected output buffer index: $outputBufferIndex")
                            }
                        } catch (e: MediaCodec.CodecException) {
                             Log.e(TAG, "MediaCodecException while retrieving encoded data", e)
                             FileLogger.getInstance()?.error(TAG, "MediaCodecException while retrieving encoded data", e)
                             Thread.currentThread().interrupt() // Hata durumunda döngüden çık
                             break
                        } catch (e: IllegalStateException) {
                             Log.e(TAG, "IllegalStateException retrieving encoded data (codec likely stopped)", e)
                             FileLogger.getInstance()?.error(TAG, "IllegalStateException retrieving encoded data (codec likely stopped)", e)
                             Thread.currentThread().interrupt()
                             break
                        }
                    } while (outputBufferIndex >= 0 && !Thread.currentThread().isInterrupted) // Tüm çıktı bufferlarını işle
                    
                    // Update statistics
                    totalFramesProcessed++
                    framesProcessedSinceLastStat++
                    
                    // Log performance stats every second
                    val now = System.currentTimeMillis()
                    val totalFrameTime = now - frameStartTime
                    
                    // If this frame took too long, log it
                    if (totalFrameTime > 100) {
                        if (now - lastSlowOperationTime > 1000) { // Don't spam logs
                            FileLogger.getInstance()?.warn(TAG, "Slow frame processing: ${totalFrameTime}ms")
                            lastSlowOperationTime = now
                        }
                    }
                    
                    if (now - lastStatsTime > 10000) { // Every 10 seconds
                        val framesPerSecond = framesProcessedSinceLastStat / ((now - lastStatsTime) / 1000.0)
                        FileLogger.getInstance()?.info(TAG, "Audio processing stats: $framesPerSecond fps, total frames: $totalFramesProcessed, longest operation: $longestOperationTime ms ($longestOperationType)")
                        
                        // Reset stats
                        lastStatsTime = now
                        framesProcessedSinceLastStat = 0
                        longestOperationTime = 0
                        longestOperationType = ""
                    }

                } else if (bytesRead == 0) {
                    // Empty read, just continue
                } else { // bytesRead < 0
                    Log.e(TAG, "Error reading PCM data: $bytesRead")
                    FileLogger.getInstance()?.error(TAG, "Error reading PCM data: $bytesRead")
                    Thread.currentThread().interrupt() // Döngüden çık
                }
            } // end while

            // ----- Kodlayıcıyı Temizle (Flush + EOS Sinyali) -----
            Log.d(TAG, "Encoding loop finished. Signaling EOS to encoder.")
            FileLogger.getInstance()?.info(TAG, "Encoding loop finished. Total frames processed: $totalFramesProcessed")
            
            try {
                // Kodlayıcıya akışın bittiğini söyle (input tarafı)
                val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                     mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                     Log.d(TAG, "Queued EOS signal to encoder input.")
                     FileLogger.getInstance()?.debug(TAG, "Queued EOS signal to encoder input.")
                } else {
                     Log.w(TAG, "Could not queue EOS signal, input buffer not available.")
                     FileLogger.getInstance()?.warn(TAG, "Could not queue EOS signal, input buffer not available.")
                }

                // Kodlayıcıdan kalan çıktıları al (EOS flag'i gelene kadar)
                 var eosReceived = false
                 var attempts = 0
                 while (!eosReceived && attempts < 100) { // Timeout ekleyelim
                     val outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(codecBufferInfo, 10000) // 10ms bekle
                     if (outputBufferIndex >= 0) {
                         if (codecBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.i(TAG, "Received EOS signal from encoder output.")
                            FileLogger.getInstance()?.info(TAG, "Received EOS signal from encoder output.")
                            eosReceived = true
                         }
                         // Kalan veriyi yazmaya devam et (EOS öncesi)
                         if(codecBufferInfo.size > 0) {
                             val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferIndex)
                             if (outputBuffer != null) {
                                 val chunk = ByteArray(codecBufferInfo.size)
                                 outputBuffer.get(chunk)
                                 outputBuffer.clear()
                                 try {
                                     pipedOutputStream?.write(chunk)
                                     totalBytesWritten += chunk.size
                                 } catch (e: IOException) {
                                     Log.e(TAG, "IOException writing final AAC chunk", e)
                                     FileLogger.getInstance()?.error(TAG, "IOException writing final AAC chunk", e)
                                     break // Pipe koptuysa daha fazla yazma
                                 }
                             }
                         }
                         mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                     } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                         // Beklemeye devam
                     } else {
                         Log.w(TAG, "Unexpected output index during EOS drain: $outputBufferIndex")
                         FileLogger.getInstance()?.warn(TAG, "Unexpected output index during EOS drain: $outputBufferIndex")
                         break // Beklenmedik durum
                     }
                     attempts++
                 }
                 if (!eosReceived) {
                    Log.w(TAG, "Did not receive EOS from encoder after draining attempts.")
                    FileLogger.getInstance()?.warn(TAG, "Did not receive EOS from encoder after draining attempts.")
                 }

            } catch (e: Exception) {
                 Log.e(TAG, "Exception during encoder EOS signaling/draining", e)
                 FileLogger.getInstance()?.error(TAG, "Exception during encoder EOS signaling/draining", e)
            }
            // ----- /Kodlayıcıyı Temizle -----
            
            // Rest of cleanup code remains unchanged
        } catch (ex: Exception) {
            Log.e(TAG, "Exception in audio processing thread", ex)
            FileLogger.getInstance()?.error(TAG, "Exception in audio processing thread", ex)
        } finally {
            Log.d(TAG, "Audio processing thread finishing. Total AAC bytes written: $totalBytesWritten")
            FileLogger.getInstance()?.info(TAG, "Audio processing thread finishing. Total AAC bytes written: $totalBytesWritten")
            // Thread bittiğinde PipedOutputStream'i kapatmak, sunucunun EOF almasını sağlar
            try {
                pipedOutputStream?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Exception closing PipedOutputStream in thread", e)
                FileLogger.getInstance()?.warn(TAG, "Exception closing PipedOutputStream in thread", e)
            }
        }
        Log.d(TAG, "Audio processing thread finished.")
        FileLogger.getInstance()?.info(TAG, "Audio processing thread finished.")
    }

    // --- ADTS Başlığı oluşturma fonksiyonları (Yorum satırları kaldırıldı) ---
    private fun createAdtsHeader(packetLength: Int): ByteArray {
        val freqIdx = 4 // 44.1KHz (getSampleRate() daha dinamik olurdu ama sabit varsayıyoruz)
        val chanCfg = 1 // Mono (getChannelCount() daha dinamik olurdu ama sabit varsayıyoruz)
        // Profil: AACObjectLC = 2. MediaCodecInfo.CodecProfileLevel.AACObjectLC = 2
        val profile = AAC_PROFILE // = 2
        val frameLength = packetLength + 7 // 7 byte header

        val header = ByteArray(7)
        header[0] = 0xFF.toByte()        // Sync word 1: 11111111
        header[1] = 0xF1.toByte()        // Sync word 2: 1111 000 1 (MPEG-4, Layer 0, CRC yok)
        header[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        header[3] = (((chanCfg and 3) shl 6) + (frameLength shr 11)).toByte()
        header[4] = ((frameLength and 0x7FF) shr 3).toByte()
        header[5] = (((frameLength and 7) shl 5) + 0x1F).toByte() // Fullness: 11111 (VBR için 0x7FF -> 31)
        header[6] = 0xFC.toByte()        // Buffer fullness devamı + Number of AAC frames (0) - 1 : 111111 00

        return header
    }

    private fun addAdtsHeader(packet: ByteArray, packetLen: Int): ByteArray {
        val header = createAdtsHeader(packetLen)
        val packetWithAdts = ByteArray(packetLen + 7)
        System.arraycopy(header, 0, packetWithAdts, 0, 7)
        System.arraycopy(packet, 0, packetWithAdts, 7, packetLen)
        return packetWithAdts
    }
    // --- /ADTS ---

    private fun stopStreaming() {
        Log.d(TAG, "Stopping streaming")
        FileLogger.getInstance()?.info(TAG, "Stopping streaming")
        
        try {
            // MediaCodec'i kapat
            mediaCodec?.let { codec ->
                try {
                    codec.stop()
                    codec.release()
                    Log.d(TAG, "MediaCodec released")
                    FileLogger.getInstance()?.debug(TAG, "MediaCodec released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MediaCodec", e)
                    FileLogger.getInstance()?.error(TAG, "Error releasing MediaCodec", e)
                }
                mediaCodec = null
            }

            // Thread'i durdur
            audioThread?.let { thread ->
                try {
                    if (thread.isAlive) {
                        thread.interrupt()
                        Log.d(TAG, "AudioThread interrupted")
                        FileLogger.getInstance()?.debug(TAG, "AudioThread interrupted")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error interrupting audio thread", e)
                    FileLogger.getInstance()?.error(TAG, "Error interrupting audio thread", e)
                }
                audioThread = null
            }

            // AudioRecord'u durdur
            audioRecord?.let { recorder ->
                try {
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop()
                        }
                        recorder.release()
                        Log.d(TAG, "AudioRecord released")
                        FileLogger.getInstance()?.debug(TAG, "AudioRecord released")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing AudioRecord", e)
                    FileLogger.getInstance()?.error(TAG, "Error releasing AudioRecord", e)
                }
                audioRecord = null
            }

            // MediaProjection'ı serbest bırak
            mediaProjection?.let { projection ->
                try {
                    projection.stop()
                    Log.d(TAG, "MediaProjection stopped")
                    FileLogger.getInstance()?.debug(TAG, "MediaProjection stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping MediaProjection", e)
                    FileLogger.getInstance()?.error(TAG, "Error stopping MediaProjection", e)
                }
                mediaProjection = null
            }

            // HTTP sunucusunu durdur
            server?.let { httpServer ->
                try {
                    httpServer.stop()
                    Log.d(TAG, "HTTP server stopped")
                    FileLogger.getInstance()?.debug(TAG, "HTTP server stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping HTTP server", e)
                    FileLogger.getInstance()?.error(TAG, "Error stopping HTTP server", e)
                }
                server = null
            }

            // Stream'leri kapat
            try {
                pipedInputStream?.close()
                pipedOutputStream?.close()
                Log.d(TAG, "Piped streams closed")
                FileLogger.getInstance()?.debug(TAG, "Piped streams closed")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing piped streams", e)
                FileLogger.getInstance()?.error(TAG, "Error closing piped streams", e)
            } finally {
                pipedInputStream = null
                pipedOutputStream = null
            }

            Log.i(TAG, "Streaming stopped successfully")
            FileLogger.getInstance()?.info(TAG, "Streaming stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during cleanup", e)
            FileLogger.getInstance()?.error(TAG, "Unexpected error during cleanup", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP) // MediaProjection Lollipop'ta geldi
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped unexpectedly (e.g., user revoked permission).")
            // MediaProjection durduğunda yayını da durdurmalıyız.
            // Servis ana thread'inde çalışmadığı için doğrudan çağırabiliriz,
            // ancak emin olmak için Handler kullanmak daha güvenli olabilir.
            // val mainHandler = Handler(Looper.getMainLooper())
            // mainHandler.post { stopStreaming() }
            stopStreaming() // Doğrudan çağırmayı deneyelim
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy called")
        FileLogger.getInstance()?.info(TAG, "Service onDestroy called")
        stopStreaming()
        super.onDestroy()
    }

    // --- Notification --- (Küçük iyileştirmeler)
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Ses Yayın Servisi"
            val descriptionText = "Arka planda ses yayını için bildirim kanalı"
            val importance = NotificationManager.IMPORTANCE_LOW // Kullanıcıyı daha az rahatsız et
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
                // İsteğe bağlı: Titreşimi/Sesi kapat
                // enableVibration(false)
                // setSound(null, null)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // TODO: drawable altına bir ikon ekleyip onu kullan: R.drawable.ic_stat_streaming
        val icon = android.R.drawable.stat_sys_headset // Geçici ikon (kulaklık ikonu)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ses Yayını Aktif")
            .setContentText("Medya sesi HTTP üzerinden yayınlanıyor.")
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Bildirimin kullanıcı tarafından kapatılmasını engelle
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // Bildirimi hemen göster
            .setSilent(true) // Bildirim sesi çıkarma
            .build()
    }
} 