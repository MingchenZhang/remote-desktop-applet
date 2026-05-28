# Web RD Applet (Selkies Remote Desktop Client)

A powerful, highly customized Android wrapper designed to serve as an optimized "thin client" for web-based remote desktop environments (like Selkies). 

This application goes beyond a standard `WebView` by transforming into a **Multi-Applet Container**. It allows users to create multiple, completely isolated remote desktop sessions, pinning them to the home screen like native Progressive Web Apps (PWAs), while providing desktop-grade hardware input support and seamless system integration.

---

## Major Features & Implementation Details

### 1. Multi-Applet Container Architecture (Sandboxing)
**Feature:** Users can connect to multiple remote environments (e.g., "Work PC", "Home Server") simultaneously without session bleed. Logging into one environment does not affect the other.
**Implementation:** 
* Utilizes the modern `androidx.webkit.ProfileStore` API.
* When an applet is launched, it receives a unique UUID. The app calls `ProfileStore.getInstance().getOrCreateProfile(uuid)` and applies it via `WebViewCompat.setProfile()`.
* This completely isolates the browser cache, LocalStorage, IndexedDB, and Cookies for each session.

### 2. Native "Recent Apps" Task Isolation
**Feature:** When multiple applets are running, they do not group together under a single app card in the Android "Recent Apps" switcher. Each applet gets its own distinct card with its custom name.
**Implementation:**
* **Launch Intents:** The `ManagerActivity` fires intents with a unique Data URI (`applet://<uuid>`) and the `Intent.FLAG_ACTIVITY_NEW_DOCUMENT` flag.
* **Manifest Config:** `AppletActivity` is declared with `android:documentLaunchMode="intoExisting"`. This ensures Android treats each unique Data URI as a separate task, while cleanly resuming existing sessions if the user taps the icon again (avoiding duplicate processes).
* **Dynamic Naming:** The app uses `Activity.setTaskDescription()` to dynamically rename the Recent Apps card to match the user-provided Applet name.

### 3. Hybrid Back Navigation & Predictive Animations
**Feature:** The app seamlessly bridges the gap between modern Android UI gestures and professional desktop keyboard input.
**Implementation:**
* **OS Gestures:** By default, the `OnBackPressedCallback` is dynamically disabled when the `WebView` is at the root of its history. Combined with `android:enableOnBackInvokedCallback="true"`, this allows modern Android devices to display native, fluid **Predictive Back Animations** when swiping to exit the app.
* **Hardware Interception:** The app listens for physical keyboard events (`KeyEvent.KEYCODE_ESCAPE` and keyboard-originated `KEYCODE_BACK`) in `dispatchKeyEvent`. 
* **The "Stealth Lock":** When a physical ESC key is pressed, it instantly injects the key into the remote session via JS and momentarily *enables* the back callback to "catch" and swallow the OS-level exit command, preventing accidental app closures.

### 4. Automatic Clipboard Synchronization
**Feature:** Users can copy text from an Android app (like a password manager) and immediately paste it into the remote desktop session without triggering annoying browser security prompts.
**Implementation:**
* **Native Bridge:** A `@JavascriptInterface` (`ClipboardInterface`) is registered to read/write directly to the Android OS `ClipboardManager`.
* **JS Shim:** The app injects a JavaScript snippet into every page that overrides the standard W3C `navigator.clipboard` API and mocks the `navigator.permissions.query` API. 
* **Focus Triggers:** The `MainActivity` listens to `onWindowFocusChanged` and dispatches `visibilitychange` and `focus` events to the DOM to force the web app (Selkies) to re-sync its clipboard state the moment the user switches back to the app.

### 5. Context-Aware Key Routing
**Feature:** System-level shortcuts are routed to the remote machine by default, but standard text shortcuts (like `Ctrl+C`) work naturally when the user is typing in a local side-menu or text area.
**Implementation:**
* **Focus Tracking:** A `@JavascriptInterface` (`FocusInterface`) combined with a global JS listener tracks when an `<input>`, `<textarea>`, or `contenteditable` element gains focus.
* **Hybrid Routing:** When a text area is focused, the app bypasses its aggressive interception for standard chords, allowing the `WebView` to handle them natively.
* **JS Injection:** For "System" keys (Meta, F-keys, Tab) or when no input is focused, the app uses high-fidelity JS injection to ensure the remote machine receives perfect W3C `KeyboardEvent` properties (`code`, `key`, `ctrlKey`, etc.).
* **Configurable:** Includes a `USE_KEY_PRESS_JS_INJECTION` flag to toggle between native and injected input modes.

### 6. Edge-to-Edge System UI
**Feature:** A beautiful, immersive UI that respects the device's notch, status bar, and gesture navigation pill in both Light and Dark modes.
**Implementation:**
* **Material 3 Theme:** Uses `Theme.Material3.DayNight.NoActionBar`.
* **Native Insets:** Calls `enableEdgeToEdge()` on startup.
* **Dynamic Padding:** The `ManagerActivity` and `AppletActivity` use `ViewCompat.setOnApplyWindowInsetsListener` to dynamically apply padding to container wrappers, ensuring content never overlaps with transparent system bars.

### 7. Remote Environment Reliability
**Feature:** Designed specifically to handle the quirks of WebRTC-based remote desktop clients.
**Implementation:**
* **WebRTC Support:** Native handling for `PermissionRequest` to allow microphone/camera access.
* **SSL Handling:** A custom `AlertDialog` overrides default `WebView` behavior, allowing users to safely "green light" self-signed certificates common in homelab environments.
* **Anti-Hibernation:** Overrides `onDestroy()` to explicitly clear the volatile RAM cache and load an empty page. This forcefully terminates the Chromium renderer process when the app is swiped away, ensuring a completely fresh load on the next start while preserving persistent login cookies on disk.

### 8. Dynamic Favicon Extraction
**Feature:** Home screen shortcuts automatically use the favicon of the remote desktop site, providing a native "App" look and feel for each connection.
**Implementation:**
* **Headless Capture:** When pinning an applet, the `ManagerActivity` spawns a headless `WebView` to fetch the target URL and intercept the icon via `WebChromeClient.onReceivedIcon`.
* **Pausable 10s Timeout:** Includes a 10-second timeout mechanism to ensure the UI remains responsive. The timeout is automatically paused if the site requires HTTP Basic Authentication, allowing the user to provide credentials before the icon is captured.
* **Silent SSL Bypass:** Specifically configured to ignore SSL errors during icon extraction, ensuring that homelab environments with self-signed certificates still receive custom shortcut icons.