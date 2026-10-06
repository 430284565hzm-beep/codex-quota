package dev.codex.glass;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity implements Repository.Listener,LoginFlow.Listener,SensorEventListener {
    Repository repo;
    FrameLayout root;
    Glass.Backdrop backdrop;
    LinearLayout shell,body,nav;
    ScrollView scroll;
    TextView statusText,primaryValue,secondaryValue,primaryLabel,secondaryLabel,primaryReset,secondaryReset,bankCount,bankExpiry,loginStatus;
    Glass.Meter primaryMeter,secondaryMeter;
    Glass.Celebration celebration;
    ResetSound resetSound;
    Glass.Card bankConfirm,bankCancel;
    boolean resetRequested,resetRestoring;
    Glass.Chart chart;
    Dialog dialog;
    SensorManager sensors;
    String tab="home";
    boolean builtSigned,builtDemo,started,weekly=true;
    Model.Snapshot rendered;
    long nextRefresh;
    long resetNoticeUntil;String resetNotice="";
    final Handler handler=new Handler(Looper.getMainLooper());
    final Runnable ticker=new Runnable(){public void run(){if(!started)return;update();long now=SystemClock.elapsedRealtime();if(repo.api.signedIn()&&now>=nextRefresh&&!repo.busy.get()){nextRefresh=now+repo.prefs.getInt("foreground_seconds",30)*1000L;repo.refresh(null);}handler.postDelayed(this,1000);}};
    int d(float n){return Glass.dp(this,n);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);repo=GlassApp.repo(this);sensors=getSystemService(SensorManager.class);
        if(state!=null){tab=state.getString("tab","home");weekly=state.getBoolean("weekly",true);}
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root=new FrameLayout(this);backdrop=new Glass.Backdrop(this);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        celebration=new Glass.Celebration(this);root.addView(celebration,new FrameLayout.LayoutParams(-1,-1));resetSound=new ResetSound(this);
        root.setOnApplyWindowInsetsListener((view,insets)->{shell.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop()+d(6),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);build();handleIntent(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("tab",tab);state.putBoolean("weekly",weekly);}
    @Override protected void onStart(){super.onStart();started=true;repo.add(this);repo.login.attach(this);if(repo.login.active&&!repo.login.userCode.isEmpty())showDevice(repo.login.userCode,repo.login.url);changed();handler.post(ticker);}
    @Override protected void onStop(){started=false;handler.removeCallbacks(ticker);celebration.stop();repo.remove(this);repo.login.attach(null);super.onStop();}
    @Override protected void onResume(){super.onResume();if(repo.prefs.getBoolean("motion",true)&&ValueAnimator.areAnimatorsEnabled()){Sensor sensor=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);if(sensor!=null)sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);} }
    @Override protected void onPause(){sensors.unregisterListener(this);super.onPause();}
    @Override protected void onDestroy(){if(dialog!=null)dialog.dismiss();celebration.stop();resetSound.close();backdrop.generation++;backdrop.worker.shutdownNow();super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    void handleIntent(Intent intent){if("bank".equals(intent.getStringExtra("screen"))){tab="home";build();root.post(()->{if(repo.api.signedIn()||repo.demo)showBank();});}}
    @Override public void changed(){if(builtSigned!=repo.api.signedIn()||builtDemo!=repo.demo)build();else update();}
    @Override public void onSensorChanged(SensorEvent e){if(e.values.length>=2){float x=Math.max(-d(9),Math.min(d(9),e.values[1]*d(24))),y=Math.max(-d(9),Math.min(d(9),e.values[0]*d(24)));backdrop.tilt(backdrop.tiltX*.84f+x*.16f,backdrop.tiltY*.84f+y*.16f);}}
    @Override public void onAccuracyChanged(Sensor s,int a){}

    TextView text(String value,float size,boolean medium){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Glass.ink(this));v.setFontFeatureSettings("tnum");v.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));v.setIncludeFontPadding(false);return v;}
    TextView muted(String value,float size){TextView v=text(value,size,false);v.setTextColor(Glass.secondary(this));return v;}
    void put(LinearLayout layout,View v,int height){layout.addView(v,new LinearLayout.LayoutParams(-1,height<0?height:d(height)));}
    void gap(LinearLayout layout,int height){Space s=new Space(this);put(layout,s,height);}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    Glass.Card card(float radius,boolean dense){return new Glass.Card(this,backdrop,radius,dense);}
    Glass.Card panel(LinearLayout contents){Glass.Card c=card(30,false);c.setPadding(d(22),d(22),d(22),d(22));c.addView(contents,new FrameLayout.LayoutParams(-1,-2));return c;}
    void bodyCard(View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=d(16);body.addView(v,p);}
    Glass.Card button(String label,Runnable action,boolean strong){
        Glass.Card c=card(24,true);c.setMinimumHeight(d(50));c.setPadding(d(18),d(12),d(18),d(12));TextView t=text(label,14,true);t.setGravity(Gravity.CENTER);if(strong)t.setTextColor(Glass.accent(this));c.addView(t,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        c.setContentDescription(label);c.setFocusable(true);c.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);action.run();});Glass.press(c);return c;
    }
    Glass.Card iconButton(String name,String label,Runnable action){Glass.Card c=card(24,true);c.addView(new Glass.Icon(this,name),new FrameLayout.LayoutParams(d(23),d(23),Gravity.CENTER));c.setOnClickListener(v->action.run());c.setContentDescription(label);c.setFocusable(true);Glass.press(c);return c;}
    void link(String label,Runnable action){TextView v=muted(label,13);v.setGravity(Gravity.CENTER);v.setPadding(0,d(15),0,d(15));v.setOnClickListener(w->action.run());v.setFocusable(true);put(body,v,-2);}
    void set(TextView v,String value){if(v!=null&&!v.getText().toString().equals(value))v.setText(value);}

    void build(){
        if(resetRestoring){resetRestoring=false;celebration.stop();}
        builtSigned=repo.api.signedIn();builtDemo=repo.demo;rendered=null;
        primaryValue=secondaryValue=primaryLabel=secondaryLabel=primaryReset=secondaryReset=bankCount=bankExpiry=statusText=loginStatus=null;primaryMeter=secondaryMeter=null;chart=null;
        shell.removeAllViews();boolean dark=Glass.dark(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|(!dark?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        LinearLayout header=row();header.setPadding(d(24),d(12),d(22),d(16));LinearLayout brand=column();put(brand,text("Codex 余量",22,true),-2);gap(brand,5);put(brand,muted(repo.demo?"体验模式 · 示例数据":"账户用量，随身可见",12),-2);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(iconButton("refresh","立即刷新",()->repo.refresh((msg,ok)->toast(ok?"已同步最新额度":msg))),new LinearLayout.LayoutParams(d(48),d(48)));put(shell,header,-2);
        scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);body=column();body.setPadding(d(22),d(8),d(22),d(22));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollChangeListener((v,x,y,oldx,oldy)->{for(Glass.Card c:backdrop.cards)c.invalidate();});
        if("settings".equals(tab))settings();else if(!builtSigned&&!builtDemo)onboarding();else if("history".equals(tab))history();else home();
        navigation();update();
    }
    void navigation(){
        Glass.Card c=card(30,false);nav=row();String[] keys={"home","history","settings"},labels={"概览","记录","设置"};
        for(int i=0;i<keys.length;i++){
            final String key=keys[i];LinearLayout item=row();item.setGravity(Gravity.CENTER);item.setPadding(d(8),d(15),d(8),d(15));Glass.Icon icon=new Glass.Icon(this,key);item.addView(icon,new LinearLayout.LayoutParams(d(19),d(19)));TextView label=text(labels[i],13,key.equals(tab));label.setTextColor(key.equals(tab)?Glass.accent(this):Glass.secondary(this));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=d(7);item.addView(label,lp);item.setOnClickListener(v->{if(!key.equals(tab)){tab=key;v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);build();}});item.setFocusable(true);item.setContentDescription(labels[i]);Glass.press(item);nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
        }
        c.addView(nav,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,d(58));p.setMargins(d(22),d(2),d(22),d(12));shell.addView(c,p);
    }
    void onboarding(){
        gap(body,18);put(body,muted("你的 CODEX 随身窗口",12),-2);gap(body,16);TextView title=text("余量。\n一眼即知。",40,true);title.setLineSpacing(d(3),1);put(body,title,-2);gap(body,17);TextView description=muted("随时查看账户额度，在需要时使用储备重置。\n把一小块玻璃，留在手机桌面。",14);description.setLineSpacing(d(6),1);put(body,description,-2);gap(body,28);
        LinearLayout preview=column();LinearLayout titleRow=row();titleRow.addView(text("Codex 余量",16,true),new LinearLayout.LayoutParams(0,-2,1));titleRow.addView(muted("小组件预览",10));put(preview,titleRow,-2);gap(preview,24);LinearLayout values=row();
        for(String label:new String[]{"当前窗口","长周期"}){LinearLayout col=column();put(col,muted(label,11),-2);gap(col,7);put(col,text("—",42,false),-2);Glass.Meter m=new Glass.Meter(this);m.set(-1,false);put(col,m,16);values.addView(col,new LinearLayout.LayoutParams(0,-2,1));}put(preview,values,-2);gap(preview,20);put(preview,muted("登录后显示真实账户额度",11),-2);bodyCard(panel(preview));
        bodyCard(button(repo.login.active?"返回登录页面":"继续使用 ChatGPT",()->{if(repo.login.active)LoginFlow.open(this,repo.login.url);else startLogin(false);},true));
        loginStatus=muted(repo.login.active?repo.login.status:"通过 OpenAI 官方页面授权，密码只在浏览器中输入。",12);loginStatus.setGravity(Gravity.CENTER);loginStatus.setLineSpacing(d(4),1);put(body,loginStatus,-2);
        link("改用一次性设备码登录",()->startLogin(true));link("先体验界面与动效",()->repo.setDemo(true));
        if(repo.login.active)link("取消本次登录",()->{repo.login.cancel();build();});
    }
    void home(){
        put(body,text("额度概览",30,true),-2);gap(body,10);statusText=muted("正在读取账户额度",12);put(body,statusText,-2);gap(body,22);
        LinearLayout content=column();LinearLayout top=row();top.addView(muted("剩余额度",13),new LinearLayout.LayoutParams(0,-2,1));TextView plan=muted(repo.demo?"PLUS":repo.api.plan().toUpperCase(Locale.US),11);top.addView(plan,new LinearLayout.LayoutParams(-2,-2));put(content,top,-2);gap(content,22);
        LinearLayout windows=row();LinearLayout a=quota(true),b=quota(false);LinearLayout.LayoutParams pa=new LinearLayout.LayoutParams(0,-2,1),pb=new LinearLayout.LayoutParams(0,-2,1);pb.leftMargin=d(18);windows.addView(a,pa);windows.addView(b,pb);put(content,windows,-2);gap(content,18);put(content,muted("跨任务共享的账户额度",11),-2);bodyCard(panel(content));
        LinearLayout bank=column();LinearLayout bankRow=row();LinearLayout bankTexts=column();put(bankTexts,text("储备重置",18,true),-2);gap(bankTexts,6);bankExpiry=muted("需要时，再给额度一次重置",12);put(bankTexts,bankExpiry,-2);bankRow.addView(bankTexts,new LinearLayout.LayoutParams(0,-2,1));bankCount=text("—",36,false);bankCount.setTextColor(Glass.accent(this));bankRow.addView(bankCount);put(bank,bankRow,-2);gap(bank,18);put(bank,button("查看与使用重置券",this::showBank,true),-2);bodyCard(panel(bank));
        LinearLayout actions=row();Glass.Card pin=button("添加桌面小组件",this::chooseWidget,false),share=button("分享额度卡片",this::share,false);actions.addView(pin,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=d(12);actions.addView(share,right);bodyCard(actions);
        LinearLayout mini=column();LinearLayout chartHeader=row();chartHeader.addView(text("最近的变化",16,true),new LinearLayout.LayoutParams(0,-2,1));TextView more=muted("查看记录  ›",12);more.setPadding(d(8),d(8),0,d(8));more.setOnClickListener(v->{tab="history";build();});chartHeader.addView(more);put(mini,chartHeader,-2);gap(mini,12);chart=new Glass.Chart(this,repo.history(),false);put(mini,chart,138);bodyCard(panel(mini));
        if(repo.demo)link("退出体验，登录我的账户",()->repo.setDemo(false));
    }
    LinearLayout quota(boolean first){
        LinearLayout l=column();TextView label=muted(first?"当前窗口":"长周期",12);put(l,label,-2);gap(l,10);TextView value=text("—",56,false);value.setTypeface(Typeface.create("sans-serif-light",0));value.setMaxLines(1);value.setAutoSizeTextTypeUniformWithConfiguration(24,56,1,android.util.TypedValue.COMPLEX_UNIT_SP);put(l,value,72);gap(l,11);Glass.Meter meter=new Glass.Meter(this);put(l,meter,12);gap(l,9);TextView reset=muted("等待同步",11);reset.setMaxLines(2);reset.setLineSpacing(d(3),1);put(l,reset,-2);
        if(first){primaryLabel=label;primaryValue=value;primaryReset=reset;primaryMeter=meter;}else{secondaryLabel=label;secondaryValue=value;secondaryReset=reset;secondaryMeter=meter;}return l;
    }
    void update(){
        Model.Snapshot s=repo.snapshot;boolean changed=s!=rendered;
        if(statusText!=null){String status=resetRestoring?"额度正在恢复…":resetRequested?"正在确认储备重置…":SystemClock.elapsedRealtime()<resetNoticeUntil?resetNotice:repo.demo?"体验模式 · 以下为示例数据":repo.busy.get()?"正在同步最新额度…":!repo.error.isEmpty()?repo.error:s==null?"尚未读取 · 点击右上角刷新":Model.age(s.fetchedAt);set(statusText,status);statusText.setOnClickListener(v->{if(repo.authIssue)startLogin(false);else if(!repo.error.isEmpty())repo.refresh((m,ok)->toast(ok?"已同步":m));});}
        if(resetRequested||resetRestoring)return;
        Model.Window a=s==null?null:s.primary,b=s==null?null:s.secondary;
        set(primaryValue,a==null?"—":a.percent());set(secondaryValue,b==null?"—":b.percent());set(primaryLabel,a==null?"当前窗口":a.label());set(secondaryLabel,b==null?"长周期":b.label());set(primaryReset,a==null?"额度暂未提供":Model.countdown(a.resetsAt));set(secondaryReset,b==null?"额度暂未提供":Model.countdown(b.resetsAt));
        if(changed){if(primaryMeter!=null)primaryMeter.set(a==null?-1:a.remaining(),rendered!=null);if(secondaryMeter!=null)secondaryMeter.set(b==null?-1:b.remaining(),rendered!=null);if(chart!=null){chart.rows=repo.history();chart.invalidate();}}
        set(bankCount,s==null?"—":s.countText());Model.Credit c=s==null?null:s.firstCredit();
        set(bankExpiry,c!=null&&c.expiresAt>0?"最早到期 "+Model.date(c.expiresAt):s!=null&&s.count!=null&&s.count==0?"暂时没有可用的重置券":"需要时，再给额度一次重置");
        if(loginStatus!=null&&repo.login.active)set(loginStatus,repo.login.status);rendered=s;
    }
    void history(){
        put(body,text("额度记录",30,true),-2);gap(body,10);put(body,muted("记录这台手机同步到的剩余百分比",12),-2);gap(body,23);
        LinearLayout range=row();range.addView(button(weekly?"✓ 最近 7 天":"最近 7 天",()->{weekly=true;build();},weekly),new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.leftMargin=d(12);range.addView(button(!weekly?"✓ 最近 24 小时":"最近 24 小时",()->{weekly=false;build();},!weekly),rp);bodyCard(range);
        LinearLayout graph=column();put(graph,text("剩余额度",17,true),-2);gap(graph,14);chart=new Glass.Chart(this,repo.history(),weekly);put(graph,chart,210);gap(graph,8);put(graph,muted("绿色：当前窗口    蓝色：长周期",11),-2);bodyCard(panel(graph));
        JSONArray records=repo.history();LinearLayout list=column();put(list,text("最近同步",17,true),-2);gap(list,14);int end=records.length();
        if(end==0)put(list,muted("还没有记录，首次同步后会开始保存。",13),-2);
        for(int i=end-1;i>=Math.max(0,end-6);i--){JSONObject record=records.optJSONObject(i);if(record==null)continue;String time=new java.text.SimpleDateFormat("M/d HH:mm",Locale.CHINA).format(new Date(record.optLong("at")*1000));double primary=record.optDouble("primary",-1),secondary=record.optDouble("secondary",-1);LinearLayout line=row();line.setPadding(0,d(10),0,d(10));line.addView(muted(time,12),new LinearLayout.LayoutParams(0,-2,1));line.addView(text((primary<0?"—":String.format(Locale.US,"%.0f%%",primary))+"  /  "+(secondary<0?"—":String.format(Locale.US,"%.0f%%",secondary)),13,true));put(list,line,-2);}bodyCard(panel(list));
        put(body,muted("曲线来自本机采样；未同步期间不会补造数据。",12),-2);link("清除本机额度记录",()->confirm("清除记录？","将删除当前账户在这台手机上的采样记录。", "清除",()->{repo.clearHistory();build();}));
    }
    void settings(){
        put(body,text("你的偏好",30,true),-2);gap(body,10);put(body,muted("让这小块玻璃，更适合你",12),-2);gap(body,23);
        LinearLayout account=column();put(account,text("账户",17,true),-2);gap(account,10);put(account,muted(repo.api.signedIn()?repo.api.email():repo.demo?"正在体验示例界面":"尚未登录 ChatGPT",13),-2);gap(account,14);put(account,button(repo.api.signedIn()?"退出当前账户":"继续使用 ChatGPT",()->{if(repo.api.signedIn())confirm("退出账户？","本机登录凭据会被删除。桌面小组件将显示登录提示。","退出",()->repo.logout((m,ok)->{toast(m);build();}));else startLogin(false);},false),-2);bodyCard(panel(account));
        LinearLayout appearance=column();put(appearance,text("玻璃外观",17,true),-2);gap(appearance,12);
        option(appearance,"主题",themeName(),()->choices("主题",new String[]{"跟随系统","浅色","深色"},new String[]{"system","light","dark"},"theme",true));
        option(appearance,"点缀色",accentName(),()->choices("点缀色",new String[]{"薄荷","雾蓝","浅紫"},new String[]{"mint","blue","lavender"},"accent",true));
        option(appearance,"玻璃背景",new File(getFilesDir(),"background.jpg").exists()?"已选图片":"柔和渐变",()->backgroundOptions());
        toggle(appearance,"轻微视差","轻轻倾斜手机，玻璃随背景移动","motion",true);bodyCard(panel(appearance));
        LinearLayout feedback=column();put(feedback,text("重置时刻",17,true),-2);gap(feedback,12);toggle(feedback,"彩纸礼炮","恢复额度时，轻轻洒落一小簇彩纸","reset_confetti",true);toggle(feedback,"重置音效","轻触确认，随后一声柔和的上行音","reset_sound",true);bodyCard(panel(feedback));
        LinearLayout reminders=column();put(reminders,text("及时提醒",17,true),-2);gap(reminders,12);toggle(reminders,"低额度提醒","剩余额度较少时提醒一次","notify_low",false);option(reminders,"提醒阈值",repo.prefs.getInt("low_threshold",20)+"%",()->numberChoices("提醒阈值",new String[]{"10%","20%","30%"},new int[]{10,20,30},"low_threshold"));toggle(reminders,"额度恢复提醒","同步到重置后的额度时提醒","notify_reset",false);toggle(reminders,"重置券到期提醒","最早到期的券在 3 天内提醒","notify_expiry",false);bodyCard(panel(reminders));
        LinearLayout widgets=column();put(widgets,text("桌面小组件",17,true),-2);gap(widgets,12);option(widgets,"添加到桌面","玻璃卡片 / 胶囊",this::chooseWidget);toggle(widgets,"隐藏桌面上的数字","点开应用后查看完整额度","widget_privacy",false);option(widgets,"后台同步",repo.prefs.getInt("background_minutes",15)+" 分钟",()->numberChoices("后台同步",new String[]{"15 分钟","30 分钟","60 分钟"},new int[]{15,30,60},"background_minutes"));option(widgets,"允许后台更新","检查省电设置",this::backgroundHelp);put(widgets,muted("刷新按钮可直接更新桌面组件。自动更新可能因系统省电而延后。",11),-2);bodyCard(panel(widgets));
        LinearLayout details=column();put(details,text("同步与数据",17,true),-2);gap(details,12);option(details,"应用内自动刷新",repo.prefs.getInt("foreground_seconds",30)+" 秒",()->numberChoices("应用内自动刷新",new String[]{"15 秒","30 秒","60 秒"},new int[]{15,30,60},"foreground_seconds"));option(details,"数据与登录说明","本机加密保存",this::showPrivacy);option(details,"协议来源","OpenAI Docs",()->LoginFlow.open(this,"https://learn.chatgpt.com/docs/app-server"));bodyCard(panel(details));
        TextView version=muted("Codex 余量 1.3 · 个人账户工具",11);version.setGravity(Gravity.CENTER);put(body,version,-2);
    }
    String themeName(){String v=repo.prefs.getString("theme","system");return "dark".equals(v)?"深色":"light".equals(v)?"浅色":"跟随系统";}
    String accentName(){String v=repo.prefs.getString("accent","mint");return "blue".equals(v)?"雾蓝":"lavender".equals(v)?"浅紫":"薄荷";}
    void option(LinearLayout target,String title,String detail,Runnable action){LinearLayout line=row();line.setPadding(0,d(15),0,d(15));line.addView(text(title,14,false),new LinearLayout.LayoutParams(0,-2,1));TextView desc=muted(detail,12);line.addView(desc);Glass.Icon arrow=new Glass.Icon(this,"arrow");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(d(18),d(18));p.leftMargin=d(7);line.addView(arrow,p);line.setOnClickListener(v->action.run());line.setFocusable(true);line.setContentDescription(title+"，"+detail);put(target,line,-2);}
    void toggle(LinearLayout target,String title,String detail,String key,boolean def){
        LinearLayout line=row();line.setPadding(0,d(12),0,d(12));LinearLayout texts=column();put(texts,text(title,14,false),-2);gap(texts,5);TextView desc=muted(detail,11);desc.setLineSpacing(d(3),1);put(texts,desc,-2);line.addView(texts,new LinearLayout.LayoutParams(0,-2,1));Switch sw=new Switch(this);sw.setChecked(repo.prefs.getBoolean(key,def));sw.setContentDescription(title);sw.setButtonTintList(ColorStateList.valueOf(Glass.accent(this)));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.leftMargin=d(12);line.addView(sw,sp);
        sw.setOnCheckedChangeListener((button,checked)->{repo.prefs.edit().putBoolean(key,checked).apply();if(key.startsWith("notify_")&&checked&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},41);if("motion".equals(key)){if(!checked){sensors.unregisterListener(this);backdrop.tilt(0,0);}else{Sensor s=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);if(s!=null)sensors.registerListener(this,s,SensorManager.SENSOR_DELAY_GAME);}}UsageWidget.updateAll(this);});put(target,line,-2);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==41&&(grants.length==0||grants[0]!=PackageManager.PERMISSION_GRANTED)){repo.prefs.edit().putBoolean("notify_low",false).putBoolean("notify_reset",false).putBoolean("notify_expiry",false).apply();toast("未开启通知权限，提醒保持关闭");build();}}
    void pick(String title,String[] labels,java.util.function.IntConsumer action){LinearLayout l=column();put(l,text(title,23,true),-2);gap(l,20);for(int i=0;i<labels.length;i++){final int index=i;put(l,button(labels[i],()->{if(dialog!=null)dialog.dismiss();action.accept(index);},false),-2);gap(l,10);}put(l,button("返回",()->{if(dialog!=null)dialog.dismiss();},false),-2);showSheet(l);}
    void choices(String title,String[] labels,String[] values,String key,boolean appearance){pick(title,labels,index->{repo.prefs.edit().putString(key,values[index]).apply();build();if(appearance)backdrop.reload();UsageWidget.updateAll(this);});}
    void numberChoices(String title,String[] labels,int[] values,String key){pick(title,labels,index->{repo.prefs.edit().putInt(key,values[index]).apply();if("background_minutes".equals(key))SyncJob.schedule(this);nextRefresh=0;build();});}
    void backgroundOptions(){pick("玻璃背景",new String[]{"选择图片（可选择当前壁纸）","恢复柔和渐变"},i->{if(i==0){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,51);}else{new File(getFilesDir(),"background.jpg").delete();build();backdrop.reload();UsageWidget.updateAll(this);}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==51&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();repo.worker.execute(()->{try{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}if(o.outWidth<=0)throw new IOException("无法读取这张图片");o.inJustDecodeBounds=false;o.inSampleSize=Math.max(1,Math.max(o.outWidth,o.outHeight)/1800);Bitmap b;try(InputStream in=getContentResolver().openInputStream(uri)){b=BitmapFactory.decodeStream(in,null,o);}if(b==null)throw new IOException("无法读取这张图片");try(OutputStream out=new FileOutputStream(new File(getFilesDir(),"background.jpg"))){b.compress(Bitmap.CompressFormat.JPEG,92,out);}b.recycle();handler.post(()->{if(!isDestroyed()){build();backdrop.reload();UsageWidget.updateAll(this);toast("背景已更新");}});
    }catch(Exception e){handler.post(()->toast("背景未更新，请选择另一张图片"));}});}}

    void startLogin(boolean device){
        if(device)repo.login.device();else repo.login.browser(this);build();
        try{startForegroundService(new Intent(this,LoginService.class));}catch(Exception ignored){}
    }
    @Override public void status(String text){set(loginStatus,text);}
    @Override public void code(String code,String url){if(started)showDevice(code,url);}
    void showDevice(String code,String url){
        if(dialog!=null)dialog.dismiss();LinearLayout l=column();put(l,text("一次性设备码",23,true),-2);gap(l,14);TextView message=muted("在 OpenAI 官方页面登录后，输入下方代码。代码在 15 分钟内有效。",13);message.setLineSpacing(d(4),1);put(l,message,-2);gap(l,22);TextView value=text(code,30,true);value.setLetterSpacing(.08f);value.setGravity(Gravity.CENTER);put(l,value,-2);gap(l,22);put(l,button("复制代码并打开官方页面",()->{getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("一次性登录码",code));LoginFlow.open(this,url);},true),-2);gap(l,12);put(l,button("已完成，等待验证",()->{if(dialog!=null)dialog.dismiss();},false),-2);gap(l,12);put(l,muted("若登录码不可用，可在 ChatGPT 安全设置中开启设备代码登录。",11),-2);showSheet(l);
    }
    @Override public void completed(String error){stopService(new Intent(this,LoginService.class));if(dialog!=null)dialog.dismiss();if(error==null){tab="home";build();toast("登录成功，正在同步账户额度");}else{build();toast(error);}}
    void showSheet(LinearLayout content){
        if(dialog!=null)dialog.dismiss();dialog=new Dialog(this);Glass.Card surface=card(30,false);surface.setPadding(d(22),d(22),d(22),d(22));ScrollView scrolling=new ScrollView(this);scrolling.setVerticalScrollBarEnabled(false);scrolling.addView(content,new ScrollView.LayoutParams(-1,-2));surface.addView(scrolling,new FrameLayout.LayoutParams(-1,-2));FrameLayout holder=new FrameLayout(this);holder.setPadding(d(10),d(10),d(10),d(10));holder.addView(surface,new FrameLayout.LayoutParams(-1,-2));dialog.setContentView(holder);Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.2f;p.width=Math.min(getResources().getDisplayMetrics().widthPixels-d(28),d(430));p.height=-2;w.setAttributes(p);}dialog.show();if(w!=null)w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-d(28),d(430)),-2);
    }
    void showBank(){
        if(resetRestoring)return;
        Model.Snapshot s=repo.snapshot;LinearLayout l=column();LinearLayout heading=row();heading.addView(muted("储备重置",11),new LinearLayout.LayoutParams(0,-2,1));TextView count=text((s==null?"—":s.countText())+" 次可用",12,false);count.setTextColor(Glass.accent(this));heading.addView(count);put(l,heading,-2);gap(l,22);put(l,text("恢复额度",26,false),-2);gap(l,13);TextView detail=muted("使用一张重置券，恢复符合条件的额度。",13);detail.setLineSpacing(d(5),1);put(l,detail,-2);gap(l,10);Model.Credit c=s==null?null:s.firstCredit();put(l,muted(c!=null&&c.expiresAt>0?"最早到期 "+Model.date(c.expiresAt):"服务会选择下一张可用的重置券",11),-2);
        if(!repo.pendingId().isEmpty()){gap(l,12);put(l,muted("上次兑换结果待确认，将使用原编号重试。",12),-2);}if(repo.demo){gap(l,12);put(l,muted("体验模式 · 不消耗真实重置券",12),-2);}gap(l,22);
        Glass.Card confirm=button(repo.demo?"体验重置动效":repo.pendingId().isEmpty()?"使用 1 次储备":"确认上次兑换结果",()->{},true);bankConfirm=confirm;
        Glass.Card cancel=button("返回",()->{if(!repo.busy.get()&&dialog!=null)dialog.dismiss();},false);bankCancel=cancel;
        boolean can=repo.demo||!repo.pendingId().isEmpty()||(s!=null&&s.count!=null&&s.count>0);confirm.setEnabled(can&&!repo.busy.get());confirm.setAlpha(can&&!repo.busy.get()?1:.4f);
        TextView result=muted(can?"确认后，两条额度会平滑恢复至最新余额":"当前没有可用的重置券",11);result.setLineSpacing(d(4),1);result.setMinHeight(d(38));
        confirm.setOnClickListener(v->{
            if(repo.busy.get())return;Dialog sheet=dialog;Model.Snapshot before=repo.snapshot;
            resetSound.click();v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            confirm.setEnabled(false);confirm.setAlpha(.6f);cancel.setEnabled(false);sheet.setCancelable(false);resetRequested=true;
            ((TextView)confirm.getChildAt(0)).setText("正在确认…");set(result,"正在确认储备重置…");
            repo.consume((message,ok)->{
                resetRequested=false;if(isDestroyed())return;
                if(ok){
                    sheet.dismiss();Model.Snapshot next=repo.snapshot;
                    if(next!=null&&next!=before)restoreQuota(next,message);else{resetNotice=message;resetNoticeUntil=SystemClock.elapsedRealtime()+5000;update();}
                }else{
                    update();set(result,message);sheet.setCancelable(true);cancel.setEnabled(true);((TextView)confirm.getChildAt(0)).setText(repo.pendingId().isEmpty()?"使用 1 次储备":"确认上次兑换结果");
                    Model.Snapshot next=repo.snapshot;boolean retry=repo.demo||!repo.pendingId().isEmpty()||(next!=null&&next.count!=null&&next.count>0);confirm.setEnabled(retry);confirm.setAlpha(retry?1:.4f);
                }
            });
        });LinearLayout actions=row();actions.addView(cancel,new LinearLayout.LayoutParams(d(82),-2));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=d(10);actions.addView(confirm,cp);put(l,actions,-2);gap(l,14);put(l,result,-2);showSheet(l);
    }
    void restoreQuota(Model.Snapshot next,String message){
        rendered=next;resetRestoring=true;set(bankCount,next.countText());update();scroll.smoothScrollTo(0,0);
        handler.postDelayed(()->{
            if(isDestroyed())return;
            if(primaryMeter==null||secondaryMeter==null){resetRestoring=false;update();return;}
            if(started){int[] p=new int[2],s=new int[2],origin=new int[2];primaryMeter.getLocationInWindow(p);secondaryMeter.getLocationInWindow(s);root.getLocationInWindow(origin);float cx=(p[0]+primaryMeter.getWidth()/2f+s[0]+secondaryMeter.getWidth()/2f)/2-origin[0],cy=(p[1]+primaryMeter.getHeight()/2f+s[1]+secondaryMeter.getHeight()/2f)/2-origin[1];celebration.play(cx,cy,repo.prefs.getBoolean("reset_confetti",true));resetSound.success();}
            final int[] remaining={2};Runnable finish=()->{if(--remaining[0]!=0)return;resetRestoring=false;resetNotice=message;resetNoticeUntil=SystemClock.elapsedRealtime()+4000;update();root.performHapticFeedback(HapticFeedbackConstants.CONFIRM);statusText.announceForAccessibility(message);};
            primaryMeter.restore(next.primary==null?-1:next.primary.remaining(),primaryValue,finish);
            secondaryMeter.restore(next.secondary==null?-1:next.secondary.remaining(),secondaryValue,finish);
        },300);
    }
    void backgroundHelp(){
        boolean samsung="samsung".equalsIgnoreCase(Build.MANUFACTURER);LinearLayout l=column();put(l,text("保持桌面更新",23,true),-2);gap(l,16);
        TextView detail=muted(samsung?"在应用设置中打开「电池」，选择「不受限制」。\n\n再到系统设置 → 电池 → 后台使用限制，将 Codex 余量加入「从不自动休眠的应用程序」，并移出深度休眠列表。":"在应用设置中允许后台活动，并取消该应用的电池限制。",13);detail.setLineSpacing(d(5),1);put(l,detail,-2);gap(l,16);put(l,muted("点击组件上的刷新按钮会独立更新。自动同步仍由系统安排，离线时保留最后一次数据。",11),-2);gap(l,22);
        put(l,button("打开应用设置",()->{dialog.dismiss();try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(ActivityNotFoundException e){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}},true),-2);gap(l,10);put(l,button("完成",()->dialog.dismiss(),false),-2);showSheet(l);
    }
    void chooseWidget(){pick("添加到桌面",new String[]{"玻璃卡片 · 额度、倒计时与储备","玻璃胶囊 · 紧凑的两个额度"},index->{Class<?> type=index==0?UsageWidget.class:CapsuleWidget.class;AppWidgetManager m=AppWidgetManager.getInstance(this);if(m.isRequestPinAppWidgetSupported()){m.requestPinAppWidget(new ComponentName(this,type),null,null);}else toast("请长按手机桌面，进入小组件，找到 Codex 余量");});}
    void confirm(String title,String detail,String label,Runnable action){LinearLayout l=column();put(l,text(title,23,true),-2);gap(l,16);TextView desc=muted(detail,13);desc.setLineSpacing(d(5),1);put(l,desc,-2);gap(l,24);put(l,button(label,()->{if(dialog!=null)dialog.dismiss();action.run();},true),-2);gap(l,10);put(l,button("返回",()->{if(dialog!=null)dialog.dismiss();},false),-2);showSheet(l);}
    void showPrivacy(){new AlertDialog.Builder(this).setTitle("登录与数据").setMessage("登录在 OpenAI 官方浏览器页面完成。本应用基于 Codex 的开源账户流程，请求用量、券详情及你确认的储备兑换。\n\n会话通过 Android Keystore 的 AES-GCM 加密保存；应用禁用系统备份。额度记录、偏好和你选的背景图只保存在这台手机。\n\n用量读取不会发起模型推理。兑换结果以服务返回和重新读取的额度为准。\n\n应用内默认每 30 秒同步；桌面后台默认每 15 分钟，由安卓系统调度。网络不可用时保留最后一次数据及同步时间。\n\n个人工具，非 OpenAI 官方应用；账户服务升级后可能需要更新客户端。").setPositiveButton("知道了",null).show();}
    void share(){
        try{Bitmap b=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));File file=new File(getCacheDir(),"snapshot.png");try(OutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();Uri uri=Uri.parse("content://"+getPackageName()+".share/snapshot.png");Intent send=new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newUri(getContentResolver(),"Codex 额度卡片",uri));startActivity(Intent.createChooser(send,"分享额度卡片"));}catch(Exception e){toast("卡片生成失败，请重试");}
    }
    void toast(String text){if(text!=null&&!isDestroyed())Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
