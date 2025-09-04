import cv2
import torch
import logging
import numpy as np
from collections import deque
from datetime import datetime
import sqlite3
import time
import threading
from function.utils_rotate import deskew
from function.helper import read_plate
from ultralytics import YOLO
from torchvision import transforms
from PIL import Image, ImageEnhance
import torch.nn as nn

# ====== CÂN BẰNG HIỆU SUẤT VÀ CHẤT LƯỢNG ======
# Biến toàn cục
current_frame = None
current_plate = None

# CÂN BẰNG STREAMING VÀ DETECTION
video_frame_skip = 2  # Giảm xuống 2 (vừa mượt vừa không bỏ qua quá nhiều)
target_fps = 20  # 20 FPS vừa đủ mượt
frame_interval = 1.0 / target_fps

# Thread-safe locks
frame_lock = threading.Lock()
plate_lock = threading.Lock()

# Logging vừa phải
logging.basicConfig(
    filename='license_plate_detection.log',
    level=logging.INFO,  # Thay đổi lại INFO để debug
    format='%(asctime)s:%(levelname)s:%(message)s'
)

video_path = "static/video/cong.mp4"

# Models
yolo_LP_detect = None
yolo_license_plate = None  # Sẽ thay thế bằng custom model

# ====== THÊM CUSTOM OCR MODEL ARCHITECTURE ======
# CLASS MAPPING từ training code
CLASS_MAPPING = {
    0: '1', 1: '2', 2: '3', 3: '4', 4: '5', 5: '6', 6: '7', 7: '8', 8: '9',
    9: 'A', 10: 'B', 11: 'C', 12: 'D', 13: 'E', 14: 'F', 15: 'G', 16: 'H',
    17: 'K', 18: 'L', 19: 'M', 20: 'N', 21: 'P', 22: 'S', 23: 'T', 24: 'U',
    25: 'V', 26: 'X', 27: 'Y', 28: 'Z', 29: '0'
}

CHAR_TO_CLASS = {v: k for k, v in CLASS_MAPPING.items()}
NUM_CLASSES = 30


class LicensePlateOCR(nn.Module):
    def __init__(self, num_classes=NUM_CLASSES):
        super(LicensePlateOCR, self).__init__()

        # CNN feature extractor
        self.features = nn.Sequential(
            # Block 1
            nn.Conv2d(3, 64, kernel_size=3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),

            # Block 2
            nn.Conv2d(64, 128, kernel_size=3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),

            # Block 3
            nn.Conv2d(128, 256, kernel_size=3, padding=1),
            nn.BatchNorm2d(256),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),

            # Block 4
            nn.Conv2d(256, 512, kernel_size=3, padding=1),
            nn.BatchNorm2d(512),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool2d((4, 4))
        )

        # Classifier
        self.classifier = nn.Sequential(
            nn.Dropout(0.5),
            nn.Linear(512 * 4 * 4, 1024),
            nn.ReLU(inplace=True),
            nn.Dropout(0.5),
            nn.Linear(1024, 512),
            nn.ReLU(inplace=True),
            nn.Linear(512, num_classes)
        )

    def forward(self, x):
        x = self.features(x)
        x = x.view(x.size(0), -1)
        x = self.classifier(x)
        return x


# ====== LIGHTING ENHANCEMENT FUNCTIONS ======

def analyze_lighting_conditions(image):
    """
    Phân tích điều kiện ánh sáng của ảnh
    Returns: dict với thông tin về lighting condition
    """
    # Convert to grayscale for analysis
    if len(image.shape) == 3:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    else:
        gray = image

    # Tính toán các metrics
    mean_brightness = np.mean(gray)
    std_brightness = np.std(gray)

    # Histogram analysis
    hist = cv2.calcHist([gray], [0], None, [256], [0, 256])
    hist_norm = hist.ravel() / hist.max()

    # Detect lighting conditions
    lighting_info = {
        'mean_brightness': mean_brightness,
        'std_brightness': std_brightness,
        'is_dark': mean_brightness < 80,
        'is_bright': mean_brightness > 180,
        'is_low_contrast': std_brightness < 30,
        'is_high_contrast': std_brightness > 80,
        'has_shadows': np.sum(hist_norm[:50]) > 0.3,  # Nhiều pixel tối
        'has_highlights': np.sum(hist_norm[200:]) > 0.2,  # Nhiều pixel sáng
        'is_backlit': mean_brightness > 160 and std_brightness > 60
    }

    # Classify overall condition
    if lighting_info['is_dark']:
        lighting_info['condition'] = 'dark'
    elif lighting_info['is_bright'] and lighting_info['has_highlights']:
        lighting_info['condition'] = 'overexposed'
    elif lighting_info['is_backlit']:
        lighting_info['condition'] = 'backlit'
    elif lighting_info['is_low_contrast']:
        lighting_info['condition'] = 'low_contrast'
    elif lighting_info['has_shadows'] and lighting_info['has_highlights']:
        lighting_info['condition'] = 'mixed_lighting'
    else:
        lighting_info['condition'] = 'normal'

    return lighting_info


def adaptive_preprocessing(image, lighting_info):
    """
    Tiền xử lý thích ứng dựa trên điều kiện ánh sáng
    """
    processed = image.copy()
    condition = lighting_info['condition']

    if condition == 'dark':
        processed = enhance_dark_image(processed, lighting_info)
    elif condition == 'overexposed':
        processed = fix_overexposed_image(processed, lighting_info)
    elif condition == 'backlit':
        processed = fix_backlit_image(processed, lighting_info)
    elif condition == 'low_contrast':
        processed = enhance_low_contrast(processed, lighting_info)
    elif condition == 'mixed_lighting':
        processed = fix_mixed_lighting(processed, lighting_info)
    else:
        processed = standard_enhancement(processed)

    return processed


def enhance_dark_image(image, lighting_info):
    """Cải thiện ảnh tối"""
    # 1. Gamma correction
    gamma = 1.5 if lighting_info['mean_brightness'] < 50 else 1.2
    processed = adjust_gamma(image, gamma)

    # 2. CLAHE cho từng kênh màu
    lab = cv2.cvtColor(processed, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)

    # CLAHE aggressive cho ảnh tối
    clahe = cv2.createCLAHE(clipLimit=4.0, tileGridSize=(8, 8))
    l = clahe.apply(l)
    processed = cv2.merge([l, a, b])
    processed = cv2.cvtColor(processed, cv2.COLOR_LAB2BGR)

    # 3. Brightness boost
    processed = cv2.convertScaleAbs(processed, alpha=1.3, beta=40)

    # 4. Unsharp masking để tăng độ sắc nét
    processed = unsharp_mask(processed, amount=1.5)

    return processed


def fix_overexposed_image(image, lighting_info):
    """Sửa ảnh quá sáng/overexposed"""
    # 1. Reduce brightness và increase contrast
    processed = cv2.convertScaleAbs(image, alpha=1.2, beta=-30)

    # 2. Tone mapping cho highlights
    processed = tone_mapping(processed)

    # 3. CLAHE nhẹ
    lab = cv2.cvtColor(processed, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l = clahe.apply(l)
    processed = cv2.merge([l, a, b])
    processed = cv2.cvtColor(processed, cv2.COLOR_LAB2BGR)

    return processed


def fix_backlit_image(image, lighting_info):
    """Sửa ảnh ngược sáng"""
    # 1. Shadow/Highlight adjustment
    processed = shadow_highlight_adjustment(image)

    # 2. Local histogram equalization
    processed = local_histogram_equalization(processed)

    # 3. Adaptive gamma correction
    processed = adaptive_gamma_correction(processed)

    return processed


def enhance_low_contrast(image, lighting_info):
    """Tăng contrast cho ảnh mờ"""
    # 1. Histogram stretching
    processed = histogram_stretching(image)

    # 2. CLAHE aggressive
    lab = cv2.cvtColor(processed, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
    l = clahe.apply(l)
    processed = cv2.merge([l, a, b])
    processed = cv2.cvtColor(processed, cv2.COLOR_LAB2BGR)

    # 3. Contrast enhancement
    processed = cv2.convertScaleAbs(processed, alpha=1.5, beta=0)

    return processed


def fix_mixed_lighting(image, lighting_info):
    """Sửa ảnh có ánh sáng hỗn hợp"""
    # 1. Multi-scale retinex
    processed = multi_scale_retinex(image)

    # 2. Adaptive histogram equalization
    processed = adaptive_histogram_equalization(processed)

    # 3. Edge-preserving filter
    processed = cv2.edgePreservingFilter(processed, flags=1, sigma_s=50, sigma_r=0.4)

    return processed


def standard_enhancement(image):
    """Enhancement chuẩn cho điều kiện bình thường"""
    # 1. Mild contrast enhancement
    processed = cv2.convertScaleAbs(image, alpha=1.2, beta=10)

    # 2. Mild sharpening
    processed = unsharp_mask(processed, amount=1.0)

    # 3. Noise reduction
    processed = cv2.bilateralFilter(processed, 9, 75, 75)

    return processed


# ====== HELPER FUNCTIONS ======

def adjust_gamma(image, gamma=1.0):
    """Gamma correction"""
    inv_gamma = 1.0 / gamma
    table = np.array([((i / 255.0) ** inv_gamma) * 255 for i in np.arange(0, 256)]).astype("uint8")
    return cv2.LUT(image, table)


def unsharp_mask(image, amount=1.0, radius=1, threshold=0):
    """Unsharp masking for sharpening"""
    blurred = cv2.GaussianBlur(image, (0, 0), radius)
    sharpened = float(amount + 1) * image - float(amount) * blurred
    sharpened = np.maximum(sharpened, np.zeros(sharpened.shape))
    sharpened = np.minimum(sharpened, 255 * np.ones(sharpened.shape))
    sharpened = sharpened.round().astype(np.uint8)
    if threshold > 0:
        low_contrast_mask = np.absolute(image - blurred) < threshold
        np.copyto(sharpened, image, where=low_contrast_mask)
    return sharpened


def tone_mapping(image):
    """Simple tone mapping"""
    # Convert to float
    img_float = image.astype(np.float32) / 255.0

    # Apply tone mapping
    mapped = np.power(img_float, 0.6)

    # Convert back
    return (mapped * 255).astype(np.uint8)


def shadow_highlight_adjustment(image, shadow_amount=0.5, highlight_amount=-0.3):
    """Adjust shadows and highlights separately"""
    # Convert to LAB
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)

    # Create shadow and highlight masks
    shadow_mask = l < 127
    highlight_mask = l > 127

    # Adjust shadows
    l[shadow_mask] = np.clip(l[shadow_mask] * (1 + shadow_amount), 0, 255)

    # Adjust highlights
    l[highlight_mask] = np.clip(l[highlight_mask] * (1 + highlight_amount), 0, 255)

    # Merge and convert back
    processed = cv2.merge([l, a, b])
    return cv2.cvtColor(processed, cv2.COLOR_LAB2BGR)


def local_histogram_equalization(image, grid_size=8):
    """Local histogram equalization"""
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)

    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(grid_size, grid_size))
    l = clahe.apply(l)

    processed = cv2.merge([l, a, b])
    return cv2.cvtColor(processed, cv2.COLOR_LAB2BGR)


def adaptive_gamma_correction(image):
    """Gamma correction thích ứng"""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    mean_brightness = np.mean(gray)

    # Calculate adaptive gamma
    if mean_brightness < 80:
        gamma = 1.5
    elif mean_brightness > 180:
        gamma = 0.7
    else:
        gamma = 1.0

    return adjust_gamma(image, gamma)


def histogram_stretching(image):
    """Histogram stretching for contrast enhancement"""
    processed = image.copy()

    for i in range(3):  # For each color channel
        channel = processed[:, :, i]
        # Calculate min and max
        min_val = np.percentile(channel, 1)
        max_val = np.percentile(channel, 99)

        # Stretch histogram
        if max_val > min_val:
            processed[:, :, i] = np.clip(255 * (channel - min_val) / (max_val - min_val), 0, 255)

    return processed


def multi_scale_retinex(image, scales=[15, 80, 250]):
    """Multi-scale Retinex algorithm"""
    image_float = image.astype(np.float32)

    # Avoid log(0)
    image_float = np.maximum(image_float, 1.0)

    retinex = np.zeros_like(image_float)

    for scale in scales:
        # Gaussian blur
        blurred = cv2.GaussianBlur(image_float, (0, 0), scale)
        blurred = np.maximum(blurred, 1.0)

        # Retinex calculation
        retinex += np.log(image_float) - np.log(blurred)

    retinex = retinex / len(scales)

    # Normalize to 0-255
    retinex = (retinex - retinex.min()) / (retinex.max() - retinex.min()) * 255

    return retinex.astype(np.uint8)


def adaptive_histogram_equalization(image):
    """Adaptive histogram equalization"""
    # Convert to YUV
    yuv = cv2.cvtColor(image, cv2.COLOR_BGR2YUV)
    y, u, v = cv2.split(yuv)

    # Apply CLAHE to Y channel
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    y_eq = clahe.apply(y)

    # Merge and convert back
    yuv_eq = cv2.merge([y_eq, u, v])
    return cv2.cvtColor(yuv_eq, cv2.COLOR_YUV2BGR)


def apply_aggressive_clahe(image):
    """Apply aggressive CLAHE"""
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=5.0, tileGridSize=(4, 4))
    l = clahe.apply(l)
    processed = cv2.merge([l, a, b])
    return cv2.cvtColor(processed, cv2.COLOR_LAB2BGR)


def generate_preprocessing_variants(image, lighting_info):
    """
    Tạo các biến thể preprocessing dựa trên điều kiện ánh sáng
    """
    variants = {}
    condition = lighting_info['condition']

    # Base variant
    variants['base'] = image.copy()

    if condition in ['dark', 'low_contrast']:
        # Extra variants for dark/low contrast images
        variants['high_gamma'] = adjust_gamma(image, 1.8)
        variants['clahe_aggressive'] = apply_aggressive_clahe(image)
        variants['brightness_boost'] = cv2.convertScaleAbs(image, alpha=1.5, beta=50)

    elif condition in ['overexposed', 'bright']:
        # Variants for bright images
        variants['low_gamma'] = adjust_gamma(image, 0.6)
        variants['tone_mapped'] = tone_mapping(image)
        variants['brightness_reduce'] = cv2.convertScaleAbs(image, alpha=1.0, beta=-40)

    elif condition == 'backlit':
        # Variants for backlit images
        variants['shadow_adjusted'] = shadow_highlight_adjustment(image, 0.8, -0.5)
        variants['retinex'] = multi_scale_retinex(image)
        variants['adaptive_eq'] = adaptive_histogram_equalization(image)

    elif condition == 'mixed_lighting':
        # Variants for mixed lighting
        variants['retinex'] = multi_scale_retinex(image)
        variants['edge_preserving'] = cv2.edgePreservingFilter(image, flags=1, sigma_s=50, sigma_r=0.4)
        variants['local_eq'] = local_histogram_equalization(image)

    # Common variants for all conditions
    variants['sharpened'] = unsharp_mask(image, amount=1.5)
    variants['bilateral'] = cv2.bilateralFilter(image, 9, 75, 75)

    # Common variants for all conditions ...
    variants['sharpened'] = unsharp_mask(image, amount=1.5)
    variants['bilateral'] = cv2.bilateralFilter(image, 9, 75, 75)

    #các biến thể xoay để cứu ảnh nghiêng
    (h, w) = image.shape[:2]
    center = (w // 2, h // 2)
    for ang in (-20, -12, -6, 6, 12, 20):
        M = cv2.getRotationMatrix2D(center, ang, 1.0)
        rotated = cv2.warpAffine(image, M, (w, h), borderMode=cv2.BORDER_REPLICATE)
        variants[f'rot_{ang}'] = rotated

    return variants


# ====== CUSTOM OCR FUNCTIONS ======
def get_ocr_transform():
    """Transform cho OCR model"""
    return transforms.Compose([
        transforms.Resize((64, 64)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])


def improved_character_ordering(annotations, img_width, img_height):
    """Sắp xếp ký tự cải thiện"""
    if len(annotations) == 0:
        return annotations

    # Chuyển sang pixel coordinates
    pixel_annotations = []
    for ann in annotations:
        x_center = ann['x_center'] * img_width
        y_center = ann['y_center'] * img_height
        pixel_annotations.append({
            **ann,
            'x_center_px': x_center,
            'y_center_px': y_center
        })

    # Phát hiện layout (1 hàng hay 2 hàng)
    y_coords = [ann['y_center_px'] for ann in pixel_annotations]
    y_std = np.std(y_coords)
    avg_height = np.mean([ann['height'] * img_height for ann in pixel_annotations])

    if y_std > avg_height / 3 and len(pixel_annotations) > 4:
        # Biển số 2 hàng
        return two_row_sorting(pixel_annotations)
    else:
        # Biển số 1 hàng
        return sorted(pixel_annotations, key=lambda x: x['x_center_px'])


def two_row_sorting(annotations):
    """Sắp xếp cho biển số 2 hàng"""
    try:
        from sklearn.cluster import KMeans

        y_coords = np.array([ann['y_center_px'] for ann in annotations]).reshape(-1, 1)
        kmeans = KMeans(n_clusters=2, random_state=42, n_init=10)
        labels = kmeans.fit_predict(y_coords)

        # Phân chia thành 2 hàng
        row1, row2 = [], []
        for i, label in enumerate(labels):
            if label == 0:
                row1.append(annotations[i])
            else:
                row2.append(annotations[i])

        # Xác định hàng trên và hàng dưới
        avg_y1 = np.mean([ann['y_center_px'] for ann in row1])
        avg_y2 = np.mean([ann['y_center_px'] for ann in row2])

        if avg_y1 > avg_y2:
            row1, row2 = row2, row1

        # Sắp xếp từng hàng theo x
        row1.sort(key=lambda x: x['x_center_px'])
        row2.sort(key=lambda x: x['x_center_px'])

        return row1 + row2
    except:
        # Fallback: sắp xếp theo x
        return sorted(annotations, key=lambda x: x['x_center_px'])


def process_single_variant(model, image, transform, device):
    """Process single preprocessing variant"""
    # Convert to PIL if needed
    if isinstance(image, np.ndarray):
        image_pil = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
    else:
        image_pil = image

    # Use existing custom_read_plate logic
    if hasattr(model, 'predict'):
        # YOLO character detection
        results = model.predict(np.array(image_pil), conf=0.15, verbose=False)

        if len(results) > 0 and len(results[0].boxes) > 0:
            # Process detections (same as original logic)
            annotations = []
            img_width, img_height = image_pil.size

            for box in results[0].boxes:
                x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
                class_id = int(box.cls[0].cpu().numpy())
                conf = box.conf[0].cpu().numpy()

                if class_id not in CLASS_MAPPING:
                    continue

                x_center = (x1 + x2) / 2 / img_width
                y_center = (y1 + y2) / 2 / img_height
                width = (x2 - x1) / img_width
                height = (y2 - y1) / img_height

                annotations.append({
                    'class_id': class_id,
                    'x_center': x_center,
                    'y_center': y_center,
                    'width': width,
                    'height': height,
                    'character': CLASS_MAPPING[class_id],
                    'confidence': conf
                })



            # Filter and sort
            annotations = [ann for ann in annotations if ann['confidence'] > 0.25]
            sorted_annotations = improved_character_ordering(annotations, img_width, img_height)


            # Build text
            plate_chars = []
            for ann in sorted_annotations:
                if ann['confidence'] > 0.20:
                    plate_chars.append(ann['character'])

            plate_text = ''.join(plate_chars)
            return post_process_plate_text(plate_text) if plate_text else "unknown"


    return "unknown"


def enhanced_custom_read_plate(model, image, transform, device='cuda'):
    """
    OCR cải tiến với adaptive preprocessing
    """
    try:
        if model is None:
            return "unknown"

        # Analyze lighting conditions
        lighting_info = analyze_lighting_conditions(image)
        logging.info(f"Lighting condition detected: {lighting_info['condition']}")

        # Apply adaptive preprocessing
        processed_image = adaptive_preprocessing(image, lighting_info)

        # Multiple preprocessing variants based on lighting
        preprocessing_variants = generate_preprocessing_variants(processed_image, lighting_info)

        best_result = None
        best_confidence = 0

        # Try each variant
        for variant_name, variant_image in preprocessing_variants.items():
            try:
                result = process_single_variant(model, variant_image, transform, device)

                if result and result != "unknown":
                    # Calculate confidence based on plate validity
                    confidence = calculate_plate_confidence(result, lighting_info)

                    if confidence > best_confidence:
                        best_confidence = confidence
                        best_result = result

                    logging.info(f"Variant {variant_name}: {result} (conf: {confidence:.3f})")

            except Exception as e:
                logging.error(f"Error processing variant {variant_name}: {e}")
                continue

        return best_result if best_result else "unknown"

    except Exception as e:
        logging.error(f"Enhanced OCR error: {e}")
        return "unknown"


def calculate_plate_confidence(plate_text, lighting_info):
    """
    Tính confidence dựa trên plate validity và lighting condition
    """
    base_confidence = calculate_plate_validity_score(plate_text)

    # Adjust confidence based on lighting condition
    condition = lighting_info['condition']

    if condition == 'normal':
        lighting_bonus = 0.2
    elif condition in ['dark', 'overexposed']:
        lighting_bonus = 0.1  # Harder conditions
    elif condition == 'backlit':
        lighting_bonus = 0.05  # Very difficult
    else:
        lighting_bonus = 0.15

    return min(base_confidence + lighting_bonus, 1.0)


def custom_read_plate(model, image, transform, device='cuda'):
    """
    Thay thế function read_plate gốc
    Sử dụng custom model để đọc biển số
    """
    try:
        if model is None:
            return "unknown"

        # Chuyển CV2 image sang PIL
        if isinstance(image, np.ndarray):
            image_pil = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
        else:
            image_pil = image

        # Preprocessing image cho better OCR
        image_np = np.array(image_pil)

        # 1. Resize image nếu quá nhỏ
        height, width = image_np.shape[:2]
        if height < 50 or width < 150:
            scale_factor = max(50 / height, 150 / width)
            new_width = int(width * scale_factor)
            new_height = int(height * scale_factor)
            image_np = cv2.resize(image_np, (new_width, new_height))

        # 2. Enhance contrast
        image_np = cv2.convertScaleAbs(image_np, alpha=1.5, beta=30)

        # 3. Denoising
        image_np = cv2.fastNlMeansDenoisingColored(image_np, None, 10, 10, 7, 21)

        # 4. Sharpening
        kernel = np.array([[-1, -1, -1], [-1, 9, -1], [-1, -1, -1]])
        image_np = cv2.filter2D(image_np, -1, kernel)

        # Convert back to PIL
        image_pil = Image.fromarray(image_np)

        # Nếu có YOLO character detection
        if hasattr(model, 'predict'):
            # Sử dụng YOLO character detection với confidence thấp hơn
            results = model.predict(image_np, conf=0.2, verbose=False)

            if len(results) > 0 and len(results[0].boxes) > 0:
                annotations = []
                img_width, img_height = image_pil.size

                for box in results[0].boxes:
                    x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
                    class_id = int(box.cls[0].cpu().numpy())
                    conf = box.conf[0].cpu().numpy()

                    # Validate class_id
                    if class_id not in CLASS_MAPPING:
                        continue

                    # Chuyển sang normalized coordinates
                    x_center = (x1 + x2) / 2 / img_width
                    y_center = (y1 + y2) / 2 / img_height
                    width = (x2 - x1) / img_width
                    height = (y2 - y1) / img_height

                    annotations.append({
                        'class_id': class_id,
                        'x_center': x_center,
                        'y_center': y_center,
                        'width': width,
                        'height': height,
                        'character': CLASS_MAPPING[class_id],
                        'confidence': conf
                    })

                # Lọc các detection có confidence thấp
                annotations = [ann for ann in annotations if ann['confidence'] > 0.3]

                # Sắp xếp ký tự
                sorted_annotations = improved_character_ordering(annotations, img_width, img_height)

                # Tạo text với confidence filtering
                plate_chars = []
                for ann in sorted_annotations:
                    if ann['confidence'] > 0.4:  # Chỉ lấy những ký tự có confidence cao
                        plate_chars.append(ann['character'])

                plate_text = ''.join(plate_chars)

                # Post-processing: Fix common OCR errors
                plate_text = post_process_plate_text(plate_text)

                return plate_text if plate_text else "unknown"

        # Nếu không có character detection, sử dụng classification model
        # (cần có detection trước)
        return "unknown"

    except Exception as e:
        logging.error(f"Custom OCR error: {e}")
        return "unknown"


def post_process_plate_text(text: str) -> str:
    """
    Chuẩn hoá chuỗi OCR theo định dạng biển số VN.
    - Sửa lỗi OCR (O->0, I/L->1, S->5, B->8, G->6, Q->0)
    - Bỏ ký tự thừa (kể cả 'D-' đứng trước)
    - Tự chèn '-' và '.' theo quy tắc phổ biến
    """
    if not text:
        return text

    import re

    # 1) Chuẩn hoá chữ/số dễ nhầm
    t = text.upper()
    corrections = {'O': '0', 'Q': '0', 'I': '1', 'L': '1', 'S': '5', 'B': '8', 'G': '6'}
    t = ''.join(corrections.get(c, c) for c in t)

    # 2) Giữ lại chỉ A-Z, 0-9 để tách lõi biển số (loại bỏ mọi dấu, kể cả 'D-' ở đầu)
    t = ''.join(ch for ch in t if ch.isalnum())

    # 3) Thử tách 2 mẫu: ô tô (XXA + 5–6 số) và xe máy (XXA1 + 4–5 số)
    candidates = []
    m_car  = re.search(r'(\d{2}[A-Z])(\d{4,6})', t)      # ví dụ: 51F + 244003
    m_bike = re.search(r'(\d{2}[A-Z]\d)(\d{4,5})', t)    # ví dụ: 59C1 + 12345
    if m_car:  candidates.append(m_car.groups())
    if m_bike: candidates.append(m_bike.groups())

    def effective_len(nums: str) -> int:
        # Nếu có 6 số và số thứ 4 là '0' → nhiều case thực chất là 5 số (mất dấu chấm): 244003 -> 244|03
        return 5 if (len(nums) == 6 and nums[3] == '0') else len(nums)

    def format_out(series: str, nums: str) -> str:
        if len(nums) == 6 and nums[3] == '0':
            nums = nums[:3] + nums[-2:]         # 244003 -> 24403
        if len(nums) == 5:
            return f"{series}-{nums[:3]}.{nums[3:]}"  # 01204 -> 012.04
        if len(nums) == 6:
            return f"{series}-{nums[:3]}.{nums[3:]}"  # 123456 -> 123.456
        if len(nums) == 4:
            return f"{series}-{nums}"                 # 1234 -> 1234 (cũ)
        return f"{series}-{nums}"

    if candidates:
        # Ưu tiên cách tách cho ra 5 số (phổ biến), rồi tới series ngắn (ô tô) nếu hoà
        candidates.sort(key=lambda sg: (effective_len(sg[1]) != 5, len(sg[0])))
        series, nums = candidates[0]
        return format_out(series, nums)

    # Fallback: tách lõi bất kỳ nếu có
    m_any = re.search(r'(\d{2}[A-Z]\d?)(\d{4,6})', t)
    if m_any:
        series, nums = m_any.groups()
        return format_out(series, nums)

    # Không tách được thì trả về chuỗi đã làm sạch
    return t



def predict_single_character(model, char_image, transform, device='cuda'):
    """Predict single character using classification model"""
    try:
        model.eval()
        with torch.no_grad():
            # Chuyển CV2 image sang PIL
            if isinstance(char_image, np.ndarray):
                char_pil = Image.fromarray(cv2.cvtColor(char_image, cv2.COLOR_BGR2RGB))
            else:
                char_pil = char_image

            # Resize nếu quá nhỏ
            if char_pil.size[0] < 20 or char_pil.size[1] < 20:
                char_pil = char_pil.resize((32, 32), Image.LANCZOS)

            # Transform
            char_tensor = transform(char_pil).unsqueeze(0).to(device)

            # Predict
            output = model(char_tensor)
            probs = torch.softmax(output, dim=1)
            confidence, predicted = torch.max(probs, 1)

            predicted_char = CLASS_MAPPING[predicted.item()]
            return predicted_char, confidence.item()

    except Exception as e:
        logging.error(f"Single character prediction error: {e}")
        return '?', 0.0


# CÂN BẰNG HIỆU SUẤT VÀ CHẤT LƯỢNG - ORIGINAL CONFIG
CONFIDENCE_THRESHOLD = 0.15  # Giảm xuống để detect nhiều hơn
MIN_PLATE_AREA = 500  # Giảm area minimum
MAX_PLATE_AREA = 80000  # Tăng area maximum
ASPECT_RATIO_MIN = 1.2
ASPECT_RATIO_MAX = 7.0  # Tăng để catch biển số dài
QUEUE_SIZE = 5
DB_PATH = 'license_plates.db'

# Detection intervals - CÂN BẰNG
detection_interval = 0.5  # Giảm xuống 0.5s để detect thường xuyên hơn

# Biến cache
models_loaded = False
ocr_transform = None


def load_models():
    """Load models với custom OCR"""
    global yolo_LP_detect, yolo_license_plate, models_loaded, ocr_transform
    if not models_loaded:
        try:
            # Load YOLO detection model
            yolo_LP_detect = YOLO('model/best.pt')
            yolo_LP_detect.overrides = {
                'conf': 0.1,
                'iou': 0.4,
                'agnostic_nms': False,
                'max_det': 20,
                'classes': None,
                'half': True,
            }

            # Load custom OCR model
            device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
            yolo_license_plate = LicensePlateOCR(num_classes=NUM_CLASSES).to(device)

            # Load trained weights
            try:
                yolo_license_plate.load_state_dict(
                    torch.load('model/best_license_plate_model.pth', map_location=device))
                yolo_license_plate.eval()
                logging.info("Custom OCR model loaded successfully from model/best_license_plate_model.pth")
                yolo_license_plate = torch.hub.load('./yolov5', 'custom', path='model/LP_ocr.pt', source='local')
                logging.info("Override to YOLO char-detector for OCR")
            except FileNotFoundError:
                logging.error("Custom OCR model file not found: model/best_license_plate_model.pth")
                # Fallback to YOLO OCR
                yolo_license_plate = torch.hub.load('./yolov5', 'custom', path='model/LP_ocr.pt', source='local')
                logging.info("Fallback to YOLO OCR model")

            # Setup transform
            ocr_transform = get_ocr_transform()

            models_loaded = True
            logging.info("Models loaded with custom OCR configuration")
        except Exception as e:
            logging.error(f"Model loading error: {e}")


def setup_database():
    """Setup database"""
    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS license_plates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plate_number TEXT NOT NULL,
                confidence FLOAT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                detection_method TEXT
            )
        ''')
        conn.commit()
        conn.close()
    except Exception as e:
        logging.error(f"Database setup error: {e}")


def is_valid_license_plate(plate: str) -> bool:
    """Kiểm tra biển số VN cho cả xe hơi (dài/ngắn) và xe máy"""
    if not plate or plate.lower() == "unknown" or len(plate) < 5 or len(plate) > 12:
        return False

    import re
    plate_clean = plate.strip().upper()

    # Các pattern phổ biến biển số VN
    patterns = [

        r"^\d{2}[A-Z]-\d{3}\\d{2}$",
        r"^\d{2}[A-Z]-\d{4,6}$",
        r"^\d{2}[A-Z]\d-\d{3,5}$",
        r"^\d{2}[A-Z]\d{4,6}$",


        r"^\d{2}[A-Z]-\d{3}\\d{2}$",
        r"^\d{2}[A-Z]-\d{5}$",
        r"^\d{2}[A-Z]\d{3}\\d{2}$",
        r"^\d{2}[A-Z]\d{5}$",


        r"^\d{2}[A-Z]-\d{3,4}$",
        r"^\d{2}[A-Z]\d-\d{4}$",
    ]

    for pattern in patterns:
        if re.match(pattern, plate_clean):
            return True

    # Fallback: kiểm tra logic cơ bản (có số + chữ, không chứa ký tự lạ)
    has_digit = any(c.isdigit() for c in plate_clean)
    has_alpha = any(c.isalpha() for c in plate_clean)
    valid_chars = set("0123456789ABCDEFGHKLMNPSTUVXYZ-.")
    if has_digit and has_alpha and all(c in valid_chars for c in plate_clean):
        return True

    return False


def enhance_frame_for_detection(frame):
    """Tiền xử lý frame để tăng khả năng detection"""
    try:
        # 1. Tăng contrast và brightness
        enhanced = cv2.convertScaleAbs(frame, alpha=1.3, beta=25)

        # 2. Làm sắc nét
        kernel = np.array([[-1, -1, -1], [-1, 9, -1], [-1, -1, -1]])
        sharpened = cv2.filter2D(enhanced, -1, kernel)

        # 3. CLAHE cho kênh sáng
        lab = cv2.cvtColor(sharpened, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        l = clahe.apply(l)
        final = cv2.merge([l, a, b])
        final = cv2.cvtColor(final, cv2.COLOR_LAB2BGR)

        return final
    except:
        return frame


def multi_scale_detection_optimized(frame):
    """Multi-scale detection được tối ưu"""
    if not models_loaded:
        load_models()

    all_detections = []

    # Thử 2 kích thước thay vì 1
    sizes = [640, 800]  # Bớt size để tăng tốc nhưng vẫn đủ chất lượng

    for size in sizes:
        try:
            # Resize frame cho detection
            height, width = frame.shape[:2]
            if max(height, width) > size:
                scale = size / max(height, width)
                new_width = int(width * scale)
                new_height = int(height * scale)
                resized_frame = cv2.resize(frame, (new_width, new_height))
            else:
                resized_frame = frame.copy()
                scale = 1.0

            # Detection với confidence thấp
            results = yolo_LP_detect(resized_frame, imgsz=size, conf=0.08, iou=0.4, max_det=15)

            for result in results:
                if result.boxes is not None:
                    for box in result.boxes:
                        x1, y1, x2, y2 = box.xyxy[0].cpu().numpy()
                        conf = box.conf[0].cpu().numpy()

                        # Scale coordinates back
                        if scale != 1.0:
                            x1, x2 = x1 / scale, x2 / scale
                            y1, y2 = y1 / scale, y2 / scale

                        x1, y1, x2, y2 = int(x1), int(y1), int(x2), int(y2)

                        # Kiểm tra kích thước hợp lệ
                        width_det = x2 - x1
                        height_det = y2 - y1
                        area = width_det * height_det
                        aspect_ratio = width_det / height_det if height_det > 0 else 0

                        if (MIN_PLATE_AREA <= area <= MAX_PLATE_AREA and
                                ASPECT_RATIO_MIN <= aspect_ratio <= ASPECT_RATIO_MAX):
                            all_detections.append([x1, y1, x2, y2, conf, size])

        except Exception as e:
            logging.error(f"Detection error at size {size}: {e}")
            continue

    return all_detections


def enhanced_ocr_processing_with_lighting(frame, detections):
    valid_plates = []
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    detections = sorted(detections, key=lambda x: x[4], reverse=True)

    for detection in detections[:3]:
        x1, y1, x2, y2, conf, size_used = detection

        # padding rộng hơn để không mất ký tự đầu/cuối
        w = x2 - x1;
        h = y2 - y1
        pad = int(0.12 * max(w, h))
        x1p = max(0, x1 - pad); y1p = max(0, y1 - pad)
        x2p = min(frame.shape[1], x2 + pad); y2p = min(frame.shape[0], y2 + pad)
        crop = frame[y1p:y2p, x1p:x2p]
        if crop.size == 0:
            continue

        # deskew nếu hơi nghiêng
        try:
            crop = deskew(crop)
        except:
            pass
        crop = cv2.copyMakeBorder(crop, 8, 8, 8, 8, cv2.BORDER_REPLICATE)

        try:
            # Ưu tiên path YOLO char-detector (có .predict)
            if hasattr(yolo_license_plate, 'predict'):
                plate_text = enhanced_custom_read_plate(yolo_license_plate, crop, ocr_transform, device)
            else:
                # fallback helper cũ
                lighting = analyze_lighting_conditions(crop)
                raw_text = read_plate(yolo_license_plate, adaptive_preprocessing(crop, lighting))
                plate_text = post_process_plate_text(raw_text)

            if plate_text and plate_text != "unknown" and is_valid_license_plate(plate_text):
                validity = calculate_plate_validity_score(plate_text)
                w = x2 - x1; h = y2 - y1
                plate_type = "long" if (w / max(h, 1e-6)) > 3.0 else "square"
                valid_plates.append({
                    'text': plate_text, 'confidence': conf, 'bbox': [x1, y1, x2, y2],
                    'method': 'enhanced_lighting', 'type': plate_type, 'size_used': size_used,
                    'validity_score': validity
                })
        except Exception as e:
            logging.error(f"OCR error: {e}")
            continue

    return valid_plates



def improve_crop_quality(crop_img):
    """Cải thiện chất lượng crop image"""
    try:
        # 1. Resize nếu quá nhỏ
        height, width = crop_img.shape[:2]
        if height < 40 or width < 120:
            scale_factor = max(40 / height, 120 / width)
            new_width = int(width * scale_factor)
            new_height = int(height * scale_factor)
            crop_img = cv2.resize(crop_img, (new_width, new_height), interpolation=cv2.INTER_CUBIC)

        # 2. Gaussian blur để reduce noise
        crop_img = cv2.GaussianBlur(crop_img, (3, 3), 0)

        # 3. Bilateral filter để preserve edges
        crop_img = cv2.bilateralFilter(crop_img, 9, 75, 75)

        return crop_img
    except:
        return crop_img


def calculate_plate_validity_score(plate_text):
    """Tính điểm hợp lệ của biển số"""
    if not plate_text:
        return 0

    score = 0

    # 1. Độ dài hợp lệ
    if 6 <= len(plate_text) <= 10:
        score += 0.3
    elif 5 <= len(plate_text) <= 11:
        score += 0.2

    # 2. Có chữ và số
    has_letter = any(c.isalpha() for c in plate_text)
    has_digit = any(c.isdigit() for c in plate_text)
    if has_letter and has_digit:
        score += 0.3

    # 3. Format pattern (VN license plate)
    import re
    patterns = [
        r'^\d{2}[A-Z]-?\d{3,6}',  # 61A-12345 hoặc 61A12345
        r'^\d{2}[A-Z]\d-?\d{3,5}',  # 61A1-2345
    ]

    for pattern in patterns:
        if re.match(pattern, plate_text.upper()):
            score += 0.4
            break

    # 4. Không có ký tự lạ
    valid_chars = set('0123456789ABCDEFGHKLMNPSTUVXYZ-.')
    if all(c in valid_chars for c in plate_text.upper()):
        score += 0.2

    return min(score, 1.0)


def generate_frames():
    """Generator frames cân bằng hiệu suất và chất lượng"""
    global current_frame, current_plate

    # Khởi tạo biến local
    local_last_detection_time = 0
    last_frame_time = 0

    vid = cv2.VideoCapture(video_path)

    # Cấu hình video capture
    vid.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    vid.set(cv2.CAP_PROP_FPS, 25)

    frame_count = 0

    # Pre-load models
    load_models()
    logging.info("Starting balanced detection system with enhanced lighting OCR")

    while vid.isOpened():
        ret, frame = vid.read()
        if not ret:
            vid.set(cv2.CAP_PROP_POS_FRAMES, 0)
            frame_count = 0
            continue

        frame_count += 1
        current_time = time.time()

        # Frame skipping để tăng tốc
        if frame_count % video_frame_skip != 0:
            continue

        # Resize frame cho streaming (nhưng giữ nguyên cho detection)
        display_frame = frame.copy()
        try:
            height, width = display_frame.shape[:2]
            if width > 900:  # Resize cho streaming
                scale = 900 / width
                new_width = int(width * scale)
                new_height = int(height * scale)
                display_frame = cv2.resize(display_frame, (new_width, new_height))
        except Exception as e:
            logging.error(f"Frame resize error: {e}")
            continue

        # Cập nhật current_frame
        with frame_lock:
            current_frame = frame.copy()  # Lưu frame gốc để detection

        # Detection với interval vừa phải
        should_detect = (current_time - local_last_detection_time) >= detection_interval

        if should_detect:
            try:
                # Multi-scale detection trên frame gốc
                all_detections = multi_scale_detection_optimized(frame)

                if all_detections:
                    logging.info(f"Found {len(all_detections)} potential detections")

                    # Enhanced OCR processing with lighting adaptation
                    valid_plates = enhanced_ocr_processing_with_lighting(frame, all_detections)

                    if valid_plates:
                        # Chọn plate tốt nhất theo validity score
                        best_plate = max(valid_plates, key=lambda x: x.get('validity_score', x['confidence']))

                        with plate_lock:
                            current_plate = best_plate['text']

                        logging.info(
                            f"Best plate: {best_plate['text']} (conf: {best_plate['confidence']:.3f}, score: {best_plate.get('validity_score', 0):.3f})")

                        # Save to database (async)
                        threading.Thread(
                            target=save_to_database_async,
                            args=(best_plate['text'], best_plate['confidence'], best_plate['method']),
                            daemon=True
                        ).start()

                        # Vẽ kết quả trên display frame (scale coordinates)
                        try:
                            scale_x = display_frame.shape[1] / frame.shape[1]
                            scale_y = display_frame.shape[0] / frame.shape[0]

                            x1, y1, x2, y2 = best_plate['bbox']
                            x1_disp = int(x1 * scale_x)
                            y1_disp = int(y1 * scale_y)
                            x2_disp = int(x2 * scale_x)
                            y2_disp = int(y2 * scale_y)

                            color = (0, 255, 0) if best_plate['type'] == 'long' else (255, 0, 0)
                            cv2.rectangle(display_frame, (x1_disp, y1_disp), (x2_disp, y2_disp), color, 2)

                            # Label với background
                            label = f"{best_plate['text']} ({best_plate['confidence']:.2f})"
                            font_scale = 0.6
                            thickness = 2
                            font = cv2.FONT_HERSHEY_SIMPLEX

                            (text_width, text_height), baseline = cv2.getTextSize(label, font, font_scale, thickness)
                            cv2.rectangle(display_frame, (x1_disp, y1_disp - text_height - 8),
                                          (x1_disp + text_width, y1_disp), color, -1)
                            cv2.putText(display_frame, label, (x1_disp, y1_disp - 4),
                                        font, font_scale, (255, 255, 255), thickness)
                        except Exception as e:
                            logging.error(f"Drawing error: {e}")
                    else:
                        logging.info("No valid plates found after enhanced OCR")
                else:
                    logging.info("No detections found")

                local_last_detection_time = current_time

            except Exception as e:
                logging.error(f"Detection error: {e}")

        # Encode frame với chất lượng vừa phải
        try:
            encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), 80]  # Tăng quality lên 80
            _, buffer = cv2.imencode('.jpg', display_frame, encode_param)
            frame_bytes = buffer.tobytes()

            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n\r\n')
        except Exception as e:
            logging.error(f"Frame encoding error: {e}")
            continue

        # Frame timing control
        current_frame_time = time.time()
        if current_frame_time - last_frame_time < frame_interval:
            time.sleep(frame_interval - (current_frame_time - last_frame_time))
        last_frame_time = current_frame_time

    vid.release()


def save_to_database_async(plate, confidence, method="enhanced_lighting"):
    """Async save database"""
    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        cursor.execute(
            "INSERT INTO license_plates (plate_number, confidence, detection_method) VALUES (?, ?, ?)",
            (plate, confidence, method)
        )
        conn.commit()
        conn.close()
        logging.info(f"Saved to DB: {plate} (conf: {confidence:.3f})")
    except Exception as e:
        logging.error(f"Database error: {e}")


def get_detected_plates():
    """Lấy danh sách biển số từ database"""
    try:
        conn = sqlite3.connect(DB_PATH)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT plate_number, confidence, timestamp, detection_method FROM license_plates ORDER BY timestamp DESC LIMIT 100")
        plates = cursor.fetchall()
        conn.close()
        return plates
    except Exception as e:
        logging.error(f"Error getting detected plates: {e}")
        return []


def get_current_frame():
    """Lấy frame hiện tại"""
    global current_frame
    with frame_lock:
        if current_frame is None:
            return False, None
        return True, current_frame.copy()


def get_current_plate():
    """Lấy biển số hiện tại"""
    global current_plate
    with plate_lock:
        return current_plate


def set_video_speed(speed='normal'):
    """Điều chỉnh tốc độ video"""
    global video_frame_skip, target_fps, frame_interval, detection_interval

    if speed == 'slow':
        video_frame_skip = 1
        target_fps = 15
        detection_interval = 0.3
    elif speed == 'normal':
        video_frame_skip = 2
        target_fps = 20
        detection_interval = 0.5
    elif speed == 'fast':
        video_frame_skip = 3
        target_fps = 25
        detection_interval = 0.8

    frame_interval = 1.0 / target_fps
    logging.info(f"Video speed set to {speed}")


def cleanup_resources():
    """Giải phóng tài nguyên"""
    global yolo_LP_detect, yolo_license_plate, models_loaded
    try:
        if yolo_LP_detect is not None:
            del yolo_LP_detect
        if yolo_license_plate is not None:
            del yolo_license_plate
        models_loaded = False
        logging.info("Resources cleaned up")
    except Exception as e:
        logging.error(f"Cleanup error: {e}")


# ==== WRAPPER FUNCTIONS ====
def process_frame_for_plate(frame):
    """Wrapper function tương thích với enhanced OCR"""
    try:
        if not models_loaded:
            load_models()

        device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        detections = multi_scale_detection_optimized(frame)

        if detections:
            valid_plates = enhanced_ocr_processing_with_lighting(frame, detections)
            if valid_plates:
                return valid_plates[0]['text']
        return "unknown"
    except Exception as e:
        logging.error(f"process_frame_for_plate error: {e}")
        return "unknown"


def optimized_background_detection(frame):
    """Background detection với enhanced lighting model"""
    return process_frame_for_plate(frame)


def process_plate_buffer():
    """Compatibility function"""
    return None, 0


def save_to_database(plate, confidence, method="manual", plate_type="auto"):
    """Sync save function"""
    save_to_database_async(plate, confidence, method)


# Add missing attributes for app.py compatibility
detection_queue = None
result_queue = None
latest_detections = []

def start_detection_threads():
    """Start detection threads - placeholder for compatibility"""
    pass

# Initialize database
setup_database()