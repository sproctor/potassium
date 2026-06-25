package com.seanproctor.potassium.updater

/**
 * Represents a completed update detected at application startup.
 * Returned by [PotassiumUpdater.consumeUpdateEvent] on the first launch after an update.
 */
public data class UpdateEvent(
    val previousVersion: String,
    val newVersion: String,
    val updateLevel: UpdateLevel,
)
