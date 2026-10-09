package de.niendo.ImapNotes3.Miscs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;
import javax.activation.CommandMap;
import javax.activation.MailcapCommandMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** MIME editing independent of Android, preserving attachment bytes and content IDs. */
public final class NoteMime {
    public static final String UPLOAD_ID = "X-ImapNotes3-Upload-ID";
    public static final String REPLACES = "X-ImapNotes3-Replaces-UID";
    public static final String VALIDITY = "X-ImapNotes3-UIDValidity";
    public static final String BASE_HASH = "X-ImapNotes3-Base-Hash";
    private NoteMime() {}

    /** Android's activation lookup may miss the handlers packaged inside the mail library. */
    public static void configureHandlers() {
        CommandMap current = CommandMap.getDefaultCommandMap();
        MailcapCommandMap map = current instanceof MailcapCommandMap ?
                (MailcapCommandMap) current : new MailcapCommandMap();
        map.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
        map.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
        map.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
        map.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
        CommandMap.setDefaultCommandMap(map);
    }

    private static String text(Part part) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof String) return (String) content;
        if (!(content instanceof InputStream)) throw new MessagingException("Unsupported text content");
        String charset = new ContentType(part.getContentType()).getParameter("charset");
        if (charset == null) charset = "US-ASCII";
        try (Reader reader = new InputStreamReader((InputStream) content, MimeUtility.javaCharset(charset))) {
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) value.append(buffer, 0, count);
            return value.toString();
        }
    }

    private static boolean attachment(Part part) throws MessagingException {
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
    }

    private static boolean mimeType(Part part, String expected) throws MessagingException {
        String type = part.getContentType();
        // setContent/setText do not create MIME headers until saveChanges. The handler already
        // knows the type, so an unsaved HTML draft must not be mistaken for plain text.
        if (part.getHeader("Content-Type") == null && part.getDataHandler() != null)
            type = part.getDataHandler().getContentType();
        return new ContentType(type).match(expected);
    }

    private static Part body(Part part, boolean html) throws MessagingException, IOException {
        if (attachment(part)) return null;
        if (mimeType(part, html ? "text/html" : "text/plain")) return part;
        if (!mimeType(part, "multipart/*")) return null;
        Multipart mp = (Multipart) part.getContent();
        for (int i = mp.getCount() - 1; i >= 0; i--) {
            Part found = body(mp.getBodyPart(i), html);
            if (found != null) return found;
        }
        return null;
    }

    public static String html(Part original) throws MessagingException, IOException {
        Part p = body(original, true);
        if (p != null) return text(p);
        p = body(original, false);
        if (p == null) throw new MessagingException("No editable note body");
        Document doc = Jsoup.parse("<html><body><pre></pre></body></html>");
        doc.selectFirst("pre").text(text(p));
        return doc.outerHtml();
    }

    /** RFC 2047 subjects are already decoded by JavaMail. Repair only valid unencoded UTF-8. */
    public static String subject(Message message) throws MessagingException {
        String decoded = message.getSubject();
        if (decoded == null) return null;
        String[] raw = message.getHeader("Subject");
        if (raw == null || raw.length == 0 || raw[0].contains("=?")) return decoded;
        for (int i = 0; i < decoded.length(); i++) if (decoded.charAt(i) > 255) return decoded;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(decoded.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) { return decoded; }
    }

    public static MimeMessage copy(Message original) throws MessagingException, IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        original.writeTo(bytes);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes.toByteArray()));
    }

    public static MimeMessage rewrite(Message original, String html) throws MessagingException, IOException {
        MimeMessage edited = copy(original);
        // Undo display-only data URLs; the original CID and its MIME attachment survive.
        for (Part p : attachments(original)) {
            String[] ids = p.getHeader("Content-ID");
            if (ids != null && p.isMimeType("image/*")) {
                String id = ids[0].replace("<", "").replace(">", "");
                html = html.replace(dataUrl(p), "cid:" + id);
            }
        }
        Part chosen = body(edited, true);
        if (chosen != null) chosen.setContent(html, "text/html; charset=UTF-8");
        else {
            chosen = body(edited, false);
            if (chosen == null) throw new MessagingException("No editable note body");
            chosen.setContent(html, "text/html; charset=UTF-8");
        }
        chosen.setHeader("Content-Type", "text/html; charset=UTF-8");
        refreshAlternatives(edited, html);
        edited.removeHeader(UPLOAD_ID);
        edited.removeHeader(REPLACES);
        edited.removeHeader(VALIDITY);
        edited.removeHeader(BASE_HASH);
        edited.saveChanges();
        return edited;
    }

    private static void refreshAlternatives(Part part, String html) throws MessagingException, IOException {
        if (attachment(part) || !part.isMimeType("multipart/*")) return;
        Multipart mp = (Multipart) part.getContent();
        boolean alternative = part.isMimeType("multipart/alternative");
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart child = mp.getBodyPart(i);
            if (alternative && !attachment(child)) {
                if (child.isMimeType("text/plain")) child.setContent(Jsoup.parse(html).text(), "text/plain; charset=UTF-8");
                else if (child.isMimeType("text/html")) child.setContent(html, "text/html; charset=UTF-8");
            }
            refreshAlternatives(child, html);
        }
        // Mark parent content modified so JavaMail serializes the edited body parts.
        part.setContent(mp);
    }

    public static List<Part> attachments(Part part) throws MessagingException, IOException {
        List<Part> result = new ArrayList<>();
        collect(part, result);
        return result;
    }
    private static void collect(Part part, List<Part> result) throws MessagingException, IOException {
        if (attachment(part) || part.getHeader("Content-ID") != null) {
            result.add(part);
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), result);
        }
    }

    private static String dataUrl(Part p) throws MessagingException, IOException {
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return "data:" + new ContentType(p.getContentType()).getBaseType() + ";base64," +
                    new String(com.sun.mail.util.BASE64EncoderStream.encode(out.toByteArray()), StandardCharsets.US_ASCII);
        }
    }
    public static String displayHtml(Message original) throws MessagingException, IOException {
        String html = html(original);
        for (Part p : attachments(original)) {
            String[] ids = p.getHeader("Content-ID");
            if (ids != null && p.isMimeType("image/*"))
                html = html.replace("cid:" + ids[0].replace("<", "").replace(">", ""), dataUrl(p));
        }
        return html;
    }

    public static String hash(Message original) throws MessagingException, IOException {
        // Compare the canonical RFC message, rather than a server's BODY[TEXT] projection.
        // QQ adds a CRLF to that projection which is absent from the downloaded MIME.
        if (original instanceof com.sun.mail.imap.IMAPMessage) original = copy(original);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(html(original).getBytes(StandardCharsets.UTF_8));
            for (Part p : attachments(original)) {
                digest.update(p.getContentType().getBytes(StandardCharsets.UTF_8));
                digest.update(Objects.toString(p.getFileName(), "").getBytes(StandardCharsets.UTF_8));
                String[] cid = p.getHeader("Content-ID");
                digest.update(Arrays.toString(cid).getBytes(StandardCharsets.UTF_8));
                try (InputStream in = p.getInputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void writeAtomic(File destination, Message message) throws IOException, MessagingException {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create note directory");
        File temporary = File.createTempFile("note-", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                message.writeTo(out);
                out.getFD().sync();
            }
            if (!temporary.renameTo(destination)) throw new IOException("Cannot replace note file");
        } finally { temporary.delete(); }
    }
}
