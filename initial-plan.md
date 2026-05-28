# Technical Proposal: Custom Android Client for Selkies Remote Desktop

## 1. Executive Summary
This proposal outlines the architecture and implementation strategy for a custom Android application designed to serve as a dedicated client for Selkies Remote Desktop. Standard mobile browsers fail to adequately route complex hardware inputs (such as system-level shortcuts and relative mouse movements) to WebRTC-based web clients. By utilizing a highly configured Android `WebView` wrapper, this application will bypass native OS input interception, enabling a desktop-grade experience on Android tablets and high-performance mobile devices.

## 2. Architecture & Technology Stack
The application will be built using Kotlin and rely on the native Android SDK. 

* **UI Layer:** A single-activity architecture utilizing a `FrameLayout` to maximize screen real estate without invoking the problematic Android Immersive Mode APIs.
* **Rendering Engine:** Android's native Chromium-based `WebView`.
* **Input Bridge:** Custom event interceptors mapped to JavaScript injection (`evaluateJavascript`) to bridge Android OS hardware events directly into the Selkies DOM.
* **Permissions:** Minimal manifest permissions (Internet, Network State) with runtime handling for WebRTC requirements (Microphone, if audio routing is eventually desired).

## 3. Core Features & Implementation Strategy

### 3.1. Viewport Configuration
The application will initialize a full-screen `WebView` locked to the standard Android layout bounds to prevent coordinate scaling bugs.
* **Configuration Handling:** The `AndroidManifest.xml` will suppress default OS behavior for screen rotation and peripheral connections (`configChanges="orientation|screenSize|screenLayout|keyboardHidden"`). This prevents the Activity from being destroyed and keeps the WebRTC socket alive during device state changes.
* **Chromium Tuning:** Web settings will be explicitly configured to support DOM storage, JavaScript execution, and media playback without user gestures, which are strictly required for Selkies to bootstrap the remote stream.

### 3.2. Low-Level Hardware Input Interception
Standard Android behavior swallows system-level shortcut keys before they reach the browser layer. The core of this implementation relies on overriding `dispatchKeyEvent` at the `Activity` level.
* **Modifier Key Mapping:** Critical keys including `KEYCODE_CTRL_LEFT/RIGHT`, `KEYCODE_ALT_LEFT/RIGHT`, and physical meta keys (`KEYCODE_META_LEFT/RIGHT`) will be captured natively.
* **System Action Bypass:** Keys like `KEYCODE_ESCAPE` (often mapped to Android's "Back" action) will be intercepted and consumed by returning `true` in the event dispatcher.
* **Event Injection:** Captured Android `KeyEvent` data will be mapped to W3C-compliant `KeyboardEvent` standards and injected into the Selkies webpage via immediate JavaScript execution, ensuring low-latency keydown/keyup states.

### 3.3. Mouse and Touch Event Routing
To support complex inputs while avoiding the 90-degree axis rotation bug common in Android viewport resizing:
* **Relative Mouse Movement:** Standard touch events will be passed to the Chromium layer naturally.
* **Viewport Locking:** By explicitly rejecting web-triggered fullscreen requests via a custom `WebChromeClient`, the application forces Chromium to respect the native Android coordinate space, ensuring the X/Y axes remain true to physical inputs.

## 4. Technical Challenges & Mitigations

| Challenge | Impact | Proposed Mitigation |
| :--- | :--- | :--- |
| **90-Degree Rotation Bug** | Mouse axes swap (X becomes Y) when standard fullscreen or immersive mode is triggered. | Maintain a standard layout. Intercept and block HTML5 fullscreen requests in the `WebChromeClient`. |
| **Meta Key Interception** | Android OS intercepts the physical Meta/Windows key for system-level actions (e.g., launching Assistant). | Explicitly capture `KEYCODE_META` at the earliest point in the Activity lifecycle (`dispatchKeyEvent`) and consume the event before propagation. |
| **WebRTC Lifecycle** | Android destroys the app instance when a physical keyboard is plugged in or the tablet rotates, severing the connection. | Apply strict `android:configChanges` parameters in the manifest to maintain the UI state manually. |

## 5. Testing & Validation Strategy
Testing will focus on environments mimicking a thin-client setup:
1.  **Hardware Keyboard Validation:** Verifying that complex chords (e.g., `Ctrl` + `Alt` + `Delete` or `Meta` + `R`) successfully propagate to the remote desktop without triggering local Android OS shortcuts.
2.  **Peripheral Connection:** Ensuring that hot-swapping a Bluetooth or USB-C mouse/keyboard does not tear down the WebRTC connection.
3.  **Coordinate Mapping:** Verifying that touch-and-drag and physical mouse tracking remain perfectly aligned with the remote cursor, specifically testing across orientation changes. 

## 6. Next Steps
1.  Initialize the Android Studio project with an empty Activity.
2.  Implement the baseline `WebView` and load the target Selkies instance URL.
3.  Implement the `dispatchKeyEvent` override and JS injection bridge to test keyboard latency.
4.  Refine mouse/touch handling and verify the coordinate space stability.
