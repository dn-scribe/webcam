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
    String recStart();
    void recStop();
    /** {state: idle|recording, elapsed, size, last:{id,size,name}} */
    JSONObject recStatus();
    /** File of a finished recording (id = video id), or null. */
    LibraryStore.Item recItem(long id);
    void discardRec(long id);
    LibraryStore library();
}
