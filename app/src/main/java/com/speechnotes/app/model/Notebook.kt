package com.speechnotes.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A flat notebook (Joplin-style). Notes reference a notebook by id; a
 * notebook is a container and a note lives in exactly one.
 */
@Serializable
data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
