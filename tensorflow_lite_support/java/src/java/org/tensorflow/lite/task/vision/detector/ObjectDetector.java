/* Copyright 2020 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package org.tensorflow.lite.task.vision.detector;

import android.content.Context;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.annotations.UsedByReflection;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.core.BaseTaskApi;
import org.tensorflow.lite.task.core.TaskJniUtils;
import org.tensorflow.lite.task.core.TaskJniUtils.FdAndOptionsHandleProvider;

/**
 * Performs object detection on images.
 *
 * <p>The API expects a TFLite model with <a
 * href="https://www.tensorflow.org/lite/convert/metadata">TFLite Model Metadata.</a>.
 *
 * <p>The API supports models with one image input tensor and four output tensors. To be more
 * specific, here are the requirements.
 *
 * <ul>
 *   <li>Input image tensor ({@code kTfLiteUInt8}/{@code kTfLiteFloat32})
 *       <ul>
 *         <li>image input of size {@code [batch x height x width x channels]}.
 *         <li>batch inference is not supported ({@code batch} is required to be 1).
 *         <li>only RGB inputs are supported ({@code channels} is required to be 3).
 *         <li>if type is {@code kTfLiteFloat32}, NormalizationOptions are required to be attached
 *             to the metadata for input normalization.
 *       </ul>
 *   <li>Output tensors must be the 4 outputs of a {@code DetectionPostProcess} op, i.e:
 *       <ul>
 *         <li>Location tensor ({@code kTfLiteFloat32}):
 *             <ul>
 *               <li>tensor of size {@code [1 x num_results x 4]}, the inner array representing
 *                   bounding boxes in the form [top, left, right, bottom].
 *               <li>{@code BoundingBoxProperties} are required to be attached to the metadata and
 *                   must specify {@code type=BOUNDARIES} and {@code coordinate_type=RATIO}.
 *             </ul>
 *         <li>Classes tensor ({@code kTfLiteFloat32}):
 *             <ul>
 *               <li>tensor of size {@code [1 x num_results]}, each value representing the integer
 *                   index of a class.
 *               <li>if label maps are attached to the metadata as {@code TENSOR_VALUE_LABELS}
 *                   associated files, they are used to convert the tensor values into labels.
 *             </ul>
 *         <li>scores tensor ({@code kTfLiteFloat32}):
 *             <ul>
 *               <li>tensor of size {@code [1 x num_results]}, each value representing the score of
 *                   the detected object.
 *             </ul>
 *         <li>Number of detection tensor ({@code kTfLiteFloat32}):
 *             <ul>
 *               <li>integer num_results as a tensor of size {@code [1]}.
 *             </ul>
 *       </ul>
 * </ul>
 *
 * <p>An example of such model can be found at<a
 * href="https://tfhub.dev/google/lite-model/object_detection/mobile_object_localizer_v1/1/metadata/1">TensorFlow
 * Hub.</a>.
 */
public final class ObjectDetector extends BaseTaskApi {

  private static final String OBJECT_DETECTOR_NATIVE_LIB = "object_detector_jni";

  /**
   * Creates an {@link ObjectDetector} instance from the default {@link ObjectDetectorOptions}.
   *
   * @param modelPath path to the detection model with metadata in the assets
   * @throws IOException if an I/O error occurs when loading the tflite model
   * @throws AssertionError if error occurs when creating {@link ObjectDetector} from the native
   *     code
   */
  public static ObjectDetector createFromFile(Context context, String modelPath)
      throws IOException {
    return createFromFileAndOptions(context, modelPath, ObjectDetectorOptions.builder().build());
  }

  /**
   * Creates an {@link ObjectDetector} instance from {@link ObjectDetectorOptions}.
   *
   * @param modelPath path to the detection model with metadata in the assets
   * @throws IOException if an I/O error occurs when loading the tflite model
   * @throws AssertionError if error occurs when creating {@link ObjectDetector} from the native
   *     code
   */
  public static ObjectDetector createFromFileAndOptions(
      Context context, String modelPath, ObjectDetectorOptions options) throws IOException {
    return new ObjectDetector(
        TaskJniUtils.createHandleFromFdAndOptions(
            context,
            new FdAndOptionsHandleProvider<ObjectDetectorOptions>() {
              @Override
              public long createHandle(
                  int fileDescriptor,
                  long fileDescriptorLength,
                  long fileDescriptorOffset,
                  ObjectDetectorOptions options) {
                return initJniWithModelFdAndOptions(
                    fileDescriptor, fileDescriptorLength, fileDescriptorOffset, options);
              }
            },
            OBJECT_DETECTOR_NATIVE_LIB,
            modelPath,
            options));
  }

  /**
   * Constructor to initialize the JNI with a pointer from C++.
   *
   * @param nativeHandle a pointer referencing memory allocated in C++
   */
  private ObjectDetector(long nativeHandle) {
    super(nativeHandle);
  }

  /** Options for setting up an ObjectDetector. */
  @UsedByReflection("object_detector_jni.cc")
  public static class ObjectDetectorOptions {
    // Not using AutoValue for this class because scoreThreshold cannot have default value
    // (otherwise, the default value would override the one in the model metadata) and `Optional` is
    // not an option here, because
    // 1. java.util.Optional require Java 8 while we need to support Java 7.
    // 2. The Guava library (com.google.common.base.Optional) is avoided in this project. See the
    // comments for classNameAllowList.
    private final String displayNamesLocale;
    private final int maxResults;
    private final float scoreThreshold;
    private final boolean isScoreThresholdSet;
    // As an open source project, we've been trying avoiding depending on common java libraries,
    // such as Guava, because it may introduce conflicts with clients who also happen to use those
    // libraries. Therefore, instead of using ImmutableList here, we convert the List into
    // unmodifiableList in setClassNameAllowList() and setClassNameDenyList() to make it less
    // vulnerable.
    private final List<String> classNameAllowList;
    private final List<String> classNameDenyList;

    public static Builder builder() {
      return new Builder();
    }

    /** A builder that helps to configure an instance of ObjectDetectorOptions. */
    public static class Builder {
      private String displayNamesLocale = "en";
      private int maxResults = -1;
      private float scoreThreshold;
      private boolean isScoreThresholdSet = false;
      private List<String> classNameAllowList = new ArrayList<>();
      private List<String> classNameDenyList = new ArrayList<>();

      private Builder() {}

      /**
       * Sets the locale to use for display names specified through the TFLite Model Metadata, if
       * any.
       *
       * <p>Defaults to English({@code "en"}). See the <a
       * href="https://github.com/tensorflow/tflite-support/blob/3ce83f0cfe2c68fecf83e019f2acc354aaba471f/tensorflow_lite_support/metadata/metadata_schema.fbs#L147">TFLite
       * Metadata schema file.</a> for the accepted pattern of locale.
       */
      public Builder setDisplayNamesLocale(String displayNamesLocale) {
        this.displayNamesLocale = displayNamesLocale;
        return this;
      }

      /**
       * Sets the maximum number of top-scored detection results to return.
       *
       * <p>If < 0, all available results will be returned. If 0, an invalid argument error is
       * returned. Note that models may intrinsically be limited to returning a maximum number of
       * results N: if the provided value here is above N, only N results will be returned. Defaults
       * to -1.
       *
       * @throws IllegalArgumentException if maxResults is 0.
       */
      public Builder setMaxResults(int maxResults) {
        if (maxResults == 0) {
          throw new IllegalArgumentException("maxResults cannot be 0.");
        }
        this.maxResults = maxResults;
        return this;
      }

      /**
       * Sets the score threshold that overrides the one provided in the model metadata (if any).
       * Results below this value are rejected.
       */
      public Builder setScoreThreshold(float scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
        this.isScoreThresholdSet = true;
        return this;
      }

      /**
       * Sets the optional allow list of class names.
       *
       * <p>If non-empty, detection results whose class name is not in this set will be filtered
       * out. Duplicate or unknown class names are ignored. Mutually exclusive with {@code
       * classNameDenyList}. It will cause {@link AssertionError} when calling {@link
       * #createFromFileAndOptions}, if both {@code classNameDenyList} and {@code
       * classNameAllowList} are set.
       */
      public Builder setClassNameAllowList(List<String> classNameAllowList) {
        this.classNameAllowList = Collections.unmodifiableList(new ArrayList<>(classNameAllowList));
        return this;
      }

      /**
       * Sets the optional deny list of class names.
       *
       * <p>If non-empty, detection results whose class name is in this set will be filtered out.
       * Duplicate or unknown class names are ignored. Mutually exclusive with {@code
       * classNameAllowList}. It will cause {@link AssertionError} when calling {@link
       * #createFromFileAndOptions}, if both {@code classNameDenyList} and {@code
       * classNameAllowList} are set.
       */
      public Builder setClassNameDenyList(List<String> classNameDenyList) {
        this.classNameDenyList = Collections.unmodifiableList(new ArrayList<>(classNameDenyList));
        return this;
      }

      public ObjectDetectorOptions build() {
        return new ObjectDetectorOptions(this);
      }
    }

    @UsedByReflection("object_detector_jni.cc")
    public String getDisplayNamesLocale() {
      return displayNamesLocale;
    }

    @UsedByReflection("object_detector_jni.cc")
    public int getMaxResults() {
      return maxResults;
    }

    @UsedByReflection("object_detector_jni.cc")
    public float getScoreThreshold() {
      return scoreThreshold;
    }

    @UsedByReflection("object_detector_jni.cc")
    public boolean getIsScoreThresholdSet() {
      return isScoreThresholdSet;
    }

    @UsedByReflection("object_detector_jni.cc")
    public List<String> getClassNameAllowList() {
      return new ArrayList<>(classNameAllowList);
    }

    @UsedByReflection("object_detector_jni.cc")
    public List<String> getClassNameDenyList() {
      return new ArrayList<>(classNameDenyList);
    }

    private ObjectDetectorOptions(Builder builder) {
      displayNamesLocale = builder.displayNamesLocale;
      maxResults = builder.maxResults;
      scoreThreshold = builder.scoreThreshold;
      isScoreThresholdSet = builder.isScoreThresholdSet;
      classNameAllowList = builder.classNameAllowList;
      classNameDenyList = builder.classNameDenyList;
    }
  }

  /**
   * Performs actual detection on the provided image.
   *
   * @param image a {@link TensorImage} object that represents a RGB image
   * @throws AssertionError if error occurs when processing the image from the native code
   */
  public List<Detection> detect(TensorImage image) {
    checkNotClosed();

    // object_detector_jni.cc expects an uint8 image. Convert image of other types into uint8.
    TensorImage imageUint8 =
        image.getDataType() == DataType.UINT8
            ? image
            : TensorImage.createFrom(image, DataType.UINT8);
    return detectNative(
        getNativeHandle(), imageUint8.getBuffer(), imageUint8.getWidth(), imageUint8.getHeight());
  }

  private static native long initJniWithModelFdAndOptions(
      int fileDescriptor,
      long fileDescriptorLength,
      long fileDescriptorOffset,
      ObjectDetectorOptions options);

  private static native List<Detection> detectNative(
      long nativeHandle, ByteBuffer image, int width, int height);
}