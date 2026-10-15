package vramsteady;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.ui.UIManager", methodName = "render")
public class PatchUIRender {
   /** Advice code is copied into UIManager.render, so it may only call public API; errors are swallowed. */
   @Patch.OnExit(suppress = Throwable.class)
   public static void exit() {
      Overlay.draw();
   }
}
