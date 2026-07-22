package com.foxtrader.app.di

import android.content.Context
import androidx.room.Room
import com.foxtrader.app.data.local.FoxDatabase
import com.foxtrader.app.data.local.dao.CandleDao
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
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCandleDao(db: FoxDatabase): CandleDao = db.candleDao()
}
