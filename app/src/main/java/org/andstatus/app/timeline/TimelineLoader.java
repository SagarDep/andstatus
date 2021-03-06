/*
 * Copyright (C) 2015-2017 yvolk (Yuri Volkov), http://yurivolkov.com
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

import android.database.Cursor;
import android.support.annotation.NonNull;

import org.andstatus.app.data.DbUtils;
import org.andstatus.app.list.SyncLoader;
import org.andstatus.app.util.I18n;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.StopWatch;

import java.util.ArrayList;
import java.util.List;

/**
* @author yvolk@yurivolkov.com
*/
public class TimelineLoader<T extends ViewItem<T>> extends SyncLoader<T> {
    private final TimelineParameters params;
    private final TimelinePage<T> page;

    private final long instanceId;

    protected TimelineLoader(@NonNull TimelineParameters params, long instanceId) {
        this.params = params;
        this.page = new TimelinePage<T>(getParams(), new ArrayList<>());
        this.items = page.items;
        this.instanceId = instanceId;
    }

    @Override
    public void load(LoadableListActivity.ProgressPublisher publisher) {
        final String method = "load";
        final StopWatch stopWatch = StopWatch.createStarted();
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " started; " + params.toSummary() );
        }
        params.timeline.save(params.getMyContext());
        if (params.whichPage != WhichPage.EMPTY) {
            filter(loadFromCursor(queryDatabase()));
        }
        params.isLoaded = true;
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " ended" + ", " + page.items.size() + " rows,"
                    + " dates from " + MyLog.formatDateTime(page.params.minDateLoaded)
                    + " to " + MyLog.formatDateTime(page.params.maxDateLoaded)
                    + ", " + stopWatch.getTime() + "ms");
        }
    }

    private Cursor queryDatabase() {
        final String method = "queryDatabase";
        final StopWatch stopWatch = StopWatch.createStarted();
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " started");
        }
        Cursor cursor = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                cursor = getParams().queryDatabase();
                break;
            } catch (IllegalStateException e) {
                String message = "Attempt " + attempt + " to prepare cursor";
                MyLog.d(this, String.valueOf(instanceId) + " " + method + "; " + message, e);
                if (DbUtils.waitBetweenRetries(message)) {
                    break;
                }
            }
        }
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " ended, " + stopWatch.getTime() + "ms");
        }
        return cursor;
    }

    private List<T> loadFromCursor(Cursor cursor) {
        final String method = "loadFromCursor";
        final StopWatch stopWatch = StopWatch.createStarted();
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " started" );
        }
        List<T> items = new ArrayList<>();
        int rowsCount = 0;
        if (cursor != null && !cursor.isClosed()) {
            try {
                final StopWatch rowStopWatch = StopWatch.createStarted();
                if (cursor.moveToFirst()) {
                    do {
                        long rowMoveTime= rowStopWatch.getTime();
                        rowsCount++;
                        T item = (T) page.getEmptyItem().fromCursor(cursor);
                        long rowFromCursorTime = rowStopWatch.getTime() - rowMoveTime;
                        getParams().rememberItemDateLoaded(item.getDate());
                        items.add(item);
                        if (MyLog.isVerboseEnabled()) {
                            MyLog.v(this, method + "; row " + rowsCount + ", id:" + item.getId()
                                    + ", total: " + rowStopWatch.getTimeAndRestart() + "ms, rowMove: " + rowMoveTime + "ms"
                                    + ", fromCursor: " + rowFromCursorTime + "ms");
                        }
                    } while (cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
        }
        getParams().rowsLoaded = rowsCount;
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " ended; " + rowsCount + " rows, " + stopWatch.getTime() + "ms" );
        }
        return items;
    }

    protected void filter(List<T> items) {
        final String method = "filter";
        final StopWatch stopWatch = StopWatch.createStarted();
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " started" );
        }
        TimelineFilter filter = new TimelineFilter(getParams().getTimeline());
        int rowsCount = 0;
        int filteredOutCount = 0;
        boolean reversedOrder = getParams().isSortOrderAscending();
        for (T item : items) {
            rowsCount++;
            if (item.matches(filter)) {
                if (reversedOrder) {
                    page.items.add(0, item);
                } else {
                    page.items.add(item);
                }
            } else {
                filteredOutCount++;
                if (MyLog.isVerboseEnabled() && filteredOutCount < 6) {
                    MyLog.v(this, filteredOutCount + " Filtered out: "
                            + I18n.trimTextAt(item.toString(), 200));
                }
            }
        }
        if (MyLog.isDebugEnabled()) {
            MyLog.d(this, method + " ended; Filtered out " + filteredOutCount + " of " + rowsCount
                    + " rows, " + stopWatch.getTime() + "ms" );
        }
    }

    public TimelineParameters getParams() {
        return params;
    }

    @Override
    public String toString() {
        return MyLog.formatKeyValue(this, getParams().toString());
    }

    @NonNull
    public TimelinePage<T> getPage() {
        return page;
    }
}
