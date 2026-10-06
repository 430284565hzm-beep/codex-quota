package dev.codex.glass;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){SyncJob.schedule(c);UsageWidget.updateAll(c);}
}
