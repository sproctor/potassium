package com.seanproctor.potassium.updater.provider

import com.seanproctor.potassium.updater.internal.PlatformInfo
import com.seanproctor.potassium.updater.runtime.Platform

public class GenericProvider(
    baseUrl: String,
) : UpdateProvider {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val fileName = PlatformInfo.ymlFileName(channel, platform)
        return "$normalizedBaseUrl/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "$normalizedBaseUrl/$fileName"
}
