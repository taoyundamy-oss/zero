package com.zero.v2.app.acceptance

import com.zero.v2.services.security.CredentialStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only, explicit provisioning test; no exported component. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalModelAcceptanceEntryPoint {
    fun credentials(): CredentialStore
}
