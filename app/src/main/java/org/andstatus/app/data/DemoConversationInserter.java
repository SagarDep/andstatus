/*
 * Copyright (C) 2014 yvolk (Yuri Volkov), http://yurivolkov.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app.data;

import android.text.TextUtils;

import org.andstatus.app.account.MyAccount;
import org.andstatus.app.database.table.ActivityTable;
import org.andstatus.app.database.table.NoteTable;
import org.andstatus.app.net.social.AActivity;
import org.andstatus.app.net.social.ActivityType;
import org.andstatus.app.net.social.Actor;
import org.andstatus.app.net.social.Attachment;
import org.andstatus.app.notification.NotificationEventType;
import org.andstatus.app.origin.OriginType;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.TriState;
import org.andstatus.app.util.UrlUtils;

import java.util.GregorianCalendar;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.andstatus.app.context.DemoData.demoData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DemoConversationInserter {
    private static final Map<String, Actor> actors = new ConcurrentHashMap<>();

    private int iteration = 0;
    private MyAccount ma;
    private Actor accountActor = Actor.EMPTY;
    private String bodySuffix = "";

    public static void onNewDemoData() {
        actors.clear();
    }

    public static Map<String, Actor> getActors() {
        return actors;
    }

    public void insertConversation(String bodySuffixIn) {
        bodySuffix = TextUtils.isEmpty(bodySuffixIn)
                ? ""
                : " " + bodySuffixIn;
        iteration = demoData.conversationIterationCounter.incrementAndGet();
        ma = demoData.getMyAccount(demoData.conversationAccountName);
        assertTrue(demoData.conversationAccountName + " exists", ma.isValid());
        accountActor = ma.getActor();
        insertAndTestConversation();
    }

    private void insertAndTestConversation() {
        assertEquals("Only PumpIo supported in this test", OriginType.PUMPIO, demoData.conversationOriginType);

        Actor author2 = buildActorFromOid(demoData.conversationAuthorSecondActorOid);
        author2.avatarUrl = "http://png.findicons.com/files/icons/1780/black_and_orange/300/android_orange.png";

        Actor author3 = buildActorFromOid(demoData.conversationAuthorThirdActorOid);
        author3.setRealName("John Smith");
        author3.setUsername(demoData.conversationAuthorThirdUsername);
        author3.setHomepage("http://johnsmith.com/welcome");
        author3.setCreatedDate(new GregorianCalendar(2011,5,12).getTimeInMillis());
        author3.setDescription("I am an ordinary guy, interested in computer science");
        author3.avatarUrl = "http://www.large-icons.com/stock-icons/free-large-android/48x48/happy-robot.gif";
        actors.put(author3.oid, author3);

        Actor author4 = buildActorFromOid("acct:fourthWithoutAvatar@pump.example.com");
        
        AActivity minus1 = buildActivity(author2, "Older one note", null, null);
        AActivity selected = buildActivity(getAuthor1(), "Selected note from Home timeline", minus1,
                iteration == 1 ? demoData.conversationEntryNoteOid : null);
        selected.setSubscribedByMe(TriState.TRUE);
        AActivity reply1 = buildActivity(author3, "Reply 1 to selected", selected, null);
        author3.followedByMe = TriState.TRUE;

        AActivity reply1Copy = buildActivity(accountActor,
                Actor.fromOriginAndActorOid(reply1.accountActor.origin, reply1.getAuthor().oid),
                "", AActivity.EMPTY,
                reply1.getNote().oid, DownloadStatus.UNKNOWN);
        AActivity reply12 = buildActivity(author2, "Reply 12 to 1 in Replies", reply1Copy, null);
        reply1.getNote().replies.add(reply12);

        AActivity reply2 = buildActivity(author2, "Reply 2 to selected is private", selected, null);
        addPrivateNote(reply2, TriState.TRUE);
        DemoNoteInserter.assertInteraction(reply2, NotificationEventType.PRIVATE, TriState.TRUE);
        DemoNoteInserter.assertInteraction(selected, NotificationEventType.EMPTY, TriState.FALSE);
        if (iteration == 1) {
            assertEquals("Should be subscribed " + selected, TriState.TRUE,
                    MyQuery.activityIdToTriState(ActivityTable.SUBSCRIBED, selected.getId()));
        }

        AActivity reply3 = buildActivity(getAuthor1(), "Reply 3 to selected by the same author", selected, null);
        reply3.getNote().attachments.add(Attachment
            .fromUrlAndContentType(UrlUtils.fromString(
                    "http://www.publicdomainpictures.net/pictures/100000/nahled/broadcasting-tower-14081029181fC.jpg"),
                    MyContentType.IMAGE));
        addActivity(reply3);
        addActivity(reply1);
        DemoNoteInserter.assertInteraction(reply1, NotificationEventType.EMPTY, TriState.FALSE);
        addActivity(reply2);
        AActivity reply4 = buildActivity(author4, "Reply 4 to Reply 1 other author", reply1, null);
        addActivity(reply4);

        DemoNoteInserter.increaseUpdateDate(reply4);
        addPrivateNote(reply4, TriState.FALSE);

        DemoConversationInserter.assertIfActorIsMyFriend(author3, true, ma);

        final String MENTIONS_NOTE_BODY = "@fourthWithoutAvatar@pump.example.com Reply 5 to Reply 4\n"
                + "@" + author3.getUsername()
                + " @unknownUser@example.com";
        AActivity reply5 = buildActivity(author2, MENTIONS_NOTE_BODY, reply4,
                iteration == 1 ? demoData.conversationMentionsNoteOid : null);
        addActivity(reply5);

        Actor reblogger1 = buildActorFromOid("acct:reblogger@" + demoData.pumpioMainHost);
        reblogger1.avatarUrl = "http://www.avatarsdb.com/avatars/cow_face.jpg";
        AActivity reblogOf5 = buildActivity(reblogger1, ActivityType.ANNOUNCE);
        reblogOf5.setNote(reply5.getNote().shallowCopy());
        reblogOf5.setSubscribedByMe(TriState.TRUE);
        addActivity(reblogOf5);

        final AActivity reply6 = buildActivity(author3, "Reply 6 to Reply 4 - the second", reply4, null);
        reply6.getNote().addFavoriteBy(accountActor, TriState.TRUE);
        addActivity(reply6);

        AActivity likeOf6 = buildActivity(author2, ActivityType.LIKE);
        likeOf6.setNote(reply6.getNote().shallowCopy());
        addActivity(likeOf6);

        AActivity reply7 = buildActivity(getAuthor1(), "Reply 7 to Reply 2 is about "
        + demoData.publicNoteText + " and something else", reply2, null);
        addPrivateNote(reply7, TriState.FALSE);
        
        AActivity reply8 = buildActivity(author4, "<b>Reply 8</b> to Reply 7", reply7, null);
        AActivity reblogOfNewActivity8 = buildActivity(author3, ActivityType.ANNOUNCE);
        reblogOfNewActivity8.setActivity(reply8);
        addActivity(reblogOfNewActivity8);

        AActivity reply9 = buildActivity(author2, "Reply 9 to Reply 7", reply7, null);
        reply9.setSubscribedByMe(TriState.TRUE);
        reply9.getNote().attachments
                .add(Attachment
                        .fromUrlAndContentType( UrlUtils.fromString(
                                "http://www.publicdomainpictures.net/pictures/100000/nahled/autumn-tree-in-a-park.jpg"),
                                MyContentType.IMAGE));
        addActivity(reply9);
        final AActivity duplicateOfReply9 = buildActivity(author4, "A duplicate of " + reply9.getNote().getBody(),
                null, null);
        duplicateOfReply9.setSubscribedByMe(TriState.TRUE);
        addActivity(duplicateOfReply9);

        AActivity myLikeOf9 =  AActivity.from(accountActor, ActivityType.LIKE) ;
        myLikeOf9.setActor(accountActor);
        myLikeOf9.setNote(reply9.getNote().shallowCopy());
        addActivity(myLikeOf9);

        // Note downloaded by another account
        final MyAccount ma2 = demoData.getMyAccount(demoData.conversationAccount2Name);
        author3.followedByMe = TriState.TRUE;
        AActivity reply10 = buildActivity(ma2.getActor(), author3, "Reply 10 to Reply 8", reply8,
                null, DownloadStatus.LOADED);
        assertEquals("The third is a note Author", author3,  reply10.getAuthor());
        addActivity(reply10);
        author3.followedByMe = TriState.UNKNOWN;

        DemoConversationInserter.assertIfActorIsMyFriend(author3, true, ma2);

        AActivity anonymousReply = buildActivity(Actor.EMPTY, "Anonymous reply to Reply 10", reply10, null);
        addActivity(anonymousReply);

        AActivity reply11 = buildActivity(author2, "Reply 11 to Reply 7, " + demoData.globalPublicNoteText
                + " text", reply7, null);
        addPrivateNote(reply11, TriState.FALSE);
        DemoNoteInserter.assertInteraction(reply11, NotificationEventType.EMPTY, TriState.FALSE);

        AActivity myReply13 = buildActivity(accountActor, "My reply to Reply 2", reply2, null);
        AActivity reply14 = buildActivity(author3, "Reply to my note 13", myReply13, null);
        addActivity(reply14);
        DemoNoteInserter.assertInteraction(reply14, NotificationEventType.MENTION, TriState.TRUE);

        AActivity reblogOf14 = buildActivity(author2, ActivityType.ANNOUNCE);
        reblogOf14.setActivity(reply14);
        addActivity(reblogOf14);
        DemoNoteInserter.assertInteraction(reblogOf14, NotificationEventType.MENTION, TriState.TRUE);

        AActivity reblogOfMy13 = buildActivity(author3, ActivityType.ANNOUNCE);
        reblogOfMy13.setActivity(myReply13);
        addActivity(reblogOfMy13);
        DemoNoteInserter.assertInteraction(reblogOfMy13, NotificationEventType.ANNOUNCE, TriState.TRUE);

        AActivity mentionOfAuthor3 = buildActivity(reblogger1, "@" + author3.getUsername() + " mention in reply to 4",
                reply4, iteration == 1 ? demoData.conversationMentionOfAuthor3Oid : null);
        addActivity(mentionOfAuthor3);

        AActivity followOf3 = buildActivity(author2, ActivityType.FOLLOW);
        followOf3.setObjActor(author3);
        addActivity(followOf3);
        DemoNoteInserter.assertInteraction(followOf3, NotificationEventType.EMPTY, TriState.FALSE);

        AActivity notLoaded1 = AActivity.newPartialNote(accountActor, MyLog.uniqueDateTimeFormatted());
        Actor notLoadedActor = Actor.fromOriginAndActorOid(accountActor.origin, "acct:notloaded@someother.host"
        + demoData.testOriginParentHost);
        notLoaded1.setActor(notLoadedActor);
        AActivity reply15 = buildActivity(author4, "Reply 15 to not loaded 1", notLoaded1, null);
        addActivity(reply15);

        AActivity followOfMe = buildActivity(getAuthor1(), ActivityType.FOLLOW);
        followOfMe.setObjActor(accountActor);
        addActivity(followOfMe);
        DemoNoteInserter.assertInteraction(followOfMe, NotificationEventType.FOLLOW, TriState.TRUE);

        AActivity reply16 = buildActivity(author2, "Reply 16 to Reply 15", reply15, null);
        addActivity(reply16);
    }

    private Actor getAuthor1() {
        Actor author1 = buildActorFromOid(demoData.conversationEntryAuthorOid);
        author1.avatarUrl = "https://raw.github.com/andstatus/andstatus/master/app/src/main/res/drawable/splash_logo.png";
        return author1;
    }
    
    private void addPrivateNote(AActivity activity, TriState isPrivate) {
        activity.getNote().setPrivate(isPrivate);
        addActivity(activity);
        TriState storedPrivate = MyQuery.noteIdToTriState(NoteTable.PRIVATE, activity.getNote().noteId);
        assertEquals("Note is " + (isPrivate.equals(TriState.TRUE) ? "private" :
                        isPrivate.equals(TriState.FALSE) ? "non private" : "") + ": " + activity.getNote().getBody(),
                isPrivate, storedPrivate);
    }

    private Actor buildActorFromOid(String actorOid) {
        return new DemoNoteInserter(accountActor).buildActorFromOid(actorOid);
    }

    private AActivity buildActivity(Actor actor, ActivityType type) {
        return new DemoNoteInserter(accountActor).buildActivity(actor, type, "");
    }

    private AActivity buildActivity(Actor author, String body, AActivity inReplyTo, String noteOidIn) {
        return buildActivity(accountActor, author, body, inReplyTo, noteOidIn, DownloadStatus.LOADED);
    }

    private AActivity buildActivity(Actor accountActor, Actor author, String body, AActivity inReplyTo,
                                    String noteOidIn, DownloadStatus status) {
        return new DemoNoteInserter(accountActor).buildActivity(author, body
                        + (inReplyTo != null ? " it" + iteration : "") + bodySuffix,
                inReplyTo, noteOidIn, status);
    }

    private void addActivity(AActivity activity) {
        DemoNoteInserter.onActivityS(activity);
    }

    static void assertIfActorIsMyFriend(Actor actor, boolean isFriendOf, MyAccount ma) {
        Set<Long> friendsIds = MyQuery.getFriendsIds(ma.getActorId());
        assertEquals("Actor " + actor + " is a friend of " + ma, isFriendOf, friendsIds.contains(actor.actorId));
    }
}
