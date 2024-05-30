/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Implementation of Cooley-Tukey radix-2 Decimation-In-Time (DIT) discrete Fourier transform (DFT) algorithm
 * to analyze the frequency spectrum of an image, such as those produced by sampling in path tracing.
 * <p>
 * This can be used to assess the quality of a sampling algorithm by analyzing the frequency spectrum of the resulting
 * image. Ideally, it should not contain any low-frequency components, which the human eye is very sensitive to.
 *
 * @author Kai Burjack
 */
public class FFT {

    private static double[][][] transpose(double[][][] inout) {
        int N = inout.length;
        for (int i = 0; i < N; i++)
            for (int j = i + 1; j < N; j++) {
                double[] tmp = inout[i][j];
                inout[i][j] = inout[j][i];
                inout[j][i] = tmp;
            }
        return inout;
    }

    private static double[][][] dfft2D(double[][][] inout) {
        int N = inout.length;
        for (int i = 0; i < N; i++)
            inout[i] = dfftShift(dfft1D(inout[i]));
        transpose(inout);
        for (int i = 0; i < N; i++)
            inout[i] = dfftShift(dfft1D(inout[i]));
        return transpose(inout);
    }

    private static double[][] dfft1D(double[][] inout) {
        int N = inout.length;
        if (N == 1) return inout;
        double[][] e = new double[N>>>1][], o = new double[N>>>1][];
        for (int i = 0; i < N>>>1; i++) {
            e[i] = inout[i << 1];
            o[i] = inout[(i<<1) + 1];
        }
        double[][] q = dfft1D(e), v = dfft1D(o);
        for (int i = 0; i < N>>>1; i++) {
            double k = (i<<1) * Math.PI / N;
            double cr = org.joml.Math.cos(k), ci = org.joml.Math.sin(k);
            inout[i] = new double[]{q[i][0] + cr * v[i][0] - ci * v[i][1], q[i][1] + cr * v[i][1] + ci * v[i][0]};
            inout[i + (N>>>1)] = new double[]{q[i][0] - (cr * v[i][0] - ci * v[i][1]), q[i][1] - (cr * v[i][1] + ci * v[i][0])};
        }
        return inout;
    }

    private static double[][][] toComplexArray(double[][] in) {
        int N = in.length;
        double[][][] output = new double[N][N][];
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++)
                output[i][j] = new double[]{in[i][j], 0};
        return output;
    }

    private static double[][] dfftShift(double[][] inout) {
        int N = inout.length;
        int hN = N>>>1;
        for (int i = 0; i < hN; i++) {
            double[] tmp = inout[i];
            inout[i] = inout[i + hN];
            inout[i + hN] = tmp;
        }
        return inout;
    }

    /**
     * Compute the frequency spectrum of the given input image, which is the magnitude of the 2D DFT of the input image.
     *
     * @param input
     *         the input image
     * @return the frequency spectrum of the input image
     */
    public static byte[][] frequencySpectrum(double[][] input) {
        double[][][] shifted = dfft2D(toComplexArray(input));
        int N = shifted.length;
        double max = 0;
        double[][] mag = new double[N][N];
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                mag[i][j] = Math.hypot(shifted[i][j][0], shifted[i][j][1]);
                if (mag[i][j] > max)
                    max = mag[i][j];
            }
        byte[][] res = new byte[N][N];
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                int value = (int) (255 * (Math.log1p(mag[i][j]) / Math.log1p(max)));
                res[i][j] = (byte) value;
            }
        return res;
    }

    // Test

    private static double[][] loadFromResource(String resource) throws Exception {
        BufferedImage image = ImageIO.read(FFT.class.getClassLoader().getResourceAsStream(resource));
        int N = image.getWidth();
        double[][] output = new double[N][N];
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++)
                output[i][j] = (image.getRGB(i, j) & 0xFF) / 255.0;
        return output;
    }

    public static void main(String[] args) throws Exception {
        double[][] input = loadFromResource("org/lwjgl/demo/opengl/raytracing/tutorial4_2/blueNoise.png");
        long time1 = System.nanoTime();
        byte[][] spectrum = frequencySpectrum(input);
        long time2 = System.nanoTime();
        System.out.println("Frequency spectrum computation took " + (time2 - time1) / 1E6 + " ms");
        int N = spectrum.length;
        BufferedImage res = new BufferedImage(N, N, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = res.getRaster();
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++)
                raster.setSample(i, j, 0, spectrum[i][j]);
        javax.imageio.ImageIO.write(res, "PNG", new java.io.File("spectrum.png"));
    }

}
