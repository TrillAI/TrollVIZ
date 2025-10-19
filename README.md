```markdown
# TrillViz — Hybrid Offline/Online Face Reenactment & Webcam Emulation

What this provides
- Hybrid backend manager: ML Kit, TFLite (on-device reenactment), Remote API, Hybrid.
- Offline-capable on-device reenactment TFLite backend (GPU/NNAPI optional).
- Save processed frames as images and record processed output to MP4.
- MJPEG & WebRTC streaming preserved for webcam emulation.
- Performance optimizations: reduced processing resolution, frame-drop when busy, configurable max FPS.

Important
- You must supply your own reenactment model(s) for high-quality realism. See "TFLite model format" below.
- Always obtain consent for using other people's images or likenesses.

TFLite model format (recommended)
- Input: one tensor or multiple inputs (for example):
  - Live frame: [1,H,W,3] float32 (RGB), normalized to [-1,1] or [0,1] (match model)
  - Source photo: [1,H,W,3] float32 (RGB)
  - Optional landmark tensors / heatmaps (if required by model)
- Output: [1,H,W,3] float32 image (or [1,H,W,4] with alpha)
- Use GPU delegate where possible for speed. Small/optimized mobile models give the best real-time performance.

Where to get or convert models
- Convert research models (First-Order-Motion, FaceSwap, etc.) to a mobile-friendly, quantized / pruned TFLite if you want on-device inference. This is advanced and may require PyTorch->ONNX->TF->TFLite conversions.
- You can also train lightweight conditional image-to-image networks that take landmarks + source/driver images and output a reenacted face.

Build & run
1. Add the provided files to your app module.
2. Put your model(s) in app/src/main/assets/models/.
3. Run on a real device. Grant CAMERA, INTERNET and (if needed) WRITE_EXTERNAL_STORAGE permissions or use MediaStore flow.
4. In Settings, pick backend: TFLite (offline) or Remote API (online) or Hybrid.

Ethics & legal
- Use responsibly and legally. Do not impersonate or deceive.
```