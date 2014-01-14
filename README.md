gl2cameraeye
============

GLES2 experiments: Video capture in a GPU Surface Texture and from there retrieve
the data into Java. The shaders implement a simple canvas to unity square mapping
and 1-to-1 color correspondence, possibly with flips and rotations.

A couple of notes -- mostly to-remember's.

Compile
-------
Simply run "ant debug" or "ant release" and, if "ant" and jdk are correctly 
mapped in your system, voila, an apk will apear in bin/GL2CameraEye-debug.apk,
or -release.apk.

Install
-------
With the Android SDK correctly installed:

sdk/platform-tools/adb   install bin/GL2CameraEye-debug.apk

Not working? Developer mode and USB debugging must be enabled in the device. When
connected, executing "adb devices -l" should show it up.