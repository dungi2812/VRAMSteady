package vramsteady;

import java.lang.reflect.Method;

/**
 * Small status line in the bottom-left corner: whether VRAM Steady is active and how much buffer churn it avoids.
 * Off with overlay=off in Zomboid/vramsteady.properties, or in game with Ctrl+Shift+V (toggle, lasts until restart).
 */
public final class Overlay {
   private Overlay() {}

   static boolean visible = true;
   private static boolean failed;
   private static long lastT = System.nanoTime(), lastCalls, lastReallocs;
   private static double perSec, reallocPerSec;
   private static String line = "";
   // game classes are newer than the compiler's class-file level, so they are reached by reflection
   private static Method keyDown, keyPressed, drawString, getInstance, screenHeight;
   private static Object textManager, fontSmall, core;

   private static void bind() throws Exception {
      Class<?> kb = Class.forName("zombie.input.GameKeyboard");
      keyDown = kb.getMethod("isKeyDown", int.class);
      keyPressed = kb.getMethod("isKeyPressed", int.class);
      Class<?> tm = Class.forName("zombie.ui.TextManager");
      Class<?> font = Class.forName("zombie.ui.UIFont");
      textManager = tm.getField("instance").get(null);
      fontSmall = font.getField("Small").get(null);
      drawString = tm.getMethod("DrawString", font, double.class, double.class, String.class,
         double.class, double.class, double.class, double.class);
      Class<?> c = Class.forName("zombie.core.Core");
      getInstance = c.getMethod("getInstance");
      screenHeight = c.getMethod("getScreenHeight");
   }

   public static void draw() {
      if (failed || !Steady.overlayWanted) return;
      try {
         if (drawString == null) bind();
         if ((boolean) keyDown.invoke(null, 29) && (boolean) keyDown.invoke(null, 42) && (boolean) keyPressed.invoke(null, 47)) {
            visible = !visible;
         }
         if (!visible) return;
         long now = System.nanoTime();
         if (now - lastT >= 1_000_000_000L) {
            double dt = (now - lastT) / 1e9;
            perSec = (Steady.totalCalls - lastCalls) / dt;
            reallocPerSec = (Steady.totalReallocs - lastReallocs) / dt;
            lastCalls = Steady.totalCalls;
            lastReallocs = Steady.totalReallocs;
            lastT = now;
            String state = Steady.broken ? "inactive (stock upload)" : Steady.mode == Steady.MODE_OFF ? "off" : Steady.modeName();
            line = "VRAM Steady: " + state + "  |  " + Math.round(perSec) + " uploads/s, new storage "
               + String.format(java.util.Locale.ROOT, "%.1f", reallocPerSec) + "/s (stock: " + Math.round(perSec) + "/s)"
               + "  |  Ctrl+Shift+V hides";
         }
         if (line.isEmpty()) return;
         drawString.invoke(textManager, fontSmall, 8.0, yBottom(), line, 0.85, 0.85, 0.85, 0.75);
      } catch (Throwable t) {
         failed = true;
         System.out.println("[VRAMSteady] overlay disabled after error: " + t);
      }
   }

   private static double yBottom() {
      try {
         return ((Number) screenHeight.invoke(getInstance.invoke(null))).doubleValue() - 28.0;
      } catch (Throwable t) {
         return 0.0;
      }
   }
}
