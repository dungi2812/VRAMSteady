package vramsteady;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.core.VBO.GLVertexBufferObject", methodName = "doDestroy")
public class PatchDestroy {
   @Patch.OnEnter
   public static void enter(@Patch.This Object self) {
      Steady.forget(self);
   }
}
