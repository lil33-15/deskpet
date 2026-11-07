package com.deskpet.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class CodexPetConfig(
    val id: String,
    val displayName: String,
    val description: String = "",
    val spritesheetPath: String,
    val schema_version: String? = null,
    val states: List<AnimationState>? = null,
    /** 精灵图总行数（默认 9）。小小kmmy 用 16 行扩展网格；老素材（橘团）不填即按 9 行解析 */
    val rows: Int? = null
)

@Serializable
data class AnimationState(
    val name: String,
    val row: Int,
    val frames: Int
)
