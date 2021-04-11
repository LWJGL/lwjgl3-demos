/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Integer.compare;
import static java.lang.Math.ceil;
import static java.lang.Math.max;
import static java.lang.Math.sqrt;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.demo.util.GreedyMeshing.Face;

/**
 * Rectangle bin-packing for {@link Face}s that tries to increase space utilization and 
 * keeping the needed area square.
 * <p>
 * Adapted from: <a href="https://github.com/mapbox/potpack">mapbox/potpack</a>
 * 
 * @author Kai Burjack
 */
public class FacePacker {
    public static class PackResult {
        public final int w, h;
        public final float u;
        public PackResult(int w, int h, float u) {
            this.w = w;
            this.h = h;
            this.u = u;
        }
        public String toString() {
            return "[" + w + ", " + h + ", " + u + "]";
        }
    }

    public static PackResult pack(List<Face> fs) {
        class S {
            short x, y, w, h;
            S(int x, int y, int w, int h) {
                this.x = (short) x;
                this.y = (short) y;
                this.w = (short) w;
                this.h = (short) h;
            }
        }
        fs.sort((f0, f1) -> compare(f1.th(), f0.th()));
        List<S> s = new ArrayList<>();
        int a = 0, mw = 0, w = 0, h = 0, sw;
        for (int i = 0; i < fs.size(); i++) {
            Face face = fs.get(i);
            a += face.tw() * face.th();
            mw = max(mw, face.tw());
        }
        sw = max((int) ceil(sqrt(a)), mw);
        s.add(new S(0, 0, sw, Short.MAX_VALUE));
        for (int i = 0; i < fs.size(); i++) {
            Face f = fs.get(i);
            for (int j = s.size() - 1; j >= 0; j--) {
                S sp = s.get(j);
                if (f.tw() > sp.w || f.th() > sp.h) continue;
                f.tx = sp.x; f.ty = sp.y;
                h = max(h, f.ty + f.th()); w = max(w, f.tx + f.tw());
                if (f.tw() == sp.w && f.th() == sp.h) {
                    S r = s.remove(s.size() - 1);
                    if (j < s.size()) s.set(j, r);
                } else if (f.th() == sp.h) {
                    sp.x += f.tw(); sp.w -= f.tw();
                } else if (f.tw() == sp.w) {
                    sp.y += f.th(); sp.h -= f.th();
                } else {
                    s.add(new S(sp.x + f.tw(), sp.y, sp.w - f.tw(), f.th()));
                    sp.y += f.th(); sp.h -= f.th();
                }
                break;
            }
        }
        return new PackResult(w, h, ((float) a / (w * h)));
    }

}
