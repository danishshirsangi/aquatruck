# AquaTrack: Water Transport Fleet & Driver Management

A comprehensive Android application for commercial water delivery services operating a fleet of 5 tanker trucks. Features continuous background GPS tracking even when closed, daily driver selfie attendance check-in/out, secure delivery proof capture with GPS stamping, and a PIN-protected Fleet Manager portal with interactive route visualization and stop analytics.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions were confirmed during the clarification interview:

- **Role Access Mode**: Role-switching architecture between **Driver Mode** (Truck 1–5 selection, attendance, voice deliveries) and **Manager Portal**, protected by a secure 4-digit manager PIN (`1234` default, configurable).
- **Visualization Strategy**: Rich interactive map canvas supporting live truck pins, polyline route tracing, stop markers with duration tooltips, and trip replay controls.
- **Proof of Delivery (POD) via Voice Note**: Simplified voice-first architecture for drivers. Drivers press a large microphone button to record an audio note (e.g., "We delivered here"), which automatically attaches exact GPS coordinates, duration, and timestamp.
- **Background Persistence**: Android Foreground Service with high-priority sticky notification (`Ongoing Water Delivery Shift`) ensuring uninterrupted background location logging when the app is closed or removed from recents.

---

## 1. Overview & Core Concept

Commercial water transport requires high accountability: ensuring drivers are on duty, tracking expensive tanker movements, detecting unauthorized or excessive stops, and verifying delivery quantities to residential or industrial customers.

### Key Value Delivered
- **5 Dedicated Trucks**: Pre-configured fleet (Truck #01 "Hydro 1" through Truck #05 "Hydro 5") with capacities (e.g., 5,000L to 15,000L tankers), assigned drivers, and real-time status (Idle, En Route, Discharging, Off Duty).
- **Driver Attendance Gate**: Shift starts only after a verification selfie is captured with front camera. Tracking automatically starts as a background service on check-in and terminates cleanly when the driver checks out.
- **Resilient Background Tracking**: A dedicated Android `ForegroundService` with notification channel and `WakeLock`/`LocationListener` logs breadcrumb coordinates into Room database every 10–15 seconds (with smart stop detection when stationary for >3 minutes).
- **Delivery Proof Capture**: Easy 1-tap POD screen for drivers to snap tank meter or delivery receipt photos, record delivery notes and volume, and immediately sync with the vehicle's log.
- **Manager Fleet Command Center**: Live fleet status cards, filterable trip histories, stop duration breakdowns, attendance inspection gallery, and route map inspection.

---

## 2. User Experience & Visual Design

### Key User Flows

```
[Driver Flow]
Select Truck (1-5) ──> Selfie Attendance ──> Start Shift (GPS Service Starts)
                              │
                              ├──> Live Shift HUD (Odometer, Current Stop, Speed)
                              ├──> New Delivery Proof (Photo + Volume + GPS Notes)
                              └──> End Shift Checkout ──> (GPS Service Stops)

[Manager Flow]
Switch Role (Enter PIN: 1234) ──> Fleet Dashboard (5 Trucks Live Status)
                              │
                              ├──> Interactive Live Map (Vehicle Pins & Clusters)
                              ├──> Trip & Stop History (Polyline Route + Stop Durations)
                              ├──> Attendance Audits (Selfie Photos + Check-in Timestamps)
                              └──> Delivery Proofs Review (Photos + Volumes + Notes)
```

### Visual Identity & Theme
- **Theme Concept**: *Industrial Aquatic Precision* — modern logistics command style blending deep navy maritime tones with vibrant turquoise and warning amber indicators.
- **Color Palette**:
  - Primary / Marine: Deep Oceanic Navy (`#0A192F` / `#1E3A8A`)
  - Accent / Water Brand: Vivid Cyan (`#00BCD4` / `#06B6D4`)
  - Active Shift: Emerald Green (`#10B981`)
  - Warning / Stop Alert: Amber Orange (`#F59E0B`)
  - Surface Background: Clean Light Slate (`#F8FAFC`) with dark navy card accents
- **Typography & Hierarchy**: Bold tabular numerals for odometer, coordinates, and delivery volumes; crisp M3 medium headers for rapid driver glances in outdoor sunlight.
- **Tactile Feedback**: 48dp+ high-contrast buttons designed for one-handed cab operation, camera preview viewfinders with face guidelines, and animated ripple indicators for active GPS transmission.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Foreground Service vs WorkManager for Tracking
- **Chosen Approach**: Native Android `ForegroundService` with `FOREGROUND_SERVICE_LOCATION` permission, ongoing notification, and periodic GPS updates.
- **Why**: Standard `WorkManager` or background alarms can be delayed by Android Doze mode or aggressive battery savers. A Foreground Service with a persistent notification is the Android standard guarantee for uninterrupted live tracking while on active duty.
- **Graceful Termination**: The service lifecycle is bound to shift state—it starts automatically upon selfie attendance check-in and immediately ceases upon driver checkout.

### Decision 2: Hybrid GPS + Realistic Fleet Simulator
- **Chosen Approach**: Dual-mode location engine. When hardware GPS is available, it streams real device coordinates. For testing the 5-truck fleet on emulator or desktop streaming environments, it includes a realistic route simulation engine generating realistic movement, transit between water reservoirs/customers, and realistic traffic stops.
- **Why**: Ensures immediate, impressive visual fidelity and testability across all 5 trucks simultaneously in the streaming emulator, while remaining fully capable of real hardware GPS recording.

### Decision 3: Local SQLite (Room) Architecture with Complete Data Export
- **Chosen Approach**: Full offline-first Room database storing trucks, attendance logs with photo bitmaps/files, delivery proofs, breadcrumb coordinates, and stop events.
- **Why**: Zero network dropouts in remote water delivery areas; instant UI responsiveness; data remains 100% intact even across app restarts. Includes a JSON/CSV data export tool for manager records.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          User Interface (M3)                           │
│  ┌──────────────────────────────┐    ┌──────────────────────────────┐  │
│  │         Driver Mode          │    │    Manager Command Center    │  │
│  │ • Truck Selector (1-5)       │    │ • Fleet Overview Grid (5)    │  │
│  │ • Selfie Attendance Camera   │    │ • Interactive Route Map      │  │
│  │ • Active Shift HUD           │    │ • Stop Duration Log List     │  │
│  │ • Delivery Proof Submission  │    │ • Attendance & Proof Audit   │  │
│  └──────────────┬───────────────┘    └──────────────┬───────────────┘  │
└─────────────────┼───────────────────────────────────┼──────────────────┘
                  ▼                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        FleetViewModel & State                          │
│     • Active Shift State    • Selected Truck    • Manager Auth PIN     │
│     • Live Coordinates Stream                  • Delivery Proof Flow   │
└─────────────────┬───────────────────────────────────▲──────────────────┘
                  │                                   │
┌─────────────────┴───────────────────┐               │
│   LocationTrackingService           │               │
│   (Android Foreground Service)      │               │
│   • Persistent Notification         │               │
│   • Breadcrumb Generator (10s)      │               │
│   • Stop Detector (>3 min idle)     │               │
└─────────────────┬───────────────────┘               │
                  ▼                                   │
┌─────────────────────────────────────────────────────┴──────────────────┐
│                   Room Database (Local Persistence)                    │
│   • TruckEntity          : ID, Plate, DriverName, Capacity, Status     │
│   • AttendanceRecord     : TruckId, SelfieUri, CheckInTime, CheckOut   │
│   • LocationBreadcrumb   : TruckId, Lat, Lng, Speed, Timestamp         │
│   • StopLog              : TruckId, Lat, Lng, StartTime, EndTime, Address│
│   • DeliveryProof        : TruckId, PhotoUri, Volume, GPS, Notes, Time │
└────────────────────────────────────────────────────────────────────────┘
```

### Proposed Changes

#### 1. Manifest & Permissions
- Add permissions:
  - `android.permission.ACCESS_FINE_LOCATION`
  - `android.permission.ACCESS_COARSE_LOCATION`
  - `android.permission.ACCESS_BACKGROUND_LOCATION`
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_LOCATION`
  - `android.permission.POST_NOTIFICATIONS`
  - `android.permission.CAMERA`
- Declare `LocationTrackingService` with `android:foregroundServiceType="location"`.

#### 2. Gradle Dependencies (`build.gradle.kts`)
- Enable `play-services-location`, `coil-compose`, `navigation-compose`, and ensure Room dependencies are active.

#### 3. Core Modules
- **Data Models & Database**:
  - `TruckEntity`, `AttendanceEntity`, `LocationPointEntity`, `StopEntity`, `DeliveryProofEntity`.
  - `AppDatabase` & DAOs with reactive Kotlin `Flow` queries.
- **Background Service**:
  - `LocationTrackingService.kt`: Notification builder, location polling, stop detection algorithm, broadcast/database updates.
- **Interactive UI Components**:
  - `InteractiveFleetMap`: Custom canvas-rendered interactive vector map showing roads, animated truck markers, route breadcrumbs, stop clusters, pan/zoom, and route replays.
  - `SelfieCameraView`: Camera launcher / selfie preview with face framing for driver attendance.
  - `DeliveryProofDialog`: Photo picker/camera capture, water gallon/liter input, GPS geotag badge, customer notes.
  - `ManagerPortal`: Fleet overview, live location tracking tab, stop history tab, attendance log review, delivery proof photo gallery.
  - `DriverPortal`: Truck selection, clock in/out with selfie, active route progress, upload proof button.

---

## 5. Verification Plan

1. **Service & Notification Verification**:
   - Verify `compile_applet` passes without errors.
   - Start shift with Truck #1; check that Foreground Service starts with active notification.
2. **Attendance & Camera Flow**:
   - Capture attendance selfie, verify timestamp and driver record created in Room.
3. **Location & History Tracking**:
   - Verify breadcrumb points are continuously stored and stop points are automatically logged.
   - Navigate to Manager Portal (PIN: `1234`), open Route History and verify route lines and stop markers render accurately.
4. **Delivery Proofs**:
   - Submit a water delivery proof with photo and notes; verify immediate appearance in Manager Proofs review.
