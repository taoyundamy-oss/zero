package com.zero.v2.app.acceptance;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Test APK process needs no target APK or Kotlin runtime classes. Returns only metadata. */
public class ExternalAttachmentReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getStringExtra("replyAction");
        if (action == null || !action.startsWith("com.zero.v2.acceptance.")) { finish(); return; }
        Intent result = new Intent(action).setPackage("com.zero.v2")
                .putExtra("uid", android.os.Process.myUid()).putExtra("mime", getIntent().getType());
        try (InputStream input = getContentResolver().openInputStream(getIntent().getData())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (output.size() + length > 5 * 1024 * 1024) throw new IOException("test size limit");
                output.write(buffer, 0, length);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(output.toByteArray()))
                hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            result.putExtra("read", true).putExtra("hash", hash.toString())
                    .putExtra("resolverMime", getContentResolver().getType(getIntent().getData()));
        } catch (SecurityException e) { result.putExtra("read", false).putExtra("denied", true); }
        catch (Exception e) { result.putExtra("read", false).putExtra("denied", false); }
        sendBroadcast(result);
        finish();
    }
}
