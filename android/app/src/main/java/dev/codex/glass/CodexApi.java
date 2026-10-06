package dev.codex.glass;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.math.BigInteger;
import java.util.*;

/** Personal, local client of the account operations in the open-source Codex implementation.
 * Protocol references are recorded in PROTOCOL.md. No inference endpoint is used. */
class CodexApi {
    static final String ISSUER="https://auth.openai.com";
    static final String CLIENT_ID="app_EMoamEEZ73f0CkXaXp7hrann";
    static final String API="https://chatgpt.com/backend-api/wham";
    private final Vault vault;
    private volatile JSONObject session;
    private JSONObject jwks;
    private long jwksFetched;
    static final class ApiError extends IOException {
        final int status;
        ApiError(int status,String message){super(message);this.status=status;}
    }
    static final class Reply {
        final int status;final JSONObject data;
        Reply(int status,JSONObject data){this.status=status;this.data=data;}
    }
    CodexApi(Context c){vault=new Vault(c);try{session=vault.read();}catch(Exception e){vault.clear();}}
    boolean signedIn(){return session!=null;}
    String email(){JSONObject s=session;return s==null?"":s.optString("email","");}
    String plan(){JSONObject s=session;return s==null?"":s.optString("plan","");}
    String accountKey(){JSONObject s=session;return s==null?"":s.optString("account_id","")+":"+s.optString("sub","");}
    synchronized void logout(){session=null;vault.clear();}

    static Reply request(String url,String method,String body,String type,String token,String account) throws Exception {
        URL destination=new URL(url);
        if(!"https".equals(destination.getProtocol())||(!"auth.openai.com".equals(destination.getHost())&&!"chatgpt.com".equals(destination.getHost())))throw new IOException("无效的服务地址");
        HttpsURLConnection conn=(HttpsURLConnection)destination.openConnection();
        try{
            conn.setConnectTimeout(15000);conn.setReadTimeout(20000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod(method);
            conn.setRequestProperty("Accept","application/json");
            conn.setRequestProperty("User-Agent","CodexGlass/1.0 (Android; personal account client)");
            if(token!=null)conn.setRequestProperty("Authorization","Bearer "+token);
            if(account!=null&&!account.isEmpty())conn.setRequestProperty("ChatGPT-Account-Id",account);
            if(body!=null){
                conn.setDoOutput(true);conn.setRequestProperty("Content-Type",type);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);conn.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
            }
            int status=conn.getResponseCode();
            InputStream input=status>=200&&status<300?conn.getInputStream():conn.getErrorStream();
            String text="";
            if(input!=null)try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("服务返回的数据过大");out.write(buf,0,n);}
                text=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            JSONObject data=null;try{data=new JSONObject(text);}catch(JSONException ignored){}
            if(status>=200&&status<300&&data==null)throw new IOException("服务未返回有效的账户数据");
            return new Reply(status,data);
        }finally{conn.disconnect();}
    }
    static String form(String... entries) throws Exception {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<entries.length;i+=2){if(out.length()>0)out.append('&');out.append(URLEncoder.encode(entries[i],"UTF-8")).append('=').append(URLEncoder.encode(entries[i+1],"UTF-8"));}
        return out.toString();
    }
    static String error(int status){
        if(status==401)return "登录已失效，请重新登录 ChatGPT";
        if(status==403)return "服务拒绝了访问，请检查账户权限及手机网络";
        if(status==404)return "此账户接口暂不可用，请更新应用后重试";
        if(status==429)return "刷新过于频繁，请稍后再试";
        if(status>=500)return "Codex 服务暂时不可用，稍后再试";
        return "请求未完成（"+status+"），请重试";
    }
    static JSONObject success(Reply r) throws Exception {
        if(r.status<200||r.status>=300)throw new ApiError(r.status,error(r.status));
        return r.data;
    }
    synchronized Model.Snapshot readUsage() throws Exception {
        JSONObject r=authorized("/usage","GET",null);
        Model.Snapshot snapshot=Model.parse(r,System.currentTimeMillis()/1000);
        // The usage endpoint may return only the count. Details are optional and can be truncated.
        if(snapshot.count!=null&&snapshot.count>0&&!snapshot.detailsKnown){
            try{Model.applyCredits(snapshot,authorized("/rate-limit-reset-credits","GET",null));}
            catch(ApiError e){if(e.status==401)throw e;}
            catch(IOException ignored){} // A detail failure never discards successfully read quota data.
        }
        return snapshot;
    }
    synchronized String consume(String id,String creditId) throws Exception {
        if(id==null||id.trim().isEmpty())throw new IllegalArgumentException("缺少兑换编号");
        JSONObject body=new JSONObject().put("redeem_request_id",id);
        if(creditId!=null&&!creditId.isEmpty())body.put("credit_id",creditId);
        JSONObject r=authorized("/rate-limit-reset-credits/consume","POST",body.toString());
        String code=r.optString("code","");
        if(!Arrays.asList("reset","already_redeemed","nothing_to_reset","no_credit").contains(code))throw new IOException("兑换结果暂不明确，请使用同一编号重试");
        return code;
    }
    private JSONObject authorized(String path,String method,String body) throws Exception {
        if(session==null)throw new ApiError(401,"请先登录 ChatGPT");
        if(session.optLong("expires_at",0)<System.currentTimeMillis()/1000+90)refresh();
        Reply r=request(API+path,method,body,"application/json",session.getString("access_token"),session.getString("account_id"));
        if(r.status==401){refresh();r=request(API+path,method,body,"application/json",session.getString("access_token"),session.getString("account_id"));}
        return success(r);
    }
    private void refresh() throws Exception {
        if(session==null||session.optString("refresh_token","").isEmpty())throw new ApiError(401,"请重新登录 ChatGPT");
        Reply reply=request(ISSUER+"/oauth/token","POST",new JSONObject().put("grant_type","refresh_token").put("client_id",CLIENT_ID).put("refresh_token",session.getString("refresh_token")).toString(),"application/json",null,null);
        if(reply.status==400||reply.status==401)throw new ApiError(401,"授权已过期，请重新登录 ChatGPT");
        JSONObject tokens=success(reply);
        JSONObject next=new JSONObject(session.toString());
        String id=tokens.optString("id_token","");
        if(!id.isEmpty()){
            JSONObject claims=verifyIdToken(id,null);
            if(!next.getString("sub").equals(claims.getString("sub")))throw new IOException("登录身份发生变化，请重新登录");
            JSONObject auth=claims.optJSONObject("https://api.openai.com/auth");
            if(auth!=null&&!next.getString("account_id").equals(auth.optString("chatgpt_account_id",next.getString("account_id"))))throw new IOException("账户工作区发生变化，请重新登录");
            next.put("id_token",id);
        }
        next.put("access_token",tokens.getString("access_token"));
        if(!tokens.optString("refresh_token","").isEmpty())next.put("refresh_token",tokens.getString("refresh_token"));
        next.put("expires_at",expiry(tokens));vault.write(next);session=next;
    }
    synchronized void acceptTokens(JSONObject tokens,String nonce,java.util.function.BooleanSupplier current) throws Exception {
        JSONObject claims=verifyIdToken(tokens.getString("id_token"),nonce);
        JSONObject auth=claims.optJSONObject("https://api.openai.com/auth");
        if(auth==null||auth.optString("chatgpt_account_id","").isEmpty())throw new IOException("授权未包含 Codex 工作区，请重新选择账户");
        if(auth.optBoolean("chatgpt_account_is_fedramp",false))throw new IOException("此版本暂不支持 FedRAMP 工作区");
        JSONObject next=new JSONObject(tokens.toString());
        JSONObject profile=claims.optJSONObject("https://api.openai.com/profile");
        next.put("sub",claims.getString("sub")).put("account_id",auth.getString("chatgpt_account_id"));
        next.put("email",claims.optString("email",profile==null?"":profile.optString("email","")));
        next.put("plan",auth.optString("chatgpt_plan_type","")).put("expires_at",expiry(tokens));
        if(!current.getAsBoolean())throw new IOException("登录已取消");
        vault.write(next);session=next;
    }
    private static long expiry(JSONObject token) throws Exception {
        try{long exp=payload(token.getString("access_token")).optLong("exp",0);if(exp>0)return exp;}catch(Exception ignored){}
        return System.currentTimeMillis()/1000+token.optLong("expires_in",3600);
    }
    static JSONObject payload(String jwt) throws Exception {
        String[] parts=jwt.split("\\.");if(parts.length!=3)throw new IOException("无效的登录凭据");
        return new JSONObject(new String(Base64.decode(parts[1],Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP),StandardCharsets.UTF_8));
    }
    private JSONObject verifyIdToken(String jwt,String nonce) throws Exception {
        String[] p=jwt.split("\\.");if(p.length!=3)throw new IOException("无效的登录凭据");
        JSONObject header=new JSONObject(new String(Base64.decode(p[0],Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8));
        if(!"RS256".equals(header.optString("alg")))throw new IOException("不支持的登录签名");
        if(jwks==null||System.currentTimeMillis()-jwksFetched>3600000){jwks=success(request(ISSUER+"/.well-known/jwks.json","GET",null,null,null,null));jwksFetched=System.currentTimeMillis();}
        JSONObject key=findKey(header.getString("kid"));
        if(key==null){jwks=success(request(ISSUER+"/.well-known/jwks.json","GET",null,null,null,null));key=findKey(header.getString("kid"));}
        if(key==null||!"RSA".equals(key.optString("kty")))throw new IOException("无法验证 OpenAI 登录签名");
        BigInteger n=new BigInteger(1,Base64.decode(key.getString("n"),Base64.URL_SAFE|Base64.NO_WRAP));
        BigInteger e=new BigInteger(1,Base64.decode(key.getString("e"),Base64.URL_SAFE|Base64.NO_WRAP));
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e)));
        verifier.update((p[0]+"."+p[1]).getBytes(StandardCharsets.US_ASCII));
        if(!verifier.verify(Base64.decode(p[2],Base64.URL_SAFE|Base64.NO_WRAP)))throw new IOException("登录签名验证失败");
        JSONObject claims=payload(jwt);boolean audience=CLIENT_ID.equals(claims.optString("aud"));
        JSONArray aud=claims.optJSONArray("aud");if(aud!=null)for(int i=0;i<aud.length();i++)audience|=CLIENT_ID.equals(aud.optString(i));
        long now=System.currentTimeMillis()/1000;
        if(!ISSUER.equals(claims.optString("iss"))||!audience||claims.optLong("exp",0)<now-60||claims.optLong("iat",now)>now+120||claims.optString("sub","").isEmpty())throw new IOException("登录身份验证失败，请检查手机时间后重试");
        if(nonce!=null&&!nonce.equals(claims.optString("nonce")))throw new IOException("登录校验不匹配，请重新登录");
        return claims;
    }
    private JSONObject findKey(String kid){JSONArray list=jwks.optJSONArray("keys");if(list!=null)for(int i=0;i<list.length();i++){JSONObject key=list.optJSONObject(i);if(key!=null&&kid.equals(key.optString("kid")))return key;}return null;}
}
