package com.zero.v2.services.termux

import okhttp3.Request

/** Pair only through the permission-protected Termux command channel; never through unauthenticated HTTP. */
internal object SupervisorAuthorization {
    fun authorize(request: Request, port: Int, token: String?): Request {
        require(request.url.scheme == "http" && request.url.host == "127.0.0.1" && request.url.port == port)
        check(token?.matches(Regex("[0-9a-f]{64}")) == true) { "Supervisor pairing unavailable" }
        return request.newBuilder().header("Authorization", "Bearer $token").build()
    }

    fun script(prefix: String): String = """
        import os, secrets
        from pathlib import Path
        p = Path('$prefix/zero/.control-token')
        p.parent.mkdir(parents=True, exist_ok=True)
        try:
            fd = os.open(str(p), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(fd, 'w') as f:
                f.write(secrets.token_hex(32))
                f.flush()
                os.fsync(f.fileno())
        os.chmod(str(p), 0o600)
        print(p.read_text().strip())
    """.trimIndent()
}
