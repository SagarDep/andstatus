/*
 * Copyright (C) 2013 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.origin;

public class OriginConfig {
    private boolean isEmpty = true;
    
    public int shortUrlLength = 0;
    public int textLimit = 0;
    public int uploadLimit = 0;
    
    public static OriginConfig getEmpty() {
        return new OriginConfig();
    }

    public static OriginConfig fromTextLimit(int textLimit, int uploadLimit) {
        OriginConfig config = new OriginConfig();
        config.textLimit = textLimit;
        config.uploadLimit = uploadLimit;
        config.isEmpty = false;
        return config;
    }
    
    private OriginConfig() {
        // Empty
    }
    
    public boolean isEmpty() {
        return isEmpty;
    }
}
