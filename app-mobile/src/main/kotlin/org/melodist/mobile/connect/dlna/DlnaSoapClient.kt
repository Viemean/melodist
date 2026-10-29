package org.melodist.mobile.connect.dlna

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.melodist.model.Song
import java.util.concurrent.TimeUnit

object DlnaSoapClient {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()

    suspend fun setAVTransportUri(
        controlUrl: String,
        streamUrl: String,
        coverUrl: String?,
        song: Song,
        mimeType: String = "audio/mpeg",
    ): Boolean =
        withContext(Dispatchers.IO) {
            val escapedTitle = escapeXml(song.name)
            val escapedSinger = escapeXml(song.singer)
            val escapedAlbum = escapeXml(song.album)
            val escapedStreamUrl = escapeXml(streamUrl)
            val albumArtTag =
                if (!coverUrl.isNullOrBlank()) {
                    val escapedCover = escapeXml(coverUrl)
                    """<upnp:albumArtURI dlna:profileID="JPEG_MED">$escapedCover</upnp:albumArtURI>"""
                } else {
                    ""
                }

            val didl =
                """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/"><item id="1" parentID="0" restricted="1"><dc:title>$escapedTitle</dc:title><dc:creator>$escapedSinger</dc:creator><upnp:artist>$escapedSinger</upnp:artist><upnp:album>$escapedAlbum</upnp:album><upnp:class>object.item.audioItem.musicTrack</upnp:class>$albumArtTag<res protocolInfo="http-get:*:$mimeType:*">$escapedStreamUrl</res></item></DIDL-Lite>"""

            val escapedDidl = escapeXml(didl)
            val body =
                """<InstanceID>0</InstanceID><CurrentURI>$escapedStreamUrl</CurrentURI><CurrentURIMetaData>$escapedDidl</CurrentURIMetaData>"""

            sendSoap(controlUrl, "SetAVTransportURI", body)
        }

    suspend fun play(controlUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = "<InstanceID>0</InstanceID><Speed>1</Speed>"
            sendSoap(controlUrl, "Play", body)
        }

    suspend fun stop(controlUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = "<InstanceID>0</InstanceID>"
            sendSoap(controlUrl, "Stop", body)
        }

    private fun sendSoap(
        controlUrl: String,
        action: String,
        innerXml: String,
    ): Boolean {
        val envelope =
            """<?xml version="1.0" encoding="utf-8"?><s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$innerXml</u:$action></s:Body></s:Envelope>"""

        val requestBody = envelope.toRequestBody("text/xml; charset=utf-8".toMediaType())
        val request =
            Request
                .Builder()
                .url(controlUrl)
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
                .addHeader("User-Agent", "DLNADOC/1.50")
                .post(requestBody)
                .build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("DlnaSoapClient", "SOAP $action failed", e)
            false
        }
    }

    private fun escapeXml(str: String): String =
        str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
