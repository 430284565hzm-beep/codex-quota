package dev.codex.glass;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

/** Short, predecoded cues; never changes system volume or overrides silent mode. */
final class ResetSound implements AutoCloseable {
    private final Context context;
    private SoundPool pool;
    private int click,success;
    private boolean clickReady,successReady;
    ResetSound(Context context){
        this.context=context;
        try{
            pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((p,id,status)->{if(p==pool&&status==0){if(id==click)clickReady=true;if(id==success)successReady=true;}});
            click=pool.load(context,R.raw.reset_click,1);success=pool.load(context,R.raw.reset_success,1);
        }catch(RuntimeException e){close();}
    }
    boolean ready(){return clickReady&&successReady;}
    private void play(int sample,boolean loaded,float volume){
        if(pool==null||!loaded||!GlassApp.repo(context).prefs.getBoolean("reset_sound",true))return;
        AudioManager audio=context.getSystemService(AudioManager.class);
        if(audio==null||audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL)return;
        pool.play(sample,volume,volume,1,0,1);
    }
    void click(){play(click,clickReady,.6f);}
    void success(){play(success,successReady,.8f);}
    @Override public void close(){if(pool!=null){pool.release();pool=null;}clickReady=successReady=false;}
}
