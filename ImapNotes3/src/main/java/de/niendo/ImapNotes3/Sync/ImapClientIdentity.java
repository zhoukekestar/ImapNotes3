package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPStore;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.mail.MessagingException;

/** RFC 2971 identification, including the identity expected by NetEase IMAP. */
public final class ImapClientIdentity {
    private ImapClientIdentity() {}

    public static void send(IMAPStore store, String version) throws MessagingException {
        if (!store.hasCapability("ID")) return;
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("name", "ImapNotes3");
        identity.put("version", version);
        identity.put("vendor", "ImapNotes3 contributors");
        identity.put("support-url", "https://github.com/zhoukekestar/ImapNotes3/issues");
        // Application metadata only; never send the user's email or device identifiers.
        store.id(identity);
    }
}
