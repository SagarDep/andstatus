/**
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

import android.net.Uri;

import org.andstatus.app.database.table.ActorTable;
import org.andstatus.app.os.AsyncTaskLauncher;
import org.andstatus.app.os.MyAsyncTask;
import org.andstatus.app.util.UriUtils;

public class AvatarData extends DownloadData {
    public static final String TAG = AvatarData.class.getSimpleName();

    public static void asyncRequestDownload(final long actorIdIn) {
        AsyncTaskLauncher.execute(TAG, false,
                new MyAsyncTask<Void, Void, Void>(TAG + actorIdIn, MyAsyncTask.PoolEnum.FILE_DOWNLOAD) {
                    @Override
                    protected Void doInBackground2(Void... params) {
                        getForActor(actorIdIn).requestDownload();
                        return null;
                    }
                }
        );
    }
    
    public static AvatarData getForActor(long actorIdIn) {
        Uri avatarUriNew = UriUtils.fromString(MyQuery.actorIdToStringColumnValue(ActorTable.AVATAR_URL, actorIdIn));
        AvatarData data = new AvatarData(actorIdIn, Uri.EMPTY);
        if (!data.getUri().equals(avatarUriNew)) {
            deleteAllOfThisActor(actorIdIn);
            data = new AvatarData(actorIdIn, avatarUriNew);
        }
        return data;
    }
    
    private AvatarData(long actorIdIn, Uri avatarUriNew) {
        super(actorIdIn, 0, MyContentType.IMAGE, avatarUriNew);
    }
    
}
