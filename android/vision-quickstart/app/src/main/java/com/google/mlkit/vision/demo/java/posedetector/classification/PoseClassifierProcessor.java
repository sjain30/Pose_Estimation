/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.java.posedetector.classification;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.google.common.base.Preconditions;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accepts a stream of {@link Pose} for classification and Rep counting.
 */
public class PoseClassifierProcessor {
    private static final String TAG = "PoseClassifierProcessor";
    private static final String POSE_SAMPLES_FILE = "pose/fitness_pose_samples.csv";

    // Specify classes for which we want rep counting.
    // These are the labels in the given {@code POSE_SAMPLES_FILE}. You can set your own class labels
    // for your pose samples.
    private static final String PUSHUPS_CLASS = "pushups_down";
    private static final String SQUATS_CLASS = "squats_down";
    private static final String[] POSE_CLASSES = {
            PUSHUPS_CLASS, SQUATS_CLASS
    };

    private final boolean isStreamMode;

    private EMASmoothing emaSmoothing;
    private List<RepetitionCounter> repCounters;
    private PoseClassifier poseClassifier;
    private String lastRepResult;
    private int jacks = 0;

    @WorkerThread
    public PoseClassifierProcessor(Context context, boolean isStreamMode) {
        Preconditions.checkState(Looper.myLooper() != Looper.getMainLooper());
        this.isStreamMode = isStreamMode;
        if (isStreamMode) {
            emaSmoothing = new EMASmoothing();
            repCounters = new ArrayList<>();
            lastRepResult = "";
        }
        loadPoseSamples(context);
    }

    private void loadPoseSamples(Context context) {
        List<PoseSample> poseSamples = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(POSE_SAMPLES_FILE)));
            String csvLine = reader.readLine();
            while (csvLine != null) {
                // If line is not a valid {@link PoseSample}, we'll get null and skip adding to the list.
                PoseSample poseSample = PoseSample.getPoseSample(csvLine, ",");
                if (poseSample != null) {
                    poseSamples.add(poseSample);
                }
                csvLine = reader.readLine();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when loading pose samples.\n" + e);
        }
        poseClassifier = new PoseClassifier(poseSamples);
        if (isStreamMode) {
            for (String className : POSE_CLASSES) {
                repCounters.add(new RepetitionCounter(className));
            }
        }
    }

    private Double getAngle(PoseLandmark first, PoseLandmark second, PoseLandmark third) {
        double res = Math.atan2(third.getPosition().y - second.getPosition().y, third.getPosition().x - second.getPosition().x) -
                Math.atan2(first.getPosition().y - second.getPosition().y, first.getPosition().x - second.getPosition().x);

        double result = Math.toDegrees(res);
        result = Math.abs(result);
        if (result > 180)
            result = 360.0 - result;
        return result;
    }

    /**
     * Given a new {@link Pose} input, returns a list of formatted {@link String}s with Pose
     * classification results.
     *
     * <p>Currently it returns up to 2 strings as following:
     * 0: PoseClass : X reps
     * 1: PoseClass : [0.0-1.0] confidence
     */
    @WorkerThread
    public List<String> getPoseResult(Pose pose) {
        Preconditions.checkState(Looper.myLooper() != Looper.getMainLooper());
        List<String> result = new ArrayList<>();
        ClassificationResult classification = poseClassifier.classify(pose);

        // Update {@link RepetitionCounter}s if {@code isStreamMode}.
        if (isStreamMode) {
            // Feed pose to smoothing even if no pose found.
            classification = emaSmoothing.getSmoothedResult(classification);

            // Return early without updating repCounter if no pose found.
            if (pose.getAllPoseLandmarks().isEmpty()) {
                result.add(lastRepResult);
                return result;
            }

            for (RepetitionCounter repCounter : repCounters) {
                int repsBefore = repCounter.getNumRepeats();
                int repsAfter = repCounter.addClassificationResult(classification);
                if (repsAfter > repsBefore) {
                    // Play a fun beep when rep counter updates.
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                    lastRepResult = String.format(
                            Locale.US, "%s : %d reps", repCounter.getClassName(), repsAfter);
                    break;
                }
            }
            result.add(lastRepResult);
        }

        // Add maxConfidence class of current frame to result if pose is found.
        if (!pose.getAllPoseLandmarks().isEmpty()) {

            String maxConfidenceClass = classification.getMaxConfidenceClass();
            PoseLandmark leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE);
            PoseLandmark leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
            PoseLandmark leftHeel = pose.getPoseLandmark(PoseLandmark.LEFT_HEEL);
            PoseLandmark rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE);
            PoseLandmark rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);
            PoseLandmark rightHeel = pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL);
            PoseLandmark leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
            PoseLandmark leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW);
            PoseLandmark leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST);
            PoseLandmark rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
            PoseLandmark rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW);
            PoseLandmark rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST);

            if (rightHip != null && rightKnee != null && rightHeel != null && leftKnee != null && leftHeel != null && leftHip != null)
                if (leftElbow != null && leftShoulder != null && rightElbow != null && rightShoulder != null)
                    if (getAngle(leftElbow, leftShoulder, leftHip) > 90 && getAngle(rightElbow, rightShoulder, rightHip) > 90)
                        if (getAngle(leftHip, leftKnee, leftHeel) < 90 && getAngle(rightHip, rightKnee, rightHeel) > 90)
                            maxConfidenceClass = "Vrikshasana";
                        else if (getAngle(leftHip, leftKnee, leftHeel) > 90 && getAngle(rightHip, rightKnee, rightHeel) < 90)
                            maxConfidenceClass = "Vrikshasana";

            if (leftKnee != null && leftHeel != null && leftHip != null && maxConfidenceClass.equals(PUSHUPS_CLASS) && getAngle(leftHip, leftKnee, leftHeel) < 110)
                maxConfidenceClass = "Knee Push Up";
            else if (rightKnee != null && rightHeel != null && rightHip != null && maxConfidenceClass.equals(PUSHUPS_CLASS) && getAngle(rightHip, rightKnee, rightHeel) < 110)
                maxConfidenceClass = "Knee Push Up";

            if (leftElbow != null && leftWrist != null && leftShoulder != null && rightElbow != null && rightWrist != null && rightShoulder != null)
                if (maxConfidenceClass.equals(PUSHUPS_CLASS) && (getAngle(leftShoulder, leftElbow, leftWrist) < 95 || getAngle(rightShoulder, rightElbow, rightWrist) < 95))
                    maxConfidenceClass = "Planks";
                else if (getAngle(leftShoulder, leftElbow, leftWrist) < 30)
                    maxConfidenceClass = "Bicep Curls";
                else if (getAngle(rightShoulder, rightElbow, rightWrist) < 30)
                    maxConfidenceClass = "Bicep Curls";


            if (rightHeel != null && leftHeel != null && leftHip != null && maxConfidenceClass.equals("squats_up") && leftElbow != null && leftShoulder != null && rightElbow != null && rightHip != null && rightShoulder != null) {
                if (getAngle(leftElbow, leftShoulder, leftHip) > 90 && getAngle(rightElbow, rightShoulder, rightHip) > 90 && getAngle(leftHeel, leftHip, rightHeel) > 10)
                    maxConfidenceClass = "Jumping Jacks";
            }

            if (leftShoulder != null && leftHip != null && leftKnee != null && rightShoulder != null && rightHip != null && rightKnee != null)
                if (getAngle(leftShoulder, leftHip, leftKnee) < 95 && getAngle(rightShoulder, rightHip, rightKnee) < 95)
                    maxConfidenceClass = "Leg Raise";


            if (maxConfidenceClass.equals("squats_up"))
                maxConfidenceClass = "Standing";
            else if (maxConfidenceClass.equals(SQUATS_CLASS))
                maxConfidenceClass = "Squats Down";
            else if (maxConfidenceClass.equals(PUSHUPS_CLASS))
                maxConfidenceClass = "Pushup Down";
            else if (maxConfidenceClass.equals("pushups_up"))
                maxConfidenceClass = "Pushup";

            String maxConfidenceClassResult = String.format(
                    Locale.US,
                    "%s",
                    maxConfidenceClass);
            result.add(maxConfidenceClassResult);
        }

        return result;
    }

}
