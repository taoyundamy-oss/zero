package com.zero.v2.app.acceptance

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to actual media owners; no replacement provider or permission bypass. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveAcceptanceEntryPoint {
    fun voice(): com.zero.v2.services.realtime.LocalVoiceProvider
    fun capture(): com.zero.v2.services.realtime.ScreenCaptureModule
}
