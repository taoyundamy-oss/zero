package com.zero.v2.services.termux

import com.zero.v2.core.common.ReleaseState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseReplyTest {
    @Test fun `transport failure never reports successful activation`() {
        assertFalse(ReleaseReply.decode(null).ok)
        val reply = ReleaseReply.decode(JSONObject("""{"ok":false,"state":"FAILED","error":"rollback_failed"}"""))
        assertFalse(reply.ok)
        assertEquals("rollback_failed", reply.error)
    }
    @Test fun `canary wire state is preserved`() {
        val reply = ReleaseReply.decode(JSONObject("""{"ok":true,"state":"CANARY","deployment_id":"abc"}"""))
        assertEquals(ReleaseState.CANARY, reply.state)
        assertEquals("abc", reply.deploymentId)
    }
    @Test fun `malformed explicit manifest fails before staging`() {
        val parser = CompileManager()
        assertNull(parser.parseManifest("# ZERO_MANIFEST: broken\nprint(1)"))
        assertNull(parser.parseManifest("# ZERO_MANIFEST: {\"runtime\":\"shell\"}"))
        assertEquals(emptyList<String>(), parser.parseManifest("print(1)")!!.deps)
    }
}
