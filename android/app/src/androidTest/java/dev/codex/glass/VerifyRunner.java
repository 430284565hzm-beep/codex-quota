package dev.codex.glass;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.nio.charset.StandardCharsets;
import android.util.Base64;

/** Installed only on the local clean emulator. Never uses a real redemption endpoint. */
public final class VerifyRunner extends Instrumentation {
    private final JSONArray passed=new JSONArray();
    private Bundle args;
    private Context target;
    private void check(boolean good,String name) throws Exception {if(!good)throw new Exception(name);passed.put(name);Bundle progress=new Bundle();progress.putString("stream",name);sendStatus(0,progress);}
    @Override public void onCreate(Bundle input){super.onCreate(input);args=input;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();target=getTargetContext();
        try{
            waitForIdleSync();
            if("true".equals(args.getString("backgroundOnly"))){
                background();result.putString("stream",new JSONObject().put("passed",passed).put("count",passed.length()).put("realRedemptions",0).toString());finish(Activity.RESULT_OK,result);return;
            }
            if("true".equals(args.getString("loginOnly"))){
                callbackPorts();identity();
                result.putString("stream",new JSONObject().put("passed",passed).put("count",passed.length()).put("realRedemptions",0).toString());finish(Activity.RESULT_OK,result);return;
            }
            if(!"true".equals(args.getString("uiOnly"))){parser();vault();identity();redemption();widgets();}
            else if("true".equals(args.getString("widgetLayout")))widgets();
            final MainActivity[] view=new MainActivity[1];
            runOnMainSync(()->{Repository r=GlassApp.repo(target);r.setDemo(true);r.prefs.edit().putString("theme","light").putBoolean("motion",false).putBoolean("reset_sound",false).putBoolean("reset_confetti",true).apply();});
            view[0]=(MainActivity)startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            Thread.sleep(850);
            MainActivity a=view[0];
            check(a.root.getWidth()>0&&a.primaryValue!=null&&a.primaryValue.getText().toString().equals("74%"),"dashboard_renders_known_quota");
            check(a.resetSound.ready(),"both_original_pcm_cues_predecoded_before_reset");
            check(a.celebration.progress==1&&!a.celebration.isClickable(),"celebration_idle_and_does_not_intercept_taps");
            screenshot("dashboard.png");
            runOnMainSync(a::showBank);Thread.sleep(500);check(a.dialog!=null&&a.dialog.isShowing(),"confirmation_sheet_opens_without_redemption");screenshot("bank.png");
            runOnMainSync(()->a.bankConfirm.performClick());Thread.sleep(350);
            runOnMainSync(()->{a.primaryMeter.animator.pause();a.secondaryMeter.animator.pause();a.celebration.animator.pause();});
            check(!a.dialog.isShowing()&&a.primaryMeter.restoring&&a.secondaryMeter.restoring,"reset_reveals_two_real_quota_meters");
            check(a.celebration.confetti&&a.celebration.progress<1,"classic_halo_and_confetti_join_confirmed_restoration");
            float previousP=74,previousS=38;
            float previousFraction=0,previousPForCurve=74,previousSpeed=Float.MAX_VALUE;
            for(float fraction:new float[]{.15f,.5f,.85f}){
                final float at=fraction;runOnMainSync(()->{a.primaryMeter.animator.setCurrentPlayTime((long)(Glass.Meter.RESTORE_MS*at));a.secondaryMeter.animator.setCurrentPlayTime((long)(Glass.Meter.RESTORE_MS*at));a.celebration.animator.setCurrentPlayTime((long)(Glass.Meter.RESTORE_MS*at));});
                float p=a.primaryMeter.value,s=a.secondaryMeter.value;check(p>previousP&&s>previousS&&p<100&&s<100,"two_quota_fills_intermediate_"+fraction);previousP=p;previousS=s;
                float speed=(p-previousPForCurve)/(fraction-previousFraction);
                check(speed<previousSpeed,"quota_fill_gently_decelerates_"+fraction);previousSpeed=speed;previousFraction=fraction;previousPForCurve=p;
                check(a.primaryValue.getText().toString().equals(Math.round(p)+"%")&&a.secondaryValue.getText().toString().equals(Math.round(s)+"%"),"percentages_follow_quota_fills_"+fraction);
                waitForIdleSync();Thread.sleep(400);screenshot("reset-"+Math.round(fraction*100)+".png");
                if(fraction==.15f)verifyPaper(a);
            }
            runOnMainSync(()->{a.primaryMeter.animator.end();a.secondaryMeter.animator.end();a.celebration.animator.end();});
            check(a.primaryMeter.value==100&&a.secondaryMeter.value==100&&!a.resetRestoring&&!a.dialog.isShowing(),"two_quota_fills_finish_without_extra_loading_bar");Thread.sleep(100);screenshot("reset-complete.png");
            check(a.celebration.progress==1,"celebration_finishes_without_stuck_light_or_paper");
            runOnMainSync(()->{a.celebration.play(a.root.getWidth()/2f,a.root.getHeight()/3f,false);a.celebration.animator.pause();a.celebration.animator.setCurrentPlayTime(200);});
            check(!a.celebration.confetti&&a.celebration.progress<1,"classic_effect_retained_when_confetti_disabled");
            runOnMainSync(a.celebration::stop);
            check(GlassApp.repo(target).demo&&GlassApp.repo(target).snapshot.count==1,"reset_preview_uses_only_demo_quota_and_credits");
            runOnMainSync(()->{a.dialog.dismiss();a.tab="history";a.build();});waitForIdleSync();screenshot("history.png");
            runOnMainSync(()->{a.tab="settings";a.build();});waitForIdleSync();screenshot("settings.png");
            runOnMainSync(()->{GlassApp.repo(target).prefs.edit().putString("theme","dark").apply();a.tab="home";a.build();a.backdrop.reload();});waitForIdleSync();Thread.sleep(700);screenshot("dashboard-dark.png");
            runOnMainSync(()->{GlassApp.repo(target).prefs.edit().putString("theme","light").apply();GlassApp.repo(target).setDemo(false);a.build();a.backdrop.reload();});waitForIdleSync();Thread.sleep(500);screenshot("onboarding.png");
            check(!GlassApp.repo(target).api.signedIn(),"no_live_session_or_user_auth_embedded");
            runOnMainSync(()->{GlassApp.repo(target).prefs.edit().remove("reset_sound").remove("reset_confetti").apply();a.finish();});
            result.putString("stream",new JSONObject().put("passed",passed).put("count",passed.length()).put("realRedemptions",0).toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream",new JSONObject().toString()+" FAILURE: "+e.getClass().getSimpleName()+" "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}
    }
    private void verifyPaper(MainActivity activity) throws Exception {
        final int[] difference={0},halo={0};
        runOnMainSync(()->{
            Glass.Celebration view=activity.celebration;Bitmap full=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888),classic=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(full));view.confetti=false;view.draw(new Canvas(classic));view.confetti=true;
            int[] a=new int[view.getWidth()*view.getHeight()],b=new int[a.length];full.getPixels(a,0,view.getWidth(),0,0,view.getWidth(),view.getHeight());classic.getPixels(b,0,view.getWidth(),0,0,view.getWidth(),view.getHeight());
            for(int i=0;i<a.length;i++){if(a[i]!=b[i])difference[0]++;if(Color.alpha(b[i])>0)halo[0]++;}full.recycle();classic.recycle();
        });
        check(difference[0]>40&&halo[0]>40,"native_canvas_renders_both_classic_halo_and_separate_paper");
    }
    private void parser() throws Exception {
        Model.Snapshot s=Model.parse(new JSONObject("{\"rate_limit\":{\"primary_window\":{\"used_percent\":36,\"limit_window_seconds\":18000,\"reset_at\":1900000000},\"secondary_window\":null},\"rate_limit_reset_credits\":{\"available_count\":5,\"credits\":[]}}"),1);
        check(s.primary.remaining()==64&&s.secondary==null&&s.count==5&&s.credits.size()==0,"wire_windows_and_authoritative_count");
        check(Model.parse(new JSONObject(),1).count==null&&Model.window(new JSONObject())==null,"unknown_values_stay_unknown");
        check(new Model.Window(140,0,0).remaining()==0&&new Model.Window(-4,0,0).remaining()==100,"percent_bounds");
        Model.applyCredits(s,new JSONObject("{\"available_count\":5,\"credits\":[{\"id\":\"later\",\"status\":\"available\",\"expires_at\":\"2030-03-01T00:00:00Z\"},{\"id\":\"first\",\"status\":\"available\",\"expires_at\":\"2030-01-01T00:00:00Z\"},{\"id\":\"spent\",\"status\":\"redeemed\"}]}"));
        check("first".equals(s.firstCredit().id)&&s.count==5&&s.credits.size()==2,"expiry_sort_status_filter_and_count");
        check(Model.parse(s.json(),1).firstCredit().expiresAt==s.firstCredit().expiresAt,"cached_snapshot_preserves_expiry");
        check(CodexApi.form("state","a&b=c").equals("state=a%26b%3Dc"),"oauth_values_encoded");
        String verifier=LoginFlow.random();check(verifier.length()>=43&&!verifier.contains("=")&&!verifier.contains("+"),"pkce_random_verifier_format");
    }
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int read;while((read=in.read(buffer))!=-1)out.write(buffer,0,read);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static class BackgroundApi extends CodexApi {
        final java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);volatile boolean fail;
        BackgroundApi(Context c){super(c);}
        void prepare(boolean error){entered=new CountDownLatch(1);release=new CountDownLatch(1);fail=error;}
        @Override boolean signedIn(){return true;}
        @Override String accountKey(){return "widget-background-fixture-only";}
        @Override Model.Snapshot readUsage() throws Exception {
            reads.incrementAndGet();entered.countDown();release.await(30,TimeUnit.SECONDS);if(fail)throw new java.net.SocketTimeoutException();
            Model.Snapshot s=Repository.demoSnapshot();s.primary=new Model.Window(18,18000,s.primary.resetsAt);s.secondary=new Model.Window(56,604800,s.secondary.resetsAt);return s;
        }
        @Override String consume(String id,String credit){throw new AssertionError("Background refresh must never redeem a credit");}
    }
    private boolean widgetClick() throws Exception {
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        android.view.accessibility.AccessibilityNodeInfo node=findWidgetRefresh(root);
        if(node!=null){Rect bounds=new Rect();node.getBoundsInScreen(bounds);if(!bounds.isEmpty()){shell("input tap "+bounds.centerX()+" "+bounds.centerY());return true;}}
        return false;
    }
    private android.view.accessibility.AccessibilityNodeInfo findWidgetRefresh(android.view.accessibility.AccessibilityNodeInfo node){
        if(node.isVisibleToUser()&&("dev.codex.glass:id/widget_refresh".equals(node.getViewIdResourceName())||"立即刷新用量".contentEquals(node.getContentDescription()==null?"":node.getContentDescription())))return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null){android.view.accessibility.AccessibilityNodeInfo found=findWidgetRefresh(child);if(found!=null)return found;}}return null;
    }
    private boolean syncServiceRunning(){
        for(ActivityManager.RunningServiceInfo service:target.getSystemService(ActivityManager.class).getRunningServices(30))if(service.service.getClassName().equals(WidgetSyncService.class.getName()))return true;return false;
    }
    private void waitForWidget(Repository r) throws Exception {long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end&&(r.busy.get()||syncServiceRunning()||r.prefs.contains("widget_pending_at")))Thread.sleep(50);}
    private void background() throws Exception {
        GlassApp app=(GlassApp)target.getApplicationContext();Repository original=app.repo;BackgroundApi api=new BackgroundApi(target);Repository r=new Repository(target,api);r.demo=false;r.snapshot=Repository.demoSnapshot();
        java.util.concurrent.atomic.AtomicInteger activities=new java.util.concurrent.atomic.AtomicInteger();
        Application.ActivityLifecycleCallbacks watch=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){if(a instanceof MainActivity)activities.incrementAndGet();}
            public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        app.registerActivityLifecycleCallbacks(watch);
        try{
            runOnMainSync(()->{app.repo=r;r.prefs.edit().putBoolean("demo",false).putInt("background_minutes",15).putString("theme","light").remove("widget_pending_at").apply();UsageWidget.updateAll(target);SyncJob.schedule(target);});
            android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
            shell("input keyevent KEYCODE_HOME");Thread.sleep(600);
            boolean clicked=widgetClick();if(!clicked){shell("input swipe 960 1100 120 1100 400");Thread.sleep(600);clicked=widgetClick();}if(!clicked){shell("input swipe 120 1100 960 1100 400");Thread.sleep(600);clicked=widgetClick();}
            check(clicked,"native_launcher_widget_refresh_button_clicked");
            check(api.entered.await(8,TimeUnit.SECONDS)&&syncServiceRunning(),"widget_tap_starts_direct_sync_without_activity");
            check(r.prefs.contains("widget_pending_at"),"widget_shows_sync_in_progress");
            check(widgetClick(),"second_native_widget_tap");Thread.sleep(150);check(api.reads.get()==1,"duplicate_widget_taps_share_one_request");
            api.release.countDown();waitForWidget(r);
            check(r.snapshot.primary.remaining()==82&&r.snapshot.secondary.remaining()==44,"widget_refresh_updates_both_cached_quotas");
            check(!r.prefs.contains("widget_pending_at")&&!syncServiceRunning(),"manual_sync_clears_pending_and_stops_service");screenshot("widget-refreshed-without-app.png");
            api.prepare(true);int before=api.reads.get();check(widgetClick()&&api.entered.await(8,TimeUnit.SECONDS),"widget_retry_runs_from_launcher");api.release.countDown();waitForWidget(r);
            check(api.reads.get()==before+1&&r.snapshot.primary.remaining()==82&&!r.error.isEmpty()&&!r.prefs.contains("widget_pending_at")&&!syncServiceRunning(),"failed_widget_sync_keeps_data_and_clears_loading");
            api.prepare(false);before=api.reads.get();CountDownLatch shared=new CountDownLatch(2);
            runOnMainSync(()->{r.refresh((m,ok)->{if(ok)shared.countDown();});r.refresh((m,ok)->{if(ok)shared.countDown();});});check(api.entered.await(5,TimeUnit.SECONDS),"concurrent_refresh_started");api.release.countDown();check(shared.await(8,TimeUnit.SECONDS)&&api.reads.get()==before+1,"concurrent_refreshes_receive_same_successful_read");
            api.prepare(false);before=api.reads.get();String dispatch=shell("cmd jobscheduler run -f dev.codex.glass 1601");check(dispatch.contains("Running job")&&api.entered.await(8,TimeUnit.SECONDS),"periodic_job_refreshes_without_app");
            runOnMainSync(()->{SyncJob.schedule(target);SyncJob.schedule(target);SyncJob.schedule(target);});Thread.sleep(150);
            check(shell("cmd jobscheduler get-job-state dev.codex.glass 1601").contains("active"),"matching_schedule_keeps_running_periodic_job");
            api.release.countDown();waitForWidget(r);Thread.sleep(200);check(api.reads.get()==before+1&&!shell("cmd jobscheduler get-job-state dev.codex.glass 1601").contains("active"),"periodic_job_finishes_and_remains_scheduled");
            runOnMainSync(()->{r.prefs.edit().putInt("background_minutes",30).apply();SyncJob.schedule(target);});check(target.getSystemService(android.app.job.JobScheduler.class).getPendingJob(1601).getIntervalMillis()==1800000,"interval_change_updates_periodic_schedule");
            check(activities.get()==0,"all_background_checks_create_no_main_activity");
        }finally{
            api.release.countDown();app.unregisterActivityLifecycleCallbacks(watch);runOnMainSync(()->{target.stopService(new Intent(target,WidgetSyncService.class));app.repo=original;original.prefs.edit().putInt("background_minutes",15).remove("widget_pending_at").apply();SyncJob.schedule(target);});r.worker.shutdownNow();
        }
    }
    private void vault() throws Exception {
        Vault v=new Vault(target);JSONObject secret=new JSONObject().put("access_token","test_secret_never_in_apk");v.write(secret);
        String raw=target.getSharedPreferences("encrypted_session",0).getString("session","");
        check(!raw.contains("test_secret_never_in_apk")&&v.read().getString("access_token").equals("test_secret_never_in_apk"),"keystore_aes_gcm_roundtrip_without_plaintext");v.clear();
    }
    private static String encode(byte[] value){return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    private void callbackPorts() throws Exception {
        try(java.net.ServerSocket socket=LoginFlow.bindCallback()){
            check(socket.getLocalPort()==1455&&socket.getInetAddress().isLoopbackAddress(),"browser_callback_registered_primary_loopback");
        }
        try(java.net.ServerSocket occupied=new java.net.ServerSocket(1455,1,java.net.InetAddress.getByName("127.0.0.1"))){
            try(java.net.ServerSocket socket=LoginFlow.bindCallback()){
                check(socket.getLocalPort()==1457&&socket.getInetAddress().isLoopbackAddress(),"browser_callback_registered_fallback_loopback");
            }
            try(java.net.ServerSocket fallback=new java.net.ServerSocket(1457,1,java.net.InetAddress.getByName("127.0.0.1"))){
                boolean refused=false;try(java.net.ServerSocket unexpected=LoginFlow.bindCallback()){}catch(java.net.BindException expected){refused=true;}
                check(refused,"browser_callback_never_uses_unregistered_port");
            }
        }
    }
    private void identity() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();RSAPublicKey publicKey=(RSAPublicKey)pair.getPublic();
        JSONObject jwks=new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("kid","test-key").put("kty","RSA").put("n",encode(publicKey.getModulus().toByteArray())).put("e",encode(publicKey.getPublicExponent().toByteArray()))));
        CodexApi api=new CodexApi(target);java.lang.reflect.Field keys=CodexApi.class.getDeclaredField("jwks"),time=CodexApi.class.getDeclaredField("jwksFetched");keys.setAccessible(true);time.setAccessible(true);keys.set(api,jwks);time.setLong(api,System.currentTimeMillis());
        JSONObject claims=new JSONObject().put("iss",CodexApi.ISSUER).put("aud",CodexApi.CLIENT_ID).put("sub","test-subject").put("exp",System.currentTimeMillis()/1000+3600).put("nonce","test-nonce").put("https://api.openai.com/auth",new JSONObject().put("chatgpt_account_id","test-workspace"));
        String input=encode(new JSONObject().put("alg","RS256").put("kid","test-key").toString().getBytes(StandardCharsets.UTF_8))+"."+encode(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(pair.getPrivate());signer.update(input.getBytes(StandardCharsets.US_ASCII));String jwt=input+"."+encode(signer.sign());
        JSONObject tokens=new JSONObject().put("id_token",jwt).put("access_token","test-opaque-access").put("refresh_token","test-refresh").put("expires_in",3600);
        boolean rejected=false;try{api.acceptTokens(tokens,"wrong-nonce",()->true);}catch(Exception e){rejected=true;}check(rejected&&!api.signedIn(),"nonce_mismatch_rejected");
        rejected=false;try{api.acceptTokens(tokens,"test-nonce",()->false);}catch(Exception e){rejected=true;}check(rejected&&!api.signedIn(),"cancelled_login_cannot_save_tokens");
        api.acceptTokens(tokens,"test-nonce",()->true);check(api.signedIn()&&api.accountKey().startsWith("test-workspace:"),"valid_signed_identity_accepted");api.logout();
    }
    private static class FakeApi extends CodexApi {
        final List<String> sent=Collections.synchronizedList(new ArrayList<>());String outcome="reset";boolean fail;
        CountDownLatch release;
        FakeApi(Context c){super(c);}
        @Override boolean signedIn(){return true;}
        @Override String accountKey(){return "local-verification-only";}
        @Override Model.Snapshot readUsage(){Model.Snapshot s=Repository.demoSnapshot();s.primary=new Model.Window(0,18000,System.currentTimeMillis()/1000+18000);s.count=1;return s;}
        @Override synchronized String consume(String id,String credit) throws Exception {sent.add(id);if(release!=null)release.await(3,TimeUnit.SECONDS);if(fail)throw new java.net.SocketTimeoutException();return outcome;}
    }
    private void redemption() throws Exception {
        FakeApi fake=new FakeApi(target);Repository r=new Repository(target,fake);r.demo=false;r.snapshot=Repository.demoSnapshot();fake.fail=true;
        CountDownLatch first=new CountDownLatch(1);runOnMainSync(()->r.consume((message,ok)->first.countDown()));check(first.await(5,TimeUnit.SECONDS),"uncertain_request_completes_with_error");
        String pending=r.pendingId();check(!pending.isEmpty()&&fake.sent.size()==1,"uncertain_request_keeps_journal");
        fake.fail=false;fake.outcome="already_redeemed";CountDownLatch next=new CountDownLatch(1);runOnMainSync(()->r.consume((message,ok)->next.countDown()));check(next.await(5,TimeUnit.SECONDS),"idempotent_retry_completes");
        check(fake.sent.size()==2&&fake.sent.get(0).equals(fake.sent.get(1))&&r.pendingId().isEmpty(),"retry_reuses_uuid_and_clears_on_definitive_result");check(r.snapshot.primary.remaining()==100&&r.snapshot.count==1,"success_rereads_server_limits");
        fake.release=new CountDownLatch(1);CountDownLatch running=new CountDownLatch(1);runOnMainSync(()->{r.consume((message,ok)->running.countDown());r.consume((message,ok)->{});});Thread.sleep(100);fake.release.countDown();check(running.await(5,TimeUnit.SECONDS)&&fake.sent.size()==3,"double_tap_sends_one_request");
        r.worker.shutdownNow();
    }
    private void widgets() throws Exception {
        Repository r=GlassApp.repo(target);runOnMainSync(()->r.setDemo(true));
        int[][] sizes={{320,190},{230,170},{170,230},{300,80},{150,80},{120,220}};
        for(String theme:new String[]{"light","dark"})for(int[] size:sizes){
            boolean capsule=size[1]<120||size[0]<150,vertical=size[0]<190&&size[1]>=165;
            r.prefs.edit().putString("theme",theme).apply();final View[] inflated=new View[1];
            runOnMainSync(()->{
                inflated[0]=UsageWidget.views(target,size[0],size[1],capsule,vertical).apply(target,null);
                inflated[0].measure(View.MeasureSpec.makeMeasureSpec(Glass.dp(target,size[0]),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Glass.dp(target,size[1]),View.MeasureSpec.EXACTLY));inflated[0].layout(0,0,inflated[0].getMeasuredWidth(),inflated[0].getMeasuredHeight());
            });
            TextView percent=inflated[0].findViewById(R.id.widget_primary);
            check(percent.getText().toString().equals("74%")&&percent.getMeasuredWidth()>0,"widget_"+theme+"_"+size[0]+"x"+size[1]);
            if("light".equals(theme)){Bitmap image=Bitmap.createBitmap(inflated[0].getWidth(),inflated[0].getHeight(),Bitmap.Config.ARGB_8888);runOnMainSync(()->inflated[0].draw(new Canvas(image)));save(image,"widget-"+size[0]+"x"+size[1]+".png");}
        }
        runOnMainSync(()->r.prefs.edit().putBoolean("widget_privacy",true).apply());final RemoteViews[] v=new RemoteViews[1];runOnMainSync(()->v[0]=UsageWidget.views(target,320,190,false,false));final View[] shown=new View[1];runOnMainSync(()->shown[0]=v[0].apply(target,null));check(((TextView)shown[0].findViewById(R.id.widget_primary)).getText().toString().equals("••"),"widget_privacy_hides_values");r.prefs.edit().putBoolean("widget_privacy",false).putString("theme","light").apply();
    }
    private void screenshot(String file) throws Exception {Bitmap b=getUiAutomation().takeScreenshot();if(b!=null)save(b,file);}
    private void save(Bitmap image,String name) throws Exception {File directory=new File(target.getExternalFilesDir(null),"verification");directory.mkdirs();try(OutputStream out=new FileOutputStream(new File(directory,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
