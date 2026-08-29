package mvdicarlo.trialbound.sync;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 message authentication with the shared group password: anyone
 * with the party passphrase can join the party, but only holders of the group
 * password can produce events the group accepts. Replays are harmless (event
 * ids are idempotent).
 */
public final class PartyAuth {
    public static String hmac(String groupPassword, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // SecretKeySpec rejects empty keys; a fixed fallback keeps groups
            // without a password interoperable (no authentication, by choice).
            String key = groupPassword == null || groupPassword.isEmpty() ? "trialbound" : groupPassword;
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean verify(String groupPassword, String payload, String claimed) {
        if (claimed == null) {
            return false;
        }
        // Constant-time-ish comparison; timing attacks are out of scope here.
        return java.security.MessageDigest.isEqual(
                hmac(groupPassword, payload).getBytes(StandardCharsets.UTF_8),
                claimed.getBytes(StandardCharsets.UTF_8));
    }

    private PartyAuth() {
    }
}
