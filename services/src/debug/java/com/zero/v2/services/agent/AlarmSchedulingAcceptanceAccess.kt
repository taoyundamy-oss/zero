package com.zero.v2.services.agent

/** Debug controller access to the production decision policy; never compiled into release. */
object AlarmSchedulingAcceptanceAccess {
    fun submit(canExact: () -> Boolean, exact: () -> Unit, window: () -> Unit): String =
        AlarmSchedulingPolicy.submit(canExact, exact, window).mode.name
}
