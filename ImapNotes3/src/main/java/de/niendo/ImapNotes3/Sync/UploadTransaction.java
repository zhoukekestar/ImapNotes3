package de.niendo.ImapNotes3.Sync;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.IOException;
import java.util.UUID;
import de.niendo.ImapNotes3.Miscs.NoteMime;

/** A persistent operation ID makes lost APPEND responses safe to retry. */
public final class UploadTransaction {
    public interface Remote {
        long find(String operationId) throws MessagingException;
        long append(Message message) throws MessagingException;
        void retireOriginal(Message message) throws MessagingException, IOException;
    }
    private UploadTransaction() {}

    public static String ensureId(Message message) throws MessagingException {
        String[] ids = message.getHeader(NoteMime.UPLOAD_ID);
        if (ids != null && !ids[0].isEmpty()) return ids[0];
        String id = UUID.randomUUID().toString();
        message.setHeader(NoteMime.UPLOAD_ID, id);
        return id;
    }

    public static long upload(Message message, Remote remote) throws MessagingException, IOException {
        String[] ids = message.getHeader(NoteMime.UPLOAD_ID);
        if (ids == null || ids[0].isEmpty()) throw new MessagingException("Persist an upload ID before sending");
        long uid = remote.find(ids[0]);
        if (uid <= 0) {
            uid = remote.append(message);
            if (uid <= 0) uid = remote.find(ids[0]);
        }
        if (uid <= 0) throw new MessagingException("Upload acknowledgement unavailable; original retained");
        remote.retireOriginal(message);
        return uid;
    }

    public static boolean canRetire(Message replacement, Message original, long validity)
            throws MessagingException, IOException {
        String[] namespace = replacement.getHeader(NoteMime.VALIDITY);
        String[] base = replacement.getHeader(NoteMime.BASE_HASH);
        return original != null && namespace != null && base != null &&
                namespace[0].equals(Long.toString(validity)) && base[0].equals(NoteMime.hash(original));
    }
}
