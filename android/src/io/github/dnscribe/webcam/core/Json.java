package io.github.dnscribe.webcam.core;

import org.json.JSONException;
import org.json.JSONObject;

final class Json {
    private Json() {}
    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); }
        catch (JSONException e) { throw new RuntimeException(e); }
        return o;
    }
}
