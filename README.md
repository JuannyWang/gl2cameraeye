gl2cameraeye
============

GLES2 experiments: Video capture in a GLSurfaceView via a GLSurfaceView.Renderer,
using Android API 18. The shaders implement a simple canvas to unity square mapping
and 1-to-1 color correspondence, possibly with flips and rotations.

A couple of notes -- mostly to-remember's.

Compile
-------
Simply run `ant debug` or `ant release` and, if ant and jdk are correctly 
mapped in your system, voila, an apk will apear in bin/GL2CameraEye-debug.apk,
or -release.apk.

Install
-------
With the Android SDK correctly installed:

<code>
sdk/platform-tools/adb   install bin/GL2CameraEye-debug.apk
</code>

Not working? Developer mode and USB debugging must be enabled in the device. When
connected, executing `adb devices -l` should show it up.
