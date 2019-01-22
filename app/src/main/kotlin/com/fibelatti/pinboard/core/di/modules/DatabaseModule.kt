package com.fibelatti.pinboard.core.di.modules

import android.content.Context
import androidx.room.Room
import com.fibelatti.pinboard.core.persistence.database.AppDatabase
import com.fibelatti.pinboard.core.persistence.database.DATABASE_NAME
import com.fibelatti.pinboard.features.posts.data.PostsDao
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object DatabaseModule {

    @Provides
    @Singleton
    @JvmStatic
    fun providesDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .build()

    @Provides
    @JvmStatic
    fun postDao(appDatabase: AppDatabase): PostsDao = appDatabase.postDao()
}