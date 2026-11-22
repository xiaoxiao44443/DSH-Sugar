package app.dsh.sugar.mobile.shell;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private UI appearance hint. Keeps aggregate colors only; never retains or encodes a frame. */
final class VirtualAppearance {
    private static final long MIN_INTERVAL_MS=2000;
    private long attemptedAt=-MIN_INTERVAL_MS;
    private Rect sampledBounds;
    private JSONObject cached;

    private static JSONObject unknown(String reason) throws Exception {
        return new JSONObject().put("known",false).put("reason",reason);
    }
    private static Rect bounds(JSONObject value) throws Exception {
        if(value==null) return null;
        int[] coords=new int[4];String[] names={"left","top","right","bottom"};
        for(int i=0;i<4;i++) {
            Object raw=value.opt(names[i]);
            if(!(raw instanceof Number)) return null;
            double number=((Number)raw).doubleValue();
            if(!Double.isFinite(number) || number!=Math.rint(number) || Math.abs(number)>32768) return null;
            coords[i]=(int)number;
        }
        return coords[2]>coords[0] && coords[3]>coords[1] ? new Rect(coords[0],coords[1],coords[2],coords[3]) : null;
    }
    private static double linear(int channel) {
        double value=channel/255.0;
        return value<=0.04045 ? value/12.92 : Math.pow((value+0.055)/1.055,2.4);
    }
    private static final class Samples {
        long red,green,blue;
        int count,black,transparent;
        double luminance;
        void add(Bitmap bitmap,int x,int y) {
            if(x<0 || y<0 || x>=bitmap.getWidth() || y>=bitmap.getHeight()) return;
            int pixel=bitmap.getPixel(x,y);
            if(Color.alpha(pixel)<128) { transparent++;return; }
            int r=Color.red(pixel),g=Color.green(pixel),b=Color.blue(pixel);
            red+=r;green+=g;blue+=b;count++;
            if(r<=3 && g<=3 && b<=3) black++;
            luminance+=0.2126*linear(r)+0.7152*linear(g)+0.0722*linear(b);
        }
    }
    JSONObject sample(UiAutomation automation,JSONObject value,BooleanSupplier allowed) throws Exception {
        Rect excluded=bounds(value);
        if(excluded==null) return unknown("invalid_overlay_bounds");
        long now=SystemClock.elapsedRealtime();
        if(now-attemptedAt<MIN_INTERVAL_MS) {
            if(cached!=null && excluded.equals(sampledBounds))
                return new JSONObject(cached.toString()).put("cached",true).put("cache_age_ms",now-attemptedAt);
            return unknown("sampling_throttled").put("cached",false);
        }
        attemptedAt=now;sampledBounds=new Rect(excluded);
        Bitmap captured=null,readable=null;
        JSONObject result;
        try {
            if(automation==null) result=unknown("capture_unavailable");
            else if(!allowed.getAsBoolean()) result=unknown("control_changed");
            else {
                captured=automation.takeScreenshot();
                if(!allowed.getAsBoolean()) result=unknown("control_changed");
                else if(captured==null) result=unknown("capture_unavailable");
                else {
                    readable=captured.getConfig()==Bitmap.Config.HARDWARE ? captured.copy(Bitmap.Config.ARGB_8888,false) : captured;
                    if(readable==null) result=unknown("capture_unavailable");
                    else {
                        int width=readable.getWidth(),height=readable.getHeight();
                        Samples samples=new Samples();
                        // Coordinates are physical main-display pixels. The caller supplies the
                        // whole overlay surface, including pet and shadow; the extra gap avoids
                        // antialiasing at its edge. Only a narrow exterior ring is sampled.
                        int gap=12;
                        int stride=Math.max(8,Math.max(excluded.width(),excluded.height())/32);
                        for(int offset: new int[]{gap,gap+16,gap+32}) {
                            for(int x=Math.max(0,excluded.left);x<Math.min(width,excluded.right);x+=stride) {
                                samples.add(readable,x,excluded.top-offset);
                                samples.add(readable,x,excluded.bottom+offset);
                            }
                            for(int y=Math.max(0,excluded.top);y<Math.min(height,excluded.bottom);y+=stride) {
                                samples.add(readable,excluded.left-offset,y);
                                samples.add(readable,excluded.right+offset,y);
                            }
                        }
                        if(samples.count<12 || samples.transparent>samples.count/4) result=unknown("insufficient_outside_samples");
                        // UiAutomation does not expose secure-layer metadata. Near-black output
                        // could be secure redaction, so never present it as a measured black theme.
                        else if(samples.black*4>=samples.count*3) result=unknown("ambiguous_black_capture");
                        else result=new JSONObject().put("known",true)
                                .put("background_rgb",new JSONArray().put(Math.round(samples.red/(double)samples.count))
                                        .put(Math.round(samples.green/(double)samples.count)).put(Math.round(samples.blue/(double)samples.count)))
                                .put("luminance",Math.round(samples.luminance/samples.count*10000)/10000.0)
                                .put("sample_count",samples.count).put("sampled_elapsed_ms",SystemClock.elapsedRealtime())
                                .put("screen_width",width).put("screen_height",height);
                    }
                }
            }
        } catch(Exception failure) { result=unknown("capture_unavailable"); }
        finally {
            if(readable!=null && readable!=captured) readable.recycle();
            if(captured!=null) captured.recycle();
        }
        result.put("capture_ms",SystemClock.elapsedRealtime()-now).put("cached",false);
        cached=new JSONObject(result.toString());
        return result;
    }
}
