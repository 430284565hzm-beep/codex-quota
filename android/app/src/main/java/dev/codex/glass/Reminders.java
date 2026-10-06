package dev.codex.glass;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class Reminders {
    static final String CHANNEL="codex_account_reminders";
    static void evaluate(Repository repo,Model.Snapshot s){
        Context c=repo.context;String ns=repo.namespace();long now=System.currentTimeMillis()/1000;
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        if(repo.prefs.getBoolean("notify_low",false)){
            int threshold=repo.prefs.getInt("low_threshold",20);
            Model.Window[] windows={s.primary,s.secondary};
            for(int i=0;i<windows.length;i++){
                Model.Window w=windows[i];if(w==null||w.remaining()>threshold)continue;
                String key="notified_low_"+ns+"_"+i;
                if(repo.prefs.getLong(key,0)==w.resetsAt)continue;
                notify(c,101+i,"Codex 额度提醒",w.label()+"剩余 "+w.percent()+" · "+Model.countdown(w.resetsAt),"home");
                repo.prefs.edit().putLong(key,w.resetsAt).apply();
            }
        }
        if(repo.prefs.getBoolean("notify_reset",false)){
            Model.Window[] windows={s.primary,s.secondary};
            for(int i=0;i<windows.length;i++){
                Model.Window w=windows[i];if(w==null)continue;
                String key="last_reset_"+ns+"_"+i;
                long previous=repo.prefs.getLong(key,0);
                if(previous>0&&previous<=now&&w.resetsAt>now&&w.used<5)notify(c,111+i,"Codex 额度已恢复",w.label()+"已更新，可以继续工作了","home");
                repo.prefs.edit().putLong(key,w.resetsAt).apply();
            }
        }
        if(repo.prefs.getBoolean("notify_expiry",false)&&s.count!=null&&s.count>0){
            Model.Credit first=s.firstCredit();if(first!=null&&first.expiresAt>now&&first.expiresAt-now<=3*86400){
                String key="notified_expiry_"+ns;
                if(!first.id.equals(repo.prefs.getString(key,""))){
                    notify(c,120,"有一张重置券即将到期","最早到期："+Model.date(first.expiresAt)+"，可在需要时使用","bank");
                    repo.prefs.edit().putString(key,first.id).apply();
                }
            }
        }
    }
    private static void notify(Context c,int id,String title,String text,String screen){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"额度与重置券提醒",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(c,MainActivity.class).putExtra("screen",screen);
        PendingIntent open=PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build();
        manager.notify(id,n);
    }
}
