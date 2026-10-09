package de.niendo.ImapNotes3.Sync;

import javax.mail.Message;
import javax.mail.MessagingException;

/** A QQ notebook shares Drafts storage, but ordinary drafts are never notes. */
final class QQNotesScope {
    private QQNotesScope() {}
    static boolean applies(String server, String folder) {
        return "imap.qq.com".equalsIgnoreCase(server) && "Drafts".equals(folder);
    }
    static boolean isNote(Message message) throws MessagingException {
        String[] values = message.getHeader("X-Uniform-Type-Identifier");
        if (values == null) return false;
        for (String value : values) if ("com.apple.mail-note".equals(value.trim())) return true;
        return false;
    }
}
