package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.exception.UpdateException

sealed class UpdateResult {
    data class Available(
        val info: UpdateInfo,
        val level: UpdateLevel,
    ) : UpdateResult()

    data object NotAvailable : UpdateResult()

    data class Error(
        val exception: UpdateException,
    ) : UpdateResult()
}
