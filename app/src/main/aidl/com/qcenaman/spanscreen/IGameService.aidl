package com.qcenaman.spanscreen;

import android.view.Surface;

interface IGameService {
    void destroy() = 16777114;

    // Returns "" on success, otherwise an error message.
    String start(int vdWidth, int vdHeight, int dpi, int fps, String component,
                 in Surface local, in float[] localRect,
                 in String[] hosts, in int[] tileSizes, in String[] mimes, in float[] rects) = 1;

    oneway void touch(int kind, int key, float u, float v) = 2;

    void stop() = 3;
}
