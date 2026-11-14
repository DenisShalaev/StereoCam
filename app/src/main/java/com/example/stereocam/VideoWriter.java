package com.example.stereocam;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;

/** Schreibt ein H.264-MP4 aus ARGB-Bildern (MediaCodec mit Image-Eingabe + MediaMuxer). Breite und Hoehe muessen durch 16 teilbar sein. */
final class VideoWriter {
    private final MediaCodec codec; private final MediaMuxer muxer; private final int w, h, fps; private int track = -1; private boolean started; private long n;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    VideoWriter(String path, int w, int h, int fps) throws Exception {
        this.w = w; this.h = h; this.fps = fps;
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_500_000, (int) (w * (long) h * fps * 0.18)));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps); f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType("video/avc"); codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    void frame(int[] argb) {
        int idx;
        while ((idx = codec.dequeueInputBuffer(10_000)) < 0) drain(false);
        Image im = codec.getInputImage(idx); fill(im, argb); im.close();
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, n * 1_000_000L / fps, 0); n++; drain(false);
    }

    void finish() {
        int idx; while ((idx = codec.dequeueInputBuffer(10_000)) < 0) drain(false);
        codec.queueInputBuffer(idx, 0, 0, n * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM); drain(true);
        try { codec.stop(); } catch (Exception ignored) {} codec.release();
        if (started) { try { muxer.stop(); } catch (Exception ignored) {} } muxer.release();
    }

    private void drain(boolean eos) {
        while (true) {
            int o = codec.dequeueOutputBuffer(info, eos ? 10_000 : 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return; else continue; }
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true; continue; }
            if (o < 0) continue;
            ByteBuffer b = codec.getOutputBuffer(o);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && started) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(track, b, info); }
            codec.releaseOutputBuffer(o, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /** ARGB -> YUV420 (BT.601, Begrenzter Bereich) in die Ebenen des Eingabebildes, mit Zeilen- und Pixelabstand der Ebenen. */
    private void fill(Image im, int[] p) {
        Image.Plane py = im.getPlanes()[0], pu = im.getPlanes()[1], pv = im.getPlanes()[2];
        ByteBuffer by = py.getBuffer(), bu = pu.getBuffer(), bv = pv.getBuffer();
        int rs = py.getRowStride(), ps = py.getPixelStride();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = p[y * w + x], r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
            by.put(y * rs + x * ps, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
        }
        int urs = pu.getRowStride(), ups = pu.getPixelStride(), vrs = pv.getRowStride(), vps = pv.getPixelStride();
        for (int y = 0; y < h / 2; y++) for (int x = 0; x < w / 2; x++) {
            int r = 0, g = 0, b = 0;
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) { int c = p[(2 * y + dy) * w + 2 * x + dx]; r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255; }
            r /= 4; g /= 4; b /= 4;
            bu.put(y * urs + x * ups, (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
            bv.put(y * vrs + x * vps, (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
        }
    }
}
