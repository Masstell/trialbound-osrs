package mvdicarlo.crabmanmode.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PartyAuthTest {
    @Test
    public void acceptsMatchingPasswordAndPayload() {
        String mac = PartyAuth.hmac("hunter2", "Matt|[{...}]");
        assertTrue(PartyAuth.verify("hunter2", "Matt|[{...}]", mac));
    }

    @Test
    public void rejectsWrongPasswordPayloadOrMissingMac() {
        String mac = PartyAuth.hmac("hunter2", "payload");
        assertFalse(PartyAuth.verify("hunter3", "payload", mac));
        assertFalse(PartyAuth.verify("hunter2", "tampered", mac));
        assertFalse(PartyAuth.verify("hunter2", "payload", null));
    }

    @Test
    public void emptyPasswordIsAConsistentKey() {
        assertEquals(PartyAuth.hmac("", "x"), PartyAuth.hmac(null, "x"));
        assertTrue(PartyAuth.verify(null, "x", PartyAuth.hmac("", "x")));
    }
}
