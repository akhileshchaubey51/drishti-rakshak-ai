# SmartHomeSecurity — Minimal Live Object Detection

- Opens camera automatically after permission.
- Displays only the live camera and object bounding boxes with labels and confidence.
- Hides system status/navigation bars while active.
- Silently saves person snapshots to private app storage with a 10-second cooldown.

## Add your model
The original project ZIP did not contain the binary model. Copy your existing `efficientdet-lite0.tflite` into `app/src/main/assets/efficientdet-lite0.tflite`. The `PUT_MODEL_HERE.txt` file is only a reminder, not the model.

Open this folder in Android Studio, sync Gradle, and run on a real phone. This ZIP does not include the Gradle wrapper or the model binary.
