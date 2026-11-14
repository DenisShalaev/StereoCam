package com.example.stereocam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sucht Kameras, aus denen sich ein Stereopaar machen laesst: logische Mehrfachkameras (Camera2, ab Android 9),
 * deren physische Kameras einzeln ansprechbar sind. Ob zwei davon wirklich GLEICHZEITIG fotografieren duerfen,
 * entscheidet das Geraet - das zeigt erst der echte Versuch (DualCaptureActivity).
 */
final class DualProbe {

    static final class Phys {
        String id;
        int orient;                      // SENSOR_ORIENTATION
        float focalMm, equiv35;          // Brennweite real / kleinbildaequivalent
        SizeF sensorMm;                  // physische Sensorgroesse
        Size pixels;                     // Pixelarray
        Size best;                       // groesste JPEG-Groesse
        float[] pose;                    // LENS_POSE_TRANSLATION (m) oder null
        float minFocusDioptre;           // LENS_INFO_MINIMUM_FOCUS_DISTANCE (1/m), 0 = Fixfokus
        boolean usable = true;
        String why = "";
    }

    static final class Pair {
        String logicalId; Phys a, b;
        double baselineMm = Double.NaN;
        double ratio() { return Math.max(a.equiv35, b.equiv35) / Math.max(0.1f, Math.min(a.equiv35, b.equiv35)); }
        String key() { return logicalId + ":" + a.id + "+" + b.id; }
    }

    static final class Result {
        final List<Pair> pairs = new ArrayList<>();
        final StringBuilder report = new StringBuilder();
    }

    private DualProbe() {}

    static Result scan(Context ctx) {
        Result r = new Result();
        StringBuilder sb = r.report;
        sb.append("StereoCam build ").append(BuildInfo.NUMBER).append('\n')
          .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append("), Android ")
          .append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\n\n");
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                boolean multi = false;
                int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (caps != null) for (int c : caps) if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) multi = true;
                Set<String> phys = Build.VERSION.SDK_INT >= 28 ? cc.getPhysicalCameraIds() : null;
                sb.append("Camera ").append(id).append(facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? " (back)" : facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? " (front)" : " (external)")
                  .append(multi ? ", logical multi-camera" : "")
                  .append(phys != null && !phys.isEmpty() ? ", physical: " + phys : "").append('\n');
                if (!multi || phys == null || phys.size() < 2 || facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;

                List<Phys> list = new ArrayList<>();
                for (String pid : phys) {
                    Phys p = new Phys(); p.id = pid;
                    try {
                        CameraCharacteristics pc = cm.getCameraCharacteristics(pid);
                        float[] fl = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        p.sensorMm = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                        p.pixels = pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                        p.pose = pc.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
                        Integer so = pc.get(CameraCharacteristics.SENSOR_ORIENTATION); p.orient = so == null ? 90 : so;
                        Float mf = pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE); p.minFocusDioptre = mf == null ? 0 : mf;
                        if (fl != null && fl.length > 0 && p.sensorMm != null) {
                            p.focalMm = fl[0];
                            double diag = Math.hypot(p.sensorMm.getWidth(), p.sensorMm.getHeight());
                            p.equiv35 = (float) (fl[0] * 43.27 / diag);
                        } else { p.usable = false; p.why = "no focal length/sensor size"; }
                        StreamConfigurationMap map = pc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
                        if (sizes != null && sizes.length > 0) {
                            Size best = sizes[0]; for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s; p.best = best;
                        } else { p.usable = false; p.why = "no JPEG sizes (physical camera not exposed to apps)"; }
                    } catch (Exception e) { p.usable = false; p.why = e.getClass().getSimpleName(); }
                    list.add(p);
                    sb.append("   phys ").append(pid).append(": ");
                    if (p.usable) sb.append(String.format(Locale.ROOT, "%.1f mm (35mm-eq %.0f mm), sensor %.1fx%.1f mm, max %dx%d, min focus %s%s",
                            p.focalMm, p.equiv35, p.sensorMm.getWidth(), p.sensorMm.getHeight(), p.best.getWidth(), p.best.getHeight(),
                            p.minFocusDioptre > 0 ? String.format(Locale.ROOT, "%.0f mm", 1000.0 / p.minFocusDioptre) : "fixed focus",
                            p.pose != null ? String.format(Locale.ROOT, ", pose (%.1f, %.1f, %.1f) mm", p.pose[0] * 1000, p.pose[1] * 1000, p.pose[2] * 1000) : ", no pose"));
                    else sb.append("not usable: ").append(p.why);
                    sb.append('\n');
                }
                Integer sync = cc.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE);
                sb.append("   sensor sync: ").append(sync == null ? "?" : sync == CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED ? "calibrated" : "approximate").append('\n');
                for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
                    Phys a = list.get(i), b = list.get(j);
                    if (!a.usable || !b.usable) continue;
                    Pair p = new Pair(); p.logicalId = id; p.a = a; p.b = b;
                    if (a.pose != null && b.pose != null) p.baselineMm = 1000 * Math.sqrt(Math.pow(a.pose[0] - b.pose[0], 2) + Math.pow(a.pose[1] - b.pose[1], 2) + Math.pow(a.pose[2] - b.pose[2], 2));
                    r.pairs.add(p);
                }
            }
        } catch (Exception e) { sb.append("\nError reading cameras: ").append(e).append('\n'); }
        // Paare mit aehnlichster Brennweite zuerst (einfacheres Entzerren)
        java.util.Collections.sort(r.pairs, (x, y) -> Double.compare(x.ratio(), y.ratio()));
        sb.append("\nPairs: ").append(r.pairs.size()).append('\n');
        for (Pair p : r.pairs) sb.append("  ").append(p.key()).append(String.format(Locale.ROOT, "  focal ratio %.2f, baseline %s\n", p.ratio(), Double.isNaN(p.baselineMm) ? "?" : String.format(Locale.ROOT, "%.1f mm", p.baselineMm)));
        return r;
    }
}
