package com.seanproctor.potassium.updater

import java.io.File

public data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Double,
    val file: File? = null,
)
