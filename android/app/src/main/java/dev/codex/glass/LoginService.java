package dev.codex.glass;
import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Keeps the phone's loopback callback and device polling alive while its browser is open. */
public final class LoginService extends Service {
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        NotificationManager m=getSystemService(NotificationManager.class);m.createNotificationChannel(new NotificationChannel("codex_sign_in","登录状态",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,501,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"codex_sign_in").setSmallIcon(R.drawable.ic_notification).setContentTitle("正在登录 ChatGPT").setContentText("完成浏览器授权后，返回 Codex 余量").setContentIntent(open).setOngoing(true).build();
        startForeground(502,n);
        if(!GlassApp.repo(this).login.active)stopSelf();return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTimeout(int startId,int fgsType){GlassApp.repo(this).login.cancel();stopSelf();}
}
