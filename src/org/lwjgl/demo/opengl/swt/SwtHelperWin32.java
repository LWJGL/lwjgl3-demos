package org.lwjgl.demo.opengl.swt;

import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Shell;

public class SwtHelperWin32 {

    public static void properFullscreen(Shell shell) {
        if (!shell.getFullScreen()) {
            int lStyle = OS.GetWindowLong(shell.handle, OS.GWL_STYLE);
            lStyle &= ~OS.WS_THICKFRAME;
            OS.SetWindowLong(shell.handle, OS.GWL_STYLE, lStyle);
        } else {
            int lStyle = OS.GetWindowLong(shell.handle, OS.GWL_STYLE);
            lStyle ^= OS.WS_THICKFRAME;
            OS.SetWindowLong(shell.handle, OS.GWL_STYLE, lStyle);
        }
        shell.setFullScreen(!shell.getFullScreen());
    }

}
