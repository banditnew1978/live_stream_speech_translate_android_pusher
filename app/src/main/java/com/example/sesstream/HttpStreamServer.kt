package com.example.sesstream

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.io.IOException

class HttpStreamServer(port: Int, private val audioStream: InputStream) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HttpStreamServer"
        // Ham PCM yerine AAC için MIME türü
        private const val MIME_TYPE = "audio/aac"
        // Eski PCM MIME türü yorum satırı olarak bırakılabilir veya silinebilir
        // private const val MIME_TYPE_PCM = "audio/L16;rate=44100;channels=1"
    }

    override fun serve(session: IHTTPSession): Response {
        Log.i(TAG, "HTTP request received: ${session.method} ${session.uri} from ${session.remoteIpAddress}")
        FileLogger.getInstance()?.info(TAG, "HTTP request received: ${session.method} ${session.uri} from ${session.remoteIpAddress}")

        // Sadece kök URL'ye gelen GET isteklerine yanıt verelim
        if (session.method == Method.GET && session.uri == "/") {
            try {
                // Chunked response kullanarak ses akışını gönder
                val response = newChunkedResponse(Response.Status.OK, MIME_TYPE, audioStream)
                // İstemcinin bağlantıyı açık tutmasını sağlamak için bazı başlıklar eklenebilir
                response.addHeader("Connection", "Keep-Alive")
                // Cachelemeyi engellemek için
                response.addHeader("Cache-Control", "no-cache")

                Log.i(TAG, "Starting audio stream for ${session.remoteIpAddress}")
                FileLogger.getInstance()?.info(TAG, "Starting audio stream for ${session.remoteIpAddress}")
                return response
            } catch (e: IOException) {
                Log.e(TAG, "IOException while creating response", e)
                FileLogger.getInstance()?.error(TAG, "IOException while creating response", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error: Could not stream audio.")
            } catch (e: Exception) {
                 Log.e(TAG, "Unexpected error while serving request", e)
                 FileLogger.getInstance()?.error(TAG, "Unexpected error while serving request", e)
                 return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error.")
            }
        } else {
            // Diğer isteklere 404 Not Found yanıtı ver
            Log.w(TAG, "Unhandled request: ${session.method} ${session.uri}")
            FileLogger.getInstance()?.warn(TAG, "Unhandled request: ${session.method} ${session.uri}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    // Sunucu durdurulduğunda stream'i kapatmak iyi bir pratik olabilir
    // Ancak stream'in sahibi Service olduğu için kapatma işlemini orada yapıyoruz.
    /*
    override fun stop() {
        super.stop()
        try {
            audioStream.close()
            Log.d(TAG, "Audio stream closed during server stop.")
        } catch (e: IOException) {
            Log.w(TAG, "Error closing audio stream during server stop", e)
        }
    }
    */
} 