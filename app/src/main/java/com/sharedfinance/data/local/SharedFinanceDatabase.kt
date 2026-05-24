package com.sharedfinance.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        ParticipantEntity::class,
        ExpenseEntity::class,
        HistoryEntity::class,
        SyncLogEntity::class,
        ConflictResolutionLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SharedFinanceDatabase : RoomDatabase() {
    abstract fun sharedFinanceDao(): SharedFinanceDao
}
