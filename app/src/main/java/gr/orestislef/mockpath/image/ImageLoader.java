package gr.orestislef.mockpath.image;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes a user-picked image into a bounded, orientation-corrected ARGB bitmap,
 * suitable for edge detection. Uses {@code inSampleSize} to avoid decoding huge images
 * into memory, then scales to an exact maximum dimension.
 */
public final class ImageLoader {

    private ImageLoader() {
    }

    /**
     * @param maxDim maximum width/height of the returned bitmap
     * @return decoded bitmap, or null on failure
     */
    @Nullable
    public static Bitmap loadDownscaled(@NonNull Context context, @NonNull Uri uri, int maxDim) {
        ContentResolver resolver = context.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            decoded = BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            return null;
        }
        if (decoded == null) {
            return null;
        }

        int rotation = readRotationDegrees(resolver, uri);
        Bitmap oriented = applyExactScaleAndRotation(decoded, maxDim, rotation);
        if (oriented != decoded) {
            decoded.recycle();
        }
        // Ensure ARGB_8888 for predictable getPixels().
        if (oriented.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = oriented.copy(Bitmap.Config.ARGB_8888, false);
            if (converted != null && converted != oriented) {
                oriented.recycle();
                return converted;
            }
        }
        return oriented;
    }

    private static int computeSampleSize(int w, int h, int maxDim) {
        int sample = 1;
        int longest = Math.max(w, h);
        // Halve until the sampled longest edge is within ~2x the target.
        while (longest / sample > maxDim * 2) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap applyExactScaleAndRotation(@NonNull Bitmap src, int maxDim, int rotation) {
        int w = src.getWidth();
        int h = src.getHeight();
        float scale = Math.min(1f, (float) maxDim / Math.max(w, h));

        Matrix matrix = new Matrix();
        if (scale < 1f) {
            matrix.postScale(scale, scale);
        }
        if (rotation != 0) {
            matrix.postRotate(rotation);
        }
        if (matrix.isIdentity()) {
            return src;
        }
        return Bitmap.createBitmap(src, 0, 0, w, h, matrix, true);
    }

    private static int readRotationDegrees(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return 0;
            }
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }
}
