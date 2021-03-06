/*
 * Copyright (c) 2017 yvolk (Yuri Volkov), http://yurivolkov.com
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

import org.andstatus.app.context.MyContext;
import org.andstatus.app.timeline.meta.TimelineType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ViewItem<T extends ViewItem<T>> {
    private final List<T> children = new ArrayList<>();
    private final boolean isEmpty;
    private ViewItem parent = EmptyViewItem.EMPTY;

    protected ViewItem(boolean isEmpty) {
        this.isEmpty = isEmpty;
    }

    @NonNull
    public T getEmpty(@NonNull TimelineType timelineType) {
        return (T) ViewItemType.fromTimelineType(timelineType).emptyViewItem;
    }

    public long getId() {
        return 0;
    }

    public long getDate() {
        return 0;
    }

    @NonNull
    public final Collection<T> getChildren() {
        return children;
    }

    @NonNull
    public DuplicationLink duplicates(@NonNull T other) {
        return DuplicationLink.NONE;
    }

    public boolean isCollapsed() {
        return getChildrenCount() > 0;
    }

    void collapse(T child) {
        this.getChildren().addAll(child.getChildren());
        child.getChildren().clear();
        this.getChildren().add(child);
    }

    @NonNull
    public T fromCursor(Cursor cursor) {
        return getEmpty(TimelineType.UNKNOWN);
    }

    @NonNull
    public T getNew() {
        return getEmpty(TimelineType.UNKNOWN);
    }

    public boolean matches(TimelineFilter filter) {
        return true;
    }

    public boolean isEmpty() {
        return isEmpty;
    }

    protected int getChildrenCount() {
        return isEmpty() ? 0 : Integer.max(getParent().getChildrenCount(), getChildren().size());
    }

    public void setParent(ViewItem parent) {
        this.parent = parent;
    }
    @NonNull
    public ViewItem getParent() {
        return parent == null ? EmptyViewItem.EMPTY : parent;
    }

    public long getTopmostId() {
        return getParent().isEmpty() ? getId() : getParent().getId();
    }
}
