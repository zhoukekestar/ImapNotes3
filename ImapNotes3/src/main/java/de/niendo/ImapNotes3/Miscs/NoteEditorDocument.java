package de.niendo.ImapNotes3.Miscs;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps a single draft across editor formats, without rewriting HTML merely to preview it. */
public final class NoteEditorDocument {
    private static final String PREFIX = "imapnotes3:markdown:v1:";
    private static final Pattern METADATA = Pattern.compile("<!--" + PREFIX + "([a-f0-9]{64}):([a-f0-9]*)-->");
    private static final MutableDataSet OPTIONS = new MutableDataSet().set(Parser.EXTENSIONS,
            Arrays.asList(TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create()));
    private static final Parser PARSER = Parser.builder(OPTIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder(OPTIONS).build();
    private static final FlexmarkHtmlConverter CONVERTER = FlexmarkHtmlConverter.builder(new MutableDataSet()
            .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
            .set(FlexmarkHtmlConverter.OUTPUT_UNKNOWN_TAGS, true)
            .set(FlexmarkHtmlConverter.DIV_AS_PARAGRAPH, true)).build();
    private String html;
    private String markdown;
    private final java.util.Map<String, String> inlineImages;

    public NoteEditorDocument(String storedHtml) {
        this(storedHtml, java.util.Collections.emptyMap());
    }

    public NoteEditorDocument(String storedHtml, java.util.Map<String, String> images) {
        inlineImages = new java.util.LinkedHashMap<>(images);
        html = stripMetadata(storedHtml);
        Matcher match = METADATA.matcher(storedHtml);
        if (match.find() && match.group(1).equals(digest(fragment(html)))) {
            try { markdown = new String(unhex(match.group(2)), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    public String html() { return html; }
    public String headStyles() {
        StringBuilder css = new StringBuilder();
        for (org.jsoup.nodes.Element style : document(html).head().select("style")) css.append(style.data()).append('\n');
        return css.toString();
    }
    public String fragment() {
        Document display = document(html);
        for (org.jsoup.nodes.Element image : display.select("[src]")) {
            String cid = image.attr("src");
            String url = inlineImages.get(cid);
            if (url != null) image.attr("src", url).attr("data-imapnotes3-cid", cid);
        }
        return fragment(display.body().html());
    }
    public String markdown() {
        if (markdown == null) markdown = CONVERTER.convert(html);
        return markdown;
    }

    public String shareHtml() {
        Document exported = document(html);
        exported.body().html(fragment());
        for (org.jsoup.nodes.Element image : exported.select("[data-imapnotes3-cid]")) image.removeAttr("data-imapnotes3-cid");
        return exported.outerHtml();
    }

    public boolean setHtml(String value) {
        value = stripMetadata(value);
        if (value.equals(html)) return false;
        html = value;
        markdown = null;
        return true;
    }

    public boolean setRichHtml(String value) {
        Document storage = document(value);
        for (org.jsoup.nodes.Element image : storage.select("[data-imapnotes3-cid]")) {
            String cid = image.attr("data-imapnotes3-cid");
            if (image.attr("src").equals(inlineImages.get(cid))) image.attr("src", cid);
            image.removeAttr("data-imapnotes3-cid");
        }
        value = storage.body().html();
        if (fragment(value).equals(fragment(html))) return false;
        Document document = document(html);
        document.body().html(stripMetadata(value));
        html = document.outerHtml();
        markdown = null;
        return true;
    }

    public boolean setMarkdown(String value) {
        if (value.equals(markdown())) return false;
        markdown = value;
        html = RENDERER.render(PARSER.parse(value));
        return true;
    }

    /** Markdown remains editable verbatim; other IMAP/Apple Notes clients receive ordinary HTML. */
    public String storedHtml() {
        if (markdown == null) return html;
        Document document = document(html);
        document.body().appendChild(new Comment(PREFIX + digest(fragment(html)) + ":" +
                hex(markdown.getBytes(StandardCharsets.UTF_8))));
        return document.outerHtml();
    }

    private static String stripMetadata(String value) { return METADATA.matcher(value).replaceAll(""); }
    private static Document document(String value) {
        Document result = Jsoup.parse(value);
        result.outputSettings().prettyPrint(false);
        return result;
    }
    private static String fragment(String value) {
        return document(stripMetadata(value).replace("\r\n", "\n").replace('\r', '\n')).body().html();
    }
    private static String digest(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] data) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder output = new StringBuilder(data.length * 2);
        for (byte value : data) { output.append(alphabet[(value & 255) >>> 4]).append(alphabet[value & 15]); }
        return output.toString();
    }
    private static byte[] unhex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException();
        byte[] output = new byte[value.length() / 2];
        for (int i = 0; i < output.length; i++) {
            output[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return output;
    }
}
