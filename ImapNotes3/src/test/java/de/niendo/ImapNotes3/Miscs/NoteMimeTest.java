package de.niendo.ImapNotes3.Miscs;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import javax.activation.DataHandler;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.util.ByteArrayDataSource;

public class NoteMimeTest {
    @Test public void unsavedHtmlDraftKeepsItsMarkup() throws Exception {
        MimeMessage m = message();
        m.setText("<div>中文标题</div><div><b>正常排版</b></div>", "UTF-8", "html");
        assertNull(m.getHeader("Content-Type"));
        assertEquals("<div>中文标题</div><div><b>正常排版</b></div>", NoteMime.html(m));
    }
    @Test public void encodedSubjectsAreNotDecodedTwice() throws Exception {
        MimeMessage m = message();
        m.setText("Fixture body", "UTF-8");
        m.setSubject("中文笔记 ✅", "UTF-8");
        assertEquals("中文笔记 ✅", NoteMime.subject(NoteMime.copy(m)));
        m.setSubject("café", "ISO-8859-1");
        assertEquals("café", NoteMime.subject(NoteMime.copy(m)));
    }
    @Test public void unencodedUtf8SubjectIsRecovered() throws Exception {
        MimeMessage m = message();
        m.setHeader("Subject", new String("中文笔记".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals("中文笔记", NoteMime.subject(m));
    }
    @Test public void literalUnicodeAndLatin1SubjectsArePreserved() throws Exception {
        MimeMessage m = message();
        m.setHeader("Subject", "café");
        assertEquals("café", NoteMime.subject(m));
        m.setHeader("Subject", "中文笔记");
        assertEquals("中文笔记", NoteMime.subject(m));
    }
    @Test public void htmlStreamFromAndroidActivationIsDecoded() throws Exception {
        byte[] encoded = "<p>中文笔记</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public Object getContent() { return new javax.mail.util.SharedByteArrayInputStream(encoded); }
        };
        m.setHeader("Content-Type", "text/html; charset=UTF-8");
        assertEquals("<p>中文笔记</p>", NoteMime.html(m));
    }
    @Test public void plainStreamRespectsCharsetAndEscapesMarkup() throws Exception {
        byte[] encoded = "café <script>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public Object getContent() { return new javax.mail.util.SharedByteArrayInputStream(encoded); }
        };
        m.setHeader("Content-Type", "text/plain; charset=ISO-8859-1");
        String html = NoteMime.html(m);
        assertTrue(html.contains("café &lt;script&gt;"));
        assertFalse(html.contains("<script>"));
    }
    private MimeMessage message() { return new MimeMessage(Session.getInstance(new Properties())); }
    private MimeBodyPart text(String text, String type) throws Exception {
        MimeBodyPart p = new MimeBodyPart(); p.setContent(text, type + "; charset=UTF-8"); return p;
    }
    private MimeBodyPart attachment(String name, String type, byte[] data) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new DataHandler(new ByteArrayDataSource(data, type)));
        p.setFileName(name); p.setDisposition(Part.ATTACHMENT); return p;
    }
    private byte[] bytes(Part p) throws Exception {
        try (InputStream in = p.getInputStream()) { return in.readAllBytes(); }
    }
    private MimeMessage complex() throws Exception {
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text("old body", "text/plain"));
        alternative.addBodyPart(text("<p>旧内容<img src=\"cid:photo\"></p>", "text/html"));
        MimeBodyPart body = new MimeBodyPart(); body.setContent(alternative);
        MimeMultipart related = new MimeMultipart("related"); related.addBodyPart(body);
        MimeBodyPart image = attachment("photo.png", "image/png", new byte[]{1,2,3,0,-1});
        image.setDisposition(Part.INLINE); image.setHeader("Content-ID", "<photo>"); related.addBodyPart(image);
        MimeBodyPart content = new MimeBodyPart(); content.setContent(related);
        MimeMultipart mixed = new MimeMultipart("mixed"); mixed.addBodyPart(content);
        mixed.addBodyPart(attachment("扫描.pdf", "application/pdf", new byte[]{4,5,6,0,-1}));
        mixed.addBodyPart(attachment("secret.txt", "text/plain", "attachment text".getBytes()));
        MimeMessage m = message(); m.setContent(mixed); m.setHeader("X-Universally-Unique-Identifier", "stable");
        m.saveChanges(); return NoteMime.copy(m);
    }

    @Test public void roundTripPreservesNestedAttachmentsAndIdentity() throws Exception {
        MimeMessage original = complex();
        MimeMessage edited = NoteMime.copy(NoteMime.rewrite(original, "<p>新内容<img src=\"cid:photo\"></p>"));
        assertTrue(NoteMime.html(edited).contains("新内容"));
        assertEquals("stable", edited.getHeader("X-Universally-Unique-Identifier")[0]);
        List<Part> before = NoteMime.attachments(original), after = NoteMime.attachments(edited);
        assertEquals(3, after.size());
        for (int i=0;i<before.size();i++) {
            assertArrayEquals(bytes(before.get(i)), bytes(after.get(i)));
            assertEquals(before.get(i).getFileName(), after.get(i).getFileName());
            assertArrayEquals(before.get(i).getHeader("Content-ID"), after.get(i).getHeader("Content-ID"));
        }
        assertTrue(NoteMime.html(original).contains("旧内容"));
    }
    @Test public void attachmentsAreNeverReadAsNoteBody() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("<p>body</p>", "text/html"));
        mp.addBodyPart(attachment("misleading.html", "text/html", "<b>secret</b>".getBytes()));
        MimeMessage m = message();m.setContent(mp);m.saveChanges();
        assertEquals("<p>body</p>", NoteMime.html(NoteMime.copy(m)));
    }
    @Test public void plainTextEscapesMarkup() throws Exception {
        MimeMessage m = message();m.setText("<script>alert('x')</script> & 中文", "UTF-8");m.saveChanges();
        String html = NoteMime.html(m);
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>"));
    }
    @Test public void plainNoteCanBeEditedAsHtml() throws Exception {
        MimeMessage m = message();m.setText("old");m.saveChanges();
        MimeMessage edited = NoteMime.copy(NoteMime.rewrite(m,"<p>new</p>"));
        assertEquals("<p>new</p>",NoteMime.html(edited));
    }
    @Test public void alternativesStayConsistent() throws Exception {
        MimeMessage edited = NoteMime.copy(NoteMime.rewrite(complex(),"<b>new text</b>"));
        Multipart mixed=(Multipart)edited.getContent();
        Multipart related=(Multipart)mixed.getBodyPart(0).getContent();
        Multipart alternative=(Multipart)related.getBodyPart(0).getContent();
        assertEquals("new text",alternative.getBodyPart(0).getContent());
        assertEquals("<b>new text</b>",alternative.getBodyPart(1).getContent());
    }
    @Test public void displayImagesReturnToCidOnSave() throws Exception {
        MimeMessage m=complex();String display=NoteMime.displayHtml(m);
        assertTrue(display.contains("data:image/png;base64,"));
        MimeMessage edited=NoteMime.copy(NoteMime.rewrite(m,display.replace("旧内容","updated")));
        assertTrue(NoteMime.html(edited).contains("cid:photo"));
        assertFalse(NoteMime.html(edited).contains("data:image"));
        assertEquals(3,NoteMime.attachments(edited).size());
    }
    @Test public void hashSurvivesSerializationButDetectsChanges() throws Exception {
        MimeMessage m=complex(); assertEquals(NoteMime.hash(m),NoteMime.hash(NoteMime.copy(m)));
        assertNotEquals(NoteMime.hash(m),NoteMime.hash(NoteMime.rewrite(m,"changed")));
    }
    @Test public void attachedMessageIsPreservedWithoutEditingItsBody() throws Exception {
        MimeMessage nested=message();nested.setText("nested original");nested.saveChanges();
        MimeBodyPart attached=new MimeBodyPart();attached.setContent(nested,"message/rfc822");
        attached.setFileName("original.eml");attached.setDisposition(Part.ATTACHMENT);
        MimeMultipart mp=new MimeMultipart();mp.addBodyPart(text("<p>outer</p>","text/html"));mp.addBodyPart(attached);
        MimeMessage m=message();m.setContent(mp);m.saveChanges();
        MimeMessage edited=NoteMime.copy(NoteMime.rewrite(m,"<p>updated</p>"));
        assertEquals("nested original",((Message)NoteMime.attachments(edited).get(0).getContent()).getContent());
    }
    @Test(expected=MessagingException.class) public void missingBodyFailsInsteadOfDroppingAttachments() throws Exception {
        MimeMultipart mp=new MimeMultipart();mp.addBodyPart(attachment("a.pdf","application/pdf",new byte[]{1}));
        MimeMessage m=message();m.setContent(mp);m.saveChanges();NoteMime.rewrite(m,"new");
    }
    @Test public void atomicWriteKeepsOldFileIfSerializationFails() throws Exception {
        File dir=java.nio.file.Files.createTempDirectory("notes-").toFile();File f=new File(dir,"1.eml");
        try {
            MimeMessage m=complex();NoteMime.writeAtomic(f,m);byte[] old=java.nio.file.Files.readAllBytes(f.toPath());
            MimeMessage failing=new MimeMessage(Session.getInstance(new Properties())) {
                @Override public void writeTo(OutputStream out) throws IOException,MessagingException {
                    out.write("partial".getBytes());throw new IOException("disk failure");
                }
            };
            try { NoteMime.writeAtomic(f,failing);fail(); } catch(IOException expected){}
            assertArrayEquals(old,java.nio.file.Files.readAllBytes(f.toPath()));
            assertEquals(1,Objects.requireNonNull(dir.list()).length);
        } finally { f.delete();dir.delete(); }
    }
}
