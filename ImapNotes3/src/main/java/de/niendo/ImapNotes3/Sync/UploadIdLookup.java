package de.niendo.ImapNotes3.Sync;

import com.sun.mail.imap.IMAPFolder;
import de.niendo.ImapNotes3.Miscs.NoteMime;
import javax.mail.*;
import javax.mail.search.HeaderTerm;

/** Locate an acknowledged upload even when the server does not index custom headers. */
final class UploadIdLookup {
    private UploadIdLookup() {}

    static long find(IMAPFolder folder, String operationId, boolean notesOnly) throws MessagingException {
        Message[] candidates = folder.search(new HeaderTerm(NoteMime.UPLOAD_ID, operationId));
        long found = match(folder, candidates, operationId, notesOnly);
        if (found > 0) return found;
        // NetEase returns an empty SEARCH for a present custom header. Read only metadata;
        // never download message bodies while looking for the persisted operation ID.
        return match(folder, folder.getMessages(), operationId, notesOnly);
    }

    private static long match(IMAPFolder folder, Message[] candidates, String operationId,
                              boolean notesOnly) throws MessagingException {
        if (candidates.length == 0) return -1;
        FetchProfile profile = new FetchProfile();
        profile.add(UIDFolder.FetchProfileItem.UID);
        profile.add(FetchProfile.Item.FLAGS);
        profile.add(NoteMime.UPLOAD_ID);
        if (notesOnly) profile.add("X-Uniform-Type-Identifier");
        folder.fetch(candidates, profile);
        long found = -1;
        for (Message candidate : candidates) {
            if (candidate.isExpunged() || candidate.isSet(Flags.Flag.DELETED)) continue;
            String[] ids = candidate.getHeader(NoteMime.UPLOAD_ID);
            if ((!notesOnly || QQNotesScope.isNote(candidate)) && ids != null &&
                    operationId.equals(ids[0])) found = Math.max(found, folder.getUID(candidate));
        }
        return found;
    }
}
