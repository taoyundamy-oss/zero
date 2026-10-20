package com.zero.v2.services.embedding

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VectorIndexModule {
    @Binds abstract fun bindVectorIndexRuntime(worker: VectorIndexWorker): VectorIndexRuntime
}
