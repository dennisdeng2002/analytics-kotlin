package com.segment.analytics.kotlin.core.platform

import com.segment.analytics.kotlin.core.*
import com.segment.analytics.kotlin.core.platform.plugins.logger.LogKind
import com.segment.analytics.kotlin.core.platform.plugins.logger.log
import com.segment.analytics.kotlin.core.platform.plugins.logger.segmentLog
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import com.segment.analytics.kotlin.core.utilities.EncodeDefaultsJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream

internal class EventPipeline(
    private val analytics: Analytics,
    private val logTag: String,
    apiKey: String,
    private val flushPolicies: List<FlushPolicy>,
    var apiHost: String = Constants.DEFAULT_API_HOST
) {

    private var writeChannel: Channel<BaseEvent>

    private var uploadChannel: Channel<String>

    private val httpClient: HTTPClient = HTTPClient(apiKey)

    private val storage get() = analytics.storage

    private val scope get() = analytics.analyticsScope

    var running: Boolean
        private set

    companion object {
        internal const val FLUSH_POISON = "#!flush"
        internal val FLUSH_EVENT = ScreenEvent(FLUSH_POISON, FLUSH_POISON, emptyJsonObject).apply { messageId = FLUSH_POISON }
        internal const val UPLOAD_SIG = "#!upload"
    }

    init {
        running = false

        writeChannel = Channel(UNLIMITED)
        uploadChannel = Channel(UNLIMITED)

        registerShutdownHook()
    }

    fun put(event: BaseEvent) {
        writeChannel.trySend(event)
    }

    fun flush() {
        writeChannel.trySend(FLUSH_EVENT)
    }

    fun start() {
        if (running) return
        running = true

        // avoid to re-establish a channel if the pipeline just gets created
        if (writeChannel.isClosedForSend || writeChannel.isClosedForReceive) {
            writeChannel = Channel(UNLIMITED)
            uploadChannel = Channel(UNLIMITED)
        }

        schedule()
        write()
        upload()
    }

    fun stop() {
        if (!running) return
        running = false

        uploadChannel.cancel()
        writeChannel.cancel()
        unschedule()
    }

    internal fun stringifyBaseEvent(payload: BaseEvent): String {
        val finalPayload = EncodeDefaultsJson.encodeToJsonElement(payload)
            .jsonObject.filterNot { (k, v) ->
                // filter out empty userId and traits values
                (k == "userId" && v.jsonPrimitive.content.isBlank()) || (k == "traits" && v == emptyJsonObject)
            }

        val stringVal = Json.encodeToString(finalPayload)
        return stringVal
    }

    private fun write() = scope.launch(analytics.fileIODispatcher) {
        for (event in writeChannel) {
            // write to storage
            val isPoison = (event.messageId == FLUSH_POISON)
            if (!isPoison) try {
                val stringVal = stringifyBaseEvent(event)
                analytics.log("$logTag running $stringVal")
                storage.write(Storage.Constants.Events, stringVal)

                flushPolicies.forEach { flushPolicy -> flushPolicy.updateState(event) }
            }
            catch (e : Exception) {
                Analytics.segmentLog("Error adding payload: $event", kind = LogKind.ERROR)
            }

            // if flush condition met, generate paths
            if (isPoison || flushPolicies.any { it.shouldFlush() }) {
                uploadChannel.trySend(UPLOAD_SIG)
                flushPolicies.forEach { it.reset() }
            }
        }
    }

    private fun upload() = scope.launch(analytics.networkIODispatcher) {
        uploadChannel.consumeEach {
            analytics.log("$logTag performing flush")
            withContext(analytics.fileIODispatcher) {
                storage.rollover()
            }

            val fileUrlList = parseFilePaths(storage.read(Storage.Constants.Events))
            for (url in fileUrlList) {
                // upload event file
                val file = File(url)
                if (!file.exists()) continue

                var shouldCleanup = true
                try {
                    val connection = httpClient.upload(apiHost)
                    connection.outputStream?.let {
                        // Write the payloads into the OutputStream.
                        val fileInputStream = FileInputStream(file)
                        fileInputStream.copyTo(connection.outputStream)
                        fileInputStream.close()
                        connection.outputStream.close()

                        // Upload the payloads.
                        connection.close()
                    }
                    // Cleanup uploaded payloads
                    analytics.log("$logTag uploaded $url")
                } catch (e: Exception) {
                    shouldCleanup = handleUploadException(e, file)
                }

                if (shouldCleanup) {
                    storage.removeFile(file.path)
                }
            }
        }
    }

    private fun schedule() {
        flushPolicies.forEach { it.schedule(analytics) }
    }

    private fun unschedule() {
        flushPolicies.forEach { it.unschedule() }
    }



    private fun handleUploadException(e: Exception, file: File): Boolean {
        var shouldCleanup = false
        if (e is HTTPException) {
            analytics.log("$logTag exception while uploading, ${e.message}")
            if (e.is4xx() && e.responseCode != 429) {
                // Simply log and proceed to remove the rejected payloads from the queue.
                Analytics.segmentLog(
                    message = "Payloads were rejected by server. Marked for removal.",
                    kind = LogKind.ERROR
                )
                shouldCleanup = true
            } else {
                Analytics.segmentLog(
                    message = "Error while uploading payloads",
                    kind = LogKind.ERROR
                )
            }
        }
        else {
            Analytics.segmentLog(
                """
                    | Error uploading events from batch file
                    | fileUrl="${file.path}"
                    | msg=${e.message}
                """.trimMargin(), kind = LogKind.ERROR
            )
        }

        return shouldCleanup
    }

    private fun registerShutdownHook() {
        // close the stream if the app shuts down
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                this@EventPipeline.stop()
            }
        })
    }
}