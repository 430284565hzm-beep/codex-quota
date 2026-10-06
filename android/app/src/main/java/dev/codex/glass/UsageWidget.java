package dev.codex.glass;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.*;
import java.io.File;

public class UsageWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){
        for(int id:ids)update(c,manager,id,this instanceof CapsuleWidget);SyncJob.schedule(c);
        Repository r=GlassApp.repo(c);long interval=Math.max(15,r.prefs.getInt("background_minutes",15))*60L;
        if(r.api.signedIn()&&(r.snapshot==null||System.currentTimeMillis()/1000-r.snapshot.fetchedAt>=interval))SyncJob.request(c);
    }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,Bundle options){update(c,m,id,this instanceof CapsuleWidget);}
    static void updateAll(Context c){
        AppWidgetManager m=AppWidgetManager.getInstance(c);
        for(int id:m.getAppWidgetIds(new ComponentName(c,UsageWidget.class)))update(c,m,id,false);
        for(int id:m.getAppWidgetIds(new ComponentName(c,CapsuleWidget.class)))update(c,m,id,true);
    }
    static void update(Context c,AppWidgetManager m,int id,boolean capsule){
        Bundle options=m.getAppWidgetOptions(id);int w=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,capsule?230:320),h=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,capsule?80:190);
        boolean vertical=w<190&&h>=165;
        RemoteViews views=views(c,w,h,capsule,vertical);m.updateAppWidget(id,views);
    }
    static RemoteViews views(Context c,int width,int height,boolean capsule,boolean vertical){
        Repository r=GlassApp.repo(c);Model.Snapshot s=r.snapshot;
        int layout=vertical?R.layout.widget_vertical:capsule?R.layout.widget_capsule:R.layout.widget_card;
        RemoteViews v=new RemoteViews(c.getPackageName(),layout);
        boolean privacy=r.prefs.getBoolean("widget_privacy",false);boolean signed=r.api.signedIn()||r.demo;
        int ink=Glass.ink(c),muted=Glass.secondary(c);
        for(int id:new int[]{R.id.widget_primary,R.id.widget_secondary})v.setTextColor(id,ink);
        for(int id:new int[]{R.id.widget_primary_label,R.id.widget_secondary_label})v.setTextColor(id,muted);
        Model.Window a=s==null?null:s.primary,b=s==null?null:s.secondary;
        v.setTextViewText(R.id.widget_primary,privacy?"••":a==null?"—":a.percent());v.setTextViewText(R.id.widget_secondary,privacy?"••":b==null?"—":b.percent());
        v.setTextViewText(R.id.widget_primary_label,a==null?"当前窗口":a.label());v.setTextViewText(R.id.widget_secondary_label,b==null?"长周期":b.label());
        v.setImageViewBitmap(R.id.widget_material,material(c,Math.max(100,width),Math.max(capsule?68:120,height),capsule));
        Intent home=new Intent(c,MainActivity.class).putExtra("screen","home");
        Intent bank=new Intent(c,MainActivity.class).putExtra("screen","bank");
        v.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,301,home,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Intent refresh=new Intent(c,WidgetSyncService.class);
        PendingIntent refreshIntent=signed?PendingIntent.getForegroundService(c,304,refresh,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE):PendingIntent.getActivity(c,301,home,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_refresh,refreshIntent);
        v.setInt(R.id.widget_refresh,"setColorFilter",ink);
        v.setContentDescription(R.id.widget_primary,a==null?"当前窗口尚未同步":"当前窗口剩余 "+(privacy?"已隐藏":a.percent()));
        v.setContentDescription(R.id.widget_secondary,b==null?"长周期尚未同步":"长周期剩余 "+(privacy?"已隐藏":b.percent()));
        if(!capsule||vertical){
            v.setTextColor(R.id.widget_title,ink);v.setTextColor(R.id.widget_bank,Glass.accent(c));
            v.setTextViewText(R.id.widget_title,r.demo?"余量 · 体验":"Codex 余量");
            v.setTextViewText(R.id.widget_bank,!signed?"登录":privacy?"储备 ••":"储备 "+(s==null?"—":s.countText()));
            v.setOnClickPendingIntent(R.id.widget_bank,PendingIntent.getActivity(c,302,bank,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        }
        if(!capsule&&!vertical){
            v.setTextColor(R.id.widget_age,muted);v.setTextColor(R.id.widget_primary_reset,muted);v.setTextColor(R.id.widget_secondary_reset,muted);
            long requested=r.prefs.getLong("widget_pending_at",0);boolean pending=r.busy.get()||(requested>0&&System.currentTimeMillis()-requested<90000);
            String age=!signed?"点击登录":pending?"正在同步…":!r.error.isEmpty()?"同步失败 · 点击刷新":r.demo?"体验数据":s==null?"尚未同步":Model.age(s.fetchedAt);
            v.setTextViewText(R.id.widget_age,age);
            v.setTextViewText(R.id.widget_primary_reset,a==null?"登录后查看":Model.countdown(a.resetsAt));v.setTextViewText(R.id.widget_secondary_reset,b==null?"登录后查看":Model.countdown(b.resetsAt));
            v.setImageViewBitmap(R.id.widget_primary_meter,meter(c,privacy?-1:a==null?-1:a.remaining()));v.setImageViewBitmap(R.id.widget_secondary_meter,meter(c,privacy?-1:b==null?-1:b.remaining()));
        }
        return v;
    }
    static Bitmap meter(Context c,double value){Bitmap b=Bitmap.createBitmap(256,12,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Glass.alpha(Glass.ink(c),.1f));cv.drawRoundRect(new RectF(0,0,256,12),6,6,p);if(value>=0){p.setColor(value<=20?0xFFC18960:Glass.accent(c));cv.drawRoundRect(new RectF(0,0,(float)(256*value/100),12),6,6,p);}return b;}
    static Bitmap material(Context c,int width,int height,boolean capsule){
        float scale=Math.min(2f,Math.min(560f/width,360f/height));int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(result);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float pad=3*scale,radius=(capsule?Math.min(width,height)/2f:28)*scale;RectF bounds=new RectF(pad,pad,w-pad,h-pad);boolean dark=Glass.dark(c);
        p.setColor(dark?0x8D1B2A35:0x8AC2D1D3);p.setShadowLayer(3*scale,0,2*scale,dark?0x45000000:0x1F31464D);cv.drawRoundRect(bounds,radius,radius,p);p.clearShadowLayer();
        cv.saveLayer(bounds,null);
        Bitmap photo=null;File f=new File(c.getFilesDir(),"background.jpg");
        if(f.exists())try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),o);o.inJustDecodeBounds=false;o.inSampleSize=Math.max(1,Math.max(o.outWidth,o.outHeight)/800);photo=BitmapFactory.decodeFile(f.getAbsolutePath(),o);}catch(Exception ignored){}
        if(photo!=null){Bitmap blurred=Glass.Backdrop.blur(photo,Math.max(12,photo.getWidth()/32));float crop=Math.max(w/(float)blurred.getWidth(),h/(float)blurred.getHeight());float bw=blurred.getWidth()*crop,bh=blurred.getHeight()*crop;cv.drawBitmap(blurred,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),p);photo.recycle();blurred.recycle();}
        p.setShader(new LinearGradient(0,0,w,h,dark?0xE0374B58:0xBCEFF6F7,dark?0xCC243641:0x91D4E7E4,Shader.TileMode.CLAMP));cv.drawRect(bounds,p);p.setShader(null);
        Bitmap mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas maskCanvas=new Canvas(mask);Paint maskPaint=new Paint(Paint.ANTI_ALIAS_FLAG);maskPaint.setColor(Color.WHITE);maskCanvas.drawRoundRect(bounds,radius,radius,maskPaint);
        p.setShader(null);p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));p.setColor(Color.WHITE);cv.drawBitmap(mask,0,0,p);p.setXfermode(null);cv.restore();mask.recycle();
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1*scale);p.setShader(new LinearGradient(0,0,w,h,dark?0x69FFFFFF:0xF4FFFFFF,dark?0x19FFFFFF:0x64FFFFFF,Shader.TileMode.CLAMP));cv.drawRoundRect(bounds,radius,radius,p);return result;
    }
}
