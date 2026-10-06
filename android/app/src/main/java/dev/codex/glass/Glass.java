package dev.codex.glass;

import android.animation.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

final class Glass {
    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static int alpha(int color,float opacity){return (color&0xFFFFFF)|((Math.round(255*opacity)&255)<<24);}
    static boolean dark(Context c){
        String mode=GlassApp.repo(c).prefs.getString("theme","system");
        return "dark".equals(mode)||("system".equals(mode)&&(c.getResources().getConfiguration().uiMode&48)==32);
    }
    static int ink(Context c){return dark(c)?Color.rgb(238,244,246):Color.rgb(35,46,53);}
    static int secondary(Context c){return dark(c)?Color.rgb(172,192,202):Color.rgb(101,117,125);}
    static int accent(Context c){String a=GlassApp.repo(c).prefs.getString("accent","mint");return "blue".equals(a)?Color.rgb(86,131,199):"lavender".equals(a)?Color.rgb(140,117,194):dark(c)?Color.rgb(127,214,192):Color.rgb(58,147,128);}
    static void press(View v){
        v.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){view.animate().scaleX(.975f).scaleY(.975f).setDuration(100).start();}
            else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){view.animate().scaleX(1).scaleY(1).setInterpolator(new OvershootInterpolator(.7f)).setDuration(180).start();}
            return false;
        });
    }
    static class Backdrop extends View {
        Bitmap sharp,soft,deep;
        float tiltX,tiltY;
        final List<Card> cards=new ArrayList<>();
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        int generation;
        Backdrop(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){if(w>0&&h>0)make(w,h);}
        void reload(){if(getWidth()>0)make(getWidth(),getHeight());}
        void tilt(float x,float y){if(Math.abs(x-tiltX)<dp(getContext(),.05f)+.04f&&Math.abs(y-tiltY)<dp(getContext(),.05f)+.04f)return;tiltX=x;tiltY=y;invalidate();for(Card card:cards)card.invalidate();}
        private void make(int w,int h){
            final int version=++generation;final boolean night=dark(getContext());final String path=new File(getContext().getFilesDir(),"background.jpg").getAbsolutePath();
            worker.execute(()->{
                int bw=Math.min(800,w),bh=Math.max(1,Math.round(h*(bw/(float)w)));
                Bitmap b=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                p.setShader(new LinearGradient(0,0,bw,bh,night?0xFF172A32:0xFFF0F2F4,night?0xFF111B29:0xFFDCE9E7,Shader.TileMode.CLAMP));c.drawRect(0,0,bw,bh,p);
                Bitmap photo=null;
                if(new File(path).exists())try{
                    BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);o.inSampleSize=Math.max(1,Math.max(o.outWidth,o.outHeight)/1800);o.inJustDecodeBounds=false;photo=BitmapFactory.decodeFile(path,o);
                }catch(Exception ignored){}
                if(photo!=null){
                    p.setShader(null);float scale=Math.max(bw/(float)photo.getWidth(),bh/(float)photo.getHeight());float pw=photo.getWidth()*scale,ph=photo.getHeight()*scale;
                    c.drawBitmap(photo,null,new RectF((bw-pw)/2,(bh-ph)/2,(bw+pw)/2,(bh+ph)/2),p);photo.recycle();
                    p.setColor(night?0x8813222C:0x28FFFFFF);c.drawRect(0,0,bw,bh,p);
                }else{
                    orb(c,p,bw*.18f,bh*.30f,bw*.92f,night?0x644D8F89:0x806ED5B8);
                    orb(c,p,bw*.96f,bh*.12f,bw*.9f,night?0x585572AF:0x6389BDE3);
                    orb(c,p,bw*.78f,bh*.70f,bw*.85f,night?0x49576590:0x63879CDC);
                }
                Bitmap blurred=blur(b,Math.max(8,bw/32));Bitmap more=blur(blurred,Math.max(5,bw/50));
                post(()->{if(version!=generation){b.recycle();blurred.recycle();more.recycle();return;}sharp=b;soft=blurred;deep=more;invalidate();for(Card card:cards)card.invalidate();});
            });
        }
        static void orb(Canvas c,Paint p,float x,float y,float radius,int color){p.setShader(new RadialGradient(x,y,radius,new int[]{color,alpha(color,0)},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,radius,p);p.setShader(null);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(sharp!=null)drawTexture(c,sharp,0,0,getWidth(),getHeight());}
        void drawTexture(Canvas c,Bitmap b,float left,float top,float width,float height){
            float pad=dp(getContext(),12);RectF r=new RectF(left-pad+tiltX,top-pad+tiltY,left+width+pad+tiltX,top+height+pad+tiltY);
            paint.setShader(null);paint.setAlpha(255);c.drawBitmap(b,null,r,paint);
        }
        static Bitmap blur(Bitmap source,int radius){
            int w=source.getWidth(),h=source.getHeight();int[] input=new int[w*h],pass=new int[w*h],output=new int[w*h];source.getPixels(input,0,w,0,0,w,h);int div=radius*2+1;
            for(int y=0;y<h;y++){
                int rs=0,gs=0,bs=0,row=y*w;
                for(int i=-radius;i<=radius;i++){int v=input[row+Math.max(0,Math.min(w-1,i))];rs+=(v>>16)&255;gs+=(v>>8)&255;bs+=v&255;}
                for(int x=0;x<w;x++){pass[row+x]=0xff000000|(rs/div<<16)|(gs/div<<8)|(bs/div);int a=input[row+Math.max(0,x-radius)],b=input[row+Math.min(w-1,x+radius+1)];rs+=((b>>16)&255)-((a>>16)&255);gs+=((b>>8)&255)-((a>>8)&255);bs+=(b&255)-(a&255);}
            }
            for(int x=0;x<w;x++){
                int rs=0,gs=0,bs=0;
                for(int i=-radius;i<=radius;i++){int v=pass[Math.max(0,Math.min(h-1,i))*w+x];rs+=(v>>16)&255;gs+=(v>>8)&255;bs+=v&255;}
                for(int y=0;y<h;y++){output[y*w+x]=0xff000000|(rs/div<<16)|(gs/div<<8)|(bs/div);int a=pass[Math.max(0,y-radius)*w+x],b=pass[Math.min(h-1,y+radius+1)*w+x];rs+=((b>>16)&255)-((a>>16)&255);gs+=((b>>8)&255)-((a>>8)&255);bs+=(b&255)-(a&255);}
            }
            Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);out.setPixels(output,0,w,0,0,w,h);return out;
        }
    }
    static class Card extends FrameLayout {
        final Backdrop backdrop;final float radius;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final Path shape=new Path();final RectF rect=new RectF();
        final int[] location=new int[2],origin=new int[2];
        final boolean dense;
        LinearGradient fill,rim;
        Card(Context c,Backdrop backdrop,float radius,boolean dense){
            super(c);this.backdrop=backdrop;this.radius=dp(c,radius);this.dense=dense;
            setWillNotDraw(false);setElevation(dp(c,dense?5:8));
            setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),Card.this.radius);o.setAlpha(.18f);}});
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(backdrop!=null)backdrop.cards.add(this);}
        @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();if(backdrop!=null)backdrop.cards.remove(this);}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){rect.set(.6f,.6f,w-.6f,h-.6f);shape.reset();shape.addRoundRect(rect,radius,radius,Path.Direction.CW);boolean night=dark(getContext());fill=new LinearGradient(0,0,w*.4f,h,night?0x18FFFFFF:0x55FFFFFF,night?0x06000000:0x06FFFFFF,Shader.TileMode.CLAMP);rim=new LinearGradient(0,0,w,h,night?0x50FFFFFF:0xEBFFFFFF,night?0x16FFFFFF:0x57FFFFFF,Shader.TileMode.CLAMP);invalidateOutline();}
        @Override protected void onDraw(Canvas c){
            boolean night=dark(getContext());c.save();c.clipPath(shape);
            if(backdrop!=null&&backdrop.soft!=null){getLocationOnScreen(location);backdrop.getLocationOnScreen(origin);backdrop.drawTexture(c,dense?backdrop.deep:backdrop.soft,origin[0]-location[0],origin[1]-location[1],backdrop.getWidth(),backdrop.getHeight());}
            paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(night?(dense?0xB42B3B46:0x9833444E):(dense?0xC8FAFCFD:0x9EEFF4F6));c.drawRect(rect,paint);
            paint.setShader(fill);c.drawRect(rect,paint);c.restore();
            // One antialiased silhouette, with a soft continuous light rim.
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),.8f));paint.setShader(rim);c.drawRoundRect(rect,radius,radius,paint);paint.setShader(null);paint.setStyle(Paint.Style.FILL);
        }
        @Override protected void dispatchDraw(Canvas c){c.save();c.clipPath(shape);super.dispatchDraw(c);c.restore();}
    }
    static class Meter extends View {
        static final long RESTORE_MS=1200;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float value=-1;ValueAnimator animator;boolean restoring;
        Meter(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(double next,boolean animate){set(next,animate,480);}
        void set(double next,boolean animate,long duration){float n=(float)next;if(animator!=null)animator.cancel();restoring=false;if(value<0||!animate||!ValueAnimator.areAnimatorsEnabled()){value=n;invalidate();return;}animator=ValueAnimator.ofFloat(value,n);animator.setDuration(duration);animator.setInterpolator(new PathInterpolator(.22f,.1f,.2f,1));animator.addUpdateListener(a->{value=(Float)a.getAnimatedValue();invalidate();});animator.start();}
        void restore(double next,TextView label,Runnable done){
            float target=(float)next;if(animator!=null)animator.cancel();animator=null;
            if(value<0||target<0||Math.abs(value-target)<.01f||!ValueAnimator.areAnimatorsEnabled()){value=target;label.setText(target<0?"—":Math.round(target)+"%");invalidate();done.run();return;}
            restoring=true;label.setText(Math.round(value)+"%");animator=ValueAnimator.ofFloat(value,target);animator.setDuration(RESTORE_MS);animator.setInterpolator(new PathInterpolator(.20f,.65f,.30f,1));
            animator.addUpdateListener(a->{value=(Float)a.getAnimatedValue();label.setText(Math.round(value)+"%");invalidate();});
            animator.addListener(new AnimatorListenerAdapter(){boolean cancelled;@Override public void onAnimationCancel(Animator a){cancelled=true;restoring=false;}@Override public void onAnimationEnd(Animator a){restoring=false;invalidate();if(!cancelled){value=target;label.setText(Math.round(target)+"%");done.run();}}});animator.start();
        }
        @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){
            float h=dp(getContext(),5),y=(getHeight()-h)/2f,right=getWidth()*Math.min(1,Math.max(0,value/100));
            paint.setShader(null);paint.setColor(alpha(ink(getContext()),.07f));c.drawRoundRect(new RectF(0,y,getWidth(),y+h),h/2,h/2,paint);
            if(value<0||right<=0)return;paint.setColor(restoring?accent(getContext()):value<=20?0xFFC18960:accent(getContext()));RectF fill=new RectF(0,y,right,y+h);c.drawRoundRect(fill,h/2,h/2,paint);
            if(restoring){
                // A restrained highlight inside the real quota fill; no separate loading meter.
                paint.setShader(new LinearGradient(0,y,0,y+h,new int[]{0x28FFFFFF,0x0EFFFFFF,0x00FFFFFF},null,Shader.TileMode.CLAMP));c.drawRoundRect(fill,h/2,h/2,paint);paint.setShader(null);
            }
        }
    }
    static class Icon extends View {
        final String kind;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c,String kind){super(c);this.kind=kind;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            canvas.save();float scale=Math.min(getWidth(),getHeight())/24f;canvas.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);canvas.scale(scale,scale);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(ink(getContext()));
            if("refresh".equals(kind)){canvas.drawArc(new RectF(5,5,19,19),35,295,false,p);canvas.drawLine(19,4,19,9,p);canvas.drawLine(19,9,14,9,p);}
            else if("history".equals(kind)){canvas.drawCircle(12,12,8,p);canvas.drawLine(12,7,12,12,p);canvas.drawLine(12,12,15.5f,14,p);}
            else if("settings".equals(kind)){for(int i=0;i<3;i++){float y=6+i*6;canvas.drawLine(4,y,20,y,p);p.setStyle(Paint.Style.FILL);p.setColor(dark(getContext())?0xFF31434D:0xFFEAF2F1);canvas.drawCircle(i==1?9:16,y,2.5f,p);p.setStyle(Paint.Style.STROKE);p.setColor(ink(getContext()));canvas.drawCircle(i==1?9:16,y,2.5f,p);}}
            else if("add".equals(kind)){canvas.drawLine(12,5,12,19,p);canvas.drawLine(5,12,19,12,p);}
            else if("arrow".equals(kind)){canvas.drawLine(9,6,15,12,p);canvas.drawLine(15,12,9,18,p);}
            else if("check".equals(kind)){canvas.drawLine(5,12,10,17,p);canvas.drawLine(10,17,19,7,p);}
            else {canvas.drawRoundRect(new RectF(4,4,20,20),5,5,p);canvas.drawLine(8,15,8,11,p);canvas.drawLine(12,15,12,8,p);canvas.drawLine(16,15,16,12,p);}
            canvas.restore();
        }
    }
    static class Chart extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);JSONArray rows;final boolean weekly;
        Chart(Context c,JSONArray rows,boolean weekly){super(c);this.rows=rows;this.weekly=weekly;setContentDescription("本机采样的剩余额度变化记录");}
        @Override protected void onDraw(Canvas c){
            float left=dp(getContext(),2),top=dp(getContext(),10),right=getWidth()-dp(getContext(),2),bottom=getHeight()-dp(getContext(),26);long now=System.currentTimeMillis()/1000,start=now-(weekly?7*86400:86400);
            p.setColor(alpha(ink(getContext()),.07f));p.setStrokeWidth(dp(getContext(),1));for(int i=0;i<3;i++){float y=top+(bottom-top)*i/2;c.drawLine(left,y,right,y,p);}
            Path primary=new Path(),secondary=new Path();int points=0;boolean firstP=true,firstS=true;
            for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;long at=r.optLong("at",0);if(at<start||at>now)continue;float x=left+(right-left)*(at-start)/(float)(now-start);
                double a=r.optDouble("primary",-1),b=r.optDouble("secondary",-1);
                if(a>=0){float y=bottom-(bottom-top)*(float)a/100;if(firstP){primary.moveTo(x,y);firstP=false;}else primary.lineTo(x,y);points++;}
                if(b>=0){float y=bottom-(bottom-top)*(float)b/100;if(firstS){secondary.moveTo(x,y);firstS=false;}else secondary.lineTo(x,y);}
            }
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(getContext(),2));p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(accent(getContext()));c.drawPath(primary,p);p.setColor(0xFF8799C9);c.drawPath(secondary,p);p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(getContext(),11));p.setColor(secondary(getContext()));c.drawText(weekly?"7 天前":"24 小时前",left,getHeight()-dp(getContext(),3),p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("现在",right,getHeight()-dp(getContext(),3),p);p.setTextAlign(Paint.Align.LEFT);
            if(points<2){p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(getContext(),13));c.drawText("同步后，变化会慢慢留在这里",getWidth()/2f,(top+bottom)/2,p);p.setTextAlign(Paint.Align.LEFT);}
        }
    }
    static class Celebration extends View {
        static final long DURATION_MS=1600;
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float progress=1,cx,cy;ValueAnimator animator;boolean confetti;
        final float[] speed=new float[32],lift=new float[32],spin=new float[32];
        final int[] colors={0xFF8BBEAD,0xFF98ADD2,0xFFB4A4CB,0xFFD4BC87};
        Celebration(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);}
        void play(float x,float y,boolean paper){
            stop();if(!ValueAnimator.areAnimatorsEnabled())return;cx=x;cy=y;confetti=paper;
            Random random=new Random();for(int i=0;i<speed.length;i++){speed[i]=55+random.nextFloat()*105;lift[i]=105+random.nextFloat()*95;spin[i]=-190+random.nextFloat()*380;}
            animator=ValueAnimator.ofFloat(0,1);animator.setDuration(DURATION_MS);animator.setInterpolator(new LinearInterpolator());animator.addUpdateListener(a->{progress=(Float)a.getAnimatedValue();invalidate();});animator.start();
        }
        void stop(){if(animator!=null){animator.cancel();animator=null;}progress=1;invalidate();}
        @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){
            if(progress>=1)return;
            // Preserve the original expanding halo and five outward light points.
            float q=Math.min(1,progress*DURATION_MS/950f),ease=1-(1-q)*(1-q),radius=dp(getContext(),24+ease*70);
            if(q<1){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(getContext(),2-ease));p.setColor(alpha(accent(getContext()),(1-ease)*.5f));c.drawCircle(cx,cy,radius,p);p.setStyle(Paint.Style.FILL);for(int i=0;i<5;i++){double angle=(-150+i*32)*Math.PI/180;float x=cx+(float)Math.cos(angle)*radius,y=cy+(float)Math.sin(angle)*radius;p.setColor(alpha(i%2==0?accent(getContext()):0xFF98A9DA,1-ease));c.drawCircle(x,y,dp(getContext(),3*(1-ease)+1),p);}}
            if(!confetti)return;
            p.setStyle(Paint.Style.FILL);float density=getResources().getDisplayMetrics().density,t=progress*1.6f;
            float spread=Math.min(getWidth()*.35f,dp(getContext(),125));
            for(int i=0;i<speed.length;i++){
                float local=t-(i%4)*.022f;if(local<0)continue;float direction=i%2==0?1:-1;
                float x=cx-direction*spread+direction*speed[i]*density*local,y=cy+dp(getContext(),58)-lift[i]*density*local+dp(getContext(),92)*local*local;
                float fade=Math.min(1,local/.07f)*Math.max(0,Math.min(1,(1.6f-local)/.65f));
                p.setColor(alpha(colors[i%colors.length],fade*.88f));c.save();c.translate(x,y);c.rotate(i*31+spin[i]*local);
                float w=dp(getContext(),2.7f)*(float)(.3+.7*Math.abs(Math.cos(local*6+i))),h=dp(getContext(),1.3f+(i%3)*.3f);
                c.drawRoundRect(-w,-h,w,h,dp(getContext(),.6f),dp(getContext(),.6f),p);c.restore();
            }
        }
    }
}
