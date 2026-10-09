package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.protocol.BASE64MailboxEncoder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.mail.Session;
import javax.mail.MessagingException;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotesFolderTest {
    private static final String QQ_NOTES = "Drafts";

    @Test public void usesWritableQQDraftsInsteadOfACustomFolder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mailboxes.put("Drafts", "\\HasNoChildren");
            NotesFolder.Prepared result = NotesFolder.prepare(fixture.store, "imap.qq.com", "Notes");
            assertEquals(QQ_NOTES, result.folder.getFullName());
            assertFalse(result.created);
            assertTrue(fixture.creates.isEmpty());
            assertTrue(fixture.subscriptions.isEmpty());
        }
    }

    @Test public void reusesExistingQQNotesWithoutCreatingDuplicates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mailboxes.put("其他文件夹", "\\NoSelect \\HasChildren");
            fixture.mailboxes.put(QQ_NOTES, "\\HasNoChildren");
            assertEquals(QQ_NOTES, NotesFolder.prepare(fixture.store, "imap.qq.com", "Notes").folder.getFullName());
            assertTrue(fixture.creates.isEmpty());
            assertFalse(NotesFolder.prepare(fixture.store, "imap.qq.com", QQ_NOTES).created);
            assertEquals(QQ_NOTES, NotesFolder.prepare(fixture.store, "imap.qq.com", "其他文件夹/Notes").folder.getFullName());
        }
    }

    @Test public void preservesExistingTopLevelNotes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mailboxes.put("Notes", "\\HasNoChildren");
            assertEquals("Notes", NotesFolder.prepare(fixture.store, "imap.example", "Notes").folder.getFullName());
            assertTrue(fixture.creates.isEmpty());
        }
    }

    @Test public void preservesExplicitQQFolderAndOtherProviders() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mailboxes.put("其他文件夹", "\\NoSelect \\HasChildren");
            assertEquals("Custom", NotesFolder.prepare(fixture.store, "imap.qq.com", "Custom").folder.getFullName());
            assertEquals("Notes", NotesFolder.prepare(fixture.store, "imap.163.com", "Notes").folder.getFullName());
            assertEquals(Arrays.asList("Custom", "Notes"), fixture.creates);
        }
    }

    @Test public void savesFullNamespacePathWithoutDuplicatingPrefix() throws Exception {
        try (Fixture fixture = new Fixture("INBOX/")) {
            String full = NotesFolder.prepare(fixture.store, "imap.example", "Notes").folder.getFullName();
            assertEquals("INBOX/Notes", full);
            assertEquals(full, NotesFolder.prepare(fixture.store, "imap.example", full).folder.getFullName());
            assertEquals(Collections.singletonList(full), fixture.creates);
        }
    }

    @Test public void preservesCreationFailureExplanation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rejectCreate = true;
            try {
                NotesFolder.prepare(fixture.store, "imap.example", "Notes");
                fail("Expected server rejection");
            } catch (MessagingException error) {
                assertTrue(error.getMessage().contains("[CANNOT] Folder quota exceeded"));
            }
        }
    }

    @Test public void doesNotTreatFalseCreateSuccessAsAUsableFolder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ignoreCreate = true;
            try {
                NotesFolder.prepare(fixture.store, "imap.qq.com", "Notes");
                fail("Expected missing LIST entry");
            } catch (MessagingException error) {
                assertTrue(error.getMessage().contains("missing from LIST"));
            }
            assertTrue(fixture.subscriptions.isEmpty());
        }
    }

    @Test public void rejectsAContainerWithoutMessages() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mailboxes.put("Notes", "\\NoSelect \\HasChildren");
            try {
                NotesFolder.prepare(fixture.store, "imap.example", "Notes");
                fail("Must not select a container as a notes mailbox");
            } catch (MessagingException error) {
                assertTrue(error.getMessage().contains("cannot hold messages"));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, String> mailboxes = new ConcurrentHashMap<>();
        final List<String> creates = new CopyOnWriteArrayList<>();
        final List<String> subscriptions = new CopyOnWriteArrayList<>();
        final ServerSocket server;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Future<?> conversation;
        final IMAPStore store;
        volatile boolean rejectCreate, ignoreCreate;
        Fixture() throws Exception { this(""); }
        Fixture(String namespace) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            conversation = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                    output.print("* OK [CAPABILITY IMAP4rev1 NAMESPACE] Fixture ready\r\n"); output.flush();
                    for (String line; (line = input.readLine()) != null;) {
                        String[] fields = line.split(" ", 3);
                        String tag = fields[0], command = fields[1], response = "OK Completed";
                        String name = fields.length == 3 ? unquote(fields[2]) : "";
                        switch (command) {
                            case "CAPABILITY": output.print("* CAPABILITY IMAP4rev1 NAMESPACE\r\n"); break;
                            case "LOGIN": break;
                            case "NAMESPACE": output.print("* NAMESPACE ((\"" + namespace + "\" \"/\")) NIL NIL\r\n"); break;
                            case "LIST":
                                name = unquote(fields[2].substring(fields[2].indexOf(' ') + 1));
                                for (Map.Entry<String,String> mailbox : mailboxes.entrySet()) {
                                    String encoded = BASE64MailboxEncoder.encode(mailbox.getKey());
                                    if (encoded.equals(name)) output.print("* LIST (" + mailbox.getValue() + ") \"/\" \"" + encoded + "\"\r\n");
                                }
                                break;
                            case "CREATE":
                                name = com.sun.mail.imap.protocol.BASE64MailboxDecoder.decode(name);
                                creates.add(name);
                                if (rejectCreate) response = "NO [CANNOT] Folder quota exceeded";
                                else if (!ignoreCreate) mailboxes.put(name, "\\HasNoChildren");
                                break;
                            case "SUBSCRIBE": subscriptions.add(com.sun.mail.imap.protocol.BASE64MailboxDecoder.decode(name)); break;
                            case "LOGOUT": output.print("* BYE Logout\r\n" + tag + " OK Logout\r\n"); output.flush(); return;
                            default: throw new AssertionError("Unexpected command: " + command);
                        }
                        output.print(tag + " " + response + "\r\n"); output.flush();
                    }
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            Properties properties = new Properties();
            properties.setProperty("mail.imap.timeout", "5000");
            store = (IMAPStore) Session.getInstance(properties).getStore("imap");
            store.connect("127.0.0.1", server.getLocalPort(), "fixture", "fixture-password");
        }
        private static String unquote(String value) {
            return value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length()-1) : value;
        }
        @Override public void close() throws Exception {
            try { store.close(); conversation.get(5, TimeUnit.SECONDS); }
            finally { server.close(); worker.shutdownNow(); }
        }
    }
}
