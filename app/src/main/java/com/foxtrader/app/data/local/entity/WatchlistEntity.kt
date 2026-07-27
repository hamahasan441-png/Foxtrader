package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named watchlist.
 *
 * `WatchlistManager` held these in a `mutableListOf` — every list a user created
 * vanished on process death. Watchlists are user-authored data, so they get the
 * same durability guarantee as journal entries and drawings.
 */
@Entity(tableName = "watchlists")
data class WatchlistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean,
    val createdAt: Long,
)

/**
 * A symbol inside a watchlist.
 *
 * Modelled as a child table rather than a delimited string column so that
 * ordering, per-symbol notes and asset class survive properly, and so a symbol
 * can be removed without rewriting the whole list.
 *
 * `position` is explicit because SQL has no inherent row order — without it,
 * user reordering could not be persisted at all.
 *
 * The foreign key cascades: deleting a watchlist must not strand its members.
 */
@Entity(
    tableName = "watchlist_symbols",
    primaryKeys = ["watchlistId", "symbol"],
    foreignKeys = [
        ForeignKey(
            entity = WatchlistEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchlistId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["watchlistId", "position"])],
)
data class WatchlistSymbolEntity(
    val watchlistId: String,
    val symbol: String,
    val assetClass: String,
    val position: Int,
    val notes: String,
    val addedAt: Long,
)
