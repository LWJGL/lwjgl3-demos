/*  
 * Copyright LWJGL. All rights reserved.    
 * License terms: https://www.lwjgl.org/license 
 */
package org.lwjgl.demo.util;

import static java.lang.Integer.MAX_VALUE;
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
            int x, y, w, h;
            S(int x, int y, int w, int h) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
            }
        }
        fs.sort((f0, f1) -> compare(f1.h(), f0.h()));
        List<S> s = new ArrayList<>();
        int a = 0, mw = 0, w = 0, h = 0, sw;
        for (int i = 0; i < fs.size(); i++) {
            Face face = fs.get(i);
            a += face.w() * face.h();
            mw = max(mw, face.w());
        }
        sw = max((int) ceil(sqrt(a)), mw);
        s.add(new S(0, 0, sw, MAX_VALUE));
        for (int i = 0; i < fs.size(); i++) {
            Face f = fs.get(i);
            for (int j = s.size() - 1; j >= 0; j--) {
                S sp = s.get(j);
                if (f.w() > sp.w || f.h() > sp.h) continue;
                f.tx = sp.x; f.ty = sp.y;
                h = max(h, f.ty + f.h()); w = max(w, f.tx + f.w());
                if (f.w() == sp.w && f.h() == sp.h) {
                    S r = s.remove(s.size() - 1);
                    if (j < s.size()) s.set(j, r);
                } else if (f.h() == sp.h) {
                    sp.x += f.w(); sp.w -= f.w();
                } else if (f.w() == sp.w) {
                    sp.y += f.h(); sp.h -= f.h();
                } else {
                    s.add(new S(sp.x + f.w(), sp.y, sp.w - f.w(), f.h()));
                    sp.y += f.h(); sp.h -= f.h();
                }
                break;
            }
        }
        return new PackResult(w, h, ((float) a / (w * h)));
    }

}
