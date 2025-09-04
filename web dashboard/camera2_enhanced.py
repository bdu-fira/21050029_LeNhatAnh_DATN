# camera2_enhanced.py - Bổ sung tính năng parking status tracking cho camera2.py hiện tại
import sys
import os
from datetime import datetime
import threading
import time
from typing import Callable, Optional
from illegal_parking_detector import get_illegal_parking_detector

sys.path.append(os.path.dirname(os.path.abspath(__file__)))

# Import parking status manager
from parking_status_manager import ParkingStatusManager

# Import original camera2 components
try:
    from camera2 import ParkingDetector as OriginalParkingDetector
    from camera2 import COLORS, VEHICLE_CLASSES, FONT, FONT_SCALE, FONT_THICKNESS
    import cv2
    import numpy as np
    import logging
except ImportError as e:
    print(f"Lỗi import camera2.py: {e}")
    print("Vui lòng đảm bảo camera2.py tồn tại trong cùng thư mục")
    exit(1)

logger = logging.getLogger(__name__)


class EnhancedParkingDetector(OriginalParkingDetector):
    """Mở rộng ParkingDetector gốc với tính năng parking status tracking"""

    def __init__(self, config_path):
        # Khởi tạo class cha
        super().__init__(config_path)

        # THÊM: Parking Status Manager
        self.status_manager = ParkingStatusManager()

        # Đếm tổng số parking spaces
        self.total_parking_spaces = len(self.parking_polygons)
        # ✅ THÊM: Notification callbacks
        self.notification_callbacks = []
        self.last_notification_time = {}
        self.notification_cooldown = 60  # seconds

        # ✅ THÊM: Change detection
        self.previous_status = None
        logger.info(f"Enhanced Parking Detector initialized with {self.total_parking_spaces} spaces")

    def register_notification_callback(self, callback: Callable):
        """Đăng ký callback để nhận thông báo thay đổi parking"""
        self.notification_callbacks.append(callback)
        logger.info(f"✅ Registered notification callback")

    def _check_for_status_changes(self, new_status: dict):
        """Kiểm tra thay đổi trạng thái và gửi thông báo"""
        try:
            if self.previous_status is None:
                self.previous_status = new_status.copy()
                return

            current_overview = self.status_manager.get_parking_overview()

            prev_available = sum(1 for status in self.previous_status.values() if status == 'empty')
            curr_available = current_overview['empty_spaces']

            # Kiểm tra parking became full
            if prev_available > 0 and curr_available == 0:
                self._send_notification(
                    'parking_full',
                    '🚫 Bãi đỗ xe đã đầy!',
                    f'Tất cả {self.total_parking_spaces} chỗ đỗ đã có xe. Vui lòng chờ hoặc tìm bãi khác.',
                    {
                        'total_spaces': self.total_parking_spaces,
                        'available_spaces': curr_available,
                        'status': 'full'
                    }
                )

            # Kiểm tra spaces became available
            elif prev_available == 0 and curr_available > 0:
                self._send_notification(
                    'space_available',
                    '✅ Có chỗ đỗ trống!',
                    f'Hiện có {curr_available} chỗ đỗ trống. Nhanh tay đến bãi xe!',
                    {
                        'available_spaces': curr_available,
                        'total_spaces': self.total_parking_spaces,
                        'status': 'available'
                    }
                )

            # Kiểm tra critical low spaces
            elif prev_available > 3 and curr_available <= 3 and curr_available > 0:
                percentage = ((self.total_parking_spaces - curr_available) / self.total_parking_spaces) * 100
                self._send_notification(
                    'space_limited',
                    '⚠️ Sắp hết chỗ đỗ',
                    f'Chỉ còn {curr_available} chỗ trống ({percentage:.0f}% đã sử dụng)',
                    {
                        'available_spaces': curr_available,
                        'total_spaces': self.total_parking_spaces,
                        'percentage': percentage,
                        'status': 'limited'
                    }
                )

            self.previous_status = new_status.copy()

        except Exception as e:
            logger.error(f"❌ Error checking status changes: {e}")

    def _send_notification(self, notification_type: str, title: str, message: str, data: dict = None):
        """Gửi thông báo qua các callbacks đã đăng ký"""
        if not self._can_send_notification(notification_type):
            logger.debug(f"⏰ Notification cooldown active: {notification_type}")
            return

        self.last_notification_time[notification_type] = time.time()

        notification_data = {
            'id': f"{notification_type}_{int(time.time())}",
            'type': notification_type,
            'title': title,
            'message': message,
            'timestamp': datetime.now().isoformat(),
            'data': data or {}
        }

        logger.info(f"📱 Sending notification: {title}")

        for callback in self.notification_callbacks:
            try:
                callback(notification_data)
            except Exception as e:
                logger.error(f"❌ Error in notification callback: {e}")

    def _can_send_notification(self, notification_type: str) -> bool:
        """Kiểm tra có thể gửi thông báo không (cooldown)"""
        last_time = self.last_notification_time.get(notification_type)
        if last_time is None:
            return True
        return (time.time() - last_time) >= self.notification_cooldown



    def _initialize_sample_data(self):  # ✅ ĐÚNG: Method riêng biệt
        """✅ THÊM: Khởi tạo dữ liệu mẫu khi detector được tạo"""
        try:
            # Tạo sample parking status (một số ô có xe, một số trống)
            sample_status = {}
            for i in range(self.total_parking_spaces):
                # 40% có xe, 60% trống
                sample_status[i] = 'occupied' if (i % 5 < 2) else 'empty'

            # Cập nhật status manager
            self.status_manager.update_parking_status(sample_status, self.total_parking_spaces)

            logger.info(
                f"Sample parking data initialized: {len([s for s in sample_status.values() if s == 'occupied'])}/{self.total_parking_spaces} occupied")

        except Exception as e:
            logger.error(f"Error initializing sample data: {e}")

    def _update_parking_status(self, tracked_objects):
        """Override method gốc để thêm tính năng thông báo"""
        # Gọi method gốc
        new_status = super()._update_parking_status(tracked_objects)

        # THÊM: Cập nhật vào Status Manager
        self.status_manager.update_parking_status(new_status, self.total_parking_spaces)

        # ✅ THÊM: Kiểm tra thay đổi và gửi thông báo
        self._check_for_status_changes(new_status)

        return new_status

    def get_notification_stats(self):
        """Lấy thống kê thông báo"""
        return {
            'registered_callbacks': len(self.notification_callbacks),
            'last_notifications': self.last_notification_time.copy(),
            'real_time_active': len(self.notification_callbacks) > 0
        }

    def _draw_parking_info(self, frame):
        """Vẽ thông tin tổng quan parking lên frame"""
        overview = self.status_manager.get_parking_overview()

        # Vẽ background cho thông tin
        info_bg = (10, 10, 400, 140)
        cv2.rectangle(frame, (info_bg[0], info_bg[1]),
                      (info_bg[0] + info_bg[2], info_bg[1] + info_bg[3]),
                      (0, 0, 0), -1)
        cv2.rectangle(frame, (info_bg[0], info_bg[1]),
                      (info_bg[0] + info_bg[2], info_bg[1] + info_bg[3]),
                      (255, 255, 255), 2)

        # Thông tin text
        y_offset = 35
        texts = [
            f"Tong so cho: {overview['total_spaces']}",
            f"Da co xe: {overview['occupied_spaces']}",
            f"Con trong: {overview['empty_spaces']}",
            f"Ti le su dung: {overview['occupancy_rate']}%",
            f"Trang thai: {overview['status_text']}"
        ]

        for text in texts:
            cv2.putText(frame, text, (20, y_offset),
                        FONT, 0.6, (255, 255, 255), 1)
            y_offset += 25

    def _draw_parking_areas(self, frame, status):
        """Override để thêm số thứ tự parking space"""
        for i, polygon in enumerate(self.parking_polygons):
            # Lấy màu dựa trên trạng thái
            color = COLORS[status.get(i, "empty")]

            # Vẽ polygon
            pts = np.array(polygon, np.int32)
            cv2.polylines(frame, [pts], True, color, 3)

            # Tô màu nhạt bên trong
            overlay = frame.copy()
            cv2.fillPoly(overlay, [pts], color)
            cv2.addWeighted(frame, 0.8, overlay, 0.2, 0, frame)

            # Vẽ số thứ tự parking space
            center_x = int(np.mean([point[0] for point in polygon]))
            center_y = int(np.mean([point[1] for point in polygon]))

            # Vẽ số với background
            text = str(i + 1)
            text_size = cv2.getTextSize(text, FONT, FONT_SCALE, FONT_THICKNESS)[0]
            cv2.rectangle(frame,
                          (center_x - text_size[0] // 2 - 5, center_y - text_size[1] // 2 - 5),
                          (center_x + text_size[0] // 2 + 5, center_y + text_size[1] // 2 + 5),
                          (0, 0, 0), -1)
            cv2.putText(frame, text,
                        (center_x - text_size[0] // 2, center_y + text_size[1] // 2),
                        FONT, FONT_SCALE, (255, 255, 255), FONT_THICKNESS)

    def process_frame(self, frame):
        """Override method process_frame để thêm thông tin parking"""
        # Gọi method gốc
        processed_frame = super().process_frame(frame)

        # THÊM: Vẽ thông tin parking
        self._draw_parking_info(processed_frame)

        return processed_frame

    def get_parking_status(self):
        """PUBLIC METHOD: Lấy trạng thái parking cho API"""
        try:
            return self.status_manager.get_parking_overview()
        except Exception as e:
            logger.error(f"Error getting parking overview: {e}")
            # Return fallback data
            return {
                'total_spaces': self.total_parking_spaces,
                'occupied_spaces': 0,
                'empty_spaces': self.total_parking_spaces,
                'occupancy_rate': 0,
                'status_text': 'Lỗi lấy dữ liệu',
                'last_updated': datetime.now().isoformat()
            }

    def get_detailed_parking_status(self):
        """PUBLIC METHOD: Lấy trạng thái chi tiết parking"""
        return self.status_manager.get_detailed_status()

    def get_empty_spaces(self):
        """PUBLIC METHOD: Lấy danh sách ô đỗ trống"""
        return self.status_manager.get_spaces_by_status('empty')

    def get_occupied_spaces(self):
        """PUBLIC METHOD: Lấy danh sách ô đỗ có xe"""
        return self.status_manager.get_spaces_by_status('occupied')

    """Mở rộng thêm tính năng phát hiện đỗ xe sai quy định"""

    def __init__(self, config_path):
        super().__init__(config_path)

        # Initialize illegal parking detector
        self.illegal_detector = get_illegal_parking_detector()

        # Đăng ký callback để nhận thông báo vi phạm
        self.illegal_detector.register_notification_callback(
            self._handle_illegal_parking_notification
        )

        # Thread để dọn dẹp tracker cũ
        self.cleanup_thread = threading.Thread(
            target=self._periodic_cleanup,
            daemon=True
        )
        self.cleanup_thread.start()

        logger.info("Illegal parking detection enabled")

    def _handle_illegal_parking_notification(self, notification):
        """Xử lý thông báo vi phạm đỗ xe"""
        try:
            # Chuyển tiếp thông báo tới các callback đã đăng ký
            for callback in self.notification_callbacks:
                callback(notification)

            # Log vi phạm
            if notification['severity'] == 'violation':
                logger.error(f"🚨 ILLEGAL PARKING: {notification['message']}")
            elif notification['severity'] == 'warning':
                logger.warning(f"⚠️ PARKING WARNING: {notification['message']}")
            else:
                logger.info(f"ℹ️ PARKING INFO: {notification['message']}")

        except Exception as e:
            logger.error(f"Error handling illegal parking notification: {e}")

    def _periodic_cleanup(self):
        """Dọn dẹp tracker cũ định kỳ"""
        while True:
            try:
                time.sleep(60)  # Mỗi phút
                self.illegal_detector.cleanup_old_trackers()
            except Exception as e:
                logger.error(f"Error in cleanup thread: {e}")

    def _update_parking_status(self, tracked_objects):
        """Override để thêm illegal parking detection"""
        # Gọi method gốc
        new_status = super()._update_parking_status(tracked_objects)

        # Cập nhật illegal parking detector
        for track_id, vehicle_info in tracked_objects.items():
            center = self._get_center(vehicle_info['box'][:4])

            # Kiểm tra xem xe có trong parking space không
            in_parking_space = False
            for i, polygon in enumerate(self.parking_polygons):
                if self._is_inside_polygon(center, polygon):
                    in_parking_space = True
                    break

            # Chỉ theo dõi xe KHÔNG trong parking space
            # (đỗ ở lối đi, đường, v.v.)
            if not in_parking_space:
                self.illegal_detector.update_vehicle(
                    track_id,
                    center,
                    vehicle_info
                )

        return new_status

    def _draw_illegal_parking_warnings(self, frame):
        """Vẽ cảnh báo vi phạm lên frame"""
        violations = self.illegal_detector.get_active_violations()

        for violation in violations:
            pos = violation['position']
            duration = violation['duration']

            # Vẽ vòng tròn cảnh báo
            cv2.circle(frame, pos, 50, (0, 0, 255), 3)

            # Vẽ text cảnh báo
            text = f"VIOLATION: {duration}s"
            text_size = cv2.getTextSize(text, FONT, 0.8, 2)[0]

            # Background cho text
            cv2.rectangle(frame,
                          (pos[0] - text_size[0] // 2 - 10, pos[1] - 60),
                          (pos[0] + text_size[0] // 2 + 10, pos[1] - 30),
                          (0, 0, 255), -1)

            # Text
            cv2.putText(frame, text,
                        (pos[0] - text_size[0] // 2, pos[1] - 40),
                        FONT, 0.8, (255, 255, 255), 2)

            # Vẽ mũi tên chỉ xuống
            arrow_start = (pos[0], pos[1] - 30)
            arrow_end = pos
            cv2.arrowedLine(frame, arrow_start, arrow_end,
                            (0, 0, 255), 3, tipLength=0.3)

    def process_frame(self, frame):
        """Override để thêm vẽ cảnh báo vi phạm"""
        # Xử lý frame gốc
        processed_frame = super().process_frame(frame)

        # Vẽ cảnh báo vi phạm
        self._draw_illegal_parking_warnings(processed_frame)

        # Vẽ thống kê vi phạm
        stats = self.illegal_detector.get_statistics()
        if stats['active_violations'] > 0:
            # Vẽ banner cảnh báo
            cv2.rectangle(processed_frame, (0, 0),
                          (processed_frame.shape[1], 40),
                          (0, 0, 255), -1)

            text = f"⚠️ CANH BAO: {stats['active_violations']} xe dang vi pham do xe!"
            cv2.putText(processed_frame, text,
                        (10, 28), FONT, 0.9, (255, 255, 255), 2)

        return processed_frame

    def get_illegal_parking_stats(self):
        """Lấy thống kê vi phạm đỗ xe"""
        return {
            'violations': self.illegal_detector.get_active_violations(),
            'statistics': self.illegal_detector.get_statistics()
        }


# Global instance để sử dụng trong app.py
_global_enhanced_detector = None


def get_enhanced_parking_detector(config_path="config.json", enable_illegal_parking=True):
    """Factory function để lấy enhanced parking detector với illegal parking detection"""
    global _global_enhanced_detector

    if _global_enhanced_detector is None:
        try:
            if enable_illegal_parking:
                # Sử dụng version với illegal parking detection
                from illegal_parking_detector import get_illegal_parking_detector

                # Tạo class kết hợp
                class CombinedParkingDetector(EnhancedParkingDetector):
                    def __init__(self, config_path):
                        super().__init__(config_path)

                        # Add illegal parking detector
                        self.illegal_detector = get_illegal_parking_detector()

                        # Register notification callback
                        self.illegal_detector.register_notification_callback(
                            self._handle_illegal_parking_notification
                        )

                        # Cleanup thread
                        self.cleanup_thread = threading.Thread(
                            target=self._periodic_cleanup,
                            daemon=True
                        )
                        self.cleanup_thread.start()

                        logger.info("🚗 Combined Parking Detector with Illegal Parking Detection initialized")

                    def _handle_illegal_parking_notification(self, notification):
                        """Forward illegal parking notifications"""
                        for callback in self.notification_callbacks:
                            try:
                                callback(notification)
                            except Exception as e:
                                logger.error(f"Error in notification callback: {e}")

                    def _periodic_cleanup(self):
                        """Periodic cleanup of old trackers"""
                        while True:
                            try:
                                time.sleep(60)
                                self.illegal_detector.cleanup_old_trackers()
                            except Exception as e:
                                logger.error(f"Cleanup error: {e}")

                    def _update_parking_status(self, tracked_objects):
                        """Override to add illegal parking detection"""
                        # Call parent method
                        new_status = super()._update_parking_status(tracked_objects)

                        # Update illegal parking detector
                        for track_id, vehicle_info in tracked_objects.items():
                            center = self._get_center(vehicle_info['box'][:4])

                            # Check if vehicle is in parking space
                            in_parking_space = False
                            for i, polygon in enumerate(self.parking_polygons):
                                if self._is_inside_polygon(center, polygon):
                                    in_parking_space = True
                                    break

                            # Only track vehicles NOT in parking spaces
                            if not in_parking_space:
                                self.illegal_detector.update_vehicle(
                                    track_id,
                                    center,
                                    vehicle_info
                                )

                        return new_status

                    def _draw_illegal_parking_warnings(self, frame):
                        """Draw violation warnings on frame"""
                        violations = self.illegal_detector.get_active_violations()

                        for violation in violations:
                            pos = violation['position']
                            duration = violation['duration']

                            # Draw warning circle
                            cv2.circle(frame, pos, 50, (0, 0, 255), 3)

                            # Draw warning text
                            text = f"VIOLATION: {duration}s"
                            text_size = cv2.getTextSize(text, FONT, 0.8, 2)[0]

                            # Text background
                            cv2.rectangle(frame,
                                          (pos[0] - text_size[0] // 2 - 10, pos[1] - 60),
                                          (pos[0] + text_size[0] // 2 + 10, pos[1] - 30),
                                          (0, 0, 255), -1)

                            # Text
                            cv2.putText(frame, text,
                                        (pos[0] - text_size[0] // 2, pos[1] - 40),
                                        FONT, 0.8, (255, 255, 255), 2)

                            # Arrow pointing down
                            cv2.arrowedLine(frame,
                                            (pos[0], pos[1] - 30),
                                            pos,
                                            (0, 0, 255), 3, tipLength=0.3)

                    def process_frame(self, frame):
                        """Process frame with illegal parking detection"""
                        # Process parent frame
                        processed_frame = super().process_frame(frame)

                        # Draw illegal parking warnings
                        self._draw_illegal_parking_warnings(processed_frame)

                        # Draw violation statistics
                        stats = self.illegal_detector.get_statistics()
                        if stats['active_violations'] > 0:
                            # Draw warning banner
                            cv2.rectangle(processed_frame, (0, 0),
                                          (processed_frame.shape[1], 40),
                                          (0, 0, 255), -1)

                            text = f"WARNING: {stats['active_violations']} illegal parking violations!"
                            cv2.putText(processed_frame, text,
                                        (10, 28), FONT, 0.9, (255, 255, 255), 2)

                        return processed_frame

                    def get_illegal_parking_stats(self):
                        """Get illegal parking statistics"""
                        return {
                            'violations': self.illegal_detector.get_active_violations(),
                            'statistics': self.illegal_detector.get_statistics()
                        }

                # Create combined detector
                _global_enhanced_detector = CombinedParkingDetector(config_path)

            else:
                # Use basic enhanced detector
                _global_enhanced_detector = EnhancedParkingDetector(config_path)

            # Initialize sample data
            _global_enhanced_detector._initialize_sample_data()

            logger.info("Enhanced Parking Detector created successfully")

        except Exception as e:
            logger.error(f"Error creating enhanced detector: {e}")
            _global_enhanced_detector = None

    return _global_enhanced_detector


# Helper function for WebSocket notifications
def setup_illegal_parking_notifications(notification_callback):
    """Setup illegal parking notifications"""
    detector = get_enhanced_parking_detector(enable_illegal_parking=True)
    if detector and hasattr(detector, 'illegal_detector'):
        detector.illegal_detector.register_notification_callback(notification_callback)
        logger.info("✅ Illegal parking notifications configured")
        return True
    return False


def get_current_parking_status():
    """Helper function để lấy trạng thái parking hiện tại"""
    global _global_enhanced_detector

    # ✅ FIXED: Đảm bảo detector được khởi tạo
    if _global_enhanced_detector is None:
        _global_enhanced_detector = get_enhanced_parking_detector()

    if _global_enhanced_detector is not None:
        try:
            return _global_enhanced_detector.get_parking_status()
        except Exception as e:
            logger.error(f"Error getting parking status: {e}")

    # Fallback data
    return {
        'total_spaces': 20,
        'occupied_spaces': 8,
        'empty_spaces': 12,
        'occupancy_rate': 40.0,
        'status_text': 'Hệ thống fallback',
        'last_updated': datetime.now().isoformat()
    }


def get_enhanced_parking_detector_with_notifications(config_path="config.json", notification_callback=None):
    """Factory function với notification support"""
    global _global_enhanced_detector

    if _global_enhanced_detector is None:
        try:
            _global_enhanced_detector = EnhancedParkingDetector(config_path)
            _global_enhanced_detector._initialize_sample_data()
        except Exception as e:
            logger.error(f"Error creating enhanced detector: {e}")
            return None

    if _global_enhanced_detector and notification_callback:
        _global_enhanced_detector.register_notification_callback(notification_callback)

    return _global_enhanced_detector


# Test function
if __name__ == "__main__":
    import asyncio

    async def test_enhanced_detector():
        detector = EnhancedParkingDetector("config.json")
        await detector.run("static/video/baidoxe.mp4")

    asyncio.run(test_enhanced_detector())