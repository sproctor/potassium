package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.exception.UpdateException

public sealed class UpdateResult {
    public data class Available(
        val info: UpdateInfo,
        val level: UpdateLevel,
    ) : UpdateResult()

    public data object NotAvailable : UpdateResult()

    public data class Error(
        val exception: UpdateException,
    ) : UpdateResult()
}
