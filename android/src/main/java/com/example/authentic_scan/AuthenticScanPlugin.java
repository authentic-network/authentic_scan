package com.example.authentic_scan;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.app.FlutterPluginRegistry;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import network.authentic.scan.pipeline.AuthenticScan;
import network.authentic.scan.pipeline.CopyClassification;


/** AuthenticScanPlugin */
public class AuthenticScanPlugin implements MethodCallHandler {

  private static final int CAMERA_REQUEST_ID = 513469796;
  private static final String CHANNEL = "authentic.network/camera";
  private static final String TAG = "Java Wrapper";

  private CameraManager cameraManager;
  private Camera camera;
  private Activity activity;
  private final FlutterView view;
  private Registrar registrar;

  private Application.ActivityLifecycleCallbacks activityLifecycleCallbacks;
  private OrientationEventListener orientationEventListener;
  private int currentOrientation = ORIENTATION_UNKNOWN;

  private Runnable cameraPermissionContinuation;
  private boolean requestingPermission;
  private MethodChannel methodChannel;
  private Handler mBackgroundHandler;

  private int imageModeCaptureRequestFlag = 0;
  private int imageModeCapturedFlag = 0;
  private JSONObject resultJson = null;

  private String rawPath;
  private String jpgPath;
  private String cameraId;

  private TotalCaptureResult currentTotalCaptureResult;

  final static private int FLAG_IMAGE_JPEG = 0x01;
  final static private int FLAG_IMAGE_RAW = 0x02;

  /**
   * A counter for tracking corresponding {@link CaptureRequest}s and {@link CaptureResult}s
   * across the {@link CameraCaptureSession} capture callbacks.
   */
  private final AtomicInteger mRequestCounter = new AtomicInteger();

  /**
   * A lock protecting camera state.
   */
  private final Object mCameraStateLock = new Object();

  private AuthenticScanPlugin(Registrar registrar, FlutterView view, Activity activity) {
    this.registrar = registrar;
    this.view = view;
    this.activity = activity;

    orientationEventListener =
            new OrientationEventListener(activity.getApplicationContext()) {
              @Override
              public void onOrientationChanged(int i) {
                if (i == ORIENTATION_UNKNOWN) {
                  return;
                }
                // Convert the raw deg angle to the nearest multiple of 90.
                currentOrientation = (int) Math.round(i / 90.0) * 90;
              }
            };

    registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());

    this.activityLifecycleCallbacks =
            new Application.ActivityLifecycleCallbacks() {
              @Override
              public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

              @Override
              public void onActivityStarted(Activity activity) {}

              @Override
              public void onActivityResumed(Activity activity) {
                boolean wasRequestingPermission = requestingPermission;
                if (requestingPermission) {
                  requestingPermission = false;
                }
                if (activity != AuthenticScanPlugin.this.activity) {
                  return;
                }
                orientationEventListener.enable();
                if (camera != null && !wasRequestingPermission) {
                  camera.open(null);
                }
              }

              @Override
              public void onActivityPaused(Activity activity) {
                if (activity == AuthenticScanPlugin.this.activity) {
                  orientationEventListener.disable();
                  if (camera != null) {
                    camera.close();
                  }
                }
              }

              @Override
              public void onActivityStopped(Activity activity) {
                if (activity == AuthenticScanPlugin.this.activity) {
                  if (camera != null) {
                    camera.close();
                  }
                }
              }

              @Override
              public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

              @Override
              public void onActivityDestroyed(Activity activity) {}
            };

    cameraManager = (CameraManager) activity.getApplication().getSystemService(Context.CAMERA_SERVICE);
  }


  /** Plugin registration. */
  public static void registerWith(Registrar registrar) {
    if (registrar.activity() == null) {
      // When a background flutter view tries to register the plugin, the registrar has no activity.
      // We stop the registration process as this plugin is foreground only.
      return;
    }
    final MethodChannel channel =
            new MethodChannel(registrar.messenger(), "authentic.network/camera");

    channel.setMethodCallHandler(new AuthenticScanPlugin(registrar, registrar.view(), registrar.activity()));
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {

    switch (call.method) {
      case "availableCameras":
        try {
          String[] cameraNames = cameraManager.getCameraIdList();
          List<Map<String, Object>> cameras = new ArrayList<>();
          for (String cameraName : cameraNames) {
            HashMap<String, Object> details = new HashMap<>();
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraName);
            details.put("name", cameraName);
            @SuppressWarnings("ConstantConditions")
            int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            switch (lensFacing) {
              case CameraMetadata.LENS_FACING_FRONT:
                details.put("lensFacing", "front");
                break;
              case CameraMetadata.LENS_FACING_BACK:
                details.put("lensFacing", "back");
                break;
              case CameraMetadata.LENS_FACING_EXTERNAL:
                details.put("lensFacing", "external");
                break;
            }
            cameras.add(details);
          }
          result.success(cameras);
        } catch (CameraAccessException e) {
          result.error("cameraAccess", e.getMessage(), null);
        }
        break;
      case "initialize":
      {

        this.activityLifecycleCallbacks =
                new Application.ActivityLifecycleCallbacks() {
                  @Override
                  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

                  @Override
                  public void onActivityStarted(Activity activity) {}

                  @Override
                  public void onActivityResumed(Activity activity) {

                    //if (requestingPermission) {
                    //    requestingPermission = false;
                    //    return;
                    //}

                    if (activity == AuthenticScanPlugin.this.activity) {
                      //orientationEventListener.enable();
                      if (camera != null) {
                        camera.open(null);
                      }
                    }
                  }

                  @Override
                  public void onActivityPaused(Activity activity) {
                    if (activity == AuthenticScanPlugin.this.activity) {
                      //orientationEventListener.disable();
                      if (camera != null) {
                        camera.close();
                      }
                    }
                  }

                  @Override
                  public void onActivityStopped(Activity activity) {
                    if (activity == AuthenticScanPlugin.this.activity) {
                      if (camera != null) {
                        camera.close();
                      }
                    }
                  }

                  @Override
                  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

                  @Override
                  public void onActivityDestroyed(Activity activity) {}
                };


        String cameraName = call.argument("cameraName");
        String resolutionPreset = call.argument("resolutionPreset");
        if (camera != null) {
          camera.close();
        }

        camera = new Camera(AuthenticScanPlugin.this.view, cameraName, resolutionPreset, result);

        Log.d(TAG, "Camera instantiated");

        //this.getApplication().registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks);
        //orientationEventListener.enable();
        break;
      }
      case "startPreview":
      {
        try {
          camera.startPreview();
          result.success(null);
        } catch (CameraAccessException e) {
          result.error("CameraAccess", e.getMessage(), null);
        }
        break;
      }
      case "retriveCameraOptions": {
        try {
          camera.retriveCameraOptions((String) call.argument("cameraId"), result);
        } catch (Exception e) {
          result.error("CameraAccess", e.getMessage(), null);
        }
        break;
      }
      case "capteringPicture":
      {
        Log.d(TAG, "capteringPicture called on " + Thread.currentThread().getName());
        try {
          camera.capteringPicture((String) call.argument("rawPath"), (String) call.argument("jpgPath"), (String) call.argument("cameraId"), (String) call.argument("type"), result);
        } catch (Exception e) {
          result.error("CameraAccess", e.getMessage(), null);
        }
        break;
      }
      case "startVideoRecording":
      {
        break;
      }
      case "stopVideoRecording":
      {
        break;
      }
      case "startImageStream":
      {
        try {
          camera.startPreviewWithImageStream();
          result.success(null);
        } catch (CameraAccessException e) {
          result.error("CameraAccess", e.getMessage(), null);
        }
        break;
      }
      case "stopImageStream":
      {
        try {
          camera.startPreview();
          result.success(null);
        } catch (CameraAccessException e) {
          result.error("CameraAccess", e.getMessage(), null);
        }
        break;
      }
      case "dispose":
      {
        if (camera != null) {
          camera.dispose();
        }

        result.success(null);
        break;
      }
      default:
        result.notImplemented();
        break;
    }
  }

  private Result currentCaptureResult;

  private void dequeueAndSaveImage(RefCountedAutoCloseable<ImageReader> reader, int type) {
    synchronized (mCameraStateLock) {

      imageModeCapturedFlag |= type;

      // Increment reference count to prevent ImageReader from being closed while we
      // are saving its Images in a background thread (otherwise their resources may
      // be freed while we are writing to a file).
      if (reader == null || reader.getAndRetain() == null) {
        Log.e(TAG, "Paused the activity before we could save the image," +
                " ImageReader already closed.");
        return;
      }

      Image image;
      try {
        image = reader.get().acquireNextImage();
      } catch (IllegalStateException e) {
        Log.e(TAG, "Too many images queued for saving, dropping image for request");
        return;
      }
      // Access jpeg encoded byte array provided by camera subsystem
      ByteBuffer buffer = image.getPlanes()[0].getBuffer();
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      image.close();

      // convert into croppable format
      Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);

      // handle possibly swapped axis
      int shortesAxis = Math.min(bitmapImage.getWidth(), bitmapImage.getHeight());
      // TODO: WARNING: Deduplicate this factor as it has to be in-sync with the scan_screen.dart ui rectangle!
      int cropRegionEdgeLength = (int) Math.round(shortesAxis*0.8);

      // crop the image
      Bitmap croppedBitmapImage = bitmapImage.createBitmap(bitmapImage, Math.abs(bitmapImage.getWidth()-cropRegionEdgeLength)/2,
              Math.abs(bitmapImage.getHeight()-cropRegionEdgeLength)/2,
              cropRegionEdgeLength, cropRegionEdgeLength);

      // access jpeg encoded byte array
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      croppedBitmapImage.compress(Bitmap.CompressFormat.JPEG, 100, stream);
      bytes = stream.toByteArray();

      if(true) {

        AuthenticScan scanPipeline = new AuthenticScan();
        scanPipeline.scan(bytes);
        CopyClassification copyClassification = scanPipeline.getCopyClassification();
        boolean[] result = scanPipeline.getCode();
        float[] copyClassificationResults=scanPipeline.getPantoneClassificationValues();

        Log.d(TAG, "Classification Values are: deltaE "+copyClassificationResults[0] + " redDifference " +
                copyClassificationResults[1] + " score " + copyClassificationResults[2]);
        Log.d(TAG, "Scan is classified as: "+scanPipeline.getCopyClassification().toString());

        try {

          JSONObject copyDetectionDebug = new JSONObject();
          copyDetectionDebug.put("deltaE", copyClassificationResults[0]);
          copyDetectionDebug.put("redDifference", copyClassificationResults[1]);
          copyDetectionDebug.put("score", copyClassificationResults[2]);
          copyDetectionDebug.put("classified", scanPipeline.getCopyClassification().toString());

          JSONArray jsonArray = new JSONArray(result);
          JSONObject jsonResult = new JSONObject();
          jsonResult.put("id", Util.byte2hex(Util.boolean2byte(result)));
          jsonResult.put("copyClassification", copyClassification.name());
          jsonResult.put("copyDetection", copyDetectionDebug);

          currentCaptureResult.success(jsonResult.toString());

        } catch(JSONException e) {
          currentCaptureResult.error(TAG, "Could not create result json", e);
        }
      } else {

        Log.i("MainActivity", "Scan pipline is not running");

        try {
          resultJson.put(type == FLAG_IMAGE_RAW ? "raw" : "jpg", Base64.encodeToString(bytes, Base64.NO_WRAP));
        } catch (JSONException e) {
          Log.e(TAG, "Error while generating result JSON", e);
        }

        // TODO: how to avoid currentCaptureResult being overwritten while we are still waiting
        // to submit our success

        Log.d(TAG, "Returning image on " + Thread.currentThread().getName());

        Log.d(TAG, "imageModeCapturedFlag: " + imageModeCapturedFlag + ", imageModeCaptureRequestFlag: " + imageModeCaptureRequestFlag);

        if(imageModeCapturedFlag == imageModeCaptureRequestFlag) {
          final String result = resultJson.toString();
          resultJson = null;
          currentCaptureResult.success(result);
        }
      }
    }
  }

  private class Camera {

    private final FlutterView.SurfaceTextureEntry textureEntry;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private EventChannel.EventSink eventSink;
    private ImageReader imageStreamReader;
    private int sensorOrientation;
    private boolean isFrontFacing;
    private String cameraName;
    private Size captureSize;
    private Size previewSize;
    private Size rawSize;
    private CaptureRequest.Builder captureRequestBuilder;

    /**
     * A reference counted holder wrapping the {@link ImageReader} that handles JPEG image
     * captures. This is used to allow us to clean up the {@link ImageReader} when all background
     * tasks using its {@link Image}s have completed.
     */
    private RefCountedAutoCloseable<ImageReader> mJpegImageReader;

    /**
     * A reference counted holder wrapping the {@link ImageReader} that handles RAW image captures.
     * This is used to allow us to clean up the {@link ImageReader} when all background tasks using
     * its {@link Image}s have completed.
     */
    private RefCountedAutoCloseable<ImageReader> mRawImageReader;
    private boolean rawSupported;

    Camera(FlutterView view, final String cameraName, final String resolutionPreset, @NonNull final Result result) {

      this.cameraName = cameraName;
      textureEntry = view.createSurfaceTexture();

      registerEventChannel();

      Log.d(TAG, "after registerEventChannel");

      try {
        Size minPreviewSize;
        switch (resolutionPreset) {
          case "high":
            minPreviewSize = new Size(1024, 768);
            break;
          case "medium":
            minPreviewSize = new Size(640, 480);
            break;
          case "low":
            minPreviewSize = new Size(320, 240);
            break;
          default:
            throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
        }

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);

        Log.d(TAG, "after characteristics");

        StreamConfigurationMap streamConfigurationMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        Log.d(TAG, "after streamConfigurationMap");

        //noinspection ConstantConditions
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        Log.d(TAG, "after sensorOrientation");

        //noinspection ConstantConditions
        isFrontFacing =
                characteristics.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_FRONT;

        Log.d(TAG, "after isFrontFacing");

        computeBestCaptureSize(streamConfigurationMap);

        Log.d(TAG, "after computeBestCaptureSize");

        computeBestPreviewAndRecordingSize(streamConfigurationMap, minPreviewSize, captureSize);

        Log.d(TAG, "after computeBestPreviewAndRecordingSize");

        Log.d(TAG, "Supported hardware level: " + characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL).toString());
        rawSupported = contains(characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW);

        if(rawSupported) {
          rawSize = bestRawSize(streamConfigurationMap);
        }

        if (cameraPermissionContinuation != null) {
          result.error("cameraPermission", "Camera permission request ongoing", null);
        }
        cameraPermissionContinuation =
                new Runnable() {
                  @Override
                  public void run() {
                    cameraPermissionContinuation = null;
                    if (!hasCameraPermission()) {
                      Log.d(TAG, "MediaRecorderCamera permission not granted");
                      result.error(
                              "cameraPermission", "MediaRecorderCamera permission not granted", null);
                      return;
                    }
                    if (!hasAudioPermission()) {
                      Log.d(TAG, "MediaRecorderAudio permission not granted");
                      result.error(
                              "cameraPermission", "MediaRecorderAudio permission not granted", null);
                      return;
                    }
                    open(result);
                  }
                };
        requestingPermission = false;
        if (hasCameraPermission() && hasAudioPermission()) {
          Log.d(TAG, "hasCameraPermission() && hasAudioPermission()");
          cameraPermissionContinuation.run();
        } else {
          Log.d(TAG, "else hasCameraPermission() && hasAudioPermission()");
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestingPermission = true;
            Log.d(TAG, "requestingPermission");
            AuthenticScanPlugin.this.activity
                    .requestPermissions(
                            new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                            CAMERA_REQUEST_ID);
            Log.d(TAG, "requestingPermission done!");
          }
        }
      } catch (CameraAccessException e) {
        result.error("CameraAccess", e.getMessage(), null);
      } catch (IllegalArgumentException e) {
        result.error("IllegalArgumentException", e.getMessage(), null);
      }
    }

    private void registerEventChannel() {
      new EventChannel(AuthenticScanPlugin.this.view
              , "authentic.network/camera/cameraEvents" + textureEntry.id())
              .setStreamHandler(
                      new EventChannel.StreamHandler() {
                        @Override
                        public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                          AuthenticScanPlugin.Camera.this.eventSink = eventSink;
                        }

                        @Override
                        public void onCancel(Object arguments) {
                          AuthenticScanPlugin.Camera.this.eventSink = null;
                        }
                      });
    }

    private boolean hasCameraPermission() {
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
              || AuthenticScanPlugin.this.activity.checkSelfPermission(Manifest.permission.CAMERA)
              == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
              || AuthenticScanPlugin.this.activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
              == PackageManager.PERMISSION_GRANTED;
    }

    private void computeBestPreviewAndRecordingSize(
            StreamConfigurationMap streamConfigurationMap, Size minPreviewSize, Size captureSize) {
      Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);

      // Preview size and video size should not be greater than screen resolution or 1080.
      Point screenResolution = new Point();
      Display display = AuthenticScanPlugin.this.activity.getWindowManager().getDefaultDisplay();
      display.getRealSize(screenResolution);

      final boolean swapWH = getMediaOrientation() % 180 == 90;
      int screenWidth = swapWH ? screenResolution.y : screenResolution.x;
      int screenHeight = swapWH ? screenResolution.x : screenResolution.y;

      List<Size> goodEnough = new ArrayList<>();
      for (Size s : sizes) {
        if (minPreviewSize.getWidth() < s.getWidth()
                && minPreviewSize.getHeight() < s.getHeight()
                && s.getWidth() <= screenWidth
                && s.getHeight() <= screenHeight
                && s.getHeight() <= 1080) {
          goodEnough.add(s);
        }
      }

      Collections.sort(goodEnough, new CompareSizesByArea());

      if (goodEnough.isEmpty()) {
        previewSize = sizes[0];
      } else {
        float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();

        previewSize = goodEnough.get(0);
        for (Size s : goodEnough) {
          if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
            previewSize = s;
            break;
          }
        }
      }
    }

    private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
      // For still image captures, we use the largest available size.
      captureSize =
              Collections.max(
                      Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
                      new CompareSizesByArea());
    }

    @SuppressLint("MissingPermission")
    private void open(@Nullable final Result result) {
      if (!hasCameraPermission()) {
        if (result != null) result.error("cameraPermission", "Camera permission not granted", null);
      } else {
        try {
          // Used to steam image byte data to dart side.
          imageStreamReader =
                  ImageReader.newInstance(
                          previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

          synchronized (mCameraStateLock) {
            // Set up ImageReaders for JPEG and RAW outputs.  Place these in a reference
            // counted wrapper to ensure they are only closed when all background tasks
            // using them are finished.
            if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
              mJpegImageReader = new RefCountedAutoCloseable<>(
                      ImageReader.newInstance(captureSize.getWidth(),
                              captureSize.getHeight(), ImageFormat.JPEG, /*maxImages*/5));
            }

            imageModeCaptureRequestFlag = imageModeCapturedFlag = 0;
            mJpegImageReader.get().setOnImageAvailableListener(
                    mOnJpegImageAvailableListener, null);

            imageModeCaptureRequestFlag |= FLAG_IMAGE_JPEG;

            if(rawSupported) {
              if (mRawImageReader == null || mRawImageReader.getAndRetain() == null) {
                Log.d(TAG, "width: " + rawSize.getWidth() + ", height: " + rawSize.getHeight() + ", format: " + ImageFormat.RAW_SENSOR);
                mRawImageReader = new RefCountedAutoCloseable<>(
                        ImageReader.newInstance(rawSize.getWidth(),
                                rawSize.getHeight(), ImageFormat.RAW_SENSOR, /*maxImages*/ 5));
              }
              mRawImageReader.get().setOnImageAvailableListener(
                      mOnRawImageAvailableListener, mBackgroundHandler);

              imageModeCaptureRequestFlag |= FLAG_IMAGE_RAW;
            }
          }

          cameraManager.openCamera(
                  cameraName,
                  new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice cameraDevice) {
                      AuthenticScanPlugin.Camera.this.cameraDevice = cameraDevice;
                      try {
                        startPreview();
                      } catch (CameraAccessException e) {
                        if (result != null) result.error("CameraAccess", e.getMessage(), null);
                        cameraDevice.close();
                        AuthenticScanPlugin.Camera.this.cameraDevice = null;
                        return;
                      }

                      if (result != null) {
                        Map<String, Object> reply = new HashMap<>();
                        reply.put("textureId", textureEntry.id());
                        reply.put("previewWidth", previewSize.getWidth());
                        reply.put("previewHeight", previewSize.getHeight());
                        result.success(reply);
                      }
                    }

                    @Override
                    public void onClosed(@NonNull CameraDevice camera) {
                      if (eventSink != null) {
                        Map<String, String> event = new HashMap<>();
                        event.put("eventType", "cameraClosing");
                        eventSink.success(event);
                      }
                      super.onClosed(camera);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                      cameraDevice.close();
                      AuthenticScanPlugin.Camera.this.cameraDevice = null;
                      sendErrorEvent("The camera was disconnected.");
                    }

                    @Override
                    public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                      cameraDevice.close();
                      AuthenticScanPlugin.Camera.this.cameraDevice = null;
                      String errorDescription;
                      switch (errorCode) {
                        case ERROR_CAMERA_IN_USE:
                          errorDescription = "The camera device is in use already.";
                          break;
                        case ERROR_MAX_CAMERAS_IN_USE:
                          errorDescription = "Max cameras in use";
                          break;
                        case ERROR_CAMERA_DISABLED:
                          errorDescription =
                                  "The camera device could not be opened due to a device policy.";
                          break;
                        case ERROR_CAMERA_DEVICE:
                          errorDescription = "The camera device has encountered a fatal error";
                          break;
                        case ERROR_CAMERA_SERVICE:
                          errorDescription = "The camera service has encountered a fatal error.";
                          break;
                        default:
                          errorDescription = "Unknown camera error";
                      }
                      sendErrorEvent(errorDescription);
                    }
                  },
                  null);
        } catch (CameraAccessException e) {
          if (result != null) result.error("cameraAccess", e.getMessage(), null);
        }
      }
    }

    private Size bestRawSize(StreamConfigurationMap map) {
      Size largestRaw = null;
      if(rawSupported) {
        largestRaw = Collections.max(
                Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
                new CompareSizesByArea());
      }
      return largestRaw;
    }

    private void writeToFile(ByteBuffer buffer, File file) throws IOException {
      try (FileOutputStream outputStream = new FileOutputStream(file)) {
        while (0 < buffer.remaining()) {
          outputStream.getChannel().write(buffer);
        }
      }
    }

    private void writeToStream(ByteBuffer buffer, OutputStream outputStream) throws IOException {
      while (0 < buffer.remaining()) {
        outputStream.write(buffer.get());
      }
    }

    private void retriveCameraOptions(String cameraId, @NonNull final Result result) {
      // upload aller characteristics
      // settings mitgeben für takePicture in raw format und jpeg
      Camera2EnumToJsonConverter converter = new Camera2EnumToJsonConverter();
      try {
        JSONObject json = converter.toJson(cameraManager.getCameraCharacteristics(cameraId));
        result.success(json.toString());
      } catch (Exception e) {
        result.error("cameraAccess", e.getMessage(), null);
      }
    }

    private void capteringPicture(String lRawPath, String lJpgPath, String lCameraId, String type, @NonNull final Result result) {
      currentCaptureResult = new ResultWrapper(result);
      Log.d("capteringPicture", "rawPath: " + rawPath + ", jpgPath: " + jpgPath + ", cameraId: " + lCameraId);
      try {
        final CaptureRequest.Builder captureBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

        captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        resultJson = new JSONObject();

        rawPath = lRawPath;
        jpgPath = lJpgPath;
        cameraId = lCameraId;

        imageModeCapturedFlag = 0;
        imageModeCaptureRequestFlag = FLAG_IMAGE_JPEG;
        captureBuilder.addTarget(mJpegImageReader.get().getSurface());

        if("full".equals(type)) {
          if (rawSupported && mRawImageReader != null) {
            captureBuilder.addTarget(mRawImageReader.get().getSurface());
            imageModeCaptureRequestFlag |= FLAG_IMAGE_RAW;
          }
        }

        captureBuilder.setTag(mRequestCounter.getAndIncrement());
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

        if("full".equals(type)) {
          captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
        } else if("preview".equals(type)) {
          captureBuilder.set(CaptureRequest.JPEG_THUMBNAIL_QUALITY, (byte) 50);
        } else {
          captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 75);
        }


        cameraCaptureSession.capture(
                captureBuilder.build(),
                new CameraCaptureSession.CaptureCallback() {
                  @Override
                  public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    Log.d(TAG, "Capture completed, onCaptureCompleted");
                    currentTotalCaptureResult = result;
                    super.onCaptureCompleted(session, request, result);
                  }

                  @Override
                  public void onCaptureFailed(
                          @NonNull CameraCaptureSession session,
                          @NonNull CaptureRequest request,
                          @NonNull CaptureFailure failure) {
                    Log.d(TAG, "Capture failure");
                    String reason;
                    switch (failure.getReason()) {
                      case CaptureFailure.REASON_ERROR:
                        reason = "An error happened in the framework";
                        break;
                      case CaptureFailure.REASON_FLUSHED:
                        reason = "The capture has failed due to an abortCaptures() call";
                        break;
                      default:
                        reason = "Unknown reason";
                    }
                    result.error("captureFailure", reason, null);
                  }
                },
                null);
      } catch (CameraAccessException e) {
        result.error("cameraAccess", e.getMessage(), null);
      }
    }

    private void startPreview() throws CameraAccessException {
      closeCaptureSession();

      SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
      surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
      captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

      List<Surface> surfaces = new ArrayList<>();

      Surface previewSurface = new Surface(surfaceTexture);
      surfaces.add(previewSurface);
      captureRequestBuilder.addTarget(previewSurface);

      surfaces.add(mJpegImageReader.get().getSurface());
      if(mRawImageReader != null) {
        surfaces.add(mRawImageReader.get().getSurface());
      }

      cameraDevice.createCaptureSession(
              surfaces,
              new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                  if (cameraDevice == null) {
                    sendErrorEvent("The camera was closed during configuration.");
                    return;
                  }
                  try {
                    cameraCaptureSession = session;
                    captureRequestBuilder.set(
                            CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                  } catch (CameraAccessException e) {
                    sendErrorEvent(e.getMessage());
                  }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                  sendErrorEvent("Failed to configure the camera for preview.");
                }
              },
              null);
    }

    private void startPreviewWithImageStream() throws CameraAccessException {
      closeCaptureSession();

      SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
      surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

      captureRequestBuilder =
              cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

      List<Surface> surfaces = new ArrayList<>();

      Surface previewSurface = new Surface(surfaceTexture);
      surfaces.add(previewSurface);
      captureRequestBuilder.addTarget(previewSurface);

      surfaces.add(imageStreamReader.getSurface());
      captureRequestBuilder.addTarget(imageStreamReader.getSurface());

      cameraDevice.createCaptureSession(
              surfaces,
              new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                  if (cameraDevice == null) {
                    sendErrorEvent("The camera was closed during configuration.");
                    return;
                  }
                  try {
                    cameraCaptureSession = session;
                    captureRequestBuilder.set(
                            CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                  } catch (CameraAccessException e) {
                    sendErrorEvent(e.getMessage());
                  }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                  sendErrorEvent("Failed to configure the camera for streaming images.");
                }
              },
              null);

      registerImageStreamEventChannel();
    }

    private void registerImageStreamEventChannel() {
      final EventChannel imageStreamChannel =
              new EventChannel(AuthenticScanPlugin.this.view,  CHANNEL + "/imageStream");

      imageStreamChannel.setStreamHandler(
              new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object o, EventChannel.EventSink eventSink) {
                  setImageStreamImageAvailableListener(eventSink);
                }

                @Override
                public void onCancel(Object o) {
                  imageStreamReader.setOnImageAvailableListener(null, null);
                }
              });
    }

    private void setImageStreamImageAvailableListener(final EventChannel.EventSink eventSink) {
      imageStreamReader.setOnImageAvailableListener(
              new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(final ImageReader reader) {
                  Image img = reader.acquireLatestImage();
                  if (img == null) return;

                  List<Map<String, Object>> planes = new ArrayList<>();
                  for (Image.Plane plane : img.getPlanes()) {
                    ByteBuffer buffer = plane.getBuffer();

                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes, 0, bytes.length);

                    Map<String, Object> planeBuffer = new HashMap<>();
                    planeBuffer.put("bytesPerRow", plane.getRowStride());
                    planeBuffer.put("bytesPerPixel", plane.getPixelStride());
                    planeBuffer.put("bytes", bytes);

                    planes.add(planeBuffer);
                  }

                  Map<String, Object> imageBuffer = new HashMap<>();
                  imageBuffer.put("width", img.getWidth());
                  imageBuffer.put("height", img.getHeight());
                  imageBuffer.put("format", img.getFormat());
                  imageBuffer.put("planes", planes);

                  eventSink.success(imageBuffer);
                  img.close();
                }
              },
              null);
    }

    private void sendErrorEvent(String errorDescription) {
      if (eventSink != null) {
        Map<String, String> event = new HashMap<>();
        event.put("eventType", "error");
        event.put("errorDescription", errorDescription);
        eventSink.success(event);
      }
    }

    private void closeCaptureSession() {
      if (cameraCaptureSession != null) {
        cameraCaptureSession.close();
        cameraCaptureSession = null;
      }
    }

    private void close() {
      closeCaptureSession();

      if (cameraDevice != null) {
        cameraDevice.close();
        cameraDevice = null;
      }
      if (imageStreamReader != null) {
        imageStreamReader.close();
        imageStreamReader = null;
      }
    }

    private void dispose() {
      close();
      textureEntry.release();
    }

    private int getMediaOrientation() {
      final int sensorOrientationOffset =
              (currentOrientation == ORIENTATION_UNKNOWN)
                      ? 0
                      : (isFrontFacing) ? -currentOrientation : currentOrientation;
      return (sensorOrientationOffset + sensorOrientation + 360) % 360;
    }

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * JPEG image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

      @Override
      public void onImageAvailable(ImageReader reader) {
        Log.d(TAG, "JPEG image available");
        dequeueAndSaveImage(mJpegImageReader, FLAG_IMAGE_JPEG);
      }

    };

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * RAW image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

      @Override
      public void onImageAvailable(ImageReader reader) {
        Log.d(TAG, "RAW image available");
        dequeueAndSaveImage(mRawImageReader, FLAG_IMAGE_RAW);
      }

    };

  }

  private static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(Size lhs, Size rhs) {
      // We cast here to ensure the multiplications won't overflow.
      return Long.signum(
              (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  private class CameraRequestPermissionsListener
          implements FlutterPluginRegistry.RequestPermissionsResultListener {
    @Override
    public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
      if (id == CAMERA_REQUEST_ID) {
        cameraPermissionContinuation.run();
        return true;
      }
      return false;
    }
  }

  /**
   * A wrapper for an {@link AutoCloseable} object that implements reference counting to allow
   * for resource management.
   */
  public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
    private T mObject;
    private long mRefCount = 0;

    /**
     * Wrap the given object.
     *
     * @param object an object to wrap.
     */
    public RefCountedAutoCloseable(T object) {
      if (object == null) throw new NullPointerException();
      mObject = object;
    }

    /**
     * Increment the reference count and return the wrapped object.
     *
     * @return the wrapped object, or null if the object has been released.
     */
    public synchronized T getAndRetain() {
      if (mRefCount < 0) {
        return null;
      }
      mRefCount++;
      return mObject;
    }

    /**
     * Return the wrapped object.
     *
     * @return the wrapped object, or null if the object has been released.
     */
    public synchronized T get() {
      return mObject;
    }

    /**
     * Decrement the reference count and release the wrapped object if there are no other
     * users retaining this object.
     */
    @Override
    public synchronized void close() {
      if (mRefCount >= 0) {
        mRefCount--;
        if (mRefCount < 0) {
          try {
            mObject.close();
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            mObject = null;
          }
        }
      }
    }
  }

  /**
   * Cleanup the given {@link OutputStream}.
   *
   * @param outputStream the stream to close.
   */
  private static void closeOutput(OutputStream outputStream) {
    if (null != outputStream) {
      try {
        outputStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Return true if the given array contains the given integer.
   *
   * @param modes array to check.
   * @param mode  integer to get for.
   * @return true if the array contains the given integer, otherwise false.
   */
  private static boolean contains(int[] modes, int mode) {
    if (modes == null) {
      return false;
    }
    for (int i : modes) {
      if (i == mode) {
        return true;
      }
    }
    return false;
  }

  class ResultWrapper implements Result {


    private Result result;

    public ResultWrapper(Result result) {
      this.result = result;
    }

    @Override
    public void success(Object o) {
      Log.d("ResultWrapper", "Success called!");
      result.success(o);
    }

    @Override
    public void error(String s, String s1, Object o) {
      Log.d("ResultWrapper", "Error called!");
      result.error(s, s1, o);
    }

    @Override
    public void notImplemented() {
      Log.d("ResultWrapper", "NotImplemented called!");
      result.notImplemented();
    }
  }
}
