package de.niendo.ImapNotes3.Sync;

import com.sun.mail.iap.BadCommandException;
import com.sun.mail.imap.IMAPFolder;
import javax.mail.Message;
import javax.mail.MessagingException;

/** Optional cleanup after messages have been marked deleted, without a global EXPUNGE. */
final class SelectiveExpunge {
    private SelectiveExpunge() {}

    static void expunge(IMAPFolder folder, Message[] deleted) throws MessagingException {
        if (!((Boolean) folder.doCommand(protocol -> protocol.hasCapability("UIDPLUS")))) return;
        try {
            folder.expunge(deleted);
        } catch (MessagingException error) {
            // NetEase advertises UIDPLUS but rejects UID EXPUNGE with BAD Parse command error.
            // The successful Deleted flags already hide these messages from synchronization.
            // Transport errors and NO refusals still propagate so pending work can be retried.
            if (!(error.getNextException() instanceof BadCommandException)) throw error;
        }
    }
}
