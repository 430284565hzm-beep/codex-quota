package dev.codex.glass;
import android.app.Application;
public final class GlassApp extends Application {
    Repository repo;
    @Override public void onCreate(){super.onCreate();repo=new Repository(this);repo.prefs.edit().remove("widget_pending").remove("widget_pending_at").apply();SyncJob.schedule(this);}
    static Repository repo(android.content.Context c){return ((GlassApp)c.getApplicationContext()).repo;}
}
