package app.dsh.sugar.mobile.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** The Surface belongs to this shell process. No mirroring or physical-display capture. */
final class VirtualFrames implements AutoCloseable {
    final int width = 720, height = 1280, dpi = 300;
    final VirtualDisplay virtualDisplay;
    final Display display;
    private final ImageReader reader;
    private Image latest;
    private long acquiredAt;
    private long acquiredElapsed;
    private long acquiredWall;
    private boolean closed;

    VirtualFrames(Context context, Runnable onStopped) throws Exception {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> {
            synchronized (this) {
                if (closed) return;
                Image next = source.acquireLatestImage();
                if (next != null) {
                    if (latest != null) latest.close();
                    latest = next;
                    acquiredAt = SystemClock.uptimeMillis();
                    acquiredElapsed = SystemClock.elapsedRealtime();
                    acquiredWall = System.currentTimeMillis();
                    notifyAll();
                }
            }
        }, new Handler(Looper.getMainLooper()));
        try {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            // Hidden platform flags are read from this device, never substituted with mirroring.
            for (String name : new String[]{"SUPPORTS_TOUCH","DESTROY_CONTENT_ON_REMOVAL","TRUSTED","OWN_DISPLAY_GROUP","OWN_FOCUS"}) {
                flags |= DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_" + name).getInt(null);
            }
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            virtualDisplay = manager.createVirtualDisplay("DSH Sugar",width,height,dpi,reader.getSurface(),flags,
                    new VirtualDisplay.Callback() {
                        @Override public void onStopped() { onStopped.run(); }
                    }, new Handler(Looper.getMainLooper()));
            if (virtualDisplay == null) throw new IOException("Android refused the virtual display.");
            display = virtualDisplay.getDisplay();
            if (display == null || display.getDisplayId() <= Display.DEFAULT_DISPLAY) {
                virtualDisplay.release();
                throw new IOException("Expected a separate Android display.");
            }
        } catch (Exception e) {
            reader.close();
            throw e;
        }
    }

    synchronized JSONObject capture() throws Exception {
        return capture(-1);
    }

    synchronized JSONObject capture(long after) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 1500;
        while (!closed && (latest == null || acquiredAt <= after) && SystemClock.uptimeMillis() < deadline)
            wait(Math.max(1, deadline - SystemClock.uptimeMillis()));
        if (closed || latest == null || acquiredAt <= after) throw new IOException("No fresh frame on this virtual display.");
        Image.Plane plane = latest.getPlanes()[0];
        int stride = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(stride,height,Bitmap.Config.ARGB_8888);
        Bitmap image = padded;
        try {
            ByteBuffer buffer = plane.getBuffer().duplicate();
            buffer.rewind();
            // Some gralloc implementations omit final row padding.
            int required = stride * height * 4;
            if (buffer.remaining() < required) {
                ByteBuffer aligned = ByteBuffer.allocate(required);
                aligned.put(buffer); aligned.rewind(); buffer = aligned;
            }
            padded.copyPixelsFromBuffer(buffer);
            if (stride != width) image = Bitmap.createBitmap(padded,0,0,width,height);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!image.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("Could not encode frame.");
            JSONObject frame = new JSONObject().put("mime_type","image/png").put("width",width).put("height",height)
                    .put("captured_elapsed_ms", acquiredElapsed).put("timestamp_ms", acquiredWall)
                    .put("scale_x",1).put("scale_y",1).put("data", Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
            if (after >= 0) frame.put("feedback_delay_ms", acquiredAt - after);
            return frame;
        } finally {
            if (image != padded) image.recycle();
            padded.recycle();
        }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        notifyAll();
        reader.setOnImageAvailableListener(null,null);
        virtualDisplay.release();
        if (latest != null) { latest.close(); latest=null; }
        reader.close();
    }
}
