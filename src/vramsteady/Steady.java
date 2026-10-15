package vramsteady;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Properties;

import org.lwjgl.opengl.GL15;


/**
 * VRAM Steady: stops the stream-buffer churn that fills video memory.
 *
 * <p>Stock B42 uploads every VBORenderer batch (lines, shapes, much of the UI and many mods' drawing) with
 * glBufferData(target, data, GL_STREAM_DRAW): every flush re-creates the buffer's storage at the exact byte size of
 * that batch. A busy scene does tens of thousands of these a second (measured: ~33,000/s, ~210 MiB/s). The driver
 * keeps each discarded storage block until the GPU is done with it and returns them in bulk much later, so the
 * process's video memory climbs by about 1 GB a minute and falls back every few minutes. On GPUs with little VRAM that
 * climb pushes textures out of video memory: stutter, then a hitch when the driver finally trims.
 *
 * <p>The fix keeps the same data and the same draw calls, only the upload changes: the buffer gets a storage size
 * from a few fixed size classes (64 KB, 128 KB, ... powers of two) and each batch is written with glBufferSubData
 * after the driver is told the old contents are no longer needed (glBufferData with the same size and no data, the
 * standard "orphan" idiom). Same-size orphaned blocks are recycled by the driver instead of piling up.
 * Mode "subdata" skips the orphan call too (no new storage at all; the driver copies small updates inline).
 * Mode "off" leaves the stock upload untouched. Config: Zomboid/vramsteady.properties (mode, minKb).
 */
public final class Steady {
   private Steady() {}

   static final int MODE_OFF = 0, MODE_ORPHAN = 1, MODE_SUBDATA = 2;
   static int mode = MODE_ORPHAN;
   static int minBytes = 64 * 1024;
   static boolean broken;
   static boolean overlayWanted = true;
   static volatile long totalCalls, totalReallocs;

   static String modeName() {
      return mode == MODE_SUBDATA ? "subdata" : "orphan";
   }

   private static VarHandle TYPE, USAGE, ID, FUNCS;
   private static Class<?> GL15_FUNCS;
   private static final HashMap<Integer, Integer> capacity = new HashMap<>();
   static long calls, bytes, reallocs, passed;
   private static long lastLog = System.nanoTime();

   static void init() {
      try {
         Class<?> vbo = Class.forName("zombie.core.VBO.GLVertexBufferObject");
         Class<?> ifc = Class.forName("zombie.core.VBO.IGLBufferObject");
         GL15_FUNCS = Class.forName("zombie.core.VBO.GLBufferObject15");
         MethodHandles.Lookup l = MethodHandles.privateLookupIn(vbo, MethodHandles.lookup());
         TYPE = l.findVarHandle(vbo, "type", int.class);
         USAGE = l.findVarHandle(vbo, "usage", int.class);
         ID = l.findVarHandle(vbo, "id", int.class);
         FUNCS = l.findStaticVarHandle(vbo, "funcs", ifc);
      } catch (Throwable t) {
         broken = true;
         System.out.println("[VRAMSteady] cannot reach GLVertexBufferObject fields, staying off: " + t);
      }
      File f = new File(new File(System.getProperty("user.home"), "Zomboid"), "vramsteady.properties");
      Properties p = new Properties();
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Throwable t) {
            System.out.println("[VRAMSteady] cannot read " + f + ": " + t);
         }
      } else {
         try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("# VRAM Steady settings (read at game start)\n");
            w.write("# mode: orphan (default) | subdata | off\n");
            w.write("mode=orphan\n");
            w.write("# overlay: on (default) | off - small status line in the bottom-left corner (in game: Ctrl+Shift+V toggles)\n");
            w.write("overlay=on\n");
            w.write("# smallest buffer size class in KB (power of two)\n");
            w.write("minKb=64\n");
         } catch (Throwable t) {
            // optional
         }
      }
      String m = p.getProperty("mode", "orphan").trim().toLowerCase();
      mode = m.equals("off") ? MODE_OFF : m.equals("subdata") ? MODE_SUBDATA : MODE_ORPHAN;
      overlayWanted = !p.getProperty("overlay", "on").trim().equalsIgnoreCase("off");
      Overlay.visible = overlayWanted;
      try {
         int kb = Integer.parseInt(p.getProperty("minKb", "64").trim());
         minBytes = Integer.highestOneBit(Math.max(4, Math.min(4096, kb))) * 1024;
      } catch (NumberFormatException e) {
         minBytes = 64 * 1024;
      }
      System.out.println("[VRAMSteady] mode " + m + ", size classes from " + (minBytes / 1024) + " KB" + (broken ? " (inactive)" : ""));
   }

   static int sizeClass(int n) {
      if (n <= minBytes) return minBytes;
      int c = Integer.highestOneBit(n - 1) << 1;
      return c < 0 ? n : c;
   }

   /**
    * Replaces GLVertexBufferObject.bufferData(ByteBuffer). Returns true when it uploaded the data itself (the stock
    * body is skipped), false to let the stock body run (mode off, ARB path, empty data, anything unexpected).
    */
   public static boolean bufferData(Object self, ByteBuffer data) {
      if (mode == MODE_OFF || broken || FUNCS == null || data == null || !GL15_FUNCS.isInstance((Object) FUNCS.get())) {
         passed++;
         return false;
      }
      int n = data.remaining();
      if (n <= 0) {
         passed++;
         return false;
      }
      try {
         int type = (int) TYPE.get(self);
         int usage = (int) USAGE.get(self);
         int id = (int) ID.get(self);
         int cls = sizeClass(n);
         Integer cap = capacity.get(id);
         if (cap == null || cap < n || cap != cls && mode == MODE_ORPHAN) {
            // first upload, a batch larger than the storage, or (orphan mode) a different size class: new storage
            GL15.glBufferData(type, (long) cls, usage);
            capacity.put(id, cls);
            reallocs++;
            totalReallocs++;
         } else if (mode == MODE_ORPHAN) {
            GL15.glBufferData(type, (long) cap, usage); // same size, no data: the driver recycles the block
         }
         GL15.glBufferSubData(type, 0L, data);
         calls++;
         totalCalls++;
         bytes += n;
      } catch (Throwable t) {
         broken = true;
         System.out.println("[VRAMSteady] upload failed, back to the stock upload: " + t);
         return false;
      }
      long now = System.nanoTime();
      if (now - lastLog > 60_000_000_000L) {
         lastLog = now;
         System.out.println("[VRAMSteady] last minute: " + calls + " uploads, " + (bytes >> 20) + " MiB, " + reallocs
            + " storage changes, " + passed + " left to stock, " + capacity.size() + " buffers");
         calls = bytes = reallocs = passed = 0;
      }
      return true;
   }

   /** A deleted buffer name may come back for another buffer: forget its size. */
   public static void forget(Object self) {
      try {
         if (ID != null) capacity.remove((int) ID.get(self));
      } catch (Throwable t) {
         // ignore
      }
   }
}
