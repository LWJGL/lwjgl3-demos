package org.lwjgl.demo.vulkan;

import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Debugging why freeing a specific ByteBuffer is problematic.
 *
 * Created by Andrew Brown on 9/14/2017 at 11:08 PM.
 */
public class DebugSnippet
{
    public static void main(String... args) throws IOException
    {
        String resource = "org/lwjgl/demo/vulkan/coloredTriangle.vert.spv";
        File file = new  File(Thread.currentThread().getContextClassLoader().getResource(resource).getFile());
        assert file.isFile();

        FileInputStream fis = new FileInputStream(file);
        FileChannel fc = fis.getChannel();

        MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());

        fc.close();
        fis.close();

        System.out.println("about to try to free the buffer");
        // Why does this fail? or what is the explanation why it shouldn't be done?
        MemoryUtil.memFree(buffer);
        System.out.println("You freed the buffer");
    }
}
