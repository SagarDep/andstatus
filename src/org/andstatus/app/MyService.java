/* 
 * Copyright (C) 2008 Torgny Bjers
 * Copyright (c) 2011 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.andstatus.app.Account.CredentialsVerified;
import org.andstatus.app.appwidget.MyAppWidgetProvider;
import org.andstatus.app.data.MyDatabase;
import org.andstatus.app.data.MyDatabase.TimelineTypeEnum;
import org.andstatus.app.data.MyProvider;
import org.andstatus.app.data.MyPreferences;
import org.andstatus.app.net.ConnectionException;
import org.andstatus.app.util.ForegroundCheckTask;
import org.andstatus.app.util.I18n;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SharedPreferencesUtil;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;


/**
 * This is an application service that serves as a connection between Android
 * and Twitter. Other applications can interact with it via IPC.
 */
public class MyService extends Service {
    private static final String TAG = MyService.class.getSimpleName();

    /**
     * Use this tag to change logging level of the whole application Is used is
     * isLoggable(APPTAG, ... ) calls
     */
    public static final String APPTAG = "AndStatus";

    private static final String packageName = MyService.class.getPackage().getName();

    /**
     * Prefix of all actions of this Service
     */
    private static final String ACTIONPREFIX = packageName + ".action.";

    /**
     * Intent with this action sent when it is time to update AndStatus
     * AppWidget.
     * <p>
     * This may be sent in response to some new information is ready for
     * notification (some changes...), or the system booting.
     * <p>
     * The intent will contain the following extras:
     * <ul>
     * <li>{@link #EXTRA_MSGTYPE}</li>
     * <li>{@link #EXTRA_NUMTWEETSMSGTYPE}</li>
     * <li>{@link android.appwidget.AppWidgetManager#EXTRA_APPWIDGET_IDS}<br/>
     * The appWidgetIds to update. This may be all of the AppWidgets created for
     * this provider, or just a subset. The system tries to send updates for as
     * few AppWidget instances as possible.</li>
     * 
     * @see AppWidgetProvider#onUpdate AppWidgetProvider.onUpdate(Context
     *      context, AppWidgetManager appWidgetManager, int[] appWidgetIds)
     */
    public static final String ACTION_APPWIDGET_UPDATE = ACTIONPREFIX + "APPWIDGET_UPDATE";
    
    /**
     * Repeating alarm was triggered.
     * @see MyService#scheduleRepeatingAlarm()
     */
    public static final String ACTION_ALARM = ACTIONPREFIX + "ALARM";

    /**
     * This action is being sent by {@link MyService} to notify that it
     * was stopped
     */
    public static final String ACTION_SERVICE_STOPPED = ACTIONPREFIX + "SERVICE_STOPPED";

    /**
     * This action is used in any intent sent to this service. Actual command to
     * perform by this service is in the {@link #EXTRA_MSGTYPE} extra of the
     * intent
     * 
     * @see CommandEnum
     */
    public static final String ACTION_GO = ACTIONPREFIX + "GO";

    /**
     * These names of extras are used in the Intent-notification of new Msg
     * (e.g. to notify Widget).
     */

    /**
     * This extra is used as a command to perform by MyService and
     * MyAppWidgetProvider Value of this extra is string code of
     * CommandEnum (not serialized enum !)
     */
    public static final String EXTRA_MSGTYPE = packageName + ".MSGTYPE";

    /**
     * Command parameter: long - ID of the Tweet (or Msg)
     */
    public static final String EXTRA_TWEETID = packageName + ".TWEETID";

    /**
     * Text of the status message
     */
    public static final String EXTRA_STATUS = packageName + ".STATUS";

    /**
     * User name
     */
    public static final String EXTRA_USERNAME = packageName + ".USERNAME";

    /**
     * Name of the preference to set
     */
    public static final String EXTRA_PREFERENCE_KEY = packageName + ".PREFERENCE_KEY";

    public static final String EXTRA_PREFERENCE_VALUE = packageName + ".PREFERENCE_VALUE";

    /**
     * Reply to
     */
    public static final String EXTRA_INREPLYTOID = packageName + ".INREPLYTOID";

    /**
     * Number of new tweets. Value is integer
     */
    public static final String EXTRA_NUMTWEETS = packageName + ".NUMTWEETS";

    /**
     * This extra is used to determine which timeline to show in
     * TimelineActivity Value is integer (TODO: enum...)
     */
    public static final String EXTRA_TIMELINE_TYPE = packageName + ".TIMELINE_TYPE";

    /**
     * The command to the MyService or to MyAppWidgetProvider as a
     * enum We use 'code' for persistence
     * 
     * @author yvolk
     */
    public enum CommandEnum {

        /**
         * The action is unknown
         */
        UNKNOWN("unknown"),
        /**
         * There is no action
         */
        EMPTY("empty"),
        /**
         * The action is being sent by recurring alarm to fetch the tweets,
         * replies and other information in the background.
         * TODO: Plus for all Accounts 
         */
        AUTOMATIC_UPDATE("automatic-update"),
        /**
         * Fetch all timelines for current Account 
         * (this is generally done after addition of the new Account)
         */
        FETCH_ALL_TIMELINES("fetch-all-timelines"),
        /**
         * Fetch the Home timeline and other information (replies...).
         */
        FETCH_HOME("fetch-home"),
        /**
         * Fetch the Mentions timeline and other information (replies...).
         */
        FETCH_MENTIONS("fetch-mention"),
        /**
         * Fetch Direct messages
         */
        FETCH_DIRECT_MESSAGES("fetch-dm"),
        /**
         * The recurring alarm that is used to implement recurring tweet
         * downloads should be started.
         */
        START_ALARM("start-alarm"),
        /**
         * The recurring alarm that is used to implement recurring tweet
         * downloads should be stopped.
         */
        STOP_ALARM("stop-alarm"),
        /**
         * The recurring alarm that is used to implement recurring tweet
         * downloads should be restarted.
         */
        RESTART_ALARM("restart-alarm"),

        CREATE_FAVORITE("create-favorite"), DESTROY_FAVORITE("destroy-favorite"),

        UPDATE_STATUS("update-status"), DESTROY_STATUS("destroy-status"),
        
        RETWEET("retweet"),

        RATE_LIMIT_STATUS("rate-limit-status"),

        /**
         * Notify User about commands in the Queue
         */
        NOTIFY_QUEUE("notify-queue"),

        /**
         * Commands to the Widget New tweets|messages were successfully loaded
         * from the server
         */
        NOTIFY_DIRECT_MESSAGE("notify-direct-message"), NOTIFY_TIMELINE("notify-timeline"), NOTIFY_MENTIONS(
                "notify-mentions"), 
                // TODO: Add NOTIFY_REPLIES("notify-replies"),
        /**
         * Clear previous notifications (because e.g. user open tweet list...)
         */
        NOTIFY_CLEAR("notify-clear"),

        /**
         * Reload all preferences...
         */
        PREFERENCES_CHANGED("preferences-changed"),

        /**
         * Save SharePreverence. We try to use it because sometimes Android
         * doesn't actually store these values to the disk... and the
         * preferences get lost. I think this is mainly because of several
         * processes using the same preferences
         */
        PUT_BOOLEAN_PREFERENCE("put-boolean-preference"), PUT_LONG_PREFERENCE("put-long-preference"), PUT_STRING_PREFERENCE(
                "put-string-preference");

        /**
         * code of the enum that is used in messages
         */
        private String code;

        private CommandEnum(String codeIn) {
            code = codeIn;
        }

        /**
         * String code for the Command to be used in messages
         */
        public String save() {
            return code;
        }

        /**
         * Returns the enum for a String action code or UNKNOWN
         */
        public static CommandEnum load(String strCode) {
            for (CommandEnum serviceCommand : CommandEnum.values()) {
                if (serviceCommand.code.equals(strCode)) {
                    return serviceCommand;
                }
            }
            return UNKNOWN;
        }

    }

    /**
     * Command data store (message...)
     * 
     * @author yvolk
     */
    public static class CommandData {
        public CommandEnum command;

        public long itemId = 0;

        /**
         * Other command parameters
         */
        public Bundle bundle = new Bundle();

        private int hashcode = 0;

        /**
         * Number of retries left
         */
        public int retriesLeft = 0;

        public CommandData(CommandEnum commandIn) {
            command = commandIn;
        }

        public CommandData(CommandEnum commandIn, long itemIdIn) {
            command = commandIn;
            itemId = itemIdIn;
        }

        /**
         * Initialize command to put SharedPreference
         * 
         * @param preferenceKey
         * @param value
         * @param username - preferences for this user, or null if Global
         *            preferences
         */
        public CommandData(String preferenceKey, boolean value, String username) {
            command = CommandEnum.PUT_BOOLEAN_PREFERENCE;
            bundle.putString(EXTRA_PREFERENCE_KEY, preferenceKey);
            bundle.putBoolean(EXTRA_PREFERENCE_VALUE, value);
            if (username != null) {
                bundle.putString(EXTRA_USERNAME, username);
            }
        }

        /**
         * Initialize command to put SharedPreference
         * 
         * @param preferenceKey
         * @param value
         * @param username - preferences for this user, or null if Global
         *            preferences
         */
        public CommandData(String preferenceKey, long value, String username) {
            command = CommandEnum.PUT_LONG_PREFERENCE;
            bundle.putString(EXTRA_PREFERENCE_KEY, preferenceKey);
            bundle.putLong(EXTRA_PREFERENCE_VALUE, value);
            if (username != null) {
                bundle.putString(EXTRA_USERNAME, username);
            }
        }

        /**
         * Initialize command to put SharedPreference
         * 
         * @param preferenceKey
         * @param value
         * @param username - preferences for this user
         */
        public CommandData(String preferenceKey, String value, String username) {
            command = CommandEnum.PUT_STRING_PREFERENCE;
            bundle.putString(EXTRA_PREFERENCE_KEY, preferenceKey);
            bundle.putString(EXTRA_PREFERENCE_VALUE, value);
            if (username != null) {
                bundle.putString(EXTRA_USERNAME, username);
            }
        }

        /**
         * Used to decode command from the Intent upon receiving it
         * 
         * @param intent
         */
        public CommandData(Intent intent) {
            bundle = intent.getExtras();
            // Decode command
            String strCommand = "(no command)";
            if (bundle != null) {
                strCommand = bundle.getString(EXTRA_MSGTYPE);
                itemId = bundle.getLong(EXTRA_TWEETID);
            }
            command = CommandEnum.load(strCommand);
        }

        /**
         * Restore the object from the SharedPreferences 
         * @param sp
         * @param index Index of the preference's name to be used
         */
        public CommandData(SharedPreferences sp, int index) {
            bundle = new Bundle();
            String si = Integer.toString(index);
            // Decode command
            String strCommand = sp.getString(EXTRA_MSGTYPE + si, CommandEnum.UNKNOWN.save());
            itemId = sp.getLong(EXTRA_TWEETID + si, 0);
            command = CommandEnum.load(strCommand);

            switch (command) {
                case UPDATE_STATUS:
                    bundle.putString(EXTRA_STATUS, sp.getString(EXTRA_STATUS + si, ""));
                    bundle.putLong(EXTRA_INREPLYTOID, sp.getLong(EXTRA_INREPLYTOID + si, 0));
                    break;
            }

            MyLog.v(TAG, "Restored command " + (EXTRA_MSGTYPE + si) + " = " + strCommand);
        }
        
        /**
         * It's used in equals() method We need to distinguish duplicated
         * commands
         */
        @Override
        public int hashCode() {
            if (hashcode == 0) {
                String text = Long.toString(command.ordinal());
                if (itemId != 0) {
                    text += Long.toString(itemId);
                }
                switch (command) {
                    case UPDATE_STATUS:
                        text += bundle.getString(EXTRA_STATUS);
                        break;
                    case PUT_BOOLEAN_PREFERENCE:
                        text += bundle.getString(EXTRA_PREFERENCE_KEY)
                                + bundle.getString(EXTRA_USERNAME)
                                + bundle.getBoolean(EXTRA_PREFERENCE_VALUE);
                        break;
                    case PUT_LONG_PREFERENCE:
                        text += bundle.getString(EXTRA_PREFERENCE_KEY)
                                + bundle.getString(EXTRA_USERNAME)
                                + bundle.getLong(EXTRA_PREFERENCE_VALUE);
                        break;
                    case PUT_STRING_PREFERENCE:
                        text += bundle.getString(EXTRA_PREFERENCE_KEY)
                                + bundle.getString(EXTRA_USERNAME)
                                + bundle.getString(EXTRA_PREFERENCE_VALUE);
                        break;
                }
                hashcode = text.hashCode();
            }
            return hashcode;
        }

        /**
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {
            return "CommandData [" + "command=" + command.save()
                    + (itemId == 0 ? "" : "; id=" + itemId) + ", hashCode=" + hashCode() + "]";
        }

        /**
         * @return Intent to be sent to this.AndStatusService
         */
        public Intent toIntent() {
            return toIntent(null);
        }

        /**
         * @return Intent to be sent to this.AndStatusService
         */
        public Intent toIntent(Intent intent_in) {
            Intent intent = intent_in;
            if (intent == null) {
                intent = new Intent(MyService.ACTION_GO);
            }
            if (bundle == null) {
                bundle = new Bundle();
            }
            bundle.putString(MyService.EXTRA_MSGTYPE, command.save());
            if (itemId != 0) {
                bundle.putLong(MyService.EXTRA_TWEETID, itemId);
            }
            intent.putExtras(bundle);
            return intent;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (obj.getClass() != getClass()) {
                return false;
            }
            return (this.hashCode() == ((CommandData) obj).hashCode());
        }

        /**
         * Persist the object to the SharedPreferences 
         * We're not storing all types of commands here because not all commands
         *   go to the queue
         * @param sp
         * @param index Index of the preference's name to be used
         */
        public void save(SharedPreferences sp, int index) {
            String si = Integer.toString(index);

            android.content.SharedPreferences.Editor ed = sp.edit();
            ed.putString(EXTRA_MSGTYPE + si, command.save());
            if (itemId != 0) {
                ed.putLong(EXTRA_TWEETID + si, itemId);
            }
            switch (command) {
                case UPDATE_STATUS:
                    ed.putString(EXTRA_STATUS + si, bundle.getString(EXTRA_STATUS));
                    ed.putLong(EXTRA_INREPLYTOID + si, bundle.getLong(EXTRA_INREPLYTOID));
                    break;
            }
            ed.commit();
        }
    }

    /**
     * This is a list of callbacks that have been registered with the service.
     */
    final RemoteCallbackList<IMyServiceCallback> mCallbacks = new RemoteCallbackList<IMyServiceCallback>();

    private static final int MILLISECONDS = 1000;

    /**
     * Send broadcast to Widgets even if there are no new tweets
     */
    // TODO: Maybe this should be additional setting...
    public static boolean updateWidgetsOnEveryUpdate = true;

    private boolean mNotificationsEnabled;

    private boolean mNotificationsVibrate;
    /**
     * Flag to control the Service state persistence
     */
    private boolean mStateRestored = false;

    /**
     * Commands queue to be processed by the Service
     */
    private BlockingQueue<CommandData> mCommands = new ArrayBlockingQueue<CommandData>(100, true);

    /**
     * Retry Commands queue
     */
    private BlockingQueue<CommandData> mRetryQueue = new ArrayBlockingQueue<CommandData>(100, true);

    /**
     * The set of threads that are currently executing commands For now let's
     * have only ONE working thread (it seems there is some problem in parallel
     * execution...)
     */
    private Set<CommandExecutor> mExecutors = new HashSet<CommandExecutor>();

    /**
     * The number of listeners returned by the last broadcast start call.
     */
    private volatile int mBroadcastListenerCount = 0;

    /**
     * The reference to the wake lock used to keep the CPU from stopping during
     * downloads.
     */
    private volatile PowerManager.WakeLock mWakeLock = null;

    /**
     * Time when shared preferences where changed
     */
    protected long preferencesChangeTime = 0;
    /**
     * Time when shared preferences where analyzed
     */
    protected long preferencesExamineTime = 0;
    
    /**
     * @return Single instance of Default SharedPreferences is returned, this is why we
     *         may synchronize on the object
     */
    private SharedPreferences getSp() {
        return MyPreferences.getDefaultSharedPreferences();
    }

    /**
     * The idea is to have SharePreferences, that are being edited by
     * the service process only (to avoid problems of concurrent access.
     * @return Single instance of SharedPreferences, specific to the class
     */
    private SharedPreferences getServiceSp() {
        return MyPreferences.getSharedPreferences(TAG, MODE_PRIVATE);
    }
    
    @Override
    public void onCreate() {
        MyPreferences.initialize(this, this);
        preferencesChangeTime = MyPreferences.getDefaultSharedPreferences().getLong(MyPreferences.KEY_PREFERENCES_CHANGE_TIME, 0);
        preferencesExamineTime = getServiceSp().getLong(MyPreferences.KEY_PREFERENCES_EXAMINE_TIME, 0);
        MyLog.d(TAG, "Service created, preferencesChangeTime=" + preferencesChangeTime + ", examined=" + preferencesExamineTime);

        registerReceiver(intentReceiver, new IntentFilter(ACTION_GO));
    }

    private BroadcastReceiver intentReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context arg0, Intent intent) {
            MyLog.v(TAG, "onReceive " + intent.toString());
            receiveCommand(intent);
        }

    };

    @Override
    public void onDestroy() {
        synchronized(this) {
            sendBroadcast(new Intent(ACTION_SERVICE_STOPPED));
            
            // Unregister all callbacks.
            mCallbacks.kill();

            unregisterReceiver(intentReceiver);

            // Clear notifications if any
            int count = notifyOfQueue(true);
            saveState();
            
            MyLog.d(TAG, "Service destroyed" + (count>0 ? ", " + count + " msg in the Queue" : ""));
            MyPreferences.forget();
        }
    }
    
    /**
     * Persist everything that we'll need on next Service creation.
     */
    private void saveState() {
        if (mStateRestored) {
            int count = 0;
            // TODO: Save Queues
            count += saveQueue(mCommands, TAG + "_" + "mCommands");
            count += saveQueue(mRetryQueue, TAG + "_" + "mRetryQueue");
            MyLog.d(TAG, "State saved" + (count>0 ? ", " + count + " msg in the Queues" : ""));
        }
        mStateRestored = false;
    }

    private int saveQueue(BlockingQueue<CommandData> q, String prefsFileName) {
        Context context = MyPreferences.getContext();
        int count = 0;
        // Delete any existing saved queue
        SharedPreferencesUtil.delete(context, prefsFileName);
        if (q.size() > 0) {
            SharedPreferences sp = MyPreferences.getSharedPreferences(prefsFileName, MODE_PRIVATE);
            while (q.size() > 0) {
                CommandData cd = q.poll();
                cd.save(sp, count);
                MyLog.v(TAG, "Command saved: " + cd.toString());
                count += 1;
            }
            MyLog.d(TAG, "Queue saved to " + prefsFileName  + ", " + count + " msgs");
        }
        return count;
    }
    
    /**
     * Restore state if it was not restored yet
     */
    private void restoreState() {
        synchronized(this) {
            if (!mStateRestored) {
                int count = 0;
                // TODO: Restore Queues
                count += restoreQueue(mCommands, TAG + "_" + "mCommands");
                count += restoreQueue(mRetryQueue, TAG + "_" + "mRetryQueue");
                MyLog.d(TAG, "State restored" + (count>0 ? ", " + count + " msg in the Queues" : ""));
            }
            mStateRestored = true;
        }        
    }

    private int restoreQueue(BlockingQueue<CommandData> q, String prefsFileName) {
        Context context = MyPreferences.getContext();
        int count = 0;
        if (SharedPreferencesUtil.exists(context, prefsFileName)) {
            boolean done = false;
            SharedPreferences sp = MyPreferences.getSharedPreferences(prefsFileName, MODE_PRIVATE);
            do {
                CommandData cd = new CommandData(sp, count);
                if (cd.command == CommandEnum.UNKNOWN) {
                    done = true;
                } else {
                    try {
                        q.put(cd);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    MyLog.v(TAG, "Command restored: " + cd.toString());
                    count += 1;
                }
            } while (!done);
            sp = null;
            // Delete this saved queue
            SharedPreferencesUtil.delete(context, prefsFileName);
            MyLog.d(TAG, "Queue restored from " + prefsFileName  + ", " + count + " msgs");
        }
        return count;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        // Select the interface to return. If your service only implements
        // a single interface, you can just return it here without checking
        // the Intent.
        if (IMyService.class.getName().equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        MyLog.d(TAG, "onStart(): startid: " + startId);
        receiveCommand(intent);
    }

    /**
     * Put Intent to the Command's queue and Start Execution thread if none is
     * already running
     * 
     * @param Intent containing command and it's parameters. It may be null to
     *            initialize execution only.
     */
    private synchronized void receiveCommand(Intent intent) {
        
        long preferencesChangeTimeNew = MyPreferences.getDefaultSharedPreferences().getLong(
                MyPreferences.KEY_PREFERENCES_CHANGE_TIME, 0);
        if (preferencesChangeTime != preferencesChangeTimeNew
                || preferencesExamineTime < preferencesChangeTimeNew) {
            examinePreferences();
        }
        
        restoreState();
        
        if (mCommands.isEmpty()) {
            // This is a good place to send commands from retry Queue
            while (!mRetryQueue.isEmpty()) {
                CommandData commandData = mRetryQueue.poll();
                if (!mCommands.contains(commandData)) {
                    if (!mCommands.offer(commandData)) {
                        Log.e(TAG, "mCommands is full?");
                    }
                }
            }
        }

        if (intent != null) {
            CommandData commandData = new CommandData(intent);
            if (commandData.command == CommandEnum.UNKNOWN) {
                // Ignore unknown commands

                // Maybe this command may be processed synchronously without
                // Internet connection?
            } else if (processCommandImmediately(commandData)) {
                // Don't add to the queue
            } else if (mCommands.contains(commandData)) {
                MyLog.d(TAG, "Duplicated " + commandData);
                // Reset retries counter on receiving duplicated command
                for (CommandData cd:mCommands) {
                    if (cd.equals(commandData)) {
                        cd.retriesLeft = 0;
                        break;
                    }
                }
            } else {
                MyLog.d(TAG, "Adding to the queue " + commandData);
                if (!mCommands.offer(commandData)) {
                    Log.e(TAG, "mCommands is full?");
                }
            }
        }

        // Start Executor if necessary
        startEndStuff(true, null, null);
    }

    /**
     * @param commandData
     * @return true if the command was processed (either successfully or not...)
     */
    private boolean processCommandImmediately(CommandData commandData) {
        boolean processed = false;
        // Processed successfully?
        boolean ok = true;
        boolean skipped = false;

        /**
         * Flag for debugging. It looks like for now we don't need to edit
         * SharedPreferences from this part of code
         */
        boolean putPreferences = false;

        processed = (commandData == null);
        if (!processed) {
            processed = true;
            switch (commandData.command) {

                // TODO: Do we really need these three commands?
                case START_ALARM:
                    ok = scheduleRepeatingAlarm();
                    break;
                case STOP_ALARM:
                    ok = cancelRepeatingAlarm();
                    break;
                case RESTART_ALARM:
                    ok = cancelRepeatingAlarm();
                    ok = scheduleRepeatingAlarm();
                    break;

                case UNKNOWN:
                case EMPTY:
                    // Nothing to do
                    break;
                case PREFERENCES_CHANGED:
                    examinePreferences();
                    break;

                case PUT_BOOLEAN_PREFERENCE:
                    if (!putPreferences) {
                        skipped = true;
                        break;
                    }
                    String key = commandData.bundle.getString(EXTRA_PREFERENCE_KEY);
                    boolean boolValue = commandData.bundle.getBoolean(EXTRA_PREFERENCE_VALUE);
                    String username = commandData.bundle.getString(EXTRA_USERNAME);
                    MyLog.v(TAG, "Put boolean Preference '" + key + "'=" + boolValue
                            + ((username != null) ? " user='" + username + "'" : " global"));
                    SharedPreferences sp = null;
                    if (username != null) {
                        sp = Account.getAccount(username).getAccountPreferences();
                    } else {
                        sp = getSp();
                    }
                    synchronized (sp) {
                        sp.edit().putBoolean(key, boolValue).commit();
                    }
                    break;
                case PUT_LONG_PREFERENCE:
                    if (!putPreferences) {
                        skipped = true;
                        break;
                    }
                    key = commandData.bundle.getString(EXTRA_PREFERENCE_KEY);
                    long longValue = commandData.bundle.getLong(EXTRA_PREFERENCE_VALUE);
                    username = commandData.bundle.getString(EXTRA_USERNAME);
                    MyLog.v(TAG, "Put long Preference '" + key + "'=" + longValue
                            + ((username != null) ? " user='" + username + "'" : " global"));
                    if (username != null) {
                        sp = Account.getAccount(username).getAccountPreferences();
                    } else {
                        sp = getSp();
                    }
                    synchronized (sp) {
                        sp.edit().putLong(key, longValue).commit();
                    }
                    break;
                case PUT_STRING_PREFERENCE:
                    if (!putPreferences) {
                        skipped = true;
                        break;
                    }
                    key = commandData.bundle.getString(EXTRA_PREFERENCE_KEY);
                    String stringValue = commandData.bundle.getString(EXTRA_PREFERENCE_VALUE);
                    username = commandData.bundle.getString(EXTRA_USERNAME);
                    MyLog.v(TAG, "Put String Preference '" + key + "'=" + stringValue
                            + ((username != null) ? " user='" + username + "'" : " global"));
                    if (username != null) {
                        sp = Account.getAccount(username).getAccountPreferences();
                    } else {
                        sp = getSp();
                    }
                    synchronized (sp) {
                        sp.edit().putString(key, stringValue).commit();
                    }
                    break;
                default:
                    processed = false;
                    break;
            }
            if (processed) {
                MyLog.d(TAG, (skipped ? "Skipped" : (ok ? "Succeeded" : "Failed")) + " " + commandData);
            }
        }
        return processed;
    }

    /**
     * Examine changed preferences and behave accordingly
     * Clear all (including static) members, that depend on preferences
     * and need to be reread...
     */
    private boolean examinePreferences() {
        boolean ok = true;
        
        // Check when preferences were changed
        long preferencesChangeTimeNew = MyPreferences.getDefaultSharedPreferences().getLong(MyPreferences.KEY_PREFERENCES_CHANGE_TIME, 0);
        long preferencesExamineTimeNew = java.lang.System.currentTimeMillis();
        
        if (preferencesChangeTimeNew > preferencesExamineTime) {
            MyLog.d(TAG, "Examine at=" + preferencesExamineTimeNew + " Preferences changed at=" + preferencesChangeTimeNew);
        } else if (preferencesChangeTimeNew > preferencesChangeTime) {
            MyLog.d(TAG, "Preferences changed at=" + preferencesChangeTimeNew);
        } else if (preferencesChangeTimeNew == preferencesChangeTime) {
            MyLog.d(TAG, "Preferences didn't change, still at=" + preferencesChangeTimeNew);
        } else {
            Log.e(TAG, "Preferences change time error, time=" + preferencesChangeTimeNew);
        }
        preferencesChangeTime = preferencesChangeTimeNew;
        preferencesExamineTime = preferencesExamineTimeNew;
        getServiceSp().edit().putLong(MyPreferences.KEY_PREFERENCES_EXAMINE_TIME, preferencesExamineTime).commit();

        // Forget and reload preferences...
        MyPreferences.forget();
        MyPreferences.initialize(this, this);

        // Stop existing alarm in any case
        ok = cancelRepeatingAlarm();

        SharedPreferences sp = MyPreferences.getDefaultSharedPreferences();
        if (sp.contains("automatic_updates") && sp.getBoolean("automatic_updates", false)) {
            /**
             * Schedule Automatic updates according to the preferences.
             */
            ok = scheduleRepeatingAlarm();
        }
        return ok;
    }

    /**
     * Start Execution thread if none is already running or stop execution
     * 
     * @param start true: start, false: stop
     * @param executor - existing executor or null (if starting new executor)
     * @param logMsg a log message to include for debugging
     */
    private synchronized void startEndStuff(boolean start, CommandExecutor executorIn, String logMsg) {
        if (start) {
            SharedPreferences sp = getSp();
            mNotificationsEnabled = sp.getBoolean("notifications_enabled", false);
            mNotificationsVibrate = sp.getBoolean("vibration", false);
            sp = null;

            if (!mCommands.isEmpty()) {
                // Don't even launch executor if we're not online
                if (isOnline() && MyPreferences.isDataAvailable()) {
                    // only one Executing thread for now...
                    if (mExecutors.isEmpty()) {
                        CommandExecutor executor;
                        if (executorIn != null) {
                            executor = executorIn;
                        } else {
                            executor = new CommandExecutor();
                        }
                        if (logMsg != null) {
                            MyLog.d(TAG, logMsg);
                        }
                        mExecutors.add(executor);
                        if (mExecutors.size() == 1) {
                            mWakeLock = getWakeLock();
                            mBroadcastListenerCount = mCallbacks.beginBroadcast();
                            MyLog.d(TAG, "No other threads running so starting new broadcast for "
                                    + mBroadcastListenerCount + " listeners");
                        }
                        executor.execute();
                    }
                } else {
                    notifyOfQueue(false);
                }
            }
        } else {
            // Stop
            if (logMsg != null) {
                MyLog.d(TAG, logMsg);
            }
            mExecutors.remove(executorIn);
            if (mExecutors.size() == 0) {
                MyLog.d(TAG, "Ending last thread so also ending broadcast.");
                mWakeLock.release();
                mCallbacks.finishBroadcast();
                if (notifyOfQueue(false) == 0) {
                    if (! ForegroundCheckTask.isAppOnForeground(MyPreferences.getContext())) {
                        MyLog.d(TAG, "App is on Background so stop this Service");
                        stopSelf();
                    }
                }
            }
        }
    }

    /**
     * Notify user of the commands Queue size
     * 
     * @return total size of Queues
     */
    private int notifyOfQueue(boolean clearNotification) {
        int count = mRetryQueue.size() + mCommands.size();
        NotificationManager nM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (count == 0 || clearNotification) {
            // Clear notification
            nM.cancel(CommandEnum.NOTIFY_QUEUE.ordinal());
        } else if (mNotificationsEnabled) {
            if (mRetryQueue.size() > 0) {
                MyLog.d(TAG, mRetryQueue.size() + " commands in Retry Queue.");
            }
            if (mCommands.size() > 0) {
                MyLog.d(TAG, mCommands.size() + " commands in Main Queue.");
            }

            // Set up the notification to display to the user
            Notification notification = new Notification(R.drawable.notification_icon,
                    (String) getText(R.string.notification_title), System.currentTimeMillis());

            int messageTitle;
            String aMessage = "";

            aMessage = I18n.formatQuantityMessage(getApplicationContext(),
                    R.string.notification_queue_format, count, R.array.notification_queue_patterns,
                    R.array.notification_queue_formats);
            messageTitle = R.string.notification_title_queue;

            // Set up the scrolling message of the notification
            notification.tickerText = aMessage;

            /**
             * Set the latest event information and send the notification
             * Actually don't start any intent
             * @see <a href="http://stackoverflow.com/questions/4232006/android-notification-pendingintent-problem">android-notification-pendingintent-problem</a>
             */
            // PendingIntent pi = PendingIntent.getActivity(this, 0, null, 0);

            /**
             * Kick the commands queue by sending empty command
             */
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new CommandData(
                    CommandEnum.EMPTY).toIntent(), 0);

            notification.setLatestEventInfo(this, getText(messageTitle), aMessage, pi);
            nM.notify(CommandEnum.NOTIFY_QUEUE.ordinal(), notification);
        }
        return count;
    }

    /**
     * Command executor
     * 
     * @author yvolk
     */
    private class CommandExecutor extends AsyncTask<Void, Void, JSONObject> {
        // private boolean skip = false;
        private final String SERVICE_NOT_RESTORED_TEXT = "MyService state is not restored";

        @Override
        protected void onPreExecute() {

        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            int what = 0;
            String message = "";
            MyLog.d(TAG, "CommandExecutor, " + mCommands.size() + " commands to process");

            do {
                boolean ok = false;
                CommandData commandData = null;
                synchronized(MyService.this) {
                    if (mStateRestored) {
                        // Get commands from the Queue one by one and execute them
                        // The queue is Blocking, so we can do this
                        commandData = mCommands.poll();
                    }
                }        
                if (commandData == null) {
                    // All work is done
                    break;
                }

                commandData.retriesLeft -= 1;
                boolean retry = false;
                MyLog.d(TAG, "Executing " + commandData);

                switch (commandData.command) {
                    case AUTOMATIC_UPDATE:
                    case FETCH_ALL_TIMELINES:
                        ok = loadTimeline(MyDatabase.TimelineTypeEnum.ALL);
                        break;
                    case FETCH_HOME:
                        ok = loadTimeline(MyDatabase.TimelineTypeEnum.HOME);
                        break;
                    case FETCH_MENTIONS:
                        ok = loadTimeline(MyDatabase.TimelineTypeEnum.MENTIONS);
                        break;
                    case FETCH_DIRECT_MESSAGES:
                        ok = loadTimeline(MyDatabase.TimelineTypeEnum.DIRECT);
                        break;
                    case CREATE_FAVORITE:
                    case DESTROY_FAVORITE:
                        ok = createOrDestroyFavorite(
                                commandData.command == CommandEnum.CREATE_FAVORITE,
                                commandData.itemId);
                        // Retry in a case of an error
                        retry = !ok;
                        break;
                    case UPDATE_STATUS:
                        String status = commandData.bundle.getString(EXTRA_STATUS).trim();
                        long inReplyToId = commandData.bundle.getLong(EXTRA_INREPLYTOID);
                        ok = updateStatus(status, inReplyToId);
                        retry = !ok;
                        break;
                    case DESTROY_STATUS:
                        ok = destroyStatus(commandData.itemId);
                        // Retry in a case of an error
                        retry = !ok;
                        break;
                    case RETWEET:
                        ok = retweet(commandData.itemId);
                        retry = !ok;
                        break;
                    case RATE_LIMIT_STATUS:
                        ok = rateLimitStatus();
                        break;
                    default:
                        Log.e(TAG, "Unexpected command here " + commandData);
                }
                MyLog.d(TAG, (ok ? "Succeeded" : "Failed") + " " + commandData);
                
                if (retry) {
                    boolean ok2 = true;
                    if (commandData.retriesLeft < 0) {
                        // This means that retriesLeft was not set yet,
                        // so let's set it to some default value, the same for
                        // any command
                        // that needs to be retried...
                        commandData.retriesLeft = 9;
                    }
                    // Check if any retries left (actually 0 means this was the
                    // last retry)
                    if (commandData.retriesLeft > 0) {
                        synchronized(MyService.this) {
                            if (mStateRestored) {
                                // Put the command to the retry queue
                                if (!mRetryQueue.contains(commandData)) {
                                    if (!mRetryQueue.offer(commandData)) {
                                        Log.e(TAG, "mRetryQueue is full?");
                                    }
                                }
                            } else {
                                Log.e(TAG, SERVICE_NOT_RESTORED_TEXT);
                                ok2 = false;
                            }
                        }        
                    } else {
                        ok2 = false;
                    }
                    if (!ok2) {
                        Log.e(TAG, "Couldn't execute " + commandData);
                    }
                }
                if (!ok && !isOnline()) {
                    // Don't bother with other commands if we're not Online :-)
                    break;
                }
            } while (true);

            try {
                jso = new JSONObject();
                jso.put("what", what);
                jso.put("message", message);
            } catch (JSONException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return jso;
        }

        /**
         * TODO: Delete unnecessary lines... This is in the UI thread, so we can
         * mess with the UI
         * 
         * @return ok
         */
        protected void onPostExecute(JSONObject jso) {
            // boolean succeeded = false;
            String message = null;
            if (jso != null) {
                try {
                    int what = jso.getInt("what");
                    message = jso.getString("message");

                    switch (what) {
                        case 0:

                            // succeeded = true;
                            break;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            synchronized(MyService.this) {
                if (mStateRestored) {
                    startEndStuff(false, this, message);
                }
            }        
        }

        /**
         * @param create true - create, false - destroy
         * @param msgId
         * @return boolean ok
         */
        private boolean createOrDestroyFavorite(boolean create, long msgId) {
            boolean ok = false;
            String oid = MyProvider.idToOid(MyDatabase.Msg.CONTENT_URI, msgId);
            JSONObject result = new JSONObject();
            if (oid.length()>0) {
                try {
                    if (create) {
                        result = Account.getAccount().getConnection().createFavorite(oid);
                    } else {
                        result = Account.getAccount().getConnection().destroyFavorite(oid);
                    }
                    ok = (result != null);
                } catch (ConnectionException e) {
                    Log.e(TAG,
                            (create ? "create" : "destroy") + "Favorite Connection Exception: "
                                    + e.toString());
                }
            } else {
                Log.e(TAG,
                        (create ? "create" : "destroy") + "Favorite; msgId not found: " + msgId);
            }
            if (ok) {
                synchronized(MyService.this) {
                    if (mStateRestored) {
                        try {
                            boolean favorited = result.getBoolean("favorited");
                            if (favorited != create) {
                                /** 
                                 * yvolk: 2011-09-27 Twitter docs state that this may happen
                                 *  due to asynchronous nature of the process,
                                 *  see https://dev.twitter.com/docs/api/1/post/favorites/create/%3Aid
                                 */
                                if (create) {
                                    // For the case we created favorite, let's change
                                    // the flag manually.
                                    result.put("favorited", create);

                                    MyLog.d(TAG,
                                            (create ? "create" : "destroy") + ". Favorited flag didn't change yet.");
                                    
                                    // Let's try to assume that everything was Ok:
                                    ok = true;
                                } else {
                                    // yvolk: 2011-09-27 Sometimes this twitter.com 'async' process doesn't work
                                    // so let's try another time...
                                    // This is safe, because "delete favorite" works
                                    // even for "Unfavorited" tweet :-)
                                    ok = false;

                                    Log.e(TAG,
                                            (create ? "create" : "destroy") + ". Favorited flag didn't change yet.");
                                }
                            }
                        } catch (JSONException e) {
                            Log.e(TAG,
                                    (create ? "create" : "destroy") + ". Checking resulted favorited flag: "
                                            + e.toString());
                        }
                        
                        if (ok) {
                            try {
                                TimelineDownloader fl = new TimelineDownloader(
                                MyService.this.getApplicationContext(), TimelineTypeEnum.HOME );
                                fl.insertFromJSONObject(result, true);
                            } catch (JSONException e) {
                                Log.e(TAG,
                                        "Error marking as " + (create ? "" : "not ") + "favorite: "
                                                + e.toString());
                            }
                        }
                    } else {
                        Log.e(TAG, (create ? "create" : "destroy") + "Favorite - " + SERVICE_NOT_RESTORED_TEXT);
                    }
                }        
                
            }

            // TODO: Maybe we need to notify the caller about the result?!

            MyLog.d(TAG, (create ? "Creating" : "Destroying") + " favorite "
                    + (ok ? "succeded" : "failed") + ", id=" + msgId);
            return ok;
        }

        /**
         * @param statusId
         * @return boolean ok
         */
        private boolean destroyStatus(long msgId) {
            boolean ok = false;
            String oid = MyProvider.idToOid(MyDatabase.Msg.CONTENT_URI, msgId);
            JSONObject result = new JSONObject();
            try {
                result = Account.getAccount().getConnection().destroyStatus(oid);
                ok = (result != null);
            } catch (ConnectionException e) {
                if (e.getStatusCode() == 404) {
                    // This means that there is no such "Status", so we may assume that it's Ok!
                    ok = true;
                } else {
                    Log.e(TAG, "destroyStatus Connection Exception: " + e.toString());
                }
            }

            if (ok) {
                synchronized(MyService.this) {
                    if (mStateRestored) {
                        // And delete the status from the local storage
                        try {
                            TimelineDownloader fl = new TimelineDownloader(
                                    MyService.this.getApplicationContext(),
                                    TimelineTypeEnum.HOME);
                            fl.destroyStatus(msgId);
                        } catch (Exception e) {
                            Log.e(TAG, "Error destroying status locally: " + e.toString());
                        }
                    } else {
                        Log.e(TAG, "destroyStatus - " + SERVICE_NOT_RESTORED_TEXT);
                    }
                }        
            }

            // TODO: Maybe we need to notify the caller about the result?!

            MyLog.d(TAG, "Destroying status " + (ok ? "succeded" : "failed") + ", id=" + msgId);
            return ok;
        }

        /**
         * @param status
         * @param inReplyToId
         * @return ok
         */
        private boolean updateStatus(String status, long inReplyToId) {
            String oid = MyProvider.idToOid(MyDatabase.Msg.CONTENT_URI, inReplyToId);
            boolean ok = false;
            JSONObject result = new JSONObject();
            try {
                result = Account.getAccount().getConnection()
                        .updateStatus(status.trim(), oid);
                ok = (result != null);
            } catch (ConnectionException e) {
                Log.e(TAG, "updateStatus Exception: " + e.toString());
            }
            if (ok) {
                synchronized(MyService.this) {
                    if (mStateRestored) {
                        try {
                            // The tweet was sent successfully
                            TimelineDownloader fl = new TimelineDownloader(
                                    MyService.this.getApplicationContext(),
                                    TimelineTypeEnum.HOME);

                            fl.insertFromJSONObject(result, true);
                        } catch (JSONException e) {
                            Log.e(TAG, "updateStatus JSONException: " + e.toString());
                        }
                    } else {
                        Log.e(TAG, "updateStatus - " + SERVICE_NOT_RESTORED_TEXT);
                    }
                }        
            }
            return ok;
        }

        private boolean retweet(long retweetedId) {
            String oid = MyProvider.idToOid(MyDatabase.Msg.CONTENT_URI, retweetedId);
            boolean ok = false;
            JSONObject result = new JSONObject();
            try {
                result = Account.getAccount().getConnection()
                        .postRetweet(oid);
                ok = (result != null);
            } catch (ConnectionException e) {
                Log.e(TAG, "retweet Exception: " + e.toString());
            }
            if (ok) {
                synchronized(MyService.this) {
                    if (mStateRestored) {
                        try {
                            // The tweet was sent successfully
                            TimelineDownloader fl = new TimelineDownloader(
                                    MyService.this.getApplicationContext(),
                                    TimelineTypeEnum.HOME);

                            fl.insertFromJSONObject(result, true);
                        } catch (JSONException e) {
                            Log.e(TAG, "retweet JSONException: " + e.toString());
                        }
                    } else {
                        Log.e(TAG, "retweet - " + SERVICE_NOT_RESTORED_TEXT);
                    }
                }        
            }
            return ok;
        }
        
        /**
         * @param loadHomeAndMentions - Should we load Home and Mentions
         * @param loadDirectMessages - Should we load direct messages
         * @return ok
         */
        public boolean loadTimeline(MyDatabase.TimelineTypeEnum timelineType_in) {

            // TODO: Cycle for all users...
            boolean ok = false;
            int msgAdded = 0;
            int mentionsAdded = 0;
            int directedAdded = 0;
            String descr = "(starting)";

            if (Account.getAccount().getCredentialsVerified() == CredentialsVerified.SUCCEEDED) {
                // Only if User was authenticated already
                try {
                    TimelineTypeEnum[] atl = new TimelineTypeEnum[] {
                        timelineType_in
                    };
                    if (timelineType_in == TimelineTypeEnum.ALL) {
                        atl = new TimelineTypeEnum[] {
                                TimelineTypeEnum.HOME, TimelineTypeEnum.MENTIONS, TimelineTypeEnum.DIRECT
                        };
                    }
                    
                    int pass = 1;
                    boolean okSomething = false;
                    boolean oKs[] = new boolean[atl.length];
                    for (int ind = 0; ind <= atl.length; ind++) {
                        
                        if (ind == atl.length) {
                            // This is some trick for the cases we load more than one timeline at once
                            // and there was an error on some timeline only
                            if (pass > 1 || !okSomething) {
                                break;
                            }
                            pass++;
                            ind = 0; // Start from beginning
                            MyLog.d(TAG, "Second pass of loading timeline");
                        }
                        if (pass > 1) {
                            // Find next error index
                            for (int ind2 = ind; ind2 < atl.length; ind2++) {
                                if (!oKs[ind2]) {
                                    ind = ind2;
                                    break;
                                }
                            }
                            if (oKs[ind]) {
                                // No more errors on the second pass
                                break;
                            }
                        }
                                                
                        TimelineTypeEnum timelineType = atl[ind];
                        MyLog.d(TAG, "Getting " + timelineType.save() + " for " + Account.getAccount().getUsername());

                        TimelineDownloader fl = null;
                        descr = "loading " + timelineType.save();
                        fl = new TimelineDownloader(MyService.this.getApplicationContext(),
                                timelineType);
                        ok = fl.loadTimeline();
                        switch (timelineType) {
                            case MENTIONS:
                                mentionsAdded = fl.mentionsCount();
                                break;
                            case HOME:
                                msgAdded = fl.messagesCount();
                                mentionsAdded += fl.mentionsCount();
                                break;
                            case DIRECT:
                                directedAdded = fl.messagesCount();
                                break;
                            default:
                                ok = false;
                                Log.e(TAG, descr + " - not implemented");
                        }

                        if (ok) {
                            synchronized (MyService.this) {
                                descr = "prune old records";
                                if (mStateRestored) {
                                    fl.pruneOldRecords();
                                } else {
                                    Log.i(TAG, descr + " - " + SERVICE_NOT_RESTORED_TEXT);
                                    ok = false;
                                }
                            }
                        }
                        if (ok) { okSomething = true;}
                        oKs[ind] = ok;
                    }
                } catch (ConnectionException e) {
                    Log.e(TAG, descr + ", Connection Exception: " + e.toString());
                    ok = false;
                } catch (SQLiteConstraintException e) {
                    Log.e(TAG, descr + ", SQLite Exception: " + e.toString());
                    ok = false;
                }
            }

            if (ok) {
                descr = "notifying";
                synchronized (MyService.this) {
                    if (mStateRestored) {
                        notifyOfUpdatedTimeline(msgAdded, mentionsAdded, directedAdded);
                    } else {
                        Log.i(TAG, descr + " - " + SERVICE_NOT_RESTORED_TEXT);
                        ok = false;
                    }
                }
            }

            String message = (ok ? "Succeeded" : "Failed") + " getting " + timelineType_in.save();
            if (msgAdded > 0) {
                message += ", " + msgAdded + " tweets";
            }
            if (mentionsAdded > 0) {
                message += ", " + mentionsAdded + " mentions";
            }
            if (directedAdded > 0) {
                message += ", " + directedAdded + " directs";
            }
            MyLog.d(TAG, message);

            return ok;
        }

        private void notifyOfUpdatedTimeline(int msgAdded, int mentionsAdded,
                int directedAdded) {

            // TODO: It's not so simple... I think :-)
            int N = mBroadcastListenerCount;

            for (int i = 0; i < N; i++) {
                try {
                    MyLog.d(TAG, "finishUpdateTimeline, Notifying callback no. " + i);
                    IMyServiceCallback cb = mCallbacks.getBroadcastItem(i);
                    if (cb != null) {
                        if (msgAdded > 0) {
                            cb.tweetsChanged(msgAdded);
                        }
                        if (mentionsAdded > 0) {
                            cb.repliesChanged(mentionsAdded);
                        }
                        if (directedAdded > 0) {
                            cb.messagesChanged(directedAdded);
                        }
                        cb.dataLoading(0);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, e.toString());
                }
            }
            boolean notified = false;
            if (mentionsAdded > 0) {
                notifyOfNewTweets(mentionsAdded, CommandEnum.NOTIFY_MENTIONS);
                notified = true;
            }
            if (directedAdded > 0) {
                notifyOfNewTweets(directedAdded, CommandEnum.NOTIFY_DIRECT_MESSAGE);
                notified = true;
            }
            if (msgAdded > 0 || !notified) {
                notifyOfNewTweets(msgAdded, CommandEnum.NOTIFY_TIMELINE);
                notified = true;
            }
        }

        /**
         * Notify the user of new tweets.
         * 
         * @param numTweets
         */
        private void notifyOfNewTweets(int numTweets, CommandEnum msgType) {
            MyLog.d(TAG, "notifyOfNewTweets n=" + numTweets + "; msgType=" + msgType);

            if (updateWidgetsOnEveryUpdate) {
                // Notify widgets even about the fact, that update occurred
                // even if there was nothing new
                updateWidgets(numTweets, msgType);
            }

            // If no notifications are enabled, return
            if (!mNotificationsEnabled || numTweets == 0) {
                return;
            }

            boolean notificationsMessages = false;
            boolean notificationsReplies = false;
            boolean notificationsTimeline = false;
            String ringtone = null;
            SharedPreferences sp = getSp();
            synchronized (sp) {
                notificationsMessages = sp.getBoolean("notifications_messages", false);
                notificationsReplies = sp.getBoolean("notifications_mentions", false);
                notificationsTimeline = sp.getBoolean("notifications_timeline", false);
                ringtone = sp.getString(MyPreferences.KEY_RINGTONE_PREFERENCE, null);
            }
            sp = null;

            // Make sure that notifications haven't been turned off for the
            // message
            // type
            switch (msgType) {
                case NOTIFY_MENTIONS:
                    if (!notificationsReplies)
                        return;
                    break;
                case NOTIFY_DIRECT_MESSAGE:
                    if (!notificationsMessages)
                        return;
                    break;
                case NOTIFY_TIMELINE:
                    if (!notificationsTimeline)
                        return;
                    break;
            }

            // Set up the notification to display to the user
            Notification notification = new Notification(R.drawable.notification_icon,
                    (String) getText(R.string.notification_title), System.currentTimeMillis());

            notification.vibrate = null;
            if (mNotificationsVibrate) {
                notification.vibrate = new long[] {
                        200, 300, 200, 300
                };
            }

            notification.flags = Notification.FLAG_SHOW_LIGHTS | Notification.FLAG_AUTO_CANCEL;
            notification.ledOffMS = 1000;
            notification.ledOnMS = 500;
            notification.ledARGB = Color.GREEN;

            if ("".equals(ringtone) || ringtone == null) {
                notification.sound = null;
            } else {
                Uri ringtoneUri = Uri.parse(ringtone);
                notification.sound = ringtoneUri;
            }

            // Set up the pending intent
            PendingIntent contentIntent;

            int messageTitle;
            Intent intent;
            String aMessage = "";

            // Prepare "intent" to launch timeline activities exactly like in
            // org.andstatus.app.TimelineActivity.onOptionsItemSelected
            switch (msgType) {
                case NOTIFY_MENTIONS:
                    aMessage = I18n.formatQuantityMessage(getApplicationContext(),
                            R.string.notification_new_mention_format, numTweets,
                            R.array.notification_mention_patterns,
                            R.array.notification_mention_formats);
                    messageTitle = R.string.notification_title_mentions;
                    intent = new Intent(getApplicationContext(), TweetListActivity.class);
                    intent.putExtra(MyService.EXTRA_TIMELINE_TYPE,
                            MyDatabase.TimelineTypeEnum.MENTIONS.save());
                    contentIntent = PendingIntent.getActivity(getApplicationContext(), numTweets,
                            intent, 0);
                    break;

                case NOTIFY_DIRECT_MESSAGE:
                    aMessage = I18n.formatQuantityMessage(getApplicationContext(),
                            R.string.notification_new_message_format, numTweets,
                            R.array.notification_message_patterns,
                            R.array.notification_message_formats);
                    messageTitle = R.string.notification_title_messages;
                    intent = new Intent(getApplicationContext(), TweetListActivity.class);
                    intent.putExtra(MyService.EXTRA_TIMELINE_TYPE,
                            MyDatabase.TimelineTypeEnum.DIRECT.save());
                    contentIntent = PendingIntent.getActivity(getApplicationContext(), numTweets,
                            intent, 0);
                    break;

                case NOTIFY_TIMELINE:
                default:
                    aMessage = I18n
                            .formatQuantityMessage(getApplicationContext(),
                                    R.string.notification_new_tweet_format, numTweets,
                                    R.array.notification_tweet_patterns,
                                    R.array.notification_tweet_formats);
                    messageTitle = R.string.notification_title;
                    intent = new Intent(getApplicationContext(), TweetListActivity.class);
                    intent.putExtra(MyService.EXTRA_TIMELINE_TYPE,
                            MyDatabase.TimelineTypeEnum.HOME.save());
                    contentIntent = PendingIntent.getActivity(getApplicationContext(), numTweets,
                            intent, 0);
                    break;
            }

            // Set up the scrolling message of the notification
            notification.tickerText = aMessage;

            // Set the latest event information and send the notification
            notification.setLatestEventInfo(MyService.this, getText(messageTitle), aMessage,
                    contentIntent);
            NotificationManager nM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nM.notify(msgType.ordinal(), notification);
        }

        /**
         * Send Update intent to AndStatus Widget(s), if there are some
         * installed... (e.g. on the Home screen...)
         * 
         * @see MyAppWidgetProvider
         */
        private void updateWidgets(int numTweets, CommandEnum msgType) {
            Intent intent = new Intent(ACTION_APPWIDGET_UPDATE);
            intent.putExtra(EXTRA_MSGTYPE, msgType.save());
            intent.putExtra(EXTRA_NUMTWEETS, numTweets);
            sendBroadcast(intent);
        }

        /**
         * Ask the the Twitter service of how many more requests are allowed:
         * number of remaining API calls.
         * 
         * @return ok
         */
        private boolean rateLimitStatus() {
            boolean ok = false;
            JSONObject result = new JSONObject();
            try {
                result = Account.getAccount().getConnection().rateLimitStatus();
                ok = (result != null);
            } catch (ConnectionException e) {
                Log.e(TAG, "rateLimitStatus Exception: " + e.toString());
            }

            if (ok) {
                synchronized(MyService.this) {
                    if (mStateRestored) {
                        for (int i = 0; i < mBroadcastListenerCount; i++) {
                            try {
                                IMyServiceCallback cb = mCallbacks.getBroadcastItem(i);
                                if (cb != null) {
                                    cb.rateLimitStatus(result.getInt("remaining_hits"),
                                            result.getInt("hourly_limit"));
                                }
                            } catch (RemoteException e) {
                                MyLog.d(TAG, e.toString());
                            } catch (JSONException e) {
                                Log.e(TAG, e.toString());
                            }
                        }
                    } else {
                        Log.e(TAG, "rateLimitStatus - " + SERVICE_NOT_RESTORED_TEXT);
                    }
                }        
            }
            return ok;
        }
    }

    private PowerManager.WakeLock getWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();
        return wakeLock;
    }

    /**
     * Returns the number of milliseconds between two fetch actions.
     * 
     * @return the number of milliseconds
     */
    private int getFetchFrequencyS() {
        int frequencyS = Integer.parseInt(getSp().getString("fetch_frequency", "180"));
        return (frequencyS * MILLISECONDS);
    }

    /**
     * Starts the repeating Alarm that sends the fetch Intent.
     */
    private boolean scheduleRepeatingAlarm() {
        final AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pIntent = getRepeatingIntent();
        final int frequencyMs = getFetchFrequencyS();
        final long firstTime = SystemClock.elapsedRealtime() + frequencyMs;
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, firstTime, frequencyMs, pIntent);
        MyLog.d(TAG, "Started repeating alarm in a " + frequencyMs + "ms rhythm.");
        return true;
    }

    /**
     * Cancels the repeating Alarm that sends the fetch Intent.
     */
    private boolean cancelRepeatingAlarm() {
        final AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pIntent = getRepeatingIntent();
        am.cancel(pIntent);
        MyLog.d(TAG, "Cancelled repeating alarm.");
        return true;
    }

    /**
     * Returns Intent to be send with Repeating Alarm.
     * This alarm will be received by {@link MyServiceManager}
     * @return the Intent
     */
    private PendingIntent getRepeatingIntent() {
        Intent intent = new Intent(ACTION_ALARM);
        intent.putExtra(MyService.EXTRA_MSGTYPE, CommandEnum.AUTOMATIC_UPDATE.save());
        PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
        return pIntent;
    }

    /**
     * The IMyService is defined through IDL
     */
    private final IMyService.Stub mBinder = new IMyService.Stub() {
        public void registerCallback(IMyServiceCallback cb) {
            if (cb != null)
                mCallbacks.register(cb);
        }

        public void unregisterCallback(IMyServiceCallback cb) {
            if (cb != null)
                mCallbacks.unregister(cb);
        }
    };

    /**
     * We use this function before actual requests of Internet services Based on
     * http
     * ://stackoverflow.com/questions/1560788/how-to-check-internet-access-on
     * -android-inetaddress-never-timeouts
     */
    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        // test for connection
        if (cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isAvailable()
                && cm.getActiveNetworkInfo().isConnected()) {
            return true;
        } else {
            MyLog.v(TAG, "Internet Connection Not Present");
            return false;
        }
    }
}
