package com.zero.v2.app.acceptance

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

/** Keep the real app UID/keystore and Hilt component; do not launch normal modules or jobs. */
class ProviderAcceptanceRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) = Unit

}
