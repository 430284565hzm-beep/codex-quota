package dev.codex.glass;

import org.json.*;
import java.text.*;
import java.util.*;

public final class Model {
    public static final class Window {
        public final double used;
        public final long seconds, resetsAt;
        public Window(double used, long seconds, long resetsAt) { this.used=used; this.seconds=seconds; this.resetsAt=resetsAt; }
        public double remaining() { return Math.max(0,Math.min(100,100-used)); }
        public String percent() { return String.format(Locale.US,"%.0f%%",remaining()); }
        public String label() {
            if (seconds>=86400 && seconds%86400==0) return (seconds/86400)+" 天额度";
            if (seconds>=3600 && seconds%3600==0) return (seconds/3600)+" 小时额度";
            if (seconds>0) return (seconds/60)+" 分钟额度";
            return "额度窗口";
        }
        public JSONObject json() throws JSONException { return new JSONObject().put("used_percent",used).put("limit_window_seconds",seconds).put("reset_at",resetsAt); }
    }
    public static final class Credit {
        public final String id,title;
        public final long expiresAt;
        public Credit(String id,String title,long expiresAt) { this.id=id;this.title=title;this.expiresAt=expiresAt; }
    }
    public static final class Snapshot {
        public Window primary,secondary;
        public Integer count;
        public String plan="",bucket="codex";
        public long fetchedAt;
        public boolean detailsKnown;
        public final List<Credit> credits=new ArrayList<>();
        public final List<Bucket> extra=new ArrayList<>();
        public String countText() { return count==null?"—":Integer.toString(count); }
        public Credit firstCredit() { return credits.isEmpty()?null:credits.get(0); }
        public boolean hasData() { return primary!=null||secondary!=null; }
        public JSONObject json() throws JSONException {
            JSONObject r=new JSONObject().put("plan_type",plan).put("fetched_at",fetchedAt);
            JSONObject limits=new JSONObject();
            if(primary!=null) limits.put("primary_window",primary.json());
            if(secondary!=null) limits.put("secondary_window",secondary.json());
            r.put("rate_limit",limits);
            if(count!=null) {
                JSONObject c=new JSONObject().put("available_count",count);
                if(detailsKnown) {
                    JSONArray list=new JSONArray();
                    for(Credit v:credits) list.put(new JSONObject().put("id",v.id).put("title",v.title).put("expires_at",v.expiresAt).put("status","available"));
                    c.put("credits",list);
                }
                r.put("rate_limit_reset_credits",c);
            }
            JSONArray additional=new JSONArray();
            for(Bucket b:extra) {
                JSONObject c=new JSONObject().put("limit_id",b.id).put("limit_name",b.name);
                JSONObject l=new JSONObject();
                if(b.primary!=null)l.put("primary_window",b.primary.json());
                if(b.secondary!=null)l.put("secondary_window",b.secondary.json());
                additional.put(c.put("rate_limit",l));
            }
            return r.put("additional_rate_limits",additional);
        }
    }
    public static final class Bucket { public String id,name; public Window primary,secondary; }
    public static Window window(JSONObject r) {
        if(r==null||r.isNull("used_percent"))return null;
        double used=r.optDouble("used_percent",Double.NaN);
        if(!Double.isFinite(used))return null;
        return new Window(used,r.optLong("limit_window_seconds",0),r.optLong("reset_at",0));
    }
    public static Snapshot parse(JSONObject r,long now) {
        Snapshot s=new Snapshot();
        s.fetchedAt=r.optLong("fetched_at",now); s.plan=r.optString("plan_type","");
        JSONObject l=r.optJSONObject("rate_limit");
        if(l!=null){s.primary=window(l.optJSONObject("primary_window"));s.secondary=window(l.optJSONObject("secondary_window"));}
        JSONObject c=r.optJSONObject("rate_limit_reset_credits");
        if(c!=null&&!c.isNull("available_count"))s.count=Math.max(0,c.optInt("available_count"));
        if(c!=null&&c.optJSONArray("credits")!=null) applyCredits(s,c);
        JSONArray extras=r.optJSONArray("additional_rate_limits");
        if(extras!=null)for(int i=0;i<extras.length();i++){
            JSONObject b=extras.optJSONObject(i); if(b==null)continue;
            Bucket bucket=new Bucket(); bucket.id=b.optString("limit_id",b.optString("metered_feature","")); bucket.name=b.optString("limit_name",bucket.id);
            JSONObject w=b.optJSONObject("rate_limit");
            if(w!=null){bucket.primary=window(w.optJSONObject("primary_window"));bucket.secondary=window(w.optJSONObject("secondary_window"));}
            if("codex".equals(bucket.id)){s.primary=bucket.primary;s.secondary=bucket.secondary;} else if(bucket.primary!=null||bucket.secondary!=null)s.extra.add(bucket);
        }
        return s;
    }
    public static void applyCredits(Snapshot s,JSONObject r){
        // available_count, not the number of detail rows, is authoritative.
        if(!r.isNull("available_count")&&r.has("available_count"))s.count=Math.max(0,r.optInt("available_count"));
        JSONArray list=r.optJSONArray("credits"); if(list==null)return;
        s.credits.clear();s.detailsKnown=true;
        for(int i=0;i<list.length();i++){
            JSONObject c=list.optJSONObject(i);if(c==null||!"available".equals(c.optString("status","available")))continue;
            String id=c.optString("id","");if(id.isEmpty())continue;
            s.credits.add(new Credit(id,c.optString("title","储备重置"),timestamp(c.opt("expires_at"))));
        }
        Collections.sort(s.credits,(a,b)->Long.compare(a.expiresAt==0?Long.MAX_VALUE:a.expiresAt,b.expiresAt==0?Long.MAX_VALUE:b.expiresAt));
    }
    public static long timestamp(Object value) {
        if(value instanceof Number)return ((Number)value).longValue();
        if(!(value instanceof String))return 0;
        String text=(String)value;
        try{return Long.parseLong(text);}catch(Exception ignored){}
        try{return java.time.Instant.parse(text).getEpochSecond();}catch(Exception ignored){}
        return 0;
    }
    public static String countdown(long target){
        if(target==0)return "重置时间暂未提供";
        long sec=target-System.currentTimeMillis()/1000;
        if(sec<=0)return "等待刷新额度";
        if(sec>=86400)return (sec/86400)+" 天 "+(sec%86400/3600)+" 小时后重置";
        if(sec>=3600)return (sec/3600)+" 小时 "+(sec%3600/60)+" 分钟后重置";
        return Math.max(1,sec/60)+" 分钟后重置";
    }
    public static String date(long seconds){return seconds>0?new SimpleDateFormat("M 月 d 日",Locale.CHINA).format(new Date(seconds*1000)):"无到期日期";}
    public static String age(long fetched){
        if(fetched<=0)return "尚未同步";
        long d=Math.max(0,System.currentTimeMillis()/1000-fetched);
        if(d<60)return "刚刚同步";
        if(d<3600)return (d/60)+" 分钟前同步";
        if(d<86400)return (d/3600)+" 小时前同步";
        return (d/86400)+" 天前同步";
    }
}
