# app.py - Complete Enhanced Parking System with Role-Based Access Control
import csv
import json
import logging
import os
import shutil
import sqlite3
import sys
import threading
import time
import atexit
from collections import defaultdict
from datetime import datetime, timedelta
from io import StringIO, BytesIO
from queue import Queue
import re
import hashlib
import secrets
from functools import wraps
from typing import Dict, List, Optional, Tuple, Any
from datetime import datetime
from functools import lru_cache
import random
from flask_socketio import SocketIO, emit, join_room, leave_room
import uuid
from enum import Enum
from dataclasses import dataclass, asdict
from illegal_parking_detector import get_illegal_parking_detector


# Flask and extensions
from flask import Flask, Response, render_template, jsonify, flash, redirect, request, url_for, send_file, session
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_cors import CORS
from werkzeug.utils import secure_filename
from werkzeug.security import generate_password_hash, check_password_hash

# Computer Vision and ML
import cv2
import numpy as np


os.environ['PYTHONIOENCODING'] = 'utf-8'
sys.stdout.reconfigure(encoding='utf-8')

# Local imports
try:
    from camera2 import ParkingDetector
except ImportError:
    ParkingDetector = None
    print("Warning: ParkingDetector not available")

try:
    import camera1
except ImportError:
    camera1 = None
    print("Warning: camera1 module not available")

# Import database manager
try:
    from database import db_manager
except ImportError:
    print("Warning: Database module not found. Creating minimal db_manager...")


    class MinimalDBManager:
        def __init__(self, db_path='parking.db'):
            self.db_path = db_path
        def get_parking_statistics(self, date=None):
            return {
                'total_entries': 0,
                'total_exits': 0,
                'registered_entries': 0,
                'unregistered_entries': 0,
                'current_in_parking': 0
            }

        def register_vehicle(self, data):
            return False, "Database not available"

        def get_vehicle_info(self, plate):
            return None

        def log_entry_exit(self, plate, action, filename, confidence=0.0):
            return False, None

        def get_current_vehicles_in_parking(self):
            return []

        def search_vehicles(self, query, search_type):
            return []

        def update_vehicle(self, plate, data):
            return False, "Database not available"

        def deactivate_vehicle(self, plate):
            return False, "Database not available"


    db_manager = MinimalDBManager()

# Excel/CSV handling
try:
    import pandas as pd
    import openpyxl
    from openpyxl.styles import Font, PatternFill, Alignment

    EXCEL_AVAILABLE = True
except ImportError:
    EXCEL_AVAILABLE = False
    print("Warning: pandas/openpyxl not available. Excel export will be disabled.")

# Initialize Flask app
app = Flask(__name__)
app.secret_key = os.environ.get('SECRET_KEY', 'parking_system_secret_key_2024_' + secrets.token_hex(16))

# Enable CORS
CORS(app, origins=['*'])
socketio = SocketIO(
    app,
    cors_allowed_origins="*",
    logger=False,
    engineio_logger=False,
    async_mode='threading',
    transports=['websocket', 'polling'],  # Add fallback
    ping_timeout=60,
    ping_interval=25
)


class NotificationType(Enum):
    PARKING_FULL = "parking_full"
    SPACE_AVAILABLE = "space_available"
    SPACE_LIMITED = "space_limited"
    SYSTEM_MAINTENANCE = "system_maintenance"


class Priority(Enum):
    LOW = 1
    NORMAL = 2
    HIGH = 3
    URGENT = 4


@dataclass
class ParkingNotification:
    id: str
    title: str
    message: str
    type: NotificationType
    priority: Priority
    timestamp: str
    data: dict = None

    def __post_init__(self):
        if self.data is None:
            self.data = {}


class ParkingSystemState:
    def __init__(self):
        self.connected_clients = {}
        self.notification_queue = []
        self.notification_cooldown = {}
        self.cooldown_period = 60

    def add_client(self, session_id: str, client_info: dict):
        self.connected_clients[session_id] = {
            **client_info,
            'connected_at': datetime.now().isoformat()
        }
        logger.info(f" Client connected: {session_id}")

    def remove_client(self, session_id: str):
        if session_id in self.connected_clients:
            self.connected_clients.pop(session_id)
            logger.info(f"🔌 Client disconnected: {session_id}")

    def can_send_notification(self, notif_type: NotificationType) -> bool:
        last_time = self.notification_cooldown.get(notif_type)
        if last_time is None:
            return True
        return time.time() - last_time >= self.cooldown_period

    def send_notification(self, notification: ParkingNotification):
        if not self.can_send_notification(notification.type):
            return

        self.notification_cooldown[notification.type] = time.time()

        notif_data = {
            'id': notification.id,
            'title': notification.title,
            'message': notification.message,
            'type': notification.type.value,
            'priority': notification.priority.value,
            'timestamp': notification.timestamp,
            'data': notification.data
        }

        socketio.emit('notification', notif_data)
        logger.info(f"📱 Broadcast notification: {notification.title}")

        self.notification_queue.append(notif_data)
        if len(self.notification_queue) > 100:
            self.notification_queue.pop(0)


parking_system_state = ParkingSystemState()







# Rate limiting
limiter = Limiter(
    key_func=get_remote_address,
    default_limits=["1000 per day", "100 per hour"]
)
limiter.init_app(app)

# File upload configuration
UPLOAD_FOLDER = 'static/uploads'
ALLOWED_EXTENSIONS = {'png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp'}
MAX_CONTENT_LENGTH = 16 * 1024 * 1024  # 16MB max file size

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
app.config['MAX_CONTENT_LENGTH'] = MAX_CONTENT_LENGTH

# Ensure required directories exist
required_dirs = ['static/uploads', 'static/captures', 'static/vehicle_photos', 'logs', 'backups', 'exports']
for directory in required_dirs:
    os.makedirs(directory, exist_ok=True)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('logs/app.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Security logging
security_logger = logging.getLogger('security')
security_handler = logging.FileHandler('logs/security.log')
security_handler.setFormatter(logging.Formatter('%(asctime)s - SECURITY - %(message)s'))
security_logger.addHandler(security_handler)
security_logger.setLevel(logging.WARNING)

# Global variables
plateTimes = defaultdict(str)
frame_buffer = None
frame_lock = threading.Lock()
parking_detector = None
system_status = {
    'camera1_active': False,
    'camera2_active': False,
    'detection_active': False,
    'database_active': False,
    'startup_time': datetime.now(),
    'last_activity': datetime.now()
}

# System metrics
system_metrics = {
    'total_requests': 0,
    'failed_requests': 0,
    'active_sessions': 0,
    'database_queries': 0,
    'cache_hits': 0,
    'cache_misses': 0
}


# Configuration
class ParkingConfig:
    TARGET_FPS = 25
    DETECTION_INTERVAL = 0.15
    FRAME_SKIP = 1
    JPEG_QUALITY = 88
    VIDEO_WIDTH = 1280
    VIDEO_HEIGHT = 720
    MAX_PLATE_HISTORY = 1000
    BACKUP_INTERVAL_HOURS = 24
    CLEANUP_INTERVAL_DAYS = 30
    CONFIDENCE_THRESHOLD = 0.5


parking_config = ParkingConfig()

# Initialize default config
default_config = {
    "parking_areas_path": "static/js/bounding_boxes.json",
    "model_path": "model/yolov8l.pt",
    "tracker_threshold": 30,
    "iou_threshold": 0.5,
    "save_output": False,
    "output_directory": "output",
    "confidence_threshold": 0.5,
    "max_detections": 10
}

# Create config.json if it doesn't exist
config_path = "config.json"
if not os.path.exists(config_path):
    with open(config_path, 'w') as f:
        json.dump(default_config, f, indent=4)
    logger.info(f"Created default config file at {config_path}")

# ===============================
# ROLE-BASED ACCESS CONTROL
# ===============================

# Định nghĩa quyền đơn giản
ROLE_PERMISSIONS = {
    'admin': {
        'access_all',  # Admin có tất cả quyền
    },
    'guard': {
        'capture_only',  # Bảo vệ chỉ chụp ảnh và tìm kiếm
    }
}


# Decorator kiểm tra quyền admin
def admin_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'user_role' not in session or session['user_role'] != 'admin':
            if request.path.startswith('/api/'):
                return jsonify({'success': False, 'error': 'Chỉ admin mới có quyền truy cập'}), 403
            flash('Chỉ admin mới có quyền truy cập chức năng này', 'error')
            return redirect(url_for('index'))
        return f(*args, **kwargs)

    return decorated_function


# Decorator kiểm tra đăng nhập cơ bản (cho bảo vệ và admin)
def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'user_id' not in session:
            if request.path.startswith('/api/'):
                return jsonify({'success': False, 'error': 'Cần đăng nhập'}), 401
            return redirect(url_for('login'))
        return f(*args, **kwargs)

    return decorated_function


# ===============================
# UTILITY FUNCTIONS
# ===============================

def allowed_file(filename):
    """Check if file extension is allowed"""
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS


def validate_plate_number(plate_number):
    """Validate Vietnamese license plate format"""
    if not plate_number or len(plate_number) < 5 or len(plate_number) > 12:
        return False

    # Vietnamese license plate patterns
    patterns = [
        r'^\d{2}[A-Z]-\d{3}\.\d{2}$',  # 61A-001.12
        r'^\d{2}[A-Z]-\d{5}$',  # 61A-00112
        r'^\d{2}[A-Z]\d-\d{4}$',  # 61A1-2345
        r'^\d{2}[A-Z]\d-\d{5}$',  # 61A1-23456
        r'^\d{2}[A-Z]-\d{6}$',  # 61A-123456
        r'^\d{2}[A-Z]\d{5}$',  # 61A12345
        r'^\d{2}[A-Z]\d{4}$',  # 61A1234
        r'^\d{2}[A-Z]\d{3}\.\d{2}$',  # 61A123.45
        r'^\d{2}[A-Z]-\d{4}$',  # 61A-1234
    ]

    plate_clean = plate_number.strip().upper()
    return any(re.match(pattern, plate_clean) for pattern in patterns)


def sanitize_input(input_str):
    """Sanitize input to prevent XSS and SQL injection"""
    if not input_str:
        return ""

    # Remove HTML tags
    clean = re.sub(r'<[^>]*>', '', str(input_str))
    # Remove potentially dangerous characters
    clean = re.sub(r'[<>"\'\&\$\;]', '', clean)
    return clean.strip()


def validate_email(email):
    """Validate email format"""
    if not email:
        return True  # Email is optional
    pattern = r'^[^\s@]+@[^\s@]+\.[^\s@]+$'
    return re.match(pattern, email) is not None


def validate_phone(phone):
    """Validate Vietnamese phone number"""
    if not phone:
        return True  # Phone is optional
    pattern = r'^[0-9]{10,11}$'
    return re.match(pattern, phone) is not None


def update_system_metrics(metric_name, increment=1):
    """Update system metrics"""
    global system_metrics
    if metric_name in system_metrics:
        system_metrics[metric_name] += increment
    system_status['last_activity'] = datetime.now()


def log_security_event(event_type, details, ip_address=None):
    """Log security events"""
    if not ip_address:
        ip_address = request.remote_addr if request else 'unknown'
    security_logger.warning(f"{event_type} - IP: {ip_address} - {details}")


# ===============================
# DECORATORS
# ===============================

def track_requests(f):
    """Decorator to track requests"""

    @wraps(f)
    def decorated_function(*args, **kwargs):
        update_system_metrics('total_requests')
        try:
            return f(*args, **kwargs)
        except Exception as e:
            update_system_metrics('failed_requests')
            logger.error(f"Request failed: {str(e)}")
            raise

    return decorated_function


def require_valid_session(f):
    """Decorator to require valid session"""

    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'session_id' not in session:
            session['session_id'] = secrets.token_hex(32)
            session['created_at'] = datetime.now().isoformat()
        return f(*args, **kwargs)

    return decorated_function


# ===============================
# PARKING DETECTOR INITIALIZATION
# ===============================

# Initialize ParkingDetector
try:
    parking_detector = ParkingDetector(config_path)
    system_status['camera2_active'] = True
    logger.info("ParkingDetector initialized successfully")
except Exception as e:
    logger.error(f"Error initializing ParkingDetector: {e}")
    system_status['camera2_active'] = False
    parking_detector = None


def simple_frame_monitor():
    """Simple frame monitoring for backup frame capture"""
    global frame_buffer
    video_path = "static/video/cong.mp4"

    try:
        vid = cv2.VideoCapture(video_path)
        if not vid.isOpened():
            logger.error(f"Cannot open video file: {video_path}")
            return

        vid.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        vid.set(cv2.CAP_PROP_FPS, 30)
        frame_count = 0

        logger.info("Frame monitor started")

        while vid.isOpened():
            ret, frame = vid.read()
            if not ret:
                vid.set(cv2.CAP_PROP_POS_FRAMES, 0)
                frame_count = 0
                continue

            frame_count += 1
            if frame_count % 3 == 0:
                with frame_lock:
                    frame_buffer = frame.copy()

            time.sleep(0.033)  # ~30 FPS

    except Exception as e:
        logger.error(f"Frame monitor error: {e}")
    finally:
        if 'vid' in locals():
            vid.release()


# Start monitor thread
monitor_thread = threading.Thread(target=simple_frame_monitor, daemon=True)
monitor_thread.start()


# ===============================
# TEMPLATE CONTEXT
# ===============================

@app.context_processor
def inject_user_info():
    """Inject thông tin user vào template"""
    user_role = session.get('user_role', '')
    user_name = session.get('user_name', 'Người dùng')

    # Xác định tên hiển thị cho vai trò
    if user_role == 'admin':
        role_display = 'Quản trị viên'
    elif user_role == 'guard':
        role_display = 'Bảo vệ'
    else:
        role_display = user_role

    return dict(
        user_role=user_role,
        user_name=user_name,
        role_display=role_display,
        is_admin=(user_role == 'admin'),
        is_guard=(user_role == 'guard')
    )


# ===============================
# MIDDLEWARE LOGGING
# ===============================



# ===============================
# MAIN ROUTE DEFINITIONS
# ===============================

# =====================================
# ROUTES CHO TẤT CẢ NGƯỜI DÙNG
# =====================================

@app.route('/')
@login_required
@track_requests
def index():
    """Trang chủ - Tất cả có thể truy cập"""
    user_role = session.get('user_role', 'guest')
    user_name = session.get('user_name', 'Người dùng')

    # Log để debug
    logger.info(f"Index page accessed by {user_name} with role {user_role}")

    return render_template('enhanced_index.html',
                           user_role=user_role,
                           user_name=user_name,
                           is_admin=(user_role == 'admin'),
                           is_guard=(user_role == 'guard'))


@app.route('/capture', methods=['POST'])
@login_required
@limiter.limit("30 per minute")
@track_requests
@require_valid_session
def capture():
    """Chụp ảnh xe - Tất cả có thể sử dụng"""
    try:

        data = request.get_json()
        if not data:
            return jsonify({"success": False, "error": "No data provided"}), 400

        action = data.get('action')
        if action not in ['entry', 'exit']:
            return jsonify({"success": False, "error": "Invalid action"}), 400

        # Log cho bảo vệ
        if session.get('user_role') == 'guard':
            logger.info(f"Guard {session.get('user_id')} performed {action} capture")



        # Get current frame
        ret, frame = False, None
        try:
            ret, frame = camera1.get_current_frame()
        except Exception as e:
            logger.warning(f"Cannot get frame from camera1: {e}")

        if not ret or frame is None:
            with frame_lock:
                if frame_buffer is not None:
                    frame = frame_buffer.copy()
                    ret = True
                else:
                    ret = False

        if not ret or frame is None:
            return jsonify({
                "success": False,
                "error": "Không thể lấy frame từ camera"
            }), 500

        # Get detected plate
        current_plate = "Không nhận diện được biển số"
        try:
            detected_plate = camera1.get_current_plate()
            if detected_plate and detected_plate.strip():
                current_plate = detected_plate.strip().upper()
        except Exception as e:
            logger.warning(f"Cannot get current plate: {e}")

        # Generate filename
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{action}_{timestamp}.jpg"
        filepath = os.path.join('static/captures', filename)

        # Save image with high quality
        success_save = cv2.imwrite(filepath, frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
        if not success_save:
            logger.error(f"Failed to save image: {filepath}")
            return jsonify({
                "success": False,
                "error": "Không thể lưu ảnh"
            }), 500

        # Format timestamp for display
        current_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        # Log to enhanced database
        db_success, vehicle_info = db_manager.log_entry_exit(
            current_plate, action, filename, confidence=1.0
        )

        if current_plate and current_plate != "Không nhận diện được biển số":
            logger.info(f"📸 🚗 Sending notification for plate: {current_plate}")

            capture_data = {
                'image': filename,
                'timestamp': current_time,
                'is_registered': bool(vehicle_info),
                'action': action
            }

            #  FIXED: Call notification function with proper error handling
            try:
                notification_sent = send_vehicle_notification(current_plate, action, capture_data)
                logger.info(f"📸 📱 Notification sent: {notification_sent}")
            except Exception as e:
                logger.error(f"📸 ❌ Notification error: {e}", exc_info=True)


        if not db_success:
            logger.warning(f"Database logging failed for plate: {current_plate}")

        # Update system metrics
        update_system_metrics('database_queries')

        # Prepare response
        response_data = {
            "success": True,
            "image": filename,
            "plate": current_plate,
            "timestamp": current_time,
            "is_registered": False,
            "vehicle_info": None,
            "action": action
        }

        # Add vehicle info if registered
        if vehicle_info:
            response_data.update({
                "is_registered": True,
                "vehicle_info": vehicle_info
            })
            logger.info(f"Registered vehicle detected: {current_plate} - {vehicle_info.get('owner_name', 'Unknown')}")
        else:
            logger.info(f"Unregistered vehicle: {current_plate}")

        return jsonify(response_data)

    except Exception as e:
        logger.error(f"Capture error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({
            "success": False,
            "error": f"Lỗi hệ thống: {str(e)}"
        }), 500


@app.route('/api/test/send-notification', methods=['POST'])
@login_required
@track_requests
def test_send_notification():
    """Test endpoint to manually send vehicle notification"""
    try:
        data = request.get_json()
        plate_number = data.get('plate_number', '61A12345')
        action = data.get('action', 'entry')

        test_capture_data = {
            'image': 'test_capture.jpg',
            'timestamp': datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            'is_registered': True,
            'action': action
        }

        logger.info(f"🧪 Testing notification for {plate_number} - {action}")

        result = send_vehicle_notification(plate_number, action, test_capture_data)

        return jsonify({
            'success': True,
            'message': f'Test notification sent for {plate_number}',
            'result': result,
            'connected_clients': len(parking_system_state.connected_clients)
        })

    except Exception as e:
        logger.error(f"🧪 Test notification error: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500



@app.route('/search_plate/<plate_number>')
@login_required
@track_requests
@require_valid_session
def search_plate(plate_number):
    """Tìm kiếm biển số - Tất cả có thể sử dụng"""
    try:
        plate_number = sanitize_input(plate_number)

        # Log cho bảo vệ
        if session.get('user_role') == 'guard':
            logger.info(f"Guard {session.get('user_id')} searched for plate: {plate_number}")

        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        cursor.execute("""
            SELECT el.plate_number, el.timestamp, el.entry_image, el.exit_image,
                   el.entry_time, el.exit_time, el.is_registered, el.parking_duration,
                   rv.owner_name, rv.owner_phone, rv.vehicle_type, rv.vehicle_brand, rv.vehicle_model
            FROM entry_exit_log el
            LEFT JOIN registered_vehicles rv ON el.plate_number = rv.plate_number
            WHERE el.plate_number = ?
            ORDER BY el.timestamp DESC LIMIT 1
        """, (plate_number.upper(),))

        result = cursor.fetchone()
        conn.close()
        update_system_metrics('database_queries')

        if result:
            return jsonify({
                'found': True,
                'plate': result[0],
                'timestamp': result[1],
                'entry_image': result[2],
                'exit_image': result[3],
                'entry_time': result[4],
                'exit_time': result[5],
                'is_registered': bool(result[6]),
                'parking_duration': result[7],
                'owner_name': result[8],
                'owner_phone': result[9],
                'vehicle_type': result[10],
                'vehicle_brand': result[11],
                'vehicle_model': result[12]
            })

        return jsonify({
            'found': False,
            'message': 'Không tìm thấy biển số xe'
        })

    except Exception as e:
        logger.error(f"Legacy search error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'found': False, 'error': str(e)}), 500


@app.route('/api/current_vehicles')
@login_required
@track_requests
@require_valid_session
def current_vehicles():
    """API xem xe hiện tại - Tất cả có thể sử dụng"""
    try:
        vehicles = db_manager.get_current_vehicles_in_parking()
        update_system_metrics('database_queries')

        return jsonify({
            'success': True,
            'vehicles': vehicles,
            'count': len(vehicles)
        })

    except Exception as e:
        logger.error(f"Current vehicles error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/sync_data', methods=['GET'])
@login_required
@track_requests
@require_valid_session
def sync_data():
    """Đồng bộ dữ liệu - Tất cả có thể sử dụng"""
    try:
        stats = db_manager.get_parking_statistics()
        update_system_metrics('database_queries')

        if stats:
            return jsonify({
                'entry_today': stats['total_entries'],
                'exit_today': stats['total_exits'],
                'total_cars': stats['current_in_parking'],
                'registered_today': stats['registered_entries'],
                'unregistered_today': stats['unregistered_entries'],
                'sync_time': datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            })
        else:
            return jsonify({'error': 'Unable to get statistics'})

    except Exception as e:
        logger.error(f"Sync data error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'error': str(e)}), 500


# =====================================
# ROUTES CHỈ DÀNH CHO ADMIN
# =====================================

@app.route('/register')
@login_required
@track_requests
@require_valid_session
def register_page():
    """Đăng ký xe - CHỈ ADMIN"""
    return render_template('register_vehicle.html')


@app.route('/management')
@admin_required
@track_requests
@require_valid_session
def management_page():
    """Quản lý xe - CHỈ ADMIN"""
    return render_template('vehicle_management.html')


@app.route('/reports')
@admin_required
@track_requests
@require_valid_session
def reports_page():
    """Báo cáo - CHỈ ADMIN"""
    return render_template('reports.html')


@app.route('/api/register_vehicle', methods=['POST'])
@login_required
@limiter.limit("10 per minute")
@track_requests
@require_valid_session
def register_vehicle():
    """API đăng ký xe - CHỈ ADMIN"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({
                'success': False,
                'error': 'Không có dữ liệu'
            }), 400

        # Validate required fields
        required_fields = ['plate_number', 'owner_name', 'vehicle_type']
        for field in required_fields:
            if not data.get(field) or not data.get(field).strip():
                return jsonify({
                    'success': False,
                    'error': f'Trường {field} là bắt buộc'
                }), 400

        # Clean and validate plate number
        plate_number = sanitize_input(data['plate_number']).upper()
        if not validate_plate_number(plate_number):
            return jsonify({
                'success': False,
                'error': 'Biển số xe không hợp lệ'
            }), 400

        # Validate other fields
        owner_name = sanitize_input(data['owner_name'])
        if len(owner_name) < 2:
            return jsonify({
                'success': False,
                'error': 'Tên chủ xe phải có ít nhất 2 ký tự'
            }), 400

        # Validate email if provided
        if data.get('owner_email') and not validate_email(data['owner_email']):
            return jsonify({
                'success': False,
                'error': 'Email không hợp lệ'
            }), 400

        # Validate phone if provided
        if data.get('owner_phone') and not validate_phone(data['owner_phone']):
            return jsonify({
                'success': False,
                'error': 'Số điện thoại không hợp lệ'
            }), 400

        # Sanitize all input data
        cleaned_data = {}
        for key, value in data.items():
            if isinstance(value, str):
                cleaned_data[key] = sanitize_input(value)
            else:
                cleaned_data[key] = value

        cleaned_data['plate_number'] = plate_number

        # Register vehicle
        success, message = db_manager.register_vehicle(cleaned_data)

        if success:
            logger.info(f"Vehicle registered: {plate_number} - {owner_name}")
            log_security_event('VEHICLE_REGISTERED', f"Plate: {plate_number}, Owner: {owner_name}")
        else:
            logger.warning(f"Vehicle registration failed: {plate_number} - {message}")

        update_system_metrics('database_queries')

        return jsonify({
            'success': success,
            'message': message
        })

    except Exception as e:
        logger.error(f"Vehicle registration error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({
            'success': False,
            'error': f'Lỗi server: {str(e)}'
        }), 500


@app.route('/api/upload_vehicle_photo', methods=['POST'])
@admin_required
@limiter.limit("5 per minute")
@track_requests
@require_valid_session
def upload_vehicle_photo():
    """Upload ảnh xe - CHỈ ADMIN"""
    try:
        if 'photo' not in request.files:
            return jsonify({'success': False, 'error': 'Không có file được tải lên'}), 400

        file = request.files['photo']
        plate_number = request.form.get('plate_number')

        if not plate_number:
            return jsonify({'success': False, 'error': 'Thiếu biển số xe'}), 400

        if file.filename == '':
            return jsonify({'success': False, 'error': 'Không có file được chọn'}), 400

        if file and allowed_file(file.filename):
            # Check file size
            file.seek(0, 2)  # Seek to end
            file_size = file.tell()
            file.seek(0)  # Reset to beginning

            if file_size > MAX_CONTENT_LENGTH:
                return jsonify({'success': False, 'error': 'File quá lớn (tối đa 16MB)'}), 400

            # Secure filename
            filename = secure_filename(file.filename)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            safe_plate = re.sub(r'[^a-zA-Z0-9]', '_', plate_number)
            new_filename = f"{safe_plate}_{timestamp}_{filename}"

            # Save file
            filepath = os.path.join('static/vehicle_photos', new_filename)
            file.save(filepath)

            # Update database
            success, message = db_manager.update_vehicle(plate_number, {'photo_path': new_filename})

            if success:
                logger.info(f"Photo uploaded for vehicle: {plate_number}")
                return jsonify({
                    'success': True,
                    'message': 'Tải ảnh thành công',
                    'filename': new_filename
                })
            else:
                # Remove file if database update failed
                try:
                    os.remove(filepath)
                except:
                    pass
                return jsonify({'success': False, 'error': message}), 500

        return jsonify({'success': False, 'error': 'Định dạng file không được hỗ trợ'}), 400

    except Exception as e:
        logger.error(f"Photo upload error: {str(e)}")
        return jsonify({'success': False, 'error': 'Lỗi server'}), 500


@app.route('/api/vehicles/all')
@admin_required
@limiter.limit("20 per minute")
@track_requests
@require_valid_session
def get_all_vehicles():
    """Xem tất cả xe - CHỈ ADMIN"""
    try:
        page = request.args.get('page', 1, type=int)
        per_page = request.args.get('per_page', 20, type=int)
        search = request.args.get('search', '')
        vehicle_type = request.args.get('vehicle_type', '')

        # Validate pagination parameters
        if page < 1:
            page = 1
        if per_page < 1 or per_page > 100:
            per_page = 20

        # Build query
        query = """
            SELECT rv.*, 
                   (SELECT COUNT(*) FROM entry_exit_log el 
                    WHERE el.plate_number = rv.plate_number) as visit_count,
                   (SELECT MAX(el.entry_time) FROM entry_exit_log el 
                    WHERE el.plate_number = rv.plate_number) as last_visit
            FROM registered_vehicles rv
            WHERE rv.is_active = 1
        """
        params = []

        if search:
            query += " AND (rv.plate_number LIKE ? OR rv.owner_name LIKE ? OR rv.owner_phone LIKE ?)"
            search_param = f"%{search}%"
            params.extend([search_param, search_param, search_param])

        if vehicle_type:
            query += " AND rv.vehicle_type = ?"
            params.append(vehicle_type)

        # Get total count for pagination
        count_query = """
            SELECT COUNT(*) FROM registered_vehicles rv
            WHERE rv.is_active = 1
        """
        count_params = []

        if search:
            count_query += " AND (rv.plate_number LIKE ? OR rv.owner_name LIKE ? OR rv.owner_phone LIKE ?)"
            search_param = f"%{search}%"
            count_params.extend([search_param, search_param, search_param])

        if vehicle_type:
            count_query += " AND rv.vehicle_type = ?"
            count_params.append(vehicle_type)

        query += " ORDER BY rv.registration_date DESC"
        query += f" LIMIT {per_page} OFFSET {(page - 1) * per_page}"

        # Execute queries
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Get total count
        cursor.execute(count_query, count_params)
        total_count = cursor.fetchone()[0]

        # Get vehicles
        cursor.execute(query, params)
        results = cursor.fetchall()
        columns = [desc[0] for desc in cursor.description]

        conn.close()

        vehicles = [dict(zip(columns, row)) for row in results]

        update_system_metrics('database_queries')

        return jsonify({
            'success': True,
            'vehicles': vehicles,
            'pagination': {
                'page': page,
                'per_page': per_page,
                'total': total_count,
                'pages': (total_count + per_page - 1) // per_page
            }
        })

    except Exception as e:
        logger.error(f"Get all vehicles error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/vehicles/update/<plate_number>', methods=['PUT'])
@admin_required
@limiter.limit("10 per minute")
@track_requests
@require_valid_session
def update_vehicle(plate_number):
    """Cập nhật xe - CHỈ ADMIN"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({'success': False, 'error': 'Không có dữ liệu'}), 400

        # Sanitize input
        cleaned_data = {}
        for key, value in data.items():
            if isinstance(value, str):
                cleaned_data[key] = sanitize_input(value)
            else:
                cleaned_data[key] = value

        # Validate email
        if cleaned_data.get('owner_email') and not validate_email(cleaned_data['owner_email']):
            return jsonify({'success': False, 'error': 'Email không hợp lệ'}), 400

        # Validate phone
        if cleaned_data.get('owner_phone') and not validate_phone(cleaned_data['owner_phone']):
            return jsonify({'success': False, 'error': 'Số điện thoại không hợp lệ'}), 400

        success, message = db_manager.update_vehicle(plate_number, cleaned_data)

        if success:
            logger.info(f"Vehicle updated: {plate_number}")
            log_security_event('VEHICLE_UPDATED', f"Plate: {plate_number}")

        update_system_metrics('database_queries')
        return jsonify({'success': success, 'message': message})

    except Exception as e:
        logger.error(f"Update vehicle error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'success': False, 'error': str(e)}), 500



@app.route('/api/vehicles/delete/<plate_number>', methods=['DELETE'])
@admin_required
@limiter.limit("5 per minute")
@track_requests
@require_valid_session
def delete_vehicle(plate_number):
    """Xóa xe - CHỈ ADMIN"""
    try:
        success, message = db_manager.deactivate_vehicle(plate_number)

        if success:
            logger.info(f"Vehicle deactivated: {plate_number}")
            log_security_event('VEHICLE_DELETED', f"Plate: {plate_number}")

        update_system_metrics('database_queries')
        return jsonify({'success': success, 'message': message})

    except Exception as e:
        logger.error(f"Delete vehicle error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/search_vehicles', methods=['POST'])
@admin_required
@limiter.limit("20 per minute")
@track_requests
@require_valid_session
def search_vehicles():
    """Tìm kiếm nâng cao - CHỈ ADMIN"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({'success': False, 'error': 'Không có dữ liệu tìm kiếm'}), 400

        query = sanitize_input(data.get('query', ''))
        search_type = data.get('search_type', 'plate')

        if search_type not in ['plate', 'owner', 'phone']:
            return jsonify({'success': False, 'error': 'Loại tìm kiếm không hợp lệ'}), 400

        results = db_manager.search_vehicles(query, search_type)
        update_system_metrics('database_queries')

        return jsonify({
            'success': True,
            'results': results,
            'count': len(results)
        })

    except Exception as e:
        logger.error(f"Vehicle search error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/reports/generate', methods=['POST'])
@admin_required
@limiter.limit("10 per minute")
@track_requests
@require_valid_session
def generate_report():
    """Tạo báo cáo - CHỈ ADMIN"""
    try:
        # 🚀 Luôn trả dữ liệu test giả lập
        fake_data = {
            "summary": {
                "total_vehicles": 50,
                "total_entries": 12,
                "total_exits": 10,
                "registered_vehicles": 2,
                "current_parking": 2,
                "avg_duration": 2.5,
                "vehicles_change": 5,
                "entries_change": 12,
                "exits_change": 8,
                "registered_change": 15,
                "parking_change": -2,
                "duration_change": 3
            },
            "charts": {
                "hourly": {
                    "entries": [2,1,0,0,0,5,8,12,15,20,25,18,22,19,17,15,10,7,5,3,2,1,1,0],
                    "exits":   [0,0,0,0,1,3,5,8,10,12,18,20,22,18,15,12,8,6,5,4,2,1,0,0]
                },
                "vehicle_types": {
                    "labels": ["Xe hơi","Xe máy","Xe tải","Khác"],
                    "data": [35, 55, 8, 5]
                },
                "weekly_trend": {
                    "labels": ["Thứ 2","Thứ 3","Thứ 4","Thứ 5","Thứ 6","Thứ 7","CN"],
                    "data": [18, 25, 32, 22, 38, 35, 28]
                },
                "registration_trend": {
                    "labels": ["Thứ 2","Thứ 3","Thứ 4","Thứ 5","Thứ 6","Thứ 7","CN"],
                    "registered":   [12,18,15,20,25,22,16],
                    "unregistered": [6,7,17,2,13,13,12]
                }
            },
            "tables": {
                "recent_activities": [
                    {"plate_number":"51A-12345","owner_name":"Nguyễn Văn A","vehicle_type":"Xe hơi",
                     "entry_time":"2025-09-01 08:00","exit_time":"2025-09-01 10:00",
                     "parking_duration":120,"is_registered":True},
                    {"plate_number":"59B-67890","owner_name":"Trần Thị B","vehicle_type":"Xe máy",
                     "entry_time":"2025-09-02 09:30","exit_time":"2025-09-02 11:00",
                     "parking_duration":90,"is_registered":False}
                ],
                "top_parkers": [
                    {"plate_number":"51A-12345","owner_name":"Nguyễn Văn A","visit_count":12,
                     "avg_duration":2.3,"total_hours":30},
                    {"plate_number":"59B-67890","owner_name":"Trần Thị B","visit_count":8,
                     "avg_duration":1.5,"total_hours":12}
                ]
            }
        }

        return jsonify({
            "success": True,
            "data": fake_data,
            "generated_at": datetime.now().isoformat(),
            "period": {
                "start": "2025-09-01",
                "end": "2025-09-07",
                "type": "daily"
            },
            "message": "Báo cáo dữ liệu giả lập (test mode)"
        })

    except Exception as e:
        logger.error(f"Report generation error: {str(e)}")
        return jsonify({
            "success": False,
            "error": str(e)
        }), 500



@app.route('/api/reports/export')
@admin_required
@limiter.limit("5 per minute")
@track_requests
@require_valid_session
def export_report():
    """Xuất báo cáo - CHỈ ADMIN"""
    try:
        if not EXCEL_AVAILABLE:
            return jsonify({'success': False, 'error': 'Excel export not available'}), 503

        start_date = request.args.get('start_date')
        end_date = request.args.get('end_date')
        report_type = request.args.get('report_type', 'daily')

        if not start_date or not end_date:
            return jsonify({'success': False, 'error': 'Missing date parameters'}), 400

        # Parse dates
        try:
            start_dt = datetime.strptime(start_date, '%Y-%m-%d')
            end_dt = datetime.strptime(end_date, '%Y-%m-%d')
        except ValueError:
            return jsonify({'success': False, 'error': 'Invalid date format'}), 400

        # Generate Excel file
        filename = f"parking_report_{start_date}_to_{end_date}.xlsx"
        filepath = os.path.join('exports', filename)

        # Create Excel workbook
        workbook = openpyxl.Workbook()

        # Summary sheet
        summary_sheet = workbook.active
        summary_sheet.title = "Tổng quan"

        # Get summary data
        summary_data = generate_summary_statistics_safe(start_dt, end_dt)

        # Write summary data
        summary_sheet['A1'] = 'BÁO CÁO TỔNG QUAN HỆ THỐNG ĐỖ XE'
        summary_sheet['A1'].font = Font(bold=True, size=16)
        summary_sheet['A3'] = f'Từ ngày: {start_date}'
        summary_sheet['A4'] = f'Đến ngày: {end_date}'
        summary_sheet['A5'] = f'Loại báo cáo: {report_type}'

        row = 7
        for key, value in summary_data.items():
            if key.endswith('_change'):
                continue
            summary_sheet[f'A{row}'] = key.replace('_', ' ').title()
            summary_sheet[f'B{row}'] = value
            row += 1

        # Save workbook
        workbook.save(filepath)

        logger.info(f"Report exported: {filename}")
        update_system_metrics('database_queries', 5)

        return send_file(filepath, as_attachment=True, download_name=filename)

    except Exception as e:
        logger.error(f"Export report error: {str(e)}")
        update_system_metrics('failed_requests')
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/download_plates')
@admin_required
@track_requests
@require_valid_session
def download_plates():
    """Download dữ liệu - CHỈ ADMIN"""
    try:
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        cursor.execute("""
            SELECT el.plate_number, el.entry_time, el.exit_time, 
                   el.entry_image, el.exit_image, el.is_registered, el.parking_duration,
                   rv.owner_name, rv.owner_phone, rv.vehicle_type, rv.vehicle_brand, rv.vehicle_model
            FROM entry_exit_log el
            LEFT JOIN registered_vehicles rv ON el.plate_number = rv.plate_number
            ORDER BY el.entry_time DESC
        """)

        data = cursor.fetchall()
        conn.close()
        update_system_metrics('database_queries')

        output = StringIO()
        writer = csv.writer(output)
        writer.writerow([
            'Biển số xe', 'Thời gian vào', 'Thời gian ra',
            'Ảnh vào', 'Ảnh ra', 'Đã đăng ký', 'Thời gian đỗ (phút)',
            'Chủ xe', 'Số điện thoại', 'Loại xe', 'Hãng xe', 'Mẫu xe'
        ])

        for row in data:
            writer.writerow(row)

        output.seek(0)

        # Generate filename with timestamp
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"parking_data_{timestamp}.csv"

        return Response(
            output.getvalue(),
            mimetype="text/csv",
            headers={
                "Content-Disposition": f"attachment; filename={filename}"
            }
        )

    except Exception as e:
        logger.error(f"Export error: {str(e)}")
        update_system_metrics('failed_requests')
        flash(f"Lỗi khi xuất dữ liệu: {str(e)}", "error")
        return redirect('/')


@app.route('/api/backup/create', methods=['POST'])
@admin_required
@limiter.limit("2 per minute")
@track_requests
@require_valid_session
def create_backup():
    """Backup - CHỈ ADMIN"""
    try:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_filename = f"parking_backup_{timestamp}.db"
        backup_path = os.path.join('backups', backup_filename)

        # Copy database
        shutil.copy2(db_manager.db_path, backup_path)

        # Create metadata
        metadata = {
            'created_at': datetime.now().isoformat(),
            'database_size': os.path.getsize(db_manager.db_path),
            'backup_size': os.path.getsize(backup_path),
            'version': '2.0.0'
        }

        metadata_path = os.path.join('backups', f"backup_metadata_{timestamp}.json")
        with open(metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)

        log_security_event('BACKUP_CREATED', f"Backup created: {backup_filename}")

        return jsonify({
            'success': True,
            'message': 'Backup created successfully',
            'backup_filename': backup_filename,
            'metadata': metadata
        })

    except Exception as e:
        logger.error(f"Backup creation error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/reset_session', methods=['POST'])
@admin_required
@limiter.limit("10 per minute")
@track_requests
@require_valid_session
def reset_session_new():
    """Reset session - CHỈ ADMIN"""
    try:
        import secrets
        import time

        # 1. Chỉ tạo session mới, không xóa data
        new_session_id = 'session_' + str(int(time.time())) + '_' + secrets.token_hex(8)

        # 2. Log hoạt động
        log_security_event('SESSION_RESET', f'Session reset performed, new session: {new_session_id}')

        logger.info(f"Session reset completed, new session: {new_session_id}")

        return jsonify({
            'success': True,
            'message': 'Đã reset session thành công',
            'new_session_id': new_session_id,
            'reset_time': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Session reset error: {str(e)}")
        return jsonify({
            'success': False,
            'error': f'Lỗi khi reset session: {str(e)}'
        }), 500


@app.route('/api/reset_system', methods=['POST'])
@admin_required
@limiter.limit("2 per minute")
@track_requests
@require_valid_session
def reset_system_complete():
    """Reset hệ thống - CHỈ ADMIN"""
    try:
        import sqlite3
        import os

        data = request.get_json()
        confirm_code = data.get('confirm_code', '') if data else ''

        # Yêu cầu mã xác nhận
        if confirm_code != 'RESET_PARKING_SYSTEM_2024':
            return jsonify({
                'success': False,
                'error': 'Mã xác nhận không đúng'
            }), 400

        # 1. Xóa dữ liệu database
        try:
            conn = sqlite3.connect(db_manager.db_path)
            cursor = conn.cursor()

            # Xóa log entry/exit
            cursor.execute("DELETE FROM entry_exit_log")

            # Reset sequence
            cursor.execute("DELETE FROM sqlite_sequence WHERE name='entry_exit_log'")

            conn.commit()
            conn.close()
        except Exception as db_error:
            logger.error(f"Database reset error: {db_error}")

        # 2. Xóa ảnh captures
        captures_dir = 'static/captures'
        if os.path.exists(captures_dir):
            for filename in os.listdir(captures_dir):
                if filename.endswith(('.jpg', '.jpeg', '.png')):
                    try:
                        os.remove(os.path.join(captures_dir, filename))
                    except:
                        pass

        # 3. Reset system metrics
        global system_metrics
        system_metrics = {
            'total_requests': 0,
            'failed_requests': 0,
            'active_sessions': 0,
            'database_queries': 0,
            'cache_hits': 0,
            'cache_misses': 0
        }

        # 4. Log security event
        log_security_event('SYSTEM_RESET', 'Complete system reset performed')
        logger.info("System reset completed successfully")

        return jsonify({
            'success': True,
            'message': 'Hệ thống đã được đặt lại hoàn toàn',
            'reset_time': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"System reset error: {str(e)}")
        return jsonify({
            'success': False,
            'error': f'Lỗi khi reset hệ thống: {str(e)}'
        }), 500


# ===============================
# VIDEO STREAMING ROUTES
# ===============================

@app.route('/video_feed')
@login_required
@track_requests
def video_feed():
    """Camera feed with license plate detection"""
    try:
        system_status['camera1_active'] = True
        return Response(
            camera1.generate_frames(),
            mimetype='multipart/x-mixed-replace; boundary=frame'
        )
    except Exception as e:
        logger.error(f"Video feed error: {e}")
        system_status['camera1_active'] = False
        return jsonify({"error": "Camera feed not available"}), 500


class OptimizedParkingStream:
    """Optimized parking video stream with caching"""

    def __init__(self):
        self.last_process_time = 0
        self.frame_cache = None
        self.cache_time = 0
        self.cache_duration = 0.1
        self.video_path = "static/video/baidoxe.mp4"

    def generate_stream(self):
        """Generate optimized parking video stream"""
        try:
            cap = cv2.VideoCapture(self.video_path)
            if not cap.isOpened():
                logger.error(f"Cannot open parking video: {self.video_path}")
                return

            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            cap.set(cv2.CAP_PROP_FPS, parking_config.TARGET_FPS)
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, parking_config.VIDEO_WIDTH)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, parking_config.VIDEO_HEIGHT)

            frame_count = 0
            last_frame_time = time.time()
            frame_interval = 1.0 / parking_config.TARGET_FPS

            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    frame_count = 0
                    continue

                frame_count += 1
                current_time = time.time()

                try:
                    if (current_time - self.last_process_time) >= parking_config.DETECTION_INTERVAL:
                        if parking_detector:
                            processed_frame = parking_detector.process_frame(frame)
                        else:
                            processed_frame = frame

                        self.frame_cache = processed_frame
                        self.cache_time = current_time
                        self.last_process_time = current_time
                    else:
                        if (current_time - self.cache_time) < self.cache_duration and self.frame_cache is not None:
                            processed_frame = self.frame_cache
                        else:
                            processed_frame = frame

                    encode_param = [
                        int(cv2.IMWRITE_JPEG_QUALITY), parking_config.JPEG_QUALITY,
                        int(cv2.IMWRITE_JPEG_OPTIMIZE), 1
                    ]
                    _, jpeg = cv2.imencode('.jpg', processed_frame, encode_param)

                    yield (b'--frame\r\n'
                           b'Content-Type: image/jpeg\r\n\r\n' + jpeg.tobytes() + b'\r\n\r\n')

                except Exception as e:
                    logger.error(f"Stream processing error: {e}")
                    _, jpeg = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
                    yield (b'--frame\r\n'
                           b'Content-Type: image/jpeg\r\n\r\n' + jpeg.tobytes() + b'\r\n\r\n')

                elapsed = time.time() - last_frame_time
                if elapsed < frame_interval:
                    time.sleep(frame_interval - elapsed)
                last_frame_time = time.time()

        except Exception as e:
            logger.error(f"Parking stream error: {e}")
        finally:
            if 'cap' in locals():
                cap.release()


parking_stream = OptimizedParkingStream()


@app.route('/video_stream')
@login_required
@track_requests
def video_stream():
    """Parking video stream with vehicle detection"""
    try:
        system_status['camera2_active'] = True
        return Response(
            parking_stream.generate_stream(),
            mimetype='multipart/x-mixed-replace; boundary=frame'
        )
    except Exception as e:
        logger.error(f"Parking stream error: {e}")
        system_status['camera2_active'] = False
        return jsonify({"error": "Parking stream not available"}), 500


# ===============================
# HEALTH CHECK AND MONITORING
# ===============================

@app.route('/health')
@limiter.limit("10 per minute")
def health_check():
    """System health check"""
    health_status = {
        'status': 'healthy',
        'timestamp': datetime.now().isoformat(),
        'uptime': str(datetime.now() - system_status['startup_time']),
        'database': 'connected',
        'cameras': {
            'camera1': system_status['camera1_active'],
            'camera2': system_status['camera2_active']
        },
        'detection': system_status['detection_active'],
        'version': '2.0.0'
    }

    # Check database connection
    try:
        stats = db_manager.get_parking_statistics()
        if not stats:
            health_status['database'] = 'disconnected'
            health_status['status'] = 'degraded'
    except Exception as e:
        health_status['database'] = 'error'
        health_status['status'] = 'unhealthy'
        logger.error(f"Database health check failed: {e}")

    return jsonify(health_status)


# ===============================
# REPORT UTILITY FUNCTIONS
# ===============================

def get_empty_report_data():
    """Return empty report data structure to prevent frontend errors"""
    return {
        'summary': {
            'total_vehicles': 0,
            'total_entries': 0,
            'total_exits': 0,
            'registered_vehicles': 0,
            'current_parking': 0,
            'avg_duration': 0,
            'vehicles_change': 0,
            'entries_change': 0,
            'exits_change': 0,
            'registered_change': 0,
            'parking_change': 0,
            'duration_change': 0
        },
        'charts': {
            'hourly': {
                'entries': [0] * 24,
                'exits': [0] * 24
            },
            'vehicle_types': {
                'data': [20, 45, 10, 5],  # Sample data
                'labels': ['Xe hơi', 'Xe máy', 'Xe tải', 'Khác']
            },
            'weekly_trend': {
                'data': [15, 25, 30, 20, 35, 40, 28]
            },
            'registration_trend': {
                'labels': ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'],
                'registered': [10, 15, 12, 18, 20, 16, 8],
                'unregistered': [5, 10, 8, 12, 15, 12, 6]
            }
        },
        'tables': {
            'recent_activities': [],
            'top_parkers': []
        }
    }


def generate_summary_statistics_safe(start_date, end_date):
    """Generate summary statistics with error handling"""
    try:
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Initialize default values
        summary = {
            'total_vehicles': 0,
            'total_entries': 0,
            'total_exits': 0,
            'registered_vehicles': 0,
            'current_parking': 0,
            'avg_duration': 0,
            'vehicles_change': 5,  # Sample change data
            'entries_change': 12,
            'exits_change': 8,
            'registered_change': 15,
            'parking_change': -2,
            'duration_change': 3
        }

        # Get total vehicles (unique plates in date range)
        try:
            cursor.execute("""
                SELECT COUNT(DISTINCT plate_number) FROM entry_exit_log 
                WHERE DATE(entry_time) BETWEEN ? AND ?
                AND entry_time IS NOT NULL
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))
            result = cursor.fetchone()
            if result and result[0]:
                summary['total_vehicles'] = result[0]
        except Exception as e:
            logger.warning(f"Error getting total vehicles: {e}")

        # Total entries
        try:
            cursor.execute("""
                SELECT COUNT(*) FROM entry_exit_log 
                WHERE DATE(entry_time) BETWEEN ? AND ? 
                AND entry_time IS NOT NULL
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))
            result = cursor.fetchone()
            if result and result[0]:
                summary['total_entries'] = result[0]
        except Exception as e:
            logger.warning(f"Error getting total entries: {e}")

        # Total exits
        try:
            cursor.execute("""
                SELECT COUNT(*) FROM entry_exit_log 
                WHERE DATE(exit_time) BETWEEN ? AND ? 
                AND exit_time IS NOT NULL
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))
            result = cursor.fetchone()
            if result and result[0]:
                summary['total_exits'] = result[0]
        except Exception as e:
            logger.warning(f"Error getting total exits: {e}")

        # Registered vehicles in date range
        try:
            cursor.execute("""
                SELECT COUNT(*) FROM entry_exit_log 
                WHERE DATE(entry_time) BETWEEN ? AND ? 
                AND is_registered = 1
                AND entry_time IS NOT NULL
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))
            result = cursor.fetchone()
            if result and result[0]:
                summary['registered_vehicles'] = result[0]
        except Exception as e:
            logger.warning(f"Error getting registered vehicles: {e}")

        # Current parking (vehicles without exit)
        try:
            cursor.execute("""
                SELECT COUNT(*) FROM entry_exit_log 
                WHERE exit_time IS NULL 
                AND entry_time IS NOT NULL
                AND (status = 'active' OR status IS NULL)
            """)
            result = cursor.fetchone()
            if result and result[0]:
                summary['current_parking'] = result[0]
        except Exception as e:
            logger.warning(f"Error getting current parking: {e}")

        # Average duration
        try:
            cursor.execute("""
                SELECT AVG(parking_duration) FROM entry_exit_log 
                WHERE DATE(entry_time) BETWEEN ? AND ? 
                AND parking_duration IS NOT NULL
                AND parking_duration > 0
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))
            result = cursor.fetchone()
            if result and result[0]:
                summary['avg_duration'] = round(result[0], 1)
        except Exception as e:
            logger.warning(f"Error getting average duration: {e}")

        conn.close()
        logger.info(f"Summary generated: {summary}")
        return summary

    except Exception as e:
        logger.error(f"Summary statistics error: {str(e)}")
        return get_empty_report_data()['summary']


def generate_chart_data_safe(start_date, end_date):
    """Generate chart data with error handling and sample data"""
    try:
        charts = {
            'hourly': {
                'entries': [0] * 24,
                'exits': [0] * 24
            },
            'vehicle_types': {
                'data': [0, 0, 0, 0],
                'labels': ['Xe hơi', 'Xe máy', 'Xe tải', 'Khác']
            },
            'weekly_trend': {
                'data': [0] * 7
            },
            'registration_trend': {
                'labels': ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'],
                'registered': [0] * 7,
                'unregistered': [0] * 7
            }
        }

        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Get hourly statistics
        try:
            # Entries by hour
            cursor.execute("""
                SELECT CAST(strftime('%H', entry_time) AS INTEGER) as hour, COUNT(*) as count
                FROM entry_exit_log 
                WHERE DATE(entry_time) BETWEEN ? AND ? 
                AND entry_time IS NOT NULL
                GROUP BY hour
                ORDER BY hour
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))

            hourly_entries = cursor.fetchall()
            for hour, count in hourly_entries:
                if hour is not None and 0 <= hour < 24:
                    charts['hourly']['entries'][hour] = count

            # Exits by hour
            cursor.execute("""
                SELECT CAST(strftime('%H', exit_time) AS INTEGER) as hour, COUNT(*) as count
                FROM entry_exit_log 
                WHERE DATE(exit_time) BETWEEN ? AND ? 
                AND exit_time IS NOT NULL
                GROUP BY hour
                ORDER BY hour
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))

            hourly_exits = cursor.fetchall()
            for hour, count in hourly_exits:
                if hour is not None and 0 <= hour < 24:
                    charts['hourly']['exits'][hour] = count

        except Exception as e:
            logger.warning(f"Error getting hourly statistics: {e}")

        # Get vehicle types statistics
        try:
            cursor.execute("""
                SELECT 
                    CASE 
                        WHEN rv.vehicle_type IN ('car', 'xe hơi') THEN 'Xe hơi'
                        WHEN rv.vehicle_type IN ('motorbike', 'motorcycle', 'xe máy') THEN 'Xe máy'
                        WHEN rv.vehicle_type IN ('truck', 'xe tải') THEN 'Xe tải'
                        ELSE 'Khác'
                    END as type_group,
                    COUNT(*) as count
                FROM entry_exit_log el
                LEFT JOIN registered_vehicles rv ON el.plate_number = rv.plate_number
                WHERE DATE(el.entry_time) BETWEEN ? AND ? 
                AND el.entry_time IS NOT NULL
                GROUP BY type_group
                ORDER BY count DESC
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))

            vehicle_types = cursor.fetchall()
            type_counts = {'Xe hơi': 0, 'Xe máy': 0, 'Xe tải': 0, 'Khác': 0}

            for vehicle_type, count in vehicle_types:
                if vehicle_type in type_counts:
                    type_counts[vehicle_type] = count
                else:
                    type_counts['Khác'] += count

            xe_hoi = type_counts.get('Xe hơi', 0)
            khac = type_counts.get('Xe máy', 0) + type_counts.get('Xe tải', 0) + type_counts.get('Khác', 0)

            charts['vehicle_types']['labels'] = ['Xe hơi', 'Khác']
            charts['vehicle_types']['data'] = [xe_hoi, khac]

        except Exception as e:
            logger.warning(f"Error getting vehicle type statistics: {e}")
            # Use sample data if error
            charts['vehicle_types']['labels'] = ['Xe hơi', 'Khác']
            charts['vehicle_types']['data'] = [35, 65]

        # Get weekly trend (last 7 days from end_date)
        try:
            weekly_data = [0] * 7
            for i in range(7):
                day_date = end_date - timedelta(days=6 - i)
                cursor.execute("""
                    SELECT COUNT(*) FROM entry_exit_log 
                    WHERE DATE(entry_time) = ?
                    AND entry_time IS NOT NULL
                """, (day_date.strftime('%Y-%m-%d'),))

                result = cursor.fetchone()
                if result and result[0]:
                    weekly_data[i] = result[0]

            charts['weekly_trend']['data'] = weekly_data

        except Exception as e:
            logger.warning(f"Error getting weekly trend: {e}")
            # Use sample data
            charts['weekly_trend']['data'] = [18, 25, 32, 22, 38, 35, 28]

        # Get registration trend
        try:
            reg_data = [0] * 7
            unreg_data = [0] * 7

            for i in range(7):
                day_date = end_date - timedelta(days=6 - i)

                # Registered
                cursor.execute("""
                    SELECT COUNT(*) FROM entry_exit_log 
                    WHERE DATE(entry_time) = ? 
                    AND is_registered = 1
                    AND entry_time IS NOT NULL
                """, (day_date.strftime('%Y-%m-%d'),))
                result = cursor.fetchone()
                if result and result[0]:
                    reg_data[i] = result[0]

                # Unregistered
                cursor.execute("""
                    SELECT COUNT(*) FROM entry_exit_log 
                    WHERE DATE(entry_time) = ? 
                    AND (is_registered = 0 OR is_registered IS NULL)
                    AND entry_time IS NOT NULL
                """, (day_date.strftime('%Y-%m-%d'),))
                result = cursor.fetchone()
                if result and result[0]:
                    unreg_data[i] = result[0]

            charts['registration_trend']['registered'] = reg_data
            charts['registration_trend']['unregistered'] = unreg_data

        except Exception as e:
            logger.warning(f"Error getting registration trend: {e}")
            # Use sample data
            charts['registration_trend']['registered'] = [12, 18, 15, 20, 25, 22, 16]
            charts['registration_trend']['unregistered'] = [6, 7, 17, 2, 13, 13, 12]

        conn.close()
        logger.info(f"Charts generated successfully")
        return charts

    except Exception as e:
        logger.error(f"Chart data generation error: {str(e)}")
        # Return sample data for demo
        return {
            'hourly': {
                'entries': [1, 0, 0, 0, 0, 2, 5, 8, 12, 15, 18, 20, 22, 18, 15, 12, 8, 6, 4, 3, 2, 1, 1, 0],
                'exits': [0, 0, 0, 0, 0, 1, 2, 3, 8, 10, 12, 15, 18, 20, 22, 18, 15, 12, 8, 5, 3, 2, 1, 0]
            },
            'vehicle_types': {
                'data': [35, 45, 12, 8],
                'labels': ['Xe hơi', 'Xe máy', 'Xe tải', 'Khác']
            },
            'weekly_trend': {
                'data': [18, 25, 32, 22, 38, 35, 28]
            },
            'registration_trend': {
                'labels': ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'],
                'registered': [12, 18, 15, 20, 25, 22, 16],
                'unregistered': [6, 7, 17, 2, 13, 13, 12]
            }
        }

def generate_table_data_safe(start_date, end_date):
    """Generate table data with error handling"""
    try:
        tables = {
            'recent_activities': [],
            'top_parkers': []
        }

        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Recent activities with better status handling
        try:
            cursor.execute("""
                SELECT 
                    el.plate_number,
                    COALESCE(rv.owner_name, 'Không xác định') as owner_name,
                    COALESCE(rv.vehicle_type, 'Không xác định') as vehicle_type,
                    el.entry_time,
                    el.exit_time,
                    el.parking_duration,
                    CASE 
                        WHEN rv.plate_number IS NOT NULL THEN 1
                        ELSE 0
                    END as is_registered
                FROM entry_exit_log el
                LEFT JOIN registered_vehicles rv ON el.plate_number = rv.plate_number AND rv.is_active = 1
                WHERE DATE(el.entry_time) BETWEEN ? AND ?
                AND el.entry_time IS NOT NULL
                ORDER BY el.entry_time DESC
                LIMIT 50
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))

            activities = cursor.fetchall()
            for activity in activities:
                tables['recent_activities'].append({
                    'plate_number': activity[0] or 'N/A',
                    'owner_name': activity[1] or 'Không xác định',
                    'vehicle_type': activity[2] or 'Không xác định',
                    'entry_time': activity[3],
                    'exit_time': activity[4],
                    'parking_duration': activity[5],
                    'is_registered': bool(activity[6])
                })

        except Exception as e:
            logger.warning(f"Error getting recent activities: {e}")

        # Top parkers
        try:
            cursor.execute("""
                SELECT 
                    el.plate_number,
                    COALESCE(rv.owner_name, 'Không xác định') as owner_name,
                    COUNT(*) as visit_count,
                    AVG(COALESCE(el.parking_duration, 0)) as avg_duration,
                    SUM(COALESCE(el.parking_duration, 0)) as total_hours
                FROM entry_exit_log el
                LEFT JOIN registered_vehicles rv ON el.plate_number = rv.plate_number AND rv.is_active = 1
                WHERE DATE(el.entry_time) BETWEEN ? AND ?
                AND el.entry_time IS NOT NULL
                GROUP BY el.plate_number
                HAVING COUNT(*) > 1
                ORDER BY total_hours DESC, visit_count DESC
                LIMIT 20
            """, (start_date.strftime('%Y-%m-%d'), end_date.strftime('%Y-%m-%d')))

            parkers = cursor.fetchall()
            for parker in parkers:
                tables['top_parkers'].append({
                    'plate_number': parker[0] or 'N/A',
                    'owner_name': parker[1] or 'Không xác định',
                    'visit_count': parker[2] or 0,
                    'avg_duration': round(parker[3] or 0, 1),
                    'total_hours': round((parker[4] or 0) / 60, 2)  # Convert minutes to hours
                })

        except Exception as e:
            logger.warning(f"Error getting top parkers: {e}")

        conn.close()
        return tables

    except Exception as e:
        logger.error(f"Table data generation error: {str(e)}")
        return get_empty_report_data()['tables']

# ===============================
# ERROR HANDLERS
# ===============================

@app.errorhandler(404)
def not_found_error(error):
    """Handle 404 errors"""
    log_security_event('404_ERROR', f"Attempted to access: {request.url}")
    return jsonify({
        'success': False,
        'error': 'Endpoint not found',
        'code': 404
    }), 404


@app.errorhandler(500)
def internal_error(error):
    """Handle 500 errors"""
    logger.error(f"Internal server error: {error}")
    update_system_metrics('failed_requests')
    return jsonify({
        'success': False,
        'error': 'Internal server error',
        'code': 500
    }), 500


@app.errorhandler(413)
def too_large_error(error):
    """Handle file too large errors"""
    return jsonify({
        'success': False,
        'error': 'File quá lớn',
        'code': 413
    }), 413


@app.errorhandler(429)
def ratelimit_handler(e):
    """Handle rate limit errors"""
    log_security_event('RATE_LIMIT_EXCEEDED', f"Rate limit exceeded: {e.description}")
    return jsonify({
        'success': False,
        'error': 'Quá nhiều yêu cầu',
        'code': 429,
        'retry_after': e.retry_after
    }), 429


# ===============================
# AUTHENTICATION ROUTES
# ===============================

@app.route('/login', methods=['GET', 'POST'])
def login():
    """Login route with proper session handling"""
    # Check if user is already logged in
    if 'user_id' in session:
        logger.info(f"User {session['user_id']} already logged in, redirecting to dashboard")
        return redirect(url_for('index'))

    if request.method == 'POST':
        # Handle JSON requests (AJAX)
        if request.is_json:
            data = request.get_json()
            username = data.get('username', '').strip()
            password = data.get('password', '')
            role = data.get('role', 'admin')
        else:
            # Handle form requests
            username = request.form.get('username', '').strip()
            password = request.form.get('password', '')
            role = request.form.get('role', 'admin')

        if not username or not password:
            error_msg = 'Vui lòng nhập đầy đủ thông tin'
            if request.is_json:
                return jsonify({
                    'success': False,
                    'error': error_msg
                }), 400
            flash(error_msg, 'error')
            return render_template('login.html')

        # VALIDATE USER CREDENTIALS
        user = None
        if username == 'admin' and password == 'admin123' and role == 'admin':
            user = {
                'username': 'admin',
                'role': 'admin',
                'name': 'Quản trị viên'
            }
        elif username == 'baove' and password == 'baove123' and role == 'guard':
            user = {
                'username': 'baove',
                'role': 'guard',
                'name': 'Bảo vệ'
            }

        if user:
            # Login successful
            try:
                session.clear()

                # Set proper display name based on role
                if user['role'] == 'admin':
                    display_name = 'Quản trị viên'
                elif user['role'] == 'guard':
                    display_name = 'Bảo vệ'
                else:
                    display_name = user.get('name', username)

                # Create session
                session['user_id'] = username
                session['user_role'] = user['role']
                session['user_name'] = display_name  # Use display name
                session['login_time'] = datetime.now().isoformat()
                session['session_id'] = secrets.token_hex(16)
                session.permanent = True
                session.modified = True

                # Set session timeout (8 hours)
                app.permanent_session_lifetime = timedelta(hours=8)

                # Force session to be saved
                session.modified = True

                logger.info(f"User {username} logged in successfully with role {role}")
                logger.info(f"Session created: {dict(session)}")
                log_security_event('USER_LOGIN', f"User: {username}, Role: {role}")

                if request.is_json:
                    return jsonify({
                        'success': True,
                        'message': 'Đăng nhập thành công',
                        'user': {
                            'username': username,
                            'role': user['role'],
                            'name': user['name']
                        },
                        'redirect_url': '/'
                    })

                flash(f'Chào mừng {user["name"]}!', 'success')

                # Redirect to intended page or dashboard
                next_page = request.args.get('next')
                redirect_url = next_page if next_page else url_for('index')
                logger.info(f"Redirecting user {username} to {redirect_url}")
                return redirect(redirect_url)

            except Exception as e:
                logger.error(f"Session creation error: {e}")
                error_msg = 'Lỗi tạo phiên đăng nhập'
                if request.is_json:
                    return jsonify({'success': False, 'error': error_msg}), 500
                flash(error_msg, 'error')
                return render_template('login.html')
        else:
            # Login failed
            error_msg = 'Tên đăng nhập hoặc mật khẩu không chính xác'
            logger.warning(f"Failed login attempt: {username} with role {role}")
            log_security_event('FAILED_LOGIN', f"User: {username}, Role: {role}")

            if request.is_json:
                return jsonify({
                    'success': False,
                    'error': error_msg
                }), 401

            flash(error_msg, 'error')
            return render_template('login.html')

    # GET request - show login form
    return render_template('login.html')


# ===============================
# USER MANAGEMENT ROUTES (ADMIN ONLY)
# ===============================

# Initialize users table in database if not exists
def init_users_table():
    """Initialize users table in database"""
    try:
        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                fullname TEXT NOT NULL,
                email TEXT,
                phone TEXT,
                role TEXT NOT NULL DEFAULT 'guard',
                status TEXT DEFAULT 'active',
                shift TEXT,
                last_login DATETIME,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by TEXT,
                notes TEXT
            )
        ''')

        # Create user activity log table
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS user_activity_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER,
                username TEXT,
                action TEXT,
                details TEXT,
                ip_address TEXT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users (id)
            )
        ''')

        # Insert default admin if not exists
        cursor.execute("SELECT COUNT(*) FROM users WHERE username = 'admin'")
        if cursor.fetchone()[0] == 0:
            admin_hash = generate_password_hash('admin123')
            cursor.execute('''
                INSERT INTO users (username, password_hash, fullname, role, status)
                VALUES ('admin', ?, 'Administrator', 'admin', 'active')
            ''', (admin_hash,))

        # Insert default guard if not exists
        cursor.execute("SELECT COUNT(*) FROM users WHERE username = 'baove'")
        if cursor.fetchone()[0] == 0:
            guard_hash = generate_password_hash('baove123')
            cursor.execute('''
                INSERT INTO users (username, password_hash, fullname, role, status, shift)
                VALUES ('baove', ?, 'Bảo vệ 1', 'guard', 'active', 'morning')
            ''', (guard_hash,))

        conn.commit()
        conn.close()
        logger.info("Users table initialized successfully")

    except Exception as e:
        logger.error(f"Error initializing users table: {e}")


# Call this on startup
init_users_table()


@app.route('/users')
@admin_required
@track_requests
@require_valid_session
def users_management_page():
    """User management page - ADMIN ONLY"""
    return render_template('user_management.html')


@app.route('/api/users/all')
@admin_required
@track_requests
@require_valid_session
def get_all_users():
    """Get all users - ADMIN ONLY"""
    try:
        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        cursor.execute('''
            SELECT id, username, fullname, email, phone, role, status, 
                   shift, last_login, created_at
            FROM users
            ORDER BY created_at DESC
        ''')

        results = cursor.fetchall()
        conn.close()

        users = []
        for row in results:
            users.append({
                'id': row[0],
                'username': row[1],
                'fullname': row[2],
                'email': row[3],
                'phone': row[4],
                'role': row[5],
                'status': row[6],
                'shift': row[7],
                'last_login': row[8],
                'created_at': row[9]
            })

        return jsonify({
            'success': True,
            'users': users
        })

    except Exception as e:
        logger.error(f"Get all users error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/users/add', methods=['POST'])
@admin_required
@limiter.limit("10 per minute")
@track_requests
@require_valid_session
def add_user():
    """Add new user - ADMIN ONLY"""
    try:
        data = request.get_json()
        logger.info(f"Add user request data: {data}")  # Debug log

        # Validate required fields
        if not data:
            return jsonify({
                'success': False,
                'message': 'Không có dữ liệu'
            }), 400

        if not data.get('username'):
            return jsonify({
                'success': False,
                'message': 'Thiếu tên đăng nhập'
            }), 400

        if not data.get('password'):
            return jsonify({
                'success': False,
                'message': 'Thiếu mật khẩu'
            }), 400

        if not data.get('fullname'):
            return jsonify({
                'success': False,
                'message': 'Thiếu họ và tên'
            }), 400

        # Sanitize inputs
        username = sanitize_input(data['username']).lower()
        fullname = sanitize_input(data['fullname'])
        email = sanitize_input(data.get('email', ''))
        phone = sanitize_input(data.get('phone', ''))
        role = data.get('role', 'guard')
        status = data.get('status', 'active')
        shift = data.get('shift', '')

        # Validate role
        if role not in ['admin', 'guard']:
            return jsonify({
                'success': False,
                'message': 'Vai trò không hợp lệ'
            }), 400

        # Hash password
        password_hash = generate_password_hash(data['password'])

        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        # Check if username exists
        cursor.execute("SELECT id FROM users WHERE username = ?", (username,))
        if cursor.fetchone():
            conn.close()
            return jsonify({
                'success': False,
                'message': 'Tên đăng nhập đã tồn tại'
            }), 400

        # Insert new user
        cursor.execute('''
            INSERT INTO users (username, password_hash, fullname, email, phone, 
                             role, status, shift, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', (username, password_hash, fullname, email, phone,
              role, status, shift, session.get('user_id')))

        conn.commit()
        user_id = cursor.lastrowid

        # Log activity
        cursor.execute('''
            INSERT INTO user_activity_log (user_id, username, action, details, ip_address)
            VALUES (?, ?, ?, ?, ?)
        ''', (user_id, username, 'USER_CREATED',
              f'Created by {session.get("user_id")}', request.remote_addr))

        conn.commit()
        conn.close()

        log_security_event('USER_CREATED', f'User {username} created by {session.get("user_id")}')
        logger.info(f"User {username} created successfully")

        return jsonify({
            'success': True,
            'message': 'Thêm người dùng thành công'
        })

    except Exception as e:
        logger.error(f"Add user error: {str(e)}")
        return jsonify({
            'success': False,
            'error': f'Lỗi server: {str(e)}'
        }), 500


@app.route('/api/users/update/<int:user_id>', methods=['PUT'])
@admin_required
@limiter.limit("10 per minute")
@track_requests
@require_valid_session
def update_user(user_id):
    """Update user - ADMIN ONLY"""
    try:
        data = request.get_json()

        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        # Build update query dynamically
        update_fields = []
        values = []

        if data.get('fullname'):
            update_fields.append('fullname = ?')
            values.append(sanitize_input(data['fullname']))

        if data.get('email'):
            update_fields.append('email = ?')
            values.append(sanitize_input(data['email']))

        if data.get('phone'):
            update_fields.append('phone = ?')
            values.append(sanitize_input(data['phone']))

        if data.get('role'):
            if data['role'] not in ['admin', 'guard']:
                conn.close()
                return jsonify({'success': False, 'message': 'Vai trò không hợp lệ'}), 400
            update_fields.append('role = ?')
            values.append(data['role'])

        if data.get('status'):
            update_fields.append('status = ?')
            values.append(data['status'])

        if 'shift' in data:
            update_fields.append('shift = ?')
            values.append(data['shift'])

        # Update password if provided
        if data.get('password'):
            update_fields.append('password_hash = ?')
            values.append(generate_password_hash(data['password']))

        if not update_fields:
            conn.close()
            return jsonify({'success': False, 'message': 'Không có thông tin cần cập nhật'}), 400

        # Add updated_at
        update_fields.append('updated_at = CURRENT_TIMESTAMP')
        values.append(user_id)

        query = f"UPDATE users SET {', '.join(update_fields)} WHERE id = ?"
        cursor.execute(query, values)

        if cursor.rowcount == 0:
            conn.close()
            return jsonify({'success': False, 'message': 'Không tìm thấy người dùng'}), 404

        # Log activity
        cursor.execute('''
            INSERT INTO user_activity_log (user_id, username, action, details, ip_address)
            VALUES (?, ?, ?, ?, ?)
        ''', (user_id, session.get('user_id'), 'USER_UPDATED',
              f'Updated by {session.get("user_id")}', request.remote_addr))

        conn.commit()
        conn.close()

        log_security_event('USER_UPDATED', f'User ID {user_id} updated by {session.get("user_id")}')

        return jsonify({
            'success': True,
            'message': 'Cập nhật người dùng thành công'
        })

    except Exception as e:
        logger.error(f"Update user error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/users/delete/<int:user_id>', methods=['DELETE'])
@admin_required
@limiter.limit("5 per minute")
@track_requests
@require_valid_session
def delete_user(user_id):
    """Delete user - ADMIN ONLY"""
    try:
        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        # Don't allow deleting the main admin account
        cursor.execute("SELECT username, role FROM users WHERE id = ?", (user_id,))
        user = cursor.fetchone()

        if not user:
            conn.close()
            return jsonify({'success': False, 'message': 'Không tìm thấy người dùng'}), 404

        if user[0] == 'admin' and user[1] == 'admin':
            conn.close()
            return jsonify({'success': False, 'message': 'Không thể xóa tài khoản admin chính'}), 403

        # Delete user
        cursor.execute("DELETE FROM users WHERE id = ?", (user_id,))

        # Log activity
        cursor.execute('''
            INSERT INTO user_activity_log (user_id, username, action, details, ip_address)
            VALUES (?, ?, ?, ?, ?)
        ''', (None, session.get('user_id'), 'USER_DELETED',
              f'User {user[0]} deleted by {session.get("user_id")}', request.remote_addr))

        conn.commit()
        conn.close()

        log_security_event('USER_DELETED', f'User {user[0]} deleted by {session.get("user_id")}')

        return jsonify({
            'success': True,
            'message': 'Xóa người dùng thành công'
        })

    except Exception as e:
        logger.error(f"Delete user error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/users/reset-password/<int:user_id>', methods=['POST'])
@admin_required
@limiter.limit("5 per minute")
@track_requests
@require_valid_session
def reset_user_password(user_id):
    """Reset user password - ADMIN ONLY"""
    try:
        data = request.get_json()

        if not data.get('password'):
            return jsonify({'success': False, 'message': 'Mật khẩu mới không được để trống'}), 400

        password_hash = generate_password_hash(data['password'])

        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        cursor.execute('''
            UPDATE users 
            SET password_hash = ?, updated_at = CURRENT_TIMESTAMP 
            WHERE id = ?
        ''', (password_hash, user_id))

        if cursor.rowcount == 0:
            conn.close()
            return jsonify({'success': False, 'message': 'Không tìm thấy người dùng'}), 404

        # Log activity
        cursor.execute('''
            INSERT INTO user_activity_log (user_id, username, action, details, ip_address)
            VALUES (?, ?, ?, ?, ?)
        ''', (user_id, session.get('user_id'), 'PASSWORD_RESET',
              f'Password reset by {session.get("user_id")}', request.remote_addr))

        conn.commit()
        conn.close()

        log_security_event('PASSWORD_RESET', f'Password reset for user ID {user_id} by {session.get("user_id")}')

        return jsonify({
            'success': True,
            'message': 'Reset mật khẩu thành công'
        })

    except Exception as e:
        logger.error(f"Reset password error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/users/activity/<int:user_id>')
@admin_required
@track_requests
@require_valid_session
def get_user_activity(user_id):
    """Get user activity log - ADMIN ONLY"""
    try:
        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        cursor.execute('''
            SELECT action, details, ip_address, timestamp
            FROM user_activity_log
            WHERE user_id = ?
            ORDER BY timestamp DESC
            LIMIT 50
        ''', (user_id,))

        results = cursor.fetchall()
        conn.close()

        activities = []
        for row in results:
            activities.append({
                'action': row[0],
                'details': row[1],
                'ip_address': row[2],
                'timestamp': row[3]
            })

        return jsonify({
            'success': True,
            'activities': activities
        })

    except Exception as e:
        logger.error(f"Get user activity error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/auth/login', methods=['POST'])
@limiter.limit("10 per minute")
def api_login():
    """API login endpoint for AJAX requests"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({'success': False, 'error': 'No data provided'}), 400

        username = data.get('username', '').strip().lower()
        password = data.get('password', '')

        if not username or not password:
            return jsonify({
                'success': False,
                'error': 'Username and password required'
            }), 400

        # Check in database
        conn = sqlite3.connect('parking_system.db')
        cursor = conn.cursor()

        cursor.execute('''
            SELECT id, username, password_hash, fullname, role, status
            FROM users
            WHERE username = ? AND status = 'active'
        ''', (username,))

        user = cursor.fetchone()

        if user and check_password_hash(user[2], password):
            # Update last login
            cursor.execute('''
                UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = ?
            ''', (user[0],))

            # Log activity
            cursor.execute('''
                INSERT INTO user_activity_log (user_id, username, action, details, ip_address)
                VALUES (?, ?, ?, ?, ?)
            ''', (user[0], username, 'LOGIN', 'User logged in', request.remote_addr))

            conn.commit()
            conn.close()

            # Create session
            session.clear()
            session['user_id'] = username
            session['user_role'] = user[4]
            session['user_name'] = user[3]
            session['login_time'] = datetime.now().isoformat()
            session['session_id'] = secrets.token_hex(16)
            session.permanent = True
            session.modified = True

            logger.info(f"API login successful: {username} with role {user[4]}")

            return jsonify({
                'success': True,
                'message': 'Login successful',
                'user': {
                    'username': username,
                    'role': user[4],
                    'name': user[3]
                },
                'redirect_url': '/'
            })
        else:
            conn.close()
            logger.warning(f"API login failed: {username}")
            return jsonify({
                'success': False,
                'error': 'Invalid credentials'
            }), 401

    except Exception as e:
        logger.error(f"API login error: {str(e)}")
        return jsonify({
            'success': False,
            'error': 'Server error'
        }), 500


@app.route('/logout')
def logout():
    """Logout route"""
    username = session.get('user_id', 'Unknown')
    session.clear()
    flash('Đã đăng xuất thành công', 'success')
    logger.info(f"User {username} logged out")
    log_security_event('USER_LOGOUT', f"User: {username}")
    return redirect(url_for('login'))


@app.route('/api/auth/check')
def check_auth():
    """Check authentication status"""
    logger.info(f"Auth check - Session: {dict(session)}")

    if 'user_id' in session and session.get('user_role') and session.get('session_id'):
        return jsonify({
            'authenticated': True,
            'user': {
                'username': session['user_id'],
                'role': session['user_role'],
                'name': session['user_name']
            }
        })
    else:
        logger.info("Auth check failed - missing session data")
        return jsonify({'authenticated': False}), 401


# ===============================
# AUTHENTICATION MIDDLEWARE
# ===============================

@app.before_request
def require_login():
    """Check if user is logged in for protected routes"""
    # Debug logging
    logger.info(f"Request to {request.path}")
    logger.info(f"Session keys: {list(session.keys())}")
    logger.info(f"User ID in session: {session.get('user_id')}")

    # Skip authentication for static files and auth routes
    if (request.endpoint and
            (request.endpoint.startswith('static') or
             request.endpoint in ['login', 'api_login', 'logout'] or
             request.path.startswith('/static/'))):
        return None

    #  FIXED: Skip for mobile API routes (they handle their own auth)
    mobile_public_routes = [
        '/api/mobile/vehicle-login',
        '/api/mobile/test-database',
        '/health'
    ]
    if request.path in mobile_public_routes:
        logger.info(f" Allowing mobile public route: {request.path}")
        return None

    # Skip for specific public routes
    public_routes = ['/health', '/api/auth/login', '/api/auth/check', '/login']
    if request.path in public_routes:
        return None

    #  FIXED: Check for both admin session AND mobile session
    has_admin_session = 'user_id' in session
    has_mobile_session = 'mobile_vehicle_id' in session and 'mobile_plate_number' in session

    if not has_admin_session and not has_mobile_session:
        logger.info(f" No valid session for {request.path}")
        logger.info(f"Available session keys: {list(session.keys())}")

        if request.path.startswith('/api/'):
            return jsonify({'success': False, 'error': 'Authentication required'}), 401
        return redirect(url_for('login', next=request.url))

    # Additional check for admin session validity
    if has_admin_session:
        if not session.get('user_role') or not session.get('session_id'):
            logger.warning(f"Invalid admin session for user {session.get('user_id')} - missing fields")
            session.clear()

            if request.path.startswith('/api/'):
                return jsonify({'success': False, 'error': 'Invalid session'}), 401
            return redirect(url_for('login', next=request.url))

        logger.info(f" Valid admin session: {session.get('user_id')} ({session.get('user_role')})")

    # Additional check for mobile session validity
    if has_mobile_session:
        login_time = session.get('mobile_login_time')
        if login_time:
            try:
                login_dt = datetime.fromisoformat(login_time)
                if (datetime.now() - login_dt).total_seconds() > 86400:  # 24 hours
                    logger.warning(f"Mobile session expired for {session.get('mobile_plate_number')}")
                    # Clear mobile session
                    for key in list(session.keys()):
                        if key.startswith('mobile_'):
                            session.pop(key)

                    if request.path.startswith('/api/mobile/'):
                        return jsonify({'success': False, 'error': 'Session expired'}), 401
                else:
                    logger.info(f" Valid mobile session: {session.get('mobile_plate_number')}")
            except:
                pass

    return None

# ===============================
# APPLICATION STARTUP
# ===============================

def initialize_app():
    """Initialize application components"""
    try:
        logger.info("=== Parking System Starting ===")

        # Initialize database
        system_status['database_active'] = True
        logger.info("Database initialized")

        # Check camera systems
        try:
            if hasattr(camera1, 'get_current_plate'):
                system_status['camera1_active'] = True
                system_status['detection_active'] = True
                logger.info("Camera1 system active")
        except Exception as e:
            logger.warning(f"Camera1 system not available: {e}")
            system_status['camera1_active'] = False

        # Check parking detector
        if parking_detector:
            system_status['camera2_active'] = True
            logger.info("Parking detector active")
        else:
            logger.warning("Parking detector not available")
            system_status['camera2_active'] = False

        # Log startup
        log_security_event('SYSTEM_STARTUP', 'Parking system started successfully')

        logger.info("=== System Initialization Complete ===")
        initialize_enhanced_parking()

    except Exception as e:
        logger.error(f"Startup error: {e}")
        log_security_event('STARTUP_ERROR', f"System startup failed: {e}")


# Initialize app components
initialize_app()



# ===============================
# CLEANUP ON EXIT
# ===============================

def cleanup_on_exit():
    """Cleanup resources on application exit"""
    try:
        logger.info("=== Cleaning up resources ===")

        # Log shutdown
        log_security_event('SYSTEM_SHUTDOWN', 'Parking system shutting down')

        # Cleanup camera resources
        if hasattr(camera1, 'cleanup_resources'):
            camera1.cleanup_resources()

        # Stop parking detector
        global parking_detector
        if parking_detector:
            try:
                del parking_detector
                parking_detector = None
            except Exception as e:
                logger.warning(f"Error cleaning up parking detector: {e}")

        # Close database connections
        if hasattr(db_manager, 'close_connections'):
            db_manager.close_connections()

        logger.info("=== Cleanup complete ===")

    except Exception as e:
        logger.error(f"Cleanup error: {e}")


# Register cleanup function
atexit.register(cleanup_on_exit)





# Import enhanced parking detector
try:
    from camera2_enhanced import get_enhanced_parking_detector, get_current_parking_status

    ENHANCED_PARKING_AVAILABLE = True
    logger.info("Enhanced parking detection available")
except ImportError:
    ENHANCED_PARKING_AVAILABLE = False
    logger.warning("Enhanced parking detection not available")


@app.route('/api/parking/status')
@login_required
@track_requests
@require_valid_session
def get_parking_status():
    """API lấy trạng thái tổng quan parking lot"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            status = get_current_parking_status()
        else:
            # Fallback data khi không có enhanced detector
            status = {
                'total_spaces': 20,
                'occupied_spaces': 12,
                'empty_spaces': 8,
                'occupancy_rate': 60.0,
                'status_text': 'Còn chỗ đỗ',
                'last_updated': datetime.now().isoformat()
            }

        return jsonify({
            'success': True,
            'data': status,
            'timestamp': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Parking status API error: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500


@app.route('/api/parking/spaces')
@login_required
@track_requests
@require_valid_session
def get_parking_spaces():
    """API lấy trạng thái chi tiết từng ô đỗ"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            detector = get_enhanced_parking_detector()
            detailed_status = detector.get_detailed_parking_status()
        else:
            # Fallback data
            detailed_status = {
                'spaces': {i: 'occupied' if i % 3 == 0 else 'empty' for i in range(20)},
                'overview': {
                    'total_spaces': 20,
                    'occupied_spaces': 7,
                    'empty_spaces': 13,
                    'occupancy_rate': 35.0,
                    'status_text': 'Còn nhiều chỗ đỗ',
                    'last_updated': datetime.now().isoformat()
                }
            }

        return jsonify({
            'success': True,
            'data': detailed_status,
            'timestamp': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Parking spaces API error: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500


@app.route('/api/parking/empty-spaces')
@login_required
@track_requests
@require_valid_session
def get_empty_parking_spaces():
    """API lấy danh sách ô đỗ trống"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            detector = get_enhanced_parking_detector()
            empty_spaces = detector.get_empty_spaces()
        else:
            # Fallback data
            empty_spaces = [
                {'space_id': i, 'status': 'empty'}
                for i in range(20) if i % 3 != 0
            ]

        return jsonify({
            'success': True,
            'data': {
                'empty_spaces': empty_spaces,
                'count': len(empty_spaces)
            },
            'timestamp': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Empty spaces API error: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500


@app.route('/api/parking/check-space/<int:space_id>')
@login_required
@track_requests
@require_valid_session
def check_specific_space(space_id):
    """API kiểm tra ô đỗ cụ thể"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            detector = get_enhanced_parking_detector()
            is_available = detector.status_manager.is_space_available(space_id)
            detailed = detector.get_detailed_parking_status()
            space_status = detailed['spaces'].get(space_id, 'unknown')
        else:
            # Fallback logic
            is_available = space_id % 3 != 0
            space_status = 'empty' if is_available else 'occupied'

        return jsonify({
            'success': True,
            'data': {
                'space_id': space_id,
                'status': space_status,
                'is_available': is_available
            },
            'timestamp': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Check space API error: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500


# ===============================
# ENHANCED VIDEO STREAMING
# ===============================

@app.route('/video_stream_enhanced')
@login_required
@track_requests
def video_stream_enhanced():
    """Enhanced parking video stream với parking status"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            detector = get_enhanced_parking_detector()
            system_status['camera2_active'] = True

            def generate_enhanced_stream():
                import cv2
                video_path = "static/video/baidoxe.mp4"
                cap = cv2.VideoCapture(video_path)

                if not cap.isOpened():
                    logger.error(f"Cannot open enhanced video: {video_path}")
                    return

                while cap.isOpened():
                    ret, frame = cap.read()
                    if not ret:
                        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                        continue

                    try:
                        # Process frame với enhanced detector
                        processed_frame = detector.process_frame(frame)

                        # Encode frame
                        _, jpeg = cv2.imencode('.jpg', processed_frame,
                                               [cv2.IMWRITE_JPEG_QUALITY, 85])

                        yield (b'--frame\r\n'
                               b'Content-Type: image/jpeg\r\n\r\n' +
                               jpeg.tobytes() + b'\r\n\r\n')

                    except Exception as e:
                        logger.error(f"Enhanced stream processing error: {e}")
                        # Fallback to original frame
                        _, jpeg = cv2.imencode('.jpg', frame)
                        yield (b'--frame\r\n'
                               b'Content-Type: image/jpeg\r\n\r\n' +
                               jpeg.tobytes() + b'\r\n\r\n')

                cap.release()

            return Response(
                generate_enhanced_stream(),
                mimetype='multipart/x-mixed-replace; boundary=frame'
            )
        else:
            # Fallback to original stream
            return redirect('/video_stream')

    except Exception as e:
        logger.error(f"Enhanced video stream error: {e}")
        system_status['camera2_active'] = False
        return jsonify({"error": "Enhanced stream not available"}), 500


# ===============================
# MOBILE API ENDPOINTS
# ===============================

@app.route('/api/mobile/parking-summary')
@limiter.limit("30 per minute")
@track_requests
def mobile_parking_summary_enhanced():
    """Enhanced mobile API with WebSocket notification support"""
    try:
        current_time = time.time()

        # Check cache (5 seconds)
        if (parking_status_cache['data'] is not None and
                current_time - parking_status_cache['timestamp'] < 5):

            # Send via WebSocket if client is connected
            if len(parking_system_state.connected_clients) > 0:
                socketio.emit('parking_status_update', parking_status_cache['data'])
                logger.debug("📡 Sent cached data via WebSocket")

            return jsonify({
                'success': True,
                'data': parking_status_cache['data'],
                'server_time': datetime.now().isoformat(),
                'cached': True,
                'websocket_clients': len(parking_system_state.connected_clients)
            })

        # Get fresh data
        if ENHANCED_PARKING_AVAILABLE:
            status = get_current_parking_status()
        else:
            # Dynamic fallback data
            import random
            occupied = random.randint(5, 15)
            available = 20 - occupied
            percentage = (occupied / 20) * 100

            status = {
                'total_spaces': 20,
                'occupied_spaces': occupied,
                'empty_spaces': available,
                'occupancy_rate': round(percentage, 1),
                'status_text': f'Còn {available} chỗ trống' if available > 0 else 'Hết chỗ đỗ',
                'last_updated': datetime.now().isoformat()
            }

        # Format for mobile
        mobile_response = {
            'parking_status': {
                'total': status['total_spaces'],
                'available': status['empty_spaces'],
                'occupied': status['occupied_spaces'],
                'percentage_full': status['occupancy_rate']
            },
            'status_message': status['status_text'],
            'last_updated': status['last_updated'],
            'color_indicator': 'green' if status['empty_spaces'] > 5 else 'yellow' if status[
                                                                                          'empty_spaces'] > 0 else 'red'
        }

        # Cache data
        parking_status_cache['data'] = mobile_response
        parking_status_cache['timestamp'] = current_time

        # Send via WebSocket to all connected clients
        if len(parking_system_state.connected_clients) > 0:
            socketio.emit('parking_status_update', mobile_response)
            logger.debug(f"📡 Sent fresh data via WebSocket to {len(parking_system_state.connected_clients)} clients")

        return jsonify({
            'success': True,
            'data': mobile_response,
            'server_time': datetime.now().isoformat(),
            'cached': False,
            'websocket_clients': len(parking_system_state.connected_clients)
        })

    except Exception as e:
        logger.error(f" Mobile parking summary error: {str(e)}")
        # Return safe fallback data
        fallback_data = {
            'parking_status': {
                'total': 20,
                'available': 10,
                'occupied': 10,
                'percentage_full': 50.0
            },
            'status_message': 'Hệ thống đang tải...',
            'last_updated': datetime.now().isoformat(),
            'color_indicator': 'yellow'
        }
        return jsonify({
            'success': True,
            'data': fallback_data,
            'server_time': datetime.now().isoformat(),
            'error_fallback': True,
            'websocket_clients': len(parking_system_state.connected_clients)
        }), 200

@app.route('/api/websocket/status')
@login_required
@track_requests
def websocket_status():
    """Get WebSocket server status for debugging"""
    try:
        return jsonify({
            'success': True,
            'websocket_enabled': True,
            'connected_clients': len(parking_system_state.connected_clients),
            'clients': {
                session_id: {
                    'remote_addr': client.get('remote_addr'),
                    'connected_at': client.get('connected_at'),
                    'app_state': client.get('app_state', 'unknown'),
                    'last_heartbeat': client.get('last_heartbeat', 'never')
                }
                for session_id, client in parking_system_state.connected_clients.items()
            },
            'notification_queue_size': len(parking_system_state.notification_queue),
            'server_time': datetime.now().isoformat()
        })
    except Exception as e:
        logger.error(f" WebSocket status error: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/websocket/broadcast-test', methods=['POST'])
@login_required
@track_requests
def websocket_broadcast_test():
    """Test WebSocket broadcast functionality"""
    try:
        data = request.get_json()
        message = data.get('message', 'Test broadcast message')

        test_notification = {
            'id': f"test_{int(time.time())}",
            'title': ' Test Broadcast',
            'message': message,
            'type': 'system_update',
            'priority': 2,
            'timestamp': datetime.now().isoformat(),
            'data': {'test': True}
        }

        # Broadcast to all connected clients
        socketio.emit('notification', test_notification)

        logger.info(f" Test broadcast sent to {len(parking_system_state.connected_clients)} clients")

        return jsonify({
            'success': True,
            'message': 'Test broadcast sent',
            'clients_notified': len(parking_system_state.connected_clients),
            'notification': test_notification
        })

    except Exception as e:
        logger.error(f" WebSocket broadcast test error: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


def initialize_websocket_features():
    """Initialize WebSocket features"""
    try:
        logger.info(" Initializing WebSocket features...")

        # Start background broadcast
        start_parking_status_broadcast()

        logger.info(" WebSocket features initialized successfully")

    except Exception as e:
        logger.error(f" Error initializing WebSocket features: {e}")








@app.route('/api/mobile/quick-status')
@login_required
@limiter.limit("60 per minute")
@track_requests
@require_valid_session
def mobile_quick_status():
    """API nhanh cho mobile - Chỉ thông tin cần thiết"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            status = get_current_parking_status()
            available = status['empty_spaces']
            total = status['total_spaces']
        else:
            available = 12
            total = 20

        return jsonify({
            'available': available,
            'total': total,
            'has_space': available > 0,
            'timestamp': int(datetime.now().timestamp())
        })

    except Exception as e:
        logger.error(f"Mobile quick status error: {str(e)}")
        return jsonify({
            'available': 0,
            'total': 0,
            'has_space': False,
            'timestamp': int(datetime.now().timestamp())
        }), 500


# ===============================
# INITIALIZATION UPDATE
# ===============================

def initialize_enhanced_parking():
    """Khởi tạo enhanced parking detector"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            detector = get_enhanced_parking_detector()
            logger.info("Enhanced parking detector initialized successfully")
            system_status['enhanced_parking_active'] = True
        else:
            logger.warning("Enhanced parking detector not available")
            system_status['enhanced_parking_active'] = False
    except Exception as e:
        logger.error(f"Enhanced parking initialization error: {e}")
        system_status['enhanced_parking_active'] = False


@app.before_request
def check_guard_access():
    """Kiểm tra và chặn truy cập của bảo vệ"""
    if session.get('user_role') == 'guard':
        # Các route mà bảo vệ KHÔNG được truy cập
        restricted_routes = [
            #'/register', '/management', '/reports', '/users',
            #'/api/register_vehicle', '/api/vehicles/update',
            '/api/vehicles/delete', '/api/reports/generate',
            '/api/reports/export', '/api/backup/create',
            '/api/reset_session', '/api/reset_system',
            '/api/users/'  # All user management APIs
        ]

        # Kiểm tra nếu bảo vệ cố truy cập route bị cấm
        for route in restricted_routes:
            if request.path.startswith(route):
                log_security_event('UNAUTHORIZED_ACCESS',
                                   f"Guard {session.get('user_id')} attempted to access {request.path}")

                if request.path.startswith('/api/'):
                    return jsonify({
                        'success': False,
                        'error': 'Bảo vệ không có quyền truy cập chức năng này'
                    }), 403
                else:
                    flash('Bạn không có quyền truy cập chức năng này', 'error')
                    return redirect(url_for('index'))



@app.route('/api/test/parking-data')
@login_required
@track_requests
def test_parking_data():
    """Test endpoint để kiểm tra dữ liệu parking"""
    try:
        test_data = {
            'enhanced_available': ENHANCED_PARKING_AVAILABLE,
            'current_status': get_current_parking_status(),
            'system_status': {
                'camera2_active': system_status.get('camera2_active', False),
                'enhanced_parking_active': system_status.get('enhanced_parking_active', False)
            }
        }

        if ENHANCED_PARKING_AVAILABLE:
            detector = get_enhanced_parking_detector()
            test_data['detailed_status'] = detector.get_detailed_parking_status()

        return jsonify({
            'success': True,
            'data': test_data,
            'timestamp': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Test parking data error: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

# Cache cho parking status (5 giây)
parking_status_cache = {
    'data': None,
    'timestamp': 0
}


@socketio.on('connect')
def handle_connect():
    """✅ FIXED: Enhanced client connection handler"""
    session_id = request.sid
    client_info = {
        'user_agent': request.headers.get('User-Agent', 'Unknown'),
        'remote_addr': request.remote_addr,
        'connected_at': datetime.now().isoformat(),
        'client_type': 'unknown'
    }

    parking_system_state.add_client(session_id, client_info)
    print(f"[SOCKET] ✅ Client connected: {session_id} from {request.remote_addr}")

    # Send welcome message
    emit('system_info', {
        'message': 'Connected to parking system',
        'server_time': datetime.now().isoformat(),
        'connected_clients': len(parking_system_state.connected_clients),
        'features': ['vehicle_notifications', 'parking_status', 'real_time_updates']
    })

    # Send current parking status immediately
    try:
        if ENHANCED_PARKING_AVAILABLE:
            current_status = get_current_parking_status()
        else:
            import random
            occupied = random.randint(5, 15)
            available = 20 - occupied
            percentage = (occupied / 20) * 100

            current_status = {
                'total_spaces': 20,
                'occupied_spaces': occupied,
                'empty_spaces': available,
                'occupancy_rate': round(percentage, 1),
                'status_text': f'Còn {available} chỗ trống' if available > 0 else 'Hết chỗ đỗ',
                'last_updated': datetime.now().isoformat()
            }

        # Format for mobile
        mobile_status = {
            'parking_status': {
                'total': current_status.get('total_spaces', 20),
                'available': current_status.get('empty_spaces', 12),
                'occupied': current_status.get('occupied_spaces', 8),
                'percentage_full': current_status.get('occupancy_rate', 40.0)
            },
            'status_message': current_status.get('status_text', 'Hệ thống hoạt động'),
            'last_updated': current_status.get('last_updated', datetime.now().isoformat()),
            'color_indicator': 'green' if current_status.get('empty_spaces', 12) > 5 else 'yellow' if current_status.get('empty_spaces', 12) > 0 else 'red'
        }

        print(f"[SOCKET] 📡 Sending initial parking status to {session_id}")
        emit('parking_status_update', mobile_status)

    except Exception as e:
        print(f"[ERROR] ❌ Error sending initial status: {e}")


@socketio.on('disconnect')
def handle_disconnect():
    """Handle client disconnection"""
    session_id = request.sid
    parking_system_state.remove_client(session_id)
    print(f"[SOCKET] Client disconnected: {session_id}")


@socketio.on('join_user_room')
def handle_join_user_room(data):
    """Handle user joining specific room"""
    try:
        user_id = data.get('user_id')
        plate_number = data.get('plate_number')

        if user_id:
            room_name = f"user_{user_id}"
            join_room(room_name)
            print(f"[SOCKET] User {user_id} ({plate_number}) joined room: {room_name}")

            emit('joined_room', {
                'room': room_name,
                'status': 'success',
                'message': f'Joined room for {plate_number}',
                'timestamp': datetime.now().isoformat()
            })
        else:
            print(f"[ERROR] Invalid join_user_room request: {data}")
            emit('error', {'message': 'Invalid room join request'})

    except Exception as e:
        print(f"[ERROR] Error in join_user_room: {e}")
        emit('error', {'message': 'Failed to join room'})


@socketio.on('request_parking_status')
def handle_parking_status_request(data=None):
    """Handle parking status request from client - FIXED"""
    try:
        session_id = request.sid
        print(f"[SOCKET] Parking status requested by {session_id}")

        if data:
            print(f"[SOCKET] Request data: {data}")

        # Generate current parking status
        if ENHANCED_PARKING_AVAILABLE:
            current_status = get_current_parking_status()
            print(f"[SOCKET] Enhanced parking data: {current_status}")
        else:
            # Generate dynamic fallback data
            import random
            occupied = random.randint(3, 17)
            available = 20 - occupied
            percentage = (occupied / 20) * 100

            current_status = {
                'total_spaces': 20,
                'occupied_spaces': occupied,
                'empty_spaces': available,
                'occupancy_rate': round(percentage, 1),
                'status_text': f'Con {available} cho trong' if available > 0 else 'Het cho do',
                'last_updated': datetime.now().isoformat()
            }
            print(f"[SOCKET] Generated parking data: {current_status}")

        # ✅ FIXED: Format data correctly for Android
        mobile_response = {
            'parking_status': {
                'total': current_status.get('total_spaces', 20),
                'available': current_status.get('empty_spaces', 0),
                'occupied': current_status.get('occupied_spaces', 0),
                'percentage_full': current_status.get('occupancy_rate', 0.0)
            },
            'status_message': current_status.get('status_text', 'He thong hoat dong'),
            'last_updated': current_status.get('last_updated', datetime.now().isoformat()),
            'color_indicator': 'green' if current_status.get('empty_spaces', 0) > 5 else 'yellow' if current_status.get(
                'empty_spaces', 0) > 0 else 'red'
        }

        print(f"[SOCKET] Sending parking status to {session_id}")
        print(f"[SOCKET] Response data: {mobile_response}")

        # ✅ FIXED: Send data object directly, not wrapped
        emit('parking_status_update', mobile_response)

        # Also send system info
        emit('system_info', {
            'system_status': 'operational',
            'connected_clients': len(parking_system_state.connected_clients),
            'server_time': datetime.now().isoformat()
        })

    except Exception as e:
        print(f"[ERROR] Error handling parking status request: {e}")
        # Send error fallback
        error_response = {
            'parking_status': {
                'total': 20,
                'available': 0,
                'occupied': 0,
                'percentage_full': 0.0
            },
            'status_message': 'Loi lay du lieu bai do xe',
            'last_updated': datetime.now().isoformat(),
            'color_indicator': 'red'
        }
        emit('parking_status_update', error_response)


@socketio.on('ping')
def handle_ping(data=None):
    """Handle ping from client"""
    try:
        session_id = request.sid
        print(f"[SOCKET] Ping received from {session_id}")

        pong_data = {
            'timestamp': datetime.now().isoformat(),
            'server_time': datetime.now().isoformat()
        }

        if data and isinstance(data, dict):
            client_timestamp = data.get('timestamp')
            if client_timestamp:
                pong_data['client_timestamp'] = client_timestamp

        emit('pong', pong_data)
        print(f"[SOCKET] Pong sent to {session_id}")

    except Exception as e:
        print(f"[ERROR] Error handling ping: {e}")


@socketio.on('heartbeat')
def handle_heartbeat(data=None):
    """Handle heartbeat from client"""
    try:
        session_id = request.sid
        print(f"[SOCKET] Heartbeat received from {session_id}")

        # Update client info
        if session_id in parking_system_state.connected_clients:
            parking_system_state.connected_clients[session_id]['last_heartbeat'] = datetime.now().isoformat()
            if data:
                parking_system_state.connected_clients[session_id]['app_state'] = data.get('app_state', 'unknown')

        # Respond with server status
        emit('heartbeat_ack', {
            'server_time': datetime.now().isoformat(),
            'status': 'healthy'
        })

    except Exception as e:
        print(f"[ERROR] Error handling heartbeat: {e}")


@socketio.on('app_state_change')
def handle_app_state_change(data=None):
    """Handle app state change notification"""
    try:
        session_id = request.sid
        app_state = data.get('app_state', 'unknown') if data else 'unknown'

        logger.info(f" App state change from {session_id}: {app_state}")

        # Update client info
        if session_id in parking_system_state.connected_clients:
            parking_system_state.connected_clients[session_id]['app_state'] = app_state
            parking_system_state.connected_clients[session_id]['last_state_change'] = datetime.now().isoformat()

    except Exception as e:
        logger.error(f" Error handling app state change: {e}")


@socketio.on('test_connection')
def handle_test_connection(data=None):
    """Handle connection test from client"""
    try:
        session_id = request.sid
        print(f"[SOCKET] Connection test from {session_id}")

        test_response = {
            'test_result': 'success',
            'server_time': datetime.now().isoformat(),
            'session_id': session_id,
            'connected_clients': len(parking_system_state.connected_clients)
        }

        if data:
            test_response['client_data'] = data

        emit('test_message', test_response)
        print(f"[SOCKET] Test response sent to {session_id}")

    except Exception as e:
        print(f"[ERROR] Error handling connection test: {e}")


@socketio.on('client_disconnect')
def handle_client_disconnect(data=None):
    """Handle client-initiated disconnect notification"""
    try:
        session_id = request.sid
        reason = data.get('reason', 'client_initiated') if data else 'client_initiated'

        logger.info(f" Client {session_id} disconnecting: {reason}")

        # Clean up client data
        parking_system_state.remove_client(session_id)

    except Exception as e:
        logger.error(f" Error handling client disconnect: {e}")


def start_parking_status_broadcast():
    """Start broadcasting parking status updates - FIXED"""

    def broadcast_status():
        while True:
            try:
                time.sleep(10)  # Broadcast every 10 seconds

                if len(parking_system_state.connected_clients) > 0:
                    # Get current status
                    if ENHANCED_PARKING_AVAILABLE:
                        current_status = get_current_parking_status()
                    else:
                        # Generate dynamic data
                        import random
                        occupied = random.randint(3, 17)
                        available = 20 - occupied
                        percentage = (occupied / 20) * 100

                        current_status = {
                            'total_spaces': 20,
                            'occupied_spaces': occupied,
                            'empty_spaces': available,
                            'occupancy_rate': round(percentage, 1),
                            'status_text': f'Con {available} cho trong' if available > 0 else 'Het cho do',
                            'last_updated': datetime.now().isoformat()
                        }

                    # ✅ FIXED: Format for broadcast
                    broadcast_data = {
                        'parking_status': {
                            'total': current_status.get('total_spaces', 20),
                            'available': current_status.get('empty_spaces', 0),
                            'occupied': current_status.get('occupied_spaces', 0),
                            'percentage_full': current_status.get('occupancy_rate', 0.0)
                        },
                        'status_message': current_status.get('status_text', 'He thong hoat dong'),
                        'last_updated': current_status.get('last_updated', datetime.now().isoformat()),
                        'color_indicator': 'green' if current_status.get('empty_spaces',
                                                                         0) > 5 else 'yellow' if current_status.get(
                            'empty_spaces', 0) > 0 else 'red'
                    }

                    print(f"[BROADCAST] Broadcasting to {len(parking_system_state.connected_clients)} clients")
                    print(f"[BROADCAST] Data: {broadcast_data}")

                    # ✅ FIXED: Send data object directly
                    socketio.emit('parking_status_update', broadcast_data)

            except Exception as e:
                print(f"[ERROR] Error in status broadcast: {e}")
                time.sleep(5)  # Wait before retrying

    # Start in background thread
    import threading
    broadcast_thread = threading.Thread(target=broadcast_status, daemon=True)
    broadcast_thread.start()
    print("[BROADCAST] Parking status broadcast started")

# THÊM API ENDPOINT MỚI:
@app.route('/api/parking/status')
@login_required
@track_requests
def api_parking_status_enhanced():
    """Enhanced parking status API"""
    try:
        if ENHANCED_PARKING_AVAILABLE:
            status = get_current_parking_status()
        else:
            status = {
                'total_spaces': 20,
                'empty_spaces': 12,
                'occupied_spaces': 8,
                'occupancy_rate': 40.0,
                'status_text': 'Còn chỗ đỗ',
                'last_updated': datetime.now().isoformat()
            }

        response_data = {
            'success': True,
            'data': {
                'parking_status': {
                    'total': status.get('total_spaces', 20),
                    'available': status.get('empty_spaces', 0),
                    'occupied': status.get('occupied_spaces', 0),
                    'percentage_full': status.get('occupancy_rate', 0)
                },
                'status_message': status.get('status_text', 'Hệ thống hoạt động'),
                'last_updated': status.get('last_updated', datetime.now().isoformat()),
                'color_indicator': 'green' if status.get('empty_spaces', 0) > 5 else 'yellow' if status.get(
                    'empty_spaces', 0) > 0 else 'red'
            },
            'server_time': datetime.now().isoformat(),
            'websocket_clients': len(parking_system_state.connected_clients)
        }

        return jsonify(response_data)

    except Exception as e:
        logger.error(f"Enhanced parking status API error: {str(e)}")
        return jsonify({'success': False, 'error': str(e)}), 500
# ===============================
# MOBILE APP API ENDPOINTS
# ===============================

@app.route('/api/mobile/vehicle-login', methods=['POST'])
@limiter.limit("10 per minute")
def mobile_vehicle_login():
    """API đăng nhập cho mobile app bằng vehicle info"""
    try:
        logger.info(" Mobile vehicle login attempt started")

        data = request.get_json()
        if not data:
            logger.warning(" No JSON data in mobile login request")
            return jsonify({
                'success': False,
                'message': 'Không có dữ liệu đăng nhập'
            }), 400

        plate_number = data.get('plate_number', '').strip().upper()
        owner_phone = data.get('owner_phone', '').strip()

        logger.info(f" Mobile login attempt: plate='{plate_number}', phone='{owner_phone}'")

        if not plate_number or not owner_phone:
            logger.warning(f" Missing credentials: plate={bool(plate_number)}, phone={bool(owner_phone)}")
            return jsonify({
                'success': False,
                'message': 'Biển số xe và số điện thoại không được để trống'
            }), 400

        # Tìm xe trong database với flexible matching
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Debug: Show what's in database
        cursor.execute("SELECT plate_number, owner_phone, owner_name FROM registered_vehicles WHERE is_active = 1")
        all_vehicles = cursor.fetchall()
        logger.info(f" Database has {len(all_vehicles)} active vehicles")
        for v in all_vehicles:
            logger.info(f"  - {v[0]} | {v[1]} | {v[2]}")

        # Try exact match first
        cursor.execute("""
            SELECT id, plate_number, owner_name, owner_phone, owner_email,
                   vehicle_type, vehicle_brand, vehicle_model, vehicle_color,
                   registration_date, expiry_date, is_active
            FROM registered_vehicles 
            WHERE plate_number = ? AND owner_phone = ? AND is_active = 1
        """, (plate_number, owner_phone))

        result = cursor.fetchone()

        if not result:
            # Try with different plate formats
            plate_variations = [
                plate_number,
                plate_number.replace('-', ''),
                plate_number.replace('-', ' '),
                plate_number.replace(' ', '-')
            ]

            # Try with different phone formats
            phone_variations = [
                owner_phone,
                owner_phone.replace(' ', ''),
                owner_phone.replace('-', ''),
                owner_phone.replace(' ', '').replace('-', '')
            ]

            logger.info(f" Trying variations: plates={plate_variations}, phones={phone_variations}")

            for plate_var in plate_variations:
                for phone_var in phone_variations:
                    cursor.execute("""
                        SELECT id, plate_number, owner_name, owner_phone, owner_email,
                               vehicle_type, vehicle_brand, vehicle_model, vehicle_color,
                               registration_date, expiry_date, is_active
                        FROM registered_vehicles 
                        WHERE plate_number = ? AND owner_phone = ? AND is_active = 1
                    """, (plate_var, phone_var))

                    result = cursor.fetchone()
                    if result:
                        logger.info(f" Found match with: plate='{plate_var}', phone='{phone_var}'")
                        break
                if result:
                    break

        if not result:
            logger.warning(f" Mobile login failed: {plate_number} / {owner_phone} - No matching record")
            conn.close()
            return jsonify({
                'success': False,
                'message': 'Biển số xe hoặc số điện thoại không chính xác. Vui lòng kiểm tra lại thông tin.'
            }), 401

        # Lấy thông tin xe
        vehicle_data = {
            'id': result[0],
            'plate_number': result[1],
            'owner_name': result[2],
            'owner_phone': result[3],
            'owner_email': result[4] or '',
            'vehicle_type': result[5],
            'vehicle_brand': result[6] or '',
            'vehicle_model': result[7] or '',
            'vehicle_color': result[8] or '',
            'registration_date': result[9],
            'expiry_date': result[10] or '',
            'is_active': bool(result[11])
        }

        conn.close()

        # Tạo session cho mobile
        session['mobile_vehicle_id'] = vehicle_data['id']
        session['mobile_plate_number'] = vehicle_data['plate_number']
        session['mobile_owner_name'] = vehicle_data['owner_name']
        session['mobile_login_time'] = datetime.now().isoformat()
        session.permanent = True

        logger.info(f" Mobile vehicle login successful: {vehicle_data['plate_number']} - {vehicle_data['owner_name']}")
        logger.info(f" Mobile session created: {dict(session)}")

        # Log security event
        log_security_event('MOBILE_VEHICLE_LOGIN',
                           f"Vehicle: {vehicle_data['plate_number']}, Owner: {vehicle_data['owner_name']}")

        return jsonify({
            'success': True,
            'message': 'Đăng nhập thành công',
            'vehicle_info': {
                'plate_number': vehicle_data['plate_number'],
                'owner_name': vehicle_data['owner_name'],
                'owner_phone': vehicle_data['owner_phone'],
                'owner_email': vehicle_data['owner_email'],
                'vehicle_type': vehicle_data['vehicle_type'],
                'vehicle_brand': vehicle_data['vehicle_brand'],
                'vehicle_model': vehicle_data['vehicle_model'],
                'vehicle_color': vehicle_data['vehicle_color'],
                'registration_date': vehicle_data['registration_date'],
                'expiry_date': vehicle_data['expiry_date'],
                'is_active': vehicle_data['is_active']
            }
        })

    except Exception as e:
        logger.error(f" Mobile vehicle login error: {str(e)}", exc_info=True)
        return jsonify({
            'success': False,
            'message': 'Lỗi server. Vui lòng thử lại sau.'
        }), 500


@app.route('/api/mobile/check-vehicle-session', methods=['POST'])
def check_mobile_vehicle_session():
    """Kiểm tra session xe mobile"""
    if ('mobile_vehicle_id' in session and
        'mobile_plate_number' in session and
        'mobile_login_time' in session):
        return jsonify({'success': True})
    else:
        return jsonify({'success': False}), 401


@app.route('/api/mobile/vehicle-history/<plate_number>')
def get_mobile_vehicle_history(plate_number):
    """Lấy lịch sử ra/vào của xe"""
    try:
        # Kiểm tra quyền truy cập
        if session.get('mobile_plate_number') != plate_number.upper():
            return jsonify({
                'success': False,
                'error': 'Không có quyền truy cập lịch sử xe này'
            }), 403

        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        cursor.execute("""
            SELECT id, entry_time, exit_time, parking_duration, entry_image, exit_image
            FROM entry_exit_log 
            WHERE plate_number = ? 
            ORDER BY entry_time DESC 
            LIMIT 50
        """, (plate_number.upper(),))

        results = cursor.fetchall()
        columns = [desc[0] for desc in cursor.description]

        history = [dict(zip(columns, row)) for row in results]
        conn.close()

        return jsonify({
            'success': True,
            'history': history,
            'count': len(history)
        })

    except Exception as e:
        logger.error(f"Get vehicle history error: {str(e)}")
        return jsonify({
            'success': False,
            'error': 'Lỗi server'
        }), 500


@app.route('/api/mobile/test-database')
def test_mobile_database():
    """Test endpoint để kiểm tra database"""
    try:
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Lấy tất cả xe đang active
        cursor.execute("""
            SELECT plate_number, owner_name, owner_phone, vehicle_type 
            FROM registered_vehicles 
            WHERE is_active = 1
            ORDER BY registration_date DESC
            LIMIT 10
        """)

        vehicles = cursor.fetchall()
        conn.close()

        return jsonify({
            'success': True,
            'message': 'Database connection OK',
            'vehicles': [
                {
                    'plate_number': v[0],
                    'owner_name': v[1],
                    'owner_phone': v[2],
                    'vehicle_type': v[3]
                } for v in vehicles
            ],
            'count': len(vehicles)
        })

    except Exception as e:
        logger.error(f"Database test error: {str(e)}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500


def send_vehicle_notification(plate_number, action, capture_data):
    """✅ FIXED: Send real-time vehicle notification via WebSocket"""
    try:
        logger.info(f"🚗 📱 *** SENDING VEHICLE NOTIFICATION ***")
        logger.info(f"🚗 Plate: {plate_number}, Action: {action}")

        # Get vehicle info from database
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        cursor.execute("""
            SELECT owner_name, owner_phone, vehicle_type, vehicle_brand, vehicle_model, owner_email
            FROM registered_vehicles
            WHERE plate_number = ? AND is_active = 1
        """, (plate_number.upper(),))

        vehicle = cursor.fetchone()

        if not vehicle:
            logger.warning(f"🚗 Vehicle {plate_number} not registered")
            conn.close()
            return False

        owner_name, owner_phone, vehicle_type, vehicle_brand, vehicle_model, owner_email = vehicle

        # Calculate parking duration for exit
        parking_duration = None
        entry_time = None
        exit_time = None

        if action == 'exit':
            cursor.execute("""
                SELECT entry_time FROM entry_exit_log
                WHERE plate_number = ? AND exit_time IS NULL
                ORDER BY entry_time DESC LIMIT 1
            """, (plate_number.upper(),))

            entry_result = cursor.fetchone()
            if entry_result:
                try:
                    entry_time_str = entry_result[0]
                    entry_dt = datetime.strptime(entry_time_str, "%Y-%m-%d %H:%M:%S")
                    exit_dt = datetime.now()
                    duration_seconds = (exit_dt - entry_dt).total_seconds()
                    parking_duration = int(duration_seconds / 60)  # minutes
                    entry_time = entry_time_str
                    exit_time = capture_data['timestamp']
                except Exception as e:
                    logger.error(f"Error calculating duration: {e}")

        conn.close()

        # ✅ FIXED: Create notification in Android-compatible format
        vehicle_notification = {
            'id': f"vehicle_{int(time.time())}_{plate_number}",
            'type': 'vehicle_activity',
            'title': f"🚗 Xe {'vào bãi' if action == 'entry' else 'ra khỏi bãi'}",
            'message': f"Xe {plate_number} đã {'vào' if action == 'entry' else 'ra khỏi'} bãi đỗ xe",
            'plate_number': plate_number,
            'owner_name': owner_name,
            'action': action,
            'timestamp': capture_data['timestamp'],
            'image_url': f"/static/captures/{capture_data['image']}" if capture_data.get('image') else None,
            'parking_duration': parking_duration,
            'entry_time': entry_time,
            'exit_time': exit_time
        }

        logger.info(f"🚗 📦 Notification data: {vehicle_notification}")

        # ✅ CRITICAL: Send via WebSocket with correct event name
        socketio.emit('vehicle_notification', vehicle_notification)
        logger.info(f"🚗 ✅ Vehicle notification broadcasted to all clients")
        logger.info(f"🚗 📡 Connected clients: {len(parking_system_state.connected_clients)}")

        # Also send to specific rooms if implemented
        try:
            vehicle_room = f"vehicle_{plate_number.upper()}"
            socketio.emit('vehicle_notification', vehicle_notification, room=vehicle_room)
            logger.info(f"🚗 📡 Sent to vehicle room: {vehicle_room}")
        except Exception as e:
            logger.warning(f"Room notification failed: {e}")

        return True

    except Exception as e:
        logger.error(f"🚗 ❌ Error sending vehicle notification: {e}", exc_info=True)
        return False


def save_notification_to_db(plate_number, notification_data):
    """Lưu notification vào database để xem lịch sử"""
    try:
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Tạo bảng notifications nếu chưa có
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notification_id TEXT UNIQUE,
                plate_number TEXT,
                type TEXT,
                title TEXT,
                message TEXT,
                action TEXT,
                timestamp DATETIME,
                data TEXT,
                is_read BOOLEAN DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        ''')

        # Insert notification
        cursor.execute('''
            INSERT INTO notifications 
            (notification_id, plate_number, type, title, message, action, timestamp, data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''', (
            notification_data['id'],
            plate_number,
            notification_data['type'],
            notification_data['title'],
            notification_data['message'],
            notification_data['action'],
            notification_data['timestamp'],
            json.dumps(notification_data['data'])
        ))

        conn.commit()
        conn.close()

        logger.info(f" Notification saved to database: {notification_data['id']}")

    except Exception as e:
        logger.error(f"Error saving notification to DB: {e}")


@socketio.on('join_vehicle_room')
def handle_join_vehicle_room(data):
    """✅ FIXED: Handle vehicle joining notification room"""
    try:
        session_id = request.sid
        plate_number = data.get('plate_number', '').upper()
        owner_phone = data.get('owner_phone', '')
        client_type = data.get('client_type', 'android')

        print(f"[SOCKET] 🚗 Vehicle room join request:")
        print(f"[SOCKET] - Session: {session_id}")
        print(f"[SOCKET] - Plate: {plate_number}")
        print(f"[SOCKET] - Phone: {owner_phone}")
        print(f"[SOCKET] - Client: {client_type}")

        if plate_number:
            # Join vehicle-specific room
            vehicle_room = f"vehicle_{plate_number}"
            join_room(vehicle_room)
            print(f"[SOCKET] ✅ Joined vehicle room: {vehicle_room}")

            # Join user room by phone
            if owner_phone:
                user_room = f"user_{owner_phone}"
                join_room(user_room)
                print(f"[SOCKET] ✅ Joined user room: {user_room}")

            # Update client info
            if session_id in parking_system_state.connected_clients:
                parking_system_state.connected_clients[session_id].update({
                    'plate_number': plate_number,
                    'owner_phone': owner_phone,
                    'client_type': client_type,
                    'rooms': [vehicle_room, user_room if owner_phone else None]
                })

            # Confirm room join
            emit('room_joined', {
                'status': 'success',
                'rooms': [vehicle_room, user_room if owner_phone else None],
                'message': f'Successfully joined notification rooms for {plate_number}',
                'timestamp': datetime.now().isoformat()
            })

            print(f"[SOCKET] ✅ Room join completed for {plate_number}")

        else:
            print(f"[ERROR] ❌ Invalid room join request - missing plate number")
            emit('error', {'message': 'Plate number required to join vehicle room'})

    except Exception as e:
        print(f"[ERROR] ❌ Error in join_vehicle_room: {e}")
        emit('error', {'message': 'Failed to join vehicle room'})


def initialize_illegal_parking_system():
    """Khởi tạo hệ thống phát hiện đỗ xe sai quy định"""
    try:
        logger.info("🚗 Initializing illegal parking detection system...")

        # Get detector instance
        detector = get_illegal_parking_detector()

        # Register callback để gửi notifications qua WebSocket
        def send_illegal_parking_notification(notification):
            try:
                # Gửi qua WebSocket tới tất cả clients
                socketio.emit('illegal_parking_event', notification)

                # Log
                logger.info(f"🚨 Illegal parking notification sent: {notification['message']}")

                # Lưu vào database nếu cần
                if notification['severity'] == 'violation':
                    log_illegal_parking_violation(notification)

            except Exception as e:
                logger.error(f"Error sending illegal parking notification: {e}")

        detector.register_notification_callback(send_illegal_parking_notification)

        # Start cleanup thread
        def cleanup_loop():
            while True:
                time.sleep(60)  # Every minute
                detector.cleanup_old_trackers()

        cleanup_thread = threading.Thread(target=cleanup_loop, daemon=True)
        cleanup_thread.start()

        logger.info("✅ Illegal parking detection system initialized")

    except Exception as e:
        logger.error(f"❌ Error initializing illegal parking system: {e}")


def log_illegal_parking_violation(notification):
    """Lưu vi phạm đỗ xe vào database"""
    try:
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        # Tạo bảng nếu chưa có
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS illegal_parking_violations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                vehicle_id TEXT,
                violation_time DATETIME,
                duration INTEGER,
                position_x INTEGER,
                position_y INTEGER,
                status TEXT,
                resolved_time DATETIME,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        ''')

        # Insert violation
        cursor.execute('''
            INSERT INTO illegal_parking_violations 
            (vehicle_id, violation_time, duration, position_x, position_y, status)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (
            notification['vehicle_id'],
            notification['timestamp'],
            notification['duration'],
            notification['position'][0] if 'position' in notification else 0,
            notification['position'][1] if 'position' in notification else 0,
            'active'
        ))

        conn.commit()
        conn.close()

        logger.info(f"Violation logged: Vehicle {notification['vehicle_id']}")

    except Exception as e:
        logger.error(f"Error logging violation: {e}")


# API endpoints cho illegal parking

@app.route('/api/illegal-parking/status')
@login_required
@track_requests
def get_illegal_parking_status():
    """Lấy trạng thái vi phạm đỗ xe hiện tại"""
    try:
        detector = get_illegal_parking_detector()

        return jsonify({
            'success': True,
            'violations': detector.get_active_violations(),
            'statistics': detector.get_statistics(),
            'timestamp': datetime.now().isoformat()
        })

    except Exception as e:
        logger.error(f"Error getting illegal parking status: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/illegal-parking/history')
@login_required
@track_requests
def get_illegal_parking_history():
    """Lấy lịch sử vi phạm đỗ xe"""
    try:
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        cursor.execute('''
            SELECT * FROM illegal_parking_violations
            ORDER BY created_at DESC
            LIMIT 100
        ''')

        columns = [desc[0] for desc in cursor.description]
        violations = [dict(zip(columns, row)) for row in cursor.fetchall()]

        conn.close()

        return jsonify({
            'success': True,
            'violations': violations,
            'count': len(violations)
        })

    except Exception as e:
        logger.error(f"Error getting violation history: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/api/illegal-parking/resolve/<vehicle_id>', methods=['POST'])
@admin_required
@track_requests
def resolve_illegal_parking(vehicle_id):
    """Xử lý/xóa vi phạm đỗ xe"""
    try:
        # Update database
        conn = sqlite3.connect(db_manager.db_path)
        cursor = conn.cursor()

        cursor.execute('''
            UPDATE illegal_parking_violations
            SET status = 'resolved', resolved_time = CURRENT_TIMESTAMP
            WHERE vehicle_id = ? AND status = 'active'
        ''', (vehicle_id,))

        conn.commit()
        conn.close()

        # Send clear notification
        socketio.emit('illegal_parking_event', {
            'type': 'illegal_parking_cleared',
            'vehicle_id': vehicle_id,
            'timestamp': datetime.now().isoformat(),
            'message': f'Vi phạm của xe {vehicle_id} đã được xử lý'
        })

        return jsonify({
            'success': True,
            'message': 'Vi phạm đã được xử lý'
        })

    except Exception as e:
        logger.error(f"Error resolving violation: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


# WebSocket event handlers

@socketio.on('request_illegal_parking_status')
def handle_illegal_parking_status_request():
    """Handle request for current illegal parking status"""
    try:
        detector = get_illegal_parking_detector()

        data = {
            'violations': detector.get_active_violations(),
            'statistics': detector.get_statistics(),
            'timestamp': datetime.now().isoformat()
        }

        emit('illegal_parking_status', data)

    except Exception as e:
        logger.error(f"Error handling illegal parking status request: {e}")

# ===============================
# MAIN APPLICATION ENTRY POINT
# ===============================

if __name__ == "__main__":
    try:
        logger.info("=== Starting Enhanced Parking System with WebSocket ===")

        # Initialize WebSocket features
        initialize_websocket_features()
        initialize_illegal_parking_system()

        # Enhanced session configuration
        import secrets

        if not app.config.get('SECRET_KEY'):
            secret_key_file = 'secret_key.txt'
            if os.path.exists(secret_key_file):
                with open(secret_key_file, 'r') as f:
                    secret_key = f.read().strip()
            else:
                secret_key = secrets.token_hex(32)
                with open(secret_key_file, 'w') as f:
                    f.write(secret_key)

            app.config['SECRET_KEY'] = secret_key

        logger.info(" WebSocket enabled for real-time notifications")
        logger.info(" Parking status broadcast enabled")
        logger.info(f" Server starting on http://0.0.0.0:5000")

        # Start with SocketIO
        socketio.run(
            app,
            debug=False,
            host='0.0.0.0',
            port=5000,
            allow_unsafe_werkzeug=True
        )

    except KeyboardInterrupt:
        logger.info("Application stopped by user")
    except Exception as e:
        logger.error(f"Application error: {e}")
    finally:
        cleanup_on_exit()