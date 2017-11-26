/*
 * Copyright (C) 2015 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.timeline;

import android.app.LoaderManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import org.andstatus.app.IntentExtra;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.context.MyContext;
import org.andstatus.app.data.MatchedUri;
import org.andstatus.app.data.ParsedUri;
import org.andstatus.app.data.TimelineSql;
import org.andstatus.app.database.table.ActivityTable;
import org.andstatus.app.timeline.meta.Timeline;
import org.andstatus.app.timeline.meta.TimelineTitle;
import org.andstatus.app.timeline.meta.TimelineType;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SelectionAndArgs;

import java.util.Date;
import java.util.Set;

public class TimelineParameters {
    private final MyContext myContext;

    LoaderManager.LoaderCallbacks<Cursor> mLoaderCallbacks = null;

    /**
     * Msg are being loaded into the list starting from one page. More Msg
     * are being loaded in a case User scrolls down to the end of list.
     */
    static final int PAGE_SIZE = 200;
    Timeline timeline = Timeline.EMPTY;

    WhichPage whichPage = WhichPage.EMPTY;
    private Set<String> mProjection;

    long maxSentDate = 0;

    // These params are updated just before page loading
    volatile long minSentDate = 0;
    volatile SelectionAndArgs selectionAndArgs = new SelectionAndArgs();
    volatile String sortOrderAndLimit = "";

    // Execution state / loaded data:
    volatile boolean isLoaded = false;
    volatile boolean cancelled = false;
    volatile int rowsLoaded = 0;
    volatile long minSentDateLoaded = 0;
    volatile long maxSentDateLoaded = 0;

    public static TimelineParameters clone(@NonNull TimelineParameters prev, WhichPage whichPage) {
        TimelineParameters params = new TimelineParameters(prev.myContext);
        params.whichPage = whichPage == WhichPage.ANY ? prev.whichPage : whichPage;
        if (whichPage != WhichPage.EMPTY) {
            enrichNonEmptyParameters(params, prev);
        }
        return params;
    }

    private static void enrichNonEmptyParameters(TimelineParameters params, TimelineParameters prev) {
        params.mLoaderCallbacks = prev.mLoaderCallbacks;
        params.timeline = prev.getTimeline();

        String msgLog = "Constructing " + params.toSummary();
        switch (params.whichPage) {
            case OLDER:
                if (prev.mayHaveOlderPage()) {
                    params.maxSentDate = prev.minSentDateLoaded;
                } else {
                    params.maxSentDate = prev.maxSentDate;
                }
                break;
            case YOUNGER:
                if (prev.mayHaveYoungerPage()) {
                    params.minSentDate = prev.maxSentDateLoaded;
                } else {
                    params.minSentDate = prev.minSentDate;
                }
                break;
            default:
                break;
        }
        MyLog.v(TimelineParameters.class, msgLog);

        params.mProjection = ViewItemType.fromTimelineType(params.timeline.getTimelineType())
                .equals(ViewItemType.ACTIVITY)
                ? TimelineSql.getActivityProjection()
                : TimelineSql.getTimelineProjection();
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    public boolean mayHaveYoungerPage() {
        return maxSentDate > 0 ||
                (minSentDate > 0 && rowsLoaded > 0 && minSentDate < maxSentDateLoaded);
    }

    public boolean mayHaveOlderPage() {
        return whichPage.equals(WhichPage.CURRENT) ||
                minSentDate > 0 ||
                (maxSentDate > 0 && rowsLoaded > 0 && maxSentDate > minSentDateLoaded);
    }

    public boolean isSortOrderAscending() {
        return maxSentDate == 0 && minSentDate > 0;
    }

    public TimelineParameters(MyContext myContext) {
        this.myContext = myContext;
    }

    public boolean isEmpty() {
        return timeline.isEmpty() || whichPage == WhichPage.EMPTY;
    }

    public boolean isAtHome() {
        return  timeline.equals(myContext.persistentTimelines().getDefault());
    }

    @Override
    public String toString() {
        return MyLog.formatKeyValue(this,
                toSummary()
                + ", account=" + timeline.getMyAccount().getAccountName()
                + (timeline.getUserId() == 0 ? "" : ", selectedUserId=" + timeline.getUserId())
            //    + ", projection=" + Arrays.toString(mProjection)
                + (minSentDate > 0 ? ", minSentDate=" + new Date(minSentDate).toString() : "")
                + (maxSentDate > 0 ? ", maxSentDate=" + new Date(maxSentDate).toString() : "")
                + (selectionAndArgs.isEmpty() ? "" : ", sa=" + selectionAndArgs)
                + (TextUtils.isEmpty(sortOrderAndLimit) ? "" : ", sortOrder=" + sortOrderAndLimit)
                + (isLoaded  ? ", loaded" : "")
                + (cancelled ? ", cancelled" : "")
                + (mLoaderCallbacks == null ? "" : ", loaderCallbacks=" + mLoaderCallbacks)
        );
    }

    public Timeline getTimeline() {
        return timeline;
    }

    public TimelineParameters setTimeline(Timeline timeline) {
        this.timeline = timeline;
        return this;
    }

    public TimelineType getTimelineType() {
        return timeline.getTimelineType();
    }

    public long getSelectedUserId() {
        return timeline.getUserId();
    }

    public boolean isTimelineCombined() {
        return timeline.isCombined();
    }

    public void saveState(Bundle outState) {
        outState.putString(IntentExtra.MATCHED_URI.key, MatchedUri.getTimelineUri(timeline).toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TimelineParameters that = (TimelineParameters) o;

        if (!timeline.equals(that.timeline)) return false;
        if (!whichPage.equals(WhichPage.CURRENT) && !that.whichPage.equals(WhichPage.CURRENT)) {
            if (minSentDate != that.minSentDate) return false;
        }
        return maxSentDate == that.maxSentDate;
    }

    @Override
    public int hashCode() {
        int result = timeline.hashCode();
        if (whichPage.equals(WhichPage.CURRENT)) {
            result = 31 * result + (-1 ^ (-1 >>> 32));
        } else {
            result = 31 * result + (int) (minSentDate ^ (minSentDate >>> 32));
        }
        result = 31 * result + (int) (maxSentDate ^ (maxSentDate >>> 32));
        return result;
    }

    boolean restoreState(@NonNull Bundle savedInstanceState) {
        whichPage = WhichPage.CURRENT;
        minSentDate = 0;
        maxSentDate = 0;
        return parseUri(Uri.parse(savedInstanceState.getString(IntentExtra.MATCHED_URI.key,"")), "");
    }
    
    /** @return true if parsed successfully */
    boolean parseUri(Uri uri, String searchQuery) {
        ParsedUri parsedUri = ParsedUri.fromUri(uri);
        timeline = Timeline.fromParsedUri(myContext, parsedUri, searchQuery);
        return !timeline.isEmpty();
    }

    public String toSummary() {
        return whichPage.getTitle(myContext.context()) + " " +
                TimelineTitle.load(myContext, timeline, MyAccount.EMPTY);
    }

    @NonNull
    public MyAccount getMyAccount() {
        return timeline.getMyAccount();
    }

    public void rememberSentDateLoaded(long sentDate) {
        if (minSentDateLoaded == 0 || minSentDateLoaded > sentDate) {
            minSentDateLoaded = sentDate;
        }
        if (maxSentDateLoaded == 0 || maxSentDateLoaded < sentDate) {
            maxSentDateLoaded = sentDate;
        }
    }

    private void prepareQueryParameters() {
        switch (whichPage) {
            case CURRENT:
                minSentDate = (new TimelinePositionStorage<>( null, null, this)).getTLPosition().minSentDate;
                break;
            default:
                break;
        }
        sortOrderAndLimit = buildSortOrderAndLimit();
        selectionAndArgs = buildSelectionAndArgs();
    }

    private String buildSortOrderAndLimit() {
        return  ActivityTable.getTimeSortOrder(getTimelineType(), isSortOrderAscending())
                + (minSentDate > 0 && maxSentDate > 0 ? "" : " LIMIT " + PAGE_SIZE);
    }

    private SelectionAndArgs buildSelectionAndArgs() {
        SelectionAndArgs sa = new SelectionAndArgs();
        sa.addSelection(ActivityTable.getTimeSortField(getTimelineType()) + " >= ?",
                new String[]{
                        String.valueOf(minSentDate > 0 ? minSentDate : 1)
                });
        if (maxSentDate > 0) {
            sa.addSelection(ActivityTable.getTimeSortField(getTimelineType()) + " <= ?",
                    String.valueOf(maxSentDate));
        }
        return sa;
    }

    Cursor queryDatabase() {
        prepareQueryParameters();
        return myContext.context().getContentResolver().query(getContentUri(), mProjection.toArray(new String[]{}),
                selectionAndArgs.selection, selectionAndArgs.selectionArgs, sortOrderAndLimit);
    }

    public Uri getContentUri() {
        return MatchedUri.getTimelineUri(timeline);
    }

    public MyContext getMyContext() {
        return myContext;
    }

    public boolean isSameTimeline(TimelineParameters other) {
        return other != null &&  getTimeline().equals(other.getTimeline());
    }
}
