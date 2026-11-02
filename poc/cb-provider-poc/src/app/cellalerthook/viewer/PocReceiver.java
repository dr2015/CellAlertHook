package app.cellalerthook.viewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Would log if the protected emergency broadcast ever reached a third-party app. */
public class PocReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(PocActivity.TAG, "MANIFEST RECEIVER GOT " + intent.getAction());
    }
}
