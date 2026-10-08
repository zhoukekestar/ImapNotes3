package de.niendo.ImapNotes3.Sync;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Properties;
import javax.mail.*;
import javax.mail.internet.MimeMessage;
import de.niendo.ImapNotes3.Miscs.NoteMime;

public class UploadTransactionTest {
    private MimeMessage note() throws Exception {
        MimeMessage m=new MimeMessage(Session.getInstance(new Properties()));
        m.setText("note");UploadTransaction.ensureId(m);m.saveChanges();return m;
    }
    private static class Server implements UploadTransaction.Remote {
        long found=-1, acknowledgement=42;int appends,deletes;boolean lost,deleteFails;
        public long find(String id) {return found;}
        public long append(Message m) throws MessagingException {
            appends++;found=42;if(lost)throw new MessagingException("lost response");return acknowledgement;
        }
        public void retireOriginal(Message m) throws MessagingException {
            deletes++;if(deleteFails)throw new MessagingException("delete failed");
        }
    }
    @Test public void stableIdSurvivesRetries() throws Exception {
        MimeMessage m=note();String id=m.getHeader(NoteMime.UPLOAD_ID)[0];
        assertEquals(id,UploadTransaction.ensureId(m));
    }
    @Test public void lostAppendResponseDoesNotDeleteOriginalOrDuplicateOnRetry() throws Exception {
        Server s=new Server();s.lost=true;MimeMessage m=note();
        try {UploadTransaction.upload(m,s);fail();}catch(MessagingException expected){}
        assertEquals(0,s.deletes);assertEquals(1,s.appends);
        assertEquals(42,UploadTransaction.upload(m,s));assertEquals(1,s.appends);assertEquals(1,s.deletes);
    }
    @Test public void failedDeleteRetriesWithoutAnotherAppend() throws Exception {
        Server s=new Server();s.deleteFails=true;MimeMessage m=note();
        try {UploadTransaction.upload(m,s);fail();}catch(MessagingException expected){}
        s.deleteFails=false;assertEquals(42,UploadTransaction.upload(m,s));assertEquals(1,s.appends);
    }
    @Test public void serverWithoutUidplusCanConfirmBySearch() throws Exception {
        Server s=new Server();s.acknowledgement=-1;assertEquals(42,UploadTransaction.upload(note(),s));
    }
    @Test public void noAcknowledgementRetainsOriginal() throws Exception {
        Server s=new Server(){public long append(Message m){appends++;return -1;}};
        try {UploadTransaction.upload(note(),s);fail();}catch(MessagingException expected){}
        assertEquals(0,s.deletes);
    }
    @Test(expected=MessagingException.class) public void refusesUnpersistedIdentity() throws Exception {
        MimeMessage m=note();m.removeHeader(NoteMime.UPLOAD_ID);UploadTransaction.upload(m,new Server());
    }
    @Test public void differentUidNamespaceNeverRetiresOriginal() throws Exception {
        MimeMessage original=note(), edited=note();
        edited.setHeader(NoteMime.VALIDITY,"10");
        edited.setHeader(NoteMime.BASE_HASH,NoteMime.hash(original));
        assertFalse(UploadTransaction.canRetire(edited,original,11));
    }
    @Test public void changedRemoteBodyBecomesConflictCopy() throws Exception {
        MimeMessage original=note(), edited=note();
        edited.setHeader(NoteMime.VALIDITY,"10");
        edited.setHeader(NoteMime.BASE_HASH,NoteMime.hash(original));
        original.setText("remote edited");original.saveChanges();
        assertFalse(UploadTransaction.canRetire(edited,original,10));
    }
    @Test public void unchangedOriginalCanRetireAfterAcknowledgement() throws Exception {
        MimeMessage original=note(), edited=note();
        edited.setHeader(NoteMime.VALIDITY,"10");
        edited.setHeader(NoteMime.BASE_HASH,NoteMime.hash(original));
        assertTrue(UploadTransaction.canRetire(edited,original,10));
    }
    @Test public void missingBaseNeverRetiresOriginal() throws Exception {
        assertFalse(UploadTransaction.canRetire(note(),note(),10));
    }
}
