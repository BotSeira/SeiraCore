package xyz.zcraft.seira.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Arrays;

import static java.lang.Math.clamp;

public class ImageUtil {
    public static BufferedImage readImage(String url) throws Exception {
        return ImageIO.read(URI.create(url).toURL());
    }

    public static BufferedImage crop(BufferedImage image, int x, int y, int width, int height) {
        BufferedImage sub = image.getSubimage(x, y, width, height);

        BufferedImage result = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB
        );

        result.getGraphics().drawImage(sub, 0, 0, null);

        return result;
    }

    public static BufferedImage gaussianBlur(BufferedImage image, int radius) {
        if (radius <= 0) {
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        float[] kernel = createGaussianKernel(radius);

        int[] source = image.getRGB(
                0, 0,
                width, height,
                null,
                0, width
        );

        int[] temp = new int[source.length];
        int[] result = new int[source.length];

        blurHorizontal(
                source,
                temp,
                width,
                height,
                kernel,
                radius
        );

        blurVertical(
                temp,
                result,
                width,
                height,
                kernel,
                radius
        );

        BufferedImage output = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );

        output.setRGB(
                0, 0,
                width, height,
                result,
                0, width
        );

        return output;
    }

    private static float[] createGaussianKernel(int radius) {
        int size = radius * 2 + 1;
        float[] kernel = new float[size];

        // 常见经验值
        double sigma = Math.max(radius / 3.0, 0.1);

        double sum = 0;

        for (int i = -radius; i <= radius; i++) {
            double value = Math.exp(
                    -(i * i) / (2.0 * sigma * sigma)
            );

            kernel[i + radius] = (float) value;
            sum += value;
        }

        // 归一化
        for (int i = 0; i < size; i++) {
            kernel[i] /= (float) sum;
        }

        return kernel;
    }

    private static void blurHorizontal(
            int[] source,
            int[] target,
            int width,
            int height,
            float[] kernel,
            int radius
    ) {
        for (int y = 0; y < height; y++) {
            int row = y * width;

            for (int x = 0; x < width; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;

                for (int k = -radius; k <= radius; k++) {
                    int sampleX = clamp(x + k, 0, width - 1);
                    int argb = source[row + sampleX];

                    float weight = kernel[k + radius];

                    a += ((argb >>> 24) & 0xff) * weight;
                    r += ((argb >>> 16) & 0xff) * weight;
                    g += ((argb >>> 8) & 0xff) * weight;
                    b += (argb & 0xff) * weight;
                }

                target[row + x] =
                        ((clamp(Math.round(a), 0, 255)) << 24)
                                | ((clamp(Math.round(r), 0, 255)) << 16)
                                | ((clamp(Math.round(g), 0, 255)) << 8)
                                | clamp(Math.round(b), 0, 255);
            }
        }
    }

    private static void blurVertical(
            int[] source,
            int[] target,
            int width,
            int height,
            float[] kernel,
            int radius
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;

                for (int k = -radius; k <= radius; k++) {
                    int sampleY = clamp(y + k, 0, height - 1);
                    int argb = source[sampleY * width + x];

                    float weight = kernel[k + radius];

                    a += ((argb >>> 24) & 0xff) * weight;
                    r += ((argb >>> 16) & 0xff) * weight;
                    g += ((argb >>> 8) & 0xff) * weight;
                    b += (argb & 0xff) * weight;
                }

                target[y * width + x] =
                        ((clamp(Math.round(a), 0, 255)) << 24)
                                | ((clamp(Math.round(r), 0, 255)) << 16)
                                | ((clamp(Math.round(g), 0, 255)) << 8)
                                | clamp(Math.round(b), 0, 255);
            }
        }
    }

    public static BufferedImage mosaic(BufferedImage image, int blockSize) {
        int width = image.getWidth();
        int height = image.getHeight();

        int smallWidth = Math.max(1, width / blockSize);
        int smallHeight = Math.max(1, height / blockSize);

        BufferedImage small = new BufferedImage(smallWidth, smallHeight, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g1 = small.createGraphics();
        g1.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g1.drawImage(image, 0, 0, smallWidth, smallHeight, null);
        g1.dispose();

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(small, 0, 0, width, height, null);
        g2.dispose();

        return result;
    }

    public static void mosaicRegion(BufferedImage image, int x, int y, int width, int height, int blockSize) {
        BufferedImage region = image.getSubimage(x, y, width, height);

        BufferedImage processed = mosaic(region, blockSize);

        Graphics2D g = image.createGraphics();
        g.drawImage(processed, x, y, null);
        g.dispose();
    }

    public static byte[] toPngBytes(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}
