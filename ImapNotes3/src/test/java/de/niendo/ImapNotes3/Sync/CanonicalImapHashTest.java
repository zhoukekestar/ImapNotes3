package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPMessage;
import de.niendo.ImapNotes3.Miscs.NoteMime;
import java.io.*;
import java.util.Properties;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import org.junit.Test;
import static org.junit.Assert.*;

public class CanonicalImapHashTest {
    @Test public void bodyTextPaddingDoesNotTurnAnUnchangedNoteIntoAConflict() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage cached = new MimeMessage(session);
        String html = "<div>Unchanged note</div>";
        cached.setContent(html, "text/html; charset=UTF-8");
        cached.saveChanges();
        IMAPMessage remote = new IMAPMessage(session) {
            @Override public String getContentType() { return "text/html; charset=UTF-8"; }
            @Override public Object getContent() { return html + "\r\n"; }
            @Override public void writeTo(OutputStream out) throws IOException, javax.mail.MessagingException { cached.writeTo(out); }
        };
        assertEquals(html + "\r\n", remote.getContent());
        MimeMessage replacement = NoteMime.copy(cached);
        replacement.setHeader(NoteMime.VALIDITY, "1");
        replacement.setHeader(NoteMime.BASE_HASH, NoteMime.hash(cached));
        assertEquals(NoteMime.hash(cached), NoteMime.hash(remote));
        assertTrue(UploadTransaction.canRetire(replacement, remote, 1));
    }
}
