package com.adafruit.glider.utils

import io.openroad.utils.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.Level

/**
 * Created by Antonio García (antonio@openroad.es)
 */

object LogManager {
    // Config
    private const val maxEntries = 10000
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

    // Structs
    data class Entry(
        val category: Category,
        val level: Level,
        val text: String,
        val millis: Long
    ) {

        enum class Category {
            Unknown,
            App,
            Bluetooth,
            FileTransferProtocol,
        }


        fun timeString(): String = formatter.format(Date(millis))

        /*
        companion object {
            fun debug(text: String): Entry {
                return Entry(text)
            }
        }*/
    }

    // Data - Private
    private val log by LogUtils()
    private var _entries =
        MutableStateFlow<MutableList<Entry>>(mutableListOf())
    val entries = _entries.asStateFlow()

    // region Actions

    fun log(entry: Entry) {
        log.info("log add: ${entry.text}")
        _entries.value.add(entry)

        // Limit entries count
        limitSizeIfNeeded()
    }

    // endregion

    // region Utils
    private fun limitSizeIfNeeded() {
        val currentSize = _entries.value.size
        if (currentSize > maxEntries) {
            _entries.value.drop(currentSize - maxEntries)
        }
    }
    // endregion
}