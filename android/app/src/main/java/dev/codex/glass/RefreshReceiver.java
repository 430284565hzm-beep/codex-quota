package dev.codex.glass;
import android.content.*;
public final class RefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        // Supports pending intents from widgets created by an older installed version.
        try{c.startForegroundService(new Intent(c,WidgetSyncService.class));}
        catch(RuntimeException e){SyncJob.request(c);UsageWidget.updateAll(c);}
    }
}
