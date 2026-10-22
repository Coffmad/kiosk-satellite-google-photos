// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import android.graphics.Bitmap;
import java.io.IOException;

/** Decodes photos for the native screensaver on the plugin's worker thread, so the main thread only draws. */
final class AndroidDecoder implements Native.Decoder {
    /** The blurred background is the photo at this fraction of its size, which the view stretches smoothly. */
    private static final int BLUR_DIVISOR = 24;

    @Override
    public Native.Picture decode(byte[] image, int maxWidth, int maxHeight, Album.Photo photo, int originX, int originY)
        throws IOException {
        Bitmap bitmap = AndroidShrinker.decode(image, maxWidth, maxHeight);
        // Two steps down keep the tiny copy smooth instead of picking scattered pixels.
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        Bitmap half = Bitmap.createScaledBitmap(bitmap, Math.max(1, width / 4), Math.max(1, height / 4), true);
        Bitmap blurred = Bitmap.createScaledBitmap(half, Math.max(1, width / BLUR_DIVISOR), Math.max(1, height / BLUR_DIVISOR), true);
        if (half != blurred && half != bitmap) half.recycle();
        return new Native.Picture(bitmap, blurred, photo, width, height, originX, originY);
    }
}
