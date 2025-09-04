import cv2
import json
import numpy as np
import asyncio
from ultralytics import YOLO
from collections import defaultdict
from norfair import Tracker, Detection
import logging
from datetime import datetime
from pathlib import Path
import time

# Cấu hình logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('parking_detection.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Constants
COLORS = {
    'empty': (0, 255, 0),  # Xanh lá - trống
    'occupied': (0, 0, 255),  # Đỏ - có xe
    'car': (255, 0, 0),  # Xanh dương - xe hơi
    'motorbike': (0, 255, 255),  # Vàng - xe máy
    'bus': (255, 0, 255)  # Tím - xe bus
}

VEHICLE_CLASSES = {
    2: 'car',
    3: 'motorbike',
    5: 'bus'
}

FONT = cv2.FONT_HERSHEY_DUPLEX
FONT_SCALE = 0.7
FONT_THICKNESS = 2


class ParkingDetector:
    def __init__(self, config_path):
        self.config = self._load_config(config_path)
        self.parking_polygons = self._load_parking_areas(self.config['parking_areas_path'])
        self.yolo_model = YOLO(self.config['model_path'])
        self.yolo_model.overrides['verbose'] = False

        # Khởi tạo tracker
        self.tracker = Tracker(
            distance_function="euclidean",
            distance_threshold=100,
            hit_counter_max=10,
            initialization_delay=2
        )

        # Trạng thái các vùng đỗ xe (mặc định là trống - xanh)
        self.parking_status = defaultdict(lambda: "empty")

        # Tracking vehicles
        self.tracked_vehicles = {}

        # Smoothing
        self.detection_buffer = []
        self.buffer_size = 3
        self.confidence_threshold = 0.6
        self.smoothed_boxes = {}

    def _load_config(self, path):
        with open(path, 'r') as f:
            return json.load(f)

    def _load_parking_areas(self, path):
        with open(path, 'r') as f:
            data = json.load(f)
        return [area['points'] for area in data]

    def _get_center(self, box):
        """Lấy tâm của bounding box"""
        x1, y1, x2, y2 = box
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    def _is_inside_polygon(self, point, polygon):
        """Kiểm tra điểm có nằm trong polygon không"""
        return cv2.pointPolygonTest(np.array(polygon, dtype=np.int32), point, False) >= 0

    def _smooth_boxes(self, boxes):
        """Làm mượt bounding boxes"""
        smoothed = []

        for box in boxes:
            x1, y1, x2, y2, cls, conf = box
            box_key = f"{cls}_{x1 // 50}_{y1 // 50}"

            if box_key in self.smoothed_boxes:
                prev_box = self.smoothed_boxes[box_key]
                alpha = 0.3

                smooth_x1 = int(alpha * x1 + (1 - alpha) * prev_box[0])
                smooth_y1 = int(alpha * y1 + (1 - alpha) * prev_box[1])
                smooth_x2 = int(alpha * x2 + (1 - alpha) * prev_box[2])
                smooth_y2 = int(alpha * y2 + (1 - alpha) * prev_box[3])

                smoothed_box = [smooth_x1, smooth_y1, smooth_x2, smooth_y2, cls, conf]
                self.smoothed_boxes[box_key] = [smooth_x1, smooth_y1, smooth_x2, smooth_y2]
            else:
                smoothed_box = box
                self.smoothed_boxes[box_key] = [x1, y1, x2, y2]

            smoothed.append(smoothed_box)

        return smoothed

    def _filter_detections(self, boxes):
        """Lọc detection để giảm nháy"""
        high_conf_boxes = [box for box in boxes if len(box) > 5 and box[5] > self.confidence_threshold]

        self.detection_buffer.append(high_conf_boxes)
        if len(self.detection_buffer) > self.buffer_size:
            self.detection_buffer.pop(0)

        if len(self.detection_buffer) < 2:
            return self._smooth_boxes(high_conf_boxes)

        stable_boxes = []
        current_boxes = self.detection_buffer[-1]

        for current_box in current_boxes:
            found_similar = False
            for prev_boxes in self.detection_buffer[:-1]:
                for prev_box in prev_boxes:
                    if (current_box[4] == prev_box[4] and
                            self._boxes_overlap(current_box[:4], prev_box[:4], 0.6)):
                        found_similar = True
                        break
                if found_similar:
                    break

            if found_similar or len(self.detection_buffer) < 3:
                stable_boxes.append(current_box)

        return self._smooth_boxes(stable_boxes)

    def _boxes_overlap(self, box1, box2, threshold=0.5):
        """Kiểm tra 2 box có overlap không"""
        x1, y1, x2, y2 = box1
        x1b, y1b, x2b, y2b = box2
        dx = max(0, min(x2, x2b) - max(x1, x1b))
        dy = max(0, min(y2, y2b) - max(y1, y1b))
        overlap_area = dx * dy
        area1 = (x2 - x1) * (y2 - y1)
        area2 = (x2b - x1b) * (y2b - y1b)
        union = area1 + area2 - overlap_area
        return (overlap_area / union) > threshold if union > 0 else False

    def _update_parking_status(self, tracked_objects):
        """Cập nhật trạng thái các vùng đỗ xe dựa trên tracking"""
        # Reset tất cả về trống (xanh)
        new_status = defaultdict(lambda: "empty")

        # Kiểm tra từng tracked object
        for track_id, vehicle_info in tracked_objects.items():
            vehicle_center = self._get_center(vehicle_info['box'][:4])

            # Kiểm tra xe có trong vùng đỗ nào không
            for i, polygon in enumerate(self.parking_polygons):
                if self._is_inside_polygon(vehicle_center, polygon):
                    new_status[i] = "occupied"  # Đổi thành đỏ
                    break

        # Cập nhật trạng thái
        self.parking_status.update(new_status)

        return new_status

    def _draw_parking_areas(self, frame, status):
        """Vẽ các vùng đỗ xe với màu tương ứng"""
        for i, polygon in enumerate(self.parking_polygons):
            # Lấy màu dựa trên trạng thái
            color = COLORS[status.get(i, "empty")]

            # Vẽ polygon
            pts = np.array(polygon, np.int32)
            cv2.polylines(frame, [pts], True, color, 3)

            # Tô màu nhạt bên trong (tùy chọn)
            overlay = frame.copy()
            cv2.fillPoly(overlay, [pts], color)
            cv2.addWeighted(frame, 0.8, overlay, 0.2, 0, frame)

    def _draw_tracked_vehicles(self, frame, tracked_objects):
        """Vẽ các xe được tracking"""
        for track_id, vehicle_info in tracked_objects.items():
            x1, y1, x2, y2 = vehicle_info['box'][:4]
            cls = vehicle_info['class']
            conf = vehicle_info['confidence']

            # Màu sắc theo loại xe
            color = COLORS.get(cls, (255, 255, 255))

            # Vẽ bounding box
            cv2.rectangle(frame, (x1, y1), (x2, y2), color, 2)

            # Label loại xe
            label = f"{cls.upper()}"
            cv2.putText(frame, label, (x1, y1 - 5),
                        FONT, FONT_SCALE, (255, 255, 255), FONT_THICKNESS)

    def process_frame(self, frame):
        """Xử lý frame chính"""
        try:
            # YOLO detection
            results = self.yolo_model(frame, iou=0.5, conf=self.confidence_threshold, verbose=False)

            # Xử lý detection results
            boxes = []
            detections_for_tracker = []

            if results[0].boxes is not None:
                for box in results[0].boxes.data.tolist():
                    cls_id = int(box[5])
                    conf = box[4]

                    if cls_id in VEHICLE_CLASSES and conf > self.confidence_threshold:
                        x1, y1, x2, y2 = map(int, box[:4])
                        cls = VEHICLE_CLASSES[cls_id]
                        boxes.append([x1, y1, x2, y2, cls, conf])

                        # Tạo detection cho tracker
                        center_x = (x1 + x2) / 2
                        center_y = (y1 + y2) / 2
                        detection = Detection(
                            points=np.array([[center_x, center_y]]),
                            scores=np.array([conf]),
                            label=cls
                        )
                        detections_for_tracker.append(detection)

            # Filter detections để giảm nháy
            stable_boxes = self._filter_detections(boxes)

            # Update tracker
            tracked_objects_norfair = self.tracker.update(detections=detections_for_tracker)

            # Xử lý tracked objects
            current_tracked = {}
            for track in tracked_objects_norfair:
                if track.last_detection is not None:
                    detection = track.last_detection
                    center = detection.points[0]
                    conf = detection.scores[0] if len(detection.scores) > 0 else 0.5
                    cls = detection.label if detection.label else 'car'

                    # Ước tính bounding box từ center
                    w, h = 80, 60  # Kích thước ước tính
                    if cls == 'bus':
                        w, h = 120, 80
                    elif cls == 'motorbike':
                        w, h = 50, 40

                    box = [
                        int(center[0] - w / 2),
                        int(center[1] - h / 2),
                        int(center[0] + w / 2),
                        int(center[1] + h / 2)
                    ]

                    current_tracked[track.id] = {
                        'box': box,
                        'class': cls,
                        'confidence': conf
                    }

            # Cập nhật trạng thái vùng đỗ xe
            parking_status = self._update_parking_status(current_tracked)

            # Vẽ kết quả
            self._draw_parking_areas(frame, parking_status)
            self._draw_tracked_vehicles(frame, current_tracked)

            return frame

        except Exception as e:
            logger.error(f"Error processing frame: {e}")
            return frame

    async def run(self, video_path):
        """Chạy detector"""
        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            logger.error("Could not open video.")
            return

        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

        if self.config.get('save_output', False):
            output_path = Path(self.config['output_directory']) / f"output_{datetime.now():%Y%m%d_%H%M%S}.mp4"
            out = cv2.VideoWriter(str(output_path), cv2.VideoWriter_fourcc(*'mp4v'), 25.0, (width, height))

        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break

            processed = self.process_frame(frame)

            if self.config.get('save_output', False):
                out.write(processed)

            cv2.imshow("Parking Detection - Tracking", processed)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                break
            await asyncio.sleep(0.01)

        cap.release()
        if self.config.get('save_output', False):
            out.release()
        cv2.destroyAllWindows()


async def main():
    config_path = "config.json"
    detector = ParkingDetector(config_path)
    await detector.run("static/video/baidoxe.mp4")


if __name__ == "__main__":
    asyncio.run(main())