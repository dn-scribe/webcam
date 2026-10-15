package io.github.dnscribe.webcam.core;

import java.io.File;
import org.json.JSONObject;

/** What the hub protocol needs from the camera side. Implemented by the Android service, and by a fake in the protocol test. */
public interface Device {
    interface SnapCallback { void done(byte[] jpeg, int w, int h, String error); }

    /** Body of the `state` message (settings, caps, devices, video, recording, streaming, version...). */
    JSONObject state();
    /** Apply settings from the hub; returns an error message or null. */
    String set(JSONObject settings);
    /** Preview stream control; 0 / negative values mean "unchanged". */
    void stream(boolean on, int fps, int width, double quality);
    /** Take a photo (and store it in the library); quality < 0 = default. */
    void snap(double quality, SnapCallback cb);
    /** Start a clip; `label` (may be empty) goes into the file name, e.g. "run0204". */
    String recStart(String label);
    /** An annotation from the hub, kept with the recording in progress: {recording, phone_wall_ms, phone_mono_ns, rec_ms?}. */
    JSONObject mark(String text, double hostT);
    /** The record kept beside a finished clip (clocks at the first frame, marks), or null. */
    JSONObject recMeta(long id);
    /** The phone's monotonic clock in nanoseconds (the clock a recording's timestamps use). */
    long monoNs();
    /** The phone's wall clock in milliseconds since 1970. */
    long wallMs();
    void recStop();
    /** {state: idle|recording, elapsed, size, last:{id,size,name}} */
    JSONObject recStatus();
    /** File of a finished recording (id = video id), or null. */
    LibraryStore.Item recItem(long id);
    void discardRec(long id);
    LibraryStore library();
}
