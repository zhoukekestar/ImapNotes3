package de.niendo.ImapNotes3.Miscs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** MIME editing independent of Android, preserving attachment bytes and content IDs. */
public final class NoteMime {
    public static final String UPLOAD_ID = "X-ImapNotes3-Upload-ID";
    public static final String REPLACES = "X-ImapNotes3-Replaces-UID";
    public static final String VALIDITY = "X-ImapNotes3-UIDValidity";
    public static final String BASE_HASH = "X-ImapNotes3-Base-Hash";
    private NoteMime() {}

    private static boolean attachment(Part part) throws MessagingException {
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
    }

    private static Part body(Part part, boolean html) throws MessagingException, IOException {
        if (attachment(part)) return null;
        if (part.isMimeType(html ? "text/html" : "text/plain")) return part;
        if (!part.isMimeType("multipart/*")) return null;
        Multipart mp = (Multipart) part.getContent();
        for (int i = mp.getCount() - 1; i >= 0; i--) {
            Part found = body(mp.getBodyPart(i), html);
            if (found != null) return found;
        }
        return null;
    }

    public static String html(Part original) throws MessagingException, IOException {
        Part p = body(original, true);
        if (p != null) return (String) p.getContent();
        p = body(original, false);
        if (p == null) throw new MessagingException("No editable note body");
        Document doc = Jsoup.parse("<html><body><pre></pre></body></html>");
        doc.selectFirst("pre").text((String) p.getContent());
        return doc.outerHtml();
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
