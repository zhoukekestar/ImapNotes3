package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import javax.mail.Folder;
import javax.mail.MessagingException;

/** Resolve the dedicated notes mailbox before creating or accessing messages. */
final class NotesFolder {
    private NotesFolder() {}

    static final class Prepared {
        final IMAPFolder folder;
        final boolean created;
        Prepared(IMAPFolder folder, boolean created) {
            this.folder = folder;
            this.created = created;
        }
    }

    static Prepared prepare(IMAPStore store, String server, String requested) throws MessagingException {
        Folder[] namespaces = store.getPersonalNamespaces();
        // QQ accepts APPEND only for drafts; custom folders reject APPEND, COPY and MOVE.
        // Treat Notes as a filtered notebook in Drafts instead of a writable custom mailbox.
        String name = "imap.qq.com".equalsIgnoreCase(server) &&
                ("Notes".equals(requested) || "其他文件夹/Notes".equals(requested)) ? "Drafts" : requested;
        if (namespaces.length > 0 && !namespaces[0].getFullName().isEmpty()) {
            String prefix = namespaces[0].getFullName();
            char separator = namespaces[0].getSeparator();
            if (separator != '\0' && prefix.charAt(prefix.length() - 1) != separator) prefix += separator;
            if (!name.startsWith(prefix)) name = prefix + name;
        }
        IMAPFolder folder = (IMAPFolder) store.getFolder(name);
        if (folder.exists()) return checked(folder, false);

        final IMAPFolder target = folder;
        try {
            // JavaMail create() suppresses the server's NO response. Preserve its explanation.
            target.doCommand(protocol -> { protocol.create(target.getFullName()); return null; });
        } catch (MessagingException error) {
            // A second client may have created the mailbox after our first LIST.
            if (target.exists()) return checked(target, false);
            throw new MessagingException("Cannot create notes folder: " + error.getMessage(), error);
        }
        if (!target.exists()) throw new MessagingException(
                "Server accepted CREATE but the notes folder is missing from LIST: " + target.getFullName());
        target.setSubscribed(true);
        return checked(target, true);
    }

    private static Prepared checked(IMAPFolder folder, boolean created) throws MessagingException {
        if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0)
            throw new MessagingException("Notes folder cannot hold messages: " + folder.getFullName());
        return new Prepared(folder, created);
    }
}
