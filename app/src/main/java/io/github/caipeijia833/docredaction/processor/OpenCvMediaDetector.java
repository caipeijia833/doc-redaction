/*
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.caipeijia833.docredaction.processor;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.QRCodeDetector;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** CPU-only visual detectors used by the sampled-frame media pipeline. */
final class OpenCvMediaDetector implements AutoCloseable {
    private static final int PLATE_INPUT_WIDTH = 320;
    private static final int PLATE_INPUT_HEIGHT = 240;
    private static final int[][] PLATE_MIN_SIZES = {
            {10, 16, 24}, {32, 48}, {64, 96}, {128, 192, 256}
    };
    private static final int[] PLATE_STEPS = {8, 16, 32, 64};

    private final FaceDetectorYN faceDetector;
    private final Net plateNet;
    private final QRCodeDetector qrDetector;
    private final List<Prior> platePriors;

    OpenCvMediaDetector(Path faceModel, Path plateModel) throws IOException {
        try {
            OpenCvRuntime.loadAndVersion();
            float faceThreshold = (float) boundedDoubleProperty(
                    "docredaction.media.faceThreshold", 0.70d, 0.40d, 0.95d);
            this.faceDetector = FaceDetectorYN.create(faceModel.toString(), "",
                    new Size(320, 320), faceThreshold, 0.3f, 5_000);
            this.plateNet = Dnn.readNetFromONNX(plateModel.toString());
            if (plateNet.empty()) {
                throw new IOException("无法加载本地车牌检测模型");
            }
            this.qrDetector = new QRCodeDetector();
            this.platePriors = createPlatePriors();
        } catch (UnsatisfiedLinkError | RuntimeException ex) {
            throw new IOException("无法初始化本地OpenCV视觉检测组件", ex);
        }
    }

    List<VisualBox> detect(BufferedImage image) throws IOException {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IOException("待检测视频帧无效");
        }
        Mat frame = toBgrMat(image);
        try {
            List<VisualBox> boxes = new ArrayList<>();
            boxes.addAll(detectFaces(frame));
            boxes.addAll(detectQrCodes(frame));
            boxes.addAll(detectPlates(frame));
            return List.copyOf(boxes);
        } finally {
            frame.release();
        }
    }

    private List<VisualBox> detectFaces(Mat frame) {
        faceDetector.setInputSize(frame.size());
        Mat faces = new Mat();
        try {
            faceDetector.detect(frame, faces);
            List<VisualBox> result = new ArrayList<>();
            for (int row = 0; row < faces.rows(); row++) {
                double[] values = readFloatValues(faces, row, 15);
                if (values == null || values.length < 15) {
                    continue;
                }
                VisualBox box = clipped(MediaFinding.Kind.FACE,
                        values[0], values[1], values[2], values[3], values[14], "",
                        frame.cols(), frame.rows());
                if (box != null) {
                    result.add(box);
                }
            }
            return result;
        } finally {
            faces.release();
        }
    }

    private List<VisualBox> detectQrCodes(Mat frame) throws IOException {
        Mat points = new Mat();
        try {
            boolean found = qrDetector.detectMulti(frame, points);
            if (!found || points.empty()) {
                found = qrDetector.detect(frame, points);
                VisualBox single = found
                        ? boxFromQrPoints(points, "", frame.cols(), frame.rows()) : null;
                if (found && single == null) {
                    throw new IOException("二维码坐标输出无效：total=" + points.total()
                            + ", rows=" + points.rows() + ", cols=" + points.cols()
                            + ", channels=" + points.channels());
                }
                return single == null ? List.of() : List.of(single);
            }
            List<VisualBox> result = new ArrayList<>();
            int count = Math.toIntExact(points.total() * points.channels() / 8L);
            Mat flattened = points.reshape(1, count);
            for (int index = 0; index < count; index++) {
                double[] coordinates = readFloatValues(flattened, index, 8);
                VisualBox box = boxFromCoordinates(MediaFinding.Kind.QR_CODE, coordinates,
                        0.99d, "", frame.cols(), frame.rows());
                if (box != null) {
                    result.add(box);
                }
            }
            if (result.isEmpty()) {
                throw new IOException("二维码多目标坐标输出无效：total=" + points.total()
                        + ", rows=" + points.rows() + ", cols=" + points.cols()
                        + ", channels=" + points.channels());
            }
            return result;
        } catch (RuntimeException ex) {
            throw new IOException("OpenCV二维码检测失败", ex);
        } finally {
            points.release();
        }
    }

    private static VisualBox boxFromQrPoints(Mat points, String value, int width, int height) {
        if (points.empty()) {
            return null;
        }
        Mat flattened = points.reshape(1, 1);
        double[] coordinates = readFloatValues(flattened, 0, 8);
        return boxFromCoordinates(MediaFinding.Kind.QR_CODE, coordinates,
                0.99d, value, width, height);
    }

    private static VisualBox boxFromCoordinates(MediaFinding.Kind kind, double[] coordinates,
            double confidence, String value, int frameWidth, int frameHeight) {
        if (coordinates == null || coordinates.length < 8) {
            return null;
        }
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (int index = 0; index + 1 < coordinates.length; index += 2) {
            left = Math.min(left, coordinates[index]);
            right = Math.max(right, coordinates[index]);
            top = Math.min(top, coordinates[index + 1]);
            bottom = Math.max(bottom, coordinates[index + 1]);
        }
        return clipped(kind, left, top, right - left, bottom - top, confidence,
                value, frameWidth, frameHeight);
    }

    private List<VisualBox> detectPlates(Mat frame) throws IOException {
        Mat resized = new Mat();
        Mat blob = new Mat();
        List<Mat> outputs = new ArrayList<>();
        try {
            Imgproc.resize(frame, resized, new Size(PLATE_INPUT_WIDTH, PLATE_INPUT_HEIGHT));
            blob = Dnn.blobFromImage(resized, 1.0d, new Size(PLATE_INPUT_WIDTH, PLATE_INPUT_HEIGHT),
                    new Scalar(0, 0, 0), false, false, CvType.CV_32F);
            plateNet.setInput(blob);
            plateNet.forward(outputs, List.of("loc", "conf", "iou"));
            if (outputs.size() != 3) {
                throw new IOException("车牌模型输出数量异常：" + outputs.size());
            }
            if (outputs.get(0).total() % platePriors.size() != 0
                    || outputs.get(1).total() % platePriors.size() != 0
                    || outputs.get(2).total() % platePriors.size() != 0) {
                throw new IOException("车牌模型输出形状异常：priors=" + platePriors.size()
                        + ", totals=" + outputs.stream().map(Mat::total).toList());
            }
            Mat locations = outputs.get(0).reshape(1, platePriors.size());
            Mat confidences = outputs.get(1).reshape(1, platePriors.size());
            Mat ious = outputs.get(2).reshape(1, platePriors.size());
            List<PlateCandidate> candidates = new ArrayList<>();
            for (int row = 0; row < platePriors.size(); row++) {
                double[] location = readFloatValues(locations, row, 14);
                double[] confidence = readFloatValues(confidences, row, 2);
                double[] iou = readFloatValues(ious, row, 1);
                if (location == null || location.length < 14 || confidence == null
                        || confidence.length < 2 || iou == null || iou.length < 1) {
                    continue;
                }
                double score = Math.sqrt(Math.max(0.0d, confidence[1])
                        * Math.max(0.0d, Math.min(1.0d, iou[0])));
                if (score < 0.80d) {
                    continue;
                }
                Prior prior = platePriors.get(row);
                double[] corners = new double[8];
                int[] offsets = {4, 6, 10, 12};
                for (int corner = 0; corner < 4; corner++) {
                    int offset = offsets[corner];
                    corners[corner * 2] = (prior.cx() + location[offset] * 0.1d * prior.width())
                            * PLATE_INPUT_WIDTH;
                    corners[corner * 2 + 1] = (prior.cy() + location[offset + 1] * 0.1d * prior.height())
                            * PLATE_INPUT_HEIGHT;
                }
                VisualBox box = boxFromCoordinates(MediaFinding.Kind.LICENSE_PLATE, corners,
                        score, "", PLATE_INPUT_WIDTH, PLATE_INPUT_HEIGHT);
                if (box != null) {
                    candidates.add(new PlateCandidate(box, score));
                }
            }
            List<VisualBox> kept = nonMaximumSuppression(candidates, 0.30d);
            double scaleX = frame.cols() / (double) PLATE_INPUT_WIDTH;
            double scaleY = frame.rows() / (double) PLATE_INPUT_HEIGHT;
            return kept.stream().map(box -> clipped(MediaFinding.Kind.LICENSE_PLATE,
                    box.x() * scaleX, box.y() * scaleY,
                    box.width() * scaleX, box.height() * scaleY,
                    box.confidence(), box.value(), frame.cols(), frame.rows()))
                    .filter(java.util.Objects::nonNull).toList();
        } catch (IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IOException("OpenCV车牌检测失败", ex);
        } finally {
            resized.release();
            blob.release();
            outputs.forEach(Mat::release);
        }
    }

    private static List<VisualBox> nonMaximumSuppression(List<PlateCandidate> candidates, double threshold) {
        List<PlateCandidate> pending = new ArrayList<>(candidates);
        pending.sort(Comparator.comparingDouble(PlateCandidate::score).reversed());
        List<VisualBox> kept = new ArrayList<>();
        while (!pending.isEmpty()) {
            PlateCandidate best = pending.removeFirst();
            kept.add(best.box());
            pending.removeIf(candidate -> intersectionOverUnion(best.box(), candidate.box()) > threshold);
        }
        return kept;
    }

    private static double intersectionOverUnion(VisualBox first, VisualBox second) {
        int left = Math.max(first.x(), second.x());
        int top = Math.max(first.y(), second.y());
        int right = Math.min(first.x() + first.width(), second.x() + second.width());
        int bottom = Math.min(first.y() + first.height(), second.y() + second.height());
        long intersection = Math.max(0, right - left) * (long) Math.max(0, bottom - top);
        long union = first.width() * (long) first.height()
                + second.width() * (long) second.height() - intersection;
        return union <= 0L ? 0.0d : intersection / (double) union;
    }

    private static List<Prior> createPlatePriors() {
        List<Prior> priors = new ArrayList<>();
        for (int level = 0; level < PLATE_STEPS.length; level++) {
            int step = PLATE_STEPS[level];
            int featureWidth = PLATE_INPUT_WIDTH / step;
            int featureHeight = PLATE_INPUT_HEIGHT / step;
            for (int row = 0; row < featureHeight; row++) {
                for (int column = 0; column < featureWidth; column++) {
                    for (int minimumSize : PLATE_MIN_SIZES[level]) {
                        priors.add(new Prior(
                                (column + 0.5d) * step / PLATE_INPUT_WIDTH,
                                (row + 0.5d) * step / PLATE_INPUT_HEIGHT,
                                minimumSize / (double) PLATE_INPUT_WIDTH,
                                minimumSize / (double) PLATE_INPUT_HEIGHT));
                    }
                }
            }
        }
        return List.copyOf(priors);
    }

    private static double[] readFloatValues(Mat matrix, int row, int count) {
        float[] values = new float[count];
        int read = matrix.get(row, 0, values);
        if (read < count) {
            return new double[0];
        }
        double[] result = new double[count];
        for (int index = 0; index < count; index++) {
            result[index] = values[index];
        }
        return result;
    }

    private static Mat toBgrMat(BufferedImage source) {
        BufferedImage bgr = source.getType() == BufferedImage.TYPE_3BYTE_BGR
                ? source : new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        if (bgr != source) {
            Graphics2D graphics = bgr.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
    }

    private static VisualBox clipped(MediaFinding.Kind kind, double x, double y,
            double width, double height, double confidence, String value,
            int frameWidth, int frameHeight) {
        int paddingX = Math.max(4, (int) Math.round(width * 0.08d));
        int paddingY = Math.max(4, (int) Math.round(height * 0.08d));
        int left = Math.max(0, (int) Math.floor(x) - paddingX);
        int top = Math.max(0, (int) Math.floor(y) - paddingY);
        int right = Math.min(frameWidth, (int) Math.ceil(x + width) + paddingX);
        int bottom = Math.min(frameHeight, (int) Math.ceil(y + height) + paddingY);
        if (right - left < 2 || bottom - top < 2) {
            return null;
        }
        return new VisualBox(kind, left, top, right - left, bottom - top,
                Math.max(0.0d, Math.min(1.0d, confidence)), value == null ? "" : value);
    }

    private static double boundedDoubleProperty(String name, double fallback,
            double minimum, double maximum) {
        try {
            double value = Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
            return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override
    public void close() {
        // OpenCV Java wrappers own their native handles and release them during finalization.
    }

    record VisualBox(MediaFinding.Kind kind, int x, int y, int width, int height,
            double confidence, String value) {
    }

    private record Prior(double cx, double cy, double width, double height) {
    }

    private record PlateCandidate(VisualBox box, double score) {
    }
}
