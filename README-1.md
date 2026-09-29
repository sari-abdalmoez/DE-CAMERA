# DE CAMEA

Professional Camera App for Android - Built with Python, Kivy, and Pyjnius

## Features

- **eal Camera Preview**: Live camera feed from your Android device
- **Camera Modes**:
  - **PHOTO**: Standard high-quality photo capture
  - **HDR**: Multi-frame processing for better dynamic range
  - **NIHT**: Night mode with noise reduction and brightness enhancement
  - **ASTRO**: Astrophotography mode with star enhancement
  - **STAR TRAILS**: Frame stacking for star trail photography
  - **PRO**: Professional controls (ISO, shutter speed, exposure, focus, white balance)
  - **VIDEO**: Video recording with audio
- **Image Processing Pipeline**:
  - Denoise
  - Contrast enhancement
  - Sharpening
  - Tone mapping
  - Multi-frame stacking
  - Star detection
- **allery**: View and manage captured photos/videos
- **Permissions**: Proper Android permission handling
- **ARM64 Optimized**: Built for modern Android devices

## Project Structure

```
DE-CAMERA/
├── main.py              # Main application code
├── requirements.txt     # Python dependencies
├── buildozer.spec       # Buildozer configuration for APK build
├── README.md            # This file
└── .github/workflows/
    └── build.yml        # itHub Actions CI/CD workflow
```

## Requirements

### Local Development
- Python 3.11+
- Kivy 2.2.1
- Buildozer
- Android SDK/NDK
- Java Development Kit (JDK 11+)

### For Building APK
- GitHub Account (for automated builds)
- OR Local Buildozer setup

## Installation & Build

### Option 1: GitHub Actions (Recommended)

1. **Create a new GitHub repository**
   ```bash
   git init
   git add .
   git commit -m "Initial commit: DE CAMERA"
   git remote add origin https://github.com/YOUR_USERNAME/DE-CAMERA.git
   git branch -M main
   git push -u origin main
   ```

2. **Enable Actions**
   - Go to Settings → Actions → Allow all actions

3. **Trigger Build**
   - Push a commit: `git push`
   - Or manually trigger from Actions tab → "Build APK" → "Run workflow"

4. **Download APK**
   - Go to Actions → Latest workflow run → Artifacts → DE-CAMERA-APK
   - Download the `.apk` file

### Option 2: Local Build

1. **Install dependencies**
   ```bash
   python -m pip install buildozer cython pyjnius
   pip install -r requirements.txt
   ```

2. **Setup Android SDK/NDK**
   ```bash
   # Follow Buildozer documentation for your OS
   # Buildozer will guide you through setup on first run
   ```

3. **Build APK**
   ```bash
   buildozer android debug
   ```

4. **Find APK**
   ```bash
   ls bin/*.apk
   ```

## Installation on Device

### Via ADB (Debug APK)
```bash
adb install -r bin/decamera-1.0.0-debug.apk
```

### Via File Transfer
1. Copy the APK to your Android device
2. Open file manager
3. Tap the APK file
4. Follow installation prompts

### Via GitHub Release
- Download APK from GitHub Releases
- Install as above

## Usage

1. **Launch App**
   - Grant required permissions (Camera, Storage, Microphone, Location)
   - Camera preview should appear

2. **Select Mode**
   - Tap mode buttons at bottom: PHOTO, HDR, NIGHT, ASTRO, STAR TRAILS, PRO, VIDEO

3. **Capture**
   - Tap the large gold circular button to capture

4. **View Gallery**
   - Tap gallery icon (🖼) to view saved photos/videos
   - Photos saved to: `/DCIM/DE_CAMERA/`

5. **Settings**
   - Tap menu icon (☰) for app settings

## Technical Details

### Camera Modes Implementation Status

| Mode | Status | Details |
|------|--------|---------|
| PHOTO | ✓ Complete | Captures standard photos |
| HDR | ✓ Complete | Multi-frame merge ready |
| NIGHT | ✓ Complete | Denoise + brightness enhancement |
| ASTRO | ✓ Complete | Contrast boost + star detection |
| STAR TRAILS | ✓ Complete | Frame accumulation pipeline |
| PRO | ⚠ Structure Ready | Requires Camera2 API (Pyjnius) |
| VIDEO | ✓ Complete | Basic video recording |

### Image Processing

**Current Implementation (Python/Pillow/NumPy):**
- ✓ Denoise (bilateral filter approximation)
- ✓ Contrast enhancement
- ✓ Sharpening
- ✓ Tone mapping
- ✓ HDR merge (frame averaging + contrast)
- ✓ Star detection (threshold-based)

**Future Enhancements (Camera2 API via Pyjnius):**
- [ ] RAW capture (DNG format)
- [ ] Real-time ISO/Shutter control
- [ ] Auto white balance detection
- [ ] Advanced autofocus modes
- [ ] Stabilization (EIS)

### Platform Support

- **Target SDK**: Android 12 (API 31)
- **Min SDK**: Android 7.0 (API 24)
- **Architecture**: ARM64-v8a (64-bit)
- **Python**: 3.11
- **Kivy**: 2.2.1
- **Buildozer**: Latest stable

## Permissions

The app requests the following Android permissions:

- `CAMERA` - Access device camera
- `WRITE_EXTERNAL_STORAGE` - Save photos/videos
- `READ_EXTERNAL_STORAGE` - Access photo library
- `RECORD_AUDIO` - Record video audio
- `ACCESS_FINE_LOCATION` - Optional for geotagging

## Troubleshooting

### APK Build Fails

1. **"Failed to find package 'tools'"**
   - The GitHub workflow handles this with manual SDK setup
   - For local builds, use `buildozer android debug --require-latest-gradle`

2. **Camera Not Working**
   - Ensure camera permissions are granted
   - Check device has camera hardware
   - Some emulators don't support camera

3. **Out of Memory**
   - Disable HDR/ASTRO modes on low-RAM devices
   - Reduce image resolution in buildozer.spec

4. **Storage Permissions Denied**
   - Make sure to grant permissions when prompted
   - Check Android Settings → Apps → DE CAMERA → Permissions

## Development

### Adding New Features

1. **Modify `main.py`**
   - Add new UI elements in `DECameraApp.build()`
   - Add processing functions in `CameraProcessor` class

2. **Add Dependencies**
   - Update `requirements.txt`
   - Update `buildozer.spec` requirements field

3. **Test Locally**
   - `python main.py` (on Linux/Mac with display)

4. **Build APK**
   - `buildozer android debug`

### Using Camera2 API (Advanced)

For professional features (RAW, advanced controls), use Pyjnius:

```python
from jnius import autoclass

CameraManager = autoclass('android.hardware.camera2.CameraManager')
CameraCharacteristics = autoclass('android.hardware.camera2.CameraCharacteristics')

# Access Camera2 features
```

See main.py for Pyjnius import structure.

## Performance Notes

- **Night/Astro modes** - Can be slow on older devices (>2-3 seconds processing)
- **Star Trails** - Buffers up to 10 frames in memory
- **Video** - Limited to device capabilities, no hardware encoding currently

## Future Roadmap

- [ ] Camera2 API integration for professional controls
- [ ] RAW/DNG capture support
- [ ] Real-time filters
- [ ] Batch processing
- [ ] Cloud storage integration
- [ ] Advanced color grading
- [ ] Histogram display
- [ ] Focus peaking
- [ ] Zebra pattern for exposure
- [ ] Timelapse mode

## License

Open source - Modify and distribute freely

## Credits

Built by Sari Abdallah  
Made with ❤️ using Python, Kivy, and Android APIs

## Support

For issues and feature requests:
- GitHub Issues: [Create an issue]
- Email: [Your contact]
