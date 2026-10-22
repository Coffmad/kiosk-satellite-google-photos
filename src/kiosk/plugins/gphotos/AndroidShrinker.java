// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Shrinks photos with Android's own decoder. Decodes at a reduced sample size so full-size photos fit in memory. */
final class AndroidShrinker implements Shrinker {
    @Override
    public byte[] shrink(byte[] image, int maxWidth, int maxHeight, int quality) throws IOException {
        Bitmap result = decode(image, maxWidth, maxHeight);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!result.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, quality)), out)) {
                throw new IOException("Could not compress the photo");
            }
            return out.toByteArray();
        } finally {
            result.recycle();
        }
    }

    /** The photo upright, fitted within maxWidth x maxHeight and never enlarged. The caller owns the bitmap. */
    static Bitmap decode(byte[] image, int maxWidth, int maxHeight) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Could not read the photo");

        int degrees = rotation(image);
        boolean turned = degrees == 90 || degrees == 270;
        // The size the photo has once it is upright.
        int uprightWidth = turned ? bounds.outHeight : bounds.outWidth;
        int uprightHeight = turned ? bounds.outWidth : bounds.outHeight;
        double scale = Math.min(1, Math.min((double) maxWidth / uprightWidth, (double) maxHeight / uprightHeight));
        int targetWidth = Math.max(1, (int) Math.round(uprightWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(uprightHeight * scale));

        // Halve while the decoded photo stays at least as large as the target, so quality survives the final scale.
        int sample = 1;
        while (uprightWidth / (sample * 2) >= targetWidth && uprightHeight / (sample * 2) >= targetHeight) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(image, 0, image.length, options);
        if (decoded == null) throw new IOException("Could not read the photo");
        // One scale for both axes keeps the shape, whichever way the photo turns.
        float factor = (float) targetWidth / (turned ? decoded.getHeight() : decoded.getWidth());
        if (degrees == 0 && Math.abs(factor - 1) < 0.001f) return decoded;
        Matrix matrix = new Matrix();
        matrix.postScale(factor, factor);
        if (degrees != 0) matrix.postRotate(degrees);
        Bitmap result = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (result != decoded) decoded.recycle();
        return result;
    }

    /** Clockwise rotation from the photo's EXIF orientation. Mirrored orientations keep their rotation only. */
    private static int rotation(byte[] image) {
        try {
            int orientation = new ExifInterface(new ByteArrayInputStream(image))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }
}
