package app.cellalerthook.viewer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;
import java.util.List;

/** Logs every observation with tag CellAlertHookPoc so the result can be read with logcat. */
public class PocActivity extends Activity {
    static final String TAG = "CellAlertHookPoc";
    private static final Uri ROOT = Uri.parse("content://cellbroadcasts");
    private static final Uri HISTORY = Uri.parse("content://cellbroadcasts/history");
    private static final String READ = "android.permission.READ_CELL_BROADCASTS";
    private static final String EMERGENCY = "android.permission.RECEIVE_EMERGENCY_BROADCAST";
    private static final String ACTION_EMERGENCY = "android.provider.action.SMS_EMERGENCY_CB_RECEIVED";
    private static final String ACTION_CB = "android.provider.Telephony.SMS_CB_RECEIVED";

    private TextView output;

    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            report("OBSERVER onChange uri=" + uri);
            dumpHistory();
        }
    };

    private final BroadcastReceiver dynamicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            report("DYNAMIC RECEIVER GOT " + intent.getAction());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setPadding(24, 24, 24, 24);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);

        report("device sdk=" + Build.VERSION.SDK_INT + " type=" + Build.TYPE + " tags=" + Build.TAGS);
        reportPermission(READ);
        reportPermission(EMERGENCY);
        reportProvider();
        reportReceivers();
        probeQuery("ROOT", ROOT);
        probeQuery("HISTORY", HISTORY);
        listHistory();
        registerObserver();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_EMERGENCY);
        filter.addAction(ACTION_CB);
        registerReceiver(dynamicReceiver, filter, Context.RECEIVER_EXPORTED);
        report("dynamic receiver registered");
    }

    private void reportPermission(String name) {
        boolean granted = checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED;
        String level = "undefined";
        try {
            PermissionInfo info = getPackageManager().getPermissionInfo(name, 0);
            level = "protection=" + info.getProtection() + " flags=0x"
                    + Integer.toHexString(info.getProtectionFlags());
        } catch (PackageManager.NameNotFoundException ignored) {
            // The name stays "undefined".
        }
        report("permission " + name + " granted=" + granted + " " + level);
    }

    private void reportProvider() {
        ProviderInfo info = getPackageManager().resolveContentProvider("cellbroadcasts", 0);
        report(info == null
                ? "provider cellbroadcasts NOT RESOLVED"
                : "provider cellbroadcasts pkg=" + info.packageName + " readPermission=" + info.readPermission
                        + " exported=" + info.exported);
    }

    private void reportReceivers() {
        List<ResolveInfo> receivers = getPackageManager()
                .queryBroadcastReceivers(new Intent(ACTION_EMERGENCY), 0);
        for (ResolveInfo receiver : receivers) {
            report("emergency receiver: " + receiver.activityInfo.packageName + "/" + receiver.activityInfo.name);
        }
    }

    private void probeQuery(String label, Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            report("query " + label + " OK rows=" + (cursor == null ? "null" : cursor.getCount()));
        } catch (RuntimeException e) {
            report("query " + label + " DENIED " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Shows what Android still holds, newest first. Read only: nothing is stored or sent. */
    private void listHistory() {
        try (Cursor cursor = getContentResolver().query(HISTORY, null, null, null, "received_time DESC")) {
            if (cursor == null) return;
            report("ALERTS IN HISTORY: " + cursor.getCount());
            while (cursor.moveToNext()) {
                report("ALERT " + new Date(column(cursor, "received_time", 0L))
                        + " channel=" + column(cursor, "service_category", -1L)
                        + " lang=" + cursor.getString(cursor.getColumnIndexOrThrow("language"))
                        + " serial=" + column(cursor, "serial_number", -1L)
                        + "\n" + cursor.getString(cursor.getColumnIndexOrThrow("body")) + "\n");
            }
        } catch (RuntimeException e) {
            report("list DENIED " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long column(Cursor cursor, String name, long missing) {
        int index = cursor.getColumnIndex(name);
        return index < 0 || cursor.isNull(index) ? missing : cursor.getLong(index);
    }

    private void registerObserver() {
        try {
            getContentResolver().registerContentObserver(ROOT, true, observer);
            report("content observer registered");
        } catch (RuntimeException e) {
            report("content observer DENIED " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void dumpHistory() {
        try (Cursor cursor = getContentResolver().query(HISTORY, null, null, null, "received_time DESC")) {
            if (cursor == null) {
                report("history cursor null");
                return;
            }
            report("history rows=" + cursor.getCount());
            if (cursor.moveToFirst()) {
                StringBuilder row = new StringBuilder();
                for (String column : cursor.getColumnNames()) {
                    row.append(column).append('=').append(cursor.getString(cursor.getColumnIndex(column))).append(" | ");
                }
                report("latest " + row);
            }
        } catch (RuntimeException e) {
            report("history DENIED " + e.getMessage());
        }
    }

    private void report(String line) {
        Log.i(TAG, line);
        if (output != null) output.append(line + "\n");
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        unregisterReceiver(dynamicReceiver);
        super.onDestroy();
    }
}
