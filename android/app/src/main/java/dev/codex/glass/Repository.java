package dev.codex.glass;

import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

final class Repository {
    interface Listener{void changed();}
    interface Completion{void done(String message,boolean success);}
    final Context context;
    final SharedPreferences prefs;
    final CodexApi api;
    final LoginFlow login;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    final AtomicBoolean busy=new AtomicBoolean();
    private final Object refreshLock=new Object();
    private final List<Completion> refreshWaiters=new ArrayList<>();
    private boolean refreshing;
    volatile Model.Snapshot snapshot;
    volatile String error="";
    volatile boolean demo,authIssue;
    Repository(Context c){this(c,new CodexApi(c));}
    Repository(Context c,CodexApi client){
        context=c.getApplicationContext();prefs=c.getSharedPreferences("preferences",Context.MODE_PRIVATE);api=client;login=new LoginFlow(api,context);
        demo=prefs.getBoolean("demo",false)&&!api.signedIn();load();
    }
    void add(Listener l){listeners.add(l);}
    void remove(Listener l){listeners.remove(l);}
    private void emit(){main.post(()->{for(Listener l:listeners)l.changed();});}
    String namespace(){return demo?"demo":digest(api.accountKey());}
    static String digest(String key){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b));return s.toString();}catch(Exception ignored){return "unknown";}}
    private void load(){
        snapshot=null;
        if(demo){snapshot=demoSnapshot();return;}
        if(!api.signedIn())return;
        try{String data=prefs.getString("snapshot_"+namespace(),null);if(data!=null)snapshot=Model.parse(new JSONObject(data),0);}catch(Exception ignored){}
    }
    void setDemo(boolean enable){if(api.signedIn())return;demo=enable;prefs.edit().putBoolean("demo",enable).apply();error="";load();emit();UsageWidget.updateAll(context);}
    void loggedIn(){demo=false;prefs.edit().putBoolean("demo",false).apply();error="";authIssue=false;load();emit();refresh(null);SyncJob.schedule(context);}
    void logout(Completion complete){
        login.cancel();worker.execute(()->{api.logout();demo=false;snapshot=null;authIssue=false;error="";prefs.edit().putBoolean("demo",false).apply();emit();UsageWidget.updateAll(context);main.post(()->complete.done("已退出登录",true));});
    }
    void refresh(Completion complete){
        if(!demo&&!api.signedIn()){if(complete!=null)main.post(()->complete.done("请先登录 ChatGPT",false));return;}
        synchronized(refreshLock){
            if(refreshing){if(complete!=null)refreshWaiters.add(complete);return;}
            if(!busy.compareAndSet(false,true)){if(complete!=null)main.post(()->complete.done("正在确认重置，请稍后刷新",false));return;}
            refreshing=true;if(complete!=null)refreshWaiters.add(complete);
        }
        emit();worker.execute(()->{
            String message=null;boolean success=false;
            try{
                Model.Snapshot next=demo?demoSnapshot():api.readUsage();
                if(demo)next.fetchedAt=System.currentTimeMillis()/1000;
                save(next);error="";authIssue=false;success=true;
                if(!demo)Reminders.evaluate(this,next);
            }catch(Exception e){error=friendly(e);if(e instanceof CodexApi.ApiError&&((CodexApi.ApiError)e).status==401)authIssue=true;message=error;}
            finally{
                List<Completion> waiting;
                synchronized(refreshLock){waiting=new ArrayList<>(refreshWaiters);refreshWaiters.clear();refreshing=false;busy.set(false);}
                emit();UsageWidget.updateAll(context);String result=message;boolean ok=success;main.post(()->{for(Completion callback:waiting)callback.done(result,ok);});
            }
        });
    }
    private void save(Model.Snapshot next) throws Exception {
        prefs.edit().putString("snapshot_"+namespace(),next.json().toString()).apply();snapshot=next;
        JSONArray history=history();long now=next.fetchedAt;
        JSONObject last=history.length()==0?null:history.optJSONObject(history.length()-1);
        double value=next.primary==null?-1:next.primary.remaining();
        if(last==null||now-last.optLong("at",0)>=900||(now-last.optLong("at",0)>=120&&Math.abs(last.optDouble("primary",-1)-value)>=1)){
            JSONArray keep=new JSONArray();for(int i=0;i<history.length();i++){JSONObject row=history.optJSONObject(i);if(row!=null&&row.optLong("at",0)>now-7*86400L&&keep.length()<2000)keep.put(row);}
            keep.put(new JSONObject().put("at",now).put("primary",value).put("secondary",next.secondary==null?-1:next.secondary.remaining()));
            prefs.edit().putString("history_"+namespace(),keep.toString()).apply();
        }
    }
    JSONArray history(){try{return new JSONArray(prefs.getString("history_"+namespace(),"[]"));}catch(Exception e){return new JSONArray();}}
    void clearHistory(){prefs.edit().remove("history_"+namespace()).apply();emit();}
    String pendingId(){return prefs.getString("redeem_id_"+namespace(),"");}
    void consume(Completion complete){
        if(demo){Model.Snapshot preview=demoSnapshot();preview.primary=new Model.Window(0,preview.primary.seconds,preview.primary.resetsAt);preview.secondary=new Model.Window(0,preview.secondary.seconds,preview.secondary.resetsAt);preview.count=Math.max(0,(snapshot==null||snapshot.count==null?2:snapshot.count)-1);snapshot=preview;complete.done("体验模式 · 未兑换真实重置券",true);return;}
        if(!api.signedIn()){complete.done("请先登录 ChatGPT",false);return;}
        if(!busy.compareAndSet(false,true)){complete.done("正在同步，请稍候再确认",false);return;}
        final String account=namespace();
        String existing=pendingId();final String id=existing.isEmpty()?UUID.randomUUID().toString():existing;
        Model.Credit first=snapshot==null?null:snapshot.firstCredit();
        final String credit=existing.isEmpty()?(first==null?"":first.id):prefs.getString("redeem_credit_"+account,"");
        if(!prefs.edit().putString("redeem_id_"+account,id).putString("redeem_credit_"+account,credit).commit()){
            busy.set(false);complete.done("无法保存兑换编号，请重试",false);return;
        }
        emit();worker.execute(()->{
            String message;boolean success=false;
            try{
                String outcome=api.consume(id,credit);
                // Only a definitive server result clears this logical request.
                prefs.edit().remove("redeem_id_"+account).remove("redeem_credit_"+account).commit();
                if("reset".equals(outcome)){message="已使用 1 次储备，额度已重置";success=true;}
                else if("already_redeemed".equals(outcome)){message="上次兑换已完成，没有重复消耗";success=true;}
                else if("nothing_to_reset".equals(outcome))message="当前没有可重置的额度窗口，重置券未消耗";
                else message="当前没有可用的重置券";
                try{save(api.readUsage());error="";authIssue=false;}
                catch(Exception e){error=friendly(e);message+="；额度暂未同步，请稍后刷新";}
            }catch(Exception e){message=friendly(e)+"。结果待确认，再次点击将使用原编号重试";error=message;}
            finally{busy.set(false);emit();UsageWidget.updateAll(context);}
            String result=message;boolean ok=success;main.post(()->complete.done(result,ok));
        });
    }
    static String friendly(Exception e){
        if(e instanceof java.net.UnknownHostException)return "无法连接 Codex，请检查手机网络";
        if(e instanceof java.net.SocketTimeoutException)return "连接超时，请稍后刷新";
        if(e instanceof javax.net.ssl.SSLException)return "安全连接未建立，请检查手机时间与网络";
        if(e instanceof java.net.ConnectException)return "无法连接服务，请检查手机网络";
        return e.getMessage()==null?"同步未完成，请重试":e.getMessage();
    }
    static Model.Snapshot demoSnapshot(){
        Model.Snapshot s=new Model.Snapshot();long now=System.currentTimeMillis()/1000;
        s.primary=new Model.Window(26,18000,now+8520);s.secondary=new Model.Window(62,604800,now+3*86400+3600);
        s.plan="plus";s.count=2;s.detailsKnown=true;s.fetchedAt=now;
        s.credits.add(new Model.Credit("demo-only","储备重置",now+22*86400));return s;
    }
}
