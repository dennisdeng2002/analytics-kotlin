package com.segment.analytics.platform.plugins

import com.segment.analytics.Analytics
import com.segment.analytics.BaseEvent
import com.segment.analytics.platform.Plugin
import com.segment.analytics.utilities.putInContextUnderKey

class DeviceToken(var token: String) : Plugin {
    companion object {
        const val SEGMENT_TOKEN_PLUGIN_NAME = "Segment_Token"
    }

    override var type = Plugin.Type.Before
    override var name = SEGMENT_TOKEN_PLUGIN_NAME
    override lateinit var analytics: Analytics

    override fun execute(event: BaseEvent): BaseEvent {
        event.putInContextUnderKey("device", "token", token)
        return event
    }
}

/**
 * Set a device token in your payload's context
 * @param token [String] Device Token to add to payloads
 */
fun Analytics.setDeviceToken(token: String) {
    var tokenPlugin = find(DeviceToken.SEGMENT_TOKEN_PLUGIN_NAME) as? DeviceToken
    if (tokenPlugin != null) {
        tokenPlugin.token = token
    } else {
        tokenPlugin = DeviceToken(token)
        add(tokenPlugin)
    }
}
