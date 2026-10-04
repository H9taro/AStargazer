# Camera Architecture

AStargazer uses Camera2BurstSession as the primary capture engine.

## Do not use

CameraX ImageCapture for:

- Interval shooting
- Dark frame shooting
- Test shooting
- Long exposure capture

Reason:

CameraX ImageCapture caused severe latency:
- 0.25s exposure -> ~3.7s per frame
- 30s exposure -> over 200s latency

## Use

Camera2BurstSession.captureSingleFrame()

for:

- Test shooting
- Dark frame shooting

Camera2BurstSession.startRepeatingCapture()

for:

- Interval shooting

## Verified

Device:
SH-M26

Verified:

- 0.25s exposure
- 30s exposure
- ISO 400
- ISO 3200

Actual exposure values verified using CaptureResult metadata.

## Design principle

CameraX is Preview only.

All image acquisition must be performed through Camera2BurstSession.