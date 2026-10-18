using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using System.Threading;
using System.Threading.Tasks;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media.Animation;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

// Native animation clocks drive the light points and paper without PowerShell frame callbacks.
public sealed class ResetCelebration : FrameworkElement {
    public static readonly DependencyProperty ProgressProperty=DependencyProperty.Register("Progress",typeof(double),typeof(ResetCelebration),new FrameworkPropertyMetadata(1.0,FrameworkPropertyMetadataOptions.AffectsRender));
    public double Progress {get{return (double)GetValue(ProgressProperty);}set{SetValue(ProgressProperty,value);}}
    private readonly double[] speed=new double[32],lift=new double[32],spin=new double[32];
    private readonly SolidColorBrush[] paper={Brush(139,190,173),Brush(152,173,210),Brush(180,164,203),Brush(212,188,135)};
    private SolidColorBrush accent=Brush(57,138,120);
    private Point origin;
    public bool Confetti {get;private set;}
    public int Plays {get;private set;}
    public ResetCelebration(){IsHitTestVisible=false;Focusable=false;ClipToBounds=true;}
    private static SolidColorBrush Brush(byte r,byte g,byte b){var brush=new SolidColorBrush(Color.FromRgb(r,g,b));brush.Freeze();return brush;}
    public void Play(double x,double y,Color color,bool confetti){
        Stop();if(!SystemParameters.ClientAreaAnimation)return;
        origin=new Point(x,y);accent=new SolidColorBrush(color);accent.Freeze();Confetti=confetti;Plays++;
        var random=new Random();for(int i=0;i<speed.Length;i++){speed[i]=35+random.NextDouble()*65;lift[i]=65+random.NextDouble()*75;spin[i]=-190+random.NextDouble()*380;}
        BeginAnimation(ProgressProperty,new DoubleAnimation(0,1,TimeSpan.FromMilliseconds(1600)));
    }
    public void Stop(){BeginAnimation(ProgressProperty,null);Progress=1;}
    protected override void OnRender(DrawingContext draw){
        double progress=Progress;if(progress>=1||ActualWidth<1)return;
        draw.PushClip(new RectangleGeometry(new Rect(0,0,ActualWidth,ActualHeight),22,22));
        // Original 620 ms halo with three outward light points.
        double q=Math.Min(1,progress*1600/620),ease=1-Math.Pow(1-q,3);
        if(q<1){
            draw.PushOpacity(.45*(1-ease));draw.DrawEllipse(null,new Pen(accent,1.2),origin,31*(.65+.9*ease),31*(.65+.9*ease));draw.Pop();
            draw.PushOpacity((1-ease)*.85);
            draw.DrawEllipse(accent,null,new Point(origin.X-26*ease,origin.Y-32*ease),2,2);
            draw.DrawEllipse(paper[1],null,new Point(origin.X+35*ease,origin.Y-8*ease),2.5,2.5);
            draw.DrawEllipse(accent,null,new Point(origin.X+17*ease,origin.Y+31*ease),2,2);draw.Pop();
        }
        if(!Confetti){draw.Pop();return;}
        double t=progress*1.6,spread=Math.Min(ActualWidth*.35,105),startY=Math.Min(ActualHeight-14,origin.Y+55);
        for(int i=0;i<speed.Length;i++){
            double local=t-(i%4)*.022;if(local<0)continue;double direction=i%2==0?1:-1;
            double x=origin.X-direction*spread+direction*speed[i]*local,y=startY-lift[i]*local+78*local*local;
            double alpha=Math.Min(1,local/.07)*Math.Max(0,Math.Min(1,(1.6-local)/.65));
            double width=2.2*(.3+.7*Math.Abs(Math.Cos(local*6+i))),height=1+(i%3)*.25;
            draw.PushOpacity(alpha*.9);draw.PushTransform(new TranslateTransform(x,y));draw.PushTransform(new RotateTransform(i*31+spin[i]*local));
            draw.DrawRoundedRectangle(paper[i%paper.Length],null,new Rect(-width,-height,width*2,height*2),.55,.55);
            draw.Pop();draw.Pop();draw.Pop();
        }
        draw.Pop();
    }
}
public sealed class ResetSounds : IDisposable {
    private System.Media.SoundPlayer click,success;
    public bool Ready {get{return click!=null&&success!=null;}}
    public ResetSounds(string directory){
        try{click=new System.Media.SoundPlayer(System.IO.Path.Combine(directory,"reset-click.wav"));click.Load();success=new System.Media.SoundPlayer(System.IO.Path.Combine(directory,"reset-success.wav"));success.Load();}
        catch{Dispose();}
    }
    public void Click(){if(click!=null)try{click.Play();}catch{}}
    public void Success(){if(success!=null)try{success.Play();}catch{}}
    public void Stop(){if(click!=null)click.Stop();if(success!=null)success.Stop();}
    public void Dispose(){if(click!=null){click.Dispose();click=null;}if(success!=null){success.Dispose();success=null;}}
}

// Native value notifications keep the percentages and expanded rings in step with the bars.
public sealed class QuotaRefillText : IDisposable {
    private readonly System.Windows.Controls.ProgressBar bar;
    private readonly System.Windows.Controls.TextBlock[] labels;
    private readonly System.Windows.Shapes.Path ring;
    private readonly double center;
    private bool finished;
    public QuotaRefillText(System.Windows.Controls.ProgressBar bar,System.Windows.Controls.TextBlock value,System.Windows.Controls.TextBlock detail,System.Windows.Controls.TextBlock peek,System.Windows.Shapes.Path ring,double center) {
        this.bar=bar;this.labels=new[]{value,detail,peek};this.ring=ring;this.center=center;
        bar.ValueChanged+=Changed;Update();
    }
    private void Changed(object sender,RoutedPropertyChangedEventArgs<double> e){Update();}
    private void Update(){
        double percent=bar.Value;string text=(finished?percent.ToString("0.#",System.Globalization.CultureInfo.InvariantCulture):Math.Floor(percent+.5).ToString("0",System.Globalization.CultureInfo.InvariantCulture))+"%";
        foreach(var label in labels)if(label.Text!=text)label.Text=text;
        if(ring==null||!ring.IsVisible)return;
        double radius=center-5.5,angle=Math.Max(.1,Math.Min(359.9,percent*3.6))*Math.PI/180,end=-Math.PI/2+angle;
        var geometry=new System.Windows.Media.StreamGeometry();
        using(var draw=geometry.Open()){
            draw.BeginFigure(new Point(center,center-radius),false,false);
            draw.ArcTo(new Point(center+radius*Math.Cos(end),center+radius*Math.Sin(end)),new Size(radius,radius),0,angle>Math.PI,System.Windows.Media.SweepDirection.Clockwise,true,false);
        }
        geometry.Freeze();ring.Data=geometry;
    }
    public void Dispose(){bar.ValueChanged-=Changed;finished=true;Update();}
}

public static class GlassCapture {
    internal static byte[] Blur(byte[] input,int width,int height,int radius) {
        byte[] output=new byte[input.Length];
        BlurInto(input,width,height,radius,new byte[input.Length],output,new int[width*3]);
        return output;
    }
    // The same two box passes as before, with RGB accumulated together and
    // the vertical pass walking contiguous rows instead of strided columns.
    internal static void BlurInto(byte[] input,int width,int height,int radius,byte[] pass,byte[] output,int[] sums) {
        int count=radius*2+1;
        for(int y=0;y<height;y++) {
            int row=y*width*4,b=0,g=0,r=0;
            for(int i=-radius;i<=radius;i++) {
                int at=row+Math.Max(0,Math.Min(width-1,i))*4;
                b+=input[at];g+=input[at+1];r+=input[at+2];
            }
            for(int x=0;x<width;x++) {
                int at=row+x*4;
                pass[at]=(byte)(b/count);pass[at+1]=(byte)(g/count);pass[at+2]=(byte)(r/count);pass[at+3]=255;
                int remove=row+Math.Max(0,x-radius)*4,add=row+Math.Min(width-1,x+radius+1)*4;
                b+=input[add]-input[remove];g+=input[add+1]-input[remove+1];r+=input[add+2]-input[remove+2];
            }
        }
        Array.Clear(sums,0,sums.Length);
        for(int i=-radius;i<=radius;i++) {
            int row=Math.Max(0,Math.Min(height-1,i))*width*4;
            for(int x=0;x<width;x++) {int at=row+x*4,s=x*3;sums[s]+=pass[at];sums[s+1]+=pass[at+1];sums[s+2]+=pass[at+2];}
        }
        for(int y=0;y<height;y++) {
            int row=y*width*4,remove=Math.Max(0,y-radius)*width*4,add=Math.Min(height-1,y+radius+1)*width*4;
            for(int x=0;x<width;x++) {
                int at=row+x*4,s=x*3,a=add+x*4,z=remove+x*4;
                output[at]=(byte)(sums[s]/count);output[at+1]=(byte)(sums[s+1]/count);output[at+2]=(byte)(sums[s+2]/count);output[at+3]=255;
                sums[s]+=pass[a]-pass[z];sums[s+1]+=pass[a+1]-pass[z+1];sums[s+2]+=pass[a+2]-pass[z+2];
            }
        }
    }
}

public sealed class GlassScreenFrame {
    public int Left, Top, NativeWidth, NativeHeight, Width, Height;
    public byte[] Pixels;
    internal int PixelLeft,PixelTop,CanvasWidth,CanvasHeight;
    internal Action ReleasePixels;
    internal void Release() { Action release=Interlocked.Exchange(ref ReleasePixels,null);if(release!=null)release(); }
    public GlassScreenFrame(int left,int top,int nativeWidth,int nativeHeight,int width,int height,byte[] pixels) {
        Left=left;Top=top;NativeWidth=nativeWidth;NativeHeight=nativeHeight;
        Width=width;Height=height;Pixels=pixels;
    }
}

// Keep a screen-space material texture. Moving the widget only changes its
// viewbox, so native dragging never waits for a screen grab or CPU blur.
public static class GlassScreenCapture {
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct BitmapInfo {
        public uint Size; public int Width, Height; public ushort Planes, BitCount;
        public uint Compression, ImageSize; public int XPelsPerMeter, YPelsPerMeter;
        public uint ClrUsed, ClrImportant;
    }
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd,out Rect rect);
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] static extern bool SetWindowDisplayAffinity(IntPtr hwnd,uint affinity);
    [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr hwnd,IntPtr dc);
    [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr dc);
    [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr dc,IntPtr value);
    [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr value);
    [DllImport("gdi32.dll")] static extern IntPtr CreateDIBSection(IntPtr dc,ref BitmapInfo info,uint usage,out IntPtr bits,IntPtr section,uint offset);
    [DllImport("gdi32.dll")] static extern int SetStretchBltMode(IntPtr dc,int mode);
    [DllImport("gdi32.dll")] static extern bool StretchBlt(IntPtr destination,int dx,int dy,int dw,int dh,
        IntPtr source,int sx,int sy,int sw,int sh,uint operation);
    static ulong lastHash;
    static int lastLeft,lastTop,lastWidth,lastHeight;
    static readonly object captureGate=new object();
    static bool paused;

    internal static GlassScreenFrame Canvas() {
        int left=GetSystemMetrics(76),top=GetSystemMetrics(77),width=GetSystemMetrics(78),height=GetSystemMetrics(79);
        if(width<=0||height<=0)return null;
        double scale=Math.Min(1.0,Math.Min(960.0/width,600.0/height));
        return new GlassScreenFrame(left,top,width,height,Math.Max(1,(int)Math.Round(width*scale)),Math.Max(1,(int)Math.Round(height*scale)),null);
    }
    internal static bool SameCanvas(GlassScreenFrame a,GlassScreenFrame b) {
        return a!=null&&b!=null&&a.Left==b.Left&&a.Top==b.Top&&a.NativeWidth==b.NativeWidth&&a.NativeHeight==b.NativeHeight;
    }
    internal static Int32Rect Tile(GlassScreenFrame canvas,IntPtr hwnd) {
        Rect rect;if(!GetWindowRect(hwnd,out rect))return Int32Rect.Empty;
        double sx=(double)canvas.Width/canvas.NativeWidth,sy=(double)canvas.Height/canvas.NativeHeight;
        int padding=Math.Max(5,(int)Math.Round(12*Math.Min(sx,sy)))+4;
        int left=Math.Max(0,(int)Math.Floor((rect.Left-canvas.Left)*sx)-padding),top=Math.Max(0,(int)Math.Floor((rect.Top-canvas.Top)*sy)-padding);
        int right=Math.Min(canvas.Width,(int)Math.Ceiling((rect.Right-canvas.Left)*sx)+padding),bottom=Math.Min(canvas.Height,(int)Math.Ceiling((rect.Bottom-canvas.Top)*sy)+padding);
        return right>left&&bottom>top?new Int32Rect(left,top,right-left,bottom-top):Int32Rect.Empty;
    }
    // One reusable DC/DIB and three leased output buffers. A pending frame may
    // be replaced, but a buffer being uploaded by WPF is never overwritten.
    internal sealed class Surface : IDisposable {
        sealed class Buffer {
            internal byte[] Pixels;internal int Used;
            internal Buffer(int size){Pixels=new byte[size];}
            internal void Release(){Interlocked.Exchange(ref Used,0);}
        }
        IntPtr screen,memory,dib,previous,bits;
        int width,height;byte[] pixels,pass;int[] sums;Buffer[] buffers;
        ulong lastHash;int lastLeft,lastTop;bool hasHash;
        void Resize(int w,int h) {
            if(w==width&&h==height&&dib!=IntPtr.Zero)return;
            Dispose();screen=GetDC(IntPtr.Zero);if(screen==IntPtr.Zero)throw new InvalidOperationException("Cannot read the screen DC");
            memory=CreateCompatibleDC(screen);if(memory==IntPtr.Zero)throw new InvalidOperationException("Cannot allocate the material DC");
            var info=new BitmapInfo{Size=(uint)Marshal.SizeOf(typeof(BitmapInfo)),Width=w,Height=-h,Planes=1,BitCount=32,ImageSize=(uint)(w*h*4)};
            dib=CreateDIBSection(memory,ref info,0,out bits,IntPtr.Zero,0);
            if(dib==IntPtr.Zero||bits==IntPtr.Zero)throw new InvalidOperationException("Cannot allocate the material bitmap");
            previous=SelectObject(memory,dib);SetStretchBltMode(memory,3);
            width=w;height=h;pixels=new byte[w*h*4];pass=new byte[pixels.Length];sums=new int[w*3];
            buffers=new[]{new Buffer(pixels.Length),new Buffer(pixels.Length),new Buffer(pixels.Length)};hasHash=false;
        }
        internal GlassScreenFrame Capture(IntPtr hwnd,GlassScreenFrame canvas,Int32Rect tile,bool full) {
            if(tile.IsEmpty||tile.Width<=0||tile.Height<=0)return null;
            Resize(tile.Width,tile.Height);
            int left=canvas.Left+(int)Math.Round((double)tile.X*canvas.NativeWidth/canvas.Width),top=canvas.Top+(int)Math.Round((double)tile.Y*canvas.NativeHeight/canvas.Height);
            int right=canvas.Left+(int)Math.Round((double)(tile.X+tile.Width)*canvas.NativeWidth/canvas.Width),bottom=canvas.Top+(int)Math.Round((double)(tile.Y+tile.Height)*canvas.NativeHeight/canvas.Height);
            lock(captureGate) {
                if(paused||!SetWindowDisplayAffinity(hwnd,0x11))return null;
                try {
                    if(!StretchBlt(memory,0,0,width,height,screen,left,top,right-left,bottom-top,0x00CC0020))throw new InvalidOperationException("Cannot capture the screen material");
                    Marshal.Copy(bits,pixels,0,pixels.Length);
                }finally{SetWindowDisplayAffinity(hwnd,0);}
            }
            ulong hash=14695981039346656037UL;
            unchecked{for(int i=0;i<pixels.Length;i+=4){hash=(hash^pixels[i])*1099511628211UL;hash=(hash^pixels[i+1])*1099511628211UL;hash=(hash^pixels[i+2])*1099511628211UL;}}
            if(!full&&hasHash&&hash==lastHash&&left==lastLeft&&top==lastTop)return null;
            Buffer buffer=null;foreach(Buffer candidate in buffers)if(Interlocked.CompareExchange(ref candidate.Used,1,0)==0){buffer=candidate;break;}
            if(buffer==null)return null;
            try {
                double scale=Math.Min((double)canvas.Width/canvas.NativeWidth,(double)canvas.Height/canvas.NativeHeight);
                GlassCapture.BlurInto(pixels,width,height,Math.Max(5,(int)Math.Round(12*scale)),pass,buffer.Pixels,sums);
                lastHash=hash;lastLeft=left;lastTop=top;hasHash=true;
                var result=new GlassScreenFrame(canvas.Left,canvas.Top,canvas.NativeWidth,canvas.NativeHeight,width,height,buffer.Pixels);
                result.PixelLeft=tile.X;result.PixelTop=tile.Y;result.CanvasWidth=canvas.Width;result.CanvasHeight=canvas.Height;result.ReleasePixels=buffer.Release;
                return result;
            }catch{buffer.Release();throw;}
        }
        public void Dispose() {
            if(previous!=IntPtr.Zero&&memory!=IntPtr.Zero)SelectObject(memory,previous);
            if(dib!=IntPtr.Zero)DeleteObject(dib);if(memory!=IntPtr.Zero)DeleteDC(memory);if(screen!=IntPtr.Zero)ReleaseDC(IntPtr.Zero,screen);
            previous=dib=memory=screen=bits=IntPtr.Zero;
        }
    }

    // Only wait for the short GDI capture section, never the blur calculation.
    // This keeps capture-exclusion changes out of native moves and resizes.
    public static void SetPaused(bool value) { lock(captureGate) { paused=value; } }

    public static Task<GlassScreenFrame> CaptureAsync(IntPtr hwnd,int left,int top,int width,int height) {
        return Task.Run(() => Capture(hwnd,left,top,width,height));
    }
    static GlassScreenFrame Capture(IntPtr hwnd,int left,int top,int width,int height) {
        if (hwnd==IntPtr.Zero || width<=0 || height<=0) return null;
        double scale=Math.Min(1.0,Math.Min(960.0/width,600.0/height));
        int scaledWidth=Math.Max(1,(int)Math.Round(width*scale));
        int scaledHeight=Math.Max(1,(int)Math.Round(height*scale));
        byte[] pixels=new byte[scaledWidth*scaledHeight*4];
        lock(captureGate) {
          if (paused || !SetWindowDisplayAffinity(hwnd,0x11)) return null;
          try {
            IntPtr screen=GetDC(IntPtr.Zero), memory=IntPtr.Zero, dib=IntPtr.Zero, previous=IntPtr.Zero, bits=IntPtr.Zero;
            if (screen==IntPtr.Zero) throw new InvalidOperationException("Cannot read the screen DC");
            try {
                memory=CreateCompatibleDC(screen);
                if (memory==IntPtr.Zero) throw new InvalidOperationException("Cannot allocate the material DC");
                var info=new BitmapInfo { Size=(uint)Marshal.SizeOf(typeof(BitmapInfo)),Width=scaledWidth,Height=-scaledHeight,
                    Planes=1,BitCount=32,Compression=0,ImageSize=(uint)pixels.Length };
                dib=CreateDIBSection(memory,ref info,0,out bits,IntPtr.Zero,0);
                if (dib==IntPtr.Zero || bits==IntPtr.Zero) throw new InvalidOperationException("Cannot allocate the material bitmap");
                previous=SelectObject(memory,dib);
                SetStretchBltMode(memory,3); // fast downsampling; the material blur smooths the result
                if (!StretchBlt(memory,0,0,scaledWidth,scaledHeight,screen,left,top,width,height,0x00CC0020))
                    throw new InvalidOperationException("Cannot capture the screen material");
                Marshal.Copy(bits,pixels,0,pixels.Length);
            } finally {
                if (previous!=IntPtr.Zero && memory!=IntPtr.Zero) SelectObject(memory,previous);
                if (dib!=IntPtr.Zero) DeleteObject(dib);
                if (memory!=IntPtr.Zero) DeleteDC(memory);
                ReleaseDC(IntPtr.Zero,screen);
            }
          } finally { SetWindowDisplayAffinity(hwnd,0); }
        }
        ulong hash=14695981039346656037UL;
        unchecked {
            for(int i=0;i<pixels.Length;i+=4) {
                hash=(hash^pixels[i])*1099511628211UL;
                hash=(hash^pixels[i+1])*1099511628211UL;
                hash=(hash^pixels[i+2])*1099511628211UL;
            }
        }
        if (hash==lastHash && left==lastLeft && top==lastTop && width==lastWidth && height==lastHeight) return null;
        byte[] blurred=GlassCapture.Blur(pixels,scaledWidth,scaledHeight,Math.Max(5,(int)Math.Round(12*scale)));
        lastHash=hash;lastLeft=left;lastTop=top;lastWidth=width;lastHeight=height;
        return new GlassScreenFrame(left,top,width,height,scaledWidth,scaledHeight,blurred);
    }
    public static double[] Viewport(GlassScreenFrame frame,IntPtr hwnd,double offsetX,double offsetY,double dpiX,double dpiY) {
        Rect rect;
        if (frame==null || !GetWindowRect(hwnd,out rect)) return null;
        return new double[] {
            (frame.Left-rect.Left)/dpiX-offsetX, (frame.Top-rect.Top)/dpiY-offsetY,
            frame.NativeWidth/dpiX, frame.NativeHeight/dpiY
        };
    }
}

// Capture and blur off the dispatcher. Only the newest completed tile is
// uploaded; the full screen-space texture stays available for instant drags.
public sealed class GlassMaterialRenderer : IDisposable {
    readonly IntPtr hwnd;readonly ImageBrush brush;readonly System.Windows.Controls.Border outer;readonly Window window;
    readonly AutoResetEvent wake=new AutoResetEvent(false);readonly Thread worker;readonly object mailbox=new object();
    GlassScreenFrame latest;volatile GlassScreenFrame canvas;WriteableBitmap bitmap;bool presentQueued;
    List<double> measuredIntervals;long previousPresentation;
    volatile bool stopped,isPaused;int refreshAll=1;long presented,captures,unchanged;
    public GlassScreenFrame Frame {get{return canvas;}}
    public long PresentedFrames {get{return Interlocked.Read(ref presented);}}
    public long CaptureAttempts {get{return Interlocked.Read(ref captures);}}
    public long UnchangedFrames {get{return Interlocked.Read(ref unchanged);}}
    public string LastError {get;private set;}
    public int LastTilePixels {get;private set;}
    public void StartMeasurement(){measuredIntervals=new List<double>();previousPresentation=0;}
    public double[] StopMeasurement(){double[] result=measuredIntervals==null?new double[0]:measuredIntervals.ToArray();measuredIntervals=null;return result;}
    [DllImport("winmm.dll")]static extern uint timeBeginPeriod(uint value);
    [DllImport("winmm.dll")]static extern uint timeEndPeriod(uint value);
    public GlassMaterialRenderer(Window window,System.Windows.Controls.Border outer,ImageBrush brush) {
        this.window=window;this.outer=outer;this.brush=brush;hwnd=new System.Windows.Interop.WindowInteropHelper(window).Handle;
        if(hwnd==IntPtr.Zero)throw new InvalidOperationException("The glass window is not initialized");
        GlassScreenCapture.SetPaused(false);
        worker=new Thread(Run){IsBackground=true,Name="Codex glass material"};worker.Start();
    }
    public void SetPaused(bool value) {
        if(stopped||isPaused==value)return;isPaused=value;GlassScreenCapture.SetPaused(value);
        if(!value)Interlocked.Exchange(ref refreshAll,1);wake.Set();
    }
    void Publish(GlassScreenFrame frame) {
        lock(mailbox) {
            if(stopped||isPaused){frame.Release();return;}
            if(latest!=null)latest.Release();latest=frame;
            if(presentQueued)return;presentQueued=true;
        }
        window.Dispatcher.BeginInvoke(DispatcherPriority.Render,new Action(Present));
    }
    void Present() {
        GlassScreenFrame frame;
        lock(mailbox){frame=latest;latest=null;presentQueued=false;}
        if(frame==null)return;
        try {
            if(stopped||isPaused)return;
            bool full=frame.Width==frame.CanvasWidth&&frame.Height==frame.CanvasHeight;
            if(bitmap==null||!GlassScreenCapture.SameCanvas(canvas,frame)) {
                if(!full){Interlocked.Exchange(ref refreshAll,1);return;}
                bitmap=new WriteableBitmap(frame.CanvasWidth,frame.CanvasHeight,96,96,PixelFormats.Bgra32,null);
                canvas=new GlassScreenFrame(frame.Left,frame.Top,frame.NativeWidth,frame.NativeHeight,frame.CanvasWidth,frame.CanvasHeight,null);
                brush.Viewbox=new Rect(0,0,canvas.Width,canvas.Height);brush.ImageSource=bitmap;
            }
            bitmap.WritePixels(new Int32Rect(frame.PixelLeft,frame.PixelTop,frame.Width,frame.Height),frame.Pixels,frame.Width*4,0);
            var source=System.Windows.Interop.HwndSource.FromHwnd(hwnd);
            if(source!=null&&source.CompositionTarget!=null) {
                Matrix dpi=source.CompositionTarget.TransformToDevice;
                double[] viewport=GlassScreenCapture.Viewport(canvas,hwnd,System.Windows.Controls.Canvas.GetLeft(outer),System.Windows.Controls.Canvas.GetTop(outer),dpi.M11,dpi.M22);
                if(viewport!=null)brush.Viewport=new Rect(viewport[0],viewport[1],viewport[2],viewport[3]);
            }
            Interlocked.Increment(ref presented);LastError=null;
            LastTilePixels=frame.Width*frame.Height;
            if(measuredIntervals!=null){long now=Stopwatch.GetTimestamp();if(previousPresentation!=0)measuredIntervals.Add((now-previousPresentation)*1000.0/Stopwatch.Frequency);previousPresentation=now;}
        }catch(Exception error){LastError=error.Message;}finally{frame.Release();}
    }
    void Run() {
        bool highResolution=timeBeginPeriod(1)==0;
        using(var local=new GlassScreenCapture.Surface())using(var full=new GlassScreenCapture.Surface()) {
            try {
                while(!stopped) {
                    if(isPaused){if(highResolution){timeEndPeriod(1);highResolution=false;}wake.WaitOne();continue;}
                    if(!highResolution)highResolution=timeBeginPeriod(1)==0;
                    var watch=Stopwatch.StartNew();
                    try {
                        var next=GlassScreenCapture.Canvas();
                        if(next==null){wake.WaitOne(250);continue;}
                        bool all=Interlocked.Exchange(ref refreshAll,0)!=0||!GlassScreenCapture.SameCanvas(canvas,next);
                        var tile=all?new Int32Rect(0,0,next.Width,next.Height):GlassScreenCapture.Tile(next,hwnd);
                        var frame=(all?full:local).Capture(hwnd,next,tile,all);Interlocked.Increment(ref captures);
                        if(frame!=null)Publish(frame);else Interlocked.Increment(ref unchanged);
                        // Do not let the first tile replace an initial whole-screen
                        // frame before the UI has installed its reusable bitmap.
                        if(all&&canvas==null)while(!stopped&&!isPaused&&canvas==null)wake.WaitOne(2);
                    }catch(Exception error){LastError=error.Message;wake.WaitOne(250);}
                    int remaining=(int)Math.Ceiling(1000.0/60-watch.Elapsed.TotalMilliseconds);
                    if(remaining>0&&!stopped)wake.WaitOne(remaining);
                }
            }finally{if(highResolution)timeEndPeriod(1);}
        }
    }
    public void Dispose() {
        if(stopped)return;stopped=true;wake.Set();GlassScreenCapture.SetPaused(true);
        lock(mailbox){if(latest!=null){latest.Release();latest=null;}}
        worker.Join(1000);
        if(!worker.IsAlive)wake.Dispose();
        GlassScreenCapture.SetPaused(false);
    }
}

public sealed class GlassCornerAnimation : AnimationTimeline {
    public static readonly DependencyProperty FromProperty=DependencyProperty.Register(
        "From",typeof(CornerRadius),typeof(GlassCornerAnimation));
    public static readonly DependencyProperty ToProperty=DependencyProperty.Register(
        "To",typeof(CornerRadius),typeof(GlassCornerAnimation));
    public CornerRadius From { get { return (CornerRadius)GetValue(FromProperty); } set { SetValue(FromProperty,value); } }
    public CornerRadius To { get { return (CornerRadius)GetValue(ToProperty); } set { SetValue(ToProperty,value); } }
    public override Type TargetPropertyType { get { return typeof(CornerRadius); } }
    protected override Freezable CreateInstanceCore() { return new GlassCornerAnimation(); }
    public override object GetCurrentValue(object origin,object destination,AnimationClock clock) {
        double t=clock.CurrentProgress.HasValue ? clock.CurrentProgress.Value : 0.0;
        t=t*t*(3.0-2.0*t);
        return new CornerRadius(
            From.TopLeft+(To.TopLeft-From.TopLeft)*t,
            From.TopRight+(To.TopRight-From.TopRight)*t,
            From.BottomRight+(To.BottomRight-From.BottomRight)*t,
            From.BottomLeft+(To.BottomLeft-From.BottomLeft)*t);
    }
}

public static class WidgetWindow {
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left,Top,Right,Bottom; }
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd,out Rect rect);
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd,IntPtr after,int x,int y,int width,int height,uint flags);
    [DllImport("user32.dll")] static extern bool ReleaseCapture();
    [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hwnd,uint message,IntPtr wparam,IntPtr lparam);
    public static void Resize(IntPtr hwnd,int edge) {
        ReleaseCapture(); SendMessage(hwnd,0x00A1,new IntPtr(edge),IntPtr.Zero);
    }
    public static void Place(IntPtr hwnd,int x,int y,int width,int height) {
        Rect current;
        if (GetWindowRect(hwnd,out current) && current.Left==x && current.Top==y && current.Right-current.Left==width && current.Bottom-current.Top==height) return;
        SetWindowPos(hwnd,IntPtr.Zero,x,y,width,height,0x0014); // no z-order change, no activation
    }
    public static int[] Bounds(IntPtr hwnd) {
        Rect rect;
        if (!GetWindowRect(hwnd,out rect)) return null;
        return new int[] { rect.Left,rect.Top,rect.Right-rect.Left,rect.Bottom-rect.Top };
    }
    public static long HitTest(IntPtr hwnd,int screenX,int screenY) {
        return SendMessage(hwnd,0x84,IntPtr.Zero,new IntPtr((screenY<<16)|(screenX&0xffff))).ToInt64();
    }
    public static void AttachHitTest(Window window,System.Windows.Controls.Border surface) {
        var source=System.Windows.Interop.HwndSource.FromHwnd(
            new System.Windows.Interop.WindowInteropHelper(window).Handle);
        source.AddHook((IntPtr hwnd,int message,IntPtr wparam,IntPtr lparam,ref bool handled) => {
            if (message!=0x84) return IntPtr.Zero;
            long packed=lparam.ToInt64();
            var point=window.PointFromScreen(new Point((short)(packed&0xffff),(short)((packed>>16)&0xffff)));
            double left=System.Windows.Controls.Canvas.GetLeft(surface),top=System.Windows.Controls.Canvas.GetTop(surface);
            if (point.X<left-5 || point.Y<top-5 || point.X>left+surface.ActualWidth+5 || point.Y>top+surface.ActualHeight+5) {
                handled=true;
                return new IntPtr(-1); // the unused transparent part of the stable host passes clicks through
            }
            return IntPtr.Zero;
        });
    }

    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr hwnd, int attr, out int value, int size);
    // Per-pixel WPF transparency owns the entire silhouette. DWM backdrops draw
    // outside custom clips and must stay disabled on this shaped window.
    public static int ConfigureGlass(IntPtr hwnd, bool dark) {
        int backdrop=1,corners=1,border=-2;
        DwmSetWindowAttribute(hwnd,33,ref corners,4);
        DwmSetWindowAttribute(hwnd,34,ref border,4);
        return DwmSetWindowAttribute(hwnd,38,ref backdrop,4);
    }
    public static int Backdrop(IntPtr hwnd) { int value; DwmGetWindowAttribute(hwnd,38,out value,4); return value; }
    public delegate bool EnumWindowCallback(IntPtr hwnd, IntPtr state);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowCallback callback, IntPtr state);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, System.Text.StringBuilder text, int max);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    public static IntPtr FindWidgetWindow() {
        IntPtr result=IntPtr.Zero;
        EnumWindows((hwnd,state) => {
            if (!IsWindowVisible(hwnd)) return true;
            var title=new System.Text.StringBuilder(128);
            GetWindowText(hwnd,title,title.Capacity);
            if (title.ToString() != "Codex 余量 · WPF") return true;
            result=hwnd;
            return false;
        },IntPtr.Zero);
        return result;
    }
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wparam, IntPtr lparam);
}

public sealed class UsageProtocolException : Exception {
    public readonly int Code;
    public UsageProtocolException(int code,string message) : base(message) { Code=code; }
}

public sealed class UsageClient : IDisposable {
    readonly string executable;
    readonly SemaphoreSlim gate = new SemaphoreSlim(1);
    readonly JavaScriptSerializer json = new JavaScriptSerializer();
    Process process;
    int sequence;
    public UsageClient(string path) { executable = path; }
    void Send(object message) {
        process.StandardInput.WriteLine(json.Serialize(message));
        process.StandardInput.Flush();
    }
    async Task<Dictionary<string,object>> Read(int id) {
        while (true) {
            var read = process.StandardOutput.ReadLineAsync();
            if (await Task.WhenAny(read, Task.Delay(20000)).ConfigureAwait(false) != read)
                throw new TimeoutException("连接超时，请检查网络");
            var line = await read.ConfigureAwait(false);
            if (line == null) throw new Exception("连接断开，请确认 Codex 已登录");
            Dictionary<string,object> message;
            try { message = json.Deserialize<Dictionary<string,object>>(line); } catch { continue; }
            if (message.ContainsKey("id") && Convert.ToString(message["id"]) == id.ToString()) {
                if (message.ContainsKey("error")) {
                    var error=message["error"] as Dictionary<string,object>;
                    int code=error!=null && error.ContainsKey("code") ? Convert.ToInt32(error["code"]) : 0;
                    string text=error!=null && error.ContainsKey("message") ? Convert.ToString(error["message"]) : "请求失败，请确认 Codex 已登录";
                    throw new UsageProtocolException(code,text);
                }
                return (Dictionary<string,object>)message["result"];
            }
            if (message.ContainsKey("id") && message.ContainsKey("method"))
                Send(new { id=message["id"], error=new { code=-32601, message="Unsupported" } });
        }
    }
    public Task<string> FetchAsync() { return RequestAsync("account/rateLimits/read",null); }
    public Task<string> ConsumeResetAsync(string idempotencyKey,string creditId) {
        if (String.IsNullOrWhiteSpace(idempotencyKey)) throw new ArgumentException("兑换请求缺少唯一标识");
        var parameters=new Dictionary<string,object> { { "idempotencyKey",idempotencyKey } };
        if (!String.IsNullOrWhiteSpace(creditId)) parameters["creditId"]=creditId;
        return RequestAsync("account/rateLimitResetCredit/consume",parameters);
    }
    async Task<string> RequestAsync(string method,Dictionary<string,object> parameters) {
        await gate.WaitAsync().ConfigureAwait(false);
        try {
            if (process == null || process.HasExited) {
                process = new Process { StartInfo = new ProcessStartInfo(executable, "app-server --stdio") {
                    UseShellExecute=false, CreateNoWindow=true, RedirectStandardInput=true,
                    RedirectStandardOutput=true, RedirectStandardError=true,
                    StandardOutputEncoding=System.Text.Encoding.UTF8
                }};
                process.ErrorDataReceived += (s,e) => {};
                process.Start();
                process.BeginErrorReadLine();
                int init = ++sequence;
                Send(new { id=init, method="initialize", @params=new { clientInfo=new {
                    name="codex_usage_desktop", title="Codex Usage Desktop", version="2.0.0" } } });
                await Read(init).ConfigureAwait(false);
                Send(new { method="initialized" });
            }
            int request = ++sequence;
            if (parameters==null) Send(new { id=request, method=method });
            else Send(new { id=request, method=method, @params=parameters });
            return json.Serialize(await Read(request).ConfigureAwait(false));
        } catch { Dispose(); throw; }
        finally { gate.Release(); }
    }
    public void Dispose() {
        var p = process; process = null;
        if (p != null) { try { if (!p.HasExited) p.Kill(); } catch {} p.Dispose(); }
    }
}
