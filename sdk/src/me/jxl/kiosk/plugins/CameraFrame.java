// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

/** SDK 1, camera. One frame from openCamera in sensor orientation. The data belongs to the plugin. */
public final class CameraFrame {
    private final int width;
    private final int height;
    private final int rotationDegrees;
    private final boolean frontFacing;
    private final long timestampNanos;
    private final String format;
    private final byte[] data;

    public CameraFrame(int width, int height, int rotationDegrees, boolean frontFacing, long timestampNanos, String format, byte[] data) {
        this.width = width;
        this.height = height;
        this.rotationDegrees = rotationDegrees;
        this.frontFacing = frontFacing;
        this.timestampNanos = timestampNanos;
        this.format = format;
        this.data = data;
    }

    /** Width in pixels, as the camera delivered the frame. It can differ from the size asked for. */
    public int width() { return width; }
    public int height() { return height; }
    /** Clockwise degrees that turn the frame upright on this display: 0, 90, 180 or 270. */
    public int rotationDegrees() { return rotationDegrees; }
    /** True for a camera that faces the user. Flip its picture to show it like a mirror. */
    public boolean frontFacing() { return frontFacing; }
    /** The camera's capture time, in nanoseconds of an unspecified base. */
    public long timestampNanos() { return timestampNanos; }
    /**
     * "luminance": width * height bytes, one brightness value per pixel, row by row. "nv21": the same, followed by
     * interleaved V and U bytes at half the width and half the height.
     */
    public String format() { return format; }
    public byte[] data() { return data; }
}
