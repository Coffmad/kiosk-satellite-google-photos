// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;

/** Resizes a photo on the device, for sources that only offer full-size files. Tests replace it. */
interface Shrinker {
    /**
     * A JPEG that fits within maxWidth x maxHeight, upright and never enlarged, at the given JPEG quality from
     * 1 to 100.
     */
    byte[] shrink(byte[] image, int maxWidth, int maxHeight, int quality) throws IOException;
}
