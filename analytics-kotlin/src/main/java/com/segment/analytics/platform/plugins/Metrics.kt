package com.segment.analytics.platform.plugins

import com.segment.analytics.BaseEvent
import com.segment.analytics.DateSerializer
import com.segment.analytics.utilities.putAll
import com.segment.analytics.utilities.putInContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

enum class MetricType(val type: Int) {
    Counter(0), // Not Verbose
    Gauge(1)    // Semi-verbose
}

@Serializable
data class Metric(
    var eventName: String = "",
    var metricName: String = "",
    var value: Double = 0.0,
    var tags: List<String> = emptyList(),
    var type: MetricType = MetricType.Counter,
    @Serializable(with = DateSerializer::class) var timestamp: Instant = Instant.now()
)

fun BaseEvent.addMetric(
    type: MetricType,
    name: String,
    value: Double,
    tags: List<String> = emptyList(),
    timestamp: Instant = Instant.now(),
) {
    val metric = Metric(
        eventName = this.type.name,
        metricName = name,
        value = value,
        tags = tags,
        type = type,
        timestamp = timestamp
    )

    val metrics = buildJsonArray {
        context["metrics"]?.jsonArray?.forEach {
            add(it)
        }
        add(Json { encodeDefaults = true }.encodeToJsonElement(Metric.serializer(), metric))
    }

    putInContext("metrics", metrics)
}