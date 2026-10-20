package com.zero.v2.core.modulemanager.di

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.bus.SystemBusImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt 接口绑定模块。
 *
 * 将接口绑定到具体实现。不使用 @Binds 时，
 * Hilt 只知道 ChatBusImpl 但不知道它实现了 ChatBus，
 * 导致 @Inject ChatBus 的注入点运行时崩溃。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingModule {

    @Binds
    abstract fun bindChatBus(impl: ChatBusImpl): ChatBus

    @Binds
    abstract fun bindSystemBus(impl: SystemBusImpl): SystemBus
}
