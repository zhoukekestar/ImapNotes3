package de.niendo.ImapNotes3.Miscs;

import org.junit.Test;
import org.jsoup.Jsoup;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import static org.junit.Assert.*;

public class NoteEditorDocumentTest {
    @Test public void readingAndSwitchingFormatsDoNotRewriteExistingHtml() {
        String html = "<!doctype html><html><head><style>p{color:red}</style></head><body>" +
                "<div style='font-family:serif'><b>原始笔记</b><br><img src='cid:photo'></div><!--ordinary--></body></html>";
        NoteEditorDocument note = new NoteEditorDocument(html);
        note.markdown();
        assertFalse(note.setRichHtml(note.fragment()));
        assertFalse(note.setHtml(html));
        assertEquals(html, note.html());
        assertTrue(note.html().contains("<style>p{color:red}</style>"));
    }

    @Test public void markdownRendersCommonSyntaxAndRetainsVerbatimSourceInMime() throws Exception {
        String source = "# 中文标题\n\n**粗体** 与 ~~删除线~~\n\n- [x] 完成\n- [ ] 待办\n\n" +
                "| 名称 | 状态 |\n| --- | --- |\n| 同步 | 正常 |\n\n```java\nString s = \"<tag>\\n\";\n```\n\n[链接](https://example.com)\n";
        NoteEditorDocument note = new NoteEditorDocument("");
        assertTrue(note.setMarkdown(source));
        assertTrue(note.fragment().contains("<h1>中文标题</h1>"));
        assertTrue(note.fragment().contains("<strong>粗体</strong>"));
        assertTrue(note.fragment().contains("<del>删除线</del>"));
        assertTrue(note.fragment().contains("type=\"checkbox\""));
        assertTrue(note.fragment().contains("<table>"));
        assertTrue(note.fragment().contains("&lt;tag&gt;"));
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        mime.setText(note.storedHtml(), "UTF-8", "html");
        mime.saveChanges();
        assertEquals(source, new NoteEditorDocument(NoteMime.html(NoteMime.copy(mime))).markdown());
    }

    @Test public void metadataSurvivesHtmlNormalizationAndBackgroundColors() {
        String source = "## 标题\n\n正文  \n第二行\n\n![照片](cid:photo)\n";
        NoteEditorDocument note = new NoteEditorDocument("");
        note.setMarkdown(source);
        org.jsoup.nodes.Document normalized = Jsoup.parse(note.storedHtml());
        normalized.outputSettings().prettyPrint(false);
        normalized.body().attr("style", "background-color:yellow;");
        assertEquals(source, new NoteEditorDocument(normalized.outerHtml()).markdown());
    }

    @Test public void editsFromAnotherClientInvalidateStaleMarkdown() {
        NoteEditorDocument note = new NoteEditorDocument("");
        note.setMarkdown("# Original\n\nOld text\n");
        NoteEditorDocument received = new NoteEditorDocument(note.storedHtml().replace("Old text", "Changed in Apple Notes"));
        assertTrue(received.markdown().contains("Changed in Apple Notes"));
        assertFalse(received.markdown().contains("Old text"));
    }

    @Test public void richTextChangesKeepDocumentHeadAndInvalidateOldMarkdown() {
        NoteEditorDocument note = new NoteEditorDocument("<html><head><style>p{color:red}</style></head><body><p>Old</p></body></html>");
        note.markdown();
        assertTrue(note.setRichHtml("<p>New <b>bold</b></p>"));
        assertTrue(note.html().contains("<style>p{color:red}</style>"));
        assertTrue(note.markdown().contains("New **bold**"));
    }

    @Test public void emptyUnicodeAndCommentLikeMarkdownRoundTripWithoutEscapingLoss() {
        for (String source : new String[]{"", "中文 😀 + % & \\\n<!-- literal -->\n", "```html\n</body> <!-- -->\n```\n"}) {
            NoteEditorDocument note = new NoteEditorDocument("<p>Before</p>");
            note.setMarkdown(source);
            assertEquals(source, new NoteEditorDocument(note.storedHtml()).markdown());
        }
    }

    @Test public void htmlSourceSupportsTablesAndInlineAttachments() {
        NoteEditorDocument note = new NoteEditorDocument("");
        String html = "<h2>源码</h2><table><tr><td>A &amp; B</td></tr></table><img src='cid:image'>";
        assertTrue(note.setHtml(html));
        assertEquals(html, note.html());
        assertTrue(note.markdown().contains("cid:image"));
        assertTrue(note.fragment().contains("<table>"));
    }

    @Test public void displayingInlineImagesDoesNotRewriteCidOrDiscardMarkdown() {
        String source = "# Image\n\n![photo](cid:photo)\n";
        NoteEditorDocument original = new NoteEditorDocument("");
        original.setMarkdown(source);
        NoteEditorDocument opened = new NoteEditorDocument(original.storedHtml(),
                java.util.Collections.singletonMap("cid:photo", "data:image/png;base64,AA=="));
        assertTrue(opened.fragment().contains("data:image/png;base64,AA=="));
        assertFalse(opened.setRichHtml(opened.fragment()));
        assertEquals(source, opened.markdown());
        assertTrue(opened.storedHtml().contains("src=\"cid:photo\""));
        assertFalse(opened.storedHtml().contains("data:image"));
        assertTrue(opened.shareHtml().contains("src=\"data:image/png;base64,AA==\""));
        assertFalse(opened.shareHtml().contains("data-imapnotes3-cid"));
    }

    @Test public void equalImagesAndCidPrefixesDoNotChangeTextOrAttachmentIdentity() {
        String html = "<p>literal cid:photo</p><img src='cid:photo'><img src='cid:photo-two'>";
        java.util.Map<String, String> images = new java.util.LinkedHashMap<>();
        images.put("cid:photo", "data:image/png;base64,AA==");
        images.put("cid:photo-two", "data:image/png;base64,AA==");
        NoteEditorDocument note = new NoteEditorDocument(html, images);
        assertTrue(note.fragment().contains("literal cid:photo"));
        assertFalse(note.setRichHtml(note.fragment()));
        assertEquals(html, note.html());
        note.setRichHtml(note.fragment().replace("literal", "edited"));
        assertTrue(note.html().contains("src=\"cid:photo-two\""));
        assertTrue(note.html().contains("src=\"cid:photo\""));
        assertFalse(note.html().contains("data-imapnotes3-cid"));
    }
}
