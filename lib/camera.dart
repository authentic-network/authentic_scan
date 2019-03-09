// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/material.dart';

part 'camera_image.dart';

final MethodChannel _channel = const MethodChannel('authentic.network/camera');

enum CameraLensDirection { front, back, external }

enum ResolutionPreset { low, medium, high }

typedef onLatestImageAvailable = Function(CameraImage image);
typedef onLatestLowLightWarningAvailable = Function(LowLightWarningState state);

/// Returns the resolution preset as a String.
String serializeResolutionPreset(ResolutionPreset resolutionPreset) {
  switch (resolutionPreset) {
    case ResolutionPreset.high:
      return 'high';
    case ResolutionPreset.medium:
      return 'medium';
    case ResolutionPreset.low:
      return 'low';
  }
  throw ArgumentError('Unknown ResolutionPreset value');
}

CameraLensDirection _parseCameraLensDirection(String string) {
  switch (string) {
    case 'front':
      return CameraLensDirection.front;
    case 'back':
      return CameraLensDirection.back;
    case 'external':
      return CameraLensDirection.external;
  }
  throw ArgumentError('Unknown CameraLensDirection value');
}

/// Completes with a list of available cameras.
///
/// May throw a [CameraException].
Future<List<CameraDescription>> availableCameras() async {
  try {
    final List<dynamic> cameras =
        // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
        // https://github.com/flutter/flutter/issues/26431
        // ignore: strong_mode_implicit_dynamic_method
        await _channel.invokeMethod('availableCameras');
    return cameras.map((dynamic camera) {
      return CameraDescription(
        name: camera['name'],
        lensDirection: _parseCameraLensDirection(camera['lensFacing']),
      );
    }).toList();
  } on PlatformException catch (e) {
    throw CameraException(e.code, e.message);
  }
}

/// Completes with a list of available cameras.
///
/// May throw a [CameraException].
Future<String> retriveCameraOptions(String cameraId) async {
  try {
    final String response =
        await _channel.invokeMethod('retriveCameraOptions', <String, dynamic>{
      'cameraId': cameraId,
    });
    print(response);
    return response;
  } on PlatformException catch (e) {
    throw CameraException(e.code, e.message);
  }
}

class CameraDescription {
  CameraDescription({this.name, this.lensDirection});

  final String name;
  final CameraLensDirection lensDirection;

  @override
  bool operator ==(Object o) {
    return o is CameraDescription &&
        o.name == name &&
        o.lensDirection == lensDirection;
  }

  @override
  int get hashCode {
    return hashValues(name, lensDirection);
  }

  @override
  String toString() {
    return '$runtimeType($name, $lensDirection)';
  }
}

/// This is thrown when the plugin reports an error.
class CameraException implements Exception {
  CameraException(this.code, this.description);

  String code;
  String description;

  @override
  String toString() => '$runtimeType($code, $description)';
}

// Build the UI texture view of the video data with textureId.
class CameraPreview extends StatelessWidget {
  const CameraPreview(this.controller);

  final CameraController controller;

  @override
  Widget build(BuildContext context) {
    return controller.value.isInitialized
        ? Texture(textureId: controller._textureId)
        : Container();
  }
}

// Build the UI texture view of the video data with textureId.
class CameraLowLightWarning extends StatefulWidget {
  final CameraController controller;
  const CameraLowLightWarning(this.controller);

  @override
  _CameraLowLightWarningState createState() => _CameraLowLightWarningState();
}

class _CameraLowLightWarningState extends State<CameraLowLightWarning> {
  @override
  void initState() {
    super.initState();
    this.widget.controller.startLowLightWarningStream((state) {
      if (mounted) {
        setState(() {});
      }
    });
  }

  @override
  void dispose() {
    this.widget.controller.startLowLightWarningStream((data) {});
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    Color color;

    switch (this.widget.controller.value.lowLightWarningState) {
      case LowLightWarningState.bad:
        color = Colors.yellow;
        break;
      default:
        color = Colors.transparent;
    }

    return Padding(
      child: Row(mainAxisAlignment: MainAxisAlignment.end, children: <Widget>[
        Icon(Icons.warning, color: color, size: 30),
        Text("low light",
            style: TextStyle(
              color: color,
              fontSize: 15,
              fontWeight: FontWeight.w200,
              fontFamily: "Ubuntu",
            ))
      ]),
      padding: EdgeInsets.fromLTRB(0, 0, 10, 0),
    );
  }
}

/// The state of a [CameraController].
class CameraValue {
  const CameraValue({
    this.isInitialized,
    this.errorDescription,
    this.previewSize,
    this.isStreamingLowLightWarnings,
    this.isRecordingVideo,
    this.isTakingPicture,
    this.isStreamingImages,
    this.lowLightWarningState,
  });

  const CameraValue.uninitialized()
      : this(
            isInitialized: false,
            isRecordingVideo: false,
            isTakingPicture: false,
            isStreamingImages: false,
            isStreamingLowLightWarnings: false);

  /// True after [CameraController.initialize] has completed successfully.
  final bool isInitialized;

  /// True when a picture capture request has been sent but as not yet returned.
  final bool isTakingPicture;

  /// True when the camera is recording (not the same as previewing).
  final bool isRecordingVideo;

  /// True when images from the camera are being streamed.
  final bool isStreamingImages;

  /// True when lowLightWarnings from the camera are being streamed.
  final bool isStreamingLowLightWarnings;

  final LowLightWarningState lowLightWarningState;

  final String errorDescription;

  /// The size of the preview in pixels.
  ///
  /// Is `null` until  [isInitialized] is `true`.
  final Size previewSize;

  /// Convenience getter for `previewSize.height / previewSize.width`.
  ///
  /// Can only be called when [initialize] is done.
  double get aspectRatio => previewSize.height / previewSize.width;

  bool get hasError => errorDescription != null;

  CameraValue copyWith(
      {bool isInitialized,
      bool isRecordingVideo,
      bool isTakingPicture,
      bool isStreamingImages,
      bool isStreamingLowLightWarnings,
      String errorDescription,
      Size previewSize,
      LowLightWarningState lowLightWarningState}) {
    return CameraValue(
      isInitialized: isInitialized ?? this.isInitialized,
      errorDescription: errorDescription,
      previewSize: previewSize ?? this.previewSize,
      lowLightWarningState: lowLightWarningState ?? this.lowLightWarningState,
      isRecordingVideo: isRecordingVideo ?? this.isRecordingVideo,
      isTakingPicture: isTakingPicture ?? this.isTakingPicture,
      isStreamingImages: isStreamingImages ?? this.isStreamingImages,
      isStreamingLowLightWarnings:
          isStreamingLowLightWarnings ?? this.isStreamingLowLightWarnings,
    );
  }

  @override
  String toString() {
    return '$runtimeType('
        'isRecordingVideo: $isRecordingVideo, '
        'isRecordingVideo: $isRecordingVideo, '
        'isInitialized: $isInitialized, '
        'errorDescription: $errorDescription, '
        'previewSize: $previewSize, '
        'isStreamingImages: $isStreamingImages)';
  }
}

/// Controls a device camera.
///
/// Use [availableCameras] to get a list of available cameras.
///
/// Before using a [CameraController] a call to [initialize] must complete.
///
/// To show the camera preview on the screen use a [CameraPreview] widget.
class CameraController extends ValueNotifier<CameraValue> {
  CameraController(this.description, this.resolutionPreset)
      : super(const CameraValue.uninitialized());

  final CameraDescription description;
  final ResolutionPreset resolutionPreset;

  int _textureId;
  bool _isDisposed = false;
  StreamSubscription<dynamic> _eventSubscription;
  StreamSubscription<dynamic> _imageStreamSubscription;
  StreamSubscription<dynamic> _lowLightWarningsStreamSubscription;
  Completer<void> _creatingCompleter;

  /// Initializes the camera on the device.
  ///
  /// Throws a [CameraException] if the initialization fails.
  Future<void> initialize() async {
    if (_isDisposed) {
      return Future<void>.value();
    }
    try {
      _creatingCompleter = Completer<void>();
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      final Map<dynamic, dynamic> reply = await _channel.invokeMethod(
        'initialize',
        <String, dynamic>{
          'cameraName': description.name,
          'resolutionPreset': serializeResolutionPreset(resolutionPreset),
        },
      );
      _textureId = reply['textureId'];
      value = value.copyWith(
        isInitialized: true,
        previewSize: Size(
          reply['previewWidth'].toDouble(),
          reply['previewHeight'].toDouble(),
        ),
      );
    } on PlatformException catch (e) {
      throw CameraException(e.code, e.message);
    }
    _eventSubscription =
        EventChannel('authentic.network/camera/cameraEvents$_textureId')
            .receiveBroadcastStream()
            .listen(_listener);
    _creatingCompleter.complete();
    return _creatingCompleter.future;
  }

  /// Listen to events from the native plugins.
  ///
  /// A "cameraClosing" event is sent when the camera is closed automatically by the system (for example when the app go to background). The plugin will try to reopen the camera automatically but any ongoing recording will end.
  void _listener(dynamic event) {
    final Map<dynamic, dynamic> map = event;
    if (_isDisposed) {
      return;
    }

    switch (map['eventType']) {
      case 'error':
        value = value.copyWith(errorDescription: event['errorDescription']);
        break;
      case 'cameraClosing':
        value = value.copyWith(isRecordingVideo: false);
        break;
    }
  }

  /// Captures an image and saves it to [path].
  ///
  /// A path can for example be obtained using
  /// [path_provider](https://pub.dartlang.org/packages/path_provider).
  ///
  /// If a file already exists at the provided path an error will be thrown.
  /// The file can be read as this function returns.
  ///
  /// Throws a [CameraException] if the capture fails.
  Future<String> capteringPicture(
      String rawPath, String jpgPath, String cameraId, String type) async {
    if (!value.isInitialized || _isDisposed) {
      throw CameraException(
        'Uninitialized CameraController.' +
            value.isInitialized.toString() +
            _isDisposed.toString(),
        'capteringPicture was called on uninitialized CameraController',
      );
    }
    if (value.isTakingPicture) {
      throw CameraException(
        'Previous capture has not returned yet.',
        'capteringPicture was called before the previous capture returned.',
      );
    }
    try {
      value = value.copyWith(isTakingPicture: true);
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      String result = await _channel.invokeMethod(
        'capteringPicture',
        <String, dynamic>{
          'textureId': _textureId,
          'rawPath': rawPath,
          'jpgPath': jpgPath,
          'cameraId': cameraId,
          'type': type
        },
      );
      value = value.copyWith(isTakingPicture: false);
      return result;
    } on PlatformException catch (e) {
      value = value.copyWith(isTakingPicture: false);
      throw CameraException(e.code, e.message);
    }
  }

  /// Start streaming images from platform camera.
  ///
  /// Settings for capturing images on iOS and Android is set to always use the
  /// latest image available from the camera and will drop all other images.
  ///
  /// When running continuously with [CameraPreview] widget, this function runs
  /// best with [ResolutionPreset.low]. Running on [ResolutionPreset.high] can
  /// have significant frame rate drops for [CameraPreview] on lower end
  /// devices.
  ///
  /// Throws a [CameraException] if image streaming or video recording has
  /// already started.
  // TODO(bmparr): Add settings for resolution and fps.
  Future<void> startImageStream(onLatestImageAvailable onAvailable) async {
    if (!value.isInitialized || _isDisposed) {
      throw CameraException(
        'Uninitialized CameraController',
        'startImageStream was called on uninitialized CameraController.',
      );
    }
    if (value.isRecordingVideo) {
      throw CameraException(
        'A video recording is already started.',
        'startImageStream was called while a video is being recorded.',
      );
    }
    if (value.isStreamingImages) {
      throw CameraException(
        'A camera has started streaming images.',
        'startImageStream was called while a camera was streaming images.',
      );
    }

    try {
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      await _channel.invokeMethod('startImageStream');
      value = value.copyWith(isStreamingImages: true);
    } on PlatformException catch (e) {
      throw CameraException(e.code, e.message);
    }
    const EventChannel cameraEventChannel =
        EventChannel('authentic.network/camera/imageStream');
    _imageStreamSubscription =
        cameraEventChannel.receiveBroadcastStream().listen(
      (dynamic imageData) {
        onAvailable(CameraImage._fromPlatformData(imageData));
      },
    );
  }

  /// Stop streaming images from platform camera.
  ///
  /// Throws a [CameraException] if image streaming was not started or video
  /// recording was started.
  Future<void> stopImageStream() async {
    if (!value.isInitialized || _isDisposed) {
      throw CameraException(
        'Uninitialized CameraController',
        'stopImageStream was called on uninitialized CameraController.',
      );
    }
    if (value.isRecordingVideo) {
      throw CameraException(
        'A video recording is already started.',
        'stopImageStream was called while a video is being recorded.',
      );
    }
    if (!value.isStreamingImages) {
      throw CameraException(
        'No camera is streaming images',
        'stopImageStream was called when no camera is streaming images.',
      );
    }

    try {
      value = value.copyWith(isStreamingImages: false);
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      await _channel.invokeMethod('stopImageStream');
    } on PlatformException catch (e) {
      throw CameraException(e.code, e.message);
    }

    _imageStreamSubscription.cancel();
    _imageStreamSubscription = null;
  }

  /// Start streaming images from platform camera.
  ///
  /// Settings for capturing images on iOS and Android is set to always use the
  /// latest image available from the camera and will drop all other images.
  ///
  /// When running continuously with [CameraPreview] widget, this function runs
  /// best with [ResolutionPreset.low]. Running on [ResolutionPreset.high] can
  /// have significant frame rate drops for [CameraPreview] on lower end
  /// devices.
  ///
  /// Throws a [CameraException] if image streaming or video recording has
  /// already started.
  // TODO(bmparr): Add settings for resolution and fps.
  Future<void> startLowLightWarningStream(
      onLatestLowLightWarningAvailable onAvailable) async {
    print("startLowLightWarningStream");

    if (!value.isInitialized || _isDisposed) {
      throw CameraException(
        'Uninitialized CameraController',
        'startImageStream was called on uninitialized CameraController.',
      );
    }
    if (value.isStreamingLowLightWarnings) {
      throw CameraException(
        'A camera has started streaming lowLightWarnings.',
        'startLowLightWarningStream was called while a camera was streaming lowLightWarnings.',
      );
    }

    try {
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      await _channel.invokeMethod('startLowLightWarningStream');
      value = value.copyWith(isStreamingImages: true);
    } on PlatformException catch (e) {
      throw CameraException(e.code, e.message);
    }
    const EventChannel cameraEventChannel =
        EventChannel('authentic.network/camera/lowLightWarningStream');
    _lowLightWarningsStreamSubscription =
        cameraEventChannel.receiveBroadcastStream().listen(
      (dynamic lowLightWarningData) {
        LowLightWarningState state =
            CameraState._fromPlatformData(lowLightWarningData).state;
        value = value.copyWith(lowLightWarningState: state);
        onAvailable(state);
      },
    );
  }

  /// Stop streaming images from platform camera.
  ///
  /// Throws a [CameraException] if image streaming was not started or video
  /// recording was started.
  Future<void> stopLowLightWarningStream() async {
    print("stopLowLightWarningStream");

    if (!value.isInitialized || _isDisposed) {
      throw CameraException(
        'Uninitialized CameraController',
        'stopImageStream was called on uninitialized CameraController.',
      );
    }
    if (!value.isStreamingLowLightWarnings) {
      throw CameraException(
        'No camera is streaming lowLightWarnings',
        'stopLowLightWarningStream was called when no camera is streaming lowLightWarnings.',
      );
    }

    try {
      value = value.copyWith(isStreamingLowLightWarnings: false);
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      await _channel.invokeMethod('stopLowLightWarningStream');
    } on PlatformException catch (e) {
      throw CameraException(e.code, e.message);
    }

    _lowLightWarningsStreamSubscription.cancel();
    _lowLightWarningsStreamSubscription = null;
  }

  /// Releases the resources of this camera.
  @override
  Future<void> dispose() async {
    if (_isDisposed) {
      return;
    }
    _isDisposed = true;
    super.dispose();
    if (_creatingCompleter != null) {
      await _creatingCompleter.future;
      // TODO(amirh): remove this on when the invokeMethod update makes it to stable Flutter.
      // https://github.com/flutter/flutter/issues/26431
      // ignore: strong_mode_implicit_dynamic_method
      await _channel.invokeMethod(
        'dispose',
        <String, dynamic>{'textureId': _textureId},
      );
      await _eventSubscription?.cancel();
    }
  }
}
