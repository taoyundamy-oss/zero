package com.zero.v2.app.skills

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object SkillBinding {
    @Provides @Singleton fun store(@ApplicationContext context: Context) = SkillStore(java.io.File(context.filesDir, "knowledge-skills"))
}
