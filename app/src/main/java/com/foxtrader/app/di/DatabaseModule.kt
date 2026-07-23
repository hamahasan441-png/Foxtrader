package com.foxtrader.app.di

import android.content.Context
import androidx.room.Room
import com.foxtrader.app.data.local.FoxDatabase
import com.foxtrader.app.data.local.dao.CandleDao
import com.foxtrader.app.data.local.dao.JournalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FoxDatabase =
        Room.databaseBuilder(context, FoxDatabase::class.java, FoxDatabase.NAME)
            // Real migration for user-authored tables (journal); candle cache
            // can still be destructively rebuilt as a last resort.
            .addMigrations(*FoxDatabase.MIGRATIONS)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCandleDao(db: FoxDatabase): CandleDao = db.candleDao()

    @Provides
    fun provideJournalDao(db: FoxDatabase): JournalDao = db.journalDao()
}
