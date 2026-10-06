package dev.codex.glass;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SyncJob extends JobService {
    static final int PERIODIC=1601,IMMEDIATE=1602;
    private final ConcurrentHashMap<Integer,JobParameters> running=new ConcurrentHashMap<>();
    static void schedule(Context c){
        long interval=Math.max(15,GlassApp.repo(c).prefs.getInt("background_minutes",15))*60000L,flex=5*60000L;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        ComponentName component=new ComponentName(c,SyncJob.class);JobInfo existing=scheduler.getPendingJob(PERIODIC);
        // Replacing a matching job resets its due time and can stop the job waking this process.
        if(existing!=null&&existing.isPeriodic()&&existing.getIntervalMillis()==interval&&existing.getFlexMillis()==flex&&existing.isPersisted()&&component.equals(existing.getService())&&existing.getNetworkType()==JobInfo.NETWORK_TYPE_ANY)return;
        scheduler.schedule(new JobInfo.Builder(PERIODIC,component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(interval,flex).setPersisted(true).build());
    }
    static void request(Context c){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler.getPendingJob(IMMEDIATE)!=null)return;
        JobInfo job=new JobInfo.Builder(IMMEDIATE,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        scheduler.schedule(job);
    }
    @Override public boolean onStartJob(JobParameters params){
        Repository r=GlassApp.repo(this);
        if(!r.api.signedIn()&&!r.demo){UsageWidget.updateAll(this);return false;}
        running.put(params.getJobId(),params);
        r.refresh((message,ok)->{
            UsageWidget.updateAll(this);
            JobParameters current=running.remove(params.getJobId());if(current!=null)jobFinished(current,!ok&&params.getJobId()==IMMEDIATE);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){running.remove(params.getJobId());return true;}
}
