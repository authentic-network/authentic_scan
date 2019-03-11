package com.example.authentic_scan;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.util.Pair;
import android.util.Rational;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;

public class Camera2EnumToJsonConverter {

    public JSONObject toJson(CaptureResult metadata) {
        JSONObject result = new JSONObject();
        for(CaptureResult.Key key: metadata.getKeys()) {
            try {
                result.put(key.getName(), valueToJSON(metadata.get(key)));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    public JSONObject toJson(CameraCharacteristics metadata) {
        JSONObject result = new JSONObject();
        for(CameraCharacteristics.Key key: metadata.getKeys()) {
            try {
                result.put(key.getName(), valueToJSON(metadata.get(key)));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return result;
    }


    private Object valueToJSON(Pair<?,?> value) throws JSONException {
        // LENS_FOCUS_RANGE, SENSOR_NOISE_PROFILE
        JSONArray pair = new JSONArray();
        pair.put(((Pair) value).first);
        pair.put(((Pair) value).second);
        return pair;
    }

    private Object valueToJSON(Rect value) throws JSONException {
        // SCALER_CROP_REGION
        JSONObject rect = new JSONObject();

        rect.put("left", ((Rect) value).left);
        rect.put("top", ((Rect) value).top);
        rect.put("width", ((Rect) value).width());
        rect.put("height", ((Rect) value).height());
        return rect;
    }

    private JSONArray arrayValueToJSON(Object value) throws JSONException {
        assert value.getClass().isArray();
        JSONArray array = new JSONArray();
        for(int i = 0; i < Array.getLength(value); ++i) {
            array.put(valueToJSON(Array.get(value, i)));
        }
        return array;
    }

    private Object valueToJSON(Rational value) throws JSONException {
        // (SENSOR_NEUTRAL_COLOR_POINT, STATISTICS_PREDICTED_COLOR_TRANSFORM)
        JSONArray rational = new JSONArray();
        rational.put(value.getNumerator());
        rational.put(value.getDenominator());
        return rational;
    }

    private Object valueToJSON(android.graphics.Point value) throws JSONException {
        //STATISTICS_HOT_PIXEL_MAP
        JSONArray point = new JSONArray();
        point.put(value.x);
        point.put(value.y);
        return point;
    }

    private Object valueToJSON(Object value) throws JSONException {
        if(value != null && value.getClass().isArray()) {
            return arrayValueToJSON(value);
        } else {
            Object wrappedValue = JSONObject.wrap(value);
            if(wrappedValue != null) {
                return wrappedValue;
            } else if(value instanceof Pair<?,?>) {
                return valueToJSON((Pair)value);
            } else if(value instanceof Rect) {
                return valueToJSON((Rect)value);
            } else if(value instanceof Rational) {
                return valueToJSON((Rational)value);
            } else if(value instanceof android.graphics.Point) {
                return valueToJSON((android.graphics.Point)value);
            } else {
                // android.hardware.camera2.params.StreamConfigurationMap
                return value.toString();
                /**
                 * What about:
                 * - android.hardware.camera2.params.ColorSpaceTransform
                 * - android.hardware.camera2.params.RggbChannelVector
                 * - android.hardware.camera2.params.MeteringRectangle[]
                 * - android.util.Range<Integer> (only CONTROL_AE_TARGET_FPS_RANGE)
                 * - android.location.Location
                 * - android.util.Size (only JPEG_THUMBNAIL_SIZE)
                 * - android.graphics.Rect[] (STATISTICS_FACE_RECTANGLES)
                 * - android.hardware.camera2.params.Face[]
                 * - android.hardware.camera2.params.LensShadingMap
                 * - android.hardware.camera2.params.TonemapCurve (only TONEMAP_CURVE, no search criterion)
                 * - android.hardware.camera2.params.OisSample
                 */
            }
        }
    }
}
