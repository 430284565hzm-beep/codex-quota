package dev.codex.glass;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.*;

final class LoginFlow {
    interface Listener {void status(String text);void code(String code,String url);void completed(String error);}
    private final CodexApi api;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private volatile int generation;
    private volatile ServerSocket server;
    volatile String url="",userCode="",status="";
    volatile boolean active;
    private volatile Listener listener;
    LoginFlow(CodexApi api,Context c){this.api=api;context=c.getApplicationContext();}
    void attach(Listener l){listener=l;}
    private void update(String text){status=text;main.post(()->{Listener l=listener;if(l!=null)l.status(text);});}
    private void done(int attempt,String error){if(attempt!=generation)return;active=false;status=error==null?"登录完成":error;closeServer();context.stopService(new Intent(context,LoginService.class));main.post(()->{if(attempt!=generation)return;if(error==null)GlassApp.repo(context).loggedIn();Listener l=listener;if(l!=null)l.completed(error);});}
    void cancel(){generation++;active=false;url="";userCode="";status="";closeServer();if(pending!=null)pending.cancel(true);context.stopService(new Intent(context,LoginService.class));}
    private void closeServer(){ServerSocket s=server;server=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
    static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static ServerSocket bindCallback() throws IOException {
        // These two loopback ports are registered for the existing Codex public client.
        // An ephemeral port is not in that client's redirect URI allow-list.
        for(int port:new int[]{1455,1457}){
            ServerSocket socket=new ServerSocket();
            try{socket.setReuseAddress(false);socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));return socket;}
            catch(BindException occupied){socket.close();}
            catch(IOException error){socket.close();throw error;}
        }
        throw new BindException("登录回调端口被占用，请改用一次性设备码登录");
    }
    void browser(Context context){
        cancel();active=true;int attempt=generation;Context app=context.getApplicationContext();
        pending=worker.submit(()->{
            try{
                String state=random(),nonce=random(),verifier=random();
                String challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)),Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
                ServerSocket local=bindCallback();local.setSoTimeout(1000);
                if(attempt!=generation){local.close();return;}server=local;
                String redirect="http://127.0.0.1:"+local.getLocalPort()+"/auth/callback";
                url=CodexApi.ISSUER+"/oauth/authorize?"+CodexApi.form("response_type","code","client_id",CodexApi.CLIENT_ID,"redirect_uri",redirect,"scope","openid profile email offline_access","code_challenge",challenge,"code_challenge_method","S256","state",state,"nonce",nonce,"id_token_add_organizations","true","codex_cli_simplified_flow","true","originator","codex_glass_android");
                update("请在浏览器中完成 ChatGPT 登录");
                main.post(()->{if(attempt==generation)open(app,url);});
                long deadline=SystemClock.elapsedRealtime()+15*60*1000;String code=null;
                while(attempt==generation&&SystemClock.elapsedRealtime()<deadline){
                    try(Socket socket=local.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                        String line=in.readLine();if(line==null||line.length()>8192){respond(socket,400,"无效的请求");continue;}
                        String[] bits=line.split(" ");if(bits.length<2){respond(socket,400,"无效的请求");continue;}
                        Uri callback=Uri.parse(bits[1]);
                        if(!"GET".equals(bits[0])||!"/auth/callback".equals(callback.getPath())){respond(socket,404,"请返回 Codex 余量");continue;}
                        if(!state.equals(callback.getQueryParameter("state"))){respond(socket,400,"登录校验不匹配，请返回应用重新登录");continue;}
                        String error=callback.getQueryParameter("error");
                        if(error!=null){respond(socket,200,"已取消授权，请返回 Codex 余量");throw new IOException("授权已取消，可重新登录");}
                        code=callback.getQueryParameter("code");
                        if(code==null||code.isEmpty()){respond(socket,400,"授权未完成");continue;}
                        respond(socket,200,"授权已收到。请返回 Codex 余量，等待账户校验完成。");break;
                    }catch(SocketTimeoutException ignored){}
                }
                closeServer();if(attempt!=generation)return;
                if(code==null)throw new IOException("登录已超时，请重试");
                update("正在校验登录与账户…");
                JSONObject tokens=CodexApi.success(CodexApi.request(CodexApi.ISSUER+"/oauth/token","POST",CodexApi.form("grant_type","authorization_code","client_id",CodexApi.CLIENT_ID,"code",code,"redirect_uri",redirect,"code_verifier",verifier),"application/x-www-form-urlencoded",null,null));
                if(attempt!=generation)return;api.acceptTokens(tokens,nonce,()->attempt==generation);done(attempt,null);
            }catch(Exception e){if(attempt==generation)done(attempt,friendly(e));}
        });
    }
    void device(){
        cancel();active=true;int attempt=generation;
        pending=worker.submit(()->{
            try{
                update("正在获取一次性登录码…");
                CodexApi.Reply reply=CodexApi.request(CodexApi.ISSUER+"/api/accounts/deviceauth/usercode","POST",new JSONObject().put("client_id",CodexApi.CLIENT_ID).toString(),"application/json",null,null);
                if(reply.status==404||reply.status==403)throw new IOException("请先在 ChatGPT 的安全设置中启用设备代码登录，或使用浏览器登录");
                JSONObject r=CodexApi.success(reply);String authId=r.getString("device_auth_id");
                String code=r.optString("user_code",r.optString("usercode",""));if(code.isEmpty())throw new IOException("服务没有返回登录码");
                if(attempt!=generation)return;
                int interval=Math.max(3,Math.min(30,r.optInt("interval",5)));
                url=CodexApi.ISSUER+"/codex/device";userCode=code;
                main.post(()->{Listener l=listener;if(l!=null&&attempt==generation)l.code(code,url);});
                update("打开官方页面，输入此设备的登录码");
                long deadline=SystemClock.elapsedRealtime()+15*60*1000;
                while(attempt==generation&&SystemClock.elapsedRealtime()<deadline){
                    Thread.sleep(interval*1000L);if(attempt!=generation)return;
                    CodexApi.Reply poll=CodexApi.request(CodexApi.ISSUER+"/api/accounts/deviceauth/token","POST",new JSONObject().put("device_auth_id",authId).put("user_code",code).toString(),"application/json",null,null);
                    if(poll.status==403||poll.status==404)continue;
                    JSONObject grant=CodexApi.success(poll);update("正在校验登录与账户…");
                    JSONObject tokens=CodexApi.success(CodexApi.request(CodexApi.ISSUER+"/oauth/token","POST",CodexApi.form("grant_type","authorization_code","client_id",CodexApi.CLIENT_ID,"code",grant.getString("authorization_code"),"redirect_uri",CodexApi.ISSUER+"/deviceauth/callback","code_verifier",grant.getString("code_verifier")),"application/x-www-form-urlencoded",null,null));
                    if(attempt!=generation)return;api.acceptTokens(tokens,null,()->attempt==generation);done(attempt,null);return;
                }
                if(attempt==generation)throw new IOException("登录码已过期，请重新获取");
            }catch(Exception e){if(attempt==generation)done(attempt,friendly(e));}
        });
    }
    static void open(Context c,String url){try{c.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){android.widget.Toast.makeText(c,"请先安装或启用一个手机浏览器",android.widget.Toast.LENGTH_LONG).show();}}
    private static String friendly(Exception e){if(e instanceof SocketTimeoutException)return "连接超时，请检查手机网络后重试";if(e instanceof UnknownHostException)return "无法连接 OpenAI，请检查手机网络";return e.getMessage()==null?"登录未完成，请重试":e.getMessage();}
    private static void respond(Socket socket,int status,String text) throws IOException {
        String html="<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Codex 余量</title><body style=\"background:#edf3f2;color:#203c39;font-family:system-ui;padding:64px 24px;line-height:1.7\"><h2>Codex 余量</h2><p>"+text+"</p></body></html>";
        byte[] body=html.getBytes(StandardCharsets.UTF_8);
        OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
}
