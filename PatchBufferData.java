package vramsteady;

import java.nio.ByteBuffer;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.core.VBO.GLVertexBufferObject", methodName = "bufferData")
public class PatchBufferData {
   @Patch.OnEnter(skipOn = true)
   public static boolean enter(@Patch.This Object self, @Patch.Argument(0) ByteBuffer data) {
      return Steady.bufferData(self, data);
   }
}
