package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.runtime.Platform
import com.seanproctor.potassium.updater.provider.UpdateProvider

class FakeUpdateProvider : UpdateProvider {
    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = "https://example.com/updates/$channel"

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "https://example.com/downloads/$version/$fileName"
}
