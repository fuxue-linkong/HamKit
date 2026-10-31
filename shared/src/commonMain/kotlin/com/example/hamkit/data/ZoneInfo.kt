package com.example.hamkit.data

/**
 * 分区定位结果：CQ 分区、ITU 分区与 6 位 Maidenhead 网格。
 *
 * 说明：该模型已迁入跨平台内核（shared），因此不再标注 `@Immutable`。
 * 类本身是只包含 `val` 的 data class，字段类型均为 Compose 稳定类型
 * （`Int?` / `String`），Compose 编译器仍会将其推断为稳定类型，
 * 重组行为与原实现一致。
 */
data class ZoneInfo(
    val cqZone: Int?,
    val ituZone: Int?,
    val maidenhead: String
)
