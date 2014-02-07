#!/bin/bash
CMD="ant debug && /p/adt/sdk/platform-tools/adb install -r ./bin/GL2CameraEye-debug.apk "

echo $CMD
eval $CMD

