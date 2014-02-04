#!/bin/bash
CMD="ant debug && /p/adt-bundle-linux-x86_64-20130514/sdk/platform-tools/adb install -r ./bin/GL2CameraEye-debug.apk "

echo $CMD
eval $CMD

