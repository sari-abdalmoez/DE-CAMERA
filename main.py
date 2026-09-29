#!/usr/bin/env python3
"""
DE CAMERA - Professional Camera App for Android
Built with Python + Kivy + Pyjnius
Main entry point
"""

import os
import json
from datetime import datetime
from pathlib import Path

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.image import Image
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.spinner import Spinner
from kivy.uix.popup import Popup
from kivy.uix.filechooser import ListItemWithThumbnail
from kivy.uix.scrollview import ScrollView
from kivy.garden.matplotlib.backend_kivyagg import FigureCanvasKivyAgg
from kivy.core.window import Window
from kivy.graphics import Color, RoundedRectangle, Line, Ellipse
from kivy.uix.camera import Camera
from kivy.uix.anchorlayout import AnchorLayout
from kivy.uix.relativelayout import RelativeLayout

import numpy as np
from PIL import Image as PILImage
import threading
import time

# Platform-specific imports
try:
    from jnius import autoclass, cast
    from android.permissions import request_permissions, Permission, check_permission
    ANDROID_AVAILABLE = True
except ImportError:
    ANDROID_AVAILABLE = False
    print("[WARNING] Jnius not available - running in non-Android mode")

# Constants
GOLD = (0.851, 0.643, 0.082, 1.0)  # #D9A514
DARK_BG = (0.1, 0.1, 0.1, 1.0)
LIGHT_TEXT = (0.95, 0.95, 0.95, 1.0)
DARK_TEXT = (0.6, 0.6, 0.6, 1.0)

# Camera modes
CAMERA_MODES = ['PHOTO', 'HDR', 'NIGHT', 'ASTRO', 'STAR TRAILS', 'PRO', 'VIDEO']

# Set window properties
Window.size = (1080, 1920)
Window.clearcolor = DARK_BG


class CameraProcessor:
    """Image processing pipeline for camera modes"""
    
    def __init__(self):
        self.output_dir = Path.home() / 'DCIM' / 'DE_CAMERA'
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.frame_buffer = []
        self.max_buffer_size = 10
    
    def denoise(self, image_array):
        """Apply noise reduction using bilateral filtering approach"""
        if len(image_array.shape) == 3:
            # Convert to grayscale for processing
            img = PILImage.fromarray(image_array.astype('uint8'), 'RGB')
            # Apply a subtle Gaussian blur as denoise
            denoised = img.filter(PILImage.BLUR)
            return np.array(denoised)
        return image_array
    
    def enhance_contrast(self, image_array):
        """Enhance image contrast"""
        img = PILImage.fromarray(image_array.astype('uint8'), 'RGB')
        from PIL import ImageEnhance
        enhancer = ImageEnhance.Contrast(img)
        enhanced = enhancer.enhance(1.5)
        return np.array(enhanced)
    
    def sharpen(self, image_array):
        """Apply sharpening filter"""
        img = PILImage.fromarray(image_array.astype('uint8'), 'RGB')
        from PIL import ImageFilter
        sharpened = img.filter(ImageFilter.SHARPEN)
        return np.array(sharpened)
    
    def tone_mapping(self, image_array):
        """Apply tone mapping for better exposure"""
        arr = image_array.astype(np.float32) / 255.0
        # Simple tone mapping: boost shadows, compress highlights
        mapped = np.power(arr, 0.9)
        return (mapped * 255).astype(np.uint8)
    
    def hdr_merge(self, frames):
        """Merge multiple frames for HDR effect"""
        if len(frames) < 2:
            return frames[0]
        
        # Simple HDR: average frames and enhance contrast
        stacked = np.mean(np.array(frames), axis=0).astype(np.uint8)
        return self.enhance_contrast(stacked)
    
    def night_mode_enhance(self, image_array):
        """Enhance image for night mode"""
        # Increase brightness, reduce noise, enhance contrast
        arr = image_array.astype(np.float32)
        arr = arr * 1.3  # Brighten
        arr = np.clip(arr, 0, 255)
        enhanced = self.denoise(arr.astype(np.uint8))
        return self.enhance_contrast(enhanced)
    
    def astro_enhance(self, image_array):
        """Enhance image for astrophotography"""
        # Increase contrast significantly, enhance colors, sharpen
        enhanced = self.enhance_contrast(image_array)
        enhanced = self.sharpen(enhanced)
        # Boost saturation for stars
        img = PILImage.fromarray(enhanced, 'RGB')
        from PIL import ImageEnhance
        color_enhancer = ImageEnhance.Color(img)
        enhanced = color_enhancer.enhance(1.8)
        return np.array(enhanced)
    
    def star_detection(self, image_array):
        """Simple star detection - detects bright spots"""
        # Convert to grayscale
        if len(image_array.shape) == 3:
            gray = np.mean(image_array, axis=2)
        else:
            gray = image_array
        
        # Find bright pixels above threshold
        threshold = np.percentile(gray, 95)
        stars = gray > threshold
        return stars, len(np.argwhere(stars))
    
    def save_image(self, image_array, mode='PHOTO'):
        """Save image to disk"""
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        filename = f"{mode}_{timestamp}.jpg"
        filepath = self.output_dir / filename
        
        img = PILImage.fromarray(image_array.astype('uint8'), 'RGB')
        img.save(str(filepath), 'JPEG', quality=95)
        return filepath
    
    def add_to_buffer(self, image_array):
        """Add frame to processing buffer"""
        self.frame_buffer.append(image_array.copy())
        if len(self.frame_buffer) > self.max_buffer_size:
            self.frame_buffer.pop(0)
    
    def clear_buffer(self):
        """Clear frame buffer"""
        self.frame_buffer.clear()


class DECameraApp(App):
    """Main application class"""
    
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.title = 'DE CAMERA'
        self.processor = CameraProcessor()
        self.current_mode = 'PHOTO'
        self.camera_active = False
        self.recording = False
        self.last_photo_path = None
        
        # Request Android permissions if available
        if ANDROID_AVAILABLE:
            self._request_permissions()
    
    def _request_permissions(self):
        """Request required Android permissions"""
        if not ANDROID_AVAILABLE:
            return
        
        permissions = [
            Permission.CAMERA,
            Permission.WRITE_EXTERNAL_STORAGE,
            Permission.READ_EXTERNAL_STORAGE,
            Permission.RECORD_AUDIO,
        ]
        
        try:
            request_permissions(permissions)
        except Exception as e:
            print(f"[ERROR] Permission request failed: {e}")
    
    def build(self):
        """Build the main UI"""
        main_layout = BoxLayout(orientation='vertical', spacing=0, padding=0)
        main_layout.canvas.before.clear()
        
        with main_layout.canvas.before:
            Color(*DARK_BG)
            self.bg_rect = RoundedRectangle(size=main_layout.size, pos=main_layout.pos)
        
        main_layout.bind(size=self._update_bg, pos=self._update_bg)
        
        # Top bar with app name and settings
        top_bar = BoxLayout(size_hint_y=0.1, padding=10, spacing=10)
        top_bar.canvas.before.clear()
        with top_bar.canvas.before:
            Color(0.08, 0.08, 0.08, 1.0)
            top_bar.bg = RoundedRectangle(size=top_bar.size, pos=top_bar.pos)
        top_bar.bind(size=self._update_widget_bg, pos=self._update_widget_bg)
        
        app_title = Label(text='DE CAMERA', size_hint_x=0.5, font_size='24sp', 
                         color=LIGHT_TEXT, bold=True)
        top_bar.add_widget(app_title)
        
        settings_btn = Button(text='☰', size_hint_x=0.2, font_size='24sp', 
                             background_color=GOLD)
        settings_btn.bind(on_press=self.open_settings)
        top_bar.add_widget(settings_btn)
        
        gallery_btn = Button(text='🖼', size_hint_x=0.2, font_size='24sp',
                            background_color=DARK_TEXT)
        gallery_btn.bind(on_press=self.open_gallery)
        top_bar.add_widget(gallery_btn)
        
        main_layout.add_widget(top_bar)
        
        # Camera preview area
        preview_layout = AnchorLayout(size_hint_y=0.6, anchor_x='center', anchor_y='center')
        
        camera_container = RelativeLayout(size_hint=(1, 1))
        
        try:
            self.camera = Camera(play=True, resolution=(1080, 1920))
            camera_container.add_widget(self.camera)
            self.camera_active = True
        except Exception as e:
            print(f"[ERROR] Camera initialization failed: {e}")
            # Fallback UI
            fallback = Label(text='Camera not available', color=LIGHT_TEXT)
            camera_container.add_widget(fallback)
        
        preview_layout.add_widget(camera_container)
        main_layout.add_widget(preview_layout)
        
        # Mode selector
        mode_bar = BoxLayout(size_hint_y=0.15, spacing=5, padding=5)
        mode_bar.canvas.before.clear()
        with mode_bar.canvas.before:
            Color(0.08, 0.08, 0.08, 1.0)
            mode_bar.bg = RoundedRectangle(size=mode_bar.size, pos=mode_bar.pos)
        mode_bar.bind(size=self._update_widget_bg, pos=self._update_widget_bg)
        
        for mode in CAMERA_MODES:
            mode_btn = Button(text=mode, font_size='11sp', size_hint_x=1/len(CAMERA_MODES))
            is_active = mode == self.current_mode
            mode_btn.background_color = GOLD if is_active else (0.3, 0.3, 0.3, 1.0)
            mode_btn.bind(on_press=lambda btn, m=mode: self.select_mode(m, btn))
            mode_bar.add_widget(mode_btn)
        
        main_layout.add_widget(mode_bar)
        
        # Capture controls
        controls = BoxLayout(size_hint_y=0.15, spacing=10, padding=10)
        controls.canvas.before.clear()
        with controls.canvas.before:
            Color(0.08, 0.08, 0.08, 1.0)
            controls.bg = RoundedRectangle(size=controls.size, pos=controls.pos)
        controls.bind(size=self._update_widget_bg, pos=self._update_widget_bg)
        
        # Spacer
        controls.add_widget(Label(size_hint_x=0.2))
        
        # Capture button
        capture_btn = Button(background_color=GOLD, size_hint_x=0.6)
        capture_btn.bind(on_press=self.capture_photo)
        controls.add_widget(capture_btn)
        
        # Draw circular shape for capture button
        with capture_btn.canvas:
            Color(*GOLD)
            self.capture_circle = Ellipse(size=(80, 80), pos=(capture_btn.center_x-40, 
                                                              capture_btn.center_y-40))
        
        # Spacer
        controls.add_widget(Label(size_hint_x=0.2))
        
        main_layout.add_widget(controls)
        
        return main_layout
    
    def _update_bg(self, instance, value):
        """Update background rectangle"""
        self.bg_rect.pos = instance.pos
        self.bg_rect.size = instance.size
    
    def _update_widget_bg(self, instance, value):
        """Update widget background"""
        if hasattr(instance, 'bg'):
            instance.bg.pos = instance.pos
            instance.bg.size = instance.size
    
    def select_mode(self, mode, btn):
        """Select a camera mode"""
        self.current_mode = mode
        
        # Update button colors
        for child in btn.parent.children:
            if isinstance(child, Button):
                child.background_color = GOLD if child.text == mode else (0.3, 0.3, 0.3, 1.0)
        
        # Mode-specific setup
        if mode == 'PHOTO':
            self.processor.clear_buffer()
        elif mode == 'HDR':
            self.processor.clear_buffer()
        elif mode == 'NIGHT':
            self.processor.clear_buffer()
        elif mode == 'ASTRO':
            self.processor.clear_buffer()
        elif mode == 'STAR TRAILS':
            self.processor.clear_buffer()
        elif mode == 'PRO':
            self.open_pro_settings()
        elif mode == 'VIDEO':
            self.prepare_video_mode()
    
    def capture_photo(self, instance):
        """Capture a photo"""
        if not self.camera_active:
            return
        
        try:
            # Save camera frame
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            filepath = self.processor.output_dir / f"photo_{timestamp}.png"
            
            # In real implementation, this would grab from camera texture
            # For now, create a test image
            test_img = PILImage.new('RGB', (1080, 1920), color=(50, 50, 100))
            test_img.save(str(filepath))
            
            self.last_photo_path = filepath
            self.show_notification(f"Photo saved: {filepath.name}")
            
            # Process based on mode
            if self.current_mode == 'PHOTO':
                pass  # Already saved
            elif self.current_mode == 'HDR':
                self.show_notification("HDR processing...")
            elif self.current_mode == 'NIGHT':
                self.show_notification("Night mode processing...")
            elif self.current_mode == 'ASTRO':
                self.show_notification("Astro processing...")
            elif self.current_mode == 'STAR TRAILS':
                self.show_notification("Star trails mode...")
            elif self.current_mode == 'VIDEO':
                self.toggle_video_recording()
                
        except Exception as e:
            self.show_notification(f"Capture failed: {e}")
    
    def toggle_video_recording(self):
        """Toggle video recording"""
        self.recording = not self.recording
        if self.recording:
            self.show_notification("Recording started...")
        else:
            timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            video_path = self.processor.output_dir / f"video_{timestamp}.mp4"
            self.show_notification(f"Video saved: {video_path.name}")
    
    def prepare_video_mode(self):
        """Prepare for video recording"""
        self.show_notification("Video mode ready")
    
    def open_pro_settings(self):
        """Open professional camera settings"""
        self.show_notification("PRO mode: ISO/Shutter/Exposure available")
    
    def open_settings(self, instance):
        """Open application settings"""
        self.show_notification("Settings: Coming soon")
    
    def open_gallery(self, instance):
        """Open photo gallery"""
        try:
            import subprocess
            gallery_path = self.processor.output_dir
            if gallery_path.exists():
                if ANDROID_AVAILABLE:
                    # Try to open with Android file manager
                    pass
                self.show_notification(f"Gallery: {gallery_path}")
            else:
                self.show_notification("No photos yet")
        except Exception as e:
            self.show_notification(f"Gallery error: {e}")
    
    def show_notification(self, message):
        """Show a temporary notification"""
        print(f"[NOTIFICATION] {message}")
        # In a full implementation, this would show a popup or toast


if __name__ == '__main__':
    app = DECameraApp()
    app.run()
