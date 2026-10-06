package dev.codex.glass;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** A short, user-initiated widget refresh. Automatic updates remain scheduled jobs. */
public final class WidgetSyncService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean syncing,finished;
    private final Runnable timeout=()->finishSync("本次同步超时，请点击刷新重试");
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("codex_widget_sync","桌面组件同步",NotificationManager.IMPORTANCE_LOW));
        Notification notice=new Notification.Builder(this,"codex_widget_sync").setSmallIcon(R.drawable.ic_notification).setContentTitle("正在更新 Codex 余量").setContentText("完成后自动结束").setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(504,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(504,notice);
        if(syncing&&!finished)return START_NOT_STICKY;
        syncing=true;finished=false;Repository r=GlassApp.repo(this);
        r.prefs.edit().putLong("widget_pending_at",System.currentTimeMillis()).apply();UsageWidget.updateAll(this);
        main.postDelayed(timeout,90000);
        r.refresh((message,ok)->finishSync(null));
        return START_NOT_STICKY;
    }
    private void finishSync(String error){
        if(finished)return;finished=true;main.removeCallbacks(timeout);
        Repository r=GlassApp.repo(this);if(error!=null)r.error=error;
        r.prefs.edit().remove("widget_pending_at").apply();UsageWidget.updateAll(this);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){main.removeCallbacks(timeout);finished=true;GlassApp.repo(this).prefs.edit().remove("widget_pending_at").apply();UsageWidget.updateAll(this);super.onDestroy();}
    @Override public void onTimeout(int startId,int fgsType){finishSync("同步已结束，请点击刷新重试");}
    @Override public IBinder onBind(Intent intent){return null;}
}
