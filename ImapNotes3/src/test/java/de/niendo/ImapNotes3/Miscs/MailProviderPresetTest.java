package de.niendo.ImapNotes3.Miscs;

import org.junit.Test;
import static org.junit.Assert.*;

public class MailProviderPresetTest {
    @Test public void recognizesPastedAddressesRegardlessOfCase() {
        assertEquals(MailProviderPreset.QQ, MailProviderPreset.forEmail("  Example@QQ.COM  "));
        assertEquals(MailProviderPreset.NETEASE_163, MailProviderPreset.forEmail("Example@163.COM"));
    }

    @Test public void requiresTheExactProviderDomain() {
        assertNull(MailProviderPreset.forEmail("example@qq.com.example.org"));
        assertNull(MailProviderPreset.forEmail("example@163.com.example.org"));
        assertNull(MailProviderPreset.forEmail("example@company163.com"));
    }

    @Test public void ignoresIncompleteOrMalformedAddresses() {
        for (String email : new String[]{"", "@qq.com", "example@", "example@@163.com", "example@qq.com invalid"})
            assertEquals("", MailProviderPreset.domainOf(email));
    }

    @Test public void leavesOtherProvidersOnTheirExistingLoginPath() {
        assertNull(MailProviderPreset.forEmail("example@gmail.com"));
        assertNull(MailProviderPreset.forEmail("example@custom.example"));
    }
}
