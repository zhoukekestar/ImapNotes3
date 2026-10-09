package de.niendo.ImapNotes3.Sync;

import java.util.Properties;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import org.junit.Test;
import static org.junit.Assert.*;

public class QQNotesScopeTest {
    @Test public void excludesOrdinaryDraftsWithoutReadingTheirBody() throws Exception {
        MimeMessage draft = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public Object getContent() { throw new AssertionError("Ordinary draft body must not be read"); }
        };
        draft.setSubject("A normal draft");
        assertFalse(QQNotesScope.isNote(draft));
    }
    @Test public void includesAppleCompatibleNotes() throws Exception {
        MimeMessage note = new MimeMessage(Session.getInstance(new Properties()));
        note.setHeader("X-Uniform-Type-Identifier", "com.apple.mail-note");
        assertTrue(QQNotesScope.isNote(note));
    }
    @Test public void rejectsPartialOrUnrelatedMarkers() throws Exception {
        MimeMessage draft = new MimeMessage(Session.getInstance(new Properties()));
        draft.setHeader("X-Uniform-Type-Identifier", "not-com.apple.mail-note-draft");
        assertFalse(QQNotesScope.isNote(draft));
        draft.setHeader("X-Uniform-Type-Identifier", "public.html");
        assertFalse(QQNotesScope.isNote(draft));
    }
    @Test public void onlyAppliesToQQDrafts() {
        assertTrue(QQNotesScope.applies("IMAP.QQ.COM", "Drafts"));
        assertFalse(QQNotesScope.applies("imap.gmail.com", "Drafts"));
        assertFalse(QQNotesScope.applies("imap.qq.com", "Custom"));
    }
}
