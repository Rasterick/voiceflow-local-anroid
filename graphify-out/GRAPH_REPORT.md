# Graph Report - voiceflow-local-android  (2026-10-23)

## Corpus Check
- Corpus is ~13,998 words - fits in a single context window. You may not need a graph.

## Summary
- 268 nodes · 446 edges · 25 communities (14 shown, 11 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 3 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17

## God Nodes (most connected - your core abstractions)
1. `OverlayPillService` - 26 edges
2. `SpeechEngine` - 19 edges
3. `DictationAccessibilityService` - 15 edges
4. `RecognitionListener` - 13 edges
5. `LlmStructuringEngine` - 12 edges
6. `DictationState` - 9 edges
7. `MainScreen()` - 8 edges
8. `MainActivity` - 7 edges
9. `DraggableFrameLayout` - 6 edges
10. `WriteMyWordsTheme()` - 5 edges

## Surprising Connections (you probably didn't know these)
- `OverlayPillService` --references--> `SpeechEngine`  [EXTRACTED]
  app/src/main/java/com/voiceflow/local/service/OverlayPillService.kt → app/src/main/java/com/voiceflow/local/engine/SpeechEngine.kt
- `MainScreenPreview()` --calls--> `WriteMyWordsTheme()`  [INFERRED]
  app/src/main/java/com/voiceflow/local/ui/main/MainScreen.kt → app/src/main/java/com/voiceflow/local/theme/Theme.kt
- `PillCapsuleOverlay()` --references--> `DictationState`  [EXTRACTED]
  app/src/main/java/com/voiceflow/local/service/OverlayPillService.kt → app/src/main/java/com/voiceflow/local/engine/SpeechEngine.kt

## Import Cycles
- None detected.

## Communities (25 total, 11 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (33): animatecontentsize, animatedvisibility, animatefloatasstate, check, composeview, decelerateinterpolator, detecttapgestures, fadein (+25 more)

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (4): base64, subprocess, time, zipfile

### Community 2 - "Community 2"
Cohesion: 0.10
Nodes (20): activityresultcontracts, Bundle, MainActivity, WriteMyWordsTheme(), MainScreen(), MainScreenPreview(), PermissionRow(), build (+12 more)

### Community 3 - "Community 3"
Cohesion: 0.16
Nodes (6): Bundle, SpeechEngine, RecognitionListener, ByteArray, polished, rawDraft

### Community 4 - "Community 4"
Cohesion: 0.09
Nodes (22): accessibilitymanager, accessibilityserviceinfo, alignment, background, border, circleshape, clickable, clip (+14 more)

### Community 5 - "Community 5"
Cohesion: 0.15
Nodes (10): AccessibilityEvent, AccessibilityNodeInfo, AccessibilityService, accessibilitywindowinfo, DictationAccessibilityService, Context, bundle, clipboardmanager (+2 more)

### Community 6 - "Community 6"
Cohesion: 0.20
Nodes (8): DraggableFrameLayout, OverlayPillService, FrameLayout, LifecycleOwner, MotionEvent, SavedStateRegistryOwner, View, WindowManager

### Community 7 - "Community 7"
Cohesion: 0.11
Nodes (17): DictationState, IDLE, LISTENING, PROCESSING, SUCCESS, DockEdge, LEFT, RIGHT (+9 more)

### Community 8 - "Community 8"
Cohesion: 0.24
Nodes (6): Context, LlmStructuringEngine, dispatchers, file, LlmInference, withcontext

### Community 9 - "Community 9"
Cohesion: 0.14
Nodes (10): graphify_analyze, graphify_build, graphify_cluster, graphify_detect, graphify_export, graphify_extract, graphify_report, json (+2 more)

### Community 10 - "Community 10"
Cohesion: 0.38
Nodes (5): Context, VocabularyManager, VocabularyProfile, log, userdictionary

### Community 11 - "Community 11"
Cohesion: 0.33
Nodes (5): fontfamily, fontweight, sp, textstyle, typography

### Community 12 - "Community 12"
Cohesion: 0.70
Nodes (4): gradlew script, die(), save(), warn()

### Community 13 - "Community 13"
Cohesion: 0.67
Nodes (3): Main, NavKey, serializable

## Knowledge Gaps
- **6 isolated node(s):** `IDLE`, `LISTENING`, `PROCESSING`, `SUCCESS`, `LEFT` (+1 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 115 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `OverlayPillService` connect `Community 6` to `Community 0`, `Community 2`, `Community 3`, `Community 4`, `Community 14`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Why does `SpeechEngine` connect `Community 3` to `Community 0`, `Community 6`, `Community 7`?**
  _High betweenness centrality (0.097) - this node is a cross-community bridge._
- **Why does `DictationAccessibilityService` connect `Community 5` to `Community 4`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **What connects `IDLE`, `LISTENING`, `PROCESSING` to the rest of the system?**
  _6 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.058823529411764705 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.1111111111111111 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.09971509971509972 - nodes in this community are weakly interconnected._